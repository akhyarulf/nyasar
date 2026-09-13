package com.nyasar.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.db.WaypointCategory
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.gpx.model.GpxWaypoint
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.ui.components.AnimatedAppear
import com.nyasar.app.ui.components.BasemapPickerSheet
import com.nyasar.app.ui.components.CompassButton
import com.nyasar.app.ui.components.ElevationProfile
import com.nyasar.app.ui.components.NyasarMapView
import com.nyasar.app.ui.components.pressScale
import com.nyasar.app.ui.theme.NyasarElevation
import com.nyasar.app.ui.theme.NyasarRadius
import com.nyasar.app.ui.waypoint.WaypointCrosshairScreen
import com.nyasar.app.ui.waypoint.WaypointFormSheet
import com.nyasar.app.ui.waypoint.rememberCrosshairCameraState
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * Route Viewer — Komoot/ActivityDetail-style layout with a Wikiloc-style
 * full-screen map mode:
 *
 * - MAP ON TOP (collapsed): a fixed-height map block (~38% of the screen,
 *   matching the reference screenshot's balance). TWO entry points, ONE
 *   destination: the expand button (bottom-right control stack, launcher
 *   icon per the project's own Recording-stats convention) OR tapping
 *   anywhere on the collapsed map opens FULL-SCREEN MAP MODE. Full-screen
 *   is a separate full-size overlay Box (the established project pattern
 *   for over-the-page viewers: WaypointCrosshairScreen, PhotoViewer) that
 *   re-hosts the same map state with the SAME control set, plus the
 *   floating elevation card ("ketika map penuh baru terlihat elevasinya").
 *   It closes via its own back arrow or the system back gesture
 *   (BackHandler) — returning to Route Detail with all state intact.
 * - DATA BELOW: a scrollable section styled like ActivityDetailScreen —
 *   title, stat tiles (same soft-tile visual), waypoint list with category
 *   icons, offline-map entry — so both detail screens read as siblings.
 * - PINNED CTA: Start Navigation lives in a bottom bar (never scrolls away),
 *   padded for the navigation bar like Recording's controls. Stats tiles,
 *   waypoint list and Start Navigation belong ONLY to the collapsed Route
 *   Detail — full-screen mode deliberately omits them.
 * - Fully localized (strings.xml only) and theme-tokenized
 *   (NyasarRadius/NyasarElevation/NyasarMotion + shared RoundIconButton
 *   recipe with AnimatedAppear + pressScale), responsive on small and large
 *   phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePreviewScreen(
    routeId: String,
    viewModel: RoutePreviewViewModel = viewModel(),
    onStartNavigation: (String) -> Unit,
    onDownloadOfflineMap: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(routeId) { viewModel.load(routeId) }
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val waypointViewModel: com.nyasar.app.ui.waypoint.WaypointViewModel = viewModel()
    // v7: DB rows for this route (GPX-imported + user pins linked here) and
    // independent pins — the two extra marker sources this screen feeds the
    // map alongside the parsed-GPX layer.
    val dbWaypoints by viewModel.dbWaypoints.collectAsState()
    val userWaypoints by waypointViewModel.independentWaypoints.collectAsState()
    val editingWaypointState by waypointViewModel.editingWaypoint.collectAsState()
    LaunchedEffect(routeId) {
        waypointViewModel.setContext(com.nyasar.app.ui.waypoint.WaypointContext.Route(routeId))
    }

    // GPS state collection (mirrors HomeScreen pattern)
    val currentLocation by viewModel.currentLocation.collectAsState()
    val followMode by viewModel.followMode.collectAsState()
    val rotateWithHeading by viewModel.rotateWithHeading.collectAsState()
    // Basemap: shared persisted selection (same DataStore row Home and
    // Recording read/write).
    val currentBasemap by viewModel.selectedBasemap.collectAsState()
    val currentProvider by viewModel.provider.collectAsState()
    val activeOverlays by viewModel.activeOverlays.collectAsState()
    val myRoutesEnabled by viewModel.myRoutesOverlayEnabled.collectAsState()
    // Data-section toggles (picker sheet) — waypoint pins gate, app-wide.
    val waypointsVisible by viewModel.waypointsVisible.collectAsState()
    val myRouteLines by viewModel.myRouteLines.collectAsState()
    val offlineOverlayEnabled by viewModel.offlineOverlayEnabled.collectAsState()
    val offlineAreas by viewModel.offlineAreas.collectAsState()

    // Start location updates once permission is granted
    LaunchedEffect(Unit) { viewModel.startLocationUpdatesIfPermitted() }

    var selectedWaypoint by remember { mutableStateOf<GpxWaypoint?>(null) }
    var selectedDbWaypoint by remember { mutableStateOf<WaypointEntity?>(null) }
    var showCrosshair by remember { mutableStateOf(false) }
    // Highlight marker position when user scrubs the elevation chart
    var highlightLatLng by remember { mutableStateOf<LatLng?>(null) }

    // ---------------------------------------------------------------------------
    // Full-screen map mode (Wikiloc pattern). mapExpanded is the single source
    // of truth for BOTH entry points — the expand control button and a tap
    // anywhere on the collapsed map. While it's true, the screen draws a
    // full-size overlay Box AFTER (on top of) the collapsed layout; the
    // collapsed layout keeps composing underneath unchanged, so closing
    // restores Route Detail exactly as the user left it. The system back
    // gesture and the overlay's own back arrow both just clear the flag.
    // ---------------------------------------------------------------------------
    var mapExpanded by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = mapExpanded) { mapExpanded = false }
    // Two live MapView instances (collapsed + full-screen overlay): each
    // NyasarMapView(shared = false) owns a real GL surface, so when the
    // overlay mounts it must NOT steal the shared `activeMap` routing —
    // after close, the collapsed map is the one still alive.
    var collapsedMapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var fullscreenMapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    val activeMap = if (mapExpanded) fullscreenMapInstance else collapsedMapInstance
    // Camera handoff: snapshotted from the collapsed map the moment
    // full-screen opens, applied to the overlay map on its onMapReady (which
    // fires after the pipeline's own track-bounds fit, so this wins).
    var fullscreenStartCamera by remember { mutableStateOf<org.maplibre.android.camera.CameraPosition?>(null) }
    var mapBearing by remember { mutableStateOf(0f) }
    var showBasemapSheet by remember { mutableStateOf(false) }
    var currentStyleVariant by remember { mutableStateOf(StyleVariant.OUTDOOR) }
    // Elevation card height (full-screen mode) — right-side controls lift above it.
    var elevationCardHeightDp by remember { mutableStateOf(0.dp) }

    // v7 "layar terakhir": the crosshair overlay reads the ACTIVE map's live
    // camera, so it opens at exactly the last position+zoom the user was
    // viewing — no teleport, no re-zoom.
    val crosshairTarget by rememberCrosshairCameraState(activeMap, showCrosshair)

    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val collapsedMapHeight = screenHeightDp * 0.38f

    // Waypoint tap plumbing shared by both map hosts (values captured once).
    val gpxWaypointsForMap = state.waypoints.filter { gpxWp ->
        dbWaypoints.none { db ->
            db.source == WaypointEntity.SOURCE_GPX &&
                db.name == gpxWp.name &&
                kotlin.math.abs(db.lat - gpxWp.lat) <= WaypointEntity.GPX_COORD_MATCH_DEGREES &&
                kotlin.math.abs(db.lon - gpxWp.lon) <= WaypointEntity.GPX_COORD_MATCH_DEGREES
        }
    }
    val allDbWaypoints = dbWaypoints + userWaypoints

    Column(Modifier.fillMaxSize()) {
        // =============================== MAP BLOCK ===============================
        Box(
            Modifier
                .fillMaxWidth()
                .height(collapsedMapHeight)
        ) {
            val userLatLng = currentLocation?.let { LatLng(it.lat, it.lon) }

            // Tap-anywhere-on-map opens full-screen (Wikiloc pattern), via
            // NyasarMapView's own onMapClick hook: its listeners fire for
            // taps the map itself didn't treat as a marker click (marker
            // taps route to the waypoint handlers instead), and pans/zooms
            // never count as clicks. Using the map-level hook avoids relying
            // on Compose clickable above an AndroidView touch surface.
            NyasarMapView(
                modifier = Modifier.fillMaxSize(),
                onMapClick = { _, _ ->
                    // Entry point (b): tap anywhere on the collapsed map.
                    fullscreenStartCamera = collapsedMapInstance?.cameraPosition
                    mapExpanded = true
                },
                provider = currentProvider,
                styleVariant = currentStyleVariant,
                basemapEntry = currentBasemap,
                // BUG FIX ("pin gak muncul di Route Viewer padahal di Activity
                // Detail muncul"): this screen uses a PRIVATE instance via the
                // full setStyle pipeline — the exact pipeline proven to render
                // pins on-device (see the longer historical note in git).
                shared = false,
                activeOverlays = activeOverlays,
                // "Jalur Saya" overlay — this screen's route renders solid
                // accent; other saved routes render gray/dashed when on.
                myRoutes = myRouteLines,
                // Downloaded-areas overlay (Settings → Offline): green =
                // complete for the active basemap's style, gray = downloading.
                offlineAreas = if (offlineOverlayEnabled) offlineAreas else emptyList(),
                activeRouteId = routeId,
                track = state.track,
                // This screen always shows the PLANNED route — keep the app
                // blue. The full-load heuristic without an actualTrack reads
                // the list as a walked path (green, ActivityDetail semantics)
                // which was never this screen's look.
                trackColorOverride = "#42A5F5",
                // v7 merge filter: the GPX layer keeps only waypoints WITHOUT
                // a DB counterpart for this route — every merged one is
                // already in dbWaypoints, without this each imported waypoint
                // would render twice after the merge feature.
                waypoints = gpxWaypointsForMap,
                userWaypoints = allDbWaypoints,
                waypointsVisible = waypointsVisible,
                highlightPoint = highlightLatLng,
                onWaypointClick = { selectedWaypoint = it },
                onUserWaypointClick = { id ->
                    allDbWaypoints.firstOrNull { it.id == id }?.let { selectedDbWaypoint = it }
                },
                onMapReady = {
                    collapsedMapInstance = it
                    mapBearing = it.cameraPosition.bearing.toFloat()
                },
                onBearingChanged = { mapBearing = it },
                userLocation = userLatLng,
                userHeadingDeg = currentLocation?.bearingDeg,
                accuracyMeters = currentLocation?.accuracyMeters,
                followUser = followMode,
                rotateWithHeading = rotateWithHeading,
                onUserGesture = viewModel::onUserPanned
            )

            // Follow the elevation-chart scrub with a short camera nudge
            LaunchedEffect(highlightLatLng) {
                val map = activeMap ?: return@LaunchedEffect
                val point = highlightLatLng ?: return@LaunchedEffect
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(point, map.cameraPosition.zoom),
                    200
                )
            }

            // --- Floating top pills (status-bar aware)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 8.dp)
            ) {
                RoundIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    onClick = onBack
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.gpxFilePath?.let { path ->
                    RoundIconButton(
                        icon = Icons.Default.Share,
                        contentDescription = stringResource(R.string.share_gpx),
                        onClick = { shareRouteGpx(context, path, state.name ?: "route") }
                    )
                }
                RoundIconButton(
                    icon = Icons.Default.CloudDownload,
                    contentDescription = stringResource(R.string.prepare_offline_cd),
                    onClick = { onDownloadOfflineMap(routeId) }
                )
            }

            CompassButton(
                bearingDeg = mapBearing,
                onClick = { activeMap?.let { it.animateCamera(CameraUpdateFactory.bearingTo(0.0)) } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 76.dp)
                    .size(48.dp)
            )

            // --- Right-side controls. The expand button is one of TWO ways
            // --- into full-screen mode (the other: tapping the map itself).
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RoundIconButton(icon = Icons.Default.Layers, contentDescription = stringResource(R.string.map_layer_cd)) {
                    showBasemapSheet = true
                }
                RoundIconButton(icon = Icons.Default.Place, contentDescription = stringResource(R.string.add_waypoint_cd)) {
                    showCrosshair = true
                }
                RoundIconButton(
                    icon = if (rotateWithHeading) Icons.Default.Navigation else Icons.Default.MyLocation,
                    contentDescription = if (rotateWithHeading) stringResource(R.string.heading_up_mode_cd) else stringResource(R.string.go_to_location_cd),
                    tint = if (followMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = { viewModel.centerOnLocation() }
                )
                // Launch full-screen map mode (same state the map-tap sets).
                // Icon follows the project's own expand convention (Recording
                // stats use OpenInFull for the same "grow this view" action);
                // UnfoldMore/UnfoldLess described an in-place resizer this
                // button no longer is.
                RoundIconButton(
                    icon = Icons.Default.OpenInFull,
                    contentDescription = stringResource(R.string.map_expand_cd),
                    onClick = {
                        // Entry point (a): the launcher button. Same snapshot
                        // + same state as the map-tap entry — one destination.
                        fullscreenStartCamera = collapsedMapInstance?.cameraPosition
                        mapExpanded = true
                    }
                )
            }
        }

        // ============================= DATA SECTION ==============================
        // Scrollable like ActivityDetail: title → stat tiles → waypoint list →
        // offline entry. Weight(1f) takes whatever the map block leaves.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                state.name ?: stringResource(R.string.default_route_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))

            // Stat tiles — same soft-tile visual as ActivityDetail's StatsGrid
            // (label over value), a stable 2-column grid: weighted Row pairs so
            // tiles share width on any screen size.
            val tiles = buildList {
                add(stringResource(R.string.stat_distance) to "%.1f km".format(state.distanceKm))
                state.elevationGainM?.let { add(stringResource(R.string.elevation_gain) to "↑ ${it.roundToInt()} m") }
                state.elevationLossM?.let { add(stringResource(R.string.stat_elev_loss) to "↓ ${it.roundToInt()} m") }
                state.highestElevationM?.let { add(stringResource(R.string.stat_highest_point) to "${it.roundToInt()} m") }
            }
            tiles.chunked(2).forEach { rowTiles ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowTiles.forEach { (label, value) ->
                        StatTile(label, value, Modifier.weight(1f))
                    }
                    if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }

            // Waypoint list (DB rows: GPX-imported + user pins linked here).
            // Tapping opens the same detail sheet as tapping the map marker.
            if (dbWaypoints.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.waypoints, dbWaypoints.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                dbWaypoints.forEach { wp ->
                    WaypointListRow(
                        waypoint = wp,
                        onClick = { selectedDbWaypoint = wp }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { onDownloadOfflineMap(routeId) }) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.prepare_offline))
            }
            Spacer(Modifier.height(16.dp))
        }

        // ============================ PINNED CTA BAR =============================
        // Never scrolls away (Komoot-style), padded above the gesture nav bar.
        Surface(tonalElevation = NyasarElevation.cardTonal) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = { onStartNavigation(routeId) },
                    shape = RoundedCornerShape(NyasarRadius.pill),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_nav), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    // ===================== FULL-SCREEN MAP MODE OVERLAY =====================
    // Drawn AFTER (so ON TOP of) the whole collapsed layout when active —
    // the same in-place overlay pattern as WaypointCrosshairScreen and the
    // photo viewer. Reuses the identical map state (same ViewModel flows,
    // same basemap/overlays/track) and the same control set; adds the
    // floating elevation card. Deliberately omits stat tiles, the waypoint
    // LIST, and Start Navigation — those stay Route-Detail-only (spec).
    if (mapExpanded) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val userLatLng = currentLocation?.let { LatLng(it.lat, it.lon) }

            NyasarMapView(
                modifier = Modifier.fillMaxSize(),
                provider = currentProvider,
                styleVariant = currentStyleVariant,
                basemapEntry = currentBasemap,
                shared = false,
                activeOverlays = activeOverlays,
                myRoutes = myRouteLines,
                offlineAreas = if (offlineOverlayEnabled) offlineAreas else emptyList(),
                activeRouteId = routeId,
                track = state.track,
                trackColorOverride = "#42A5F5",
                waypoints = gpxWaypointsForMap,
                userWaypoints = allDbWaypoints,
                waypointsVisible = waypointsVisible,
                highlightPoint = highlightLatLng,
                onWaypointClick = { selectedWaypoint = it },
                onUserWaypointClick = { id ->
                    allDbWaypoints.firstOrNull { it.id == id }?.let { selectedDbWaypoint = it }
                },
                onMapReady = { map ->
                    fullscreenMapInstance = map
                    // Camera handoff: the map pipeline just fit the track's
                    // bounds; re-apply the collapsed map's exact camera so
                    // full-screen opens where the user was looking.
                    fullscreenStartCamera?.let { keep ->
                        map.moveCamera(CameraUpdateFactory.newCameraPosition(keep))
                    }
                },
                onBearingChanged = { mapBearing = it },
                userLocation = userLatLng,
                userHeadingDeg = currentLocation?.bearingDeg,
                accuracyMeters = currentLocation?.accuracyMeters,
                followUser = followMode,
                rotateWithHeading = rotateWithHeading,
                onUserGesture = viewModel::onUserPanned
            )

            // Elevation-chart scrub keeps nudging the camera in full-screen.
            LaunchedEffect(highlightLatLng) {
                val map = activeMap ?: return@LaunchedEffect
                val point = highlightLatLng ?: return@LaunchedEffect
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(point, map.cameraPosition.zoom),
                    200
                )
            }

            // --- Floating top pills (status-bar aware). Back HERE means
            // --- "leave full-screen", not "leave Route Detail".
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 8.dp)
            ) {
                RoundIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    onClick = { mapExpanded = false }
                )
            }

            CompassButton(
                bearingDeg = mapBearing,
                onClick = { activeMap?.let { it.animateCamera(CameraUpdateFactory.bearingTo(0.0)) } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 76.dp)
                    .size(48.dp)
            )

            // --- Same right-side control set as the collapsed map, minus the
            // --- expand launcher (already full-screen). Controls lift above
            // --- the floating elevation card so nothing hides behind it.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp)
                    .offset(y = -(elevationCardHeightDp + 12.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RoundIconButton(icon = Icons.Default.Layers, contentDescription = stringResource(R.string.map_layer_cd)) {
                    showBasemapSheet = true
                }
                RoundIconButton(icon = Icons.Default.Place, contentDescription = stringResource(R.string.add_waypoint_cd)) {
                    showCrosshair = true
                }
                RoundIconButton(
                    icon = if (rotateWithHeading) Icons.Default.Navigation else Icons.Default.MyLocation,
                    contentDescription = if (rotateWithHeading) stringResource(R.string.heading_up_mode_cd) else stringResource(R.string.go_to_location_cd),
                    tint = if (followMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = { viewModel.centerOnLocation() }
                )
            }

            // --- Elevation card: floating at the bottom of the full map —
            // --- same ElevationSection chart as before (identical component,
            // --- same scrub→highlight camera link), just re-hosted here.
            AnimatedAppear(modifier = Modifier.align(Alignment.BottomCenter)) {
                val density = LocalDensity.current
                Surface(
                    shape = RoundedCornerShape(NyasarRadius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = NyasarElevation.cardTonal,
                    shadowElevation = NyasarElevation.floatingShadow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .onSizeChanged { with(density) { elevationCardHeightDp = it.height.toDp() } }
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        ElevationSection(state) { highlightLatLng = it }
                    }
                }
            }
        }
    }

    // ============================ OVERLAYS / SHEETS ============================
    selectedWaypoint?.let { wp ->
        WaypointDetailSheet(waypoint = wp, onDismiss = { selectedWaypoint = null })
    }

    if (showBasemapSheet) {
        BasemapPickerSheet(
            selected = currentBasemap,
            onSelect = { entry ->
                viewModel.setBasemap(entry)
                showBasemapSheet = false
            },
            activeOverlays = activeOverlays,
            onToggleOverlay = { overlay -> viewModel.toggleOverlay(overlay) },
            myRoutesEnabled = myRoutesEnabled,
            onToggleMyRoutes = { viewModel.setMyRoutesOverlayEnabled(!myRoutesEnabled) },
            waypointsVisible = waypointsVisible,
            onToggleWaypoints = { viewModel.setWaypointsVisible(!waypointsVisible) },
            offlineAreasEnabled = offlineOverlayEnabled,
            onToggleOfflineAreas = { viewModel.setOfflineOverlayEnabled(!offlineOverlayEnabled) },
            onDismiss = { showBasemapSheet = false }
        )
    }

    // v7 waypoint overlays (detail/crosshair/edit) — emitted after everything
    // so they draw on top of collapsed layout AND full-screen map alike.
    selectedDbWaypoint?.let { wp ->
        com.nyasar.app.ui.waypoint.WaypointDetailSheet(
            waypoint = wp,
            distanceFromUserMeters = currentLocation?.let {
                com.nyasar.app.navigation.GeoMath.distanceMeters(
                    com.nyasar.app.navigation.LatLng(it.lat, it.lon),
                    com.nyasar.app.navigation.LatLng(wp.lat, wp.lon)
                )
            },
            onDismiss = { selectedDbWaypoint = null },
            onEdit = {
                waypointViewModel.startEditing(wp)
                selectedDbWaypoint = null
            },
            onDelete = {
                waypointViewModel.deleteWaypoint(wp)
                selectedDbWaypoint = null
            }
        )
    }

    if (showCrosshair) {
        WaypointCrosshairScreen(
            cameraTarget = crosshairTarget,
            attachments = com.nyasar.app.ui.waypoint.WaypointAttachments(
                routeId = routeId,
                routeName = state.name
            ),
            initialLinkedRouteId = routeId,
            onSave = { lat, lon, name, category, linkedRouteId, linkedActivityId ->
                waypointViewModel.confirmCrosshairWaypointFrom(
                    lat = lat, lon = lon, name = name, category = category,
                    note = null, linkedRouteId = linkedRouteId, linkedActivityId = linkedActivityId
                )
                showCrosshair = false
            },
            onDismiss = { showCrosshair = false }
        )
    }

    editingWaypointState?.let { wp ->
        val category = WaypointCategory.fromStorageValue(wp.category)
        WaypointFormSheet(
            title = stringResource(R.string.edit_waypoint),
            initialName = wp.name,
            initialCategory = category,
            initialNote = wp.note ?: "",
            lat = wp.lat,
            lon = wp.lon,
            elevationM = wp.elevationM,
            attachments = com.nyasar.app.ui.waypoint.WaypointAttachments(
                routeId = routeId,
                routeName = state.name
            ),
            initialLinkedRouteId = wp.linkedRouteId,
            initialLinkedActivityId = wp.linkedActivityId,
            lockAttachment = wp.source == WaypointEntity.SOURCE_GPX,
            onDismiss = { waypointViewModel.dismissEditing() },
            onSave = { name, cat, note, linkedRouteId, linkedActivityId ->
                waypointViewModel.confirmEditWithLinks(name, cat, note, linkedRouteId, linkedActivityId)
            },
            onDelete = { waypointViewModel.deleteWaypoint(wp) }
        )
    }
}

// ---------------------------------------------------------------------------
// Data-section pieces
// ---------------------------------------------------------------------------

/** One stat tile — same soft-tile visual as ActivityDetailScreen's StatsGrid
 *  (small muted label over a semibold value), flexible width so 2-up rows
 *  wrap naturally on narrow screens. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(NyasarRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One waypoint row in the scrollable list: colored category disc + name +
 *  coordinates — mirrors the map marker's icon/color coding so list and map
 *  read as one dataset. */
@Composable
private fun WaypointListRow(waypoint: WaypointEntity, onClick: () -> Unit) {
    val category = WaypointCategory.fromStorageValue(waypoint.category)
    val rowInteraction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(NyasarRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pressScale(rowInteraction)
            .clickable(interactionSource = rowInteraction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(category.color.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    category.icon,
                    contentDescription = stringResource(category.labelRes),
                    tint = category.color,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    waypoint.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "%.5f, %.5f".format(waypoint.lat, waypoint.lon),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (waypoint.source == WaypointEntity.SOURCE_GPX) {
                Icon(
                    Icons.Default.Landscape,
                    contentDescription = stringResource(R.string.gpx_badge),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Elevation profile (full-screen floating card content). Same chart
 *  component and data path as before; scrubbing reports the highlighted
 *  track point back to the map via [onHighlight]. */
@Composable
private fun ElevationSection(state: RoutePreviewUiState, onHighlight: (LatLng) -> Unit) {
    val elevationResult = remember(state.track) {
        val profile = ElevationStats.toElevationProfile(state.track)
        val trackIndices = mutableListOf<Int>()
        var cumDist = 0.0
        var lastPt: com.nyasar.app.gpx.model.TrackPoint? = null
        var profileIdx = 0
        state.track.forEachIndexed { trackIdx, tp ->
            lastPt?.let { prev ->
                cumDist += com.nyasar.app.navigation.GeoMath.distanceMeters(
                    com.nyasar.app.navigation.LatLng(prev.lat, prev.lon),
                    com.nyasar.app.navigation.LatLng(tp.lat, tp.lon)
                )
            }
            lastPt = tp
            if (tp.elevationM != null && profileIdx < profile.size) {
                trackIndices.add(trackIdx)
                profileIdx++
            }
        }
        profile to trackIndices
    }
    val elevationPoints = elevationResult.first
    val trackIndexMap = elevationResult.second
    if (elevationPoints.size >= 2) {
        ElevationProfile(
            points = elevationPoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            onPointSelected = { profileIdx, _ ->
                val trackIdx = trackIndexMap.getOrNull(profileIdx)
                if (trackIdx != null) {
                    val tp = state.track.getOrNull(trackIdx)
                    if (tp != null) {
                        onHighlight(LatLng(tp.lat, tp.lon))
                    }
                }
            }
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Shared map-control recipe — SAME as HomeScreen's RoundIconButton:
    // theme surface over any basemap, app-wide CircleShape, the shared
    // NyasarElevation.mapControl* tokens, the standard press spring
    // (pressScale), and the shared fade-and-rise entrance.
    val interaction = remember { MutableInteractionSource() }
    AnimatedAppear(modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = NyasarElevation.mapControlTonal,
            shadowElevation = NyasarElevation.mapControlShadow,
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

/** GPX-layer waypoint detail (parsed straight from the file — pre-merge
 *  rows). Kept for parity with map taps on the filtered GPX layer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaypointDetailSheet(waypoint: GpxWaypoint, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = com.nyasar.app.ui.theme.NyasarContentWidth.sheetMaxWidth).fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(waypoint.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            DetailRow(stringResource(R.string.coordinate), "%.5f, %.5f".format(waypoint.lat, waypoint.lon))
            waypoint.elevationM?.let { DetailRow(stringResource(R.string.elevation), "${it.roundToInt()} m") }
            waypoint.description?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Spec: "Export GPX" / "Share route" — the route's original GPX already
 *  lives on disk (RouteRepository.gpxFile), so this just hands that file
 *  to the share sheet via FileProvider, no re-export needed. */
private fun shareRouteGpx(context: android.content.Context, gpxFilePath: String, routeName: String) {
    val file = java.io.File(gpxFilePath)
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_route_intent, routeName)))
}
