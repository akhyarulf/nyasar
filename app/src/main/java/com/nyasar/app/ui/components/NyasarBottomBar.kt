package com.nyasar.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/** The 4 routes the bottom bar is allowed to appear on (spec PART 1:
 *  "Tab bar HANYA muncul di 4 screen utama"). "start-activity" here means
 *  specifically the route-less Start Activity screen (Home's quick-start
 *  entry) — the route-scoped variant "start-activity/{routeId}" reached
 *  from Route Preview is a secondary flow and intentionally not included,
 *  same as Route Preview itself. Exposed so MainActivity's NavHost can
 *  decide visibility from the current back stack entry without duplicating
 *  this route list in two places. */
/**
 * Routes where the bottom bar should be visible. The recording route
 * can appear in several forms depending on parameters:
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
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** Route template to match against currentRoute for the "selected"
     *  highlight — needed only where it differs from [route] (navigate()
     *  argument string vs. the composable's registered template, e.g.
     *  optional query params get filled in by NavHost before
     *  destination.route is read back). Defaults to [route] itself. */
    val matchRoute: String = route
)

private val TABS = listOf(
    BottomTab("home", "Home", Icons.Default.Home),
    BottomTab("track-and-maps", "Library", Icons.Default.Map),
    // Was "start-activity" — an intermediate "Tanpa route / pilih route"
    // screen the user had to tap through before ever seeing the map. Now
    // goes straight to the live recording screen (map + big Play button,
    // nothing started yet) — matches Strava's Record tab opening straight
    // onto the map instead of a picker first.
    BottomTab(
        route = "recording?autoStart=false",
        label = "Record",
        icon = Icons.Default.FiberManualRecord,
        matchRoute = "recording?routeId={routeId}&autoStart={autoStart}"
    ),
    BottomTab("history", "History", Icons.Default.History),
    BottomTab("settings", "Settings", Icons.Default.Settings)
)

/**
 * Spec PART 1: permanent 4-tab shell. Deliberately dumb — no state of its
 * own, just renders [currentRoute] highlighted and forwards taps. Does NOT
 * decide when it's visible; that's the caller's job (see
 * [BOTTOM_BAR_ROUTES]), since visibility depends on nav-graph knowledge
 * this component shouldn't need.
 */
@Composable
fun NyasarBottomBar(currentRoute: String?, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier) {
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
            NavigationBarItem(
                selected = isSelected,
                onClick = { if (!isSelected) onTabSelected(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}
