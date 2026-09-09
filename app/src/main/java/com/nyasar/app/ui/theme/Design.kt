package com.nyasar.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Nyasar Design Tokens — the single source of truth for the app's visual
 * language. Every corner radius, spacing step, elevation and animation
 * duration/easing in the app MUST come from here (previously radii were a
 * scattered mix of 8/10/12/16/20/24/28dp picked ad-hoc per file, which is
 * exactly the "random" look this upgrade removes).
 *
 * Radius scale (on one visual curve):
 *   - [Radius.xs]  8dp  — chips, small tags, inline badges
 *   - [Radius.sm]  12dp — buttons, banners, small cards, map pills
 *   - [Radius.md]  16dp — cards, stat containers, dialogs' inner surfaces
 *   - [Radius.lg]  20dp — large cards, action bars over the map
 *   - [Radius.xl]  28dp — bottom sheets and hero containers
 *   - Full circle (CircleShape) stays reserved for map control buttons and
 *     avatars only, matching the existing play/recenter/compass pattern.
 *
 * Spacing scale (4dp grid): xs=4, sm=8, md=12, lg=16, xl=24, xxl=32.
 *
 * Motion scale: one standard duration per motion class + one easing pair,
 * so screens animate with the same personality everywhere (decelerate in,
 * accelerate out, spring for direct-manipulation presses).
 */
object NyasarRadius {
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 28.dp

    /** Stadium-shaped containers (search bar, status pills) — half-height
     *  rounding at typical control heights. Distinct from CircleShape, which
     *  stays reserved for round map-control buttons and avatars. */
    val pill: Dp = 24.dp
}

object NyasarSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Elevation tokens for floating map UI. Map controls must read over
 *  satellite imagery and tiles, so they carry one shared shadow recipe
 *  (tonal 3 + shadow 2) instead of per-file guesses. */
object NyasarElevation {
    val mapControlTonal: Dp = 3.dp
    val mapControlShadow: Dp = 2.dp
    val cardTonal: Dp = 2.dp
    val floatingShadow: Dp = 4.dp
}

object NyasarMotion {
    // Durations (ms) — fast for micro-interactions, base for entrances,
    // slow for full-screen transitions.
    const val FAST_MS = 150
    const val BASE_MS = 220
    const val SLOW_MS = 300

    // Easings — the Material "emphasized" pair. Enter decelerates to rest,
    // exit accelerates away. Used by every enter/exit in the app so motion
    // feels identical everywhere.
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // Standard tween factories — screens call these instead of hand-rolling
    // tween(duration) with random easings.
    fun <T> enter() = androidx.compose.animation.core.tween<T>(BASE_MS, easing = EmphasizedDecelerate)
    fun <T> exit() = androidx.compose.animation.core.tween<T>(BASE_MS, easing = EmphasizedAccelerate)
    fun <T> fast() = androidx.compose.animation.core.tween<T>(FAST_MS, easing = EmphasizedDecelerate)
    fun <T> slow() = androidx.compose.animation.core.tween<T>(SLOW_MS, easing = EmphasizedDecelerate)

    // Press spring — direct-manipulation scale feedback on tappable cards
    // and buttons. Same spring everywhere = same tactile feel everywhere.
    fun <T> press() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

    // Tab/nav fade-and-settle — used by MainActivity's tab transitions and
    // the bottom bar's selection animation. Kept here so nav transitions
    // and in-screen entrances share one personality. (LinearOutSlowIn is
    // the platform's decelerate curve for small UI elements.)
    val Settle = LinearOutSlowInEasing
}

/**
 * Max content widths for responsiveness: on phones everything is full
 * width; on tablets/landscape the list/detail content centers at these
 * caps instead of stretching edge-to-edge (which reads broken at 800dp+).
 * Sheets center too. Map screens intentionally stay full-bleed — a map
 * should always fill the screen.
 */
object NyasarContentWidth {
    /** List screens (History, Library, RoutePicker, Offline maps). */
    val listMaxWidth: Dp = 640.dp

    /** Bottom sheets and dialogs. */
    val sheetMaxWidth: Dp = 560.dp

    /** Forms (post-recording, waypoint, draw-route save panel). */
    val formMaxWidth: Dp = 560.dp
}
