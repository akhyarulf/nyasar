package com.nyasar.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nyasar.app.R

/**
 * One header recipe for all four bottom-bar tabs (Maps, Library, Explore,
 * Profile — Record keeps its own full-screen recording UI on purpose).
 *
 * Design: primary→secondary gradient with a subtle topographic contour
 * motif, "NYASAR" kicker + bold title (optional subtitle), and optional
 * trailing action icons. Everything reads from MaterialTheme.colorScheme
 * so light and dark mode both stay correct (onPrimary text/icons over the
 * primary/secondary gradient — no hardcoded light-theme colors).
 *
 * Height is fixed for all tabs so switching tabs never makes the content
 * below jump.
 */
@Composable
fun NyasarTabHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(color = MaterialTheme.colorScheme.primary, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(124.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
        ) {
            // Topographic contour motif — the same personality as the map
            // brand. Low-alpha white keeps the motif identical in dark mode.
            Canvas(Modifier.fillMaxSize()) {
                val lineColor = Color.White.copy(alpha = 0.10f)
                for (i in 0..3) {
                    val y = size.height * (0.30f + i * 0.16f)
                    val path = Path().apply {
                        moveTo(-24f, y)
                        cubicTo(
                            size.width * 0.22f, y - 24f,
                            size.width * 0.52f, y + 24f,
                            size.width + 24f, y
                        )
                    }
                    drawPath(path, lineColor, style = Stroke(width = 1.5f))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.brand_kicker),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                            maxLines = 1
                        )
                    }
                }
                actions()
            }
        }
    }
}

/** Trailing action inside [NyasarTabHeader] — onPrimary-tinted icon button. */
@Composable
fun TabHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/** Small gap helper between trailing actions. */
@Composable
fun TabHeaderActionSpacer() {
    Spacer(Modifier.width(4.dp))
}
