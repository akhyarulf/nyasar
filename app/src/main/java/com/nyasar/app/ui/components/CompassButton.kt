package com.nyasar.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/**
 * Missing entirely before (spec complaint: "kompas gaada"). Shows which way
 * the map is currently rotated relative to true north. Tap jumps straight
 * back to north-up.
 *
 * Two things changed from the first version:
 * - The icon is now a drawn red/gray needle diamond (the standard map-app
 *   compass look — Google Maps, Strava, etc all use this shape) instead of
 *   a plain filled arrow, which read as crude ("gambarnya kaya purba").
 * - The whole widget fades out once the map is within ~1° of north-up and
 *   fades back in as soon as it isn't, since a "reset to north" button is
 *   pure clutter while already facing north. Handled here once so every
 *   screen using this component gets it for free.
 *
 * onLongClick toggles heading-up follow mode (see NavigationScreen).
 *
 * bearingDeg is the CAMERA's bearing (degrees clockwise from north the map
 * is currently rotated), not the user's GPS heading.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompassButton(
    bearingDeg: Float,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val normalized = ((bearingDeg % 360f) + 360f) % 360f
    val facingNorth = normalized < 1f || normalized > 359f

    AnimatedVisibility(
        visible = !facingNorth,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val northColor = MaterialTheme.colorScheme.error
        val southColor = MaterialTheme.colorScheme.onSurfaceVariant
        val ringColor = MaterialTheme.colorScheme.outlineVariant

        Surface(
            shape = CircleShape,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            modifier = Modifier.size(44.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Canvas(modifier = Modifier.size(44.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension * 0.38f

                drawCircle(color = ringColor, radius = r + 4f, center = Offset(cx, cy), style = Stroke(width = 1.5f))

                rotate(degrees = -normalized, pivot = Offset(cx, cy)) {
                    // North half — sharp point up, red.
                    drawPath(
                        path = Path().apply {
                            moveTo(cx, cy - r)
                            lineTo(cx - r * 0.32f, cy)
                            lineTo(cx + r * 0.32f, cy)
                            close()
                        },
                        color = northColor,
                        style = Fill
                    )
                    // South half — sharp point down, gray.
                    drawPath(
                        path = Path().apply {
                            moveTo(cx, cy + r)
                            lineTo(cx - r * 0.32f, cy)
                            lineTo(cx + r * 0.32f, cy)
                            close()
                        },
                        color = southColor,
                        style = Fill
                    )
                }
                drawCircle(color = Color.White, radius = 2.5f, center = Offset(cx, cy))
            }
        }
    }
}
