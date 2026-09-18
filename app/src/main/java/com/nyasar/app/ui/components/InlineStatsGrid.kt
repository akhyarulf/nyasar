package com.nyasar.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Strava-style inline stats grid — the shared visual language for ALL three
 * route/activity detail screens (browse Route Detail, library Route Preview,
 * activity ActivityDetail): small muted label ABOVE a bold value, 3 per row,
 * NO tiles/cards. Data stays each screen's own; only the shape is shared.
 *
 * Rows of fewer than 3 items still fill the width evenly (weighted cells),
 * and the last row is left-aligned with empty spacers for the remainder.
 */
@Composable
fun InlineStatsGrid(
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    if (stats.isEmpty()) return
    Column(modifier) {
        stats.chunked(3).forEach { rowStats ->
            Row(Modifier.fillMaxWidth()) {
                rowStats.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
                repeat(3 - rowStats.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** One elevation summary cell (value bold over muted label, centered) — the
 *  "+1398 m / Elev Gain" tiles under the elevation chart. Kept separate from
 *  [InlineStatsGrid] because summary cells are CENTERED and large, unlike
 *  the left-aligned header stats. */
@Composable
fun SummaryStatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
