package com.nyasar.app.map

import android.content.Context
import org.maplibre.android.offline.OfflineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** One downloaded (or downloading) offline area, ready to draw. */
data class OfflineCoverageArea(
    val bounds: org.maplibre.android.geometry.LatLngBounds,
    val styleUrl: String,
    val complete: Boolean
)

/**
 * Process-wide snapshot of the offline regions for the coverage overlay.
 *
 * One instance per process (see [get]): MapLibre's OfflineManager is itself
 * a process singleton, and all three map screens (Home/Recording/Route
 * Preview) must render the SAME picture — with the shared MapView the last
 * screen to write the overlay would otherwise win for everyone.
 *
 * [refresh] re-lists regions and asks each for its status (complete or
 * still downloading). Cheap enough to run on screen entry; screens call it
 * rather than caching stale state, so a download finished moments ago is
 * reflected the moment the user navigates back to the map.
 *
 * Updated automatically when a download completes or a region is deleted
 * via [notifyChanged] — OfflineDownloadViewModel / OfflineMapsViewModel
 * call that; the overlay flows re-emit; every mounted map redraws.
 */
class OfflineCoverageStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val offlineManager = org.maplibre.android.offline.OfflineManager.getInstance(appContext)

    private val _areas = MutableStateFlow<List<OfflineCoverageArea>>(emptyList())
    val areas: StateFlow<List<OfflineCoverageArea>> = _areas.asStateFlow()

    init {
        // First snapshot at process start so the very first map frame can
        // already show coverage; later updates arrive via notifyChanged().
        refresh()
    }

    /** Monotonic change counter — VMs bump it after download/delete so any
     *  mounted screen's refresh effect re-syncs without polling. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun refresh() {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions?.toList() ?: emptyList()
                if (regions.isEmpty()) {
                    _areas.value = emptyList()
                    return
                }
                var pending = regions.size
                val collected = mutableListOf<OfflineCoverageArea>()
                fun doneOne() {
                    pending--
                    if (pending == 0) _areas.value = collected
                }
                for (region in regions) {
                    val bounds = (region.definition as? OfflineTilePyramidRegionDefinition)?.bounds
                    val style = (region.definition as? OfflineTilePyramidRegionDefinition)?.styleURL
                    if (bounds == null || style == null) {
                        doneOne(); continue
                    }
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            synchronized(collected) {
                                collected += OfflineCoverageArea(
                                    bounds = bounds,
                                    styleUrl = style,
                                    complete = status?.isComplete == true
                                )
                            }
                            doneOne()
                        }
                        override fun onError(error: String?) {
                            // Status unknown — still draw the rectangle, as
                            // incomplete (gray), so coverage never vanishes
                            // just because a status check hiccupped.
                            synchronized(collected) {
                                collected += OfflineCoverageArea(bounds = bounds, styleUrl = style, complete = false)
                            }
                            doneOne()
                        }
                    })
                }
            }

            override fun onError(error: String) {
                // Keep the previous snapshot on transient listing errors.
            }
        })
    }

    /** Download completed / region deleted / resumed — bump revision and
     *  re-list. Screen-level effects key on [revision]. */
    fun notifyChanged() {
        _revision.value += 1
        refresh()
    }

    companion object {
        @Volatile private var instance: OfflineCoverageStore? = null

        fun get(context: Context): OfflineCoverageStore =
            instance ?: synchronized(this) {
                instance ?: OfflineCoverageStore(context.applicationContext).also { instance = it }
            }
    }
}
