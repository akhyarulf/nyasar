package com.nyasar.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nyasar.app.ui.theme.NyasarRadius

/**
 * Wikiloc-style fixed-pastel difficulty badge, extracted from the browse
 * card so the detail screen shows the IDENTICAL chip (same hue per level
 * across themes/locales — the level reads at a glance in both places).
 */
@Composable
fun DifficultyChip(label: String, wire: String, modifier: Modifier = Modifier) {
    val (fg, bg) = when (wire) {
        "easy" -> Color(0xFF1B5E20) to Color(0xFFE3F2E4)
        "moderate" -> Color(0xFF9A6A00) to Color(0xFFFFF1DB)
        "difficult" -> Color(0xFFB3261E) to Color(0xFFFCE8E6)
        else -> Color(0xFF6A1B9A) to Color(0xFFF3E5F5)
    }
    Surface(color = bg, shape = RoundedCornerShape(NyasarRadius.xs), modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Neutral chip for the trail shape (loop / out-and-back / point-to-point)
 *  — same extraction story as [DifficultyChip]. */
@Composable
fun TrailTypeChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(NyasarRadius.xs),
        modifier = modifier
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
