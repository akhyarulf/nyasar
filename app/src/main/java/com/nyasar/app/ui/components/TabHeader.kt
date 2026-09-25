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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nyasar.app.R

/**
 * One header recipe for all four bottom-bar tabs (Browse, Map, Library,
 * Profile — Record keeps its own full-screen recording UI on purpose).
 *
 * Design (2026-09 rework v2, user feedback: header "nyempil", terlalu
 * kosong, tidak estetik): a compact brand-anchored bar instead of a tall
 * title block. One row — the launcher-style logo mark in a rounded brand
 * chip, the tab title beside it (no kicker line, no subtitle line), then
 * trailing actions. Height shrinks from 112dp+statusbar to a tight
 * bar (~64dp incl. status-bar inset) so the content below gets the space,
 * and switching tabs reads as one continuous app frame.
 *
 * The topographic contour motif stays as the map-brand personality but is
 * clipped to the shorter bar. Colors still come from the M3 scheme so
 * light/dark both read correctly with zero hardcoding.
 */
@Composable
fun NyasarTabHeader(
    title: String,
    /** Optional one-liner shown under the title — kept for callers that
     *  genuinely explain the screen (Explore). Renders inline in the same
     *  row block; the bar grows slightly only when present. */
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                // Topographic contour motif — clipped to the bar itself.
                val contourColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..2) {
                        val y = size.height * (0.35f + i * 0.28f)
                        val path = Path().apply {
                            moveTo(-24f, y)
                            cubicTo(
                                size.width * 0.22f, y - 18f,
                                size.width * 0.52f, y + 18f,
                                size.width + 24f, y
                            )
                        }
                        drawPath(path, contourColor, style = Stroke(width = 1.5f))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Brand mark: the actual launcher foreground artwork on a
                    // rounded brand chip — the app icon in miniature, same
                    // artwork the splash + web favicon use. Anchors the
                    // header visually instead of a floating text kicker.
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                brandMarkPainter(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        if (subtitle != null) {
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

/**
 * The launcher foreground artwork as a painter: the splash logo PNG —
 * the same artwork as the launcher icon, hi-res — so header, splash, and
 * app icon share one visual source of truth.
 */
@Composable
private fun brandMarkPainter() = painterResource(com.nyasar.app.R.drawable.splash_logo)

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
