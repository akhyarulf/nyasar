package com.nyasar.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
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
 * Bottom-bar navigation concept (2026 IA rework — PROJECT_CONTEXT.md):
 *
 *   Browse | Map | Record | Library | Profile
 *
 * - Browse  — NEW public-route browser (search/filter/sort, Fase 2 poin 3).
 * - Map     — the previous HomeScreen fullscreen map, MOVED (not rebuilt).
 * - Record  — unchanged, straight onto the recording screen.
 * - Library — TrackAndMapsScreen unchanged (GPX import/draw/offline maps).
 * - Profile — History + Saved tabs; Settings behind the header's gear icon.
 *
 * The old standalone "history"/"settings" routes still exist as nav
 * destinations ONLY inside the Profile tab (see ProfileScreen) — they are
 * no longer bottom-bar entries, and the old "home" route string is kept
 * for the Map tab so every existing "navigate(\"home\")" call site
 * (offline-map focus, TrackAndMaps' home button) keeps working untouched.
 */
val BOTTOM_BAR_ROUTES = setOf("browse", "home", "track-and-maps", "profile")
private const val RECORDING_ROUTE_PREFIX = "recording?"

/**
 * Check if the current route should show the bottom bar.
 */
fun shouldShowBottomBar(route: String?): Boolean {
    if (route == null) return false
    return route in BOTTOM_BAR_ROUTES ||
            // Prefix-match: track-and-maps?pickMode=true must also match,
            // recording?routeId=X&autoStart=Y must also match, and the
            // Profile tab is registered as "profile?tab={tab}" (optional
            // arg), which also lands under this prefix.
            route.startsWith("track-and-maps") ||
            route.startsWith(RECORDING_ROUTE_PREFIX) ||
            route.startsWith("profile") ||
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
    BottomTab("browse", R.string.nav_browse, Icons.Default.Explore),
    // Map = the old HomeScreen, route string deliberately unchanged.
    BottomTab("home", R.string.nav_map, Icons.Default.Map),
    // Record goes straight to the live recording screen (map + big Play
    // button) — unchanged from the previous IA.
    BottomTab(
        route = "recording?autoStart=false",
        labelRes = R.string.nav_record,
        icon = Icons.Default.FiberManualRecord,
        matchRoute = "recording?routeId={routeId}&autoStart={autoStart}"
    ),
    BottomTab("track-and-maps", R.string.nav_library, Icons.Default.Layers),
    BottomTab(
        route = "profile",
        labelRes = R.string.nav_profile,
        icon = Icons.Default.Person,
        matchRoute = "profile?tab={tab}"
    )
)

/**
 * Permanent 5-tab shell. Deliberately dumb — no state of its own, just
 * renders [currentRoute] highlighted and forwards taps. Does NOT decide when
 * it's visible; that's the caller's job (see [BOTTOM_BAR_ROUTES]), since
 * visibility depends on nav-graph knowledge this component shouldn't need.
 *
 * UI: M3 NavigationBar with the shared theme (surfaceContainer bar, primary
 * indicator pill) and an animated selection — the icon pops to full size /
 * labels stay, so switching tabs reads as motion rather than a color flip,
 * using the shared [NyasarMotion] spring. Route-matching logic is byte-
 * identical to the pre-rework version (prefix matching for parameterized
 * routes, exact match for plain tabs).
 */
@Composable
fun NyasarBottomBar(currentRoute: String?, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        TABS.forEach { tab ->
            // BUG FIX (kept from the pre-rework bar): use prefix matching for
            // parameterized tabs, since the actual route can carry different
            // parameters but must still highlight its tab. Exact match for
            // plain tabs: "track-and-maps?pickMode=true" is a separate route
            // (Recording's route picker), NOT the Library tab.
            val isSelected = when {
                tab.route == "recording?autoStart=false" -> currentRoute?.startsWith("recording?") == true
                tab.route == "track-and-maps" -> currentRoute == "track-and-maps"
                tab.route == "profile" -> currentRoute == tab.matchRoute ||
                    currentRoute?.startsWith("profile?tab=") == true
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
