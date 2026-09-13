package com.nyasar.app.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.gpx.GpxParseException
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.OverlayLayer
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.map.TileProvider
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.GpsFix
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
@OptIn(ExperimentalCoroutinesApi::class)
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

    // Basemap picker (9-entry World catalog): ONE persisted selection shared
    // by Home, Recording, and RoutePreview. Previously this was a private
    // MutableStateFlow that reset to the default on every process restart and
    // was disconnected from the other two screens' own copies — pick Liberty
    // Satellite on Home, and RoutePreview still showed Liberty Topo. Now
    // backed by SettingsRepository's DataStore (setBasemap below writes the
    // gpxKey; the settings flow propagates the change to every collecting
    // screen within milliseconds, and the choice survives relaunch).
    // Eagerly-started so the first frame already carries the persisted value
    // rather than waiting for the screen's collector to subscribe.
    val selectedBasemap: StateFlow<BasemapEntry> = settingsRepository.settings
        .map { BasemapEntry.fromId(it.basemapId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BasemapEntry.LIBERTY_TOPO)

    /** Downloaded-areas overlay on/off (Settings → Offline), shared app-wide
     *  like every other map-level toggle so all 3 map screens agree. */
    val offlineOverlayEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.offlineOverlayEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Live coverage snapshot from the process-wide store (refreshed on app
     *  start and whenever a download completes / a region is deleted — the
     *  store's StateFlow re-emits and every mounted map redraws). */
    val offlineAreas: StateFlow<List<com.nyasar.app.map.OfflineCoverageArea>> =
        com.nyasar.app.map.OfflineCoverageStore.get(getApplication()).areas

    fun setBasemap(entry: BasemapEntry) {
        viewModelScope.launch { settingsRepository.setBasemapId(entry.gpxKey) }
    }

    // Waymarked Trails overlays: same shared-persisted pattern as the basemap.
    // Required now that the 3 map screens borrow ONE MapView: the overlay
    // effect mutates the single loaded style, so per-screen overlay sets would
    // visibly strip/restore overlays on every screen switch. Kept deliberately
    // shared (unlike follow/heading camera state, which stays per-screen since
    // it's a per-context UX choice — spec §15 gives Recording a different
    // default from Home).
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

    /** Waypoint pins (Data section of the picker sheet) — persisted app-wide
     *  like the other Data toggles: with ONE shared MapView a per-screen flag
     *  would let the last-mounted screen decide visibility for everyone. */
    val waypointsVisible: StateFlow<Boolean> = settingsRepository.settings
        .map { it.waypointsVisible }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setWaypointsVisible(visible: Boolean) {
        viewModelScope.launch { settingsRepository.setWaypointsVisible(visible) }
    }

    /** Saved routes as render-ready lines for the map — the enabled gate
     *  and route-id null filter live inside the repository flow; GPX
     *  parsing/decimation happens there on Dispatchers.IO and only runs
     *  while the overlay is ON. */
    val myRouteLines: StateFlow<List<com.nyasar.app.map.MyRouteLine>> =
        myRoutesOverlayEnabled
            .flatMapLatest { enabled ->
                if (enabled) repository.observeOverlayLines(true) else flowOf(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                _importError.value = getApplication<Application>().getString(com.nyasar.app.R.string.import_gpx_failed, e.message ?: "")
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
