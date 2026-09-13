package com.nyasar.app.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.map.BasemapCatalog
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.OfflineMapManager
import com.nyasar.app.map.OfflineRegionMetadata
import com.nyasar.app.map.covers
import com.nyasar.app.map.styleUrlFor
import com.nyasar.app.map.styleUrlOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Loading : DownloadState
    data class InProgress(val percentage: Float) : DownloadState
    data object Done : DownloadState
    data class Error(val message: String) : DownloadState
}

/** Why the download button is disabled while bounds are already ready.
 *  Distinct user-facing reasons — the screen renders a different hint for
 *  each instead of one opaque disabled button. */
sealed interface DownloadBlockedReason {
    /** This basemap's host forbids bulk/offline download (OSMF, OpenTopoMap,
     *  openmaps.fr, CyclOSM-FR, UtagawaMTB). */
    data object PolicyNotAllowed : DownloadBlockedReason
    /** Basemap needs MAPTILER_API_KEY and none is configured. */
    data object MissingKey : DownloadBlockedReason
    /** An existing downloaded region already covers these bounds with the
     *  same style — re-downloading would waste storage and quota. */
    data object AlreadyDownloaded : DownloadBlockedReason
}

data class OfflineDownloadUiState(
    /** Null when this is a free-area download (spec §20/22 — offline maps
     *  must be downloadable without any GPX/route involved). */
    val routeName: String? = null,
    /** User-entered label for a free-area download (spec §21 example:
     *  "Lawu", "Klotok" — a real name, not "area-offline-<timestamp>").
     *  Ignored when routeName is set; the route name is already a real
     *  name and asking for a second one would be redundant. */
    val areaName: String = "",
    val bounds: LatLngBounds? = null,
    val downloadState: DownloadState = DownloadState.Idle,
    /** Rough tile-count-based size estimate shown before download starts,
     *  per spec §22 "estimated size jika tersedia". */
    val estimatedTileCount: Int? = null,
    /** Spec §22 explicitly lists "zoom level" as a picker control — user
     *  choice between a lighter download (enough for overview navigation)
     *  and a more detailed one (closer zoom for precise trail-following).
     *  minZoom stays fixed at 10 (already a wide-area overview level);
     *  this only varies the max. */
    val maxZoom: Double = 16.0,
    /** Region actually being downloaded — kept so cancel (spec §"Jika
     *  cancel memang didukung oleh engine") can call
     *  setDownloadState(STATE_INACTIVE) on the exact region MapLibre
     *  created, not a re-derived one. Null until onCreate fires. */
    val activeRegion: org.maplibre.android.offline.OfflineRegion? = null,
    /** Real completed size, from the same OfflineRegionStatus source the
     *  Offline Maps list already reads — spec's success screen requires a
     *  size, and re-deriving it from the tile-count estimate would show a
     *  number the download itself never confirmed. */
    val completedSizeBytes: Long = 0L,
    /** The basemap this download targets — the app's ACTIVE basemap at the
     *  moment this screen opened. Previously downloads silently used the
     *  default provider style regardless of what the user was looking at;
     *  now what-you-see is what-gets-downloaded. Null only in the first
     *  frame before settings load. */
    val basemap: BasemapEntry? = null,
    /** Why the download button is disabled (null = enabled). Only meaningful
     *  once bounds are ready — the "area" part of the check otherwise. */
    val blockedReason: DownloadBlockedReason? = null
)

/**
 * Downloads map tiles for a bounding box. Two entry points:
 *  - [load]: preselects the route's track bounding box (spec §23 — "Route
 *    Preview → Download Map Offline" shortcut). User can still resize
 *    afterward via [setBounds].
 *  - [startFreeArea] / [setBounds]: no route involved at all — the area
 *    the user is currently viewing/framed on the picker map (spec §20/22).
 * Delegates the actual download to [OfflineMapManager], which is
 * provider-agnostic and bounds-only, so it doesn't care which path set
 * the bounds.
 */
class OfflineDownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val routeRepository = RouteRepository(app)
    private val settingsRepository = SettingsRepository(app)
    private val offlineMapManager = OfflineMapManager(app)

    private val _uiState = MutableStateFlow(OfflineDownloadUiState())
    val uiState: StateFlow<OfflineDownloadUiState> = _uiState.asStateFlow()

    fun load(routeId: String) {
        viewModelScope.launch {
            val route = routeRepository.getRoute(routeId) ?: return@launch
            val doc = routeRepository.loadDocument(route)
            val points = doc.allTrackPoints
            if (points.isEmpty()) return@launch

            // Pad bounding box ~1.5km beyond the track extent — generous
            // enough for a detour to a shelter/water source without
            // requiring the user to guess a download radius themselves.
            val padDeg = 1500.0 / 111_000.0
            val minLat = points.minOf { it.lat } - padDeg
            val maxLat = points.maxOf { it.lat } + padDeg
            val minLon = points.minOf { it.lon } - padDeg
            val maxLon = points.maxOf { it.lon } + padDeg

            val bounds = LatLngBounds.from(maxLat, maxLon, minLat, minLon)
            setBounds(bounds, routeName = route.name)
        }
    }

    /** Free-area entry point (spec §20/22) — no route, just whatever
     *  bounding box the picker map is currently framing. Called as the
     *  user pans/zooms the picker (see OfflineDownloadScreen) so the
     *  estimate stays live. */
    /** Monotonic counter for setBounds calls — bumped synchronously at
     *  call time (before the coroutine runs) so results land in order:
     *  a slower, older camera-settle must never overwrite a newer one's
     *  state (evaluateBlocked does real I/O, so finishes are unordered). */
    private var boundsSeq = 0

    fun setBounds(bounds: LatLngBounds, routeName: String? = _uiState.value.routeName) {
        val seq = ++boundsSeq
        viewModelScope.launch {
            val basemap = resolveActiveBasemap()
            val blocked = evaluateBlocked(bounds, basemap)
            if (seq != boundsSeq) return@launch // a newer settle superseded this one
            _uiState.value = _uiState.value.copy(
                bounds = bounds,
                routeName = routeName,
                basemap = basemap,
                estimatedTileCount = estimateTileCount(bounds, maxZoom = _uiState.value.maxZoom.toInt()),
                blockedReason = blocked
            )
        }
    }

    fun setMaxZoom(maxZoom: Double) {
        val bounds = _uiState.value.bounds
        _uiState.value = _uiState.value.copy(
            maxZoom = maxZoom,
            estimatedTileCount = bounds?.let { estimateTileCount(it, maxZoom = maxZoom.toInt()) }
        )
    }

    /** The app's currently selected basemap (BasemapPickerSheet choice,
     *  persisted in DataStore) resolved through the same path the map
     *  screens use. Falls back to the default-provider style entry only if
     *  the persisted id is unknown (first launch). */
    private suspend fun resolveActiveBasemap(): BasemapEntry {
        val settings = settingsRepository.settings.first()
        return BasemapCatalog.fromId(settings.basemapId)
    }

    /** Full gate evaluation for [bounds] under [basemap]: policy flag,
     *  key requirement, and duplicate-coverage detection against regions
     *  already on disk. Suspend because the coverage scan hits MapLibre's
     *  region database through a callback API; called from [setBounds]'
     *  coroutine (camera-idle), never the main thread directly. */
    private suspend fun evaluateBlocked(bounds: LatLngBounds, basemap: BasemapEntry?): DownloadBlockedReason? {
        if (basemap == null) return null
        if (!basemap.supportsOfflineDownload) return DownloadBlockedReason.PolicyNotAllowed
        if (!BasemapCatalog.isConfiguredFor(basemap, com.nyasar.app.BuildConfig.MAPTILER_API_KEY)) {
            return DownloadBlockedReason.MissingKey
        }
        if (existingRegionCovers(bounds, basemap)) return DownloadBlockedReason.AlreadyDownloaded
        return null
    }

    /** True when ANY completed region already covers [bounds] with the same
     *  style URL the active basemap resolves to — the "sudah terunduh"
     *  answer. Incomplete regions don't count (the user can finish those
     *  from the Offline Maps list); a re-download over an incomplete one
     *  would create a duplicate region, which MapLibre allows silently. */
    private suspend fun existingRegionCovers(bounds: LatLngBounds, basemap: BasemapEntry): Boolean {
        val targetStyle = styleUrlFor(basemap, getApplication())
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            offlineMapManager.listRegions { regions ->
                val covered = regions.any { region ->
                    region.styleUrlOrNull() == targetStyle && region.covers(bounds)
                }
                if (cont.isActive) cont.resumeWith(Result.success(covered))
            }
        }
    }

    /** Rough estimate only — real count depends on the style/provider, but
     *  this gives the user a ballpark before committing to a download
     *  (spec §22 "estimated size jika tersedia"), which the app had zero
     *  of before. Counts tiles across the same zoom range downloadRegion
     *  uses by default. */
    private fun estimateTileCount(bounds: LatLngBounds, minZoom: Int = 10, maxZoom: Int = 16): Int {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            fun lonToX(lon: Double) = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            fun latToY(lat: Double): Int {
                val latRad = Math.toRadians(lat)
                return ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n)
                    .toInt().coerceIn(0, n - 1)
            }
            val xMin = lonToX(bounds.longitudeWest)
            val xMax = lonToX(bounds.longitudeEast)
            val yMin = latToY(bounds.latitudeNorth)
            val yMax = latToY(bounds.latitudeSouth)
            total += (xMax - xMin + 1).toLong() * (yMax - yMin + 1).toLong()
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun setAreaName(name: String) {
        _uiState.value = _uiState.value.copy(areaName = name)
    }

    fun startDownload(regionNameHint: String) {
        val bounds = _uiState.value.bounds ?: return
        _uiState.value = _uiState.value.copy(downloadState = DownloadState.Loading)

        viewModelScope.launch {
            // Download the style of the basemap the user is actually
            // LOOKING at (active BasemapPickerSheet selection), not whatever
            // the settings' default provider happens to be — previously the
            // downloaded region silently didn't match the visible map.
            val basemap = resolveActiveBasemap()
            val styleUrl = styleUrlFor(basemap, getApplication())
            // Re-run the gate fresh: bounds may have changed since the last
            // evaluateBlocked, and a download may have completed elsewhere.
            when (evaluateBlocked(bounds, basemap)) {
                DownloadBlockedReason.PolicyNotAllowed -> {
                    _uiState.value = _uiState.value.copy(
                        downloadState = DownloadState.Error(
                            getApplication<Application>().getString(R.string.offline_policy_blocked)
                        )
                    )
                    return@launch
                }
                DownloadBlockedReason.MissingKey -> {
                    _uiState.value = _uiState.value.copy(
                        downloadState = DownloadState.Error(
                            getApplication<Application>().getString(R.string.offline_missing_key)
                        )
                    )
                    return@launch
                }
                DownloadBlockedReason.AlreadyDownloaded -> {
                    _uiState.value = _uiState.value.copy(downloadState = DownloadState.Done)
                    return@launch
                }
                null -> Unit
            }
            // Real, user-meaningful name (spec §21 example: "Lawu",
            // "Klotok") — route downloads already had one (the route's own
            // name); free-area downloads previously fell through to the
            // literal string "area", which is what showed up in the
            // Offline Maps list forever after. A blank areaName still
            // falls back to the hint so startDownload can't silently no-op
            // on an empty name.
            val displayName = _uiState.value.routeName
                ?: _uiState.value.areaName.trim().takeIf { it.isNotBlank() }
                ?: regionNameHint
            val regionName = "$displayName-offline-${System.currentTimeMillis()}"

            offlineMapManager.downloadRegion(
                styleUrl = styleUrl,
                bounds = bounds,
                regionName = regionName,
                maxZoom = _uiState.value.maxZoom,
                // Self-describing metadata (name + basemap + date) — the
                // Offline Maps list reads THIS to show which map an area
                // belongs to, and the duplicate-coverage check keys on it.
                metadata = OfflineRegionMetadata.encode(
                    name = displayName,
                    basemapId = basemap.gpxKey,
                    createdAtEpochMs = System.currentTimeMillis()
                ),
                callback = object : OfflineMapManager.DownloadCallback {
                    override fun onRegionCreated(region: org.maplibre.android.offline.OfflineRegion) {
                        _uiState.value = _uiState.value.copy(activeRegion = region)
                    }
                    override fun onProgress(percentage: Float, completedSizeBytes: Long) {
                        _uiState.value = _uiState.value.copy(
                            downloadState = DownloadState.InProgress(percentage),
                            completedSizeBytes = completedSizeBytes
                        )
                    }
                    override fun onComplete(region: org.maplibre.android.offline.OfflineRegion) {
                        _uiState.value = _uiState.value.copy(downloadState = DownloadState.Done)
                    }
                    override fun onError(message: String) {
                        _uiState.value = _uiState.value.copy(downloadState = DownloadState.Error(message))
                    }
                }
            )
        }
    }

    /** Only callable while a region actually exists and is downloading —
     *  the button that calls this is itself hidden otherwise (see
     *  OfflineDownloadScreen), so this never needs to explain "cancel
     *  isn't available" to the user; that's decided by whether the button
     *  is even shown. Sets the region inactive, which is MapLibre's own
     *  stop-download mechanism (spec: "Jika cancel memang didukung oleh
     *  engine" — it is, this just wires the OfflineRegion reference the
     *  create callback already gets through to here). Partial tiles stay
     *  on disk as an incomplete region rather than being deleted — same
     *  as any other incomplete/interrupted download already shown in the
     *  Offline Maps list ("Belum lengkap"), not a special cancel-only state. */
    fun cancelDownload() {
        val region = _uiState.value.activeRegion ?: return
        region.setDownloadState(org.maplibre.android.offline.OfflineRegion.STATE_INACTIVE)
        _uiState.value = _uiState.value.copy(downloadState = DownloadState.Idle, activeRegion = null)
    }
}
