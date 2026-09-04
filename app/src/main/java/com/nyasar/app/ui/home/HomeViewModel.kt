package com.nyasar.app.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.gpx.GpxParseException
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.map.TileProvider
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * P3: map is now the home screen (spec Section 10), so this ViewModel owns
 * the live GPS stream + layer/follow state on top of what HomeViewModel
 * already had (route list + GPX import, both untouched -- spec Section 14:
 * "jangan membuat parser GPX kedua").
 *
 * Location collection here is a plain LocationRepository subscription, not
 * shared with RecordingService -- Home isn't recording anything, it's just
 * showing "where am I" on the map, so there is no second-GPS-source concern
 * (that rule is specifically about recording+navigation sharing one stream,
 * see NavigationViewModel).
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RouteRepository(app)
    private val locationRepository = LocationRepository(app)
    private val settingsRepository = SettingsRepository(app)

    val routes = repository.observeRoutes()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    private val _currentLocation = MutableStateFlow<GpsFix?>(null)
    val currentLocation: StateFlow<GpsFix?> = _currentLocation.asStateFlow()

    private val _followMode = MutableStateFlow(true)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    // North-up vs heading-up (spec-adjacent UX: tapping recenter while
    // already following toggles orientation instead of doing nothing --
    // same pattern as Strava/Gaia GPS). Only meaningful while followMode is
    // true; onUserPanned() below resets it off so a rotated camera never
    // lingers after the user takes manual control.
    private val _rotateWithHeading = MutableStateFlow(false)
    val rotateWithHeading: StateFlow<Boolean> = _rotateWithHeading.asStateFlow()

    private val _provider = MutableStateFlow<TileProvider>(TileProviderFactory.default())
    val provider: StateFlow<TileProvider> = _provider.asStateFlow()

    private val _styleVariant = MutableStateFlow(StyleVariant.OUTDOOR)
    val styleVariant: StateFlow<StyleVariant> = _styleVariant.asStateFlow()

    /** Extended GPX Studio-style basemap selection (null = legacy variant). */
    private val _basemap = MutableStateFlow<BasemapEntry?>(null)
    val basemap: StateFlow<BasemapEntry?> = _basemap.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var locationStarted = false

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _provider.value = TileProviderFactory.byId(settings.providerId)
        }
    }

    /** Called once from HomeScreen after permission is confirmed granted --
     *  mirrors how NavigationViewModel/RecordingService each check
     *  hasLocationPermission() before subscribing, so Home never starts a
     *  FusedLocationProviderClient request without permission. */
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

    /** GPS/recenter button (spec Section 12: "tombol center on location").
     *  Tap while NOT following -> snap back to user, follow ON, north-up.
     *  Tap again while ALREADY following -> toggle heading-up/north-up
     *  instead of re-doing a no-op recenter. */
    fun centerOnLocation() {
        if (_followMode.value) {
            _rotateWithHeading.value = !_rotateWithHeading.value
        } else {
            _followMode.value = true
        }
    }

    /** Manual pan should break follow mode (spec Section 12: "manual pan tetap
     *  memungkinkan") -- called by the map's drag-gesture callback. Also
     *  drops heading-up so the next recenter starts from a known state
     *  (north-up) rather than resuming mid-rotation. */
    fun onUserPanned() {
        _followMode.value = false
        _rotateWithHeading.value = false
    }

    fun cycleLayer() {
        val all = TileProviderFactory.all().filter { it.isConfigured() }
        if (all.isEmpty()) return
        val currentIndex = all.indexOfFirst { it.id == _provider.value.id }
        val next = all[(currentIndex + 1) % all.size]
        _provider.value = next
    }

    fun setStyleVariant(variant: StyleVariant) {
        _styleVariant.value = variant
        _basemap.value = null // back to the legacy variant pipeline
    }

    fun setBasemap(entry: BasemapEntry) {
        _basemap.value = entry
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * @param onImported called with the new route's id on success -- used both
     * by the manual "Import GPX" button (stays on Home) and by the
     * Open-With/Share auto-import flow (navigates straight to preview).
     */
    fun importGpx(uri: Uri, onImported: (String) -> Unit = {}) {
        viewModelScope.launch {
            _importError.value = null
            try {
                val displayName = queryDisplayName(uri)
                val route = repository.importFromUri(uri, displayName)
                onImported(route.id)
            } catch (e: GpxParseException) {
                _importError.value = e.message
            } catch (e: Exception) {
                _importError.value = "Gagal mengimpor GPX: ${e.message}"
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) it.getString(idx) else null
        }
    }

    /** Route Library gap: RouteRepository.delete() already existed, nothing
     *  in the UI ever called it — a route could be imported but never
     *  removed. */
    fun deleteRoute(route: com.nyasar.app.data.db.RouteEntity) {
        viewModelScope.launch { repository.delete(route) }
    }
}
