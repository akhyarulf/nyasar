package com.nyasar.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.ActivityPointEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.repository.WaypointRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.map.TileProvider
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class DetailLoadState { LOADING, LOADED, ERROR, NOT_FOUND }

data class GpsQualityMetrics(
    val pointCount: Int = 0,
    val averageAccuracyMeters: Float? = null,
    val bestAccuracyMeters: Float? = null,
    val worstAccuracyMeters: Float? = null,
    val weakPointCount: Int = 0,
    val poorPointCount: Int = 0,
    val largestGapMs: Long = 0L
) {
    val weakPercent: Int get() = if (pointCount == 0) 0 else (weakPointCount * 100) / pointCount
    val poorPercent: Int get() = if (pointCount == 0) 0 else (poorPointCount * 100) / pointCount
}

fun calculateGpsQuality(points: List<ActivityPointEntity>): GpsQualityMetrics {
    if (points.isEmpty()) return GpsQualityMetrics()
    val accuracies = points.map { it.accuracyMeters }
    return GpsQualityMetrics(
        pointCount = points.size,
        averageAccuracyMeters = accuracies.average().toFloat(),
        bestAccuracyMeters = accuracies.minOrNull(),
        worstAccuracyMeters = accuracies.maxOrNull(),
        weakPointCount = points.count { it.accuracyMeters > 30f },
        poorPointCount = points.count { it.accuracyMeters > 100f },
        largestGapMs = points.zipWithNext().maxOfOrNull { (a, b) ->
            (b.timestampMs - a.timestampMs).coerceAtLeast(0L)
        } ?: 0L
    )
}

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
    val gpsQuality: GpsQualityMetrics = GpsQualityMetrics(),
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

    // Layer-picker state for the fullscreen map (2026-09 user request: last
    // map screen without the BasemapPickerSheet). Same app-wide DataStore
    // keys as Home/RoutePreview/browse detail — the map picture is identical
    // everywhere and follows the user across screens.
    val selectedBasemap: StateFlow<com.nyasar.app.map.BasemapEntry> = settingsRepository.settings
        .map { com.nyasar.app.map.BasemapEntry.fromId(it.basemapId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.nyasar.app.map.BasemapEntry.LIBERTY_TOPO)

    fun setBasemap(entry: com.nyasar.app.map.BasemapEntry) {
        viewModelScope.launch { settingsRepository.setBasemapId(entry.gpxKey) }
    }

    val activeOverlays: StateFlow<Set<com.nyasar.app.map.OverlayLayer>> = settingsRepository.settings
        .map { prefs ->
            prefs.overlayIds.mapNotNull { id ->
                com.nyasar.app.map.OverlayLayer.entries.firstOrNull { it.id == id }
            }.toSet()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleOverlay(overlay: com.nyasar.app.map.OverlayLayer) {
        val next = if (overlay in activeOverlays.value) activeOverlays.value - overlay
        else activeOverlays.value + overlay
        viewModelScope.launch { settingsRepository.setOverlayIds(next.map { it.id }.toSet()) }
    }

    val myRoutesOverlayEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.myRoutesOverlayEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setMyRoutesOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMyRoutesOverlayEnabled(enabled) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val myRouteLines: StateFlow<List<com.nyasar.app.map.MyRouteLine>> =
        myRoutesOverlayEnabled
            .flatMapLatest { enabled ->
                if (enabled) routeRepository.observeOverlayLines(true) else flowOf(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(ActivityDetailUiState())
    val uiState: StateFlow<ActivityDetailUiState> = _uiState.asStateFlow()

    fun load(activityId: String) {
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
                // v7: union of (a) pins CREATED during the session window
                // (user drops, honest time-window approximation) and (b)
                // rows explicitly LINKED to this activity (v7 attachment) —
                // deduped by id since a drop during a linked session lands
                // in both sets.
                val createdDuring = waypointRepository.getCreatedBetween(
                    activity.startedAtEpochMs,
                    activity.endedAtEpochMs ?: System.currentTimeMillis()
                )
                val linkedToActivity = waypointRepository.getForActivity(activity.id)
                val waypointsDuring = (createdDuring + linkedToActivity).distinctBy { it.id }
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
                    gpsQuality = calculateGpsQuality(points),
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

    /** Full Edit-Activity (title + publish metadata) — delegates cloud
     *  sync/persistence to [PublishViewModel.editPublished], then re-reads
     *  the row so the screen reflects the new name at once. */
    fun editActivity(
        activityId: String,
        title: String,
        sportType: com.nyasar.app.recording.SportType,
        difficulty: com.nyasar.app.ui.publish.PublishDifficulty,
        difficultyDescription: String?,
        trailType: com.nyasar.app.ui.publish.PublishTrailType,
        description: String?,
        isPublic: Boolean
    ) {
        val vm = com.nyasar.app.ui.publish.PublishViewModel(getApplication())
        viewModelScope.launch {
            vm.editPublished(
                sourceId = activityId,
                isActivity = true,
                title = title,
                sportType = sportType,
                difficulty = difficulty,
                difficultyDescription = difficultyDescription,
                trailType = trailType,
                description = description,
                isPublic = isPublic
            )
            load(activityId)
        }
    }

    /** Wikiloc-style draft → publish now. Delegates to PublishViewModel's
     *  publishDraft (status flip + queue drain); the status flip lands in
     *  Room before this returns, so the chip/menu entry disappear on the
     *  next emission. */
    fun publishDraftNow(activityId: String) {
        val vm = com.nyasar.app.ui.publish.PublishViewModel(getApplication())
        viewModelScope.launch {
            vm.publishDraftBlocking(activityId)
            load(activityId)
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
            // v7: waypoints linked to this activity are unlinked (kept as
            // independent) — deleting an activity must not destroy the
            // user's own pins.
            try {
                waypointRepository.onActivityDeleted(activity.id)
            } catch (_: Exception) {
                // never block activity deletion on waypoint cleanup
            }
            dao.deletePointsForActivity(activity.id)
            dao.deleteById(activity.id)
            onDeleted()
        }
    }

}

private fun ActivityPointEntity.toTrackPoint() = TrackPoint(
    lat = lat,
    lon = lon,
    elevationM = elevationM,
    timestampEpochMs = timestampMs
)
