package com.nyasar.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.PathParser
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
 * Visual language (Strava-style redesign, 2026-09):
 *   - map: FULL-BLEED map (the snapshot covers the entire card, center-
 *     cropped) under a top-transparent → near-black scrim; sport glyph +
 *     NYASAR wordmark + title + two stat rows drawn directly ON the map —
 *     the exact anatomy of Strava's activity story card. No separate stats
 *     bar below the map anymore.
 *   - Route lines are the NYASAR GREEN family — NO orange (user request):
 *     a bright green fill over a dark casing, the web browse preview's
 *     trick, so the line holds contrast on light map tiles AND dark scrim.
 *   - every template: a small "Nyasar" watermark bottom-right (map uses
 *     the big NYASAR wordmark in its overlay block instead) and a circular
 *     white chip with the activity's SportType icon (except map, whose
 *     glyph is bare white — Strava-style).
 *
 * Templates:
 *   "map"         — full-bleed map + route + overlay stats (Strava-style)
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
     * Route line colors — Nyasar GREEN family, no orange (user request).
     * TRACK_FILL #A5C0AA is the app's dark-theme primary: bright enough to
     * pop on dark scrims. TRACK_CASE #2A3A30 is drawn ~1.7x wider UNDER the
     * fill (the web browse preview's casing trick) so the line also holds
     * contrast against light map tiles. Together they read on any background
     * while staying 100% inside the Nyasar palette.
     */
    private val TRACK_FILL = Color.parseColor("#A5C0AA")
    private val TRACK_CASE = Color.parseColor("#2A3A30")

    /** Route line on LIGHT map fallbacks — dark green stays legible there. */
    private val TRACK_COLOR_LIGHT_BG = Color.parseColor("#2A5546")

    private val WHITE = Color.WHITE
    private val LIGHT = Color.parseColor("#CCCCCC")

    /** Map-template brand wordmark (Strava puts its logo in the overlay). */
    private const val WORDMARK_TEXT = "NYASAR"
    private const val WORDMARK_SIZE = 46f
    private val WORDMARK_COLOR = 0xE6FFFFFF.toInt()

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

    // ── Sport icon badge — the exact Material glyph set behind
    //    SportType.icon (the ImageVector set RecordingScreen/SportFilterSheet
    //    render): the map below embeds the official Material Icons
    //    "filled 24px" path data for each sport, drawn with
    //    androidx.core.graphics.PathParser. DrawScope.drawVector only exists
    //    in Compose UI 1.7+ (this project pins 1.6.x via BOM 2024.06), so
    //    rasterizing ImageVectors via CanvasDrawScope does not compile here;
    //    identical glyphs, version-proof rendering. ──

    // Card generation can run on a background thread (export flows); guard
    // the raster cache against concurrent reads/writes.
    private val _sportIconCache = HashMap<String, Bitmap>()

    private val SPORT_ICON_PATHS = mapOf(
        SportType.RUN to "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2m1.5 4c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1m2.5 6c-.7 0-2.01-.54-2.91-1.76l-.41 2.35L14 14.03V18h-1v-3.58l-1.11-1.21-.52 2.64-3.77-.77.2-.98 2.78.57.96-4.89-1.54.57V12H9V9.65l3.28-1.21c.49-.18 1.03.06 1.26.53.83 1.7 2.05 2.03 2.46 2.03z",
        SportType.TRAIL_RUN to "M11.23 6c-1.66 0-3.22.66-4.36 1.73C6.54 6.73 5.61 6 4.5 6 3.12 6 2 7.12 2 8.5S3.12 11 4.5 11c.21 0 .41-.03.61-.08-.05.25-.09.51-.1.78-.18 3.68 2.95 6.68 6.68 6.27 2.55-.28 4.68-2.26 5.19-4.77.15-.71.15-1.4.06-2.06-.09-.6.38-1.13.99-1.13H22V6zM4.5 9c-.28 0-.5-.22-.5-.5s.22-.5.5-.5.5.22.5.5-.22.5-.5.5m6.5 6c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3",
        SportType.WALK to "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2M9.8 8.9 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6z",
        SportType.HIKE to "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2m4 5.28c-1.23-.37-2.22-1.17-2.8-2.18l-1-1.6c-.41-.65-1.11-1-1.84-1-.78 0-1.59.5-1.78 1.44S7 23 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3c1 1.15 2.41 2.01 4 2.34V23H19V9h-1.5zM7.43 13.13l-2.12-.41c-.54-.11-.9-.63-.79-1.17l.76-3.93c.21-1.08 1.26-1.79 2.34-1.58l1.16.23z",
        SportType.WHEELCHAIR to "M4.5 4c0-1.11.89-2 2-2s2 .89 2 2-.89 2-2 2-2-.89-2-2m5.5 6.95V9c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v6h2v7h3.5v-.11c-1.24-1.26-2-2.99-2-4.89 0-2.58 1.41-4.84 3.5-6.05M16.5 17c0 1.65-1.35 3-3 3s-3-1.35-3-3c0-1.11.61-2.06 1.5-2.58v-2.16c-2.02.64-3.5 2.51-3.5 4.74 0 2.76 2.24 5 5 5s5-2.24 5-5zm3.04-3H15V8h-2v8h5.46l2.47 3.71 1.66-1.11z",
        SportType.RIDE to "M15.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2M5 12c-2.8 0-5 2.2-5 5s2.2 5 5 5 5-2.2 5-5-2.2-5-5-5m0 8.5c-1.9 0-3.5-1.6-3.5-3.5s1.6-3.5 3.5-3.5 3.5 1.6 3.5 3.5-1.6 3.5-3.5 3.5m5.8-10 2.4-2.4.8.8c1.3 1.3 3 2.1 5.1 2.1V9c-1.5 0-2.7-.6-3.6-1.5l-1.9-1.9c-.5-.4-1-.6-1.6-.6s-1.1.2-1.4.6L7.8 8.4c-.4.4-.6.9-.6 1.4 0 .6.2 1.1.6 1.4L11 14v5h2v-6.2zM19 12c-2.8 0-5 2.2-5 5s2.2 5 5 5 5-2.2 5-5-2.2-5-5-5m0 8.5c-1.9 0-3.5-1.6-3.5-3.5s1.6-3.5 3.5-3.5 3.5 1.6 3.5 3.5-1.6 3.5-3.5 3.5",
        SportType.UNSPECIFIED to "M11 18h2v-2h-2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2m0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8m0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4"
    )

    /**
     * Rasterizes a [SportType]'s Material glyph at [sizePx] px. The glyph
     * renders in its native Material black fill (same monochrome look as an
     * untinted Material icon in the app UI) on the white chip.
     */
    private fun sportIconBitmap(type: SportType, sizePx: Int, tint: Int = Color.BLACK): Bitmap {
        val key = "${type.name}|$sizePx|$tint"
        synchronized(_sportIconCache) { _sportIconCache[key] }?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        SPORT_ICON_PATHS[type]?.let { d ->
            val path = PathParser.createPathFromPathData(d)
            path.transform(Matrix().apply { setScale(sizePx / 24f, sizePx / 24f) })
            Canvas(bmp).drawPath(
                path,
                Paint().apply { color = tint; isAntiAlias = true }
            )
        }
        synchronized(_sportIconCache) { _sportIconCache[key] = bmp }
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

    // ── Template 1: Map — Strava anatomy: FULL-BLEED map + overlay stats ──

    private fun drawMapTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>, mapSnapshot: Bitmap?, mapBounds: LatLngBounds?) {
        if (mapSnapshot != null) {
            // Full-bleed center-crop: the map covers the ENTIRE card. Compute
            // the crop rect FIRST so the route overlay shares the same
            // coordinate space as the drawn pixels — the track must sit on
            // the map, not on the letterbox. (drawBitmap's src param takes an
            // integer Rect; the rounding error is sub-pixel, invisible.)
            val bmpW = mapSnapshot.width.toFloat()
            val bmpH = mapSnapshot.height.toFloat()
            val cardAspect = CARD_W.toFloat() / CARD_H
            val bmpAspect = bmpW / bmpH
            val srcRect: android.graphics.Rect
            val dstRect: RectF
            if (bmpAspect > cardAspect) {
                // bitmap wider than the card → crop the SIDES
                val visW = (bmpH * cardAspect).roundToInt()
                val left = ((bmpW - visW) / 2f).roundToInt()
                srcRect = android.graphics.Rect(left, 0, left + visW, mapSnapshot.height)
            } else {
                // bitmap taller → crop top/bottom, biased UP (keep the sky
                // out; maps have no sky, so bias keeps the route's center)
                val visH = (bmpW / cardAspect).roundToInt()
                val top = ((bmpH - visH) / 2f).roundToInt()
                srcRect = android.graphics.Rect(0, top, mapSnapshot.width, top + visH)
            }
            dstRect = RectF(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat())
            c.drawBitmap(mapSnapshot, srcRect, dstRect, Paint().apply { isFilterBitmap = true; isAntiAlias = true })

            // Strava scrim: transparent top → near-black bottom, weighted to
            // deepen through the LOWER HALF where the overlay block lives.
            val scrim = LinearGradient(
                0f, 0f, 0f, CARD_H.toFloat(),
                intArrayOf(
                    Color.parseColor("#33000000"),
                    Color.parseColor("#59000000"),
                    Color.parseColor("#C4000000"),
                    Color.parseColor("#F2000000")
                ),
                floatArrayOf(0f, 0.32f, 0.66f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(dstRect, Paint().apply { shader = scrim })

            // Route overlay — the srcRect crop maps 1:1 onto the full card,
            // so drawing the track into the FULL canvas with the snapshot's
            // geographic bounds keeps it perfectly on the drawn tiles.
            if (track.size >= 2 && mapBounds != null) {
                MapSnapshotHelper.drawTrackOnCanvas(
                    canvas = c, trackPoints = track, bounds = mapBounds,
                    canvasLeft = 0f, canvasTop = 0f,
                    canvasRight = CARD_W.toFloat(), canvasBottom = CARD_H.toFloat(),
                    strokeWidth = 14f, color = TRACK_FILL,
                    casingColor = TRACK_CASE
                )
            }
        } else {
            // Fallback: brand gradient + faint grid (no network / snapshot failed)
            fillGradient(c, PRIMARY, DARK)
            drawMapGrid(c, Color.parseColor("#1AFFFFFF"))
            if (track.size >= 2) {
                drawRouteProportional(c, track, 100f, 120f, CARD_W - 100f, CARD_H * 0.55f, 14f, TRACK_FILL)
            }
        }

        // ── Overlay block (Strava anatomy): sport glyph + wordmark row,
        // title, then two rows of label-over-value stats — all drawn on the
        // map's dark scrim, nothing else between them and the viewer. ──
        val type = SportType.fromString(a.sportType)

        // Row 1: bare white sport glyph (left) + NYASAR wordmark (right).
        // Strava's story card shows exactly this pairing.
        val glyphSize = 64f
        val glyph = sportIconBitmap(type, glyphSize.roundToInt(), tint = WHITE)
        c.drawBitmap(glyph, null, RectF(80f, 1150f, 80f + glyphSize, 1150f + glyphSize), Paint().apply { isFilterBitmap = true })
        val wordP = textPaint(WORDMARK_SIZE, interBold(ctx), WORDMARK_COLOR)
        val word = WORDMARK_TEXT
        c.drawText(word, CARD_W - 80f - wordP.measureText(word), 1150f + glyphSize * 0.78f, wordP)

        // Row 2: activity title (Strava-weight bold, ~64px).
        val nameP = textPaint(64f, interBold(ctx), WHITE)
        c.drawText(ellipsize(a.name, CARD_W - 160f, nameP), 80f, 1310f, nameP)

        // Rows 3+4: the 2–3 stat columns, labels tiny, values huge —
        // auto-shrinking only for pathological cases (same helper as before).
        drawStatRow(
            c, ctx,
            columns = statColumns(ctx, a),
            leftX = 80f, rightX = CARD_W - 80f,
            labelBaselineY = 1400f, valueGap = 78f
        )

        // No extra watermark in this template — the NYASAR wordmark IS the
        // branding (drawn in row 1), drawing it twice would clutter the card.
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
            drawRouteProportional(c, track, 200f, CARD_H * 0.79f, CARD_W - 200f, CARD_H * 0.91f, 12f, TRACK_FILL)
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
                    strokeWidth = 15f, color = TRACK_FILL,
                    casingColor = TRACK_CASE
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
            drawRouteProportional(c, track, 120f, CARD_H * 0.12f, CARD_W - 120f, CARD_H * 0.62f, 14f, TRACK_FILL)
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
