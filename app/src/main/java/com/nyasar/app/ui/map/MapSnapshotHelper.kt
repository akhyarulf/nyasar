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
    private const val CACHE_VERSION = 9 // bump: removed verticalOffsetFraction from Share Card map request — it was cropping the visible route toward the gradient edge instead of showing the full route like List History does

    // P3K audit fix: basemap picker thumbnails (generateBasemapPreview)
    // were sharing CACHE_VERSION with activity-track snapshots above,
    // even though the two are unrelated. That meant a stale/failed
    // basemap thumbnail (e.g. all 4 raster entries falling back to the
    // same generic placeholder while offline, or from a since-fixed
    // RasterStyleJson bug) could sit on disk under
    // "basemap_<gpxKey>_<w>x<h>_v9.png" forever — nothing would ever
    // invalidate it, since bumping CACHE_VERSION for an unrelated
    // activity-thumbnail fix would silently also "fix" (by accident,
    // or not at all) basemap previews with no relation to that change.
    // A basemap-specific version lets this cache be invalidated
    // independently, and bumping it here (9 -> 10) explicitly discards
    // every previously-cached basemap thumbnail once, forcing a fresh
    // MapSnapshotter fetch per entry using each entry's own real style.
    private const val BASEMAP_PREVIEW_CACHE_VERSION = 10
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

    // version defaults to the shared CACHE_VERSION for every existing
    // caller (activity-track thumbnails, share cards) — behavior for
    // those callers is unchanged. generateBasemapPreview below is the
    // only caller that passes BASEMAP_PREVIEW_CACHE_VERSION explicitly,
    // so basemap thumbnails invalidate independently of everything else.
    private fun cacheFile(context: Context, activityId: String, widthPx: Int, heightPx: Int, version: Int = CACHE_VERSION): File =
        File(cacheDir(context), "${activityId}_${widthPx}x${heightPx}_v${version}.png")

    private fun loadFromDisk(context: Context, activityId: String, widthPx: Int, heightPx: Int, version: Int = CACHE_VERSION): Bitmap? {
        val file = cacheFile(context, activityId, widthPx, heightPx, version)
        return if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                file.delete()
                null
            }
        } else null
    }

    private fun saveToDisk(context: Context, activityId: String, widthPx: Int, heightPx: Int, bitmap: Bitmap, version: Int = CACHE_VERSION) {
        try {
            val file = cacheFile(context, activityId, widthPx, heightPx, version)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            android.util.Log.w("MapSnapshotHelper", "Cache write failed: ${e.message}")
        }
    }

    /**
     * Real map preview for the basemap picker (BasemapPickerSheet) — NOT
     * a duplicate snapshot system: reuses this exact object's disk cache
     * (cacheFile/loadFromDisk/saveToDisk) and the same MapSnapshotter
     * machinery as generateSync above, just with a fixed representative
     * region instead of one derived from an activity's track points (the
     * picker has no track to derive bounds from). [styleUrl] is always
     * one of this app's own real per-provider URLs from
     * TileProvider.styleUrlFor/BasemapEntry — never anything under
     * styles.gpx.studio — so this renders each basemap's genuine upstream
     * tiles/style, the same source the full map uses, just at thumbnail
     * size and for a fixed area instead of the user's current viewport.
     *
     * Cache key is the catalog entry's own id (passed in as [cacheKey]),
     * not an activity id — one cached thumbnail per basemap entry, shared
     * across every screen that opens the picker.
     *
     * P3K audit fix: this previously called loadFromDisk/saveToDisk with
     * no version argument, which defaulted to the shared [CACHE_VERSION]
     * — the same version counter used for unrelated activity-track
     * thumbnails. That meant a stale basemap thumbnail (e.g. captured
     * while offline, when every raster entry fails identically and the
     * caller falls back to the same generic placeholder) could never be
     * invalidated except by an activity-thumbnail-motivated version bump
     * that had nothing to do with basemaps. Now pinned to
     * [BASEMAP_PREVIEW_CACHE_VERSION], bumped independently, so a bad
     * cached basemap thumbnail is invalidated deliberately rather than
     * by accident (or never).
     */
    suspend fun generateBasemapPreview(
        context: Context,
        cacheKey: String,
        styleUrl: String,
        widthPx: Int,
        heightPx: Int
    ): Bitmap? {
        val cached = loadFromDisk(context, cacheKey, widthPx, heightPx, BASEMAP_PREVIEW_CACHE_VERSION)
        if (cached != null) return cached

        // Slopes of Gunung Lawu — has enough hillshade/contour/vegetation
        // variety that a viewer can actually tell the 9 styles apart (a
        // flat plain would look nearly identical across several of them),
        // and it's thematically the app's own reference hike rather than
        // an arbitrary coordinate.
        val bounds = LatLngBounds.Builder()
            .include(LatLng(-7.66, 111.13))
            .include(LatLng(-7.58, 111.22))
            .build()

        val bitmap = withContext(Dispatchers.Main) {
            try {
                val options = MapSnapshotter.Options(widthPx, heightPx).apply {
                    withStyle(styleUrl)
                    withRegion(bounds)
                    withAttribution(false)
                }
                val snapshotter = MapSnapshotter(context, options)
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    cont.invokeOnCancellation { snapshotter.cancel() }
                    snapshotter.start(object : MapSnapshotter.SnapshotReadyCallback {
                        override fun onSnapshotReady(snapshot: MapSnapshot) {
                            if (cont.isActive) cont.resume(snapshot.bitmap)
                        }
                    })
                }
            } catch (e: Exception) {
                android.util.Log.w("MapSnapshotHelper", "Basemap preview failed for $cacheKey: ${e.message}")
                null
            }
        }

        bitmap?.let { saveToDisk(context, cacheKey, widthPx, heightPx, it, BASEMAP_PREVIEW_CACHE_VERSION) }
        return bitmap
    }

    /**
     * One-time cleanup: deletes any on-disk basemap-picker thumbnail
     * cached under an older [BASEMAP_PREVIEW_CACHE_VERSION] (or the old
     * shared-CACHE_VERSION scheme this replaces), so a stale/wrong
     * thumbnail from before this fix can never be served again even if a
     * caller somehow still holds an old cache key. Safe to call
     * repeatedly — it only touches files matching the "basemap_" prefix,
     * never activity-track or share-card snapshots. Call once at app
     * startup or from BasemapPickerSheet's first composition.
     */
    fun purgeStaleBasemapPreviews(context: Context) {
        try {
            val dir = cacheDir(context)
            val currentSuffix = "_v${BASEMAP_PREVIEW_CACHE_VERSION}.png"
            dir.listFiles { f -> f.name.startsWith("basemap_") && !f.name.endsWith(currentSuffix) }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("MapSnapshotHelper", "Stale basemap preview purge failed: ${e.message}")
        }
    }
}
