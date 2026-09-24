package com.nyasar.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nyasar.app.data.db.ActivityPointEntity
import com.nyasar.app.navigation.GeoMath
import com.nyasar.app.navigation.LatLng
import com.nyasar.app.recording.RecordingUiState
import kotlin.math.roundToInt
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

/** Pre-computed data for one km split. */
data class KmSplit(
    val km: Int,
    /** Elapsed time in milliseconds for this km, including pauses. */
    val timeMs: Long,
    /** Moving time in milliseconds; pace is derived from this, not elapsed. */
    val movingTimeMs: Long,
    /** Distance in meters covered by this row. */
    val distanceMeters: Double,
    /** Net elevation change for this km (positive = gain, negative = loss). */
    val elevDeltaM: Double
)

/**
 * Compute per-km splits from a list of activity points.
 * Returns empty list if total distance < 1 km.
 */
fun computeSplits(points: List<ActivityPointEntity>): List<KmSplit> {
    if (points.size < 2) return emptyList()

    var cumulativeM = 0.0
    var lastPoint = points.first()
    var splitStartMs = lastPoint.timestampMs
    var splitStartMovingMs = 0L
    var splitStartElev = lastPoint.elevationM ?: 0.0
    var splitStartDistanceM = 0.0
    var nextBoundaryM = 1000.0
    val splits = mutableListOf<KmSplit>()

    fun addSplit(km: Int, elapsedMs: Long, movingMs: Long, distanceM: Double, elevDeltaM: Double) {
        splits += KmSplit(km, elapsedMs, movingMs, distanceM, elevDeltaM)
    }

    for (nextPoint in points.drop(1)) {
        val segmentDistance = GeoMath.distanceMeters(
            LatLng(lastPoint.lat, lastPoint.lon),
            LatLng(nextPoint.lat, nextPoint.lon)
        )
        val segmentElapsed = (nextPoint.timestampMs - lastPoint.timestampMs).coerceAtLeast(0L)
        val segmentStartMs = lastPoint.timestampMs
        val moving = if (nextPoint.speedMps != null) {
            nextPoint.speedMps > 0.3f
        } else {
            segmentElapsed > 0L && segmentDistance / (segmentElapsed / 1000.0) > 0.3
        }
        var consumedMovingMs = 0L
        var segmentStartElev = lastPoint.elevationM ?: 0.0

        while (cumulativeM + segmentDistance >= nextBoundaryM && segmentDistance > 0.0) {
            val fraction = (nextBoundaryM - cumulativeM) / segmentDistance
            val boundaryMs = segmentStartMs + (segmentElapsed * fraction).toLong()
            val boundaryElev = segmentStartElev +
                ((nextPoint.elevationM ?: segmentStartElev) - segmentStartElev) * fraction
            val boundaryMovingMs = if (moving) (segmentElapsed * fraction).toLong() else 0L
            consumedMovingMs += boundaryMovingMs
            addSplit(
                km = splits.size + 1,
                elapsedMs = boundaryMs - splitStartMs,
                movingMs = splitStartMovingMs + boundaryMovingMs,
                distanceM = nextBoundaryM - splitStartDistanceM,
                elevDeltaM = boundaryElev - splitStartElev
            )
            splitStartMs = boundaryMs
            splitStartMovingMs = 0L
            splitStartElev = boundaryElev
            splitStartDistanceM = nextBoundaryM
            nextBoundaryM += 1000.0
            segmentStartElev = boundaryElev
        }

        cumulativeM += segmentDistance
        if (moving) {
            splitStartMovingMs += segmentElapsed - consumedMovingMs
        }
        lastPoint = nextPoint
    }

    val partialDistance = cumulativeM - splitStartDistanceM
    if (partialDistance > 100.0) {
        addSplit(
            km = splits.size + 1,
            elapsedMs = lastPoint.timestampMs - splitStartMs,
            movingMs = splitStartMovingMs,
            distanceM = partialDistance,
            elevDeltaM = (lastPoint.elevationM ?: splitStartElev) - splitStartElev
        )
    }

    return splits
}

/** Format moving milliseconds as m:ss pace per km. */
fun formatSplitPace(movingTimeMs: Long, distanceMeters: Double = 1000.0): String {
    if (movingTimeMs <= 0 || distanceMeters <= 0.0) return "-"
    val paceSecondsPerKm = movingTimeMs / 1000.0 * (1000.0 / distanceMeters)
    val minutes = (paceSecondsPerKm / 60).toInt()
    val seconds = (paceSecondsPerKm % 60).toInt()
    return "$minutes:%02d".format(seconds)
}

/**
 * Per-km splits table showing Km, Pace, visual bar, and Elevation delta.
 * Skipped entirely if total distance < 1 km.
 */
@Composable
fun SplitsTable(
    points: List<ActivityPointEntity>,
    modifier: Modifier = Modifier
) {
    val splits = remember(points) { computeSplits(points) }
    if (splits.isEmpty()) return

    val maxTimeMs = splits.maxOf { it.movingTimeMs.coerceAtLeast(1L) }

    Column(modifier.padding(horizontal = 16.dp)) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.km), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.pace), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.elev), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(4.dp))

        splits.forEach { split ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Km number
                Text(
                    "${split.km}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(28.dp)
                )
                // Pace text
                Text(
                    formatSplitPace(split.movingTimeMs, split.distanceMeters),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(48.dp)
                )
                // Visual bar (proportional to time, inversely = faster = shorter bar)
                val barColor = MaterialTheme.colorScheme.primary
                val barFraction = (1.0 - (split.movingTimeMs.toDouble() / maxTimeMs)).coerceIn(0.0, 1.0).toFloat()
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(16.dp)
                        .padding(horizontal = 4.dp)
                ) {
                    val barWidth = (size.width * barFraction).coerceAtLeast(4.dp.toPx())
                    drawRect(
                        color = barColor,
                        topLeft = Offset.Zero,
                        size = Size(barWidth, size.height)
                    )
                }
                // Elev delta
                val elevText = if (split.elevDeltaM >= 0) "+${split.elevDeltaM.roundToInt()}" else "${split.elevDeltaM.roundToInt()}"
                Text(
                    elevText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (split.elevDeltaM > 0) Color(0xFF4CAF50) else if (split.elevDeltaM < 0) Color(0xFFF44336) else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(36.dp)
                )
            }
        }
    }
}
