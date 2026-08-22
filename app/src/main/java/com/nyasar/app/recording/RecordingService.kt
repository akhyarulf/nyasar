package com.nyasar.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nyasar.app.MainActivity
import com.nyasar.app.R
import com.nyasar.app.data.db.ActivityDao
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.ActivityPointEntity
import com.nyasar.app.data.db.ActivityStatus
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.navigation.GpsFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Real foreground service for recording (replaces the previous
 * NavigationService, which only showed a notification and never actually
 * collected location — see audit). This is the piece that makes recording
 * survive screen-off / app-minimized, per spec's Background GPS section.
 *
 * Design: bound + started service. Started so it's independent of any
 * Activity/ViewModel lifecycle (keeps running if the UI unbinds while the
 * user has the app minimized). Bound so RecordingViewModel can observe
 * [state] directly without going through a broadcast/DB-polling roundtrip.
 *
 * GpsFix collection lives here now, not in a ViewModel's viewModelScope —
 * that was the actual reason background recording/navigation didn't work
 * before. NavigationEngine (route-matching) can subscribe to the same fix
 * stream via [addFixListener] so navigation + recording run simultaneously
 * off one GPS source, per spec (they are not mutually exclusive).
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "nyasar_recording"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.nyasar.app.recording.START"
        const val ACTION_PAUSE = "com.nyasar.app.recording.PAUSE"
        const val ACTION_RESUME = "com.nyasar.app.recording.RESUME"
        const val ACTION_STOP = "com.nyasar.app.recording.STOP"
        const val ACTION_RESUME_EXISTING = "com.nyasar.app.recording.RESUME_EXISTING"
        const val EXTRA_ROUTE_ID = "routeId"
        const val EXTRA_ACTIVITY_ID = "activityId"

        fun startIntent(context: Context, routeId: String? = null) =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                routeId?.let { putExtra(EXTRA_ROUTE_ID, it) }
            }

        /**
         * Resumes an activity whose row already exists in Room (status
         * RECORDING/PAUSED) but whose in-memory service/engine was lost —
         * process death mid-hike, per Task 4. Deliberately not the same
         * path as [startIntent]: that always mints a brand-new activityId,
         * which would create a duplicate row for the same hike.
         */
        fun resumeExistingIntent(context: Context, activityId: String, routeId: String?) =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_RESUME_EXISTING
                putExtra(EXTRA_ACTIVITY_ID, activityId)
                routeId?.let { putExtra(EXTRA_ROUTE_ID, it) }
            }
    }

    inner class LocalBinder : Binder() {
        fun service(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var locationJob: Job? = null

    private lateinit var locationRepository: LocationRepository
    private lateinit var dao: ActivityDao
    // Part 2 fix (BUG #9 "RecordingEngine lama tidak digunakan untuk
    // session baru"): was `private val engine = RecordingEngine()`, one
    // instance for this Service's entire lifetime. RecordingEngine.start()
    // hard-requires status == IDLE (`check(...)`), but after a session
    // completes this same instance is left in STOPPED — so a second
    // handleStart() on the SAME service instance would crash with
    // IllegalStateException instead of beginning a fresh session. Making
    // this a `var`, reassigned to a brand-new RecordingEngine() at the top
    // of handleStart(), is the safest fix: a new instance's fields all
    // start at their declared defaults (0.0, 0L, null, IDLE) with no way to
    // half-remember a stat from the previous session, which a manual
    // reset()-all-fields method on the same instance would risk if a future
    // field is added there and this reset path is forgotten.
    private var engine = RecordingEngine()

    private var activityId: String? = null
    private var routeId: String? = null
    private var startedAtEpochMs: Long = 0L
    private var pointSequence: Int = 0
    // P3I audit: tracks whether THIS service instance has called
    // startForeground() yet, so onStartCommand's defensive call (see
    // above) never fires twice for the same instance — a second
    // startForeground() call is harmless on its own, but this keeps the
    // guard's intent explicit rather than relying on that being safe.
    private var foregroundStarted = false

    // --- Auto Pause (spec P3C) ---
    // Deliberately kept out of RecordingEngine: engine is pure track/stat
    // math with no concept of wall-clock watchdogs. This is service-level
    // orchestration on top of it, same relationship the GPS-health watchdog
    // below has.
    private var autoPauseEnabled = true
    private var isAutoPaused = false
    private var stillSinceMs: Long? = null
    private val autoPauseTriggerMs = 20_000L // 20s of near-zero speed — long
    // enough that a photo stop or catching breath doesn't trigger it (spec:
    // "jangan terlalu sensitif"), short enough to actually save moving-time
    // accuracy on a real stop.
    private val autoPauseSpeedThresholdMps = 0.3f
    private val autoResumeSpeedThresholdMps = 0.6f // higher than the pause
    // threshold on purpose (hysteresis) so speed hovering right at the
    // boundary can't flip pause/resume back and forth every fix.

    // --- GPS health (spec P3C: "RECORDING STATUS: GPS WEAK, GPS LOST") ---
    private var lastFixReceivedAtMs: Long = 0L
    // Raw accuracy from every incoming fix, not just ones RecordingEngine
    // accepted — engine.lastAcceptedFix only updates for fixes within its
    // own accuracy filter, so if GPS accuracy stays bad continuously,
    // lastAcceptedFix would go stale and this watchdog would keep reporting
    // the old (better) accuracy instead of the truth.
    private var lastRawAccuracyMeters: Float? = null
    private var gpsWatchdogJob: Job? = null
    private val gpsWeakAccuracyThresholdM = 30f
    private val gpsLostAfterMs = 15_000L

    /** Totals persisted before this process died, carried forward when
     *  resuming an existing activity (see [resumeExistingIntent]). The
     *  fresh RecordingEngine instance in this process starts its own
     *  counters at zero for the new GPS segment; these baselines are added
     *  back in when persisting/publishing so distance/time/elevation don't
     *  reset to zero in the UI or database after a resume. */
    private var baselineDistanceMeters = 0.0
    private var baselineMovingTimeMs = 0L
    private var baselineElapsedTimeMs = 0L
    private var baselineMaxSpeedKmh = 0.0
    private var baselineElevationGainM = 0.0
    private var baselineElevationLossM = 0.0

    private val fixListeners = mutableListOf<(GpsFix) -> Unit>()

    /** Every accepted GPS fix this session, in order — this is what
     *  [NyasarMapView] draws as the live "actual track" (spec section 3).
     *  Seeded from Room on [handleResumeExisting] so the drawn track doesn't
     *  reset to empty after a process death mid-hike. */
    private val recordedPoints = mutableListOf<TrackPoint>()

    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    /** Dipanggil NavigationViewModel supaya navigation engine ikut menerima
     *  fix yang sama dengan yang dipakai recording — satu sumber GPS untuk
     *  keduanya, sesuai spec (recording + navigation tidak saling meniadakan). */
    fun addFixListener(listener: (GpsFix) -> Unit) {
        fixListeners.add(listener)
    }

    fun removeFixListener(listener: (GpsFix) -> Unit) {
        fixListeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        locationRepository = LocationRepository(this)
        dao = AppDatabase.get(this).activityDao()
        createChannelIfNeeded()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // P3I audit: START and RESUME_EXISTING are the two actions that can
        // arrive via startForegroundService() (see call sites in
        // RecordingViewModel/MainActivity), which obligates this service to
        // call startForeground() for THIS onStartCommand invocation — even
        // if handleStart()/handleResumeExisting() then early-returns because
        // a session is already active (e.g. a duplicate/racy call). Calling
        // it here unconditionally, before delegating, closes that gap;
        // handleStart/handleResumeExisting still call it again themselves
        // for the normal (non-duplicate) path with the correct notification
        // text — a harmless redundant call, not a second service.
        if (intent?.action == ACTION_START || intent?.action == ACTION_RESUME_EXISTING) {
            if (!foregroundStarted) {
                startForeground(NOTIFICATION_ID, buildNotification("Nyasar", "Memulai recording…"))
                foregroundStarted = true
            }
        }
        when (intent?.action) {
            ACTION_START -> handleStart(intent.getStringExtra(EXTRA_ROUTE_ID))
            ACTION_RESUME_EXISTING -> handleResumeExisting(
                intent.getStringExtra(EXTRA_ACTIVITY_ID),
                intent.getStringExtra(EXTRA_ROUTE_ID)
            )
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    private fun handleResumeExisting(existingActivityId: String?, routeIdArg: String?) {
        val id = existingActivityId ?: return
        // Part 2 fix (consistent with handleStart()'s guard): was
        // RECORDING-only. A resume-from-crash request while this service
        // instance's engine is PAUSED (e.g. a duplicate recovery Intent)
        // must also be rejected, not just one for an already-RECORDING
        // engine — resuming into a live PAUSED session should go through
        // handleResume(), not re-run this whole recovery path a second time
        // over it.
        val currentStatus = engine.currentState().status
        if (currentStatus == RecordingStatus.RECORDING || currentStatus == RecordingStatus.PAUSED) return

        // P3I audit fix: startForeground() must happen synchronously,
        // before any async work, when the service was launched via
        // startForegroundService() (see RecordingViewModel/MainActivity
        // call sites). It was previously called from inside this
        // coroutine, AFTER dao.getById() and dao.getPoints() — two Room
        // reads, the second of which loads every point in the activity
        // (Task 15: could be 20,000+ for a long hike). On a cold DB open
        // or large activity, that can exceed Android's few-second
        // foreground-start window and crash this service with
        // ForegroundServiceDidNotStartInTimeException — precisely during
        // process-death recovery (Task 3), the scenario this exists for.
        // Fix: call startForeground() immediately with a placeholder
        // notification, then update it once the real data has loaded.
        startForeground(NOTIFICATION_ID, buildNotification("Memulihkan recording…", "Memuat data aktivitas"))

        serviceScope.launch {
            val existing = dao.getById(id) ?: run {
                // Nothing to resume — this foreground service must not be
                // left running with a placeholder notification forever.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            activityId = id
            routeId = routeIdArg ?: existing.routeId
            startedAtEpochMs = existing.startedAtEpochMs
            pointSequence = dao.getPointCount(id)

            baselineDistanceMeters = existing.distanceMeters
            baselineMovingTimeMs = existing.movingTimeMs
            baselineElapsedTimeMs = existing.elapsedTimeMs
            baselineMaxSpeedKmh = existing.maxSpeedKmh ?: 0.0
            baselineElevationGainM = existing.elevationGainM ?: 0.0
            baselineElevationLossM = existing.elevationLossM ?: 0.0

            recordedPoints.clear()
            recordedPoints.addAll(dao.getPoints(id).map {
                TrackPoint(lat = it.lat, lon = it.lon, elevationM = it.elevationM, timestampEpochMs = it.timestampMs)
            })

            // Part 2 fix (BUG #9, same reasoning as handleStart()): fresh
            // RecordingEngine() instance, not reusing whatever state this
            // service's engine field was left in by an unrelated prior
            // session. engine.start() below requires IDLE — a brand-new
            // instance always satisfies that regardless of what came before.
            engine = RecordingEngine()
            engine.start()
            updateNotification("Recording dilanjutkan", formatDistance())

            dao.update(existing.copy(status = ActivityStatus.RECORDING))

            resetAutoPauseAndGpsWatchdogState()
            loadAutoPauseSetting()
            startLocationCollection()
            startGpsWatchdog()
            publishState()
        }
    }

    private fun handleStart(routeIdArg: String?) {
        // Part 2 fix (BUG #1/#9/#11/#12): guard was RECORDING-only, so a
        // START arriving while PAUSED (state machine says PAUSED only
        // allows Lanjutkan/Selesaikan, spec §"ATURAN STATE") would have
        // silently begun overwriting the in-progress session's identity
        // (new activityId, cleared recordedPoints) out from under it. Now
        // covers both live states; only IDLE and STOPPED (a just-finished,
        // already-persisted-and-closed session) may begin a new one.
        val currentStatus = engine.currentState().status
        if (currentStatus == RecordingStatus.RECORDING || currentStatus == RecordingStatus.PAUSED) return

        routeId = routeIdArg
        val id = UUID.randomUUID().toString()
        activityId = id
        startedAtEpochMs = System.currentTimeMillis()
        pointSequence = 0
        baselineDistanceMeters = 0.0
        baselineMovingTimeMs = 0L
        baselineElapsedTimeMs = 0L
        baselineMaxSpeedKmh = 0.0
        baselineElevationGainM = 0.0
        baselineElevationLossM = 0.0
        recordedPoints.clear()
        // Part 5 fix: storageError previously wasn't cleared here, so a
        // write failure from a PREVIOUS session (e.g. storage was briefly
        // full, then freed up) kept showing its warning badge on a brand
        // new session that hadn't had any write problems of its own yet.
        // Every other piece of per-session state above is already reset
        // the same way; this was simply missing from that list.
        if (_state.value.storageError) {
            _state.value = _state.value.copy(storageError = false)
        }

        // Part 2 fix (BUG #9): fresh instance every session, see the field
        // declaration's comment for why this is safer than a reset() call
        // on the same (possibly STOPPED) instance — engine.start() below
        // requires IDLE, which a brand-new RecordingEngine() always is.
        engine = RecordingEngine()
        engine.start()
        startForeground(NOTIFICATION_ID, buildNotification("Recording dimulai", "0.0 km"))

        resetAutoPauseAndGpsWatchdogState()
        loadAutoPauseSetting()

        serviceScope.launch {
            // P3I §20/26: if this insert fails (storage full, disk error),
            // GPS collection must not start against an activityId that has
            // no row in Room — every subsequent insertPoint would fail too,
            // silently, for the entire session. Surface it via
            // storageError instead and stop here; the user sees the
            // warning immediately rather than discovering an empty
            // Activity after Stop.
            val insertedOk = try {
                dao.insert(
                    ActivityEntity(
                        id = id,
                        routeId = routeId,
                        name = "Aktivitas ${java.text.SimpleDateFormat("d MMM HH:mm").format(java.util.Date(startedAtEpochMs))}",
                        startedAtEpochMs = startedAtEpochMs,
                        endedAtEpochMs = null,
                        status = ActivityStatus.RECORDING,
                        distanceMeters = 0.0,
                        movingTimeMs = 0,
                        elapsedTimeMs = 0,
                        avgSpeedKmh = null,
                        maxSpeedKmh = null,
                        elevationGainM = null,
                        elevationLossM = null
                    )
                )
                true
            } catch (e: Exception) {
                android.util.Log.e("RecordingService", "Failed to create Activity row", e)
                false
            }

            if (insertedOk) {
                startLocationCollection()
                startGpsWatchdog()
            } else {
                // engine already transitioned to RECORDING above (before we
                // knew the insert would fail) — stop() it back to a known
                // terminal state so a stuck IDLE-guard can't block a retry
                // if stopSelf() doesn't tear down this instance immediately.
                engine.stop()
                activityId = null
                _state.value = _state.value.copy(storageError = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // BUG #3 FIX: publishState() must be inside the coroutine,
            // AFTER the DB insert. Previously it was called synchronously
            // at the end of handleStart(), which meant the UI showed
            // RECORDING status before the activity row existed in Room.
            // If the user stopped before the insert completed, the
            // activity row was never created and the summary was lost.
            publishState()
            // BUG FIX: For 0-second recordings, we also need to ensure
            // the stop command is processed after the DB insert completes.
            // The persistSummary will handle creating the activity if it
            // doesn't exist yet (see persistSummary's null handling).
        }
    }

    private fun loadAutoPauseSetting() {
        serviceScope.launch {
            autoPauseEnabled = SettingsRepository(this@RecordingService).settings.first().autoPauseEnabled
        }
    }

    private fun resetAutoPauseAndGpsWatchdogState() {
        isAutoPaused = false
        stillSinceMs = null
        lastFixReceivedAtMs = System.currentTimeMillis()
        lastRawAccuracyMeters = null
    }

    private fun handlePause() {
        // Part 2 fix (BUG #14 "Stop -> Resume tidak boleh terjadi", applies
        // symmetrically to Pause): guard against a stray/racy PAUSE arriving
        // after this session is already STOPPED (e.g. a delayed Intent from
        // before stopSelf() actually tore this instance down — see
        // handleStop()'s comment). Without this, engine.pause() itself is a
        // safe no-op (only acts if status == RECORDING), but the
        // persistSummary(PAUSED) call below it was NOT guarded, and would
        // still overwrite an already-COMPLETED Room row back to PAUSED.
        if (engine.currentState().status != RecordingStatus.RECORDING) return
        engine.pause()
        isAutoPaused = false // a manual pause always overrides/clears any auto-pause bookkeeping
        stillSinceMs = null
        publishState()
        updateNotification("Recording dijeda", formatDistance())
        persistSummary(ActivityStatus.PAUSED)
    }

    private fun handleResume() {
        // Part 2 fix (BUG #14): same reasoning as handlePause() above — a
        // stray RESUME after STOP must not resurrect a finished session by
        // persisting RECORDING over an already-COMPLETED row.
        if (engine.currentState().status != RecordingStatus.PAUSED) return
        engine.resume()
        isAutoPaused = false
        stillSinceMs = null
        publishState()
        updateNotification("Recording aktif", formatDistance())
        persistSummary(ActivityStatus.RECORDING)
    }

    private fun handleStop() {
        val finalState = engine.stop()
        locationJob?.cancel()
        gpsWatchdogJob?.cancel()
        persistSummary(ActivityStatus.COMPLETED, finalState)
        publishState()
        // BUG #2 FIX: activityId must NOT be cleared here — it must
        // survive until the persistSummary coroutine has captured it (see
        // persistSummary's own coroutine below). The old code cleared it
        // BEFORE persistSummary's coroutine ran, so if the service was
        // destroyed before that coroutine completed (onDestroy cancels
        // serviceScope), the summary was never persisted — silent data
        // loss. We defer the clear into the persistSummary coroutine
        // itself, which runs on serviceScope and completes quickly (one
        // Room write). stopSelf() is called AFTER the coroutine is
        // launched; Android does not synchronously destroy the service on
        // stopSelf() — it drains the message queue, giving the coroutine
        // time to finish.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * BUG FIX: Handle the case where activityId is null when stopping.
     * This can happen when the user stops immediately after starting,
     * before the DB insert coroutine completes. In this case, we need to
     * create the activity first, then persist the summary.
     */
    private fun handleStopWithNullActivityId() {
        val id = activityId ?: run {
            // No activityId set — this shouldn't happen normally, but if it
            // does, we can't create a valid activity. Just stop the service.
            engine.stop()
            locationJob?.cancel()
            gpsWatchdogJob?.cancel()
            publishState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // If we get here, activityId is set but the activity might not
        // exist in DB yet. The persistSummary will handle this case.
        handleStop()
    }

    /**
     * Spec P3C Auto Pause: RecordingEngine has no concept of a wall-clock
     * watchdog by design (pure track/stat math) — this lives here, next to
     * the GPS fix stream it needs to observe continuously, including while
     * PAUSED (so it can detect movement resuming). A manual pause/resume
     * (see [handlePause]/[handleResume]) always clears [isAutoPaused], so
     * this never auto-resumes something the user paused on purpose.
     */
    private fun evaluateAutoPause(fix: GpsFix) {
        if (!autoPauseEnabled) return
        val speed = fix.speedMps
        val status = engine.currentState().status

        if (status == RecordingStatus.RECORDING) {
            if (speed != null && speed < autoPauseSpeedThresholdMps) {
                val since = stillSinceMs ?: fix.timestampMs.also { stillSinceMs = it }
                if (fix.timestampMs - since >= autoPauseTriggerMs) {
                    stillSinceMs = null
                    isAutoPaused = true
                    engine.pause()
                    publishState()
                    updateNotification("Recording dijeda otomatis", formatDistance())
                    persistSummary(ActivityStatus.PAUSED)
                }
            } else {
                stillSinceMs = null
            }
        } else if (status == RecordingStatus.PAUSED && isAutoPaused) {
            if (speed != null && speed > autoResumeSpeedThresholdMps) {
                isAutoPaused = false
                engine.resume()
                publishState()
                updateNotification("Recording aktif", formatDistance())
                persistSummary(ActivityStatus.RECORDING)
            }
        }
    }

    /**
     * Spec P3C: "RECORDING STATUS: ... GPS WEAK, GPS LOST". Ticks every 5s
     * rather than reacting per-fix, since "lost" is inherently an absence
     * of fixes — there's nothing to react to per-fix for that case. Only
     * runs while a session is active; cancelled in [handleStop] so it
     * never spams a health check for a recording that no longer exists.
     */
    private fun startGpsWatchdog() {
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000L)
                val sinceLastFixMs = System.currentTimeMillis() - lastFixReceivedAtMs
                val health = when {
                    sinceLastFixMs >= gpsLostAfterMs -> GpsHealth.LOST
                    (lastRawAccuracyMeters ?: 0f) > gpsWeakAccuracyThresholdM -> GpsHealth.WEAK
                    else -> GpsHealth.OK
                }
                if (health != _state.value.gpsHealth) {
                    _state.value = _state.value.copy(gpsHealth = health)
                }
            }
        }
    }

    private fun startLocationCollection() {
        locationJob?.cancel()
        locationJob = serviceScope.launch {
            if (!locationRepository.hasLocationPermission()) return@launch

            locationRepository.observeLocation().collect { fix ->
                lastFixReceivedAtMs = System.currentTimeMillis()
                lastRawAccuracyMeters = fix.accuracyMeters
                fixListeners.forEach { it(fix) }

                evaluateAutoPause(fix)

                engine.onGpsFix(fix) ?: return@collect
                recordedPoints.add(
                    TrackPoint(lat = fix.lat, lon = fix.lon, elevationM = fix.elevationM, timestampEpochMs = fix.timestampMs)
                )
                publishState()

                val id = activityId ?: return@collect
                pointSequence += 1
                // P3I §20/26: a single failed Room write (storage full, disk
                // I/O error) must not kill this coroutine — an uncaught
                // exception inside collect{} cancels the whole flow, which
                // would silently end the entire recording session on one
                // bad write. In-memory state (engine, recordedPoints,
                // published stats) is unaffected either way; only this
                // point's persistence is lost, and the user is told via
                // storageError rather than the app just going quiet.
                try {
                    dao.insertPoint(
                        ActivityPointEntity(
                            activityId = id,
                            sequence = pointSequence,
                            lat = fix.lat,
                            lon = fix.lon,
                            elevationM = fix.elevationM,
                            speedMps = fix.speedMps,
                            accuracyMeters = fix.accuracyMeters,
                            timestampMs = fix.timestampMs
                        )
                    )
                    if (_state.value.storageError) {
                        _state.value = _state.value.copy(storageError = false)
                    }
                } catch (e: Exception) {
                    pointSequence -= 1 // this point never actually landed in Room
                    android.util.Log.e("RecordingService", "Failed to persist GPS point", e)
                    _state.value = _state.value.copy(storageError = true)
                }

                // Re-publish so a storageError flip (either direction) is
                // reflected immediately rather than waiting for the next
                // fix — publishState() above already ran before this
                // try-catch, using the pre-write storageError value.
                publishState()

                // Update notification only every ~10 points to avoid hammering
                // NotificationManager on every single GPS fix (every ~1s).
                if (pointSequence % 10 == 0) {
                    updateNotification("Recording aktif", formatDistance())
                }
            }
        }
    }

    private fun persistSummary(status: String, finalState: RecordingState? = null) {
        // BUG #2 FIX: capture activityId IMMEDIATELY at call-site time,
        // before the coroutine launches. The old code read activityId
        // inside the coroutine, but handleStop() clears it to null right
        // after calling persistSummary() — so if the coroutine hadn't
        // started by the time handleStop cleared it, persistSummary would
        // see null and silently skip the write (data loss). Capturing it
        // here (the calling thread) ensures the captured value is always
        // the correct one, even if activityId is cleared before the
        // coroutine body runs.
        val id = activityId ?: return
        val s = finalState ?: engine.currentState()
        val distanceMeters = baselineDistanceMeters + s.distanceMeters
        val movingTimeMs = baselineMovingTimeMs + s.movingTimeMs
        val elapsedTimeMs = baselineElapsedTimeMs + s.elapsedTimeMs
        val maxSpeedKmh = maxOf(baselineMaxSpeedKmh, s.maxSpeedKmh)
        val hours = movingTimeMs / 3_600_000.0
        val avgSpeedKmh = if (hours > 0) (distanceMeters / 1000.0) / hours else s.avgSpeedKmh

        // BUG FIX: Capture the activity creation parameters needed to
        // create the activity if it doesn't exist yet. This handles the
        // case where the user stops immediately after starting, before
        // the DB insert coroutine completes.
        val capturedActivityId = id
        val capturedRouteId = routeId
        val capturedStartedAt = startedAtEpochMs

        serviceScope.launch {
            val existing = dao.getById(id)
            if (existing == null) {
                // BUG FIX: Activity doesn't exist yet — create it now.
                // This happens when the user stops immediately after
                // starting, before the handleStart() coroutine completes.
                try {
                    dao.insert(
                        ActivityEntity(
                            id = capturedActivityId,
                            routeId = capturedRouteId,
                            name = "Aktivitas ${java.text.SimpleDateFormat("d MMM HH:mm").format(java.util.Date(capturedStartedAt))}",
                            startedAtEpochMs = capturedStartedAt,
                            endedAtEpochMs = if (status == ActivityStatus.COMPLETED) System.currentTimeMillis() else null,
                            status = status,
                            distanceMeters = distanceMeters,
                            movingTimeMs = movingTimeMs,
                            elapsedTimeMs = elapsedTimeMs,
                            avgSpeedKmh = avgSpeedKmh,
                            maxSpeedKmh = maxSpeedKmh,
                            elevationGainM = baselineElevationGainM + s.elevationGainM,
                            elevationLossM = baselineElevationLossM + s.elevationLossM
                        )
                    )
                    if (status == ActivityStatus.COMPLETED) {
                        activityId = null
                    }
                    if (_state.value.storageError) {
                        _state.value = _state.value.copy(storageError = false)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RecordingService", "Failed to create activity for 0-second recording", e)
                    _state.value = _state.value.copy(storageError = true)
                }
                return@launch
            }
            try {
                dao.update(
                    existing.copy(
                        endedAtEpochMs = if (status == ActivityStatus.COMPLETED) System.currentTimeMillis() else null,
                        status = status,
                        distanceMeters = distanceMeters,
                        movingTimeMs = movingTimeMs,
                        elapsedTimeMs = elapsedTimeMs,
                        avgSpeedKmh = avgSpeedKmh,
                        maxSpeedKmh = maxSpeedKmh,
                        elevationGainM = baselineElevationGainM + s.elevationGainM,
                        elevationLossM = baselineElevationLossM + s.elevationLossM
                    )
                )
                // BUG #2 FIX: clear activityId INSIDE the coroutine, AFTER
                // the successful dao.update — but ONLY for COMPLETED
                // (final stop). For PAUSED/RECORDING mid-session updates,
                // activityId must remain set so subsequent persistSummary
                // calls (auto-pause, manual pause/resume, next GPS point)
                // still have a valid target. The old code cleared it in
                // handleStop() synchronously, before this coroutine ran —
                // creating a window where a stray Intent or service
                // teardown could find a null activityId and skip the
                // summary entirely.
                if (status == ActivityStatus.COMPLETED) {
                    activityId = null
                }
                if (_state.value.storageError) {
                    _state.value = _state.value.copy(storageError = false)
                }
            } catch (e: Exception) {
                // P3I §20/26: same reasoning as insertPoint's try-catch above
                // — persistSummary runs on every pause/resume/stop, and an
                // uncaught exception here would silently kill whichever
                // coroutine called it (e.g. the evaluateAutoPause launch),
                // not just this one save. In-memory engine/recordedPoints
                // state is untouched either way; only this write is lost.
                android.util.Log.e("RecordingService", "Failed to persist activity summary", e)
                _state.value = _state.value.copy(storageError = true)
            }
        }
    }

    private fun publishState() {
        val s = engine.currentState()
        val fix = engine.lastAcceptedFix
        _state.value = RecordingUiState(
            activityId = activityId,
            status = s.status,
            distanceMeters = baselineDistanceMeters + s.distanceMeters,
            movingTimeMs = baselineMovingTimeMs + s.movingTimeMs,
            elapsedTimeMs = baselineElapsedTimeMs + s.elapsedTimeMs,
            currentSpeedKmh = s.currentSpeedKmh,
            avgSpeedKmh = s.avgSpeedKmh,
            maxSpeedKmh = maxOf(baselineMaxSpeedKmh, s.maxSpeedKmh),
            elevationGainM = baselineElevationGainM + s.elevationGainM,
            elevationLossM = baselineElevationLossM + s.elevationLossM,
            pointCount = s.pointCount,
            currentLat = fix?.lat,
            currentLon = fix?.lon,
            currentHeadingDeg = fix?.bearingDeg,
            // Snapshot copy — recordedPoints keeps mutating, StateFlow values
            // must not alias a list that changes after being published.
            recordedTrack = recordedPoints.toList(),
            isAutoPaused = isAutoPaused,
            // Preserve whatever the watchdog last determined — publishState()
            // is called far more often (every fix) than the watchdog ticks
            // (every 5s), so it must not stomp that field back to OK.
            gpsHealth = _state.value.gpsHealth,
            // Same reasoning: publishState() runs on every accepted fix,
            // which is more often than storageError is set/cleared (only
            // on an actual failed/succeeded write) — preserve it rather
            // than resetting to false on every call.
            storageError = _state.value.storageError
        )
    }

    private fun formatDistance(): String =
        "%.2f km".format((baselineDistanceMeters + engine.currentState().distanceMeters) / 1000.0)

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_navigation)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        // P3I audit fix (§19): "pastikan notification tidak menyebabkan
        // exception." This is called from inside startLocationCollection's
        // fix-processing loop and evaluateAutoPause — both critical paths.
        // An uncaught SecurityException (notification permission revoked
        // mid-session) or any other NotificationManager failure here would
        // propagate up and could kill the GPS collection coroutine
        // entirely, which is a far more serious problem than the
        // notification itself failing to update. A missing/failed
        // notification update must never take down recording.
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (e: Exception) {
            android.util.Log.e("RecordingService", "Failed to update notification", e)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationJob?.cancel()
        serviceScope.cancel()
    }
}

data class RecordingUiState(
    val activityId: String? = null,
    val status: RecordingStatus = RecordingStatus.IDLE,
    val distanceMeters: Double = 0.0,
    val movingTimeMs: Long = 0,
    val elapsedTimeMs: Long = 0,
    val currentSpeedKmh: Double? = null,
    val avgSpeedKmh: Double? = null,
    val maxSpeedKmh: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val pointCount: Int = 0,
    val currentLat: Double? = null,
    val currentLon: Double? = null,
    val currentHeadingDeg: Float? = null,
    /** Every accepted point this session, for [NyasarMapView]'s live "actual
     *  track" line — spec section 3, "jejak yang sudah dilewati digambar
     *  realtime di map". */
    val recordedTrack: List<TrackPoint> = emptyList(),
    /** True when the current PAUSED status was triggered automatically
     *  (spec P3C Auto Pause) rather than by the user tapping Pause — lets
     *  the UI show "Dijeda otomatis" and lets the service know it's safe
     *  to auto-resume on movement (a manual pause never auto-resumes). */
    val isAutoPaused: Boolean = false,
    /** Spec P3C: "RECORDING STATUS harus jelas: ... GPS WEAK, GPS LOST".
     *  Derived from real fix accuracy/freshness in [RecordingService] —
     *  never synthesized. */
    val gpsHealth: GpsHealth = GpsHealth.OK,
    /** P3I §20/26: true when the most recent Room write (a GPS point, or
     *  the activity summary on pause/resume/stop) failed — storage full or
     *  a disk I/O error. Recording keeps running in-memory either way
     *  (this only reflects persistence health); the UI should show a
     *  warning so the user knows not to trust that everything is being
     *  saved, rather than the app silently going quiet. */
    val storageError: Boolean = false
)

enum class GpsHealth { OK, WEAK, LOST }
