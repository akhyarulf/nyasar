package com.nyasar.app.ui.drawroute

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.data.db.WaypointCategory
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.navigation.LatLng
import com.nyasar.app.ui.components.AnimatedAppear
import com.nyasar.app.ui.components.BasemapPickerSheet
import com.nyasar.app.ui.components.NyasarMapView
import com.nyasar.app.ui.theme.NyasarElevation
import com.nyasar.app.ui.waypoint.WaypointCrosshairScreen
import com.nyasar.app.ui.waypoint.WaypointDetailSheet
import com.nyasar.app.ui.waypoint.WaypointFormSheet
import com.nyasar.app.ui.waypoint.rememberCrosshairCameraState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

/**
 * Manual point-by-point route drawing (spec: "tap titik satu-satu di peta,
 * garis mengikuti jalur asli"). With "ikuti jalur" ON (default), each new
 * segment is upgraded from a straight line to the REAL trail/road geometry
 * via PathSnapper (OSRM foot — the GPX Studio feel); OFF (or offline, or
 * an unmapped trail) degrades to the original manual straight-line mode —
 * the old P1 "deliberately manual" behavior remains the fallback, not a
 * lock-in. Anchors (the taps) stay the undo/history model in the VM.
 *
 * Map controls parity (user request): the same floating right-side control
 * stack as RoutePreview/Recording — basemap/overlay picker (shared
 * DataStore, so layer picks here follow the user app-wide), drop-waypoint
 * (crosshair overlay, drafts linked to the route on save), and recenter.
 * Anchor taps render as blue DOTS (NyasarMapView drawnAnchors), so even
 * the first tap is immediately visible.
 *
 * This is for building a route BEFORE going outside, with no GPS
 * involved — distinct from Recording (GPS-tracked, while actually
 * walking). "Add Route" reaching this screen, not Recording, matches
 * what was actually asked for: a way to create a route when the user
 * doesn't have a GPX yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawRouteScreen(
    viewModel: DrawRouteViewModel = viewModel(),
    onBack: () -> Unit,
    onRouteSaved: (routeId: String) -> Unit,
    onNavigateToStart: (routeId: String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val selectedBasemap by viewModel.selectedBasemap.collectAsState()
    val currentProvider by viewModel.provider.collectAsState()
    val activeOverlays by viewModel.activeOverlays.collectAsState()
    val myRoutesEnabled by viewModel.myRoutesOverlayEnabled.collectAsState()
    val myRouteLines by viewModel.myRouteLines.collectAsState()
    val draftWaypoints by viewModel.draftWaypoints.collectAsState()
    val offlineAreas by viewModel.offlineAreas.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationRepository = remember { LocationRepository(context) }
    var mapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var showFinishSheet by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showBasemapSheet by remember { mutableStateOf(false) }
    var showCrosshair by remember { mutableStateOf(false) }
    // Waypoint overlays (detail/edit) — same VM contract as RoutePreview.
    var selectedWaypoint by remember { mutableStateOf<WaypointEntity?>(null) }
    val waypointViewModel: com.nyasar.app.ui.waypoint.WaypointViewModel = viewModel()
    val editingWaypointState by waypointViewModel.editingWaypoint.collectAsState()
    // Live GPS dot while drawing (null until a fix / no permission).
    var userFix by remember { mutableStateOf<LatLng?>(null) }

    // Live GPS marker — drawing near your own position needs to know where
    // "here" is; every other map screen shows the dot, so this one does too.
    // Silently absent without permission (the recenter button handles that
    // case with its own guard).
    LaunchedEffect(Unit) {
        if (!locationRepository.hasLocationPermission()) return@LaunchedEffect
        locationRepository.observeLocation().collect { userFix = LatLng(it.lat, it.lon) }
    }

    // Same reasoning/pattern as OfflineDownloadScreen's "Around Me": without
    // this the map opens on MapLibre's raw default camera (effectively
    // 0,0/null island), which makes tapping out a route impractical until
    // the user manually finds their own area first. Keyed on mapInstance
    // (not Unit) so this waits for onMapReady to actually fire before
    // trying to animateCamera — a plain LaunchedEffect(Unit) could run
    // before the map finishes initializing, silently no-op on a still-null
    // mapInstance, and never retry (OfflineDownloadScreen's own version of
    // this dodges the race because it's a manual button tap, which can't
    // happen before the map has visibly rendered; this one is automatic on
    // entry, so it needs to wait explicitly). Permission-denied or a slow
    // fix silently leaves the default view in place rather than blocking
    // drawing on it.
    LaunchedEffect(mapInstance) {
        val map = mapInstance ?: return@LaunchedEffect
        if (!locationRepository.hasLocationPermission()) return@LaunchedEffect
        val fix = locationRepository.observeLocation().first()
        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                org.maplibre.android.geometry.LatLng(fix.lat, fix.lon), 14.0
            )
        )
    }

    // v7 crosshair camera: the overlay reads the ACTIVE map's live camera,
    // so it opens at exactly the position+zoom the user is viewing.
    val crosshairTarget by rememberCrosshairCameraState(mapInstance, showCrosshair)

    // Two different outcomes need two different exits: "just save" goes
    // back to wherever the user came from (Track & Peta, where the new
    // route now appears); "save and navigate" goes straight into
    // start-activity for it instead. Both only fire once (LaunchedEffect
    // keyed on the id, which only ever transitions null -> a real id
    // once), so neither can double-navigate on recomposition.
    var pendingNavigateAfterSave by remember { mutableStateOf(false) }
    LaunchedEffect(state.savedRouteId) {
        val id = state.savedRouteId ?: return@LaunchedEffect
        if (pendingNavigateAfterSave) onNavigateToStart(id) else onRouteSaved(id)
    }

    if (state.error != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(stringResource(R.string.save_failed)) },
            text = { Text(state.error ?: "") },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.ok)) } }
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.discard_route)) },
            text = { Text(stringResource(R.string.discard_route_message)) },
            confirmButton = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.discard_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        NyasarMapView(
            modifier = Modifier.fillMaxSize(),
            provider = currentProvider,
            basemapEntry = selectedBasemap,
            // Draft waypoints render through the SAME user-pin layer as
            // RoutePreview (null link = generic pin) — the pins show while
            // drawing and are persisted linked to the route on save.
            userWaypoints = draftWaypoints,
            onUserWaypointClick = { id ->
                selectedWaypoint = draftWaypoints.firstOrNull { wp -> wp.id == id }
            },
            // "Jalur Saya" overlay — same shared flag as the other map
            // screens, rendered from the same RouteRepository flow.
            myRoutes = myRouteLines,
            // Downloaded-area coverage — same live store the other screens read.
            offlineAreas = offlineAreas,
            track = emptyList(),
            // drawnPoints (not track) — track's LaunchedEffect key would
            // re-run the full style-setup effect (incl. a camera bounds
            // refit) on every single tap; drawnPoints has its own isolated
            // update path that doesn't touch the camera at all, letting
            // the user keep tapping without the map jumping around.
            drawnPoints = state.pathPoints,
            // Anchor dots (bug fix: the first tap used to be invisible —
            // a one-point LineString renders nothing).
            drawnAnchors = state.points,
            // NyasarMapView's userLocation is the MapLibre LatLng type —
            // convert from the app-domain fix (which GeoMath needs below).
            userLocation = userFix?.let { org.maplibre.android.geometry.LatLng(it.lat, it.lon) },
            onMapClick = { lat, lon -> viewModel.addPoint(lat, lon) },
            onMapReady = { mapInstance = it }
        )

        TopAppBar(
            title = { Text(stringResource(R.string.draw_route)) },
            navigationIcon = {
                IconButton(onClick = {
                    if (state.points.isNotEmpty()) showDiscardConfirm = true else onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(onClick = viewModel::undoLastPoint, enabled = state.canUndo) {
                    Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.undo_last_point_cd))
                }
                // "Ikuti jalur" toggle: snap new segments to real OSM
                // trails/roads. Icon-only + contentDescription like every
                // other top-bar action; the on/off state is visible from
                // the icon itself (tint follows the enabled-ish state).
                IconButton(onClick = { viewModel.setFollowPaths(!state.followPaths) }) {
                    Icon(
                        Icons.Default.Route,
                        contentDescription = stringResource(
                            if (state.followPaths) R.string.follow_paths_on_cd else R.string.follow_paths_off_cd
                        ),
                        tint = if (state.followPaths) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        // --- Right-side floating control stack (user request): the SAME
        // set + look as RoutePreview/Recording — layer picker, add
        // waypoint, recenter. Replaces the old bare MyLocation action in
        // the top bar. (Recenter moved here; "go to location" semantics
        // and guard are identical.)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp)
                .offset(y = -(120.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MapControlButton(icon = Icons.Default.Layers, contentDescription = stringResource(R.string.map_layer_cd)) {
                showBasemapSheet = true
            }
            MapControlButton(icon = Icons.Default.Place, contentDescription = stringResource(R.string.add_waypoint_cd)) {
                showCrosshair = true
            }
            MapControlButton(icon = Icons.Default.MyLocation, contentDescription = stringResource(R.string.go_to_location_cd)) {
                scope.launch {
                    if (!locationRepository.hasLocationPermission()) return@launch
                    val fix = locationRepository.observeLocation().first()
                    mapInstance?.animateCamera(
                        org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                            org.maplibre.android.geometry.LatLng(fix.lat, fix.lon), 14.0
                        )
                    )
                }
            }
        }

        // Live point count + straight-line distance so the user has some
        // feedback while drawing, without needing to open anything else.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        stringResource(R.string.point_count, state.points.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val km = state.distanceMeters / 1000.0
                    Text(
                        "%.2f km".format(km),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Tiny status line: what the line on the map is doing
                    // right now (snapping in flight / fallback because a
                    // snap failed). Replaces guessing — with snapping the
                    // line can visibly "move" a second after the tap.
                    if (state.followPaths) {
                        Text(
                            stringResource(
                                when {
                                    state.snapping -> R.string.snap_status_routing
                                    else -> R.string.snap_status_on
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(
                    onClick = { showFinishSheet = true },
                    enabled = state.canFinish
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }

        // Crosshair waypoint drop — overlay reads the live camera of THIS
        // screen's map. Save = draft pin (linked to the route on finish).
        // INSIDE the root Box so it stacks over the map (same pattern as
        // RoutePreview's full-screen overlay).
        if (showCrosshair) {
            WaypointCrosshairScreen(
                cameraTarget = crosshairTarget,
                initialLinkedRouteId = null,
                onSave = { lat, lon, name, category, _, _ ->
                    // Draft: memory-only WaypointEntity (id = UUID, link null
                    // until finish() persists it against the saved route).
                    viewModel.addDraftWaypoint(
                        WaypointEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name,
                            category = category.name,
                            lat = lat,
                            lon = lon,
                            elevationM = null,
                            note = null,
                            createdAtEpochMs = System.currentTimeMillis(),
                            source = WaypointEntity.SOURCE_USER
                        )
                    )
                    showCrosshair = false
                },
                onDismiss = { showCrosshair = false }
            )
        }
    }

    if (showBasemapSheet) {
        BasemapPickerSheet(
            selected = selectedBasemap,
            onSelect = { entry ->
                viewModel.setBasemap(entry)
                showBasemapSheet = false
            },
            activeOverlays = activeOverlays,
            onToggleOverlay = { viewModel.toggleOverlay(it) },
            myRoutesEnabled = myRoutesEnabled,
            onToggleMyRoutes = { viewModel.setMyRoutesOverlayEnabled(!myRoutesEnabled) },
            // Waypoint-pin gate & offline-coverage tiles belong to the other
            // map screens' shared overlay set; on THIS screen the draft pins
            // ARE the content (always shown) and coverage adds nothing, so
            // both tiles are hidden instead of rendered as dead toggles.
            showWaypointsToggle = false,
            showOfflineAreasToggle = false,
            onDismiss = { showBasemapSheet = false }
        )
    }

    // Draft waypoint detail (tap a pin) — edit/delete mirror RoutePreview
    // but operate on the DRAFT list only; nothing hits the DB until save.
    selectedWaypoint?.let { wp ->
        WaypointDetailSheet(
            waypoint = wp,
            distanceFromUserMeters = userFix?.let {
                com.nyasar.app.navigation.GeoMath.distanceMeters(
                    LatLng(it.lat, it.lon),
                    LatLng(wp.lat, wp.lon)
                )
            },
            onDismiss = { selectedWaypoint = null },
            onEdit = {
                waypointViewModel.startEditing(wp)
                selectedWaypoint = null
            },
            onDelete = {
                viewModel.removeDraftWaypoint(wp)
                selectedWaypoint = null
            }
        )
    }

    // Edit draft waypoint form — same sheet component as RoutePreview,
    // saving into the DRAFT list (coordinates/elevation stay fixed).
    editingWaypointState?.let { wp ->
        WaypointFormSheet(
            title = stringResource(R.string.edit_waypoint),
            initialName = wp.name,
            initialCategory = WaypointCategory.fromStorageValue(wp.category),
            initialNote = wp.note ?: "",
            lat = wp.lat,
            lon = wp.lon,
            elevationM = wp.elevationM,
            onDismiss = { waypointViewModel.dismissEditing() },
            onSave = { name, cat, note, _, _ ->
                viewModel.updateDraftWaypoint(wp, name, cat, note)
                waypointViewModel.dismissEditing()
            },
            onDelete = {
                viewModel.removeDraftWaypoint(wp)
                waypointViewModel.dismissEditing()
            }
        )
    }

    if (showFinishSheet) {
        FinishRouteSheet(
            saving = state.saving,
            onDismiss = { showFinishSheet = false },
            onConfirm = { name, alsoNavigate ->
                pendingNavigateAfterSave = alsoNavigate
                viewModel.finish(name)
            }
        )
    }
}

/** Shared map-control recipe — same look as RoutePreview/Home's
 *  RoundIconButton (theme surface circle over any basemap). Private to
 *  this screen to avoid widening the other screens' private helpers. */
@Composable
private fun MapControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    AnimatedAppear {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = NyasarElevation.mapControlTonal,
            shadowElevation = NyasarElevation.mapControlShadow,
            modifier = Modifier.size(48.dp)
        ) {
            IconButton(onClick = onClick) {
                Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinishRouteSheet(
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, alsoNavigate: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.widthIn(max = com.nyasar.app.ui.theme.NyasarContentWidth.sheetMaxWidth).fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.save_route), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.route_name_hint)) },
                placeholder = { Text(stringResource(R.string.new_route)) },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onConfirm(name, false) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onConfirm(name, true) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save_and_navigate))
            }
            if (saving) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
