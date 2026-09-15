package com.nyasar.app.ui.publish

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.ActivityDao
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.db.WaypointEntity
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
 * Bridges ActivityDetail to the Supabase publish pipeline: gathers the
 * activity + points + activity-scoped waypoints from Room exactly the way
 * GpxExporter gets them (same data, same order), applies the form fields,
 * and runs [PublishRepository.publish] on the ViewModel scope.
 */
class PublishViewModel(app: Application) : AndroidViewModel(app) {

    private val activityDao: ActivityDao = AppDatabase.get(app).activityDao()
    private val repository = PublishRepository()

    private val _state = MutableStateFlow<PublishState>(PublishState.Idle())
    val state: StateFlow<PublishState> = _state.asStateFlow()

    /** True only when this build has Supabase credentials AND a session. */
    val canPublish: Boolean
        get() = SupabaseClientProvider.isConfigured &&
            SupabaseClientProvider.client.auth.currentUserOrNull() != null

    fun publish(
        activityId: String,
        /** Same list ActivityDetail uses for Export/Share (created-during +
         *  linked union, already deduped) — publish never recomputes it. */
        waypoints: List<WaypointEntity>,
        mountainName: String?,
        region: String?,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?
    ) {
        if (_state.value is PublishState.Publishing) return
        _state.value = PublishState.Publishing
        viewModelScope.launch {
            val activity: ActivityEntity = activityDao.getById(activityId)
                ?: return@launch resetWith(PublishUiError.GENERIC)
            val points = activityDao.getPoints(activityId)
            val outcome = repository.publish(
                getApplication(), PublishRepository.PublishInput(
                    activity = activity,
                    points = points,
                    waypoints = waypoints,
                    mountainName = mountainName,
                    region = region,
                    difficulty = difficulty.toApi(),
                    difficultyDescription = difficultyDescription,
                    trailType = trailType.toApi(),
                    description = description
                )
            )
            _state.value = when (outcome) {
                is PublishRepository.PublishOutcome.Success ->
                    PublishState.Success(outcome.routeId, outcome.gpxUrl)
                is PublishRepository.PublishOutcome.Failure ->
                    PublishState.Idle(outcome.error.toUi())
            }
        }
    }

    /** Back to a clean idle form (clears any error banner). */
    fun reset() {
        _state.value = PublishState.Idle()
    }

    private fun resetWith(error: PublishUiError) {
        _state.value = PublishState.Idle(error)
    }
}
