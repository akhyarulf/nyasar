package com.nyasar.app.ui.offline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.map.OfflineMapManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

data class OfflineRegionUi(
    val region: OfflineRegion,
    val name: String,
    val sizeBytes: Long,
    val completed: Boolean,
    /** Null only if the region's definition isn't a tile-pyramid definition
     *  (the only kind this app creates via OfflineMapManager.downloadRegion,
     *  so in practice this is always present) — used to draw coverage on
     *  the map (spec §24, WAJIB) and to recenter when the user taps a
     *  region in the list. */
    val bounds: LatLngBounds? = null,
    /** True once getStatus() has actually answered (success OR error) — lets
     *  the screen tell "still checking" apart from "checked, and it's
     *  incomplete". Without this, a getStatus() failure looked identical to
     *  a genuinely-incomplete download forever (spec complaint: "gajelas
     *  mana yang sudah/belum kedownload"). */
    val statusKnown: Boolean = false,
    val statusError: Boolean = false
)

data class OfflineMapsUiState(
    val loading: Boolean = true,
    val regions: List<OfflineRegionUi> = emptyList(),
    val deletingRegionKey: Int? = null,
    /** PART 3 "Lanjut unduh" busy indicator — same shape as
     *  [deletingRegionKey], separate field since delete and resume are
     *  never mutually exclusive states worth conflating. */
    val resumingRegionKey: Int? = null,
    /** Which region's coverage is focused on the map, if any — tapping a
     *  list item sets this so "Lihat" (§21) actually shows something. */
    val focusedRegionKey: Int? = null
)

/**
 * Backs the "Peta Offline" screen (spec P3 §5 gap: OfflineMapManager already
 * had listRegions()/deleteRegion(), nothing in the app ever called them —
 * downloaded maps had no place to be seen or managed). Read-only listing +
 * delete + coverage-on-map (spec §24) + view/focus (spec §21 "Lihat").
 * Downloading new maps happens either from Route Preview (route-based) or
 * from here via "+ Download Area" (free-area, spec §22).
 */
class OfflineMapsViewModel(app: Application) : AndroidViewModel(app) {

    private val offlineMapManager = OfflineMapManager(app)

    private val _uiState = MutableStateFlow(OfflineMapsUiState())
    val uiState: StateFlow<OfflineMapsUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true)
        offlineMapManager.listRegions { regions ->
            val items = regions.mapNotNull { region ->
                // Force-close root cause: String(region.metadata) throws when
                // metadata is empty/corrupt/non-UTF8 — happens for regions
                // left over from an older schema, or ones this exact code
                // never created (metadata format isn't enforced by MapLibre
                // itself). One bad region must not take down the whole list.
                val name = try {
                    region.metadata?.let { String(it) }?.takeIf { it.isNotBlank() }
                        ?: "Peta tanpa nama (#${System.identityHashCode(region)})"
                } catch (e: Exception) {
                    "Peta tanpa nama (#${System.identityHashCode(region)})"
                }
                val bounds = try {
                    (region.definition as? OfflineTilePyramidRegionDefinition)?.bounds
                } catch (e: Exception) {
                    null
                }
                region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        if (status == null) {
                            markStatusError(region)
                            return
                        }
                        val sizeBytes = status.completedTileSize + status.completedResourceSize
                        updateRegionStats(region, sizeBytes, status.isComplete)
                    }
                    override fun onError(error: String?) { markStatusError(region) }
                })
                OfflineRegionUi(
                    region = region,
                    name = name,
                    sizeBytes = 0L,
                    completed = false,
                    bounds = bounds
                )
            }
            _uiState.value = _uiState.value.copy(loading = false, regions = items)
        }
    }

    private fun updateRegionStats(region: OfflineRegion, sizeBytes: Long, completed: Boolean) {
        _uiState.value = _uiState.value.copy(
            regions = _uiState.value.regions.map {
                if (it.region === region) it.copy(sizeBytes = sizeBytes, completed = completed, statusKnown = true, statusError = false) else it
            }
        )
    }

    private fun markStatusError(region: OfflineRegion) {
        _uiState.value = _uiState.value.copy(
            regions = _uiState.value.regions.map {
                if (it.region === region) it.copy(statusKnown = true, statusError = true) else it
            }
        )
    }

    /** "Lihat" (spec §21) — focuses the coverage map on this region. */
    fun focus(item: OfflineRegionUi) {
        _uiState.value = _uiState.value.copy(focusedRegionKey = System.identityHashCode(item.region))
    }

    /** "Lanjut unduh" (PART 3) — resumes an incomplete region in place via
     *  [OfflineMapManager.resumeDownload]. Reuses the same progress/
     *  completion plumbing [refresh]'s status check already uses
     *  ([updateRegionStats]/[markStatusError]), so the row updates size
     *  and flips to "Siap dipakai offline" live as tiles come in, same as
     *  a fresh download would. [resumingRegionKey] mirrors
     *  [deletingRegionKey]'s pattern for a busy-row indicator. */
    fun resumeDownload(item: OfflineRegionUi) {
        _uiState.value = _uiState.value.copy(resumingRegionKey = System.identityHashCode(item.region))
        offlineMapManager.resumeDownload(item.region, object : OfflineMapManager.DownloadCallback {
            override fun onProgress(percentage: Float, completedSizeBytes: Long) {
                updateRegionStats(item.region, completedSizeBytes, completed = false)
            }
            override fun onComplete(region: org.maplibre.android.offline.OfflineRegion) {
                _uiState.value = _uiState.value.copy(resumingRegionKey = null)
                updateRegionStats(region, _uiState.value.regions.firstOrNull { it.region === region }?.sizeBytes ?: 0L, completed = true)
            }
            override fun onError(message: String) {
                _uiState.value = _uiState.value.copy(resumingRegionKey = null)
                markStatusError(item.region)
            }
        })
    }

    fun delete(item: OfflineRegionUi) {
        _uiState.value = _uiState.value.copy(deletingRegionKey = System.identityHashCode(item.region))
        viewModelScope.launch {
            offlineMapManager.deleteRegion(item.region) {
                _uiState.value = _uiState.value.copy(
                    deletingRegionKey = null,
                    regions = _uiState.value.regions.filterNot { it.region === item.region }
                )
            }
        }
    }
}
