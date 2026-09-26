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
 *     cropped) under a scrim that stays nearly clear through the upper
 *     half (the map is the hero) and deepens only under the overlay text
 *     block; sport glyph +
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
 *   "minimal"     — transparent bg + glyph+wordmark lockup + 3 left stats
 *   "sticker"     — transparent bg + glyph+wordmark row + 6-stat grid
 *                   (Distance | Pace | Max Elev // Time | Elev Gain | Elev
 *                   Loss) — Strava's subscriber 3x3 stats card, free here.
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

    val TEMPLATES = listOf("map", "stats", "dark_card", "route", "grid", "minimal", "sticker")

    fun templateLabel(key: String): String = when (key) {
        "map" -> "Map"
        "stats" -> "Stats"
        "dark_card" -> "Dark"
        "route" -> "Route"
        "grid" -> "Poster"
        "minimal" -> "Minimal"
        "sticker" -> "Sticker"
        else -> key
    }

    /**
     * Map-bearing templates need one snapshot per FRAME, not one for all:
     * "map" is full-bleed 9:16 (the card's own aspect → zero crop → the
     * route overlay is pixel-exact), "dark_card" is a portrait inset card.
     * Sharing one bitmap across both forced a cover-crop somewhere, and
     * the cropped region silently broke the bounds→canvas mapping used by
     * the GPS line (the 2026-09 "route doesn't match the map" report).
     */
    fun generate(
        context: android.content.Context,
        activity: ActivityEntity,
        track: List<TrackPoint>,
        template: String,
        mapSnapshot: Bitmap? = null,
        mapBounds: LatLngBounds? = null,
        insetSnapshot: Bitmap? = mapSnapshot,
        insetBounds: LatLngBounds? = mapBounds
    ): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (template) {
            "map" -> drawMapTemplate(c, context, activity, track, mapSnapshot, mapBounds)
            "stats" -> drawStatsTemplate(c, context, activity, track)
            "dark_card" -> drawDarkCardTemplate(c, context, activity, track, insetSnapshot, insetBounds)
            "route" -> drawRouteTemplate(c, context, activity, track)
            "grid" -> drawGridTemplate(c, context, activity, track)
            "minimal" -> drawMinimalTemplate(c, context, activity)
            "sticker" -> drawStickerTemplate(c, context, activity, track)
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

    private fun formatElevLoss(a: ActivityEntity): String =
        a.elevationLossM?.let { "${it.roundToInt()} m" } ?: "0 m"

    /** Highest point of the track — ActivityEntity doesn't store max
     *  elevation, but the full point list is already in hand here. Falls
     *  back to "—" when the recording carried no elevation data. */
    private fun formatMaxElev(track: List<TrackPoint>): String {
        val max = track.mapNotNull { it.elevationM }.maxOrNull() ?: return "—"
        return "${max.roundToInt()} m"
    }

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

            // Strava scrim: nearly CLEAR through the upper half — the map
            // stays the hero (user feedback 2026-09: the old 35%→77% ramp
            // made the map "almost disappear") — then deepens only under
            // the overlay text block (~60% down) to keep it legible.
            val scrim = LinearGradient(
                0f, 0f, 0f, CARD_H.toFloat(),
                intArrayOf(
                    Color.parseColor("#00000000"),
                    Color.parseColor("#0F000000"),
                    Color.parseColor("#73000000"),
                    Color.parseColor("#B3000000"),
                    Color.parseColor("#E6000000")
                ),
                floatArrayOf(0f, 0.35f, 0.60f, 0.80f, 1f),
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

    // ── Template 2: Stats — transparent + Strava-stacked stats + centered route ──

    private fun drawStatsTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)
        drawTranslucentScrim(c) // Strava bakes a dark wash into its transparent cards

        // Strava anatomy for the transparent card (1:1): NO sport badge and
        // NO corner watermark — three stat blocks stacked HIGH on the card
        // (Distance → primary metric → Time), each a BOLD white label over a
        // big value, then the route centered below, then the brand wordmark
        // centered at the bottom. Transparent background stays fully clear.
        val cx = CARD_W / 2f
        val lbl = textPaint(36f, interBold(ctx), 0xF2FFFFFF.toInt())
        val big = textPaint(96f, interBold(ctx), WHITE)

        fun block(label: String, value: String, labelY: Float, valueY: Float) {
            c.drawText(label, cx - lbl.measureText(label) / 2, labelY, lbl)
            c.drawText(value, cx - big.measureText(value) / 2, valueY, big)
        }

        val dist = "%.2f km".format(a.distanceMeters / 1000.0)
        val dur = formatDuration(a.movingTimeMs)

        block(ctx.getString(R.string.share_stat_distance), dist, CARD_H * 0.235f, CARD_H * 0.295f)
        if (sportMetric(a) == ShareMetric.PACE) {
            block(ctx.getString(R.string.share_stat_pace), formatPace(a), CARD_H * 0.375f, CARD_H * 0.435f)
        } else {
            block(ctx.getString(R.string.share_stat_elev_gain), "\u2191 ${formatElevGain(a)}", CARD_H * 0.375f, CARD_H * 0.435f)
        }
        block(ctx.getString(R.string.share_stat_time), dur, CARD_H * 0.515f, CARD_H * 0.575f)

        // Route centered below the stats — green fill over dark casing (the
        // same two-layer trick as the map template; no orange in the palette).
        if (track.size >= 2) {
            drawRouteProportional(c, track, 280f, CARD_H * 0.62f, CARD_W - 280f, CARD_H * 0.78f, 12f, TRACK_FILL, TRACK_CASE)
        }

        // Centered brand wordmark, wide letter-spaced (Strava's logo slot).
        val wordP = textPaint(48f, interBold(ctx), WORDMARK_COLOR).apply { letterSpacing = 0.18f }
        c.drawText(WORDMARK_TEXT, cx - wordP.measureText(WORDMARK_TEXT) / 2, CARD_H * 0.835f, wordP)
    }

    // ── Template 3: Dark Card — Strava anatomy: black grainy bg + green
    //    diagonal ribbon + inset map card with stats INSIDE its base ──

    private fun drawDarkCardTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>, mapSnapshot: Bitmap?, mapBounds: LatLngBounds?) {
        // Near-black grainy backdrop (Strava's story uses noise grain; two
        // alpha layers of fine diagonal hatching read as grain at card size).
        c.drawColor(Color.parseColor("#121412"))
        val grainPaint = Paint().apply { color = Color.parseColor("#14FFFFFF"); strokeWidth = 2f }
        var gx = -CARD_H.toFloat()
        while (gx < CARD_W + CARD_H) {
            c.drawLine(gx, 0f, gx + CARD_H, CARD_H.toFloat(), grainPaint)
            gx += 7f
        }
        val grainPaint2 = Paint().apply { color = Color.parseColor("#0A000000"); strokeWidth = 2f }
        gx = -CARD_H.toFloat()
        while (gx < CARD_W + CARD_H) {
            c.drawLine(gx + 3f, 0f, gx + 3f + CARD_H, CARD_H.toFloat(), grainPaint2)
            gx += 11f
        }

        // Strava's signature diagonal ribbon — NYASAR GREEN, not orange
        // (user request). Two broad 45° bands sweeping behind the map card,
        // one wider upper-left → lower-right, one thinner lower-left → up.
        val ribbon = Paint().apply { color = Color.parseColor("#5A7562"); isAntiAlias = true }
        c.save()
        c.rotate(-45f, CARD_W / 2f, CARD_H / 2f)
        c.drawRect(-CARD_H.toFloat(), CARD_H * 0.10f, (CARD_W + CARD_H).toFloat(), CARD_H * 0.28f, ribbon)
        c.drawRect(-CARD_H.toFloat(), CARD_H * 0.86f, (CARD_W + CARD_H).toFloat(), CARD_H * 0.95f, ribbon)
        c.restore()

        // Inset map card — Strava's story proportions. Its aspect FOLLOWS
        // the snapshot bitmap (portrait 1080x1344) instead of the old
        // landscape rect: cover-cropping a portrait snapshot into a
        // landscape card shaved ~12% off the map's top AND bottom, and
        // computeBounds' capped padding puts the route's ends inside
        // exactly that shaved band — the route ran past both card edges
        // ("rute bablas", 2026-09 report). Card = bitmap aspect → drawn
        // 1:1 with no crop, so the full route always stays visible.
        val cardRect = if (mapSnapshot != null) {
            val w = CARD_W - 176f
            RectF(88f, 190f, CARD_W - 88f,
                190f + w * mapSnapshot.height.toFloat() / mapSnapshot.width.toFloat())
        } else {
            RectF(88f, 190f, CARD_W - 88f, CARD_H * 0.55f)
        }
        val cardRadius = 36f

        if (mapSnapshot != null) {
            // Center-crop the bitmap into the card area; MUST be computed
            // first so the route overlay shares the bitmap's coordinate space.
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

            c.save()
            val clipPath = Path().apply { addRoundRect(cardRect, cardRadius, cardRadius, Path.Direction.CW) }
            c.clipPath(clipPath)

            c.drawBitmap(mapSnapshot, null, destRect, null)

            // Dark scrim deepening toward the card's base — the stats block
            // sits on this (Strava draws its stats INSIDE the map card's
            // bottom edge, not below the card).
            val mapGradient = LinearGradient(
                0f, cardRect.top, 0f, cardRect.bottom,
                intArrayOf(
                    Color.parseColor("#00000000"),
                    Color.parseColor("#59000000"),
                    Color.parseColor("#E0000000")
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(destRect, Paint().apply { shader = mapGradient })

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

            // Hairline border (not clipped — full stroke visible)
            val borderPaint = Paint().apply {
                color = Color.parseColor("#26FFFFFF")
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
            c.drawRoundRect(cardRect, cardRadius, cardRadius, borderPaint)
        } else {
            // Fallback: brand gradient card + route when no snapshot.
            val cardBg = Paint().apply { style = Paint.Style.FILL }
            val cardGradient = LinearGradient(
                cardRect.left, cardRect.top, cardRect.left, cardRect.bottom,
                intArrayOf(Color.parseColor("#E8E8E0"), Color.parseColor("#D0D0C8")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            cardBg.shader = cardGradient
            c.drawRoundRect(cardRect, cardRadius, cardRadius, cardBg)
            if (track.size >= 2) {
                drawRouteProportional(c, track, cardRect.left + 50f, cardRect.top + 50f,
                    cardRect.right - 50f, cardRect.bottom - 50f, 15f, TRACK_COLOR_LIGHT_BG)
            }
        }

        // ── Stats INSIDE the map card's base (Strava draws them there) ──
        // Labels tiny white, values big bold — same auto-fit row helper.
        val statsBaseY = cardRect.bottom - 170f
        drawStatRow(
            c, ctx,
            columns = statColumns(ctx, a),
            leftX = cardRect.left + 40f, rightX = cardRect.right - 40f,
            labelBaselineY = statsBaseY, valueGap = 74f
        )

        // ── Below the card: centered title (no badge — Strava anatomy) ──
        // The taller portrait card pushes these down with it; maxOf keeps
        // the old layout when the shorter fallback (no-snapshot) card is
        // used.
        val nameP = textPaint(64f, interBold(ctx), WHITE)
        val name = ellipsize(a.name, CARD_W - 160f, nameP)
        c.drawText(name, CARD_W / 2f - nameP.measureText(name) / 2,
            maxOf(cardRect.bottom + 110f, CARD_H * 0.635f), nameP)

        // ── Brand lockup: "Lihat aktivitasku di" + NYASAR wordmark ──
        val tagP = textPaint(40f, interBold(ctx), 0xF2FFFFFF.toInt())
        val tag = ctx.getString(R.string.share_check_out)
        c.drawText(tag, CARD_W / 2f - tagP.measureText(tag) / 2, CARD_H * 0.815f, tagP)
        val wordP = textPaint(56f, interBold(ctx), WORDMARK_COLOR).apply { letterSpacing = 0.18f }
        c.drawText(WORDMARK_TEXT, CARD_W / 2f - wordP.measureText(WORDMARK_TEXT) / 2, CARD_H * 0.87f, wordP)
    }

    // ── Template 4: Route — transparent + Strava anatomy: big centered
    //    route, wordmark, THREE centered stat columns, sport glyph ──

    private fun drawRouteTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)
        drawTranslucentScrim(c) // Strava's baked-in dark wash (see drawTranslucentScrim)

        // Strava's transparent route card (1:1): the route dominates the
        // upper half; then the brand wordmark; then Distance | Pace | Time
        // as a CENTERED group of label-over-value columns; then the sport
        // glyph centered underneath. Everything axis-centered like Strava.
        if (track.size >= 2) {
            drawRouteProportional(c, track, 150f, CARD_H * 0.13f, CARD_W - 150f, CARD_H * 0.52f, 14f, TRACK_FILL, TRACK_CASE)
        }

        // Brand wordmark, letter-spaced, centered.
        val wordP = textPaint(52f, interBold(ctx), WORDMARK_COLOR).apply { letterSpacing = 0.18f }
        val cx = CARD_W / 2f
        c.drawText(WORDMARK_TEXT, cx - wordP.measureText(WORDMARK_TEXT) / 2, CARD_H * 0.585f, wordP)

        // Three stat columns in Strava order (Distance | Pace | Time),
        // centered as a GROUP: measure all columns first, then lay out
        // from the group's centered left edge. Labels bold white over
        // big values (Strava's label treatment).
        val lblP = textPaint(STAT_LABEL_SIZE, interBold(ctx), 0xF2FFFFFF.toInt())
        val valP = textPaint(64f, interBold(ctx), WHITE)
        val primary: Pair<String, String> = if (sportMetric(a) == ShareMetric.PACE) {
            ctx.getString(R.string.share_stat_pace) to formatPace(a)
        } else {
            ctx.getString(R.string.share_stat_elev_gain) to "\u2191 ${formatElevGain(a)}"
        }
        val columns = listOf(
            ctx.getString(R.string.share_stat_distance) to "%.2f km".format(a.distanceMeters / 1000.0),
            primary,
            ctx.getString(R.string.share_stat_time) to formatDuration(a.movingTimeMs)
        )
        val gap = 72f
        val widths = columns.map { maxOf(lblP.measureText(it.first), valP.measureText(it.second)) }
        val groupW = widths.sum() + gap * (columns.size - 1)
        var x = cx - groupW / 2f
        val labelY = CARD_H * 0.645f
        columns.forEachIndexed { i, (label, value) ->
            val colCx = x + widths[i] / 2f
            c.drawText(label, colCx - lblP.measureText(label) / 2, labelY, lblP)
            c.drawText(value, colCx - valP.measureText(value) / 2, labelY + 78f, valP)
            x += widths[i] + gap
        }

        // Sport glyph centered below the stats (bare white, Strava-style).
        val glyphSize = 76f
        val glyph = sportIconBitmap(SportType.fromString(a.sportType), glyphSize.roundToInt(), tint = WHITE)
        c.drawBitmap(
            glyph, null,
            RectF(cx - glyphSize / 2f, CARD_H * 0.70f, cx + glyphSize / 2f, CARD_H * 0.70f + glyphSize),
            Paint().apply { isFilterBitmap = true; isAntiAlias = true }
        )
    }

    // ── Template 5: Poster — Strava anatomy: ONE big centered route +
    //    wordmark, nothing else ──

    private fun drawGridTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)
        drawTranslucentScrim(c) // Strava's baked-in dark wash (see drawTranslucentScrim)

        // Strava's clean route poster (1:1): a single large route fills the
        // middle of the card and the brand wordmark sits centered beneath
        // it. No stats, no badge, no watermark — Strava reserves this card
        // for the SHAPE of the activity itself. (The template key stays
        // "grid" for backward compatibility; only its drawing changed.)
        if (track.size >= 2) {
            drawRouteProportional(c, track, 130f, CARD_H * 0.22f, CARD_W - 130f, CARD_H * 0.62f, 16f, TRACK_FILL, TRACK_CASE)
        }

        // Brand wordmark, letter-spaced, centered below the route.
        val wordP = textPaint(52f, interBold(ctx), WORDMARK_COLOR).apply { letterSpacing = 0.18f }
        val cx = CARD_W / 2f
        c.drawText(WORDMARK_TEXT, cx - wordP.measureText(WORDMARK_TEXT) / 2, CARD_H * 0.685f, wordP)
    }

    // ── Template 6: Minimal — Strava anatomy: transparent bg, glyph +
    //    wordmark row, three left-aligned stat columns, nothing else ──

    private fun drawMinimalTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity) {
        c.drawColor(Color.TRANSPARENT)
        drawTranslucentScrim(c) // Strava's baked-in dark wash (see drawTranslucentScrim)

        // Strava's minimal stats card (1:1): TRANSPARENT background; a
        // left-aligned row of the sport glyph beside the brand wordmark;
        // below it the three stat columns (Distance | Pace | Time) also
        // left-aligned. No title, no route, no watermark — the emptiness
        // is the design. (Was a solid-green centered card; Strava's sixth
        // card is the quiet one.)
        val leftX = 110f

        // Row 1: sport glyph + NYASAR wordmark side by side (Strava's
        // shoe + STRAVA lockup), vertically centered against each other.
        val glyphSize = 84f
        val glyphY = CARD_H * 0.405f
        val glyph = sportIconBitmap(SportType.fromString(a.sportType), glyphSize.roundToInt(), tint = WHITE)
        c.drawBitmap(
            glyph, null,
            RectF(leftX, glyphY, leftX + glyphSize, glyphY + glyphSize),
            Paint().apply { isFilterBitmap = true; isAntiAlias = true }
        )
        val wordP = textPaint(54f, interBold(ctx), WORDMARK_COLOR).apply { letterSpacing = 0.14f }
        c.drawText(WORDMARK_TEXT, leftX + glyphSize + 30f, glyphY + glyphSize * 0.74f, wordP)

        // Row 2: three stat columns left-aligned from the same edge —
        // the shared stat-row helper gives Strava's bold-white labels over
        // big values and auto-shrinks for pathological lengths.
        val primary: Pair<String, String> = if (sportMetric(a) == ShareMetric.PACE) {
            ctx.getString(R.string.share_stat_pace) to formatPace(a)
        } else {
            ctx.getString(R.string.share_stat_elev_gain) to "\u2191 ${formatElevGain(a)}"
        }
        drawStatRow(
            c, ctx,
            columns = listOf(
                ctx.getString(R.string.share_stat_distance) to "%.2f km".format(a.distanceMeters / 1000.0),
                primary,
                ctx.getString(R.string.share_stat_time) to formatDuration(a.movingTimeMs)
            ),
            leftX = leftX, rightX = CARD_W - 90f,
            labelBaselineY = glyphY + glyphSize + 140f, valueGap = 74f
        )
    }

    // ── Template 7: Sticker — Strava anatomy: transparent bg (dark scrim
    //    baked in), glyph + wordmark row, TWO ROWS of three stat columns
    //    on a SHARED 3-column grid so columns align between rows ──

    private fun drawStickerTemplate(c: Canvas, ctx: android.content.Context, a: ActivityEntity, track: List<TrackPoint>) {
        c.drawColor(Color.TRANSPARENT)
        drawTranslucentScrim(c) // Strava's baked-in dark wash (see drawTranslucentScrim)

        // Strava's subscriber stats card (1:1 anatomy, free here): same
        // glyph + NYASAR lockup row as Minimal, then TWO rows of three
        // left-aligned columns — Row 1: Distance | Pace | Max Elev, Row 2:
        // Time | Elev Gain | Elev Loss. Pace swaps for Elev Gain when the
        // sport's primary metric isn't pace, mirroring statColumns().
        val leftX = 90f

        // Row 1: sport glyph + NYASAR wordmark (same lockup as Minimal).
        val glyphSize = 84f
        val glyphY = CARD_H * 0.38f
        val glyph = sportIconBitmap(SportType.fromString(a.sportType), glyphSize.roundToInt(), tint = WHITE)
        c.drawBitmap(
            glyph, null,
            RectF(leftX, glyphY, leftX + glyphSize, glyphY + glyphSize),
            Paint().apply { isFilterBitmap = true; isAntiAlias = true }
        )
        val wordP = textPaint(54f, interBold(ctx), WORDMARK_COLOR).apply { letterSpacing = 0.14f }
        c.drawText(WORDMARK_TEXT, leftX + glyphSize + 30f, glyphY + glyphSize * 0.74f, wordP)

        // Six stats — label/value pairs, same typography as the other cards
        // (no arrows — Strava's subscriber card shows bare numbers).
        val primary: Pair<String, String> = if (sportMetric(a) == ShareMetric.PACE) {
            ctx.getString(R.string.share_stat_pace) to formatPace(a)
        } else {
            ctx.getString(R.string.share_stat_elev_gain) to formatElevGain(a)
        }
        val row1 = listOf(
            ctx.getString(R.string.share_stat_distance) to "%.2f km".format(a.distanceMeters / 1000.0),
            primary,
            ctx.getString(R.string.share_stat_max_elev) to formatMaxElev(track)
        )
        val row2 = listOf(
            ctx.getString(R.string.share_stat_time) to formatDuration(a.movingTimeMs),
            ctx.getString(R.string.share_stat_elev_gain) to formatElevGain(a),
            ctx.getString(R.string.share_stat_elev_loss) to formatElevLoss(a)
        )

        drawAlignedStatGrid(c, ctx, rows = listOf(row1, row2), leftX = leftX,
            rightX = CARD_W - 90f, firstBaselineY = glyphY + glyphSize + 120f,
            rowPitch = 190f, startValueSize = 58f)
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
        val lblP = textPaint(STAT_LABEL_SIZE, interBold(ctx), 0xF2FFFFFF.toInt())
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

    /**
     * Draws MULTIPLE stat rows on a SHARED 3-column grid: one auto-fit pass
     * sizes the value type and derives each column's x from the WIDEST cell
     * of that column across ALL rows — so row 2's Time sits exactly under
     * row 1's Distance, etc. Strava's subscriber card lays its six stats on
     * a true grid, not as two independent left-flowing rows.
     */
    private fun drawAlignedStatGrid(
        c: Canvas, ctx: android.content.Context,
        rows: List<List<Pair<String, String>>>,
        leftX: Float, rightX: Float, firstBaselineY: Float,
        rowPitch: Float, startValueSize: Float
    ) {
        val gap = 48f
        val lblP = textPaint(STAT_LABEL_SIZE, interBold(ctx), 0xF2FFFFFF.toInt())
        var valueSize = startValueSize
        var valP = textPaint(valueSize, interBold(ctx), WHITE)
        fun colWidths(): FloatArray {
            val n = rows.firstOrNull()?.size ?: return FloatArray(0)
            val w = FloatArray(n)
            rows.forEach { row -> row.forEachIndexed { i, cell ->
                w[i] = maxOf(w[i], lblP.measureText(cell.first), valP.measureText(cell.second))
            } }
            return w
        }
        var widths = colWidths()
        val cols = widths.size.coerceAtLeast(1)
        while (widths.sum() + gap * (cols - 1) > rightX - leftX && valueSize > 44f) {
            valueSize -= 4f
            valP = textPaint(valueSize, interBold(ctx), WHITE)
            widths = colWidths()
        }
        val starts = FloatArray(cols)
        var x = leftX
        for (i in 0 until cols) {
            starts[i] = x
            x += widths[i] + gap
        }
        rows.forEachIndexed { r, row ->
            val labelBaselineY = firstBaselineY + r * rowPitch
            row.forEachIndexed { i, (label, value) ->
                var cellX = starts[i]
                if (i == cols - 1) {
                    cellX = minOf(cellX, rightX - maxOf(lblP.measureText(label), valP.measureText(value)))
                }
                c.drawText(label, cellX, labelBaselineY, lblP)
                c.drawText(value, cellX, labelBaselineY + 74f, valP)
            }
        }
    }

    /**
     * Bakes Strava's "darkened transparency" into the PNG: a semi-opaque
     * black wash (a touch heavier at the top and bottom edges) so the card
     * stays legible on bright story backgrounds — white, photos, anything.
     * The bitmap keeps partial alpha: what's baked is the DARKENING, not an
     * opaque backdrop — exactly how Strava's subscriber cards behave.
     */
    private fun drawTranslucentScrim(c: Canvas) {
        val scrim = LinearGradient(
            0f, 0f, 0f, CARD_H.toFloat(),
            intArrayOf(
                Color.parseColor("#8C000000"),
                Color.parseColor("#6B000000"),
                Color.parseColor("#6B000000"),
                Color.parseColor("#8C000000")
            ),
            floatArrayOf(0f, 0.30f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply { shader = scrim })
    }

    /** Small "Nyasar" wordmark, right-aligned at (xRight, yBaseline).
     *  No longer called by any template — every card now carries the bigger
     *  Strava-style branding (corner mark or centered lockup) — kept as a
     *  helper for future templates. */
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
        strokeW: Float, color: Int,
        casingColor: Int? = null
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
        // Casing first (wider, dark) so the fill holds contrast on any
        // background — same two-layer trick as the map template.
        if (casingColor != null) {
            c.drawPath(path, Paint().apply {
                this.color = casingColor; style = Paint.Style.STROKE
                strokeWidth = strokeW * 1.7f; strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND; isAntiAlias = true
            })
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
