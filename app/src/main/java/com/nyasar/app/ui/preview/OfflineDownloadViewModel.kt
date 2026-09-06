package com.nyasar.app.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.map.OfflineMapManager
import com.nyasar.app.map.providers.TileProviderFactory
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
    val completedSizeBytes: Long = 0L
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
    fun setBounds(bounds: LatLngBounds, routeName: String? = _uiState.value.routeName) {
        _uiState.value = _uiState.value.copy(
            bounds = bounds,
            routeName = routeName,
            estimatedTileCount = estimateTileCount(bounds, maxZoom = _uiState.value.maxZoom.toInt())
        )
    }

    fun setMaxZoom(maxZoom: Double) {
        val bounds = _uiState.value.bounds
        _uiState.value = _uiState.value.copy(
            maxZoom = maxZoom,
            estimatedTileCount = bounds?.let { estimateTileCount(it, maxZoom = maxZoom.toInt()) }
        )
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
            val settings = settingsRepository.settings.first()
            val provider = TileProviderFactory.byId(settings.providerId)
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
                provider = provider,
                bounds = bounds,
                regionName = regionName,
                maxZoom = _uiState.value.maxZoom,
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
