package com.nyasar.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import android.graphics.Typeface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** One point along the profile: cumulative distance from the start, and
 *  elevation at that point. */
data class ElevationPoint(
    val distanceMeters: Double,
    val elevationM: Double,
    /** Geographic coordinates for map highlight scrubbing. */
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

/**
 * Interactive elevation profile chart with:
 * - Y-axis labels (elevation in meters)
 * - X-axis labels (distance in km)
 * - Horizontal grid lines
 * - Tap/drag to scrub → tooltip + onPointSelected callback
 * - Filled area under the curve
 *
 * No charting library dependency — pure Canvas + native text drawing.
 */
@Composable
fun ElevationProfile(
    points: List<ElevationPoint>,
    modifier: Modifier = Modifier,
    onPointSelected: ((index: Int, point: ElevationPoint) -> Unit)? = null
) {
    if (points.size < 2) return

    val elevations = points.map { it.elevationM }
    val minE = elevations.min()
    val maxE = elevations.max()
    val rangeE = (maxE - minE).takeIf { it > 0.0 } ?: 1.0
    val totalDistance = points.last().distanceMeters.takeIf { it > 0.0 } ?: 1.0

    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.2f)
    // Theme-aware chart chrome: follows light/dark instead of hardcoded
    // dark-mode grays that vanished against light surfaces.
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBg = MaterialTheme.colorScheme.surfaceVariant
    val tooltipText = MaterialTheme.colorScheme.onSurfaceVariant

    val context = LocalContext.current
    val interTypeface: Typeface = remember {
        ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.DEFAULT
    }

    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    fun nearestIndexForX(xFraction: Float) {
        val targetDistance = xFraction.coerceIn(0f, 1f) * totalDistance
        var closest = 0
        var bestDelta = Double.MAX_VALUE
        points.forEachIndexed { i, p ->
            val delta = kotlin.math.abs(p.distanceMeters - targetDistance)
            if (delta < bestDelta) {
                bestDelta = delta
                closest = i
            }
        }
        selectedIndex = closest
        onPointSelected?.invoke(closest, points[closest])
    }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 8.dp, top = 8.dp, bottom = 36.dp)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        nearestIndexForX(offset.x / size.width)
                    }
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> nearestIndexForX(offset.x / size.width) },
                        onHorizontalDrag = { change, _ ->
                            nearestIndexForX(change.position.x / size.width)
                        }
                    )
                }
        ) {
            val chartWidth = size.width
            val chartHeight = size.height
            val topPadding = 12f
            val bottomPadding = 8f
            val drawableHeight = chartHeight - topPadding - bottomPadding

            // --- Draw Y-axis labels and horizontal grid lines ---
            // Y-axis: fewer labels, skip bottom to avoid overlap with X-axis
            val yLabelCount = 4
            val yLabelPaint = android.graphics.Paint().apply {
                color = labelColor.hashCode()
                textSize = 20f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.RIGHT
                typeface = interTypeface
            }

            for (i in 0..yLabelCount) {
                val fraction = i.toFloat() / yLabelCount
                val elevValue = maxE - fraction * rangeE
                val y = topPadding + fraction * drawableHeight

                // Grid line
                drawLine(
                    color = gridColor.copy(alpha = 0.3f),
                    start = Offset(0f, y),
                    end = Offset(chartWidth, y),
                    strokeWidth = 1f
                )

                // Label text — only draw if not at the very bottom (avoids overlap with X-axis "0.00")
                if (i < yLabelCount) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "${elevValue.roundToInt()}",
                        -4f,
                        y + 6f,
                        yLabelPaint
                    )
                }
            }

            // --- Baseline ---
            val baselineY = topPadding + drawableHeight
            drawLine(
                color = gridColor,
                start = Offset(0f, baselineY),
                end = Offset(chartWidth, baselineY),
                strokeWidth = 2f
            )

            // --- Draw X-axis labels below the baseline ---
            val xLabelCount = 6
            val xLabelPaint = android.graphics.Paint().apply {
                color = labelColor.hashCode()
                textSize = 20f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = interTypeface
            }
            for (i in 0..xLabelCount) {
                val fraction = i.toFloat() / xLabelCount
                val distKm = fraction * totalDistance / 1000.0
                val x = fraction * chartWidth

                drawContext.canvas.nativeCanvas.drawText(
                    "%.2f".format(distKm),
                    x,
                    baselineY + 20f,
                    xLabelPaint
                )
            }

            // --- Draw the elevation line and fill ---
            val path = Path()
            points.forEachIndexed { index, p ->
                val x = (p.distanceMeters / totalDistance).toFloat() * chartWidth
                val normalized = ((p.elevationM - minE) / rangeE).toFloat()
                val y = topPadding + (1f - normalized) * drawableHeight
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Fill area
            val fillPath = Path().apply {
                addPath(path)
                lineTo(chartWidth, topPadding + drawableHeight)
                lineTo(0f, topPadding + drawableHeight)
                close()
            }
            drawPath(fillPath, color = fillColor, style = Fill)

            // Line stroke
            drawPath(path, color = lineColor, style = Stroke(width = 3.5f))

            // --- Selected point indicator ---
            selectedIndex?.let { idx ->
                val p = points[idx]
                val x = (p.distanceMeters / totalDistance).toFloat() * chartWidth
                val normalized = ((p.elevationM - minE) / rangeE).toFloat()
                val y = topPadding + (1f - normalized) * drawableHeight

                // Vertical crosshair line
                drawLine(
                    color = lineColor.copy(alpha = 0.6f),
                    start = Offset(x, topPadding),
                    end = Offset(x, topPadding + drawableHeight),
                    strokeWidth = 1.5f
                )

                // Dot on the line
                drawCircle(color = lineColor, radius = 7f, center = Offset(x, y))
                drawCircle(color = Color.White, radius = 4f, center = Offset(x, y))
            }
        }

        // --- Tooltip overlay ---
        selectedIndex?.let { idx ->
            val p = points[idx]
            val grade = gradeAt(points, idx)
            ElevationTooltip(
                distanceKm = p.distanceMeters / 1000.0,
                elevationM = p.elevationM,
                gradePercent = grade,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }
}

/**
 * Grade (% slope) centered on [index].
 */
private fun gradeAt(points: List<ElevationPoint>, index: Int): Double? {
    val prev = points.getOrNull(index - 1) ?: points.getOrNull(index) ?: return null
    val next = points.getOrNull(index + 1) ?: points.getOrNull(index) ?: return null
    val distance = next.distanceMeters - prev.distanceMeters
    if (distance <= 1.0) return null
    val rise = next.elevationM - prev.elevationM
    return (rise / distance) * 100.0
}

@Composable
private fun ElevationTooltip(
    distanceKm: Double,
    elevationM: Double,
    gradePercent: Double?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(4.dp),
        tonalElevation = 8.dp,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface
    ) {
        Text(
            buildString {
                append("%.2f km".format(distanceKm))
                append("\n")
                append("${stringResource(R.string.elevation_label)}: ${elevationM.roundToInt()} m")
                gradePercent?.let { append("\n%.0f%%".format(it)) }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
