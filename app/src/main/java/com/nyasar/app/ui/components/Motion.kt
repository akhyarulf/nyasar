package com.nyasar.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nyasar.app.ui.theme.NyasarContentWidth
import com.nyasar.app.ui.theme.NyasarMotion
import com.nyasar.app.ui.theme.NyasarRadius

/**
 * Nyasar shared motion & responsive primitives. Previously every screen
 * composed its own press feedback (or none), its own entrance (or none),
 * and stretched edge-to-edge on tablets — this is the one place those
 * behaviors live now, so the whole app animates and adapts identically.
 */

/**
 * Press-scale feedback for tappable cards/surfaces that aren't Buttons.
 * Apply to a Surface/Box that already handles its own [androidx.compose.foundation.clickable]:
 *
 * ```
 * val interaction = remember { MutableInteractionSource() }
 * Surface(
 *     modifier = Modifier
 *         .pressScale(interaction)
 *         .clickable(interactionSource = interaction, indication = null) { ... }
 * )
 * ```
 *
 * Using `indication = null` removes the default ripple so the scale IS the
 * feedback (a ripple under a scaling surface looks doubled). Scale anchor
 * is the center, matching how cards are perceived to compress.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = NyasarMotion.press(),
        label = "pressScale"
    )
    this.scale(scale)
}

/**
 * One-shot fade + rise entrance for cards, banners and sections. First
 * composition: starts invisible, animates to full opacity and its resting
 * position with the standard emphasized decelerate curve. Recompositions
 * don't replay it (state is remembered per call site), and [delayMs]
 * staggers list items or grouped elements.
 *
 * @param delayMs stagger offset — pass [Stagger.forIndex](index) for lists
 *        so long lists never wait forever.
 */
@Composable
fun AnimatedAppear(
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(
            animationSpec = tween(NyasarMotion.BASE_MS, delayMillis = delayMs, easing = NyasarMotion.EmphasizedDecelerate)
        ) + slideInVertically(
            initialOffsetY = { it / 20 },
            animationSpec = tween(NyasarMotion.BASE_MS, delayMillis = delayMs, easing = NyasarMotion.EmphasizedDecelerate)
        )
    ) {
        content()
    }
}

/** Stagger delay helper: index * step capped at [MAX_DELAY_MS] so a
 *  200-item list doesn't hold the last card invisible for 8 seconds. */
object Stagger {
    const val STEP_MS = 40
    const val MAX_DELAY_MS = 320
    fun forIndex(index: Int): Int = (index * STEP_MS).coerceAtMost(MAX_DELAY_MS)
}

/**
 * Animated numeric readout for stats (distance ticking while recording,
 * animated totals on summary screens). Keeps digits from "flashing" on
 * every GPS fix: value changes slide/fade between strings with the shared
 * fast tween instead of hard-cutting.
 */
@Composable
fun AnimatedStatText(
    value: String,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(animationSpec = tween(NyasarMotion.FAST_MS)) +
                slideInVertically { it / 6 })
                .togetherWith(
                    fadeOut(animationSpec = tween(NyasarMotion.FAST_MS)) +
                        slideOutVertically { -it / 6 }
                )
        },
        label = "statText",
        modifier = modifier
    ) { text ->
        Text(
            text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * Centers and caps content on wide screens (tablets/landscape). Phones are
 * unaffected (full width below the cap). Combine with
 * `Modifier.align(Alignment.CenterHorizontally)` at the call site.
 */
fun Modifier.cappedWidth(maxWidth: Dp = NyasarContentWidth.listMaxWidth): Modifier =
    composed {
        this.widthIn(max = maxWidth).fillMaxWidth()
    }

// ---------------------------------------------------------------------------
// NyasarStatChip — the ONE stat presentation unit (icon + label + value)
// shared across Recording, Navigation, History cards and Activity Detail, so
// stats look identical everywhere (previously each screen rolled its own
// Column-of-Texts with different label styles/colors).
// ---------------------------------------------------------------------------

@Composable
fun NyasarStatChip(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(NyasarRadius.md),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// NyasarIconBadge — icon in a soft tinted circle. The shared icon-badge look
// (EmptyState and the History sport badge each invented it independently).
// ---------------------------------------------------------------------------

@Composable
fun NyasarIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    iconSize: Dp = size / 2
) {
    Surface(shape = CircleShape, color = container, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

// ---------------------------------------------------------------------------
// NyasarSectionCard — the shared grouped-container look (content on a
// surfaceContainerLow card with md radius). Settings, detail screens and
// sheets use this so "sections" read identically app-wide.
// ---------------------------------------------------------------------------

@Composable
fun NyasarSectionCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NyasarRadius.md),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * AnimatedScreen — standard page entrance wrapper: content fades+rises as a
 * whole on first composition. Screens that stagger their own cards with
 * [AnimatedAppear] per item don't need this; screens with a single static
 * column (Settings, forms) get a consistent entrance for free.
 */
@Composable
fun AnimatedScreen(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedAppear(modifier = modifier, delayMs = 0) { content() }
}
