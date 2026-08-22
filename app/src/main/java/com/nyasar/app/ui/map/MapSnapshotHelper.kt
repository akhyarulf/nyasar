package com.nyasar.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapSnapshotter
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Generates static map snapshot bitmaps for activity track thumbnails
 * and share card backgrounds, using MapLibre's MapSnapshotter API.
 *
 * Snapshots are cached to disk (one file per activity) so they are only
 * generated once and reused across scrolls and share operations.
 *
 * Falls back to null (caller draws a grid placeholder) if:
 * - No network to load tiles
 * - Snapshotter fails for any reason
 * - Track has fewer than 2 points
 */
object MapSnapshotHelper {

    private const val CACHE_DIR = "map_snapshots"
    private const val PADDING_FACTOR = 1.6 // extra zoom-out so route isn't edge-to-edge

    /**
     * Get a cached snapshot or generate a new one.
     * @param trackPoints lat/lon pairs from the activity's recorded points
     * @param widthPx pixel width of the output bitmap
     * @param heightPx pixel height of the output bitmap
     * @param styleUrl MapLibre style URL from the current TileProvider
     * @return Bitmap or null on failure
     */
    suspend fun getOrGenerate(
        context: Context,
        activityId: String,
        trackPoints: List<Pair<Double, Double>>,
        widthPx: Int,
        heightPx: Int,
        styleUrl: String
    ): Bitmap? {
        if (trackPoints.size < 2) return null

        // Check disk cache first
        val cached = loadFromDisk(context, activityId)
        if (cached != null) return cached

        // Generate via MapSnapshotter
        val bitmap = withContext(Dispatchers.IO) {
            generateSnapshot(context, trackPoints, widthPx, heightPx, styleUrl)
        }

        // Save to disk cache
        if (bitmap != null) {
            saveToDisk(context, activityId, bitmap)
        }

        return bitmap
    }

    /**
     * Generate a snapshot bitmap synchronously (call from IO dispatcher).
     * Used by ShareCardGenerator on a background thread.
     */
    suspend fun generateSync(
        context: Context,
        activityId: String,
        trackPoints: List<Pair<Double, Double>>,
        widthPx: Int,
        heightPx: Int,
        styleUrl: String
    ): Bitmap? {
        if (trackPoints.size < 2) return null

        val cached = loadFromDisk(context, activityId)
        if (cached != null) return cached

        val bitmap = generateSnapshot(context, trackPoints, widthPx, heightPx, styleUrl)
        if (bitmap != null) {
            saveToDisk(context, activityId, bitmap)
        }
        return bitmap
    }

    private fun generateSnapshot(
        context: Context,
        trackPoints: List<Pair<Double, Double>>,
        widthPx: Int,
        heightPx: Int,
        styleUrl: String
    ): Bitmap? {
        return try {
            val bounds = computeBounds(trackPoints)
            val camera = CameraPosition.Builder()
                .target(bounds.center)
                .zoom(computeZoom(bounds, widthPx, heightPx))
                .build()

            val options = MapSnapshotter.Options(widthPx, heightPx)
                .withStyle(styleUrl)
                .withCameraPosition(camera)
                .withLogo(false)

            val snapshotter = MapSnapshotter(context, options)

            suspendCancellableCoroutine { cont ->
                snapshotter.start({ snapshot ->
                    val bmp = snapshot.bitmap
                    if (cont.isActive) cont.resume(bmp)
                }, { error ->
                    android.util.Log.w("MapSnapshotHelper", "Snapshot failed: $error")
                    if (cont.isActive) cont.resume(null)
                })

                cont.invokeOnCancellation {
                    snapshotter.cancel()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MapSnapshotHelper", "Snapshot error: ${e.message}")
            null
        }
    }

    private fun computeBounds(trackPoints: List<Pair<Double, Double>>): LatLngBounds {
        val lats = trackPoints.map { it.second }
        val lons = trackPoints.map { it.first }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLon = lons.min(); val maxLon = lons.max()
        val latPad = (maxLat - minLat).coerceAtLeast(0.001) * (PADDING_FACTOR - 1.0) / 2.0
        val lonPad = (maxLon - minLon).coerceAtLeast(0.001) * (PADDING_FACTOR - 1.0) / 2.0
        return LatLngBounds.Builder()
            .include(LatLng(maxLat + latPad, maxLon + lonPad))
            .include(LatLng(minLat - latPad, minLon - lonPad))
            .build()
    }

    private fun computeZoom(bounds: LatLngBounds, widthPx: Int, heightPx: Int): Double {
        // Approximate zoom level to fit bounds in the given pixel dimensions.
        // This is a rough heuristic — MapSnapshotter adjusts internally.
        val latDiff = bounds.northEast.latitude - bounds.southWest.latitude
        val lonDiff = bounds.northEast.longitude - bounds.southWest.longitude
        val maxDiff = max(latDiff, lonDiff)
        return when {
            maxDiff > 10 -> 4.0
            maxDiff > 5 -> 5.0
            maxDiff > 2 -> 6.0
            maxDiff > 1 -> 7.0
            maxDiff > 0.5 -> 8.0
            maxDiff > 0.2 -> 9.0
            maxDiff > 0.1 -> 10.0
            maxDiff > 0.05 -> 11.0
            maxDiff > 0.02 -> 12.0
            maxDiff > 0.01 -> 13.0
            else -> 14.0
        }
    }

    // ── Disk cache ──

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cacheFile(context: Context, activityId: String): File =
        File(cacheDir(context), "$activityId.png")

    private fun loadFromDisk(context: Context, activityId: String): Bitmap? {
        val file = cacheFile(context, activityId)
        return if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                file.delete()
                null
            }
        } else null
    }

    private fun saveToDisk(context: Context, activityId: String, bitmap: Bitmap) {
        try {
            val file = cacheFile(context, activityId)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            android.util.Log.w("MapSnapshotHelper", "Cache write failed: ${e.message}")
        }
    }
}
