package com.nyasar.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.gpx.model.TrackPoint
import kotlin.math.roundToInt

/**
 * Generates share card images for activities.
 * Creates a visually appealing card with:
 * - Activity stats
 * - Route visualization
 * - Background options (plain, gradient, or with route overlay)
 *
 * Design: Original Nyasar design, not a copy of Strava.
 * Uses #5A7562 as primary color with outdoor/nature theme.
 */
object ShareCardGenerator {

    // Card dimensions (1080x1920 for story/social media)
    private const val CARD_WIDTH = 1080
    private const val CARD_HEIGHT = 1920

    // Colors
    private val PRIMARY_GREEN = Color.parseColor("#5A7562")
    private val DARK_GREEN = Color.parseColor("#3A5542")
    private val LIGHT_GREEN = Color.parseColor("#7A9582")
    private val ORANGE = Color.parseColor("#FF6B00")
    private val WHITE = Color.WHITE
    private val BLACK = Color.BLACK
    private val GRAY = Color.parseColor("#888888")

    /**
     * Generate a share card bitmap for the given activity.
     *
     * @param context Android context
     * @param activity Activity data
     * @param trackPoints Track points for route visualization
     * @param backgroundType Background type: "plain", "gradient", or "route"
     * @return Generated bitmap
     */
    fun generateShareCard(
        context: Context,
        activity: ActivityEntity,
        trackPoints: List<TrackPoint>,
        backgroundType: String = "gradient"
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw background
        when (backgroundType) {
            "gradient" -> drawGradientBackground(canvas)
            "route" -> drawRouteBackground(canvas, trackPoints)
            else -> drawPlainBackground(canvas)
        }

        // Draw route visualization
        if (trackPoints.size >= 2) {
            drawRoute(canvas, trackPoints)
        }

        // Draw stats overlay
        drawStatsOverlay(canvas, activity)

        // Draw app branding
        drawBranding(canvas)

        return bitmap
    }

    private fun drawPlainBackground(canvas: Canvas) {
        val paint = Paint().apply {
            color = PRIMARY_GREEN
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(), paint)
    }

    private fun drawGradientBackground(canvas: Canvas) {
        val gradient = android.graphics.LinearGradient(
            0f, 0f, 0f, CARD_HEIGHT.toFloat(),
            intArrayOf(PRIMARY_GREEN, DARK_GREEN),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        val paint = Paint().apply {
            shader = gradient
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(), paint)
    }

    private fun drawRouteBackground(canvas: Canvas, trackPoints: List<TrackPoint>) {
        // First draw gradient background
        drawGradientBackground(canvas)

        // Then draw a faded route as background decoration
        if (trackPoints.size >= 2) {
            val paint = Paint().apply {
                color = Color.parseColor("#22FFFFFF")
                style = Paint.Style.STROKE
                strokeWidth = 20f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }

            val path = Path()
            val bounds = calculateBounds(trackPoints)
            val padding = 100f

            trackPoints.forEachIndexed { index, point ->
                val x = mapX(point.lon, bounds, padding)
                val y = mapY(point.lat, bounds, padding)

                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(path, paint)
        }
    }

    private fun drawRoute(canvas: Canvas, trackPoints: List<TrackPoint>) {
        if (trackPoints.size < 2) return

        val paint = Paint().apply {
            color = ORANGE
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        val path = Path()
        val bounds = calculateBounds(trackPoints)
        val padding = 100f

        // Draw route in the middle section of the card
        val routeTop = CARD_HEIGHT * 0.4f
        val routeBottom = CARD_HEIGHT * 0.7f
        val routeHeight = routeBottom - routeTop

        trackPoints.forEachIndexed { index, point ->
            val normalizedX = ((point.lon - bounds.minLon) / (bounds.maxLon - bounds.minLon)).toFloat()
            val normalizedY = ((point.lat - bounds.minLat) / (bounds.maxLat - bounds.minLat)).toFloat()

            val x = padding + normalizedX * (CARD_WIDTH - 2 * padding)
            val y = routeTop + (1 - normalizedY) * routeHeight

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, paint)

        // Draw start and end markers
        drawStartEndMarkers(canvas, trackPoints, bounds, routeTop, routeHeight, padding)
    }

    private fun drawStartEndMarkers(
        canvas: Canvas,
        trackPoints: List<TrackPoint>,
        bounds: TrackBounds,
        routeTop: Float,
        routeHeight: Float,
        padding: Float
    ) {
        val startPaint = Paint().apply {
            color = Color.parseColor("#00C853")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val endPaint = Paint().apply {
            color = Color.parseColor("#D64545")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val start = trackPoints.first()
        val end = trackPoints.last()

        val startX = padding + ((start.lon - bounds.minLon) / (bounds.maxLon - bounds.minLon)).toFloat() * (CARD_WIDTH - 2 * padding)
        val startY = routeTop + (1 - (start.lat - bounds.minLat) / (bounds.maxLat - bounds.minLat)).toFloat() * routeHeight

        val endX = padding + ((end.lon - bounds.minLon) / (bounds.maxLon - bounds.minLon)).toFloat() * (CARD_WIDTH - 2 * padding)
        val endY = routeTop + (1 - (end.lat - bounds.minLat) / (bounds.maxLat - bounds.minLat)).toFloat() * routeHeight

        canvas.drawCircle(startX, startY, 12f, startPaint)
        canvas.drawCircle(endX, endY, 12f, endPaint)
    }

    private fun drawStatsOverlay(canvas: Canvas, activity: ActivityEntity) {
        // Stats background (semi-transparent)
        val bgPaint = Paint().apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }
        val bgRect = RectF(40f, CARD_HEIGHT * 0.75f, CARD_WIDTH - 40f, CARD_HEIGHT - 200f)
        canvas.drawRoundRect(bgRect, 24f, 24f, bgPaint)

        // Activity name
        val namePaint = Paint().apply {
            color = WHITE
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        canvas.drawText(activity.name, 80f, CARD_HEIGHT * 0.82f, namePaint)

        // Stats
        val statsPaint = Paint().apply {
            color = WHITE
            textSize = 48f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val statsY = CARD_HEIGHT * 0.88f
        val distance = "%.2f km".format(activity.distanceMeters / 1000.0)
        val duration = formatDuration(activity.elapsedTimeMs)
        val elevation = activity.elevationGainM?.let { "↑ ${it.roundToInt()} m" } ?: ""

        canvas.drawText(distance, 80f, statsY, statsPaint)
        canvas.drawText(duration, 400f, statsY, statsPaint)
        if (elevation.isNotEmpty()) {
            canvas.drawText(elevation, 700f, statsY, statsPaint)
        }

        // Date
        val datePaint = Paint().apply {
            color = GRAY
            textSize = 36f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(activity.startedAtEpochMs))
        canvas.drawText(date, 80f, CARD_HEIGHT * 0.94f, datePaint)
    }

    private fun drawBranding(canvas: Canvas) {
        val paint = Paint().apply {
            color = Color.parseColor("#88FFFFFF")
            textSize = 32f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        canvas.drawText("Nyasar", 80f, CARD_HEIGHT - 100f, paint)
    }

    private fun calculateBounds(trackPoints: List<TrackPoint>): TrackBounds {
        var minLat = Double.MAX_VALUE
        var maxLat = Double.MIN_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = Double.MIN_VALUE

        trackPoints.forEach { point ->
            minLat = minOf(minLat, point.lat)
            maxLat = maxOf(maxLat, point.lat)
            minLon = minOf(minLon, point.lon)
            maxLon = maxOf(maxLon, point.lon)
        }

        return TrackBounds(minLat, maxLat, minLon, maxLon)
    }

    private fun mapX(lon: Double, bounds: TrackBounds, padding: Float): Float {
        val normalized = (lon - bounds.minLon) / (bounds.maxLon - bounds.minLon)
        return padding + normalized.toFloat() * (CARD_WIDTH - 2 * padding)
    }

    private fun mapY(lat: Double, bounds: TrackBounds, padding: Float): Float {
        val normalized = (lat - bounds.minLat) / (bounds.maxLat - bounds.minLat)
        return padding + (1 - normalized).toFloat() * (CARD_HEIGHT - 2 * padding)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }

    private data class TrackBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )
}