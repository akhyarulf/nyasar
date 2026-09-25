package com.nyasar.app.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Design (2026-09 rework): a CALM header that mirrors the bottom
 * navigation bar instead of fighting it — the same surfaceContainer
 * color the M3 NavigationBar uses, so the top and bottom of every tab
 * read as one frame in BOTH light and dark mode (the old primary→
 * secondary gradient rendered as a big pale-green block over the dark
 * theme and the user asked for it to go). Personality stays through a
 * barely-there topographic contour motif and the "NYASAR" kicker in
 * brand primary; text/icons read from onSurface/onSurfaceVariant so
 * contrast is correct in either theme automatically.
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                // Topographic contour motif — the map-brand personality, now
                // in onSurface at very low alpha so it whispers instead of
                // shouting and stays correct in both themes. Color is read
                // OUTSIDE the Canvas: DrawScope is not a composable context.
                val contourColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                Canvas(Modifier.fillMaxSize()) {
                    val lineColor = contourColor
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
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.brand_kicker),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        if (subtitle != null) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    actions()
                }
            }
            // Hairline separation from the content below — the same visual
            // job tonalElevation does for the bottom bar, mirrored up top.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Trailing action inside [NyasarTabHeader] — onSurface-tinted icon button. */
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
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Small gap helper between trailing actions. */
@Composable
fun TabHeaderActionSpacer() {
    Spacer(Modifier.width(4.dp))
}
