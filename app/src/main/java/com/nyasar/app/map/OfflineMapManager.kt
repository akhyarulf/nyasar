package com.nyasar.app.map

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Downloads a bounding-box region of the CURRENT [TileProvider]'s style
 * for offline use, via MapLibre's built-in OfflineManager. This is what
 * makes "download area before you go" (spec section 10) work regardless
 * of which provider is active — MapLibre's offline database keys regions
 * by style URL, so nothing here needs to special-case any particular
 * provider.
 *
 * Downloaded regions live in MapLibre's own local database (ambient +
 * offline tile cache) — no separate storage system to maintain, and
 * navigation reads from it automatically once offline, no code change
 * needed in NavigationEngine/TrackMatcher.
 */
class OfflineMapManager(context: Context) {

    private val offlineManager = OfflineManager.getInstance(context)

    interface DownloadCallback {
        /** Fired once MapLibre has created the region, before any tiles
         *  are downloaded — the earliest point a caller can hold a
         *  reference to it for cancellation (spec: cancel must use a real
         *  engine mechanism, not a fake button). */
        fun onRegionCreated(region: OfflineRegion) {}
        fun onProgress(percentage: Float, completedSizeBytes: Long)
        fun onComplete(region: OfflineRegion)
        fun onError(message: String)
    }

    fun downloadRegion(
        provider: TileProvider,
        bounds: LatLngBounds,
        regionName: String,
        minZoom: Double = 10.0,
        maxZoom: Double = 16.0,
        callback: DownloadCallback
    ) {
        val definition = OfflineTilePyramidRegionDefinition(
            provider.styleUrl(),
            bounds,
            minZoom,
            maxZoom,
            context_density()
        )
        val metadata = regionName.toByteArray()

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    callback.onRegionCreated(offlineRegion)
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val percentage = if (status.requiredResourceCount >= 0) {
                                100.0 * status.completedResourceCount / status.requiredResourceCount.coerceAtLeast(1)
                            } else 0.0
                            val sizeBytes = status.completedTileSize + status.completedResourceSize
                            callback.onProgress(percentage.toFloat(), sizeBytes)
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                callback.onComplete(offlineRegion)
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            callback.onError(error.message ?: "Unknown offline download error")
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            callback.onError("Batas jumlah tile offline terlampaui ($limit)")
                        }
                    })
                }

                override fun onError(error: String) {
                    callback.onError(error)
                }
            }
        )
    }

    fun listRegions(onResult: (List<OfflineRegion>) -> Unit) {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                onResult(offlineRegions?.toList() ?: emptyList())
            }
            override fun onError(error: String) {
                onResult(emptyList())
            }
        })
    }

    fun deleteRegion(region: OfflineRegion, onDone: (Boolean) -> Unit) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onDone(true)
            override fun onError(error: String) = onDone(false)
        })
    }

    /**
     * Resumes an incomplete region's download (PART 3 "Lanjut unduh").
     * Additive — [downloadRegion] above (which creates a brand-new region)
     * is untouched. This reuses MapLibre's own OfflineRegion download
     * state machine: STATE_ACTIVE on a region that already exists (e.g.
     * one just handed back by [listRegions]) resumes fetching whatever
     * tiles it's still missing rather than restarting from zero — the
     * same mechanism [downloadRegion] already relies on to *start* a
     * download, just invoked on an existing region instead of a freshly
     * created one.
     */
    fun resumeDownload(region: OfflineRegion, callback: DownloadCallback) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                val percentage = if (status.requiredResourceCount >= 0) {
                    100.0 * status.completedResourceCount / status.requiredResourceCount.coerceAtLeast(1)
                } else 0.0
                val sizeBytes = status.completedTileSize + status.completedResourceSize
                callback.onProgress(percentage.toFloat(), sizeBytes)
                if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    callback.onComplete(region)
                }
            }

            override fun onError(error: OfflineRegionError) {
                callback.onError(error.message ?: "Unknown offline download error")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                callback.onError("Batas jumlah tile offline terlampaui ($limit)")
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun context_density(): Float = android.content.res.Resources.getSystem().displayMetrics.density
}
