package com.nyasar.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nyasar.app.BuildConfig
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.ui.browse.trackPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Static "hero" map preview for browse cards: a small raster-tile snapshot
 * (same upstream sources as the app's own basemap catalog) with the route
 * trace drawn on top — the Strava-style visual for a card list whose photo
 * slot was intentionally removed (Keputusan poin 1, PROJECT_CONTEXT.md).
 *
 * Tile source precedence:
 *  1. MapTiler "topo" raster tiles — only when MAPTILER_API_KEY is set
 *     (same key the map screens already use, from local.properties).
 *  2. OpenTopoMap keyless raster (catalog entry OPEN_TOPO_MAP upstream).
 *  3. No tiles at all: a pure-canvas polyline fallback (the previous card
 *     look) — also the LOADING and the FAILURE state.
 *
 * Offline/no-signal behavior: tiles are best-effort decoration. Any
 * failure degrades to the fallback silently — a browse card must never
 * error, block, or retry-loop (hiking context: no signal is normal).
 * Fetches run on Dispatchers.IO via produceState and are cancelled with
 * the composable when the card scrolls out of the LazyColumn viewport.
 *
 * Geometry: the snapshot camera fits the route bbox with the same Mercator
 * math as the raster path (see snapshotCameraFor for the abs() audit fix);
 * the trace overlay reads pixel positions straight from the rendered
 * snapshot (MapSnapshot.pixelForLatLng) so it can never drift.
 */

/** Slippy tile is 256px at scale 1. */
private const val TILE_PX = 256

/** Bbox-fit scales below this mean the route span needs a multi-MB source
 *  canvas — too expensive for a card; callers fall back to the plain
 *  polyline canvas (see computeStaticMapLayout). */
private const val MIN_SCALE = 0.575f

/** World-pixel Y for a latitude (normalized 0..1 world fraction * tiles * 256 happens at call sites). */
private fun mercY(latDeg: Double): Double {
    val s = sin(Math.toRadians(latDeg))
    return 0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * Math.PI)
}

private fun lonToTile(lonDeg: Double, worldTiles: Int): Double =
    (lonDeg + 180.0) / 360.0 * worldTiles

private fun latToTile(latDeg: Double, worldTiles: Int): Double =
    mercY(latDeg) * worldTiles

/**
 * Pure layout math (no Android graphics) — internal so unit tests can pin
 * the chosen zoom/origin/tile set for known bboxes without an emulator.
 */
internal data class StaticMapLayout(
    val zoom: Int,
    /** View px per source px (0.575..1.15 by construction — see class doc). */
    val scale: Float,
    /** View origin in world pixels at [zoom]. */
    val originX: Double,
    val originY: Double,
    val sourceW: Int,
    val sourceH: Int
) {
    private val worldTiles: Int get() = 1 shl zoom

    fun sourceX(lon: Double): Double = lonToTile(lon, worldTiles) * TILE_PX - originX
    fun sourceY(lat: Double): Double = latToTile(lat, worldTiles) * TILE_PX - originY

    /** Tile (x, y) coords covering the whole source canvas — never a huge
     *  set: the zoom choice bounds the view to ~1-2 tiles per axis. */
    val tiles: List<Pair<Int, Int>>
        get() {
            val x0 = floor(originX / TILE_PX).toInt()
            val y0 = floor(originY / TILE_PX).toInt()
            val x1 = floor((originX + sourceW) / TILE_PX).toInt()
            val y1 = floor((originY + sourceH) / TILE_PX).toInt()
            val out = mutableListOf<Pair<Int, Int>>()
            for (y in y0..y1) for (x in x0..x1) out += x to y
            return out
        }
}

internal fun computeStaticMapLayout(
    points: List<TrackPoint>,
    viewW: Float,
    viewH: Float,
    maxZoom: Int
): StaticMapLayout? {
    if (points.size < 2 || viewW < 8f || viewH < 8f) return null
    val minLat = points.minOf { it.lat }.coerceIn(-85.05, 85.05)
    val maxLat = points.maxOf { it.lat }.coerceIn(-85.05, 85.05)
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val lonSpan = (maxLon - minLon).takeIf { it > 1e-9 } ?: return null
    // AUDIT FIX: abs() — mercY decreases as latitude grows, so the raw span
    // is negative; without abs this path ALSO ignored track height.
    val latSpanMerc = abs(mercY(maxLat) - mercY(minLat)).takeIf { it > 1e-12 } ?: return null

    // First zoom (from low) whose on-screen scale drops to <= 1.15 —
    // monotonic per zoom step (bbox px doubles, so scale halves), hence the
    // first hit is the most detailed zoom without visible upscaling.
    var zoom = 3
    var scale = Float.MAX_VALUE
    for (z in 3..maxZoom) {
        val wPx = lonSpan / 360.0 * (1 shl z) * TILE_PX
        val hPx = latSpanMerc * (1 shl z) * TILE_PX
        val s = min(viewW / wPx.toFloat(), viewH / hPx.toFloat())
        zoom = z
        scale = s
        if (s <= 1.15f) break
    }
    // Degenerate-extent handling (audit fix):
    //  - scale > 1.15 after the loop = TINY route (never broke — bbox far
    //    smaller than one tile even at maxZoom). Clamp to 1.15: the map
    //    shows the ~city-block area around the route (Strava does the same
    //    for very short activities) instead of a 1px stretched bitmap.
    //  - scale < MIN_SCALE = HUGE route (broke at low zoom with heavy tile
    //    downscale). Fitting it would need a multi-MB bitmap and 25+ tile
    //    requests for ONE card — return null and let the caller draw the
    //    canvas-polyline fallback instead. Roughly >40-50km spans.
    scale = scale.coerceAtMost(1.15f)
    if (scale < MIN_SCALE) return null
    val sourceW = ceil(viewW / scale).toInt()
    val sourceH = ceil(viewH / scale).toInt()
    val centerX = lonToTile((minLon + maxLon) / 2.0, 1 shl zoom) * TILE_PX
    val centerY = latToTile((minLat + maxLat) / 2.0, 1 shl zoom) * TILE_PX
    return StaticMapLayout(
        zoom = zoom,
        scale = scale,
        originX = centerX - sourceW / 2.0,
        originY = centerY - sourceH / 2.0,
        sourceW = sourceW,
        sourceH = sourceH
    )
}

/** Tile URL for one (x, y) at [layout]'s zoom from ONE named source.
 *  Sources are tried in order by [fetchTileWithFallback]:
 *   1. MapTiler "topo" — only when MAPTILER_API_KEY is set (CI/release
 *      builds don't get the key from local.properties, so often absent),
 *   2. OpenStreetMap standard raster — keyless, generous, real street/terrain
 *      context (the "peta kebaca" look users expect from a map hero),
 *   3. OpenTopoMap — keyless topo; throttles aggressively in practice
 *      (frequently answers 200 with a tiny placeholder tile), so it's LAST.
 *  x wraps around the world seam; y clamps (polar overdraw can request an
 *  out-of-range row that no server would serve). */
internal fun buildTileUrl(layout: StaticMapLayout, x: Int, y: Int, mapTilerKey: String, source: Int): String? {
    val z = layout.zoom
    val worldTiles = 1 shl z
    val wx = ((x % worldTiles) + worldTiles) % worldTiles
    val wy = y.coerceIn(0, worldTiles - 1)
    return when (source) {
        SRC_MAPTILER -> if (mapTilerKey.isNotBlank()) {
            "https://api.maptiler.com/maps/topo/$z/$wx/$wy.png?key=$mapTilerKey"
        } else null
        SRC_OSM -> "https://tile.openstreetmap.org/$z/$wx/$wy.png"
        else -> {
            val host = listOf("a", "b", "c")[(wx + wy).mod(3)]
            "https://$host.tile.opentopomap.org/$z/$wx/$wy.png"
        }
    }
}

internal const val SRC_MAPTILER = 0
internal const val SRC_OSM = 1
internal const val SRC_OPENTOPOMAP = 2

/** Small in-memory cache so fling-scrolling the LazyColumn doesn't refetch
 *  tiles for cards that just left the viewport. Sized by BYTES (bitmap
 *  memory), capped at 12MB — bounded and tiny next to the real map screens,
 *  and it can never hold more regardless of preview dimensions. */
private object StaticTileCache {
    val lru = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
}

/**
 * Static tile result — internal so the shared fallback fetcher can return it.
 */
internal sealed interface TileResult {
    data class Ok(val bitmap: Bitmap) : TileResult
    data object Failed : TileResult
}

private fun fetchTile(url: String): TileResult = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 4000
    conn.readTimeout = 4000
    // Identifiable UA — OpenStreetMap & OpenTopoMap usage policies ask for one.
    conn.setRequestProperty("User-Agent", "Nyasar/1.0 (Android; static route preview)")
    if (conn.responseCode != 200) {
        TileResult.Failed
    } else {
        conn.inputStream.use { stream ->
            // Reject placeholder tiles: throttled servers answer 200 with a
            // few-byte PNG (a real 256px raster tile is always >= ~1KB).
            val bmp = BitmapFactory.decodeStream(stream)
            if (bmp != null && bmp.byteCount > 512) TileResult.Ok(bmp) else TileResult.Failed
        }
    }
} catch (_: Exception) {
    TileResult.Failed
}

/** Fetch one tile trying sources in order: MapTiler (when keyed) → OSM →
 *  OpenTopoMap. First success wins. All-fail → Failed → card falls back to
 *  the canvas polyline (never an error state — offline is normal here). */internal fun fetchTileWithFallback(
    layout: StaticMapLayout,
    x: Int,
    y: Int,
    mapTilerKey: String
): TileResult {
    // Explicit TileResult type: `var last = TileResult.Failed` would infer
    // the data-object type and reject assignments of the Ok variant.
    var last: TileResult = TileResult.Failed
    for (source in intArrayOf(SRC_MAPTILER, SRC_OSM, SRC_OPENTOPOMAP)) {
        val url = buildTileUrl(layout, x, y, mapTilerKey, source) ?: continue
        last = fetchTile(url)
        if (last is TileResult.Ok) return last
    }
    return last
}

/** Compose the base map + route trace into one bitmap, or null on ANY tile
 *  failure (partial maps look broken — fall back to the canvas instead). */
internal fun renderStaticMap(
    layout: StaticMapLayout,
    points: List<TrackPoint>,
    tiles: Map<Pair<Int, Int>, Bitmap>,
    traceColorArgb: Int,
    traceStrokeSourcePx: Float
): Bitmap? {
    val out = Bitmap.createBitmap(layout.sourceW, layout.sourceH, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(out)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    layout.tiles.forEach { (x, y) ->
        val bmp = tiles[x to y] ?: return null
        canvas.drawBitmap(bmp, (x * TILE_PX - layout.originX).toFloat(), (y * TILE_PX - layout.originY).toFloat(), paint)
    }
    val trace = AndroidPath().apply {
        points.forEachIndexed { i, p ->
            val sx = layout.sourceX(p.lon).toFloat()
            val sy = layout.sourceY(p.lat).toFloat()
            if (i == 0) moveTo(sx, sy) else lineTo(sx, sy)
        }
    }
    paint.style = AndroidPaint.Style.STROKE
    paint.strokeWidth = traceStrokeSourcePx
    paint.strokeJoin = AndroidPaint.Join.ROUND
    paint.strokeCap = AndroidPaint.Cap.ROUND
    paint.color = traceColorArgb
    // Dark casing under the colored trace = readable on any basemap.
    canvas.drawPath(trace, paint.apply { strokeWidth = traceStrokeSourcePx * 1.8f; color = 0xAA202020.toInt() })
    canvas.drawPath(trace, paint.apply { strokeWidth = traceStrokeSourcePx; color = traceColorArgb })
    // Start/end dots.
    paint.style = AndroidPaint.Style.FILL
    listOf(points.first(), points.last()).forEach { p ->
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(layout.sourceX(p.lon).toFloat(), layout.sourceY(p.lat).toFloat(), traceStrokeSourcePx * 1.5f, paint)
        paint.color = traceColorArgb
        canvas.drawCircle(layout.sourceX(p.lon).toFloat(), layout.sourceY(p.lat).toFloat(), traceStrokeSourcePx * 0.8f, paint)
    }
    return out
}

@Composable
fun StaticMapPreview(
    polyline: String,
    modifier: Modifier = Modifier,
    maxZoom: Int = 14
) {
    val points = remember(polyline) { BrowseRepository.decodeTrack(polyline) }
    val traceColor = MaterialTheme.colorScheme.primary
    val fallbackBg = MaterialTheme.colorScheme.surfaceVariant
    val density = LocalDensity.current
    // Trace stroke in VIEW px (converted to bitmap px inside the renderer).
    val strokeViewPx = with(density) { 2.5.dp.toPx() }
    val apiKey = BuildConfig.MAPTILER_API_KEY
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Snapshot at up to 2x for crisp high-dpi cards (bitmap drawn back down
    // 1:1 by the Canvas below).
    val pixelRatio = remember { minOf(2f, density.density.coerceAtLeast(1f)) }

    var canvasW by remember { mutableStateOf(0f) }
    var canvasH by remember { mutableStateOf(0f) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(points, canvasW, canvasH, traceColor) {
        val w = canvasW
        val h = canvasH
        var snapshotter: MapSnapshotter? = null
        if (points.size >= 2 && w >= 8f && h >= 8f) {
            val key = "ofm-${points.hashCode()}-${w.toInt()}x${h.toInt()}-${traceColor.toArgbCompat()}"
            val cached = StaticTileCache.lru.get(key)
            if (cached != null) {
                rendered = cached
            } else {
                // Primary engine: MapLibre MapSnapshotter with the SAME
                // keyless OpenFreeMap Liberty style the app's real map
                // screens use (proven reachable from devices). No API key,
                // no per-tile HTTP, no throttle placeholders.
                val camera = snapshotCameraFor(points, w, h, maxZoom)
                snapshotter = MapSnapshotter(
                    context,
                    MapSnapshotter.Options(w.toInt(), h.toInt())
                        .withStyleBuilder(Style.Builder().fromUri(SNAPSHOT_STYLE_URL))
                        .withCameraPosition(camera)
                        .withPixelRatio(pixelRatio)
                ).also { snap ->
                    snap.start(
                        { snapshot ->
                            try {
                                drawTraceOnSnapshot(
                                    base = snapshot.bitmap,
                                    points = points,
                                    ratio = pixelRatio,
                                    // Ground truth from the engine: ask the
                                    // snapshot where each point landed.
                                    toBitmapPx = { p -> snapshot.pixelForLatLng(LatLng(p.lat, p.lon)) },
                                    strokeViewPx = strokeViewPx,
                                    traceColorArgb = traceColor.copy(alpha = 1f).toArgbCompat()
                                )
                            } catch (_: Exception) {
                                null
                            }?.let { bmp ->
                                StaticTileCache.lru.put(key, bmp)
                                rendered = bmp
                            }
                        },
                        { _ ->
                            // Snapshotter failed (offline first launch, style
                            // unreachable): fall back to the direct raster
                            // tile chain, then the plain polyline canvas.
                            scope.launch {
                                val bmp = withContext(Dispatchers.IO) {
                                    renderRasterFallback(points, w, h, maxZoom, apiKey, strokeViewPx, traceColor)
                                }
                                if (bmp != null) {
                                    StaticTileCache.lru.put(key, bmp)
                                    rendered = bmp
                                }
                            }
                        }
                    )
                }
            }
        }
        onDispose { snapshotter?.cancel() }
    }

    Canvas(
        modifier = modifier.onSizeChanged {
            canvasW = it.width.toFloat()
            canvasH = it.height.toFloat()
        }
    ) {
        // Fallback (also the loading state): tinted canvas + polyline —
        // the previous card look, so the list is never blank while the
        // map renders or when there is no signal.
        drawRect(fallbackBg)
        val path = trackPath(points, size.width, size.height)
        if (!path.isEmpty) {
            drawPath(path, traceColor, style = Stroke(width = strokeViewPx))
        }
        // Map ready → draw the composed snapshot over the fallback, 1:1.
        rendered?.let { bmp ->
            drawImage(
                image = bmp.asImageBitmap(),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
            )
        }
    }
}

/** OpenFreeMap Liberty — keyless, unlimited (see BasemapCatalog), and the
 *  same style the app's interactive map screens already load successfully. */
private const val SNAPSHOT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Camera fitting [points]' bbox into a w×h view with a small margin.
 *
 *  AUDIT FIX (track-terpotong): mercY is north-anchored, so mercY(maxLat) -
 *  mercY(minLat) is NEGATIVE for every normal bbox — the old takeIf{>0}
 *  always fell back to 1e-5 and the fit zoom ignored the track's height
 *  entirely (tall point-to-point routes got zoomed ~2.6x past the card and
 *  clipped top/bottom). abs() restores the real Mercator span.
 *
 *  Pure math for the camera fit; the on-bitmap trace overlay itself reads
 *  pixel positions from the rendered snapshot (pixelForLatLng), not from
 *  this math. */
internal fun snapshotCameraFor(
    points: List<TrackPoint>,
    viewW: Float,
    viewH: Float,
    maxZoom: Int
): CameraPosition {
    val minLat = points.minOf { it.lat }.coerceIn(-85.05, 85.05)
    val maxLat = points.maxOf { it.lat }.coerceIn(-85.05, 85.05)
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val lonSpan = (maxLon - minLon).takeIf { it > 1e-9 } ?: 1e-4
    val latSpanMerc = abs(mercY(maxLat) - mercY(minLat)).takeIf { it > 1e-12 } ?: 1e-5
    // Largest integer zoom where the bbox still FITS inside the view.
    var zoom = 3
    for (z in 3..maxZoom + 2) {
        val wPx = lonSpan / 360.0 * (1 shl z) * SNAPSHOT_WORLD_PX
        val hPx = latSpanMerc * (1 shl z) * SNAPSHOT_WORLD_PX
        if (wPx > viewW || hPx > viewH) break
        zoom = z
    }
    // Fractional zoom-out = margin so the trace isn't glued to the card
    // edges (same visual padding the 80px newLatLngBounds gave the real map).
    val fitted = zoom - 0.45
    return CameraPosition.Builder()
        .target(LatLng((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0))
        .zoom(fitted.coerceAtMost(16.0))
        .build()
}

/** World pixels per zoom-0 tile in the GL/Mercator model used for the fit. */
private const val SNAPSHOT_WORLD_PX = 512.0

/** Copies the snapshotter's base map and draws the route trace + start/end
 *  dots on top with the same casing/stroke look the raster path uses.
 *
 *  Pixel positions come from [toBitmapPx] — production passes
 *  MapSnapshot.pixelForLatLng, i.e. the engine's own projection. No local
 *  world-pixel math is involved anymore, so the trace CANNOT drift off the
 *  rendered basemap (the old recomputed-projection approach relied on
 *  matching the native renderer's conventions exactly). */
internal fun drawTraceOnSnapshot(
    base: Bitmap,
    points: List<TrackPoint>,
    ratio: Float,
    toBitmapPx: (TrackPoint) -> android.graphics.PointF,
    strokeViewPx: Float,
    traceColorArgb: Int
): Bitmap? {
    if (points.size < 2) return null
    val out = base.copy(Bitmap.Config.ARGB_8888, true) ?: return null
    val canvas = AndroidCanvas(out)
    val trace = AndroidPath()
    points.forEachIndexed { i, p ->
        val pf = toBitmapPx(p)
        if (i == 0) trace.moveTo(pf.x, pf.y) else trace.lineTo(pf.x, pf.y)
    }
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.STROKE
        strokeJoin = AndroidPaint.Join.ROUND
        strokeCap = AndroidPaint.Cap.ROUND
    }
    val stroke = (strokeViewPx * ratio).coerceIn(3f, 10f)
    // Dark casing under the colored trace = readable on any basemap.
    paint.strokeWidth = stroke * 1.8f
    paint.color = 0xAA202020.toInt()
    canvas.drawPath(trace, paint)
    paint.strokeWidth = stroke
    paint.color = traceColorArgb
    canvas.drawPath(trace, paint)
    // Start/end dots.
    paint.style = AndroidPaint.Style.FILL
    listOf(points.first(), points.last()).forEach { p ->
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(sx(p), sy(p), stroke * 1.5f, paint)
        paint.color = traceColorArgb
        canvas.drawCircle(sx(p), sy(p), stroke * 0.8f, paint)
    }
    return out
}

/** Direct raster-tile fallback (MapTiler→OSM→OpenTopoMap), used only when
 *  the MapLibre snapshotter itself fails. Must run off the main thread. */
internal fun renderRasterFallback(
    points: List<TrackPoint>,
    w: Float,
    h: Float,
    maxZoom: Int,
    mapTilerKey: String,
    strokeViewPx: Float,
    traceColor: androidx.compose.ui.graphics.Color
): Bitmap? {
    val layout = computeStaticMapLayout(points, w, h, maxZoom) ?: return null
    val coords = layout.tiles
    val fetched = coords.associateWith { (x, y) -> fetchTileWithFallback(layout, x, y, mapTilerKey) }
    val tiles = fetched.mapNotNull { (coord, res) -> (res as? TileResult.Ok)?.let { coord to it.bitmap } }
    if (tiles.size != coords.size) return null
    return renderStaticMap(
        layout = layout,
        points = points,
        tiles = tiles.toMap(),
        traceColorArgb = traceColor.copy(alpha = 1f).toArgbCompat(),
        traceStrokeSourcePx = (strokeViewPx / layout.scale).coerceIn(2f, 6f)
    )
}

/** Color -> ARGB int for the android.graphics paint side. */
private fun androidx.compose.ui.graphics.Color.toArgbCompat(): Int =
    (alpha * 255f).toInt().shl(24) or
        (red * 255f).toInt().shl(16) or
        (green * 255f).toInt().shl(8) or
        (blue * 255f).toInt()
