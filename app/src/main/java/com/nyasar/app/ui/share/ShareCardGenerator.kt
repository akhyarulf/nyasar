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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.drawVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.res.ResourcesCompat
import com.nyasar.app.R
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
 * Visual language (Strava-style redesign):
 *   - map/dark_card: the whole map area carries a top-transparent → dark
 *     scrim (not just behind the text), stat VALUES are ~3x their labels,
 *     the route line is bright orange on dark/photo backgrounds.
 *   - every template: a small "Nyasar" watermark bottom-right and a
 *     circular white chip with the activity's SportType icon (the exact
 *     ImageVector set RecordingScreen/SportFilterSheet render).
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

    /**
     * Route line on dark/photo backgrounds: bright orange (#FF6B35). The old
     * muted green (#5A7562) melted into the full-map dark scrim and into dark
     * story backgrounds; orange is the warm accent already used in the app's
     * wayfinding palette (DANGER waypoint amber/red family) and keeps high
     * contrast against both the scrim and typical map tiles.
     */
    private val TRACK_COLOR = Color.parseColor("#FF6B35")

    /** Route line on LIGHT map fallbacks — dark green stays legible there. */
    private val TRACK_COLOR_LIGHT_BG = Color.parseColor("#2A5546")

    private val WHITE = Color.WHITE
    private val LIGHT = Color.parseColor("#CCCCCC")

    // Watermark: small "Nyasar" wordmark, bottom-right of EVERY template
    // (same values across all six — Strava-style branding).
    private const val WATERMARK_TEXT = "Nyasar"
    private const val WATERMARK_SIZE = 36f
    private val WATERMARK_COLOR = 0xCCFFFFFF.toInt()

    // Stat typography: value is ≥ 2.5x the label size (76f vs 26f ≈ 2.9x).
    private const val STAT_LABEL_SIZE = 26f
    private const val STAT_VALUE_SIZE = 76f

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

    /** The 2–3 label/value columns every stats bar shows, per sport metric. */
    private fun statColumns(ctx: android.content.Context, a: ActivityEntity): List<Pair<String, String>> = buildList {
        add(ctx.getString(R.string.share_stat_distance) to "%.2f km".format(a.distanceMeters / 1000.0))
        add(ctx.getString(R.string.share_stat_time) to formatDuration(a.movingTimeMs))
        if (sportMetric(a) == ShareMetric.PACE) {
            add(ctx.getString(R.string.share_stat_pace) to formatPace(a))
        } else {
            add(ctx.getString(R.string.share_stat_elev_gain) to "\u2191 ${formatElevGain(a)}")
        }
    }

    // ── Sport icon badge (reuses the exact ImageVector set from SportType —
    //    the same icons RecordingScreen/SportFilterSheet render) ──

    private val _sportIconCache = HashMap<String, Bitmap>()

    /**
     * Rasterizes a [SportType]'s ImageVector at [sizePx] px. Density is set
     * to sizePx/24 so the 24.dp vector fills the bitmap exactly; the glyph
     * renders with its native Material black fill (same monochrome look as
     * an untinted Material icon in the app UI) on the white chip.
     */
    private fun sportIconBitmap(type: SportType, sizePx: Int): Bitmap {
        val key = "${type.name}|$sizePx"
        _sportIconCache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(
            Density(density = sizePx / 24f),
            LayoutDirection.Ltr,
            ComposeCanvas(bmp),
            Size(sizePx.toFloat(), sizePx.toFloat())
        ) {
            drawVector(type.icon)
        }
        _sportIconCache[key] = bmp
        return bmp
    }

    /** White circular chip + sport glyph — Strava-style activity badge. */
    private fun drawSportIcon(c: Canvas, type: SportType, cx: Float, cy: Float, radius: Float) {
        c.drawCircle(cx, cy, radius, Paint().apply { color = WHITE; isAntiAlias = true })
        val iconPx = (radius * 2f * 0.58f).roundToInt().coerceAtLeast(8)
        val bmp = sportIconBitmap(type, iconPx)
        val half = iconPx / 2f
        c.drawBitmap(
            bmp, null,
            RectF(cx - half, cy - half, cx + half, cy + half),
            Paint().apply { isFilterBitmap = true; isAntiAlias = true }
        )
    }

    // ── Template 1: Map — real map snapshot + route + stats ──

    private fun drawMapTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>, mapSnapshot: Bitmap?, mapBounds: LatLngBounds?) {
        // snapTop: where the map area starts (0). snapBottom: where the map ends.
        val snapTop = 0f
        val snapBottom = CARD_H * 0.70f

        if (mapSnapshot != null) {
            // Draw real map snapshot, scaled to fill the upper 70% of the card
            val snapRect = RectF(0f, snapTop, CARD_W.toFloat(), snapBottom)
            c.drawBitmap(mapSnapshot, null, snapRect, null)
            // Full-map dark scrim: transparent at the very top, already
            // #66000000 by 40% down, deepening to #DD000000 at the map's
            // bottom edge — the whole map reads "dimmed" while the darkest
            // area still sits behind the stats bar (Strava-style).
            val mapGradient = LinearGradient(
                0f, snapTop, 0f, snapBottom,
                intArrayOf(
                    Color.parseColor("#00000000"),
                    Color.parseColor("#66000000"),
                    Color.parseColor("#DD000000")
                ),
                floatArrayOf(0f, 0.40f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(snapRect, Paint().apply { shader = mapGradient })
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
                    strokeWidth = 15f, color = TRACK_COLOR
                )
            } else {
                // Fallback: proportional scaling when no snapshot bounds
                drawRouteProportional(c, track, 100f, 80f, CARD_W - 100f, snapBottom, 15f, TRACK_COLOR)
            }
        }

        // Stats bar at bottom
        val barTop = CARD_H * 0.70f
        val barPaint = Paint().apply { color = Color.parseColor("#99000000"); style = Paint.Style.FILL }
        c.drawRoundRect(RectF(40f, barTop, CARD_W - 40f, CARD_H - 120f), 28f, 28f, barPaint)

        // Activity title with its sport icon chip to its left (the spot the
        // reference design puts its shoe icon).
        val nameP = textPaint(64f, interBold(ctx), WHITE)
        val chipR = 30f
        val nameBaseline = barTop + 96f
        drawSportIcon(c, SportType.fromString(a.sportType), 80f + chipR, nameBaseline - 22f, chipR)
        val nameX = 80f + chipR * 2 + 20f
        c.drawText(ellipsize(a.name, CARD_W - nameX - 60f, nameP), nameX, nameBaseline, nameP)

        // Stats: small label over a much larger value, auto-shrinking only
        // for pathological three-long-column cases.
        drawStatRow(
            c, ctx,
            columns = statColumns(ctx, a),
            leftX = 80f, rightX = CARD_W - 80f,
            labelBaselineY = barTop + 178f, valueGap = 76f
        )

        // Watermark bottom-right, inside the stats bar
        drawWatermark(c, ctx, xRight = CARD_W - 64f, yBaseline = CARD_H - 140f)
    }

    // ── Template 2: Stats — transparent + large centered stats + small route ──

    private fun drawStatsTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)

        val cx = CARD_W / 2f
        val big = textPaint(152f, interBold(ctx), WHITE)
        val sm = textPaint(34f, interRegular(ctx), LIGHT)

        // Sport badge top-left (this template draws no title).
        drawSportIcon(c, SportType.fromString(a.sportType), 110f, 150f, 36f)

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)

        val lblDistance = ctx.getString(R.string.share_stat_distance)
        val lblTime = ctx.getString(R.string.share_stat_time)
        c.drawText(lblDistance, cx - sm.measureText(lblDistance) / 2, CARD_H * 0.30f, sm)
        c.drawText(dist, cx - big.measureText(dist) / 2, CARD_H * 0.38f, big)

        c.drawText(lblTime, cx - sm.measureText(lblTime) / 2, CARD_H * 0.48f, sm)
        c.drawText(dur, cx - big.measureText(dur) / 2, CARD_H * 0.56f, big)

        if (sportMetric(a) == ShareMetric.PACE) {
            val pace = formatPace(a)
            val lblPace = ctx.getString(R.string.share_stat_pace)
            c.drawText(lblPace, cx - sm.measureText(lblPace) / 2, CARD_H * 0.64f, sm)
            c.drawText(pace, cx - big.measureText(pace) / 2, CARD_H * 0.72f, big)
        } else {
            val gain = formatElevGain(a)
            val lblElev = ctx.getString(R.string.share_stat_elev_gain)
            c.drawText(lblElev, cx - sm.measureText(lblElev) / 2, CARD_H * 0.64f, sm)
            c.drawText("\u2191 $gain", cx - big.measureText("\u2191 $gain") / 2, CARD_H * 0.72f, big)
        }

        if (track.size >= 2) {
            drawRouteProportional(c, track, 200f, CARD_H * 0.79f, CARD_W - 200f, CARD_H * 0.91f, 12f, TRACK_COLOR)
        }

        drawWatermark(c, ctx)
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

            // Full-card dark scrim (same profile as the "map" template):
            // transparent at the card's top, #66000000 by 40% down, #DD000000
            // at its bottom edge — the map reads dimmed end to end.
            val mapGradient = LinearGradient(
                0f, cardRect.top, 0f, cardRect.bottom,
                intArrayOf(
                    Color.parseColor("#00000000"),
                    Color.parseColor("#66000000"),
                    Color.parseColor("#DD000000")
                ),
                floatArrayOf(0f, 0.40f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(destRect, Paint().apply { shader = mapGradient })

            // Route overlay — MUST use destRect (same space as bitmap),
            // NOT cardRect. When destRect differs from cardRect due to
            // center-crop, using cardRect causes the route to shift.
            if (track.size >= 2 && mapBounds != null) {
                MapSnapshotHelper.drawTrackOnCanvas(
                    canvas = c, trackPoints = track, bounds = mapBounds,
                    canvasLeft = destRect.left, canvasTop = destRect.top,
                    canvasRight = destRect.right, canvasBottom = destRect.bottom,
                    strokeWidth = 15f, color = TRACK_COLOR
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
            // Fallback: light map-colored card + route when no snapshot.
            // LIGHT background → keep the dark-green track here, only the
            // stroke thickens (15f) like the snapshot path.
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
                    cardRect.right - 50f, cardRect.bottom - 50f, 15f, TRACK_COLOR_LIGHT_BG)
            }
        }

        // Title + sport icon chip to its left (same row as before, bigger).
        val sy = CARD_H * 0.56f
        val nameP = textPaint(62f, interBold(ctx), WHITE)
        val chipR = 30f
        drawSportIcon(c, SportType.fromString(a.sportType), 80f + chipR, sy - 20f, chipR)
        val nameX = 80f + chipR * 2 + 20f
        c.drawText(ellipsize(a.name, CARD_W - nameX - 60f, nameP), nameX, sy, nameP)

        drawStatRow(
            c, ctx,
            columns = statColumns(ctx, a),
            leftX = 80f, rightX = CARD_W - 80f,
            labelBaselineY = sy + 64f, valueGap = 76f
        )

        drawWatermark(c, ctx)
    }

    // ── Template 4: Route — transparent + large centered route ──

    private fun drawRouteTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)

        // Sport badge top-left (this template draws no title).
        drawSportIcon(c, SportType.fromString(a.sportType), 110f, 150f, 36f)

        if (track.size >= 2) {
            drawRouteProportional(c, track, 120f, CARD_H * 0.12f, CARD_W - 120f, CARD_H * 0.62f, 14f, TRACK_COLOR)
        }

        val sy = CARD_H * 0.75f
        val labelP = textPaint(30f, interRegular(ctx), LIGHT)
        val statP = textPaint(112f, interBold(ctx), WHITE)
        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)

        c.drawText(ctx.getString(R.string.share_stat_distance), 80f, sy, labelP); c.drawText(dist, 80f, sy + 96f, statP)
        c.drawText(ctx.getString(R.string.share_stat_time), 600f, sy, labelP); c.drawText(dur, 600f, sy + 96f, statP)

        drawWatermark(c, ctx)
    }

    // ── Template 5: Grid — transparent + stat grid ──

    private fun drawGridTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity) {
        c.drawColor(Color.TRANSPARENT)

        // Sport badge top-left.
        drawSportIcon(c, SportType.fromString(a.sportType), 110f, 150f, 36f)

        val col1 = CARD_W * 0.17f
        val col2 = CARD_W * 0.50f
        val col3 = CARD_W * 0.83f
        val row1 = CARD_H * 0.30f
        val row2 = CARD_H * 0.50f
        val valP = textPaint(70f, interBold(ctx), WHITE)
        val lblP = textPaint(28f, interRegular(ctx), LIGHT)

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)
        val gain = formatElevGain(a)

        val primaryLabel: String
        val primaryValue: String
        if (sportMetric(a) == ShareMetric.PACE) {
            primaryLabel = ctx.getString(R.string.share_stat_pace)
            primaryValue = formatPace(a)
        } else {
            primaryLabel = ctx.getString(R.string.share_stat_elev_gain)
            primaryValue = "\u2191 $gain"
        }

        // Row 1: Distance | Primary Metric | Duration
        val lblDistance = ctx.getString(R.string.share_stat_distance)
        val lblDuration = ctx.getString(R.string.share_stat_duration)
        val lblElev = ctx.getString(R.string.share_stat_elev_gain)
        val lblMaxSpeed = ctx.getString(R.string.share_stat_max_speed)
        val lblPoints = ctx.getString(R.string.share_stat_points)
        c.drawText(lblDistance, col1 - lblP.measureText(lblDistance) / 2, row1, lblP)
        c.drawText(dist, col1 - valP.measureText(dist) / 2, row1 + 92f, valP)
        c.drawText(primaryLabel, col2 - lblP.measureText(primaryLabel) / 2, row1, lblP)
        c.drawText(primaryValue, col2 - valP.measureText(primaryValue) / 2, row1 + 92f, valP)
        c.drawText(lblDuration, col3 - lblP.measureText(lblDuration) / 2, row1, lblP)
        c.drawText(dur, col3 - valP.measureText(dur) / 2, row1 + 92f, valP)

        // Row 2: Elev Gain | Max Speed | Point Count
        c.drawText(lblElev, col1 - lblP.measureText(lblElev) / 2, row2, lblP)
        c.drawText("\u2191 $gain", col1 - valP.measureText("\u2191 $gain") / 2, row2 + 92f, valP)

        c.drawText(lblMaxSpeed, col2 - lblP.measureText(lblMaxSpeed) / 2, row2, lblP)
        // maxSpeedKmh is nullable (null on recordings with no speed sample):
        // String.format on a null Double? throws NPE — render 0.0 instead.
        val maxSpeed = "%.1f km/h".format(a.maxSpeedKmh ?: 0.0)
        c.drawText(maxSpeed, col2 - valP.measureText(maxSpeed) / 2, row2 + 92f, valP)

        c.drawText(lblPoints, col3 - lblP.measureText(lblPoints) / 2, row2, lblP)
        c.drawText("${pointCount(a)}", col3 - valP.measureText("${pointCount(a)}") / 2, row2 + 92f, valP)

        drawWatermark(c, ctx)
    }

    // ── Template 6: Minimal — solid green + name + big distance ──

    private fun drawMinimalTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity) {
        fillGradient(c, PRIMARY, Color.parseColor("#1A2A20"))

        // Sport badge top-left.
        drawSportIcon(c, SportType.fromString(a.sportType), 110f, 150f, 36f)

        val cx = CARD_W / 2f
        val nameP = textPaint(56f, interBold(ctx), WHITE)
        val name = ellipsize(a.name, CARD_W - 160f, nameP)
        c.drawText(name, cx - nameP.measureText(name) / 2, CARD_H * 0.36f, nameP)

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val distP = textPaint(150f, interBold(ctx), WHITE)
        c.drawText(dist, cx - distP.measureText(dist) / 2, CARD_H * 0.50f, distP)

        val lblDistP = textPaint(34f, interRegular(ctx), LIGHT)
        val lblDist = ctx.getString(R.string.share_stat_distance)
        c.drawText(lblDist, cx - lblDistP.measureText(lblDist) / 2, CARD_H * 0.55f, lblDistP)

        val dur = formatDuration(a.movingTimeMs)
        val durP = textPaint(76f, interBold(ctx), WHITE)
        c.drawText(dur, cx - durP.measureText(dur) / 2, CARD_H * 0.66f, durP)

        drawWatermark(c, ctx)
    }

    // ── Helpers ──

    /**
     * Draws a label-over-value stat row that always fits [leftX]..[rightX]:
     * values start at [STAT_VALUE_SIZE] and step down (min 56f) only for
     * pathological three-long-column cases — typical cards keep the full
     * Strava-style ratio (76f value vs 26f label ≈ 2.9x).
     */
    private fun drawStatRow(
        c: Canvas, ctx: android.content.Context,
        columns: List<Pair<String, String>>,
        leftX: Float, rightX: Float, labelBaselineY: Float, valueGap: Float
    ) {
        val gap = 44f
        val lblP = textPaint(STAT_LABEL_SIZE, interRegular(ctx), LIGHT)
        var valueSize = STAT_VALUE_SIZE
        var valP = textPaint(valueSize, interBold(ctx), WHITE)
        fun colWidths(): List<Float> =
            columns.map { maxOf(lblP.measureText(it.first), valP.measureText(it.second)) }
        var widths = colWidths()
        while (widths.sum() + gap * (columns.size - 1) > rightX - leftX && valueSize > 56f) {
            valueSize -= 4f
            valP = textPaint(valueSize, interBold(ctx), WHITE)
            widths = colWidths()
        }
        var x = leftX
        columns.forEachIndexed { i, (label, value) ->
            c.drawText(label, x, labelBaselineY, lblP)
            c.drawText(value, x, labelBaselineY + valueGap, valP)
            x += widths[i] + gap
        }
    }

    /** Small "Nyasar" wordmark, right-aligned at (xRight, yBaseline). */
    private fun drawWatermark(c: Canvas, ctx: android.content.Context, xRight: Float = CARD_W - 56f, yBaseline: Float = CARD_H - 56f) {
        val p = textPaint(WATERMARK_SIZE, interBold(ctx), WATERMARK_COLOR)
        c.drawText(WATERMARK_TEXT, xRight - p.measureText(WATERMARK_TEXT), yBaseline, p)
    }

    /** Ellipsizes [text] with "…" so it never exceeds [maxWidthPx]. */
    private fun ellipsize(text: String, maxWidthPx: Float, p: Paint): String {
        if (p.measureText(text) <= maxWidthPx) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > maxWidthPx) t = t.dropLast(1)
        return "$t…"
    }

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
