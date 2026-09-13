package com.nyasar.app.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.repository.WaypointRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.gpx.model.GpxWaypoint
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.OverlayLayer
import com.nyasar.app.map.TileProvider
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.GpsFix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RoutePreviewUiState(
    val name: String? = null,
    val distanceKm: Double = 0.0,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val highestElevationM: Double? = null,
    val lowestElevationM: Double? = null,
    val waypointCount: Int = 0,
    val track: List<TrackPoint> = emptyList(),
    val waypoints: List<GpxWaypoint> = emptyList(),
    val provider: TileProvider = TileProviderFactory.default(),
    /** Path to the route's original stored GPX (RouteRepository.gpxFile) —
     *  kept here so Route Preview's Share button can hand the file straight
     *  off to FileProvider without re-deriving it in the UI layer. */
    val gpxFilePath: String? = null
)

class RoutePreviewViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RouteRepository(app)
    private val settingsRepository = SettingsRepository(app)
    private val locationRepository = LocationRepository(app)
    private val waypointRepository = WaypointRepository(app)

    private val _uiState = MutableStateFlow(RoutePreviewUiState())
    val uiState: StateFlow<RoutePreviewUiState> = _uiState.asStateFlow()

    // --- GPS state (mirrors HomeViewModel pattern) ---

    private val _currentLocation = MutableStateFlow<GpsFix?>(null)
    val currentLocation: StateFlow<GpsFix?> = _currentLocation.asStateFlow()

    private val _followMode = MutableStateFlow(false)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    private val _rotateWithHeading = MutableStateFlow(false)
    val rotateWithHeading: StateFlow<Boolean> = _rotateWithHeading.asStateFlow()

    // Basemap picker (9-entry World catalog): ONE persisted selection shared
    // with Home and Recording via SettingsRepository's DataStore. RoutePreview
    // previously held this as a plain `remember { mutableStateOf(...) }` in
    // the Screen — a fresh local every time the destination was entered, so a
    // basemap picked on Home never showed here and a pick here never stuck
    // anywhere else. Now the same DataStore row as the other two screens feeds
    // this state (and setBasemap writes it back), so the selection follows the
    // user across screens AND process restarts.
    val selectedBasemap: StateFlow<BasemapEntry> = settingsRepository.settings
        .map { BasemapEntry.fromId(it.basemapId) }

    /** Downloaded-areas overlay flag — shared app-wide, same DataStore as
     *  Home/Recording so all map screens render the same picture. */
    val offlineOverlayEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.offlineOverlayEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Live coverage snapshot from the process-wide store. */
    val offlineAreas: StateFlow<List<com.nyasar.app.map.OfflineCoverageArea>> =
        com.nyasar.app.map.OfflineCoverageStore.get(getApplication()).areas
        .stateIn(viewModelScope, SharingStarted.Eagerly, BasemapEntry.LIBERTY_TOPO)

    fun setBasemap(entry: BasemapEntry) {
        viewModelScope.launch { settingsRepository.setBasemapId(entry.gpxKey) }
    }

    // Tile provider from the same persisted setting Home/Recording read —
    // previously the Screen's `remember { mutableStateOf(state.provider) }`
    // froze on RoutePreviewUiState's pre-load default. Consistent provider
    // across all 3 shared-map screens is also required for style-key
    // equality (see RecordingViewModel.provider).
    val provider: StateFlow<TileProvider> = settingsRepository.settings
        .map { TileProviderFactory.byId(it.providerId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TileProviderFactory.default())

    // Waymarked Trails overlays: shared + persisted, same reasoning as
    // HomeViewModel.activeOverlays — with one shared MapView, per-screen
    // overlay sets would visibly strip/restore overlays on every switch.
    val activeOverlays: StateFlow<Set<OverlayLayer>> = settingsRepository.settings
        .map { prefs ->
            prefs.overlayIds.mapNotNull { id ->
                OverlayLayer.entries.firstOrNull { it.id == id }
            }.toSet()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleOverlay(overlay: OverlayLayer) {
        val current = activeOverlays.value
        val next = if (overlay in current) current - overlay else current + overlay
        viewModelScope.launch { settingsRepository.setOverlayIds(next.map { it.id }.toSet()) }
    }

    // "Jalur Saya" overlay (MyRoutesOverlay): persisted app-wide like the
    // Waymarked overlays — with ONE shared MapView the flag must come from
    // the same DataStore on all 3 map screens or the last-mounted screen
    // would decide visibility for everyone. Default false (user opt-in).
    val myRoutesOverlayEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.myRoutesOverlayEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setMyRoutesOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMyRoutesOverlayEnabled(enabled) }
    }

    /** Saved routes as render-ready lines for the map — the enabled gate
     *  and route-id null filter live inside the repository flow; GPX
     *  parsing/decimation happens there on Dispatchers.IO and only runs
     *  while the overlay is ON. Same source as Home/Recording (one shared
     *  MapView + one DataStore), so overlay state is identical everywhere.
     *  The route being previewed is accented via [NyasarMapView]'s
     *  activeRouteId param, not through this flow. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val myRouteLines: StateFlow<List<com.nyasar.app.map.MyRouteLine>> =
        myRoutesOverlayEnabled
            .flatMapLatest { enabled ->
                if (enabled) repository.observeOverlayLines(true) else flowOf(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // v7: DB waypoints for THIS route (GPX-imported rows + user pins linked
    // here) — reactive, so a categorize/edit/delete made right here
    // updates the markers without re-parsing the GPX file.
    // BUG FIX ("pin muncul di Activity Detail tapi tidak di Route Viewer"):
    // this flow used to be observeForRoute ONLY. A pin dropped DURING a
    // recording is linked to the ACTIVITY (the recording context seeds
    // linkedActivityId, not linkedRouteId — see WaypointContext.Recording),
    // and ActivityDetail finds it via its activity union — so the same row
    // rendered there but never here. Route Viewer now also pulls waypoints
    // whose activity link points at an activity recorded along this route
    // (activities.routeId), merged + deduped with the route-linked set.
    private val _currentRouteId = MutableStateFlow<String?>(null)
    internal val currentRouteId: StateFlow<String?> = _currentRouteId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val dbWaypoints: StateFlow<List<WaypointEntity>> = currentRouteId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(emptyList())
            } else {
                kotlinx.coroutines.flow.combine(
                    waypointRepository.observeForRoute(id),
                    waypointRepository.observeForRouteActivities(id)
                ) { routeLinked, activityLinked ->
                    // DISTINCT BY id because a row CAN be in both sets: a
                    // user pin may carry linkedRouteId AND its linkedActivity
                    // may still point at this route's activity (either link
                    // changed via the edit form, or the recording context
                    // attached it to the route while the activity stayed
                    // linked). Order matters for stable list rendering:
                    // route-linked first (GPX import order), then the
                    // activity-only drops by creation time.
                    (routeLinked + activityLinked).distinctBy { it.id }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var locationStarted = false

    fun startLocationUpdatesIfPermitted() {
        if (locationStarted) return
        if (!locationRepository.hasLocationPermission()) return
        locationStarted = true
        viewModelScope.launch {
            locationRepository.observeLocation().collect { fix ->
                _currentLocation.value = fix
            }
        }
    }

    fun hasLocationPermission(): Boolean = locationRepository.hasLocationPermission()

    /** Tap while NOT following -> snap back to user, follow ON.
     *  Tap again while ALREADY following -> toggle heading-up/north-up. */
    fun centerOnLocation() {
        if (_followMode.value) {
            _rotateWithHeading.value = !_rotateWithHeading.value
        } else {
            _followMode.value = true
        }
    }

    /** Manual pan breaks follow mode. */
    fun onUserPanned() {
        _followMode.value = false
        _rotateWithHeading.value = false
    }

    // --- Route data ---

    fun load(routeId: String) {
        viewModelScope.launch {
            val route = repository.getRoute(routeId) ?: return@launch
            _currentRouteId.value = routeId

            // v7 backfill (idempotent): routes imported BEFORE the merge
            // feature have their waypoints only in the file. Previewing one
            // imports them into the DB once; importFromGpx's content dedup
            // makes every later call a no-op, so repeated opens can never
            // stack duplicates.
            try {
                val parsed = repository.loadDocument(route)
                waypointRepository.importFromGpx(
                    routeId = routeId,
                    waypoints = parsed.waypoints,
                    isBackfill = true
                )
            } catch (_: Exception) {
                // corrupt/unreadable file: load() below already handles the
                // missing document gracefully.
            }

            val doc = repository.loadDocument(route)
            val settings = settingsRepository.settings.first()
            _uiState.value = RoutePreviewUiState(
                name = route.name,
                distanceKm = route.distanceMeters / 1000.0,
                elevationGainM = route.elevationGainM,
                elevationLossM = route.elevationLossM,
                highestElevationM = route.highestElevationM,
                lowestElevationM = route.lowestElevationM,
                waypointCount = route.waypointCount,
                track = doc.allTrackPoints,
                waypoints = doc.waypoints,
                provider = TileProviderFactory.byId(settings.providerId),
                gpxFilePath = route.localGpxFilePath
            )
        }
    }
}
