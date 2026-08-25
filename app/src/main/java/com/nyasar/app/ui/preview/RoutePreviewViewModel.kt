package com.nyasar.app.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.gpx.model.GpxWaypoint
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.map.TileProvider
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private val _uiState = MutableStateFlow(RoutePreviewUiState())
    val uiState: StateFlow<RoutePreviewUiState> = _uiState.asStateFlow()

    // --- GPS state (mirrors HomeViewModel pattern) ---

    private val _currentLocation = MutableStateFlow<GpsFix?>(null)
    val currentLocation: StateFlow<GpsFix?> = _currentLocation.asStateFlow()

    private val _followMode = MutableStateFlow(false)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    private val _rotateWithHeading = MutableStateFlow(false)
    val rotateWithHeading: StateFlow<Boolean> = _rotateWithHeading.asStateFlow()

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
