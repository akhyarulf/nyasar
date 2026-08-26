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
    /** Time in milliseconds for this km. */
    val timeMs: Long,
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
    var lastPoint: ActivityPointEntity = points.first()
    var kmStartMs = points.first().timestampMs
    var kmStartElev = points.first().elevationM ?: 0.0
    var currentKmTarget = 1000.0 // next km boundary in meters
    val splits = mutableListOf<KmSplit>()

    for (i in 1 until points.size) {
        val p = points[i]
        val dist = GeoMath.distanceMeters(
            LatLng(lastPoint.lat, lastPoint.lon),
            LatLng(p.lat, p.lon)
        )
        cumulativeM += dist

        // Check if we crossed a km boundary
        if (cumulativeM >= currentKmTarget) {
            val timeDelta = p.timestampMs - kmStartMs
            val elevDelta = (p.elevationM ?: kmStartElev) - kmStartElev
            splits.add(KmSplit(
                km = splits.size + 1,
                timeMs = timeDelta,
                elevDeltaM = elevDelta
            ))
            kmStartMs = p.timestampMs
            kmStartElev = p.elevationM ?: 0.0
            currentKmTarget += 1000.0
        }

        lastPoint = p
    }

    // Partial last km (only if > 100m)
    if (cumulativeM > (splits.size * 1000.0) + 100.0) {
        val timeDelta = lastPoint.timestampMs - kmStartMs
        val elevDelta = (lastPoint.elevationM ?: kmStartElev) - kmStartElev
        splits.add(KmSplit(
            km = splits.size + 1,
            timeMs = timeDelta,
            elevDeltaM = elevDelta
        ))
    }

    return splits
}

/** Format milliseconds as m:ss pace per km. */
fun formatSplitPace(timeMs: Long): String {
    if (timeMs <= 0) return "-"
    val totalSeconds = timeMs / 1000.0
    val paceSecondsPerKm = totalSeconds // already per-km since each split is 1km
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

    val maxTimeMs = splits.maxOf { it.timeMs }.coerceAtLeast(1L)

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
                    formatSplitPace(split.timeMs),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(48.dp)
                )
                // Visual bar (proportional to time, inversely = faster = shorter bar)
                val barColor = MaterialTheme.colorScheme.primary
                val barFraction = (1.0 - (split.timeMs.toDouble() / maxTimeMs)).coerceIn(0.0, 1.0).toFloat()
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
