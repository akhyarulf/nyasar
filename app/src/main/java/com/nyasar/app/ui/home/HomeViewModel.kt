package com.nyasar.app.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.data.supabase.BrowseBbox
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import com.nyasar.app.map.MyRouteLine
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P3: map is now the home screen (spec Section 10), so this ViewModel owns
 * the live GPS stream + layer/follow state on top of what HomeViewModel
 * already had (route list + GPX import, both untouched -- spec Section 14:
 * "jangan membuat parser GPX kedua").
 *
 * Location collection here is a plain LocationRepository subscription, not
 * shared with RecordingService -- Home isn't recording anything, it's just
 * showing "where am I" on the map, so there is no second-GPS-source concern
 * (aturan satu-sumber GPS itu hanya berlaku untuk sesi rekam, yang pemilik
 * stream-nya RecordingService).
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setMyRoutesOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMyRoutesOverlayEnabled(enabled) }
    }

    /** Waypoint pins (Data section of the picker sheet) — persisted app-wide
     *  like the other Data toggles: with ONE shared MapView a per-screen flag
     *  would let the last-mounted screen decide visibility for everyone. */
    val waypointsVisible: StateFlow<Boolean> = settingsRepository.settings
        .map { it.waypointsVisible }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setWaypointsVisible(visible: Boolean) {
        viewModelScope.launch { settingsRepository.setWaypointsVisible(visible) }
    }

    fun setOfflineOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setOfflineOverlayEnabled(enabled) }
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

    // ── Jelajah peta (rute PUBLIK di area yang sedang dilihat) ──
    //
    // Konsep Strava: yang jadi acuan adalah AREA PETA, bukan posisi pengguna,
    // dan hasilnya tampil sebagai garis di peta — bukan daftar. Kotak yang
    // dikirim = batas kamera yang dikembangkan 25%, jadi posisi pengguna
    // tidak pernah ikut terkirim dan tidak disimpan di mana pun.
    //
    // Tiga penjaga egress (kuota Supabase free tier): debounce 500 ms saat
    // kamera berhenti, cache per-kotak 60 detik (maks 20 entri), dan batas
    // 60 rute per permintaan. Tanpa itu, pan bolak-balik akan mengunduh
    // ratusan KB polyline berkali-kali per menit.
    private val browseRepository = BrowseRepository()

    private val _exploreEnabled = MutableStateFlow(false)
    val exploreEnabled: StateFlow<Boolean> = _exploreEnabled.asStateFlow()

    private val _exploreRoutes = MutableStateFlow<List<MyRouteLine>>(emptyList())
    val exploreRoutes: StateFlow<List<MyRouteLine>> = _exploreRoutes.asStateFlow()

    /** Naik setiap hasil explore BARU diterapkan. Dipakai sebagai kunci
     *  redraw overlay peta: membandingkan daftar track (60 × ~1500 titik)
     *  sebagai key Compose berarti puluhan ribu perbandingan per
     *  recomposition, sedangkan Int gratis. */
    private val _exploreRevision = MutableStateFlow(0)
    val exploreRevision: StateFlow<Int> = _exploreRevision.asStateFlow()

    private val _exploreSelectedId = MutableStateFlow<String?>(null)
    val exploreSelectedId: StateFlow<String?> = _exploreSelectedId.asStateFlow()

    /** Full row of the tapped route (name/stats/difficulty for the card) —
     *  kept from the SAME fetch that drew the line, so opening the card never
     *  costs a second request. */
    private val _exploreDetail = MutableStateFlow<BrowseRepository.PublicRoute?>(null)
    val exploreDetail: StateFlow<BrowseRepository.PublicRoute?> = _exploreDetail.asStateFlow()

    private val _exploreLoading = MutableStateFlow(false)
    val exploreLoading: StateFlow<Boolean> = _exploreLoading.asStateFlow()

    /** One-shot status message (string res): area kosong / gagal / zoom
     *  terlalu jauh. Null = nothing to say. */
    private val _exploreNotice = MutableStateFlow<Int?>(null)
    val exploreNotice: StateFlow<Int?> = _exploreNotice.asStateFlow()

    private var exploreRows: List<BrowseRepository.PublicRoute> = emptyList()
    private var exploreJob: Job? = null
    private var lastExploreKey: String? = null

    /** LRU (accessOrder = true) — the 20 most recently viewed areas. */
    private val exploreCache = object : LinkedHashMap<String, List<BrowseRepository.PublicRoute>>(8, 0.75f, true) {}
    private val exploreCacheAt = HashMap<String, Long>()

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
     *  sama seperti RecordingService yang juga mengecek hasLocationPermission()
     *  sebelum berlangganan, supaya Home tidak pernah memulai request
     *  FusedLocationProviderClient tanpa izin. */
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
                // Konsep backup tanpa tombol: route baru = data backup, langsung
                // diupsert diam-diam (signed-in check ada di dalam manager).
                com.nyasar.app.backup.BackupManager.scheduleRouteBackup(getApplication(), route.id)
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

    // ── Jelajah peta: API untuk HomeScreen ──

    fun toggleExplore() {
        if (_exploreEnabled.value) disableExplore() else enableExplore()
    }

    private fun enableExplore() {
        if (!SupabaseClientProvider.isConfigured) {
            // Sama seperti Browse/Jelajah: tanpa kunci Supabase tidak ada
            // yang bisa diambil. Katakan apa adanya, jangan peta kosong
            // tanpa penjelasan.
            _exploreNotice.value = com.nyasar.app.R.string.explore_failed
            return
        }
        _exploreEnabled.value = true
        _exploreNotice.value = null
        lastExploreKey = null
    }

    fun disableExplore() {
        exploreJob?.cancel()
        _exploreEnabled.value = false
        _exploreRoutes.value = emptyList()
        _exploreRevision.value = _exploreRevision.value + 1
        _exploreSelectedId.value = null
        _exploreDetail.value = null
        exploreRows = emptyList()
        _exploreNotice.value = null
        lastExploreKey = null
    }

    /**
     * Dipanggil NyasarMapView setiap kamera BERHENTI bergerak (bukan tiap
     * frame). Kotak dari batas kamera → debounce → cache → fetch.
     */
    fun onMapCameraIdle(bounds: org.maplibre.android.geometry.LatLngBounds) {
        if (!_exploreEnabled.value) return
        val box = exploreBoxFor(bounds)
        if (box == null) {
            exploreJob?.cancel()
            exploreJob = null
            lastExploreKey = null
            _exploreLoading.value = false
            _exploreRoutes.value = emptyList()
            _exploreSelectedId.value = null
            _exploreDetail.value = null
            _exploreNotice.value = com.nyasar.app.R.string.explore_zoom_in
            return
        }
        val key = "%.4f|%.4f|%.4f|%.4f".format(box.minLat, box.minLng, box.maxLat, box.maxLng)
        // Kamera berhenti di tempat yang sama (mis. setelah memilih rute) —
        // jangan fetch ulang.
        if (key == lastExploreKey) return
        lastExploreKey = key
        exploreJob?.cancel()
        exploreJob = viewModelScope.launch {
            delay(EXPLORE_DEBOUNCE_MS)
            val cachedAt = exploreCacheAt[key] ?: 0L
            val cached = exploreCache[key]
                ?.takeIf { System.currentTimeMillis() - cachedAt < EXPLORE_CACHE_TTL_MS }
            if (cached != null) {
                applyExplore(cached)
                return@launch
            }
            _exploreLoading.value = true
            _exploreNotice.value = null
            when (val outcome = browseRepository.browse(
                client = SupabaseClientProvider.client,
                bbox = box,
                limit = EXPLORE_LIMIT
            )) {
                is BrowseRepository.Outcome.Success -> {
                    exploreCache[key] = outcome.routes
                    exploreCacheAt[key] = System.currentTimeMillis()
                    trimExploreCache()
                    applyExplore(outcome.routes)
                }
                is BrowseRepository.Outcome.Failure -> {
                    _exploreLoading.value = false
                    _exploreNotice.value = com.nyasar.app.R.string.explore_failed
                }
            }
        }
    }

    /**
     * Batas kamera → kotak query. Dikembangkan 25% karena filter server
     * memakai titik AWAL rute: tanpa mengembang, rute yang mulai sedikit di
     * luar layar tapi garisnya melintasi layar tidak akan muncul. Zoom yang
     * terlalu jauh ditolak (null) — itu permintaan se-provinsi, bukan
     * "jelajah area ini".
     */
    private fun exploreBoxFor(bounds: org.maplibre.android.geometry.LatLngBounds): BrowseBbox? {
        val latSpan = bounds.latitudeNorth - bounds.latitudeSouth
        val lngSpan = bounds.longitudeEast - bounds.longitudeWest
        if (latSpan <= 0.0 && lngSpan <= 0.0) return null
        if (latSpan > MAX_EXPLORE_SPAN_DEGREES || lngSpan > MAX_EXPLORE_SPAN_DEGREES) return null
        val padLat = latSpan * 0.25
        val padLng = lngSpan * 0.25
        return BrowseBbox(
            minLat = bounds.latitudeSouth - padLat,
            minLng = bounds.longitudeWest - padLng,
            maxLat = bounds.latitudeNorth + padLat,
            maxLng = bounds.longitudeEast + padLng
        ).normalized().takeIf { it.isUsable }
    }

    /** Decode polyline → garis peta. Decimation memakai batas vertex yang
     *  sudah dipakai "Jalur Saya" (1500/rute), jadi 60 rute tetap ringan
     *  bagi MapLibre. Dijalankan di Default, bukan main thread. */
    private suspend fun applyExplore(rows: List<BrowseRepository.PublicRoute>) {
        val lines = withContext(Dispatchers.Default) {
            rows.mapNotNull { row ->
                val points = BrowseRepository.decodeTrack(row.trackPolyline)
                if (points.size < 2) null
                else MyRouteLine(row.id, row.name, MyRouteLine.decimate(points))
            }
        }
        exploreRows = rows
        _exploreRoutes.value = lines
        _exploreRevision.value = _exploreRevision.value + 1
        _exploreLoading.value = false
        _exploreNotice.value = if (lines.isEmpty()) com.nyasar.app.R.string.explore_empty else null
        // Card yang terbuka bisa menunjuk rute di area sebelumnya.
        val selected = _exploreSelectedId.value
        if (selected != null && lines.none { it.routeId == selected }) {
            _exploreSelectedId.value = null
            _exploreDetail.value = null
        }
    }

    fun selectExploreRoute(id: String) {
        _exploreSelectedId.value = id
        _exploreDetail.value = exploreRows.firstOrNull { it.id == id }
    }

    fun clearExploreSelection() {
        _exploreSelectedId.value = null
        _exploreDetail.value = null
    }

    private fun trimExploreCache() {
        if (exploreCache.size <= EXPLORE_CACHE_MAX) return
        val it = exploreCache.keys.iterator()
        while (exploreCache.size > EXPLORE_CACHE_MAX && it.hasNext()) {
            val oldest = it.next()
            it.remove()
            exploreCacheAt.remove(oldest)
        }
    }

    private companion object {
        /** Batas atas per permintaan — 60 rute × ~8 KB masih di bawah 1 MB. */
        const val EXPLORE_LIMIT = 60

        /** Lebar kamera maksimum (derajat). Kotak akhirnya dikali 1,25 dan
         *  harus lolos [BrowseBbox.isUsable] (maks 2°), jadi ~1,3° di sini
         *  adalah batas zoom-out praktis. */
        const val MAX_EXPLORE_SPAN_DEGREES = 1.3

        const val EXPLORE_DEBOUNCE_MS = 500L
        const val EXPLORE_CACHE_TTL_MS = 60_000L
        const val EXPLORE_CACHE_MAX = 20
    }
}
