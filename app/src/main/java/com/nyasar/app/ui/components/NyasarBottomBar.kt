package com.nyasar.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R
import com.nyasar.app.ui.theme.NyasarMotion

/**
 * Routes where the bottom bar should be visible (spec PART 1: "Tab bar
 * HANYA muncul di 4 screen utama"). The recording route can appear in
 * several forms depending on parameters:
 * - "recording?autoStart=false" (from Record tab)
 * - "recording?autoStart=true" (from Home quick-start)
 * - "recording?routeId=X&autoStart=false" (from Track picker)
 * - "recording?routeId=X&autoStart=true" (from Start Activity with route)
 *
 * We check if the route starts with "recording?" to handle all these
 * cases, rather than listing each variant.
 */
val BOTTOM_BAR_ROUTES = setOf("home", "track-and-maps", "history", "settings")
private const val RECORDING_ROUTE_PREFIX = "recording?"

/**
 * Check if the current route should show the bottom bar.
 */
fun shouldShowBottomBar(route: String?): Boolean {
    if (route == null) return false
    return route in BOTTOM_BAR_ROUTES ||
            // Prefix-match: track-and-maps?pickMode=true must also match,
            // recording?routeId=X&autoStart=Y must also match.
            route.startsWith("track-and-maps") ||
            route.startsWith(RECORDING_ROUTE_PREFIX) ||
            route.startsWith("activity/")
}

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    /** Route template to match against currentRoute for the "selected"
     *  highlight — needed only where it differs from [route] (navigate()
     *  argument string vs. the composable's registered template, e.g.
     *  optional query params get filled in by NavHost before
     *  destination.route is read back). Defaults to [route] itself. */
    val matchRoute: String = route
)

private val TABS = listOf(
    BottomTab("home", R.string.nav_home, Icons.Default.Home),
    BottomTab("track-and-maps", R.string.nav_library, Icons.Default.Map),
    // Was "start-activity" — an intermediate "Tanpa route / pilih route"
    // screen the user had to tap through before ever seeing the map. Now
    // goes straight to the live recording screen (map + big Play button,
    // nothing started yet) — matches Strava's Record tab opening straight
    // onto the map instead of a picker first.
    BottomTab(
        route = "recording?autoStart=false",
        labelRes = R.string.nav_record,
        icon = Icons.Default.FiberManualRecord,
        matchRoute = "recording?routeId={routeId}&autoStart={autoStart}"
    ),
    BottomTab("history", R.string.nav_history, Icons.Default.History),
    BottomTab("settings", R.string.nav_settings, Icons.Default.Settings)
)

/**
 * Spec PART 1: permanent 4-tab shell. Deliberately dumb — no state of its
 * own, just renders [currentRoute] highlighted and forwards taps. Does NOT
 * decide when it's visible; that's the caller's job (see
 * [BOTTOM_BAR_ROUTES]), since visibility depends on nav-graph knowledge
 * this component shouldn't need.
 *
 * UI upgrade: M3 NavigationBar with the shared theme (surfaceContainer bar,
 * primary indicator pill) and an animated selection — the icon pops to full
 * size / labels stay, so switching tabs reads as motion rather than a
 * color flip, using the shared [NyasarMotion] spring. Route-matching logic
 * is byte-identical to the pre-upgrade version.
 */
@Composable
fun NyasarBottomBar(currentRoute: String?, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        TABS.forEach { tab ->
            // BUG FIX: Use prefix matching for the recording tab, since
            // the actual route can have different parameters (routeId, autoStart)
            // but should still be considered as the "Record" tab being selected.
            val isSelected = when {
                tab.route == "recording?autoStart=false" -> currentRoute?.startsWith("recording?") == true
                // Use exact match: "track-and-maps?pickMode=true" is a separate route
                // (Recording's route picker), not the Library tab. Using startsWith
                // would make Library appear selected on pickMode, preventing the user
                // from navigating back to proper Library via the bottom bar.
                tab.route == "track-and-maps" -> currentRoute == "track-and-maps"
                tab.route == "history" -> currentRoute == tab.matchRoute || currentRoute?.startsWith("activity/") == true
                else -> currentRoute == tab.matchRoute
            }
            // Animated selection: icon scales up gently when its tab is
            // selected (the M3 indicator pill animates itself; this adds
            // the same feel to the icon). Shared press spring keeps it
            // consistent with every other animated element.
            val iconScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isSelected) 1.12f else 1f,
                animationSpec = NyasarMotion.press(),
                label = "tabIconScale"
            )
            NavigationBarItem(
                selected = isSelected,
                onClick = { if (!isSelected) onTabSelected(tab.route) },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = stringResource(tab.labelRes),
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    )
                },
                label = { Text(stringResource(tab.labelRes), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
