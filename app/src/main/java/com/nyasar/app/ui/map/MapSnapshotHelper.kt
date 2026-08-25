package com.nyasar.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Path
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
 * Result of generating a map snapshot — bitmap plus the geographic bounds
 * that were used. Callers MUST use the same bounds when drawing the track
 * overlay so the line aligns with the map tiles.
 */
data class MapSnapshotResult(
    val bitmap: Bitmap,
    val bounds: LatLngBounds
)

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
    private const val CACHE_VERSION = 5 // bump to invalidate stale snapshots after coordinate-space alignment fix
    private const val MIN_PADDING_METERS = 100.0
    private const val MAX_PADDING_METERS = 500.0

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
     * @return MapSnapshotResult or null on failure
     */
    suspend fun getOrGenerate(
        context: Context,
        activityId: String,
        trackPoints: List<Pair<Double, Double>>, // Pair(lat, lon)
        widthPx: Int,
        heightPx: Int,
        styleUrl: String
    ): MapSnapshotResult? {
        if (trackPoints.size < 2) return null

        // Check disk cache first
        val cached = loadFromDisk(context, activityId, widthPx, heightPx)
        if (cached != null) return MapSnapshotResult(cached, computeBounds(trackPoints))

        // Generate via MapSnapshotter (must run on UI thread)
        val result = withContext(Dispatchers.Main) {
            generateSnapshot(context, trackPoints, widthPx, heightPx, styleUrl)
        }

        // Save to disk cache
        if (result != null) {
            saveToDisk(context, activityId, widthPx, heightPx, result.bitmap)
        }

        return result
    }

    /**
     * Generate a snapshot synchronously (call from IO dispatcher).
     * Used by ShareCardGenerator on a background thread.
     */
    suspend fun generateSync(
        context: Context,
        activityId: String,
        trackPoints: List<Pair<Double, Double>>, // Pair(lat, lon)
        widthPx: Int,
        heightPx: Int,
        styleUrl: String
    ): MapSnapshotResult? {
        if (trackPoints.size < 2) return null

        val cached = loadFromDisk(context, activityId, widthPx, heightPx)
        if (cached != null) return MapSnapshotResult(cached, computeBounds(trackPoints))

        val result = withContext(Dispatchers.Main) {
            generateSnapshot(context, trackPoints, widthPx, heightPx, styleUrl)
        }

        if (result != null) {
            saveToDisk(context, activityId, widthPx, heightPx, result.bitmap)
        }
        return result
    }

    /**
     * Generate a real map snapshot using MapLibre's MapSnapshotter.
     * Must be called from the UI thread (MapSnapshotter has @UiThread).
     * Returns null on failure — caller falls back to gradient+grid.
     */
    private suspend fun generateSnapshot(
        context: Context,
        trackPoints: List<Pair<Double, Double>>, // Pair(lat, lon)
        widthPx: Int,
        heightPx: Int,
        styleUrl: String
    ): MapSnapshotResult? {
        return try {
            val bounds = computeBounds(trackPoints)

            val options = MapSnapshotter.Options(widthPx, heightPx).apply {
                withStyle(styleUrl)
                withRegion(bounds)
                withAttribution(false)
            }

            val snapshotter = MapSnapshotter(context, options)

            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation {
                    snapshotter.cancel()
                }

                snapshotter.start(object : MapSnapshotter.SnapshotReadyCallback {
                    override fun onSnapshotReady(snapshot: MapSnapshot) {
                        if (cont.isActive) {
                            cont.resume(MapSnapshotResult(snapshot.bitmap, bounds))
                        }
                    }
                })
            }
        } catch (e: Exception) {
            android.util.Log.w("MapSnapshotHelper", "Snapshot failed: ${e.message}")
            null
        }
    }

    /**
     * Compute LatLngBounds from track points with padding around the route.
     * @param trackPoints List of Pair(lat, lon) — first=lat, second=lon
     * @return LatLngBounds with padding applied
     */
    internal fun computeBounds(trackPoints: List<Pair<Double, Double>>): LatLngBounds {
        val lats = trackPoints.map { it.first }
        val lons = trackPoints.map { it.second }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLon = lons.min(); val maxLon = lons.max()

        // Dynamic padding: short tracks get tighter zoom, long tracks get
        // wider padding. Track span in meters determines the padding.
        val midLat = (minLat + maxLat) / 2.0
        val cosMid = Math.cos(Math.toRadians(midLat))
        val latSpanM = (maxLat - minLat) * 111_320.0
        val lonSpanM = (maxLon - minLon) * 111_320.0 * cosMid
        val trackSpanM = maxOf(latSpanM, lonSpanM)
        val padding = (trackSpanM * 2.0).coerceIn(MIN_PADDING_METERS, MAX_PADDING_METERS)

        // Convert padding from meters to approximate degrees
        val latPad = padding / 111_320.0
        val lonPad = padding / (111_320.0 * cosMid)

        return LatLngBounds.Builder()
            .include(LatLng(maxLat + latPad, maxLon + lonPad))
            .include(LatLng(minLat - latPad, minLon - lonPad))
            .build()
    }

    /**
     * Draw a track line onto a Canvas using the SAME bounds as the map snapshot,
     * ensuring the track aligns precisely with the map tiles.
     *
     * @param canvas Canvas to draw on (e.g. Compose Canvas drawScope)
     * @param trackPoints raw TrackPoint list with .lat/.lon
     * @param bounds the LatLngBounds used to generate the map snapshot
     * @param canvasLeft left pixel coordinate of the map area
     * @param canvasTop top pixel coordinate of the map area
     * @param canvasRight right pixel coordinate of the map area
     * @param canvasBottom bottom pixel coordinate of the map area
     * @param strokeWidth line thickness in pixels
     * @param color line color (ARGB int)
     */
    fun drawTrackOnCanvas(
        canvas: Canvas,
        trackPoints: List<com.nyasar.app.gpx.model.TrackPoint>,
        bounds: LatLngBounds,
        canvasLeft: Float, canvasTop: Float,
        canvasRight: Float, canvasBottom: Float,
        strokeWidth: Float, color: Int
    ) {
        if (trackPoints.size < 2) return

        val boundsLatN = bounds.northEast.latitude
        val boundsLatS = bounds.southWest.latitude
        val boundsLonE = bounds.northEast.longitude
        val boundsLonW = bounds.southWest.longitude
        val boundsLatSpan = boundsLatN - boundsLatS
        val boundsLonSpan = boundsLonE - boundsLonW

        if (boundsLatSpan <= 0.0 || boundsLonSpan <= 0.0) return

        val areaW = canvasRight - canvasLeft
        val areaH = canvasBottom - canvasTop
        if (areaW <= 0f || areaH <= 0f) return

        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        val path = Path()
        trackPoints.forEachIndexed { i, p ->
            // Normalize to 0..1 within bounds, then scale to canvas area
            val nx = ((p.lon - boundsLonW) / boundsLonSpan).toFloat()
            val ny = 1f - ((p.lat - boundsLatS) / boundsLatSpan).toFloat()
            val x = canvasLeft + nx * areaW
            val y = canvasTop + ny * areaH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    /**
     * Overload for Compose drawScope using Pair(lat, lon) points
     * (used by ActivityHistoryScreen's thumbnail).
     */
    fun drawTrackOnCanvas(
        canvas: androidx.compose.ui.graphics.drawscope.DrawScope,
        trackPoints: List<Pair<Double, Double>>, // Pair(lat, lon)
        bounds: LatLngBounds,
        strokeWidth: Float,
        color: androidx.compose.ui.graphics.Color
    ) {
        if (trackPoints.size < 2) return

        val boundsLatN = bounds.northEast.latitude
        val boundsLatS = bounds.southWest.latitude
        val boundsLonE = bounds.northEast.longitude
        val boundsLonW = bounds.southWest.longitude
        val boundsLatSpan = boundsLatN - boundsLatS
        val boundsLonSpan = boundsLonE - boundsLonW
        if (boundsLatSpan <= 0.0 || boundsLonSpan <= 0.0) return

        with(canvas) {
            val canvasW = size.width
            val canvasH = size.height
            if (canvasW <= 0f || canvasH <= 0f) return

            val path = androidx.compose.ui.graphics.Path()
            trackPoints.forEachIndexed { i, (lat, lon) ->
                val nx = ((lon - boundsLonW) / boundsLonSpan).toFloat()
                val ny = 1f - ((lat - boundsLatS) / boundsLatSpan).toFloat()
                val x = nx * canvasW
                val y = ny * canvasH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
        }
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

    private fun cacheFile(context: Context, activityId: String, widthPx: Int, heightPx: Int): File =
        File(cacheDir(context), "${activityId}_${widthPx}x${heightPx}_v${CACHE_VERSION}.png")

    private fun loadFromDisk(context: Context, activityId: String, widthPx: Int, heightPx: Int): Bitmap? {
        val file = cacheFile(context, activityId, widthPx, heightPx)
        return if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                file.delete()
                null
            }
        } else null
    }

    private fun saveToDisk(context: Context, activityId: String, widthPx: Int, heightPx: Int, bitmap: Bitmap) {
        try {
            val file = cacheFile(context, activityId, widthPx, heightPx)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            android.util.Log.w("MapSnapshotHelper", "Cache write failed: ${e.message}")
        }
    }
}
