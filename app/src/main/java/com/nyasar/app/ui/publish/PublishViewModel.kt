package com.nyasar.app.ui.publish

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.ActivityDao
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.db.PendingPublishEntity
import com.nyasar.app.data.db.RouteDao
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.repository.WaypointRepository
import com.nyasar.app.data.supabase.PublishRepository
import com.nyasar.app.data.supabase.PublishRepository.PublishError
import com.nyasar.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How the publish form gets its difficulty/trail options (strings resolved
 *  in the Composable — only stable values cross the process boundary). */
enum class PublishDifficulty { NONE, EASY, MODERATE, DIFFICULT, VERY_DIFFICULT }

enum class PublishTrailType { NONE, LOOP, OUT_AND_BACK, POINT_TO_POINT }

/** Schema enum literals for `routes.difficulty` (schema_v1.sql). */
internal fun PublishDifficulty.toApi(): String? = when (this) {
    PublishDifficulty.NONE -> null
    PublishDifficulty.EASY -> "easy"
    PublishDifficulty.MODERATE -> "moderate"
    PublishDifficulty.DIFFICULT -> "difficult"
    PublishDifficulty.VERY_DIFFICULT -> "very_difficult"
}

/** Schema enum literals for `routes.trail_type` (schema_v1.sql). */
internal fun PublishTrailType.toApi(): String? = when (this) {
    PublishTrailType.NONE -> null
    PublishTrailType.LOOP -> "loop"
    PublishTrailType.OUT_AND_BACK -> "out_and_back"
    PublishTrailType.POINT_TO_POINT -> "point_to_point"
}

/** Sealed publish state — same style as AuthViewModel's AccountActionState. */
sealed class PublishState {
    /** Form idle; optionally carries the last error for the banner. */
    data class Idle(val error: PublishUiError? = null) : PublishState()
    data object Publishing : PublishState()
    data class Success(val routeId: String, val gpxUrl: String) : PublishState()
}

/** Localized error categories shared with the sheet (maps PublishError). */
enum class PublishUiError {
    NOT_SIGNED_IN, EMPTY_TRACK, INSERT, UPLOAD, NETWORK, GENERIC
}

internal fun PublishRepository.PublishError.toUi(): PublishUiError = when (this) {
    PublishRepository.PublishError.NOT_CONFIGURED,
    PublishRepository.PublishError.NOT_SIGNED_IN -> PublishUiError.NOT_SIGNED_IN
    PublishRepository.PublishError.EMPTY_TRACK -> PublishUiError.EMPTY_TRACK
    PublishRepository.PublishError.ROUTE_INSERT_FAILED -> PublishUiError.INSERT
    PublishRepository.PublishError.STORAGE_UPLOAD_FAILED -> PublishUiError.UPLOAD
    PublishRepository.PublishError.NETWORK -> PublishUiError.NETWORK
    PublishRepository.PublishError.UNKNOWN -> PublishUiError.GENERIC
}

/**
 * Publish pipeline ViewModel — now with THREE entry points (konsep
 * "save = publish", 2026-09):
 *
 * 1. [saveAndPublish] — the recording Review form's single "Simpan
 *    Aktivitas" action: persists the completed activity locally FIRST
 *    (never network-dependent), auto-backs it up, then publishes in the
 *    same tap. No account / no signal → the activity is still saved, and
 *    the publish rides the [PendingPublishEntity] queue for the next
 *    flush. Anti-double: the repository probes the unique source index,
 *    so re-saves/flushes never create duplicate cloud rows.
 * 2. [publish] — explicit publish of an existing activity (kept for the
 *    queue-flush and any future caller).
 * 3. [publishRoute] — Library route publish (the only remaining manual
 *    publish UI, per the IA decision).
 *
 * [flushPendingPublishes] drains the offline queue (login initial-sync /
 * app-start online); every outcome is either Success/AlreadyPublished
 * (row dequeued) or left queued for the next flush.
 */
class PublishViewModel(app: Application) : AndroidViewModel(app) {

    private val activityDao: ActivityDao = AppDatabase.get(app).activityDao()
    private val routeDao: RouteDao = AppDatabase.get(app).routeDao()
    private val pendingPublishDao = AppDatabase.get(app).pendingPublishDao()
    private val waypointRepository = WaypointRepository(app)
    private val routeRepository = RouteRepository(app)
    private val repository = PublishRepository()

    private val _state = MutableStateFlow<PublishState>(PublishState.Idle())
    val state: StateFlow<PublishState> = _state.asStateFlow()

    /** True only when this build has Supabase credentials AND a session. */
    val canPublish: Boolean
        get() = SupabaseClientProvider.isConfigured &&
            SupabaseClientProvider.client.auth.currentUserOrNull() != null

    /**
     * The Review form's single action — SAVE first (local, always),
     * backup second (silent), publish third (best-effort this tap, queued
     * otherwise). The title update is applied before anything else so the
     * backed-up/published name is the user's, not the auto title.
     */
    fun saveAndPublish(
        activityId: String,
        title: String,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        isPublic: Boolean,
        /** Fired after the local save committed (screen may pop immediately). */
        onSaved: (publishQueuedForLater: Boolean) -> Unit
    ) {
        viewModelScope.launch {
            // 1 SAVE — pure local, never fails on network.
            val activity = activityDao.getById(activityId)
            if (activity == null) {
                onSaved(false)
                return@launch
            }
            if (title.isNotBlank() && title != activity.name) {
                activityDao.update(activity.copy(name = title.trim()))
            }

            // 2 AUTO-BACKUP — silent, same contract as scheduleActivityBackup.
            com.nyasar.app.backup.BackupManager.scheduleActivityBackup(
                getApplication(), activityId
            )

            // 3 PUBLISH — only when the user left it enabled and an account
            //    exists. Offline/failed → queue for the next flush.
            if (!canPublish) {
                enqueuePending(activityId, difficulty, difficultyDescription, trailType, description, isPublic)
                onSaved(true)
                return@launch
            }
            val queued = !performPublish(activityId, difficulty, difficultyDescription, trailType, description, isPublic)
            if (queued) {
                enqueuePending(activityId, difficulty, difficultyDescription, trailType, description, isPublic)
            }
            onSaved(queued)
        }
    }

    /** Publish an existing activity now (queue-flush + explicit paths).
     *  Returns true when the outcome was TRANSIENT (queued for retry:
     *  offline/timeout) — false means done (success/duplicate) or
     *  permanently rejected (queue row dropped). */
    private suspend fun performPublish(
        activityId: String,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        isPublic: Boolean
    ): Boolean {
        val activity: ActivityEntity = activityDao.getById(activityId) ?: return false
        val points = activityDao.getPoints(activityId)
        val outcome = repository.publish(
            getApplication(), PublishRepository.PublishInput(
                activity = activity,
                points = points,
                waypoints = waypointUnion(activity),
                difficulty = difficulty.toApi(),
                difficultyDescription = difficultyDescription,
                trailType = trailType.toApi(),
                description = description,
                isPublic = isPublic
            )
        )
        return when (outcome) {
            is PublishRepository.PublishOutcome.Success,
            is PublishRepository.PublishOutcome.AlreadyPublished -> {
                pendingPublishDao.dequeue(activityId)
                false
            }
            is PublishRepository.PublishOutcome.Failure -> when (outcome.error) {
                PublishError.NETWORK -> true // transient — keep/queue for flush
                else -> {
                    // EMPTY_TRACK/INSERT on one bad activity must not wedge
                    // the queue forever — drop and log.
                    Log.w(TAG, "publish permanently rejected ($activityId): ${outcome.error}")
                    pendingPublishDao.dequeue(activityId)
                    _state.value = PublishState.Idle(outcome.error.toUi())
                    false
                }
            }
        }
    }

    /** The union ActivityDetail publishes with (created-during + linked),
     *  recomputed here so the flush path needs no UI state. */
    private suspend fun waypointUnion(activity: ActivityEntity): List<WaypointEntity> {
        val createdDuring = runCatching {
            waypointRepository.getCreatedBetween(
                activity.startedAtEpochMs,
                activity.endedAtEpochMs ?: System.currentTimeMillis()
            )
        }.getOrDefault(emptyList())
        val linked = runCatching { waypointRepository.getForActivity(activity.id) }
            .getOrDefault(emptyList())
        return (createdDuring + linked).distinctBy { it.id }
    }

    private suspend fun enqueuePending(
        activityId: String,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        isPublic: Boolean
    ) {
        pendingPublishDao.enqueue(
            PendingPublishEntity(
                activityId = activityId,
                difficulty = difficulty.toApi(),
                difficultyDescription = difficultyDescription?.trim()?.takeIf { it.isNotEmpty() },
                trailType = trailType.toApi(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                isPublic = isPublic,
                queuedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Drains the offline publish queue — fired by the login initial-sync
     * and the app-start online flush. One row at a time, oldest first;
     * NETWORK aborts the pass (next flush retries), permanent rejections
     * dequeue so the queue never wedges.
     */
    suspend fun flushPendingPublishes() {
        if (!canPublish) return
        var guard = 0
        while (guard++ < MAX_FLUSH_BATCH) {
            val row = pendingPublishDao.takeOldest() ?: return
            val activity = activityDao.getById(row.activityId)
            if (activity == null) {
                // Source activity was deleted/discard-recovered while queued.
                pendingPublishDao.dequeue(row.activityId)
            } else {
                // Re-derive enum round-trip safely (schema literals → enum or NONE).
                val difficulty = enumValueOrNone<PublishDifficulty>(row.difficulty)
                val trailType = enumValueOrNone<PublishTrailType>(row.trailType)
                val transient = performPublish(
                    activityId = row.activityId,
                    difficulty = difficulty,
                    difficultyDescription = row.difficultyDescription,
                    trailType = trailType,
                    description = row.description,
                    isPublic = row.isPublic
                )
                if (transient) return // offline again — finish the flush quietly
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNone(name: String?): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: T::class.java.enumConstants.first()

    /** Publish an existing activity explicitly (kept for future callers;
     *  the UI path is now save-and-publish). */
    fun publish(
        activityId: String,
        /** Same list ActivityDetail uses for Export/Share (created-during +
         *  linked union, already deduped) — publish never recomputes it. */
        waypoints: List<WaypointEntity>,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        /** Wikiloc 2-level visibility chosen in the publish form. */
        isPublic: Boolean = true
    ) {
        if (_state.value is PublishState.Publishing) return
        _state.value = PublishState.Publishing
        viewModelScope.launch {
            _state.value = if (performPublish(activityId, difficulty, difficultyDescription, trailType, description, isPublic)) {
                PublishState.Idle(PublishUiError.NETWORK)
            } else {
                val state = _state.value
                state as? PublishState.Idle ?: PublishState.Idle()
            }
        }
    }

    /**
     * Publish an IMPORTED GPX route from the Library (the only remaining
     * manual publish UI). Loads the RouteEntity + its local GPX, builds
     * the polyline summary from the parsed track, and uploads the ORIGINAL
     * GPX file verbatim.
     */
    fun publishRoute(
        routeId: String,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        /** Wikiloc 2-level visibility chosen in the publish form. */
        isPublic: Boolean = true
    ) {
        if (_state.value is PublishState.Publishing) return
        _state.value = PublishState.Publishing
        viewModelScope.launch {
            val route = routeDao.getById(routeId)
                ?: return@launch resetWith(PublishUiError.GENERIC)
            // Parse the stored GPX once: the track feeds track_polyline (the
            // queryable summary); the uploaded file is the untouched original.
            val trackPoints = try {
                routeRepository.loadDocument(route).allTrackPoints
            } catch (_: Exception) {
                return@launch resetWith(PublishUiError.GENERIC)
            }
            if (trackPoints.isEmpty()) {
                return@launch resetWith(PublishUiError.EMPTY_TRACK)
            }
            val outcome = repository.publishRoute(
                PublishRepository.RoutePublishInput(
                    route = route,
                    trackPoints = trackPoints.map { it.lat to it.lon },
                    difficulty = difficulty.toApi(),
                    difficultyDescription = difficultyDescription,
                    trailType = trailType.toApi(),
                    description = description,
                    isPublic = isPublic
                )
            )
            _state.value = when (outcome) {
                is PublishRepository.PublishOutcome.Success ->
                    PublishState.Success(outcome.routeId, outcome.gpxUrl)
                is PublishRepository.PublishOutcome.AlreadyPublished ->
                    PublishState.Success(outcome.existingRouteId, "")
                is PublishRepository.PublishOutcome.Failure ->
                    PublishState.Idle(outcome.error.toUi())
            }
        }
    }

    /** Back to a clean idle form (clears any error banner). */
    fun reset() {
        _state.value = PublishState.Idle()
    }

    /** Headless flush entry for [PendingPublishFlusher] — launches on the
     *  ViewModel's own scope and never surfaces UI state. */
    fun flushInScope() {
        viewModelScope.launch { runCatching { flushPendingPublishes() } }
    }

    private fun resetWith(error: PublishUiError) {
        _state.value = PublishState.Idle(error)
    }

    companion object {
        private const val TAG = "PublishViewModel"

        /** Bound the flush pass so a huge queue can't monopolize IO. */
        private const val MAX_FLUSH_BATCH = 50
    }
}
