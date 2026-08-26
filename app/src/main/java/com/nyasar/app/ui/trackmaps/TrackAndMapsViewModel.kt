package com.nyasar.app.ui.trackmaps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.RouteEntity
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.map.OfflineMapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds

enum class TrackAndMapsFilter { ALL, TRACK, OFFLINE }

/** Null while this route's own bounds haven't been computed yet (GPX not
 *  loaded), so the row can show "Memeriksa peta offline..." instead of
 *  flashing a wrong answer before settling. */
data class TrackRowUi(
    val route: RouteEntity,
    val hasOfflineCoverage: Boolean? = null
)

data class TrackAndMapsUiState(
    val loading: Boolean = true,
    val filter: TrackAndMapsFilter = TrackAndMapsFilter.ALL,
    val searchQuery: String = "",
    val tracks: List<TrackRowUi> = emptyList(),
    val offlineRegionCount: Int = 0,
    /** Up to 2 region names for the summary banner (spec design ref: "1-2
     *  area offline teratas") — full list is PART 3's redesigned card /
     *  the existing OfflineMapsScreen via "Lihat semua", not duplicated
     *  here. */
    val offlineRegionPreviewNames: List<String> = emptyList()
) {
    val filteredTracks: List<TrackRowUi>
        get() = tracks.filter { searchQuery.isBlank() || it.route.name.contains(searchQuery, ignoreCase = true) }
}

/**
 * Backs the combined "Track & Peta" screen (PART 2). Deliberately reads
 * from the two repositories/managers that already exist —
 * [RouteRepository] for track data, [OfflineMapManager] for offline region
 * data — rather than a new repository, per spec: "JANGAN membuat
 * repository/database baru". The only genuinely new logic here is the
 * per-track "does an offline map already cover this area" cross-check,
 * which nothing in the codebase computed before this screen needed it.
 */
class TrackAndMapsViewModel(app: Application) : AndroidViewModel(app) {

    private val routeRepository = RouteRepository(app)
    private val offlineMapManager = OfflineMapManager(app)

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _uiState = MutableStateFlow(TrackAndMapsUiState())
    val uiState: StateFlow<TrackAndMapsUiState> = _uiState.asStateFlow()

    fun load() {
        _uiState.value = _uiState.value.copy(loading = true)
        viewModelScope.launch {
            // Fix: `kotlinx.coroutines.flow.first(flow)` (fully-qualified
            // call) doesn't compile — Kotlin extension functions can't be
            // invoked that way; needs the dot-call form with `first`
            // actually imported (see import above). This is what caused
            // the whole downstream cascade (Result.map instead of
            // Iterable.map, "suspension functions" error, etc. below) —
            // `routes` was never actually List<RouteEntity>.
            val routes = routeRepository.observeRoutes().first()

            val regions = suspendListRegionsBounds()

            val rows = withContext(Dispatchers.IO) {
                routes.map { route ->
                    val bounds = routeBounds(route)
                    val covered = bounds?.let { rb -> regions.any { it.overlaps(rb) } }
                    TrackRowUi(route, hasOfflineCoverage = covered)
                }
            }

            _uiState.value = _uiState.value.copy(
                loading = false,
                tracks = rows,
                offlineRegionCount = regions.size,
                offlineRegionPreviewNames = regions.take(2).map { it.name }
            )
        }
    }

    fun setFilter(filter: TrackAndMapsFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    /** Same import logic as Home's (HomeViewModel.importGpx) — duplicated
     *  rather than shared through a new class, since it's ~10 lines and this
     *  screen was the one place users kept looking for "Import GPX" and not
     *  finding it (it only lived inside Home's route sheet before). Reloads
     *  the list on success so the imported track shows up immediately. */
    fun importGpx(uri: android.net.Uri) {
        viewModelScope.launch {
            _importError.value = null
            try {
                val displayName = queryDisplayName(uri)
                routeRepository.importFromUri(uri, displayName)
                load()
            } catch (e: com.nyasar.app.gpx.GpxParseException) {
                _importError.value = e.message
            } catch (e: Exception) {
                _importError.value = "Gagal mengimpor GPX: ${e.message}"
            }
        }
    }

    fun dismissImportError() {
        _importError.value = null
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) it.getString(idx) else null
        }
    }

    /** Bounds for one route's own track, same padded-bbox approach
     *  OfflineDownloadViewModel.load() already uses to preselect a
     *  download area for a route — reused here as a coverage *check*
     *  input rather than a download-area suggestion, same formula so a
     *  route's "sudah ada peta offline" badge agrees with what "Download
     *  Map Offline" from Route Preview would actually preselect. */
    private suspend fun routeBounds(route: RouteEntity): LatLngBounds? {
        return try {
            val doc = routeRepository.loadDocument(route)
            val points = doc.allTrackPoints
            if (points.isEmpty()) return null
            val padDeg = 1500.0 / 111_000.0
            val minLat = points.minOf { it.lat } - padDeg
            val maxLat = points.maxOf { it.lat } + padDeg
            val minLon = points.minOf { it.lon } - padDeg
            val maxLon = points.maxOf { it.lon } + padDeg
            LatLngBounds.from(maxLat, maxLon, minLat, minLon)
        } catch (e: Exception) {
            null
        }
    }

    private data class RegionBoundsInfo(val name: String, val bounds: LatLngBounds) {
        /** Plain axis-aligned overlap test using LatLngBounds' own
         *  north/south/east/west accessors (the same accessors
         *  OfflineDownloadViewModel already reads elsewhere in this
         *  codebase, so they're confirmed present on this exact class).
         *  Not using LatLngBounds' own intersects(), if it has one —
         *  couldn't confirm its exact signature/behavior without network
         *  access to the library sources in this environment, and a wrong
         *  guess there fails to compile; this formula is unambiguous and
         *  needs nothing beyond what's already used in this codebase. This
         *  is a coverage *badge*, not a precision measurement, so the
         *  bounding-box (rather than true polygon) approximation is
         *  intentional and sufficient. */
        fun overlaps(other: LatLngBounds): Boolean =
            bounds.longitudeWest <= other.longitudeEast &&
                bounds.longitudeEast >= other.longitudeWest &&
                bounds.latitudeSouth <= other.latitudeNorth &&
                bounds.latitudeNorth >= other.latitudeSouth
    }

    private suspend fun suspendListRegionsBounds(): List<RegionBoundsInfo> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            offlineMapManager.listRegions { regions ->
                val infos = regions.mapNotNull { region ->
                    val name = try {
                        region.metadata?.let { String(it) }?.takeIf { it.isNotBlank() } ?: "Peta tanpa nama"
                    } catch (e: Exception) {
                        "Peta tanpa nama"
                    }
                    val bounds = try {
                        (region.definition as? org.maplibre.android.offline.OfflineTilePyramidRegionDefinition)?.bounds
                    } catch (e: Exception) {
                        null
                    }
                    bounds?.let { RegionBoundsInfo(name, it) }
                }
                if (cont.isActive) cont.resumeWith(Result.success(infos))
            }
        }
}
