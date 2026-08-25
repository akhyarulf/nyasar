package com.nyasar.app.ui.history

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.ActivityPhotoEntity
import com.nyasar.app.data.db.ActivityPointEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.data.repository.ActivityPhotoRepository
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.repository.WaypointRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.map.TileProvider
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.ElevationStats
import java.io.File
import com.nyasar.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class DetailLoadState { LOADING, LOADED, ERROR, NOT_FOUND }

data class ActivityDetailUiState(
    val loadState: DetailLoadState = DetailLoadState.LOADING,
    val activity: ActivityEntity? = null,
    /** Actual recorded track, converted from ActivityPointEntity — always
     *  shown if points exist, regardless of whether the activity has a
     *  route. */
    val actualTrack: List<TrackPoint> = emptyList(),
    /** The route as originally planned, if this activity has one (P3E3
     *  fix #2: "planned route + actual track + waypoints" together).
     *  Loaded from RouteRepository via [ActivityEntity.routeId] — not
     *  stored on the activity itself, so if the route file was since
     *  deleted/moved this simply stays empty and only actualTrack renders,
     *  same as before this fix. */
    val plannedTrack: List<TrackPoint> = emptyList(),
    /** Route.distanceMeters when this activity has a route (spec P3F §4
     *  "Planned Distance"); null when there's no route, so the comparison
     *  section can be hidden outright (spec: hide, don't show a fabricated
     *  0/blank). */
    val plannedDistanceMeters: Double? = null,
    val elevationProfile: List<TrackPoint> = emptyList(),
    // P3E1: ActivityEntity never stored highest/lowest — computed here from
    // the full point list, which is already loaded for the map/chart, so no
    // schema change or extra query is needed.
    val highestElevationM: Double? = null,
    val lowestElevationM: Double? = null,
    val provider: TileProvider = TileProviderFactory.default(),
    /** Kept alongside actualTrack (which is TrackPoint, lossy) so Export/Share
     *  can write a real GPX without re-querying Room from the UI layer. */
    val rawPoints: List<ActivityPointEntity> = emptyList(),
    /** P3E3 fix #2: waypoints created while this activity was being
     *  recorded — see WaypointDao.getCreatedBetween for why this is a time
     *  window match rather than a stored foreign key. Empty (not an error)
     *  when the user never dropped one during this session. */
    val waypointsDuringActivity: List<WaypointEntity> = emptyList()
)

/**
 * Reads ActivityDao (existing), plus RouteRepository when the activity has
 * a routeId, so ActivityDetail can show planned route + actual track
 * together (P3E3 fix #2) — NyasarMapView already supports a second track
 * layer via its `actualTrack` param (added for live recording), this just
 * starts using that same layer here too instead of drawing the recorded
 * track as if it were the planned route.
 * If a route no longer exists (deleted separately), the activity and its
 * actual track are still shown — not treated as an error, since spec says
 * an activity must not disappear just because its route did; plannedTrack
 * simply stays empty in that case.
 */
class ActivityDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).activityDao()
    private val waypointRepository = WaypointRepository(app)
    private val routeRepository = RouteRepository(app)
    private val settingsRepository = SettingsRepository(app)
    private val photoRepository = ActivityPhotoRepository(app)

    private val _uiState = MutableStateFlow(ActivityDetailUiState())
    val uiState: StateFlow<ActivityDetailUiState> = _uiState.asStateFlow()

    // P3H: own StateFlow, observed independently of the one-shot `load()`
    // above (which re-queries route/track/waypoints — unnecessary work on
    // every photo add/delete). See ActivityPhotoDao.observeForActivity.
    private val _photos = MutableStateFlow<List<ActivityPhotoEntity>>(emptyList())
    val photos: StateFlow<List<ActivityPhotoEntity>> = _photos.asStateFlow()

    // load() is already re-invoked after every waypoint edit/delete (see
    // ActivityDetailScreen) to refresh waypointsDuringActivity — guarding
    // here stops that same re-invocation from stacking up a fresh
    // photoRepository.observeForActivity collector each time.
    private var photosObservedFor: String? = null

    fun load(activityId: String) {
        if (photosObservedFor != activityId) {
            photosObservedFor = activityId
            viewModelScope.launch {
                photoRepository.observeForActivity(activityId).collect { _photos.value = it }
            }
        }
        viewModelScope.launch {
            _uiState.value = ActivityDetailUiState(loadState = DetailLoadState.LOADING)
            try {
                val activity = dao.getById(activityId)
                if (activity == null) {
                    _uiState.value = ActivityDetailUiState(loadState = DetailLoadState.NOT_FOUND)
                    return@launch
                }
                val points = dao.getPoints(activityId)
                val track = points.map { it.toTrackPoint() }
                // BUG FIX: Handle empty track (0 GPS points) gracefully.
                // ElevationStats.summarize() may return null for empty tracks,
                // which is fine — we just won't show elevation stats.
                val elevationSummary = if (track.isNotEmpty()) {
                    ElevationStats.summarize(track)
                } else {
                    null
                }

                // endedAtEpochMs is null only while the activity is still
                // recording/paused (see ActivityEntity) — "now" is the
                // correct upper bound in that case, not a fabricated one.
                val waypointsDuring = waypointRepository.getCreatedBetween(
                    activity.startedAtEpochMs,
                    activity.endedAtEpochMs ?: System.currentTimeMillis()
                )
                val settings = settingsRepository.settings.first()

                val plannedRoute = activity.routeId?.let { routeId ->
                    try {
                        routeRepository.getRoute(routeId)
                    } catch (e: Exception) {
                        null
                    }
                }
                val plannedTrack = plannedRoute?.let { route ->
                    try {
                        routeRepository.loadDocument(route).allTrackPoints
                    } catch (e: Exception) {
                        // Route file missing/unreadable — not fatal, just no
                        // planned line to overlay (see class doc).
                        emptyList()
                    }
                } ?: emptyList()

                // Planned vs Actual (spec P3F §4) — distance only, taken
                // directly from RouteEntity.distanceMeters (computed once at
                // GPX import time, same value shown everywhere else the
                // route appears — Route Preview, Route Library). Reusing it
                // here means zero risk of a second distance algorithm
                // disagreeing with the first. Elevation gain comparison is
                // intentionally left out: the planned route's "elevation
                // gain" depends on which smoothing/threshold the GPX itself
                // encodes (or doesn't), which isn't something this activity
                // recorded — showing it next to the actual (measured) gain
                // would imply a same-basis comparison that isn't actually
                // there (spec: "jangan menampilkan statistik yang tidak
                // dapat dihitung dengan benar").
                val plannedDistanceMeters = plannedRoute?.distanceMeters

                _uiState.value = ActivityDetailUiState(
                    loadState = DetailLoadState.LOADED,
                    activity = activity,
                    actualTrack = track,
                    plannedTrack = plannedTrack,
                    plannedDistanceMeters = plannedDistanceMeters,
                    elevationProfile = track,
                    highestElevationM = elevationSummary?.highestM,
                    lowestElevationM = elevationSummary?.lowestM,
                    provider = TileProviderFactory.byId(settings.providerId),
                    rawPoints = points,
                    waypointsDuringActivity = waypointsDuring
                )
            } catch (e: Exception) {
                _uiState.value = ActivityDetailUiState(loadState = DetailLoadState.ERROR)
            }
        }
    }

    /** Rename (spec P3F §9). Uses the existing ActivityDao.update() — no new
     *  write path. Re-reads nothing else; only the in-memory state's name
     *  field changes, avoiding a full [load] round-trip (which would
     *  needlessly re-query points/route/waypoints just to change a string). */
    fun rename(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val current = _uiState.value.activity ?: return
        val updated = current.copy(name = trimmed)
        viewModelScope.launch {
            dao.update(updated)
            _uiState.value = _uiState.value.copy(activity = updated)
        }
    }

    /** Delete (spec P3F §10, WAJIB confirmation — enforced by the screen,
     *  not here). Deletes points first, then the activity row itself,
     *  mirroring RouteRepository.delete()'s file-then-row order. Does NOT
     *  touch the linked route (activity.routeId) — spec §10 "jika ada file
     *  GPX yang terkait, jangan menghapus file sembarangan"; the route is a
     *  separately-owned entity (see ActivityEntity doc: no FK cascade,
     *  routes survive activity deletion and vice versa). */
    fun delete(onDeleted: () -> Unit) {
        val activity = _uiState.value.activity ?: return
        viewModelScope.launch {
            // P3H spec §21: photo files + associations must go with the
            // activity, not linger as orphans — done before the points/row
            // delete below, mirroring the existing points-then-row order.
            photoRepository.deleteAllForActivity(activity.id)
            dao.deletePointsForActivity(activity.id)
            dao.deleteById(activity.id)
            onDeleted()
        }
    }

    // --- P3H: Activity Photos ---

    /** Step 1 of Take Photo — must resolve before the camera intent can be
     *  launched (it needs a destination Uri up front). Suspend, not a plain
     *  return, since it touches Room (sortOrder) and storage (mkdirs) —
     *  called from a coroutine in the screen via a callback below. */
    suspend fun prepareCameraCapture(activityId: String) = photoRepository.prepareCameraCaptureTarget(activityId)

    // P3I audit fix (§16/§22): all four photo operations below previously
    // called the repository directly inside viewModelScope.launch with no
    // try/catch. A Room insert failure or file I/O error (disk full — the
    // exact scenario spec §16 requires graceful handling for) would throw
    // uncaught inside that coroutine and crash the app, rather than
    // surfacing a clear error as spec §16 requires. Each call is now
    // wrapped, with the failure exposed via [photoError] (one-shot —
    // consumed and cleared by the screen, see clearPhotoError) so the UI
    // can show it as a Toast instead of the app silently going down.
    private val _photoError = MutableStateFlow<String?>(null)
    val photoError: StateFlow<String?> = _photoError.asStateFlow()

    fun clearPhotoError() { _photoError.value = null }

    fun confirmCameraCapture(activityId: String, file: File) {
        viewModelScope.launch {
            try {
                photoRepository.confirmCameraCapture(activityId, file)
            } catch (e: Exception) {
                _photoError.value = getApplication<android.app.Application>().getString(R.string.error_saving_photo)
            }
        }
    }

    fun discardCameraCapture(file: File) {
        viewModelScope.launch {
            try {
                photoRepository.discardCameraCapture(file)
            } catch (e: Exception) {
                // Best-effort cleanup of an already-cancelled capture —
                // nothing meaningful to surface to the user if this fails,
                // the file was never in the DB either way.
            }
        }
    }

    fun addPhotosFromGallery(activityId: String, uris: List<Uri>) {
        viewModelScope.launch {
            try {
                photoRepository.addFromGallery(activityId, uris)
            } catch (e: Exception) {
                _photoError.value = getApplication<android.app.Application>().getString(R.string.error_adding_photo)
            }
        }
    }

    fun deletePhoto(photo: ActivityPhotoEntity) {
        viewModelScope.launch {
            try {
                photoRepository.delete(photo)
            } catch (e: Exception) {
                _photoError.value = getApplication<android.app.Application>().getString(R.string.error_deleting_photo)
            }
        }
    }
}

private fun ActivityPointEntity.toTrackPoint() = TrackPoint(
    lat = lat,
    lon = lon,
    elevationM = elevationM,
    timestampEpochMs = timestampMs
)
