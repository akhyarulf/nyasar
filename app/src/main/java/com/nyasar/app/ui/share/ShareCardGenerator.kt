package com.nyasar.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.recording.ShareMetric
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.map.MapSnapshotHelper
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Generates share card bitmaps for activities.
 * 6 template styles — all free, no subscription/paywall.
 *
 * Templates:
 *   "map"         — real map snapshot (or gradient fallback) + route + stats at bottom
 *   "stats"       — transparent (checkerboard) + large centered stats + small route
 *   "dark_card"   — dark textured bg + inset map card + stats below
 *   "route"       — transparent bg + large centered route + stats at bottom
 *   "grid"        — transparent bg + 6-stat grid + branding
 *   "minimal"     — solid green + activity name + one big distance number
 */
object ShareCardGenerator {

    private const val CARD_W = 1080
    private const val CARD_H = 1920

    private val PRIMARY = Color.parseColor("#5A7562")
    private val DARK = Color.parseColor("#2A3A30")
    private val TRACK_COLOR = Color.parseColor("#5A7562") // muted green — matches History List primary color
    private val WHITE = Color.WHITE
    private val LIGHT = Color.parseColor("#CCCCCC")
    private val GRAY = Color.parseColor("#999999")

    val TEMPLATES = listOf("map", "stats", "dark_card", "route", "grid", "minimal")

    fun templateLabel(key: String): String = when (key) {
        "map" -> "Map"
        "stats" -> "Stats"
        "dark_card" -> "Dark"
        "route" -> "Route"
        "grid" -> "Grid"
        "minimal" -> "Minimal"
        else -> key
    }

    fun generate(
        context: android.content.Context,
        activity: ActivityEntity,
        track: List<TrackPoint>,
        template: String,
        mapSnapshot: Bitmap? = null,
        mapBounds: LatLngBounds? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (template) {
            "map" -> drawMapTemplate(c, context, activity, track, mapSnapshot, mapBounds)
            "stats" -> drawStatsTemplate(c, context, activity, track)
            "dark_card" -> drawDarkCardTemplate(c, context, activity, track, mapSnapshot, mapBounds)
            "route" -> drawRouteTemplate(c, context, activity, track)
            "grid" -> drawGridTemplate(c, context, activity)
            "minimal" -> drawMinimalTemplate(c, context, activity)
            else -> drawMapTemplate(c, context, activity, track, mapSnapshot, mapBounds)
        }
        return bmp
    }

    // ── Helpers for sport-aware metric display ──

    private fun sportMetric(a: ActivityEntity): ShareMetric =
        SportType.fromString(a.sportType).primaryMetric

    private fun formatPace(a: ActivityEntity): String {
        if (a.distanceMeters <= 0) return "0:00 /km"
        val paceMinPerKm = (a.elapsedTimeMs / 60000.0) / (a.distanceMeters / 1000.0)
        val pm = paceMinPerKm.toInt()
        val ps = ((paceMinPerKm - pm) * 60).toInt()
        return "%d:%02d /km".format(pm, ps)
    }

    private fun formatElevGain(a: ActivityEntity): String =
        a.elevationGainM?.let { "${it.roundToInt()} m" } ?: "0 m"

    // ── Template 1: Map — real map snapshot + route + stats ──

    private fun drawMapTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>, mapSnapshot: Bitmap?, mapBounds: LatLngBounds?) {
        // snapTop: where the map area starts (0). snapBottom: where the map ends.
        val snapTop = 0f
        val snapBottom = CARD_H * 0.70f

        if (mapSnapshot != null) {
            // Draw real map snapshot, scaled to fill the upper 65% of the card
            val snapRect = RectF(0f, snapTop, CARD_W.toFloat(), snapBottom)
            c.drawBitmap(mapSnapshot, null, snapRect, null)
            // Dark gradient overlay at bottom of map for text readability
            val gradientH = CARD_H * 0.25f
            val gradTop = CARD_H * 0.50f
            val mapGradient = LinearGradient(
                0f, gradTop, 0f, gradTop + gradientH,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#CC000000")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(0f, gradTop, CARD_W.toFloat(), gradTop + gradientH,
                Paint().apply { shader = mapGradient })
        } else {
            // Fallback: gradient + grid (no network or snapshot failed)
            fillGradient(c, PRIMARY, DARK)
            drawMapGrid(c, Color.parseColor("#1AFFFFFF"))
        }

        // Route overlay — use the SAME bounds and SAME canvas area as the map
        // snapshot so the track aligns with the map tiles exactly.
        if (track.size >= 2) {
            if (mapBounds != null && mapSnapshot != null) {
                // Draw track using the snapshot's geographic bounds, mapped to
                // the exact same pixel area the bitmap occupies (snapTop..snapBottom)
                MapSnapshotHelper.drawTrackOnCanvas(
                    canvas = c, trackPoints = track, bounds = mapBounds,
                    canvasLeft = 0f, canvasTop = snapTop,
                    canvasRight = CARD_W.toFloat(), canvasBottom = snapBottom,
                    strokeWidth = 10f, color = TRACK_COLOR
                )
            } else {
                // Fallback: proportional scaling when no snapshot bounds
                drawRouteProportional(c, track, 100f, 80f, CARD_W - 100f, snapBottom, 10f, TRACK_COLOR)
            }
        }

        // Stats bar at bottom
        val barTop = CARD_H * 0.70f
        val barPaint = Paint().apply { color = Color.parseColor("#99000000"); style = Paint.Style.FILL }
        c.drawRoundRect(RectF(40f, barTop, CARD_W - 40f, CARD_H - 120f), 28f, 28f, barPaint)

        val nameP = textPaint(56f, interBold(ctx), WHITE)
        c.drawText(a.name, 80f, barTop + 72f, nameP)

        val statP = textPaint(46f, interBold(ctx), WHITE)
        val labelP = textPaint(26f, interRegular(ctx), LIGHT)
        val y1 = barTop + 140f
        val y2 = barTop + 188f
        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)

        c.drawText("Distance", 80f, y1, labelP); c.drawText(dist, 80f, y2, statP)
        c.drawText("Time", 420f, y1, labelP); c.drawText(dur, 420f, y2, statP)

        if (sportMetric(a) == ShareMetric.PACE) {
            c.drawText("Pace", 720f, y1, labelP); c.drawText(formatPace(a), 720f, y2, statP)
        } else {
            val gain = formatElevGain(a)
            c.drawText("Elev Gain", 720f, y1, labelP); c.drawText("\u2191 $gain", 720f, y2, statP)
        }

        // Branding
        c.drawText("Nyasar", 80f, CARD_H - 50f, textPaint(30f, interRegular(ctx), Color.parseColor("#88FFFFFF")))
    }

    // Legacy formatDuration kept for templates that still reference it;
    // new code should use the one above which handles hours.

    // ── Template 2: Stats — transparent + large centered stats + small route ──

    private fun drawStatsTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)

        val cx = CARD_W / 2f
        val big = textPaint(120f, interBold(ctx), WHITE)
        val med = textPaint(48f, interRegular(ctx), WHITE)
        val sm = textPaint(32f, interRegular(ctx), LIGHT)

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)

        c.drawText("Distance", cx - sm.measureText("Distance") / 2, CARD_H * 0.32f, sm)
        c.drawText(dist, cx - big.measureText(dist) / 2, CARD_H * 0.38f, big)

        c.drawText("Time", cx - sm.measureText("Time") / 2, CARD_H * 0.48f, sm)
        c.drawText(dur, cx - big.measureText(dur) / 2, CARD_H * 0.54f, big)

        if (sportMetric(a) == ShareMetric.PACE) {
            val pace = formatPace(a)
            c.drawText("Pace", cx - sm.measureText("Pace") / 2, CARD_H * 0.64f, sm)
            c.drawText(pace, cx - big.measureText(pace) / 2, CARD_H * 0.70f, big)
        } else {
            val gain = formatElevGain(a)
            c.drawText("Elev Gain", cx - sm.measureText("Elev Gain") / 2, CARD_H * 0.64f, sm)
            c.drawText("\u2191 $gain", cx - big.measureText("\u2191 $gain") / 2, CARD_H * 0.70f, big)
        }

        if (track.size >= 2) {
            drawRouteProportional(c, track, 200f, CARD_H * 0.78f, CARD_W - 200f, CARD_H * 0.92f, 10f, TRACK_COLOR)
        }

        c.drawText("Nyasar", cx - textPaint(28f, interRegular(ctx), LIGHT).measureText("Nyasar") / 2,
            CARD_H - 60f, textPaint(28f, interRegular(ctx), LIGHT))
    }

    // ── Template 3: Dark Card — dark textured bg + inset map card ──

    private fun drawDarkCardTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>, mapSnapshot: Bitmap?, mapBounds: LatLngBounds?) {
        c.drawColor(DARK)

        // Subtle diagonal stripes for texture
        val stripePaint = Paint().apply { color = Color.parseColor("#0DFFFFFF"); strokeWidth = 3f }
        var x = -CARD_H.toFloat()
        while (x < CARD_W + CARD_H) {
            c.drawLine(x, 0f, x + CARD_H, CARD_H.toFloat(), stripePaint)
            x += 80f
        }

        // Inset map card
        val cardRect = RectF(60f, 140f, CARD_W - 60f, CARD_H * 0.50f)

        if (mapSnapshot != null) {
            // Compute destRect: center-crop the bitmap into the card area
            // preserving aspect ratio. MUST be computed first so the route
            // overlay uses the same coordinate space as the bitmap.
            val bmpW = mapSnapshot.width.toFloat()
            val bmpH = mapSnapshot.height.toFloat()
            val cardW = cardRect.width()
            val cardH = cardRect.height()
            val bmpAspect = bmpW / bmpH
            val cardAspect = cardW / cardH
            val destRect = if (bmpAspect > cardAspect) {
                val scaledW = cardH * bmpAspect
                val offsetX = (cardW - scaledW) / 2f
                RectF(cardRect.left + offsetX, cardRect.top, cardRect.left + offsetX + scaledW, cardRect.bottom)
            } else {
                val scaledH = cardW / bmpAspect
                val offsetY = (cardH - scaledH) / 2f
                RectF(cardRect.left, cardRect.top + offsetY, cardRect.right, cardRect.top + offsetY + scaledH)
            }

            // Clip everything to the rounded card shape, then draw bitmap,
            // gradient, and route all in the SAME destRect coordinate space.
            c.save()
            val clipPath = Path().apply { addRoundRect(cardRect, 24f, 24f, Path.Direction.CW) }
            c.clipPath(clipPath)

            c.drawBitmap(mapSnapshot, null, destRect, null)

            // Dark gradient overlay at bottom of map for readability
            val gradientH = cardRect.height() * 0.3f
            val gradTop = cardRect.bottom - gradientH
            val mapGradient = LinearGradient(
                0f, gradTop, 0f, cardRect.bottom,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#CC000000")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(cardRect.left, gradTop, cardRect.right, cardRect.bottom,
                Paint().apply { shader = mapGradient })

            // Route overlay — MUST use destRect (same space as bitmap),
            // NOT cardRect. When destRect differs from cardRect due to
            // center-crop, using cardRect causes the route to shift.
            if (track.size >= 2 && mapBounds != null) {
                MapSnapshotHelper.drawTrackOnCanvas(
                    canvas = c, trackPoints = track, bounds = mapBounds,
                    canvasLeft = destRect.left, canvasTop = destRect.top,
                    canvasRight = destRect.right, canvasBottom = destRect.bottom,
                    strokeWidth = 10f, color = TRACK_COLOR
                )
            }

            c.restore() // release clip

            // Border around the card (not clipped — full stroke visible)
            val borderPaint = Paint().apply {
                color = Color.parseColor("#33FFFFFF")
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
            c.drawRoundRect(cardRect, 24f, 24f, borderPaint)
        } else {
            // Fallback: gradient + route when no snapshot
            val cardBg = Paint().apply { style = Paint.Style.FILL }
            val cardGradient = LinearGradient(
                cardRect.left, cardRect.top, cardRect.left, cardRect.bottom,
                intArrayOf(Color.parseColor("#E8E8E0"), Color.parseColor("#D0D0C8")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            cardBg.shader = cardGradient
            c.drawRoundRect(cardRect, 24f, 24f, cardBg)
            if (track.size >= 2) {
                drawRouteProportional(c, track, cardRect.left + 50f, cardRect.top + 50f,
                    cardRect.right - 50f, cardRect.bottom - 50f, 10f, TRACK_COLOR)
            }
        }

        val sy = CARD_H * 0.56f
        c.drawText(a.name, 80f, sy, textPaint(54f, interBold(ctx), WHITE))

        val statP = textPaint(44f, interBold(ctx), WHITE)
        val labelP = textPaint(26f, interRegular(ctx), LIGHT)
        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)

        c.drawText("Distance", 80f, sy + 70f, labelP); c.drawText(dist, 80f, sy + 120f, statP)
        c.drawText("Time", 440f, sy + 70f, labelP); c.drawText(dur, 440f, sy + 120f, statP)

        if (sportMetric(a) == ShareMetric.PACE) {
            c.drawText("Pace", 780f, sy + 70f, labelP); c.drawText(formatPace(a), 780f, sy + 120f, statP)
        } else {
            val gain = formatElevGain(a)
            c.drawText("Elev Gain", 780f, sy + 70f, labelP); c.drawText("\u2191 $gain", 780f, sy + 120f, statP)
        }

        c.drawText("Nyasar", 80f, CARD_H - 80f, textPaint(30f, interRegular(ctx), Color.parseColor("#66FFFFFF")))
    }

    // ── Template 4: Route — transparent + large centered route ──

    private fun drawRouteTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)

        if (track.size >= 2) {
            drawRouteProportional(c, track, 120f, CARD_H * 0.12f, CARD_W - 120f, CARD_H * 0.62f, 14f, TRACK_COLOR)
        }

        val sy = CARD_H * 0.75f
        val statP = textPaint(52f, interBold(ctx), WHITE)
        val labelP = textPaint(28f, interRegular(ctx), LIGHT)
        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)

        c.drawText("Distance", 80f, sy, labelP); c.drawText(dist, 80f, sy + 55f, statP)
        c.drawText("Time", 500f, sy, labelP); c.drawText(dur, 500f, sy + 55f, statP)

        c.drawText("Nyasar", CARD_W / 2f - textPaint(28f, interRegular(ctx), LIGHT).measureText("Nyasar") / 2,
            CARD_H - 60f, textPaint(28f, interRegular(ctx), LIGHT))
    }

    // ── Template 5: Grid — transparent + stat grid ──

    private fun drawGridTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity) {
        c.drawColor(Color.TRANSPARENT)

        val col1 = CARD_W * 0.17f
        val col2 = CARD_W * 0.50f
        val col3 = CARD_W * 0.83f
        val row1 = CARD_H * 0.33f
        val row2 = CARD_H * 0.52f
        val valP = textPaint(54f, interBold(ctx), WHITE)
        val lblP = textPaint(26f, interRegular(ctx), LIGHT)

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)
        val gain = formatElevGain(a)

        val primaryLabel: String
        val primaryValue: String
        if (sportMetric(a) == ShareMetric.PACE) {
            primaryLabel = "Pace"
            primaryValue = formatPace(a)
        } else {
            primaryLabel = "Elev Gain"
            primaryValue = "\u2191 $gain"
        }

        // Row 1: Distance | Primary Metric | Duration
        c.drawText("Distance", col1 - lblP.measureText("Distance") / 2, row1, lblP)
        c.drawText(dist, col1 - valP.measureText(dist) / 2, row1 + 52f, valP)
        c.drawText(primaryLabel, col2 - lblP.measureText(primaryLabel) / 2, row1, lblP)
        c.drawText(primaryValue, col2 - valP.measureText(primaryValue) / 2, row1 + 52f, valP)
        c.drawText("Duration", col3 - lblP.measureText("Duration") / 2, row1, lblP)
        c.drawText(dur, col3 - valP.measureText(dur) / 2, row1 + 52f, valP)

        // Row 2: Elev Gain | Max Speed | Point Count
        c.drawText("Elev Gain", col1 - lblP.measureText("Elev Gain") / 2, row2, lblP)
        c.drawText("\u2191 $gain", col1 - valP.measureText("\u2191 $gain") / 2, row2 + 52f, valP)

        c.drawText("Max Speed", col2 - lblP.measureText("Max Speed") / 2, row2, lblP)
        c.drawText("%.1f km/h".format(a.maxSpeedKmh), col2 - valP.measureText("%.1f km/h".format(a.maxSpeedKmh)) / 2, row2 + 52f, valP)

        c.drawText("Points", col3 - lblP.measureText("Points") / 2, row2, lblP)
        c.drawText("${pointCount(a)}", col3 - valP.measureText("${pointCount(a)}") / 2, row2 + 52f, valP)

        c.drawText("Nyasar", CARD_W / 2f - lblP.measureText("Nyasar") / 2,
            CARD_H - 60f, textPaint(28f, interRegular(ctx), LIGHT))
    }

    // ── Template 6: Minimal — solid green + name + big distance ──

    private fun drawMinimalTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity) {
        fillGradient(c, PRIMARY, Color.parseColor("#1A2A20"))

        val cx = CARD_W / 2f
        c.drawText(a.name, cx - textPaint(48f, interBold(ctx), WHITE).measureText(a.name) / 2,
            CARD_H * 0.36f, textPaint(48f, interBold(ctx), WHITE))

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        c.drawText(dist, cx - textPaint(140f, interBold(ctx), WHITE).measureText(dist) / 2,
            CARD_H * 0.50f, textPaint(140f, interBold(ctx), WHITE))

        c.drawText("Distance", cx - textPaint(32f, interRegular(ctx), LIGHT).measureText("Distance") / 2,
            CARD_H * 0.55f, textPaint(32f, interRegular(ctx), LIGHT))

        val dur = formatDuration(a.movingTimeMs)
        c.drawText(dur, cx - textPaint(64f, interBold(ctx), WHITE).measureText(dur) / 2,
            CARD_H * 0.66f, textPaint(64f, interBold(ctx), WHITE))

        c.drawText("Nyasar", cx - textPaint(28f, interRegular(ctx), Color.parseColor("#88FFFFFF")).measureText("Nyasar") / 2,
            CARD_H - 80f, textPaint(28f, interRegular(ctx), Color.parseColor("#88FFFFFF")))
    }

    // ── Helpers ──

    /** Format duration with hours when applicable. */
    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }

    private fun drawRouteProportional(
        c: Canvas, track: List<TrackPoint>,
        left: Float, top: Float, right: Float, bottom: Float,
        strokeW: Float, color: Int
    ) {
        if (track.size < 2) return
        val minLat = track.minOf { it.lat }; val maxLat = track.maxOf { it.lat }
        val minLon = track.minOf { it.lon }; val maxLon = track.maxOf { it.lon }
        val lonSpan = (maxLon - minLon).takeIf { it > 0.0 } ?: 1.0
        val latSpan = (maxLat - minLat).takeIf { it > 0.0 } ?: 1.0
        val cosLat = cos(Math.toRadians((minLat + maxLat) / 2.0)).toFloat()
        val lonW = (lonSpan * cosLat).toFloat()
        val latH = latSpan.toFloat()
        val maxDim = maxOf(lonW, latH)
        val areaW = right - left
        val areaH = bottom - top
        val scale = if (maxDim > 0f) min(areaW / lonW, areaH / latH) else 1f
        val rw = lonW * scale
        val rh = latH * scale
        val ox = left + (areaW - rw) / 2f
        val oy = top + (areaH - rh) / 2f

        val paint = Paint().apply {
            this.color = color; style = Paint.Style.STROKE
            strokeWidth = strokeW; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; isAntiAlias = true
        }
        val path = Path()
        track.forEachIndexed { i, p ->
            val x = ox + (p.lon - minLon).toFloat() * cosLat * scale
            val y = oy + rh - (p.lat - minLat).toFloat() * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, paint)
    }

    private fun drawMapGrid(c: Canvas, color: Int) {
        val paint = Paint().apply { this.color = color; strokeWidth = 1.5f }
        val sp = 60f
        var x = 0f; while (x <= CARD_W) { c.drawLine(x, 0f, x, CARD_H.toFloat(), paint); x += sp }
        var y = 0f; while (y <= CARD_H) { c.drawLine(0f, y, CARD_W.toFloat(), y, paint); y += sp }
    }

    private fun fillGradient(c: Canvas, top: Int, bottom: Int) {
        val g = LinearGradient(0f, 0f, 0f, CARD_H.toFloat(), intArrayOf(top, bottom),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawPaint(Paint().apply { shader = g })
    }

    /**
     * Inter typefaces for native Canvas text rendering.
     * Loaded ON via ResourcesCompat.getFont and cached — never re-read per render.
     */
    private fun interRegular(context: android.content.Context): Typeface =
        _interRegular ?: ResourcesCompat.getFont(context, com.nyasar.app.R.font.inter_regular)
            ?.also { _interRegular = it } ?: Typeface.DEFAULT

    private fun interBold(context: android.content.Context): Typeface =
        _interBold ?: ResourcesCompat.getFont(context, com.nyasar.app.R.font.inter_bold)
            ?.also { _interBold = it } ?: Typeface.DEFAULT_BOLD

    private var _interRegular: Typeface? = null
    private var _interBold: Typeface? = null

    private fun textPaint(size: Float, typeface: Typeface, color: Int) = Paint().apply {
        textSize = size; this.typeface = typeface; this.color = color; isAntiAlias = true
    }

    private fun pointCount(a: ActivityEntity): Int {
        // Approximate from distance — not exact but enough for display
        return (a.distanceMeters / 10.0).toInt().coerceAtLeast(1)
    }
}
