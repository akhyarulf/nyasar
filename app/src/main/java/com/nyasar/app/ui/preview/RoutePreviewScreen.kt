package com.nyasar.app.ui.preview

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
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
import com.nyasar.app.ui.theme.NyasarMotion
import com.nyasar.app.ui.theme.NyasarRadius
import com.nyasar.app.ui.waypoint.WaypointCrosshairScreen
import com.nyasar.app.ui.waypoint.WaypointFormSheet
import com.nyasar.app.ui.waypoint.rememberCrosshairCameraState
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * Route Viewer — Komoot/ActivityDetail-style layout:
 *
 * - MAP ON TOP: a fixed-height map block (~38% of the screen, matching the
 *   reference screenshot's balance). An expand toggle grows it to nearly the
 *   full screen; the elevation profile appears on the map as a floating card
 *   ONLY in the expanded state (spec: "ketika map penuh baru terlihat
 *   elevasinya"). The camera keeps its center across the resize so the route
 *   stays under the user's eyes.
 * - DATA BELOW: a scrollable section styled like ActivityDetailScreen —
 *   title, stat tiles (same soft-tile visual), waypoint list with category
 *   icons, offline-map entry — so both detail screens read as siblings.
 * - PINNED CTA: Start Navigation lives in a bottom bar (never scrolls away),
 *   padded for the navigation bar like Recording's controls.
 * - Fully localized (strings.xml only) and theme-tokenized
 *   (NyasarRadius/NyasarElevation/NyasarMotion + shared RoundIconButton
 *   recipe with AnimatedAppear + pressScale), responsive on small and large
 *   phones: collapsed map is a fraction of screen height, data column scrolls,
 *   nothing overflows.
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

    var mapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var mapBearing by remember { mutableStateOf(0f) }
    var showBasemapSheet by remember { mutableStateOf(false) }
    var currentStyleVariant by remember { mutableStateOf(StyleVariant.OUTDOOR) }

    // ---------------------------------------------------------------------------
    // Map expand state. Collapsed: a screen-height fraction so the data list
    // gets a real share on every device. Expanded: as tall as the screen
    // allows while the pinned CTA bar and its padding stay visible. The height
    // change animates with the shared motion spec; the camera center is kept
    // across the resize so the route doesn't jump.
    // ---------------------------------------------------------------------------
    var mapExpanded by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val collapsedMapHeight = screenHeightDp * 0.38f
    val expandedMapHeight = (screenHeightDp - 200.dp).coerceIn(320.dp, 720.dp)
    val mapHeight by animateDpAsState(
        targetValue = if (mapExpanded) expandedMapHeight else collapsedMapHeight,
        animationSpec = NyasarMotion.enter(),
        label = "routeMapHeight"
    )
    // Camera snapshot taken at toggle time, re-applied after the resize.
    var cameraKeep by remember { mutableStateOf<org.maplibre.android.camera.CameraPosition?>(null) }
    LaunchedEffect(mapExpanded) {
        val map = mapInstance ?: return@LaunchedEffect
        cameraKeep?.let { keep ->
            map.animateCamera(CameraUpdateFactory.newCameraPosition(keep), 220)
        }
    }
    // Elevation card height (expanded mode) — right-side controls lift above it.
    var elevationCardHeightDp by remember { mutableStateOf(0.dp) }

    // v7 "layar terakhir": the crosshair overlay reads THIS screen's live
    // camera, so it opens at exactly the last position+zoom the user was
    // viewing — no teleport, no re-zoom.
    val crosshairTarget by rememberCrosshairCameraState(mapInstance, showCrosshair)

    Column(Modifier.fillMaxSize()) {
        // =============================== MAP BLOCK ===============================
        Box(
            Modifier
                .fillMaxWidth()
                .height(mapHeight)
        ) {
            val userLatLng = currentLocation?.let { LatLng(it.lat, it.lon) }

            NyasarMapView(
                modifier = Modifier.fillMaxSize(),
                provider = currentProvider,
                styleVariant = currentStyleVariant,
                basemapEntry = currentBasemap,
                // BUG FIX ("pin gak muncul di Route Viewer padahal di Activity
                // Detail muncul"): this screen used to opt into the SHARED
                // MapView (shared = true). ActivityDetailScreen — where pins
                // provably render — uses a PRIVATE instance via the full
                // setStyle pipeline. The shared fast path (refreshSharedContent)
                // was audited line-by-line and produces the identical sources,
                // layers, images, and properties — and the track line FROM THE
                // SAME CALLBACK visibly renders, so the callback runs to
                // completion. The only remaining variable is persistent state
                // carried by the process-wide shared instance (style + content
                // + images installed by other screens), which this screen
                // inherits instead of building fresh. The pragmatic fix is to
                // give RoutePreview its own instance on the EXACT pipeline
                // that is proven to work on-device — the ActivityDetail
                // pipeline — at the cost of one style/tile load per entry
                // (acceptable: it's a detail screen reached from Library, not
                // a tab users flip between constantly; Home ↔ Recording keep
                // the shared optimization where it matters most).
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
                // which was never this screen's look (its old shared fast
                // path hardcoded blue).
                trackColorOverride = "#42A5F5",
                // v7 merge filter: the GPX layer keeps only waypoints WITHOUT
                // a DB counterpart for this route (name + coords within
                // WaypointEntity.GPX_COORD_MATCH_DEGREES) — every merged one
                // is already in dbWaypoints. Without this, each imported
                // waypoint would render twice after the merge feature.
                waypoints = state.waypoints.filter { gpxWp ->
                    dbWaypoints.none { db ->
                        db.source == WaypointEntity.SOURCE_GPX &&
                            db.name == gpxWp.name &&
                            kotlin.math.abs(db.lat - gpxWp.lat) <= WaypointEntity.GPX_COORD_MATCH_DEGREES &&
                            kotlin.math.abs(db.lon - gpxWp.lon) <= WaypointEntity.GPX_COORD_MATCH_DEGREES
                    }
                },
                userWaypoints = dbWaypoints + userWaypoints,
                waypointsVisible = waypointsVisible,
                highlightPoint = highlightLatLng,
                onWaypointClick = { selectedWaypoint = it },
                onUserWaypointClick = { id ->
                    (dbWaypoints + userWaypoints).firstOrNull { it.id == id }?.let { selectedDbWaypoint = it }
                },
                onMapReady = { mapInstance = it },
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
                val map = mapInstance ?: return@LaunchedEffect
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
                onClick = { mapInstance?.let { it.animateCamera(CameraUpdateFactory.bearingTo(0.0)) } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 76.dp)
                    .size(48.dp)
            )

            // --- Right-side controls. In expanded mode they lift above the
            // --- floating elevation card so nothing hides behind it.
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
                // Expand/collapse the map — Komoot-style full-map mode. The
                // camera center is snapshotted first so the resize doesn't
                // shove the route off-screen.
                RoundIconButton(
                    icon = if (mapExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                    contentDescription = stringResource(
                        if (mapExpanded) R.string.map_collapse_cd else R.string.map_expand_cd
                    ),
                    onClick = {
                        cameraKeep = mapInstance?.cameraPosition
                        mapExpanded = !mapExpanded
                    }
                )
            }

            // --- Elevation card: ONLY in expanded mode (spec), floating at
            // --- the bottom of the full map like the reference screenshot.
            if (mapExpanded) {
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

    // v7 waypoint overlays (detail/crosshair/edit) — emitted after the main
    // content so they draw on top of it.
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
    val rowInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
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

/** Elevation profile (expanded-map card content). Same chart component and
 *  data path as before; scrubbing reports the highlighted track point back
 *  to the map via [onHighlight]. */
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
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
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
