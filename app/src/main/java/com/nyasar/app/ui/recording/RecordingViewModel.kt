package com.nyasar.app.ui.recording

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.location.HeadingProvider
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.navigation.GpsFix
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.recording.RecordingService
import com.nyasar.app.recording.RecordingServiceConnection
import com.nyasar.app.recording.RecordingUiState
import com.nyasar.app.recording.RecordingStatus
import com.nyasar.app.ui.components.CameraFollowMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI-facing wrapper around RecordingService. Doesn't own recording state
 * itself — the service does, so state survives this ViewModel being
 * cleared (e.g. user navigates away and back while recording continues).
 * routeId is optional: null means "recording without a route", which is
 * the flow the spec explicitly requires and the app previously had no
 * entry point for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModel(app: Application) : AndroidViewModel(app) {

    private val connection = RecordingServiceConnection(app)
    private val dao = AppDatabase.get(app).activityDao()
    // Fix: before this, "recenter" on the pre-record (IDLE) screen was a
    // no-op — RecordingViewModel had no GPS source of its own, only ever
    // reading currentLat/currentLon from RecordingService's state, which
    // stays null until the service is actually started (ACTION_START).
    // Same pattern HomeViewModel already uses for its own "where am I"
    // preview (see its class doc) — independent LocationRepository
    // subscription, active only while IDLE. previewLocationJob is
    // cancelled the moment recording actually starts, so this never runs
    // as a second GPS source alongside RecordingService's own stream (the
    // same one-source rule NavigationViewModel's class doc describes).
    private val locationRepository = LocationRepository(app)
    private var previewLocationJob: Job? = null
    private val _previewLocation = MutableStateFlow<GpsFix?>(null)
    val previewLocation: StateFlow<GpsFix?> = _previewLocation.asStateFlow()

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    /** Non-null when Room has an activity row left in RECORDING/PAUSED
     *  status with no live service attached to it — i.e. the process was
     *  killed mid-recording (Task 4). Screen shows the recovery prompt
     *  while this is set; cleared once the user picks resume/stop/discard. */
    private val _recoveryCandidate = MutableStateFlow<ActivityEntity?>(null)
    val recoveryCandidate: StateFlow<ActivityEntity?> = _recoveryCandidate.asStateFlow()

    // --- Camera / follow / orientation state (spec P3 §14-16) ---
    // Recording defaults to Follow ON + North Up per spec §15 ("Default
    // yang disarankan: Recording: North Up"); user can still switch to
    // Heading Up, unlike the previous build which had no toggle at all.
    private val headingProvider = HeadingProvider(app)
    private var lastSensorHeadingDeg: Float? = null
    private var lastFix: GpsFix? = null

    // Same 3-state cycle as NavigationViewModel (FREE -> FOLLOW_NORTH_UP ->
    // FOLLOW_HEADING -> FREE), replacing the followMode + headingUp Boolean
    // pair which allowed the same meaningless 4th combination.
    // Bug fix: this used to default to FREE, directly contradicting the
    // spec comment right above ("Recording defaults to Follow ON") and
    // HomeViewModel's already-correct default (_followMode = true there).
    // A fresh RecordingScreen open showed MapLibre's raw world-view camera
    // until the user manually tapped recenter, instead of snapping to GPS
    // the moment the first fix arrived.
    private val _cameraMode = MutableStateFlow(CameraFollowMode.FOLLOW_NORTH_UP)
    val cameraMode: StateFlow<CameraFollowMode> = _cameraMode.asStateFlow()

    // --- Map style / layer variant ---
    private val _styleVariant = MutableStateFlow(StyleVariant.TOPO)
    val styleVariant: StateFlow<StyleVariant> = _styleVariant.asStateFlow()

    val followMode: StateFlow<Boolean> = _cameraMode
        .map { it != CameraFollowMode.FREE }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    val headingUp: StateFlow<Boolean> = _cameraMode
        .map { it == CameraFollowMode.FOLLOW_HEADING }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    private val _displayHeadingDeg = MutableStateFlow<Float?>(null)
    val displayHeadingDeg: StateFlow<Float?> = _displayHeadingDeg.asStateFlow()

    /** Called by RecordingScreen when the user manually drags the map —
     *  same contract as NavigationScreen: manual pan drops all the way to
     *  FREE (spec §10/§16). */
    fun onUserPanned() {
        _cameraMode.value = CameraFollowMode.FREE
    }

    /** Tapping the recenter button advances the cycle, same order and
     *  reasoning as NavigationViewModel.recenter(). */
    fun recenter() {
        _cameraMode.value = when (_cameraMode.value) {
            CameraFollowMode.FREE -> CameraFollowMode.FOLLOW_NORTH_UP
            CameraFollowMode.FOLLOW_NORTH_UP -> CameraFollowMode.FOLLOW_HEADING
            CameraFollowMode.FOLLOW_HEADING -> CameraFollowMode.FREE
        }
    }

    /** Tap-compass-to-north convenience, matching NavigationScreen/spec §13.
     *  One step back from FOLLOW_HEADING, not a drop to FREE — a compass
     *  tap was never meant to also stop centering on the user. */
    fun resetToNorthUp() {
        if (_cameraMode.value == CameraFollowMode.FOLLOW_HEADING) {
            _cameraMode.value = CameraFollowMode.FOLLOW_NORTH_UP
        }
    }

    /** Same pattern/name as HomeViewModel's function of the same name —
     *  starts the pre-record GPS preview once, no-ops on repeat calls
     *  (e.g. recomposition) or once a recording session has already
     *  claimed the GPS stream (see the IDLE check in init{} above). */
    fun startLocationUpdatesIfPermitted() {
        if (previewLocationJob != null) return
        if (_uiState.value.status != RecordingStatus.IDLE) return
        if (!locationRepository.hasLocationPermission()) return
        previewLocationJob = viewModelScope.launch {
            locationRepository.observeLocation().collect { fix ->
                // Bug: camera defaults to follow-user (FOLLOW_NORTH_UP) on
                // this screen, and NyasarMapView's animateCamera runs on
                // every fix with no accuracy gate — so the first several
                // fixes off a cold GPS lock (which can be off by hundreds
                // of meters before the radio settles) each yanked the
                // camera to a wildly different spot, animated smoothly
                // each time but landing somewhere new every time, reading
                // as a jump cut rather than "no animation" (the animation
                // itself was never the problem). RecordingEngine's own
                // recording-acceptance filter is 100m (P3I) — deliberately
                // tighter here (30m) since this only gates *camera
                // movement*, not whether a fix is kept, and early
                // GPS_PROVIDER fixes indoors/near buildings routinely land
                // in the 100-500m range, which is still a visible jump for
                // a map that's supposed to be centered on the user.
                if (fix.accuracyMeters <= 30f) {
                    _previewLocation.value = fix
                }
            }
        }
    }

    fun hasLocationPermission(): Boolean = locationRepository.hasLocationPermission()

    // --- Map style / layer (same pattern as HomeViewModel) ---

    fun setStyleVariant(variant: StyleVariant) {
        _styleVariant.value = variant
    }

    fun cycleLayer() {
        val current = _styleVariant.value
        _styleVariant.value = when (current) {
            StyleVariant.OUTDOOR -> StyleVariant.SATELLITE
            StyleVariant.SATELLITE -> StyleVariant.TOPO
            StyleVariant.TOPO -> StyleVariant.OUTDOOR
        }
    }

    private fun resolveDisplayHeading(): Float? {
        lastSensorHeadingDeg?.let { return it }
        val fix = lastFix ?: return null
        val isMoving = (fix.speedMps ?: 0f) > 0.3f
        return if (isMoving) fix.bearingDeg else null
    }

    init {
        connection.bind()
        // flatMapLatest so a new emission on connection.service (e.g. the
        // service disconnects and rebinds) actually switches which state
        // flow we're collecting instead of getting stuck forever inside a
        // nested collect{} on the previous (possibly dead) service instance.
        viewModelScope.launch {
            connection.service.flatMapLatest { svc ->
                svc?.state ?: flowOf(RecordingUiState())
            }.collect { state ->
                _uiState.value = state
                // Preview GPS is only for the pre-record screen; once a
                // session actually starts (RECORDING or PAUSED — not just
                // a bare status flip, so a resumed-from-recovery session
                // stays off it too), stop it so RecordingService's own
                // stream is the single GPS source from that point on.
                if (state.status != RecordingStatus.IDLE) {
                    previewLocationJob?.cancel()
                    previewLocationJob = null
                }
                lastFix = state.currentLat?.let { lat ->
                    state.currentLon?.let { lon ->
                        GpsFix(
                            lat = lat, lon = lon, elevationM = null, speedMps = null,
                            accuracyMeters = 0f, timestampMs = System.currentTimeMillis(),
                            bearingDeg = state.currentHeadingDeg
                        )
                    }
                }
                _displayHeadingDeg.value = resolveDisplayHeading()
            }
        }
        if (headingProvider.hasOrientationSensor()) {
            viewModelScope.launch {
                headingProvider.observeHeading().collect { heading ->
                    lastSensorHeadingDeg = heading
                    _displayHeadingDeg.value = resolveDisplayHeading()
                }
            }
        }
    }

    /**
     * Call once when the recording flow is entered. Checks for an orphaned
     * active activity before anything else starts. Uses ActivityManager
     * rather than the (async, not-yet-connected-on-first-frame) bound
     * uiState to decide whether a live service already owns this activity —
     * if RecordingService is running at all, whatever it's tracking is not
     * orphaned, so recovery is skipped and the normal service-state flow
     * takes over once binding completes. Suspends until the check is done
     * so the caller (RecordingScreen) can gate auto-start on it and avoid
     * racing a start against an unresolved recovery check.
     */
    suspend fun checkForRecovery() {
        if (isRecordingServiceRunning()) return
        val active = dao.getActiveOrNull() ?: return
        _recoveryCandidate.value = active
    }

    @Suppress("DEPRECATION") // querying our own process's own service — see
    // NavigationViewModel.isRecordingServiceRunning for the same pattern/rationale.
    private fun isRecordingServiceRunning(): Boolean {
        val manager = getApplication<Application>()
            .getSystemService(android.app.ActivityManager::class.java) ?: return false
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == RecordingService::class.java.name
        }
    }

    fun resumeRecovered() {
        val activity = _recoveryCandidate.value ?: return
        _recoveryCandidate.value = null
        val intent = RecordingService.resumeExistingIntent(getApplication(), activity.id, activity.routeId)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    /** Finalizes the orphaned activity with whatever was persisted before
     *  the process died — no new GPS points, just closes it out so it
     *  shows up in history instead of staying stuck. */
    fun stopAndSaveRecovered() {
        val activity = _recoveryCandidate.value ?: return
        _recoveryCandidate.value = null
        viewModelScope.launch {
            dao.update(
                activity.copy(
                    status = com.nyasar.app.data.db.ActivityStatus.COMPLETED,
                    endedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun discardRecovered() {
        val activity = _recoveryCandidate.value ?: return
        _recoveryCandidate.value = null
        viewModelScope.launch {
            dao.deletePointsForActivity(activity.id)
            dao.deleteById(activity.id)
        }
    }

    fun startRecording(routeId: String? = null) {
        val intent = RecordingService.startIntent(getApplication(), routeId)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun selectSportType(type: com.nyasar.app.recording.SportType) {
        _uiState.value = _uiState.value.copy(sportType = type.name)
    }

    fun pauseRecording() = sendAction(RecordingService.ACTION_PAUSE)
    fun resumeRecording() = sendAction(RecordingService.ACTION_RESUME)
    fun stopRecording() = sendAction(RecordingService.ACTION_STOP)

    /** Dismiss the "Belum bergerak?" prompt without stopping recording. */
    fun dismissNotMovingPrompt() = sendAction(RecordingService.ACTION_DISMISS_NOT_MOVING)

    /**
     * Discard an empty recording (user never moved, "Belum bergerak?"
     * prompt → Buang). Stops the service, deletes the activity + points
     * from DB, and lets the caller handle navigation (onExit).
     */
    fun discardNotMoving() {
        val activityId = _uiState.value.activityId
        stopRecording()
        if (activityId != null) {
            discardRecording(activityId)
        }
    }

    /**
     * Update activity title after recording stops. Called from PostRecordingForm.
     */
    fun updateActivityTitle(activityId: String?, title: String) {
        if (activityId == null) return
        viewModelScope.launch {
            dao.getById(activityId)?.let { activity ->
                dao.update(activity.copy(name = title))
            }
        }
    }

    /**
     * Discard/delete an activity after recording stops. Called from PostRecordingForm.
     */
    fun discardRecording(activityId: String?) {
        if (activityId == null) return
        viewModelScope.launch {
            dao.deletePointsForActivity(activityId)
            dao.deleteById(activityId)
        }
    }

    /**
     * Delete a photo during post-recording form.
     */
    fun deletePhotoForPostRecording(photo: com.nyasar.app.data.db.ActivityPhotoEntity) {
        viewModelScope.launch {
            try {
                com.nyasar.app.data.repository.ActivityPhotoRepository(getApplication()).delete(photo)
            } catch (e: Exception) {
                android.util.Log.e("RecordingViewModel", "Failed to delete photo", e)
            }
        }
    }

    /**
     * Part 2 fix (§"SERVICE" — "Jangan menggunakan startForegroundService
     * secara tidak perlu untuk command yang hanya mengontrol service yang
     * sudah berjalan"): PAUSE/RESUME/STOP only make sense once RecordingService
     * is already alive and already foreground (they're no-ops in
     * RecordingService if nothing is RECORDING/PAUSED — see the guards
     * added to handlePause/handleResume in Part 2). Using
     * startForegroundService() for them was unconditionally re-obligating
     * this service to call startForeground() within the OS's few-second
     * window on every single pause/resume/stop tap, for no reason — the
     * service is already in the foreground from the original START. A
     * plain startService() targets the same running instance without that
     * obligation. (If the service isn't running at all — e.g. a stray tap
     * after process death — plain startService() still safely starts it
     * un-foregrounded just long enough for the action's early-return guard
     * to make it a no-op; there is no scenario where that leaves an
     * un-foregrounded service actually doing background GPS work, since
     * PAUSE/RESUME/STOP never begin GPS collection themselves.)
     */
    private fun sendAction(action: String) {
        val intent = Intent(getApplication(), RecordingService::class.java).setAction(action)
        getApplication<Application>().startService(intent)
    }

    override fun onCleared() {
        super.onCleared()
        connection.unbind()
    }
}
