package com.nyasar.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.data.db.RouteEntity
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.recording.RecordingStatus
import com.nyasar.app.ui.components.EmptyState
import com.nyasar.app.ui.components.NyasarMapView
import com.nyasar.app.ui.components.ZoomControls
import com.nyasar.app.ui.components.pressScale
import com.nyasar.app.ui.recording.RecordingViewModel
import com.nyasar.app.ui.recording.RecoveryDialog
import com.nyasar.app.ui.theme.NyasarRadius
import kotlin.math.roundToInt

/**
 * P3: the map is the home screen (spec Section 10) -- not a dashboard with a
 * list in front of it. Existing entry points (route list, import, history,
 * settings) are preserved exactly (spec Section 19 rule 8: "pertahankan fitur
 * existing"), just relocated: the route list moves into a bottom sheet
 * reachable from the search bar instead of being the default view.
 *
 * Recording flow entry point (Start Activity, route-less) is unchanged --
 * spec Section 13: "gunakan flow existing", not a second recording flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    recordingViewModel: RecordingViewModel = viewModel(),
    waypointViewModel: com.nyasar.app.ui.waypoint.WaypointViewModel = viewModel(),
    pendingImportUri: Uri? = null,
    onImportConsumed: () -> Unit = {},
    // PART 4: "Lihat di Peta" from OfflineMapsScreen. One-shot — mirrors
    // pendingImportUri/onImportConsumed immediately above (same file, same
    // established "pending value + consumed callback" idiom for a one-time
    // cross-screen event) rather than a new pattern. Null on every normal
    // Home open, so default/normal Home behavior is untouched unless this
    // is explicitly supplied.
    pendingFocusBounds: org.maplibre.android.geometry.LatLngBounds? = null,
    onFocusBoundsConsumed: () -> Unit = {},
    onOpenRoute: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onStartRecording: () -> Unit = {},
    onResumeRecording: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDrawRoute: () -> Unit = {}
) {
    val routes by viewModel.routes.collectAsState(initial = emptyList())
    val importError by viewModel.importError.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val followMode by viewModel.followMode.collectAsState()
    val rotateWithHeading by viewModel.rotateWithHeading.collectAsState()
    val provider by viewModel.provider.collectAsState()
    val styleVariant by viewModel.styleVariant.collectAsState()
    val selectedBasemap by viewModel.selectedBasemap.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    // Task 4 gap (P3 audit): recovery was previously only checked once the
    // user manually opened RecordingScreen, so a process kill while
    // recording never surfaced anything unless the user happened to
    // navigate there themselves. Home is where the app actually reopens
    // to (spec: "MAP ADALAH HOME"), so the check belongs here — reuses
    // RecordingViewModel.checkForRecovery()/RecoveryDialog exactly as
    // RecordingScreen does, no duplicated recovery logic.
    val recoveryCandidate by recordingViewModel.recoveryCandidate.collectAsState()
    LaunchedEffect(Unit) { recordingViewModel.checkForRecovery() }

    // P3J §1/§6: live status of whatever recording is currently running
    // (or not) — see the bottom-action-button block below for why Home
    // needs this. Same service-bound state RecordingScreen itself reads,
    // not a separate signal that could drift out of sync with it.
    val recordingUiState by recordingViewModel.uiState.collectAsState()

    // P3E2: user-created waypoints, tap-to-add on the map.
    val userWaypoints by waypointViewModel.waypoints.collectAsState()
    val pendingWaypointTap by waypointViewModel.pendingTap.collectAsState()
    val selectedWaypoint by waypointViewModel.selectedWaypoint.collectAsState()
    val editingWaypoint by waypointViewModel.editingWaypoint.collectAsState()

    var showRoutesSheet by remember { mutableStateOf(false) }
    var showBasemapSheet by remember { mutableStateOf(false) }
    var mapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var mapBearing by remember { mutableStateOf(0f) }
    // Waymarked Trails overlays — shared persisted state (ViewModel reads the
    // same DataStore row Recording/RoutePreview use). Required now that all 3
    // map screens borrow ONE MapView: a per-screen set would let whichever
    // screen mounts last strip the others' overlays from the single loaded
    // style. Previously this was a local `remember` (and before that, missing
    // wiring entirely — the checkboxes did nothing on this screen).
    val activeOverlays by viewModel.activeOverlays.collectAsState()
    // "Jalur Saya" overlay: app-wide persisted switch + reactive lines from
    // the same repository the Library reads. The lines flow only parses GPX
    // while the switch is ON (see HomeViewModel.myRouteLines).
    val myRoutesEnabled by viewModel.myRoutesOverlayEnabled.collectAsState()
    val myRouteLines by viewModel.myRouteLines.collectAsState()

    val pickGpx = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importGpx(it) }
    }

    // A GPX opened from a file manager or shared into the app (spec section
    // 5: "Open GPX melalui Android Share/Open With") lands here once -- after
    // import we jump straight to Route Preview instead of leaving the user
    // to find it in the list themselves.
    LaunchedEffect(pendingImportUri) {
        pendingImportUri?.let { uri ->
            viewModel.importGpx(uri) { routeId ->
                onImportConsumed()
                onOpenRoute(routeId)
            }
        }
    }

    // Permission was already requested app-wide in MainActivity; this just
    // starts the GPS stream once it's actually granted (works whether the
    // grant happened before this screen composed or arrives moments later).
    LaunchedEffect(Unit) {
        viewModel.startLocationUpdatesIfPermitted()
    }

    val hasLocationPermission = viewModel.hasLocationPermission()

    // PART 4: apply an incoming "focus this area" request once. Uses the
    // exact same MapLibre call NyasarMapView's own initial-focus effect
    // uses (CameraUpdateFactory.newLatLngBounds, same 40px padding) — not a
    // second focus mechanism, just invoked here instead of inside
    // NyasarMapView's style-setup effect, because that effect is
    // deliberately NOT re-keyed on focusBounds (see its own comment on why
    // — re-keying it risks the same camera feedback loop OfflineDownloadScreen
    // already had to work around). Waits for mapInstance so a request that
    // arrives before the map finishes its first frame isn't dropped.
    // Explicitly calls onUserPanned() first (spec: "JANGAN mengaktifkan
    // follow mode saat fokus ke area offline") — reuses the same function
    // an actual manual pan already calls, rather than adding new follow-
    // state; Home then sits statically on the focused area exactly as it
    // would after any other manual pan.
    LaunchedEffect(pendingFocusBounds, mapInstance) {
        val bounds = pendingFocusBounds ?: return@LaunchedEffect
        val map = mapInstance ?: return@LaunchedEffect
        viewModel.onUserPanned()
        map.moveCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds, 40)
        )
        onFocusBoundsConsumed()
    }

    Box(Modifier.fillMaxSize()) {
        // --- MAP: fills the whole screen, everything else floats on top ---
        val userLatLng = currentLocation?.let {
            org.maplibre.android.geometry.LatLng(it.lat, it.lon)
        }
        NyasarMapView(
            modifier = Modifier.fillMaxSize(),
            provider = provider,
            styleVariant = styleVariant,
            basemapEntry = selectedBasemap,
            // Opt into the shared MapView so Home ↔ RoutePreview ↔ Recording
            // reuse one GL surface/tile cache/style instead of rebuilding the
            // map on every screen switch. Basemap/overlays state is persisted
            // in SettingsRepository, so it's identical on all 3 screens.
            shared = true,
            activeOverlays = activeOverlays,
            // "Jalur Saya" overlay — app-wide persisted flag + reactive
            // line data; no active route on Home, so every line renders
            // inactive (gray/dashed).
            myRoutes = myRouteLines,
            track = emptyList(),
            waypoints = emptyList(),
            userWaypoints = userWaypoints,
            onUserWaypointClick = { id ->
                waypointViewModel.selectWaypoint(userWaypoints.firstOrNull { it.id == id })
            },
            onMapLongPress = { lat, lon ->
                waypointViewModel.onMapLongPress(lat, lon, currentLocation?.elevationM)
            },
            userLocation = userLatLng,
            userHeadingDeg = currentLocation?.bearingDeg,
            accuracyMeters = currentLocation?.accuracyMeters,
            followUser = followMode,
            rotateWithHeading = rotateWithHeading,
            onUserGesture = viewModel::onUserPanned,
            onMapReady = { mapInstance = it },
            onBearingChanged = { mapBearing = it }
        )

        // --- TOP: Full-width dark bar with logo + search (matches bottom bar) ---
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPaddingCompat()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Nyasar logo badge — the brand pin logo (mipmap PNG
                // densities) on its original pale-cream backdrop. The
                // container color is deliberately FIXED across light/dark
                // mode instead of following colorScheme: the logo artwork
                // inside (gray mountains, compass, green pin) was designed
                // against this pale #E6EBE5 background, so a dark-mode-
                // tinted container would destroy the artwork's contrast
                // rather than help it. Pale badge on the dark-theme header
                // surface = strongest separation in dark mode; in light
                // mode it reads as a subtle tonal step against the near-
                // white surface. Mirrors the launcher icon decision (pale
                // background there too, both themes). Same rounded-badge
                // container shape/position the "Nyasar" text badge used —
                // nothing else in the header moves.
                //
                // Sizing: the launcher foreground PNG is an adaptive-icon
                // asset — its artwork only fills the center ~65% of the
                // canvas (the rest is baked-in safe-zone padding). At the
                // old 28dp the VISIBLE artwork was only ~18dp, far too
                // small to read next to the search bar. 40dp canvas keeps
                // the same in-canvas proportions as the launcher icon while
                // making the visible artwork ~26dp — on par with the
                // header's text/icon scale.
                Surface(
                    shape = RoundedCornerShape(NyasarRadius.sm),
                    color = Color(0xFFE6EBE5)
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .padding(4.dp)
                            .size(40.dp)
                    )
                }
                // Search bar
                Surface(
                    modifier = Modifier.weight(1f).clickable { showRoutesSheet = true },
                    shape = RoundedCornerShape(NyasarRadius.pill),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (routes.isEmpty()) stringResource(R.string.search_route) else stringResource(R.string.search_route_count, routes.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        importError?.let {
            // Toast-style banners share one recipe now: shared radius token
            // + the standard fade-and-rise entrance, so every screen's
            // floating banner behaves identically.
            com.nyasar.app.ui.components.AnimatedAppear(
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    modifier = Modifier
                        .padding(top = 80.dp, start = 16.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(com.nyasar.app.ui.theme.NyasarRadius.sm)
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (!hasLocationPermission) {
            // Spec Section 12: "jika GPS tidak tersedia, tampilkan state
            // yang jelas. Jangan crash." -- covers permission-denied too,
            // not just a hardware/signal outage.
            // P3J §10 fix: this used to be plain, unclickable text telling
            // the user to go open system settings themselves with no way
            // to get there from the app ("berikan jalur retry/settings").
            // Now the whole banner is a tap target that opens this app's
            // exact permission page.
            val context = androidx.compose.ui.platform.LocalContext.current
            val bannerInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            com.nyasar.app.ui.components.AnimatedAppear(
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    modifier = Modifier
                        .padding(top = 80.dp, start = 16.dp, end = 16.dp)
                        .pressScale(bannerInteraction)
                        .clickable(
                            interactionSource = bannerInteraction,
                            indication = null
                        ) {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(com.nyasar.app.ui.theme.NyasarRadius.sm)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.gps_permission_banner),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.tap_to_open_settings),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else if (currentLocation == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp, start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(NyasarRadius.sm)
            ) {
                Text(
                    stringResource(R.string.gps_searching),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            // Real accuracy from the last fix (spec P3A GPS UX: "GPS
            // accuracy") — no synthetic/estimated number, straight from
            // GpsFix.accuracyMeters same as NavigationScreen's stat bar.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(NyasarRadius.sm)
            ) {
                Text(
                    "GPS \u00b1${currentLocation?.accuracyMeters?.roundToInt() ?: 0} m",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- RIGHT: grouped vertical stack (Layer / Lokasi / Gambar Rute),
        // positioned above bottom bar with matching padding ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // (entrance animation lives on each RoundIconButton below —
            // staggered fade-and-rise, same family as banners/cards)
            Box {
                RoundIconButton(icon = Icons.Default.Layers, contentDescription = stringResource(R.string.map_layer_cd)) {
                    showBasemapSheet = true
                }
            }
            RoundIconButton(
                icon = if (rotateWithHeading) Icons.Default.Navigation else Icons.Default.MyLocation,
                contentDescription = if (rotateWithHeading) stringResource(R.string.heading_up_mode_cd) else stringResource(R.string.go_to_location_cd),
                tint = if (followMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                onClick = { viewModel.centerOnLocation() }
            )
            RoundIconButton(
                icon = Icons.Default.Edit,
                contentDescription = stringResource(R.string.draw_route_cd),
                onClick = onOpenDrawRoute
            )
        }

        // Compass — top-end, pushed down below the search bar (was
        // overlapping it before) using the same real status-bar inset as
        // the search row itself, not a guessed flat offset.
        com.nyasar.app.ui.components.CompassButton(
            bearingDeg = mapBearing,
            onClick = { mapInstance?.let { it.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.bearingTo(0.0)) } },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPaddingCompat()
                .padding(end = 12.dp, top = 84.dp)
                .size(48.dp)
        )

        // Zoom +/- removed per feedback — recenter + pinch-to-zoom cover
        // this, the buttons were just extra clutter.

        // --- BOTTOM: live recording pill (tap = back into the session) ---
        // AnimatedContent between hidden/shown so the pill slides up/fades
        // in when a recording starts and back out when it ends, instead of
        // popping. Slide distance is small — it sits at the bottom edge.
        androidx.compose.animation.AnimatedVisibility(
            visible = recordingUiState.status == RecordingStatus.RECORDING ||
                recordingUiState.status == RecordingStatus.PAUSED,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = androidx.compose.animation.core.tween(
                    com.nyasar.app.ui.theme.NyasarMotion.BASE_MS,
                    easing = com.nyasar.app.ui.theme.NyasarMotion.EmphasizedDecelerate
                )
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(
                    com.nyasar.app.ui.theme.NyasarMotion.BASE_MS,
                    easing = com.nyasar.app.ui.theme.NyasarMotion.EmphasizedDecelerate
                )
            ),
            exit = androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = androidx.compose.animation.core.tween(
                    com.nyasar.app.ui.theme.NyasarMotion.BASE_MS,
                    easing = com.nyasar.app.ui.theme.NyasarMotion.EmphasizedAccelerate
                )
            ) + androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(
                    com.nyasar.app.ui.theme.NyasarMotion.BASE_MS,
                    easing = com.nyasar.app.ui.theme.NyasarMotion.EmphasizedAccelerate
                )
            )
        ) {
            val pillInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .pressScale(pillInteraction)
                    .clickable(
                        interactionSource = pillInteraction,
                        indication = null,
                        onClick = onResumeRecording
                    ),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(com.nyasar.app.ui.theme.NyasarRadius.lg),
                shadowElevation = 2.dp
            ) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Live dot: pulses while recording, steady when
                        // paused — small, but it's what makes the pill feel
                        // alive (and communicates state without text).
                        RecordingPulseDot(
                            active = recordingUiState.status == RecordingStatus.RECORDING,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (recordingUiState.status == RecordingStatus.PAUSED) stringResource(R.string.recording_paused) else stringResource(R.string.recording_active),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "%.2f km".format(recordingUiState.distanceMeters / 1000.0),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.continue_recording),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }

    if (showBasemapSheet) {
        com.nyasar.app.ui.components.BasemapPickerSheet(
            selected = selectedBasemap,
            onSelect = { entry ->
                viewModel.setBasemap(entry)
                showBasemapSheet = false
            },
            activeOverlays = activeOverlays,
            onToggleOverlay = { overlay ->
                viewModel.toggleOverlay(overlay)
            },
            myRoutesEnabled = myRoutesEnabled,
            onToggleMyRoutes = { viewModel.setMyRoutesOverlayEnabled(!myRoutesEnabled) },
            onDismiss = { showBasemapSheet = false }
        )
    }

    if (showRoutesSheet) {
        RoutesBottomSheet(
            routes = routes,
            searchQuery = searchQuery,
            onSearchQueryChange = viewModel::setSearchQuery,
            onImportClick = { pickGpx.launch("*/*") },
            onOpenRoute = { routeId ->
                showRoutesSheet = false
                onOpenRoute(routeId)
            },
            onDeleteRoute = viewModel::deleteRoute,
            onDismiss = { showRoutesSheet = false }
        )
    }

    recoveryCandidate?.let { candidate ->
        RecoveryDialog(
            activityName = candidate.name,
            onResume = {
                recordingViewModel.resumeRecovered()
                // Jump straight to the live recording UI rather than
                // resuming silently behind Home — user explicitly asked
                // to continue, they should see distance/time ticking.
                // Distinct callback from onStartRecording: the service is
                // already running (resumeRecovered just started it), so
                // this must land on a screen that only observes, not one
                // that calls startRecording() again on top of it.
                onResumeRecording()
            },
            onStopAndSave = recordingViewModel::stopAndSaveRecovered,
            onDiscard = recordingViewModel::discardRecovered
        )
    }

    // P3E2: Add Waypoint sheet, opened by a long-press on the map above.
    pendingWaypointTap?.let { tap ->
        com.nyasar.app.ui.waypoint.WaypointFormSheet(
            title = stringResource(R.string.new_waypoint),
            initialName = "",
            initialCategory = com.nyasar.app.data.db.WaypointCategory.POI,
            initialNote = "",
            lat = tap.lat,
            lon = tap.lon,
            elevationM = tap.elevationM,
            onDismiss = waypointViewModel::dismissPendingTap,
            onSave = { name, category, note -> waypointViewModel.confirmAdd(name, category, note) }
        )
    }

    // P3E2: tapping an existing user waypoint marker opens this detail
    // sheet (name/category/elevation/koordinat/note/jarak dari user).
    selectedWaypoint?.let { wp ->
        val distance = currentLocation?.let {
            com.nyasar.app.navigation.GeoMath.distanceMeters(
                com.nyasar.app.navigation.LatLng(it.lat, it.lon),
                com.nyasar.app.navigation.LatLng(wp.lat, wp.lon)
            )
        }
        com.nyasar.app.ui.waypoint.WaypointDetailSheet(
            waypoint = wp,
            distanceFromUserMeters = distance,
            onDismiss = { waypointViewModel.selectWaypoint(null) },
            onEdit = { waypointViewModel.startEditing(wp) },
            onDelete = { waypointViewModel.deleteWaypoint(wp) }
        )
    }

    // P3E2: Edit sheet for an existing waypoint, reuses the same form as Add.
    editingWaypoint?.let { wp ->
        val category = com.nyasar.app.data.db.WaypointCategory.fromStorageValue(wp.category)
        com.nyasar.app.ui.waypoint.WaypointFormSheet(
            title = stringResource(R.string.edit_waypoint),
            initialName = wp.name,
            initialCategory = category,
            initialNote = wp.note ?: "",
            lat = wp.lat,
            lon = wp.lon,
            elevationM = wp.elevationM,
            onDismiss = waypointViewModel::dismissEditing,
            onSave = { name, cat, note -> waypointViewModel.confirmEdit(name, cat, note) },
            onDelete = { waypointViewModel.deleteWaypoint(wp) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutesBottomSheet(
    routes: List<RouteEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onImportClick: () -> Unit,
    onOpenRoute: (String) -> Unit,
    onDeleteRoute: (RouteEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = remember(routes, searchQuery) {
        if (searchQuery.isBlank()) routes
        else routes.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    var pendingDelete by remember { mutableStateOf<RouteEntity?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = com.nyasar.app.ui.theme.NyasarContentWidth.sheetMaxWidth).fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.route_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                // Import GPX access -- spec Section 14: "Home Map harus tetap
                // menyediakan cara [Import GPX]". Same picker/parser as before.
                FilledTonalIconButton(onClick = onImportClick) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.import_gpx))
                }
            }
            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Hiking,
                    title = if (routes.isEmpty()) stringResource(R.string.empty_routes_title)
                    else stringResource(R.string.empty_search_title),
                    description = if (routes.isEmpty()) stringResource(R.string.empty_routes_desc)
                    else stringResource(R.string.empty_search_desc),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered, key = { it.id }) { route ->
                        RouteRow(
                            route = route,
                            onClick = { onOpenRoute(route.id) },
                            onDeleteClick = { pendingDelete = route }
                        )
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // Route Library gap: RouteRepository.delete() existed, no UI ever called
    // it -- a route could be imported but never removed (spec section 15:
    // "Delete" is a required Route Library action).
    pendingDelete?.let { route ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_route)) },
            text = { Text(stringResource(R.string.delete_route_message, route.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRoute(route)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun RouteRow(
    route: RouteEntity,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val rowInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    ListItem(
        headlineContent = { Text(route.name) },
        supportingContent = {
            val km = route.distanceMeters / 1000.0
            val gain = route.elevationGainM?.roundToInt()
            Text(
                buildString {
                    append("%.1f km".format(km))
                    if (gain != null) append(" - ↑${gain} m")
                    append(" - ${route.waypointCount} waypoints")
                }
            )
        },
        trailingContent = {
            Row {
                val context = androidx.compose.ui.platform.LocalContext.current
                IconButton(onClick = { shareRouteFile(context, route) }) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_gpx_cd))
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_route_cd))
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(rowInteraction)
            .clickable(interactionSource = rowInteraction, indication = null, onClick = onClick)
    )
}

/** Route Library "Export/Share" action (spec section 15). File already
 *  lives on disk from import -- just hand it to the share sheet. */
private fun shareRouteFile(context: android.content.Context, route: RouteEntity) {
    val file = java.io.File(route.localGpxFilePath)
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_route_intent, route.name)))
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Shared map-control recipe: theme surface over any basemap, the
    // app-wide CircleShape, one shared elevation pair, the standard press
    // spring (inside pressScale), and the shared fade-and-rise entrance.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    com.nyasar.app.ui.components.AnimatedAppear(modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = com.nyasar.app.ui.theme.NyasarElevation.mapControlTonal,
            shadowElevation = com.nyasar.app.ui.theme.NyasarElevation.mapControlShadow,
            modifier = Modifier
                .size(48.dp)
                .pressScale(interaction)
        ) {
            IconButton(
                onClick = onClick,
                interactionSource = interaction
            ) {
                Icon(icon, contentDescription = contentDescription, tint = tint)
            }
        }
    }
}

/**
 * Live-recording pulse dot — a small colored dot that gently pulses while a
 * recording is active and holds steady when paused. Used by Home's live
 * recording pill; kept here (single call site) until a second consumer
 * appears, then it moves to ui/components.
 */
@Composable
private fun RecordingPulseDot(color: Color, active: Boolean, modifier: Modifier = Modifier) {
    val infinite = androidx.compose.runtime.rememberInfiniteTransition(label = "pulseDot")
    val alpha by infinite.animateFloat(
        initialValue = if (active) 1f else 0.4f,
        targetValue = if (active) 0.35f else 0.4f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(10.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape)
    )
}

/** Small shim so this file doesn't need a WindowInsets import wired through
 *  every call site -- fixed padding is fine here (spec: "jangan membuat UI
 *  final terlalu kompleks dulu"); true insets handling can follow later. */
@Composable
private fun Modifier.statusBarsPaddingCompat(): Modifier = this.windowInsetsPadding(WindowInsets.statusBars)
