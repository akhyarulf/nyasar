package com.nyasar.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nyasar.app.ui.components.BOTTOM_BAR_ROUTES
import com.nyasar.app.ui.components.NyasarBottomBar
import com.nyasar.app.ui.drawroute.DrawRouteScreen
import com.nyasar.app.ui.history.ActivityDetailScreen
import com.nyasar.app.ui.history.ActivityHistoryScreen
import com.nyasar.app.ui.home.HomeScreen
import com.nyasar.app.ui.navigation.NavigationScreen
import com.nyasar.app.ui.offline.OfflineMapsScreen
import com.nyasar.app.ui.preview.OfflineDownloadScreen
import com.nyasar.app.ui.preview.RoutePreviewScreen
import com.nyasar.app.ui.recording.RecordingScreen
import com.nyasar.app.ui.settings.SettingsScreen
import com.nyasar.app.ui.startactivity.StartActivityScreen
import com.nyasar.app.ui.theme.NyasarTheme
import com.nyasar.app.ui.trackmaps.TrackAndMapsScreen

class MainActivity : ComponentActivity() {

    private var pendingImportUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportUri = extractGpxUriFromIntent(intent)

        setContent {
            // Read theme mode directly here (not via a ViewModel) since it
            // has to wrap the entire NavHost, above any single screen's
            // scope (spec Settings > Appearance: Light/Dark/System).
            val settingsRepository = remember {
                com.nyasar.app.data.settings.SettingsRepository(applicationContext)
            }
            val settings by settingsRepository.settings.collectAsState(initial = null)

            NyasarTheme(themeMode = settings?.themeMode) {
                val locationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* result observed by screens via LocationRepository.hasLocationPermission() */ }
                // P3I audit fix (§19): POST_NOTIFICATIONS was declared in
                // the manifest but never requested at runtime — required
                // separately on API 33+ (unlike the older manifest-only
                // permissions). Without this, RecordingService's
                // foreground notification (spec §19: "selalu muncul ketika
                // service recording") silently fails to display on a
                // fresh API 33+ install; the foreground service itself
                // still runs fine and recording is unaffected, but the
                // user loses the visible indicator that recording is
                // active, and any updateNotification() call becomes a
                // no-op the app has no way to detect. Requesting it here,
                // alongside location, means it's granted (or denied, same
                // as location) before the user ever starts a recording.
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* denial is non-fatal — recording still works, see above */ }

                LaunchedEffect(Unit) {
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val navController = rememberNavController()
                NyasarNavHost(
                    navController = navController,
                    pendingImportUri = pendingImportUri,
                    onImportConsumed = { pendingImportUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractGpxUriFromIntent(intent)?.let { pendingImportUri = it }
    }

    private fun extractGpxUriFromIntent(intent: Intent?): Uri? {
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
    }
}

@Composable
private fun NyasarNavHost(
    navController: NavHostController,
    pendingImportUri: Uri?,
    onImportConsumed: () -> Unit
) {
    // Bottom bar via Scaffold's bottomBar slot + innerPadding for content.
    // System insets (navigation bar) are handled by Scaffold defaults.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // PART 3 fix: BOTTOM_BAR_ROUTES matches the recording route pattern
    // for its whole lifetime (IDLE through STOPPED — Compose keeps the
    // same NavBackStackEntry the entire time), so a route-based check
    // alone can't tell "IDLE, bar is fine" apart from "RECORDING, bar
    // covers the Pause button" — the state that matters lives inside
    // RecordingScreen (RecordingUiState.status), which this NavHost has
    // no reason to otherwise know about. RecordingScreen reports it up
    // through this one boolean instead of restructuring navigation or
    // hoisting recording state to this level.
    var recordingActive by remember { mutableStateOf(false) }
    val isRecordingRoute = currentRoute?.startsWith("recording?") == true
    val showBottomBar = when {
        // On a recording route: hide bar only while recording is actually active
        // (to avoid overlapping the Pause button), show it once recording stops.
        isRecordingRoute -> !recordingActive
        // On main tabs (Home, Library, History, Settings): always show.
        // On sub-screens (preview, activity detail, etc.): show if registered.
        currentRoute != null -> com.nyasar.app.ui.components.shouldShowBottomBar(currentRoute)
        // Safety: if currentRoute is null (during navigation transition or
        // state restoration), default to showing the bar so it never
        // unexpectedly disappears on main tabs like Library.
        else -> true
    }

    // Reset recordingActive when navigating away from recording route.
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && !isRecordingRoute) {
            recordingActive = false
        }
    }

    // PART 4: "Lihat di Peta" (OfflineMapsScreen -> Home, focused on the
    // selected offline area). Lives here (not inside a ViewModel) for the
    // same reason pendingImportUri does two lines below in MainActivity —
    // it's a value produced by one composable (offline-maps' button) and
    // consumed by another (home), and this NavHost function is exactly
    // where that already happens for pendingImportUri. Home's own route
    // string/pattern stays untouched ("home", no query args) specifically
    // so BOTTOM_BAR_ROUTES (PART 1, not touched) keeps matching it.
    var pendingHomeFocusBounds by remember {
        mutableStateOf<org.maplibre.android.geometry.LatLngBounds?>(null)
    }

    // PART 3: "Pilih Jalur" — same hoisted-state-in-MainActivity handoff
    // pattern as pendingImportUri/pendingHomeFocusBounds above, this time
    // carrying a routeId from Track & Peta (in pick mode) back to whatever
    // RecordingScreen instance is still sitting on the backstack beneath it.
    var pendingSelectedRouteId by remember { mutableStateOf<String?>(null) }

    // FIX: Use Scaffold's default contentWindowInsets so system insets
    // (status bar, navigation bar) are properly handled. The bottom bar
    // renders above the system navigation bar, and innerPadding includes
    // both bar heights + system insets. We copy(top = 0.dp) because each
    // screen handles its own top inset (HomeScreen fullscreen, other
    // screens have their own TopAppBar/Scaffold).
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NyasarBottomBar(
                    currentRoute = currentRoute,
                    onTabSelected = { route ->
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        // Exclude top inset: each screen handles its own status bar.
        // Include bottom inset: bottom bar + system navigation bar.
        val contentPadding = PaddingValues(
            top = 0.dp,
            bottom = innerPadding.calculateBottomPadding()
        )
        NavHost(navController = navController, startDestination = "home", modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        composable("home") {
            HomeScreen(
                pendingImportUri = pendingImportUri,
                onImportConsumed = onImportConsumed,
                pendingFocusBounds = pendingHomeFocusBounds,
                onFocusBoundsConsumed = { pendingHomeFocusBounds = null },
                onOpenRoute = { routeId -> navController.navigate("preview/$routeId") },
                onOpenSettings = { navController.navigate("settings") },
                onStartRecording = { navController.navigate("start-activity") },
                onResumeRecording = { navController.navigate("recording?autoStart=false") },
                onOpenHistory = { navController.navigate("history") },
                onOpenDrawRoute = { navController.navigate("draw-route") }
            )
        }
        composable("history") {
            ActivityHistoryScreen(
                onOpenActivity = { id -> navController.navigate("activity/$id") },
                onBack = { navController.popBackStack() }
            )
        }
        composable("activity/{activityId}") { backStackEntry ->
            val activityId = backStackEntry.arguments?.getString("activityId") ?: return@composable
            ActivityDetailScreen(
                activityId = activityId,
                onBack = { navController.popBackStack() }
            )
        }
        // Route-less Start Activity: Home's "Mulai Recording" FAB. No route
        // to pick, so Navigation is never offered here — only the Recording
        // toggle (spec Task 7: "Jika tidak ada route: Recording = ON,
        // Navigation = unavailable").
        composable("start-activity") {
            StartActivityScreen(
                routeName = null,
                onBack = { navController.popBackStack() },
                onStart = { recordingEnabled, _ ->
                    if (recordingEnabled) {
                        navController.navigate("recording?autoStart=true") {
                            popUpTo("start-activity") { inclusive = true }
                        }
                    }
                }
            )
        }
        // Route-based Start Activity: reached from Route Preview's "MULAI
        // AKTIVITAS" button. Both Recording and Navigation toggles shown,
        // both default on, neither forces the other off (Task 7).
        composable("start-activity/{routeId}") { backStackEntry ->
            val routeId = backStackEntry.arguments?.getString("routeId") ?: return@composable
            val previewViewModel: com.nyasar.app.ui.preview.RoutePreviewViewModel = viewModel()
            LaunchedEffect(routeId) { previewViewModel.load(routeId) }
            val previewState by previewViewModel.uiState.collectAsState()

            StartActivityScreen(
                routeName = previewState.name ?: routeId,
                onBack = { navController.popBackStack() },
                onStart = { recordingEnabled, navigationEnabled ->
                    val destination = when {
                        navigationEnabled && recordingEnabled ->
                            "navigate/$routeId?withRecording=true"
                        navigationEnabled ->
                            "navigate/$routeId?withRecording=false"
                        recordingEnabled ->
                            "recording?routeId=$routeId&autoStart=true"
                        else -> null
                    }
                    if (destination != null) {
                        // If recording is part of this session, start the
                        // service *before* navigating to Navigation, so
                        // NavigationViewModel's "is RecordingService already
                        // running" check (Task 6) finds it immediately
                        // instead of racing it.
                        if (recordingEnabled && navigationEnabled) {
                            val intent = com.nyasar.app.recording.RecordingService.startIntent(
                                navController.context, routeId
                            )
                            androidx.core.content.ContextCompat.startForegroundService(
                                navController.context, intent
                            )
                        }
                        navController.navigate(destination) {
                            popUpTo("start-activity/$routeId") { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(
            "recording?routeId={routeId}&autoStart={autoStart}",
            arguments = listOf(
                navArgument("routeId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("autoStart") { type = NavType.BoolType; defaultValue = true }
            )
        ) { backStackEntry ->
            val routeId = backStackEntry.arguments?.getString("routeId")
            val autoStart = backStackEntry.arguments?.getBoolean("autoStart") ?: true
            // routeId null: recording tanpa route, entry point yang sebelumnya
            // tidak ada sama sekali di app ini (spec: "jalan tanpa route ->
            // hanya recording" harus bisa langsung, tidak lewat route dulu).
            RecordingScreen(
                routeId = routeId,
                autoStart = autoStart,
                onExit = { navController.popBackStack() },
                // Navigate to the dedicated route picker screen
                // (separated from Library's TrackAndMapsScreen).
                onAddRoute = { navController.navigate("route-picker") },
                pendingSelectedRouteId = pendingSelectedRouteId,
                onSelectedRouteConsumed = { pendingSelectedRouteId = null },
                onRecordingActiveChanged = { recordingActive = it }
            )
        }
        composable("preview/{routeId}") { backStackEntry ->
            val routeId = backStackEntry.arguments?.getString("routeId") ?: return@composable
            RoutePreviewScreen(
                routeId = routeId,
                // "MULAI NAVIGASI" goes straight to Recording screen with
                // this route attached — same pattern as "Pilih Jalur" from
                // the route picker. If Recording is already on the back
                // stack, pop back to it; otherwise navigate fresh.
                onStartNavigation = { id ->
                    if (navController.popBackStack("recording?routeId={routeId}&autoStart={autoStart}", false)) {
                        pendingSelectedRouteId = id
                    } else {
                        navController.navigate("recording?routeId=$id&autoStart=false") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onDownloadOfflineMap = { id -> navController.navigate("offline-download/$id") },
                onOpenSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "navigate/{routeId}?withRecording={withRecording}",
            arguments = listOf(navArgument("withRecording") { type = NavType.BoolType; defaultValue = false })
        ) { backStackEntry ->
            val routeId = backStackEntry.arguments?.getString("routeId") ?: return@composable
            val withRecording = backStackEntry.arguments?.getBoolean("withRecording") ?: false
            NavigationScreen(
                routeId = routeId,
                withRecording = withRecording,
                onExit = { navController.popBackStack() }
            )
        }
        composable("offline-download/{routeId}") { backStackEntry ->
            val routeId = backStackEntry.arguments?.getString("routeId") ?: return@composable
            OfflineDownloadScreen(
                routeId = routeId,
                onOpenOfflineMaps = {
                    navController.navigate("offline-maps") {
                        popUpTo("home")
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        // Free-area entry point (spec §20/22 — download must be possible
        // without any route/GPX). Reuses the same screen with routeId=null.
        composable("offline-download-area") {
            OfflineDownloadScreen(
                routeId = null,
                onOpenOfflineMaps = {
                    navController.navigate("offline-maps") {
                        popUpTo("home")
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onOpenOfflineMaps = { navController.navigate("offline-maps") },
                onBack = { navController.popBackStack() }
            )
        }
        composable("track-and-maps") {
            TrackAndMapsScreen(
                onOpenRoute = { routeId -> navController.navigate("preview/$routeId") },
                onOpenOfflineMaps = { navController.navigate("offline-maps") },
                // PART 5 point 2: reuses the exact same tab-switch semantics
                // the bottom bar itself already uses (findStartDestination
                // popUpTo + saveState/restoreState) — this is just "go to
                // the Home tab", triggered from a different place.
                onOpenHome = {
                    navController.navigate("home") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenDrawRoute = { navController.navigate("draw-route") }
            )
        }
        // PART 3: same screen, same data, opened as a picker for "Pilih
        // Jalur" from the Recording IDLE state. pickMode is a plain nav
        // arg (not a second composable/route registration duplicating
        // logic) — the screen body and ViewModel are 100% shared with the
        // normal "track-and-maps" entry above.
        // Dedicated route picker for Record tab's "Pilih Jalur" —
        // separated from Library (track-and-maps) to keep the two
        // flows independent. This screen shows ONLY the route list
        // without Library's offline maps section or filter pills.
        composable("route-picker") {
            com.nyasar.app.ui.routepicker.RoutePickerScreen(
                // Route picker sets the selected route and pops back
                // to RecordingScreen — no preview screen involved.
                onRouteSelected = { routeId ->
                    pendingSelectedRouteId = routeId
                    navController.popBackStack()
                },
                onOpenDrawRoute = { navController.navigate("draw-route") },
                onBack = { navController.popBackStack() }
            )
        }
        // Draw-route feature: manual point-by-point route creation ("belum
        // ada GPX, mau gambar dulu di map"). Saving produces a real
        // RouteEntity via RouteRepository.importFromDrawnPoints — same
        // artifact an imported GPX produces — so "preview/{routeId}" and
        // "start-activity/{routeId}" both already work for it unmodified;
        // no new screens needed beyond this one and the two exit callbacks.
        composable("draw-route") {
            DrawRouteScreen(
                onBack = { navController.popBackStack() },
                onRouteSaved = { routeId ->
                    // Coming from Record's route-picker: return to
                    // recording with the drawn route attached.
                    if (navController.popBackStack("route-picker", false)) {
                        navController.navigate("recording?routeId=$routeId&autoStart=false") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                    // Coming from Library (track-and-maps): go to
                    // route preview.
                    else if (navController.popBackStack("track-and-maps", false)) {
                        navController.navigate("preview/$routeId")
                    }
                    // Coming from Home or elsewhere: go to preview.
                    else {
                        navController.navigate("preview/$routeId")
                    }
                },
                onNavigateToStart = { routeId ->
                    if (navController.popBackStack("route-picker", false)) {
                        navController.navigate("recording?routeId=$routeId&autoStart=false") {
                            popUpTo(0) { inclusive = true }
                        }
                    } else if (navController.popBackStack("track-and-maps", false)) {
                        navController.navigate("start-activity/$routeId")
                    } else {
                        navController.navigate("start-activity/$routeId")
                    }
                }
            )
        }
        composable("offline-maps") {
            OfflineMapsScreen(
                onBack = { navController.popBackStack() },
                onDownloadArea = { navController.navigate("offline-download-area") },
                // PART 4 fix: previously defaulted to
                // `{ viewModel.focus(it) }` inside OfflineMapsScreen itself
                // (a real, working action — just scoped to that screen's own
                // coverage preview map, not Home). This override replaces
                // that with the actual "navigate back to Home, focused on
                // this area" behavior the button's label promises.
                onOpenInMap = { region ->
                    // popUpTo("home") (non-inclusive, the default) pops
                    // everything above Home — settings/offline-maps/etc —
                    // WITHOUT popping Home itself, so its existing
                    // NavBackStackEntry (and HomeViewModel) survives.
                    // launchSingleTop then reuses that same entry instead of
                    // pushing a duplicate — same "don't stack duplicate
                    // Home entries" outcome the bottom bar's own navigation
                    // already guarantees elsewhere, just without that
                    // handler's saveState/restoreState (which would
                    // conflict with wanting the *new* focus value applied
                    // now, not a restored old one).
                    region.bounds?.let { pendingHomeFocusBounds = it }
                    navController.navigate("home") {
                        popUpTo("home")
                        launchSingleTop = true
                    }
                }
            )
        }
        // Share card screen
        composable("share-card/{activityId}") { backStackEntry ->
            val activityId = backStackEntry.arguments?.getString("activityId") ?: return@composable
            // Load activity data and navigate to share card screen
            // This would typically load the activity from the database
            // For now, we'll pass the activityId and let the screen handle loading
            com.nyasar.app.ui.share.ShareCardScreen(
                activity = com.nyasar.app.data.db.ActivityEntity(
                    id = activityId,
                    routeId = null,
                    name = "Activity",
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = com.nyasar.app.data.db.ActivityStatus.COMPLETED,
                    distanceMeters = 0.0,
                    movingTimeMs = 0,
                    elapsedTimeMs = 0,
                    avgSpeedKmh = null,
                    maxSpeedKmh = null,
                    elevationGainM = null,
                    elevationLossM = null
                ),
                trackPoints = emptyList(),
                onBack = { navController.popBackStack() }
            )
        }
        }
    } // Scaffold (content lambda closes here)
} // NyasarNavHost
