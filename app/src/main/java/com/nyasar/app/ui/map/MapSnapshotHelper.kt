package com.nyasar.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Generates static map snapshot bitmaps for activity track thumbnails
 * and share card backgrounds, using MapLibre's MapSnapshotter API.
 *
 * Snapshots are cached to disk (one file per activity) so they are only
 * generated once and reused across scrolls and share operations.
 *
 * Falls back to a gradient+grid placeholder if:
 * - No network to load tiles (first-time, no cache)
 * - Snapshotter fails for any reason
 * - Track has fewer than 2 points
 */
object MapSnapshotHelper {

    private const val CACHE_DIR = "map_snapshots"
    private const val BOUNDS_PADDING_METERS = 500.0

    // Gradient fallback colors
    private val FALLBACK_TOP = Color.parseColor("#5A7562")
    private val FALLBACK_BOT = Color.parseColor("#2A3A30")
    private val FALLBACK_GRID = Color.parseColor("#1AFFFFFF")

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

        // Generate via MapSnapshotter (must run on UI thread)
        val bitmap = withContext(Dispatchers.Main) {
            generateSnapshot(context, trackPoints, widthPx, heightPx, styleUrl)
        }

        // Save to disk cache
        if (bitmap != null) {
            saveToDisk(context, activityId, bitmap)
        }

        return bitmap
    }

    /**
     * Generate a snapshot synchronously (call from IO dispatcher).
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

        val bitmap = withContext(Dispatchers.Main) {
            generateSnapshot(context, trackPoints, widthPx, heightPx, styleUrl)
        }

        if (bitmap != null) {
            saveToDisk(context, activityId, bitmap)
        }
        return bitmap
    }

    /**
     * Generate a real map snapshot using MapLibre's MapSnapshotter.
     * Must be called from the UI thread (MapSnapshotter has @UiThread).
     * Returns null on failure — caller falls back to gradient+grid.
     */
    private suspend fun generateSnapshot(
        context: Context,
        trackPoints: List<Pair<Double, Double>>,
        widthPx: Int,
        heightPx: Int,
        styleUrl: String
    ): Bitmap? {
        return try {
            val bounds = computeBounds(trackPoints)

            val options = MapSnapshotter.Options(widthPx, heightPx).apply {
                withStyle(styleUrl)
                withRegion(bounds)
            }

            val snapshotter = MapSnapshotter(context, options)

            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation {
                    snapshotter.cancel()
                }

                snapshotter.start(object : MapSnapshotter.SnapshotReadyCallback {
                    override fun onSnapshotReady(snapshot: MapSnapshot) {
                        if (cont.isActive) {
                            cont.resume(snapshot.bitmap)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            android.util.Log.w("MapSnapshotHelper", "Snapshot failed: ${e.message}")
            // Fallback: gradient + grid bitmap
            generateFallbackBitmap(widthPx, heightPx)
        }
    }

    /**
     * Compute LatLngBounds from track points with padding around the route.
     */
    private fun computeBounds(trackPoints: List<Pair<Double, Double>>): LatLngBounds {
        val lats = trackPoints.map { it.second }
        val lons = trackPoints.map { it.first }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLon = lons.min(); val maxLon = lons.max()

        // Convert padding from meters to approximate degrees
        // 1 degree latitude ≈ 111,320 meters
        val latPad = BOUNDS_PADDING_METERS / 111_320.0
        // 1 degree longitude varies with latitude — use midpoint for approximation
        val midLat = (minLat + maxLat) / 2.0
        val lonPad = BOUNDS_PADDING_METERS / (111_320.0 * Math.cos(Math.toRadians(midLat)))

        return LatLngBounds.Builder()
            .include(LatLng(maxLat + latPad, maxLon + lonPad))
            .include(LatLng(minLat - latPad, minLon - lonPad))
            .build()
    }

    // ── Fallback: gradient + grid (when snapshot fails) ──

    private fun generateFallbackBitmap(widthPx: Int, heightPx: Int): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            val gradient = LinearGradient(
                0f, 0f, 0f, heightPx.toFloat(),
                intArrayOf(FALLBACK_TOP, FALLBACK_BOT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawPaint(Paint().apply { shader = gradient })

            val gridPaint = Paint().apply { color = FALLBACK_GRID; strokeWidth = 1f }
            val spacing = (widthPx / 18).toFloat().coerceAtLeast(30f)
            var x = 0f
            while (x <= widthPx) { c.drawLine(x, 0f, x, heightPx.toFloat(), gridPaint); x += spacing }
            var y = 0f
            while (y <= heightPx) { c.drawLine(0f, y, widthPx.toFloat(), y, gridPaint); y += spacing }

            bmp
        } catch (e: Exception) {
            null
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
