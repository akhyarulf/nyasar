package com.nyasar.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.recording.ShareMetric
import com.nyasar.app.recording.SportType
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Generates share card bitmaps for activities.
 * 6 template styles — all free, no subscription/paywall.
 *
 * Templates:
 *   "map"         — green gradient + faded map grid + route + stats at bottom
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
    private val ORANGE = Color.parseColor("#FF6B00")
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

    fun generate(activity: ActivityEntity, track: List<TrackPoint>, template: String): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (template) {
            "map" -> drawMapTemplate(c, activity, track)
            "stats" -> drawStatsTemplate(c, activity, track)
            "dark_card" -> drawDarkCardTemplate(c, activity, track)
            "route" -> drawRouteTemplate(c, activity, track)
            "grid" -> drawGridTemplate(c, activity)
            "minimal" -> drawMinimalTemplate(c, activity)
            else -> drawMapTemplate(c, activity, track)
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

    // ── Template 1: Map — green gradient + grid + route + stats ──

    private fun drawMapTemplate(c: Canvas, a: ActivityEntity, track: List<TrackPoint>) {
        // Background gradient
        fillGradient(c, PRIMARY, DARK)

        // Subtle map grid
        drawMapGrid(c, Color.parseColor("#1AFFFFFF"))

        // Route in upper 60%
        if (track.size >= 2) {
            drawRouteProportional(c, track, 120f, 120f, CARD_W - 120f, CARD_H * 0.55f, 10f, ORANGE)
        }

        // Stats bar at bottom
        val barTop = CARD_H * 0.72f
        val barPaint = Paint().apply { color = Color.parseColor("#AA000000"); style = Paint.Style.FILL }
        c.drawRoundRect(RectF(40f, barTop, CARD_W - 40f, CARD_H - 160f), 28f, 28f, barPaint)

        val nameP = textPaint(60f, Typeface.DEFAULT_BOLD, WHITE)
        c.drawText(a.name, 80f, barTop + 80f, nameP)

        val statP = textPaint(48f, Typeface.DEFAULT_BOLD, WHITE)
        val labelP = textPaint(28f, Typeface.DEFAULT, LIGHT)
        val y1 = barTop + 160f
        val y2 = barTop + 210f
        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.elapsedTimeMs)

        c.drawText("Distance", 80f, y1, labelP); c.drawText(dist, 80f, y2, statP)
        c.drawText("Time", 420f, y1, labelP); c.drawText(dur, 420f, y2, statP)

        if (sportMetric(a) == ShareMetric.PACE) {
            c.drawText("Pace", 720f, y1, labelP); c.drawText(formatPace(a), 720f, y2, statP)
        } else {
            val gain = formatElevGain(a)
            c.drawText("Elev Gain", 720f, y1, labelP); c.drawText("\u2191 $gain", 720f, y2, statP)
        }

        // Branding
        c.drawText("Nyasar", 80f, CARD_H - 60f, textPaint(32f, Typeface.DEFAULT, Color.parseColor("#88FFFFFF")))
    }

    // ── Template 2: Stats — transparent + large centered stats + small route ──

    private fun drawStatsTemplate(c: Canvas, a: ActivityEntity, track: List<TrackPoint>) {
        // Transparent (leave blank / alpha 0 — checkerboard shown by caller)
        c.drawColor(Color.TRANSPARENT)

        val cx = CARD_W / 2f
        val big = textPaint(120f, Typeface.DEFAULT_BOLD, WHITE)
        val med = textPaint(48f, Typeface.DEFAULT, WHITE)
        val sm = textPaint(32f, Typeface.DEFAULT, LIGHT)

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.elapsedTimeMs)

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

        // Small route at bottom
        if (track.size >= 2) {
            drawRouteProportional(c, track, 200f, CARD_H * 0.78f, CARD_W - 200f, CARD_H * 0.92f, 6f, ORANGE)
        }

        c.drawText("Nyasar", cx - textPaint(28f, Typeface.DEFAULT, LIGHT).measureText("Nyasar") / 2,
            CARD_H - 60f, textPaint(28f, Typeface.DEFAULT, LIGHT))
    }

    // ── Template 3: Dark Card — dark textured bg + inset map card ──

    private fun drawDarkCardTemplate(c: Canvas, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(DARK)

        // Subtle diagonal stripes for texture
        val stripePaint = Paint().apply { color = Color.parseColor("#0DFFFFFF"); strokeWidth = 3f }
        var x = -CARD_H.toFloat()
        while (x < CARD_W + CARD_H) {
            c.drawLine(x, 0f, x + CARD_H, CARD_H.toFloat(), stripePaint)
            x += 80f
        }

        // Inset map card
        val cardRect = RectF(60f, 120f, CARD_W - 60f, CARD_H * 0.52f)
        val cardBg = Paint().apply { color = Color.parseColor("#E8E8E0"); style = Paint.Style.FILL }
        c.drawRoundRect(cardRect, 24f, 24f, cardBg)

        // Route inside card
        if (track.size >= 2) {
            drawRouteProportional(c, track, cardRect.left + 40f, cardRect.top + 40f,
                cardRect.right - 40f, cardRect.bottom - 40f, 8f, ORANGE)
        }

        // Stats below card
        val sy = CARD_H * 0.58f
        c.drawText(a.name, 80f, sy, textPaint(56f, Typeface.DEFAULT_BOLD, WHITE))

        val statP = textPaint(44f, Typeface.DEFAULT_BOLD, WHITE)
        val labelP = textPaint(26f, Typeface.DEFAULT, LIGHT)
        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.elapsedTimeMs)

        c.drawText("Distance", 80f, sy + 70f, labelP); c.drawText(dist, 80f, sy + 120f, statP)
        c.drawText("Time", 440f, sy + 70f, labelP); c.drawText(dur, 440f, sy + 120f, statP)

        if (sportMetric(a) == ShareMetric.PACE) {
            c.drawText("Pace", 780f, sy + 70f, labelP); c.drawText(formatPace(a), 780f, sy + 120f, statP)
        } else {
            val gain = formatElevGain(a)
            c.drawText("Elev", 780f, sy + 70f, labelP); c.drawText("\u2191 $gain", 780f, sy + 120f, statP)
        }

        c.drawText("Nyasar", 80f, CARD_H - 80f, textPaint(32f, Typeface.DEFAULT, Color.parseColor("#66FFFFFF")))
    }

    // ── Template 4: Route — transparent + large centered route ──

    private fun drawRouteTemplate(c: Canvas, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)

        if (track.size >= 2) {
            drawRouteProportional(c, track, 100f, CARD_H * 0.15f, CARD_W - 100f, CARD_H * 0.65f, 12f, ORANGE)
        }

        // Stats at bottom
        val sy = CARD_H * 0.78f
        val statP = textPaint(52f, Typeface.DEFAULT_BOLD, WHITE)
        val labelP = textPaint(28f, Typeface.DEFAULT, LIGHT)
        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.elapsedTimeMs)

        c.drawText("Distance", 80f, sy, labelP); c.drawText(dist, 80f, sy + 55f, statP)
        c.drawText("Time", 500f, sy, labelP); c.drawText(dur, 500f, sy + 55f, statP)

        c.drawText("Nyasar", CARD_W / 2f - textPaint(28f, Typeface.DEFAULT, LIGHT).measureText("Nyasar") / 2,
            CARD_H - 60f, textPaint(28f, Typeface.DEFAULT, LIGHT))
    }

    // ── Template 5: Grid — transparent + 6-stat grid ──

    private fun drawGridTemplate(c: Canvas, a: ActivityEntity) {
        c.drawColor(Color.TRANSPARENT)

        val col1 = CARD_W * 0.17f
        val col2 = CARD_W * 0.50f
        val col3 = CARD_W * 0.83f
        val row1 = CARD_H * 0.35f
        val row2 = CARD_H * 0.55f
        val valP = textPaint(56f, Typeface.DEFAULT_BOLD, WHITE)
        val lblP = textPaint(26f, Typeface.DEFAULT, LIGHT)

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.elapsedTimeMs)
        val gain = formatElevGain(a)

        // Primary metric: PACE for Run/Trail Run/Walk, ELEVATION for Hike/Wheelchair
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
        c.drawText(dist, col1 - valP.measureText(dist) / 2, row1 + 55f, valP)
        c.drawText(primaryLabel, col2 - lblP.measureText(primaryLabel) / 2, row1, lblP)
        c.drawText(primaryValue, col2 - valP.measureText(primaryValue) / 2, row1 + 55f, valP)
        c.drawText("Duration", col3 - lblP.measureText("Duration") / 2, row1, lblP)
        c.drawText(dur, col3 - valP.measureText(dur) / 2, row1 + 55f, valP)

        // Row 2: Elev Gain (always shown for elevation context)
        c.drawText("Elev Gain", col2 - lblP.measureText("Elev Gain") / 2, row2, lblP)
        c.drawText("\u2191 $gain", col2 - valP.measureText("\u2191 $gain") / 2, row2 + 55f, valP)

        c.drawText("Nyasar", CARD_W / 2f - lblP.measureText("Nyasar") / 2,
            CARD_H - 60f, textPaint(28f, Typeface.DEFAULT, LIGHT))
    }

    // ── Template 6: Minimal — solid green + name + big distance ──

    private fun drawMinimalTemplate(c: Canvas, a: ActivityEntity) {
        fillGradient(c, PRIMARY, Color.parseColor("#1A2A20"))

        val cx = CARD_W / 2f
        c.drawText(a.name, cx - textPaint(48f, Typeface.DEFAULT_BOLD, WHITE).measureText(a.name) / 2,
            CARD_H * 0.38f, textPaint(48f, Typeface.DEFAULT_BOLD, WHITE))

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        c.drawText(dist, cx - textPaint(140f, Typeface.DEFAULT_BOLD, WHITE).measureText(dist) / 2,
            CARD_H * 0.52f, textPaint(140f, Typeface.DEFAULT_BOLD, WHITE))

        c.drawText("Distance", cx - textPaint(32f, Typeface.DEFAULT, LIGHT).measureText("Distance") / 2,
            CARD_H * 0.57f, textPaint(32f, Typeface.DEFAULT, LIGHT))

        val dur = formatDuration(a.elapsedTimeMs)
        c.drawText(dur, cx - textPaint(64f, Typeface.DEFAULT_BOLD, WHITE).measureText(dur) / 2,
            CARD_H * 0.68f, textPaint(64f, Typeface.DEFAULT_BOLD, WHITE))

        c.drawText("Nyasar", cx - textPaint(28f, Typeface.DEFAULT, Color.parseColor("#88FFFFFF")).measureText("Nyasar") / 2,
            CARD_H - 80f, textPaint(28f, Typeface.DEFAULT, Color.parseColor("#88FFFFFF")))
    }

    // ── Helpers ──

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

    private fun textPaint(size: Float, typeface: Typeface, color: Int) = Paint().apply {
        textSize = size; this.typeface = typeface; this.color = color; isAntiAlias = true
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }
}
