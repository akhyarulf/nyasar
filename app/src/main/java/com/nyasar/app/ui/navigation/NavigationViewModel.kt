package com.nyasar.app.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.repository.WaypointRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.gpx.model.GpxWaypoint
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.location.HeadingProvider
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.map.TileProvider
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.GpsFix
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.navigation.NavigationEngine
import com.nyasar.app.recording.RecordingService
import com.nyasar.app.recording.RecordingServiceConnection
import com.nyasar.app.recording.RecordingStatus
import com.nyasar.app.ui.components.CameraFollowMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NavigationUiState(
    val track: List<TrackPoint> = emptyList(),
    val waypoints: List<GpxWaypoint> = emptyList(),
    val userLocation: GpsFix? = null,
    val distanceTraveledMeters: Double = 0.0,
    val distanceFromTrackMeters: Double = 0.0,
    val currentElevationM: Double? = null,
    val elevationGainSoFarM: Double = 0.0,
    /** Elevation gain still ahead on the planned route, from the user's
     *  current position to the end (P3E1: "Remaining Elevation jika bisa
     *  dihitung"). Null when the route has no elevation data to compute it
     *  from — never fabricated. */
    val remainingElevationGainM: Double? = null,
    val currentSpeedKmh: Double? = null,
    val movingTimeMs: Long = 0,
    val provider: TileProvider = TileProviderFactory.default(),
    /** Nearest waypoint still ahead on the route (P3E3). Null once the
     *  route has no more waypoints ahead, or the route has none at all —
     *  never a stale/last-known one left over from before the user passed
     *  it. */
    val nextWaypoint: NextWaypoint? = null,
    /** Heading actually used for Heading-Up / marker arrow — device sensor
     *  when available, GPS bearing fallback only while moving (spec §38).
     *  Kept separate from [userLocation]'s raw bearingDeg so the map layer
     *  and camera never rotate off noisy GPS bearing while the user is
     *  standing still. */
    val displayHeadingDeg: Float? = null
)

/**
 * P3E3: "NEXT WAYPOINT" panel data. Distance/elevationDiff are always from
 * the user's live position (great-circle straight line, not along-track —
 * a waypoint is rarely exactly on the track line itself, e.g. a shelter a
 * few meters off-trail), direction is the compass bearing to head there.
 * elevationDiffM is null exactly when either endpoint's elevation is
 * unknown — never a fabricated 0.
 */
data class NextWaypoint(
    val waypoint: NavWaypointRef,
    val distanceMeters: Double,
    val bearingDeg: Double,
    val elevationDiffM: Double?
)

/**
 * Minimal shape both [GpxWaypoint] (parsed from the route file) and
 * [com.nyasar.app.data.db.WaypointEntity] (user-created, P3E2) can be
 * viewed through for NEXT WAYPOINT purposes — fix #1: previously only a
 * GpxWaypoint could ever become the next waypoint, so a hiker's own pins
 * (Pos 1, Sumber Air, ...) were invisible to navigation entirely. This
 * isn't a new persisted model or a second waypoint system — just a
 * read-only view used right here to merge the two existing sources
 * without changing either's real schema/architecture.
 */
data class NavWaypointRef(
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevationM: Double?
)

/**
 * GPS source: if RecordingService is already running (Start Activity flow
 * with both Recording + Navigation on, Task 6/7), this subscribes to its
 * [RecordingService.addFixListener] instead of opening a second, redundant
 * FusedLocationProviderClient stream — one GPS source feeds both engines.
 * If recording isn't running (plain "Mulai Navigasi" with no recording),
 * it falls back to its own LocationRepository subscription exactly as
 * before, so standalone navigation is unaffected.
 */
class NavigationViewModel(app: Application) : AndroidViewModel(app) {

    private val routeRepository = RouteRepository(app)
    private val locationRepository = LocationRepository(app)
    private val settingsRepository = SettingsRepository(app)
    private val recordingConnection = RecordingServiceConnection(app)
    private val headingProvider = HeadingProvider(app)
    // Not a new/duplicate waypoint system — same WaypointRepository/table
    // Home and the map's long-press Add-Waypoint flow already use (P3E3
    // fix #1). Only read here, to fold user waypoints into NEXT WAYPOINT.
    private val waypointRepository = WaypointRepository(app)
    private var engine: NavigationEngine? = null
    private var sharedFixListener: ((GpsFix) -> Unit)? = null
    private var boundRecordingService: RecordingService? = null

    // Sensor heading is primary; GPS bearing only substitutes for it while
    // the sensor hasn't reported yet AND the device is actually moving
    // (spec §38 — GPS bearing is unreliable while stationary).
    private var lastSensorHeadingDeg: Float? = null
    private var lastGpsFix: GpsFix? = null

    init {
        if (headingProvider.hasOrientationSensor()) {
            viewModelScope.launch {
                headingProvider.observeHeading().collect { heading ->
                    // Skip pushing imperceptible changes into state — cuts
                    // down on recomposition/marker-redraw churn from a
                    // ~16Hz sensor stream when the device is essentially
                    // still (spec §37 point 5: don't burn battery/CPU on
                    // sensor noise that has no visible effect).
                    val prev = lastSensorHeadingDeg
                    if (prev != null) {
                        var delta = heading - prev
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        if (kotlin.math.abs(delta) < 0.5f) return@collect
                    }
                    lastSensorHeadingDeg = heading
                    _uiState.value = _uiState.value.copy(displayHeadingDeg = resolveDisplayHeading())
                }
            }
        }
    }

    private fun resolveDisplayHeading(): Float? {
        lastSensorHeadingDeg?.let { return it }
        // No sensor reading yet (or device has none) — fall back to GPS
        // bearing, but only while genuinely moving; a stationary/no-bearing
        // fix keeps the last known heading instead of resetting to null.
        val fix = lastGpsFix ?: return null
        val isMoving = (fix.speedMps ?: 0f) > 0.3f
        return if (isMoving) fix.bearingDeg else null
    }

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    // Recenter button cycles through 3 states, same as Google Maps'
    // location button, instead of the old followMode Boolean +
    // rotateWithHeading Boolean pair. That pair allowed a 4th combination
    // (rotateWithHeading=true, followMode=false) that made no sense on
    // screen — heading-up rotation with no active follow just left the
    // map spinning around a point the user had already panned away from.
    // A single enum makes that combination unrepresentable.
    // Bug fix: defaults to FOLLOW_NORTH_UP, not FREE — same reasoning as
    // RecordingViewModel's identical fix (see its comment). Opening
    // Navigation should snap to the user's GPS position as soon as it's
    // available, not sit on MapLibre's raw world-view camera until they
    // manually tap recenter.
    private val _cameraMode = MutableStateFlow(CameraFollowMode.FOLLOW_NORTH_UP)
    val cameraMode: StateFlow<CameraFollowMode> = _cameraMode.asStateFlow()

    /** Kept for callers/composables still reading a plain follow flag
     *  (e.g. NyasarMapView's followUser param) — derived, not a separate
     *  source of truth, so it can never drift from [cameraMode]. */
    val followMode: StateFlow<Boolean> = _cameraMode
        .map { it != CameraFollowMode.FREE }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /** Same derivation for the heading-up flag NyasarMapView expects. */
    val rotateWithHeading: StateFlow<Boolean> = _cameraMode
        .map { it == CameraFollowMode.FOLLOW_HEADING }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun onUserPanned() {
        // A manual drag/pinch always drops all the way to FREE, from
        // either follow state — matches spec: "saat user menggeser/zoom
        // manual -> Follow GPS harus OFF", regardless of which follow
        // variant was active.
        _cameraMode.value = CameraFollowMode.FREE
    }

    /** Tapping the recenter button advances one step:
     *  FREE -> FOLLOW_NORTH_UP -> FOLLOW_HEADING -> FREE -> ...
     *  Mirrors Google Maps' own location-button cycle so the behavior
     *  needs no explanation the first time someone uses it. */
    fun recenter() {
        _cameraMode.value = when (_cameraMode.value) {
            CameraFollowMode.FREE -> CameraFollowMode.FOLLOW_NORTH_UP
            CameraFollowMode.FOLLOW_NORTH_UP -> CameraFollowMode.FOLLOW_HEADING
            CameraFollowMode.FOLLOW_HEADING -> CameraFollowMode.FREE
        }
    }

    /** Compass tap still means "reset to north" specifically, not "cycle
     *  modes" — if currently rotating with heading, dropping to
     *  FOLLOW_NORTH_UP is the correct one-step-back, not going all the way
     *  to FREE (which would also stop centering on the user, and a compass
     *  tap was never meant to do that). */
    fun resetToNorth() {
        if (_cameraMode.value == CameraFollowMode.FOLLOW_HEADING) {
            _cameraMode.value = CameraFollowMode.FOLLOW_NORTH_UP
        }
    }

    private var started = false
    // Full-route elevation profile, computed once at start() — reused per
    // fix to compute "remaining gain from here to the end" without
    // re-walking the whole track on every GPS update.
    private var routeElevationProfile: List<com.nyasar.app.ui.components.ElevationPoint> = emptyList()

    /** Route-file (GPX) waypoints paired with their fixed along-track
     *  distance, computed once in [start] — waypoints don't move, so
     *  there's no reason to re-match them on every fix. */
    private var gpxWaypointsAlongTrack: List<Pair<NavWaypointRef, Double>> = emptyList()

    /** User-created waypoints (P3E2/[WaypointEntity]) paired with their
     *  along-track distance — unlike the GPX list above, this is NOT
     *  computed once: the user can drop a new pin mid-hike (long-press on
     *  this very screen), so it's recomputed every time WaypointRepository
     *  emits a new list (see [start]). Only waypoints within
     *  [MAX_WAYPOINT_TRACK_DISTANCE_M] of the track are kept — otherwise a
     *  pin from a completely unrelated hike could still mathematically
     *  project onto this track's line and get treated as "ahead". */
    private var userWaypointsAlongTrack: List<Pair<NavWaypointRef, Double>> = emptyList()

    private val waypointsAlongTrack: List<Pair<NavWaypointRef, Double>>
        get() = gpxWaypointsAlongTrack + userWaypointsAlongTrack

    fun start(routeId: String) {
        if (started) return
        started = true

        viewModelScope.launch {
            val route = routeRepository.getRoute(routeId) ?: return@launch
            val doc = routeRepository.loadDocument(route)
            routeRepository.markOpened(routeId)

            // Settings drives which map style is drawn — read once at nav
            // start; changing it mid-hike is a P1 concern (would need a
            // live restart of the engine, deliberately not done here to
            // keep state simple).
            val settings = settingsRepository.settings.first()
            val provider = TileProviderFactory.byId(settings.providerId)

            val trackPoints = doc.allTrackPoints
            val navEngine = NavigationEngine(trackPoints)
            engine = navEngine
            routeElevationProfile = ElevationStats.toElevationProfile(trackPoints)

            // P3E3: precompute each GPX waypoint's along-track distance once
            // here (reusing the same TrackMatcher the engine already
            // built), not per GPS fix.
            gpxWaypointsAlongTrack = doc.waypoints.mapNotNull { wp ->
                navEngine.distanceAlongTrackMeters(
                    com.nyasar.app.navigation.LatLng(wp.lat, wp.lon)
                )?.let { NavWaypointRef(wp.name, wp.lat, wp.lon, wp.elevationM) to it }
            }

            // P3E3 fix #1: user-created waypoints as NEXT WAYPOINT
            // candidates too, not only GPX ones. Recomputed on every
            // change to the waypoint table (add/edit/delete — including
            // one dropped on this very screen mid-navigation), not a
            // one-shot snapshot like the GPX list above.
            viewModelScope.launch {
                waypointRepository.observeAll().collect { userWaypoints ->
                    userWaypointsAlongTrack = userWaypoints.mapNotNull { wp ->
                        val match = navEngine.matchWaypoint(
                            com.nyasar.app.navigation.LatLng(wp.lat, wp.lon)
                        ) ?: return@mapNotNull null
                        if (match.distanceFromTrackMeters > MAX_WAYPOINT_TRACK_DISTANCE_M) return@mapNotNull null
                        NavWaypointRef(wp.name, wp.lat, wp.lon, wp.elevationM) to match.distanceTraveledMeters
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                track = trackPoints,
                waypoints = doc.waypoints,
                provider = provider
            )

            if (!locationRepository.hasLocationPermission()) return@launch

            // Try the shared-GPS path first (recording already running for
            // this session, i.e. user picked Route + Recording + Navigation
            // together in Start Activity). Only *bind* — never start —
            // RecordingService here: if it isn't already running (plain
            // "Mulai Navigasi" with recording off), binding must not spin
            // it up just to read its state, or navigation-only sessions
            // would silently create empty recording rows.
            val activeService = if (isRecordingServiceRunning()) {
                recordingConnection.bind(autoCreate = false)
                recordingConnection.service.first { it != null }
            } else {
                null
            }

            if (activeService != null && activeService.state.value.status != RecordingStatus.STOPPED) {
                boundRecordingService = activeService
                val listener: (GpsFix) -> Unit = { fix -> onFix(fix) }
                sharedFixListener = listener
                activeService.addFixListener(listener)
            } else {
                locationRepository.observeLocation().collect { fix -> onFix(fix) }
            }
        }
    }

    private fun onFix(fix: GpsFix) {
        lastGpsFix = fix
        _uiState.value = _uiState.value.copy(
            userLocation = fix,
            displayHeadingDeg = resolveDisplayHeading()
        )
        val nav = engine?.onGpsFix(fix) ?: return
        _uiState.value = _uiState.value.copy(
            distanceTraveledMeters = nav.distanceTraveledMeters,
            distanceFromTrackMeters = nav.distanceFromTrackMeters,
            currentElevationM = nav.currentElevationM,
            elevationGainSoFarM = nav.elevationGainSoFarM,
            remainingElevationGainM = remainingElevationGain(nav.distanceTraveledMeters),
            currentSpeedKmh = nav.currentSpeedKmh,
            movingTimeMs = nav.movingTimeMs,
            nextWaypoint = nextWaypoint(fix, nav.distanceTraveledMeters)
        )
    }

    /**
     * Nearest waypoint the user hasn't reached yet (P3E3). "Ahead" is
     * defined by along-track distance (smallest waypointDistance that's
     * still >= the user's own along-track progress) rather than straight-
     * line distance, so a waypoint just behind the user on a switchback
     * isn't picked over one further away but still ahead on the path.
     * Once past a waypoint's along-track position it drops out of
     * consideration entirely — never shown as "next" again this session.
     */
    private fun nextWaypoint(fix: GpsFix, distanceTraveledMeters: Double): NextWaypoint? {
        val (waypoint, _) = waypointsAlongTrack
            .filter { (_, alongTrack) -> alongTrack >= distanceTraveledMeters }
            .minByOrNull { (_, alongTrack) -> alongTrack }
            ?: return null

        val userLatLng = com.nyasar.app.navigation.LatLng(fix.lat, fix.lon)
        val wpLatLng = com.nyasar.app.navigation.LatLng(waypoint.lat, waypoint.lon)
        val elevationDiff = if (fix.elevationM != null && waypoint.elevationM != null) {
            waypoint.elevationM - fix.elevationM
        } else null

        return NextWaypoint(
            waypoint = waypoint,
            distanceMeters = com.nyasar.app.navigation.GeoMath.distanceMeters(userLatLng, wpLatLng),
            bearingDeg = com.nyasar.app.navigation.GeoMath.bearingDegrees(userLatLng, wpLatLng),
            elevationDiffM = elevationDiff
        )
    }

    /**
     * Elevation still to climb from the user's current position to the end
     * of the planned route (P3E1). Reuses the same ElevationStats.summarize
     * gain calculation as everywhere else in the app — sliced to only the
     * remaining portion of [routeElevationProfile] — so this number is
     * computed the same way as every other gain figure, not a bespoke
     * formula. Returns null (never a fabricated 0) when there's no
     * elevation data on the remaining portion of the route to compute from.
     *
     * Filters by cumulative distance rather than TrackMatcher's raw point
     * index: routeElevationProfile has already dropped any track points
     * without elevation (see ElevationStats.toElevationProfile), so its
     * indices don't line up with the original track's indices whenever a
     * point in between lacks elevation. Distance is the one coordinate
     * both representations agree on.
     */
    private fun remainingElevationGain(distanceTraveledMeters: Double): Double? {
        if (routeElevationProfile.isEmpty()) return null
        val remaining = routeElevationProfile.filter { it.distanceMeters >= distanceTraveledMeters }
        if (remaining.size < 2) return null
        var gain = 0.0
        for (i in 1 until remaining.size) {
            val delta = remaining[i].elevationM - remaining[i - 1].elevationM
            if (delta > 2.0) gain += delta // same 2m noise floor used throughout
        }
        return gain
    }

    @Suppress("DEPRECATION") // getRunningServices is deprecated for querying other apps'
    // services, but querying our own process's own service (same package) is the
    // documented still-fine use case and there's no non-deprecated equivalent for it.
    private fun isRecordingServiceRunning(): Boolean {
        val manager = getApplication<Application>()
            .getSystemService(android.app.ActivityManager::class.java) ?: return false
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == RecordingService::class.java.name
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stopping navigation must not touch the recording session running
        // in RecordingService — only unregister this listener/binding.
        sharedFixListener?.let { boundRecordingService?.removeFixListener(it) }
        recordingConnection.unbind()
    }

    companion object {
        /** How far off the planned track a user-created waypoint can be
         *  and still count as "belongs to this route" for NEXT WAYPOINT
         *  purposes (P3E3 fix #1) — generous enough for a shelter/water
         *  source a short detour from the trail, tight enough to exclude
         *  a pin dropped on an unrelated hike elsewhere. GPX waypoints
         *  skip this check entirely: they're authored inside the route
         *  file itself, so they're never "unrelated" to it. */
        private const val MAX_WAYPOINT_TRACK_DISTANCE_M = 400.0
    }
}
