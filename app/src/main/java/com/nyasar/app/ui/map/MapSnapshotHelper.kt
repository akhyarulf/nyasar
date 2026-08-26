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
    private const val CACHE_VERSION = 8 // bump: TRACK_COLOR green + verticalOffsetFraction for bottom gradient alignment
    // 25m floor (was 100m) — 100m alone was already 5-10x wider than a
    // typical very-short recording's own span (a few meters to a few tens
    // of meters), so those tracks rendered as a tiny speck regardless of
    // the *2.0 span-based scaling below. 25m still leaves enough breathing
    // room that GPS jitter on a normal-length track doesn't clip at the
    // frame edge.
    private const val MIN_PADDING_METERS = 25.0
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
        if (cached != null) return MapSnapshotResult(cached, computeBounds(trackPoints, widthPx, heightPx))

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
        styleUrl: String,
        verticalOffsetFraction: Double = 0.0
    ): MapSnapshotResult? {
        if (trackPoints.size < 2) return null

        val cached = loadFromDisk(context, activityId, widthPx, heightPx)
        if (cached != null) return MapSnapshotResult(cached, computeBounds(trackPoints, widthPx, heightPx, verticalOffsetFraction))

        val result = withContext(Dispatchers.Main) {
            generateSnapshot(context, trackPoints, widthPx, heightPx, styleUrl, verticalOffsetFraction)
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
        styleUrl: String,
        verticalOffsetFraction: Double = 0.0
    ): MapSnapshotResult? {
        return try {
            val bounds = computeBounds(trackPoints, widthPx, heightPx, verticalOffsetFraction)

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
     * Compute LatLngBounds from track points with padding around the route,
     * THEN expand to match the requested canvas aspect ratio.
     *
     * ROOT CAUSE this fixes: MapSnapshotter.Options.withRegion(bounds) does
     * NOT render exactly the bounds it's given — MapLibre only guarantees
     * those bounds are *visible* within the frame. If the bounds' own aspect
     * ratio doesn't match widthPx:heightPx, MapLibre silently expands
     * (letterboxes) one axis to fit the frame, and the ACTUAL rendered
     * region ends up wider/taller than the `bounds` value we hold onto for
     * drawing the route overlay. That mismatch is exactly why the route
     * line looked shifted in History's 1080x640 thumbnail (very wide aspect,
     * so MapLibre was expanding it a lot) while Share Card's 1080x1248
     * (closer to square) happened to look closer to correct.
     *
     * Fixing it at the source: force `bounds` itself to already match
     * widthPx:heightPx before it's ever handed to MapSnapshotter, so
     * MapLibre never needs to silently expand anything, and the bounds we
     * use for the overlay are the exact bounds that got rendered.
     *
     * @param trackPoints List of Pair(lat, lon) — first=lat, second=lon
     * @param widthPx requested snapshot width in pixels
     * @param heightPx requested snapshot height in pixels
     * @param verticalOffsetFraction Shifts the visible area upward by this
     *   fraction of the total latitude span (0.0–0.5). Used when the bottom
     *   portion of the bitmap will be covered by a gradient overlay, so the
     *   track should appear centered in the VISIBLE area (top) rather than
     *   the full bitmap. 0.05 shifts the center up by 5% of the span.
     * @return LatLngBounds with padding applied AND aspect-ratio-matched to widthPx:heightPx
     */
    internal fun computeBounds(
        trackPoints: List<Pair<Double, Double>>,
        widthPx: Int,
        heightPx: Int,
        verticalOffsetFraction: Double = 0.0
    ): LatLngBounds {
        val lats = trackPoints.map { it.first }
        val lons = trackPoints.map { it.second }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLon = lons.min(); val maxLon = lons.max()

        // Dynamic padding: short tracks get tighter zoom, long tracks get
        // wider padding. Track span in meters determines the padding.
        val midLat = (minLat + maxLat) / 2.0
        val cosMid = Math.cos(Math.toRadians(midLat)).coerceAtLeast(0.01)
        val latSpanM = (maxLat - minLat) * 111_320.0
        val lonSpanM = (maxLon - minLon) * 111_320.0 * cosMid
        val trackSpanM = maxOf(latSpanM, lonSpanM)
        val padding = (trackSpanM * 2.0).coerceIn(MIN_PADDING_METERS, MAX_PADDING_METERS)

        // Convert padding from meters to approximate degrees
        val latPad = padding / 111_320.0
        val lonPad = padding / (111_320.0 * cosMid)

        var north = maxLat + latPad
        var south = minLat - latPad
        var east = maxLon + lonPad
        var west = minLon - lonPad

        // Expand to match the target canvas aspect ratio, in METERS (not raw
        // degrees), since 1 degree of longitude != 1 degree of latitude in
        // real-world distance. Whichever axis is "too narrow" for the target
        // ratio gets grown, centered on the same midpoint — never shrunk, so
        // the padding computed above is always fully honored.
        val targetRatio = widthPx.toFloat() / heightPx.toFloat() // width / height
        val currentLatSpanM = (north - south) * 111_320.0
        val currentLonSpanM = (east - west) * 111_320.0 * cosMid
        val currentRatio = currentLonSpanM / currentLatSpanM.coerceAtLeast(0.01)

        if (currentRatio < targetRatio) {
            // too narrow (tall) — widen longitude span
            val wantedLonSpanM = currentLatSpanM * targetRatio
            val extraLonSpanM = wantedLonSpanM - currentLonSpanM
            val extraLonDeg = (extraLonSpanM / 2.0) / (111_320.0 * cosMid)
            east += extraLonDeg
            west -= extraLonDeg
        } else if (currentRatio > targetRatio) {
            // too wide (short) — grow latitude span
            val wantedLatSpanM = currentLonSpanM / targetRatio
            val extraLatSpanM = wantedLatSpanM - currentLatSpanM
            val extraLatDeg = (extraLatSpanM / 2.0) / 111_320.0
            north += extraLatDeg
            south -= extraLatDeg
        }

        // Apply vertical offset: shift bounds upward so the track appears
        // centered in the VISIBLE portion of the bitmap (top area that
        // won't be covered by a gradient/stats overlay).
        if (verticalOffsetFraction > 0.0) {
            val offset = (north - south) * verticalOffsetFraction.coerceIn(0.0, 0.5)
            north -= offset
            south -= offset
        }

        return LatLngBounds.Builder()
            .include(LatLng(north, east))
            .include(LatLng(south, west))
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
