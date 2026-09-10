package com.nyasar.app.ui.preview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.gpx.model.GpxWaypoint
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.ui.components.CompassButton
import com.nyasar.app.ui.components.ElevationProfile
import com.nyasar.app.ui.components.NyasarMapView
import com.nyasar.app.ui.components.pressScale
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt
import com.nyasar.app.R
import com.nyasar.app.ui.waypoint.WaypointCrosshairScreen
import com.nyasar.app.ui.waypoint.WaypointFormSheet
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // Recording read/write) — replaces the former per-destination
    // `remember { mutableStateOf(...) }` that reset on every entry.
    val currentBasemap by viewModel.selectedBasemap.collectAsState()

    // Start location updates once permission is granted
    LaunchedEffect(Unit) { viewModel.startLocationUpdatesIfPermitted() }

    var selectedWaypoint by remember { mutableStateOf<GpxWaypoint?>(null) }
    // v7: DB-side selection (GPX-imported + linked/independent user pins)
    // and the crosshair add-waypoint flow, context-seeded to THIS route.
    var selectedDbWaypoint by remember { mutableStateOf<com.nyasar.app.data.db.WaypointEntity?>(null) }
    var showCrosshair by remember { mutableStateOf(false) }
    // Highlight marker position when user scrubs the elevation chart
    var highlightLatLng by remember { mutableStateOf<LatLng?>(null) }

    // Map controls state
    var mapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var mapBearing by remember { mutableStateOf(0f) }
    var showBasemapSheet by remember { mutableStateOf(false) }
    var currentStyleVariant by remember { mutableStateOf(StyleVariant.OUTDOOR) }
    // Provider as a persisted-setting StateFlow, same source Home/Recording
    // read: state.provider is only set once `load()` finishes, so a plain
    // remember of it froze on the pre-load default for the whole screen's
    // lifetime (and could disagree with the other two screens' provider,
    // which would break shared-map style-key equality too).
    val currentProvider by viewModel.provider.collectAsState()
    // Waymarked Trails overlays — shared persisted state from the ViewModel
    // (same DataStore row Home/Recording read; spec: GPX Studio's "Overlays"
    // section, checkboxes not radio, multiple can be active together). With
    // one shared MapView, per-screen overlay sets would strip/restore
    // overlays on every screen switch.
    val activeOverlays by viewModel.activeOverlays.collectAsState()
    // "Jalur Saya" overlay: app-wide persisted switch + reactive lines.
    val myRoutesEnabled by viewModel.myRoutesOverlayEnabled.collectAsState()
    val myRouteLines by viewModel.myRouteLines.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.name ?: stringResource(R.string.default_route_name),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    state.gpxFilePath?.let { path ->
                        IconButton(onClick = { shareRouteGpx(context, path, state.name ?: "route") }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_gpx))
                        }
                    }
                    IconButton(onClick = { onDownloadOfflineMap(routeId) }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = stringResource(R.string.prepare_offline_cd))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Map — takes remaining space, buttons float inside this Box
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Build user location LatLng for the map marker
                val userLatLng = currentLocation?.let {
                    LatLng(it.lat, it.lon)
                }

                NyasarMapView(
                    modifier = Modifier.fillMaxSize(),
                    provider = currentProvider,
                    styleVariant = currentStyleVariant,
                    basemapEntry = currentBasemap,
                    // Opt into the shared MapView so Home ↔ RoutePreview ↔
                    // Recording reuse one GL surface/tile cache/style instead
                    // of rebuilding the map on every screen switch.
                    shared = true,
                    activeOverlays = activeOverlays,
                    // "Jalur Saya" overlay — this screen's own route is the
                    // active one (solid accent); every other saved route
                    // renders gray/dashed when the overlay is on.
                    myRoutes = myRouteLines,
                    activeRouteId = routeId,
                    track = state.track,
                    // v7 merge filter: the GPX layer keeps only waypoints
                    // WITHOUT a DB counterpart for this route (name + coords
                    // within WaypointEntity.GPX_COORD_MATCH_DEGREES) — every
                    // merged one is already in dbWaypoints. Without this,
                    // each imported waypoint would render twice (GPX icon +
                    // DB marker) after the merge feature lands.
                    waypoints = state.waypoints.filter { gpxWp ->
                        dbWaypoints.none { db ->
                            db.source == com.nyasar.app.data.db.WaypointEntity.SOURCE_GPX &&
                                db.name == gpxWp.name &&
                                kotlin.math.abs(db.lat - gpxWp.lat) <= com.nyasar.app.data.db.WaypointEntity.GPX_COORD_MATCH_DEGREES &&
                                kotlin.math.abs(db.lon - gpxWp.lon) <= com.nyasar.app.data.db.WaypointEntity.GPX_COORD_MATCH_DEGREES
                        }
                    },
                    userWaypoints = dbWaypoints + userWaypoints,
                    highlightPoint = highlightLatLng,
                    onWaypointClick = { selectedWaypoint = it },
                    onUserWaypointClick = { id ->
                        (dbWaypoints + userWaypoints).firstOrNull { it.id == id }?.let { selectedDbWaypoint = it }
                    },
                    onMapReady = { mapInstance = it },
                    onBearingChanged = { mapBearing = it },
                    // GPS user position + follow mode
                    userLocation = userLatLng,
                    userHeadingDeg = currentLocation?.bearingDeg,
                    accuracyMeters = currentLocation?.accuracyMeters,
                    followUser = followMode,
                    rotateWithHeading = rotateWithHeading,
                    onUserGesture = viewModel::onUserPanned
                )

                // Animate camera to follow highlight point when user scrubs elevation chart
                LaunchedEffect(highlightLatLng) {
                    val map = mapInstance ?: return@LaunchedEffect
                    val point = highlightLatLng ?: return@LaunchedEffect
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(point, map.cameraPosition.zoom),
                        200
                    )
                }

                // Compass — top-end, below top bar
                CompassButton(
                    bearingDeg = mapBearing,
                    onClick = { mapInstance?.let { it.animateCamera(CameraUpdateFactory.bearingTo(0.0)) } },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = 12.dp)
                        .size(48.dp)
                )

                // Right-side buttons: Layer + Location (positioned above bottom of map)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Layer button — opens Strava-style basemap grid sheet
                    RoundIconButton(icon = Icons.Default.Layers, contentDescription = stringResource(R.string.map_layer_cd)) {
                        showBasemapSheet = true
                    }
                    // v7: add a waypoint pinned to THIS route (crosshair picker).
                    RoundIconButton(icon = Icons.Default.Place, contentDescription = stringResource(R.string.add_waypoint_cd)) {
                        showCrosshair = true
                    }
                    // Location button — center on user GPS position + toggle heading
                    RoundIconButton(
                        icon = if (rotateWithHeading) Icons.Default.Navigation else Icons.Default.MyLocation,
                        contentDescription = if (rotateWithHeading) stringResource(R.string.heading_up_mode_cd) else stringResource(R.string.go_to_location_cd),
                        tint = if (followMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        onClick = { viewModel.centerOnLocation() }
                    )
                }
            }

            // Bottom info section — scrollable for responsive layout.
            // ROOT CAUSE FIX (map squeezed to a sliver at narrow widths,
            // e.g. 320dp): this Surface previously had no height cap, so
            // whenever its content grew taller than expected (e.g. the
            // stats Row below wrapping badly), the Column above gave this
            // unweighted section however much height it asked for and the
            // map's Box(weight(1f)) got squeezed down to whatever was left
            // — sometimes almost nothing. Capping this section's height to
            // a fraction of the screen guarantees the map always keeps a
            // reasonable minimum share of the vertical space regardless of
            // how tall the stats/chart content below gets.
            val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.heightIn(max = screenHeightDp * 0.55f)
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Stats row — FlowRow instead of Row: at narrow widths
                    // (e.g. 320dp) a plain Row can't fit 5 stats, so instead
                    // of wrapping to a new line as whole items it used to
                    // squeeze every single Text down until words inside
                    // wrapped individually ("m tertinggi" broke onto its own
                    // stacked lines), ballooning this section's height.
                    // FlowRow wraps whole Stat items onto additional lines
                    // as a unit, which is both readable and bounded in
                    // height regardless of screen width.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Stat("%.1f km".format(state.distanceKm))
                        state.elevationGainM?.let { Stat("↑ ${it.roundToInt()} m") }
                        state.elevationLossM?.let { Stat("↓ ${it.roundToInt()} m") }
                        state.highestElevationM?.let { Stat("${it.roundToInt()} m tertinggi") }
                        Stat("${state.waypointCount} waypoint")
                    }

                    // Elevation profile chart
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
                        Spacer(Modifier.height(12.dp))
                        ElevationProfile(
                            points = elevationPoints,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            onPointSelected = { profileIdx, _ ->
                                val trackIdx = trackIndexMap.getOrNull(profileIdx)
                                if (trackIdx != null) {
                                    val tp = state.track.getOrNull(trackIdx)
                                    if (tp != null) {
                                        highlightLatLng = LatLng(tp.lat, tp.lon)
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onDownloadOfflineMap(routeId) }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.prepare_offline))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onStartNavigation(routeId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(stringResource(R.string.start_nav), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    selectedWaypoint?.let { wp ->
        WaypointDetailSheet(waypoint = wp, onDismiss = { selectedWaypoint = null })
    }

    if (showBasemapSheet) {
        com.nyasar.app.ui.components.BasemapPickerSheet(
            selected = currentBasemap,
            onSelect = { entry ->
                // Persisted app-wide: Home and Recording observe the same
                // DataStore-backed flow, so the pick follows the user there.
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

    // v7 waypoint overlays (detail/crosshair/edit) — drawn above the Scaffold.
    RoutePreviewWaypointOverlays(
        selectedDbWaypoint = selectedDbWaypoint,
        onDismissSelected = { selectedDbWaypoint = null },
        onEditSelected = { wp ->
            waypointViewModel.startEditing(wp)
            selectedDbWaypoint = null
        },
        onDeleteSelected = { wp ->
            waypointViewModel.deleteWaypoint(wp)
            selectedDbWaypoint = null
        },
        distanceFromUserMeters = { wp ->
            currentLocation?.let {
                com.nyasar.app.navigation.GeoMath.distanceMeters(
                    com.nyasar.app.navigation.LatLng(it.lat, it.lon),
                    com.nyasar.app.navigation.LatLng(wp.lat, wp.lon)
                )
            }
        },
        showCrosshair = showCrosshair,
        routeId = routeId,
        routeName = state.name,
        editingWaypoint = editingWaypointState,
        onDismissEditing = { waypointViewModel.dismissEditing() },
        onCrosshairDismiss = { showCrosshair = false },
        onCrosshairSave = { lat, lon, name, category, linkedRouteId, linkedActivityId ->
            waypointViewModel.confirmCrosshairWaypointFrom(
                lat = lat, lon = lon, name = name, category = category,
                note = null, linkedRouteId = linkedRouteId, linkedActivityId = linkedActivityId
            )
            showCrosshair = false
        },
        onEditSave = { name, cat, note, linkedRouteId, linkedActivityId ->
            waypointViewModel.confirmEditWithLinks(name, cat, note, linkedRouteId, linkedActivityId)
        },
        onEditDelete = { wp -> waypointViewModel.deleteWaypoint(wp) }
    )
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Shared map-control recipe — SAME as HomeScreen's RoundIconButton:
    // theme surface over any basemap, app-wide CircleShape, the shared
    // NyasarElevation.mapControl* tokens (this local copy previously used
    // bare 3.dp/2.dp values), the standard press spring (pressScale inside
    // AnimatedAppear), and the shared fade-and-rise entrance. RoutePreview's
    // map buttons (incl. the v7 add-waypoint button) now look, feel and
    // animate identically to Home's.
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

@Composable
private fun Stat(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

// ---------------------------------------------------------------------------
// v7 waypoint overlays. Composed AFTER the Scaffold so they draw on top of
// it (a full-screen crosshair or bottom sheet emitted before the Scaffold
// would be covered by it).
// ---------------------------------------------------------------------------

// v7: DB waypoint tap → the shared detail sheet (categorize/edit/delete
// for GPX-imported rows too — the whole point of the merge).
@Composable
private fun RoutePreviewWaypointOverlays(
    selectedDbWaypoint: com.nyasar.app.data.db.WaypointEntity?,
    onDismissSelected: () -> Unit,
    onEditSelected: (com.nyasar.app.data.db.WaypointEntity) -> Unit,
    onDeleteSelected: (com.nyasar.app.data.db.WaypointEntity) -> Unit,
    distanceFromUserMeters: (com.nyasar.app.data.db.WaypointEntity) -> Double?,
    showCrosshair: Boolean,
    routeId: String,
    routeName: String?,
    editingWaypoint: com.nyasar.app.data.db.WaypointEntity?,
    onDismissEditing: () -> Unit,
    onCrosshairDismiss: () -> Unit,
    onCrosshairSave: (Double, Double, String, com.nyasar.app.data.db.WaypointCategory, String?, String?) -> Unit,
    onEditSave: (String, com.nyasar.app.data.db.WaypointCategory, String?, String?, String?) -> Unit,
    onEditDelete: (com.nyasar.app.data.db.WaypointEntity) -> Unit
) {
    selectedDbWaypoint?.let { wp ->
        com.nyasar.app.ui.waypoint.WaypointDetailSheet(
            waypoint = wp,
            distanceFromUserMeters = distanceFromUserMeters(wp),
            onDismiss = onDismissSelected,
            onEdit = { onEditSelected(wp) },
            onDelete = { onDeleteSelected(wp) }
        )
    }

    if (showCrosshair) {
        WaypointCrosshairScreen(
            attachments = com.nyasar.app.ui.waypoint.WaypointAttachments(
                routeId = routeId,
                routeName = routeName
            ),
            initialLinkedRouteId = routeId,
            onSave = { lat, lon, name, category, linkedRouteId, linkedActivityId ->
                onCrosshairSave(lat, lon, name, category, linkedRouteId, linkedActivityId)
            },
            onDismiss = onCrosshairDismiss
        )
    }

    editingWaypoint?.let { wp ->
        val category = com.nyasar.app.data.db.WaypointCategory.fromStorageValue(wp.category)
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
                routeName = routeName
            ),
            initialLinkedRouteId = wp.linkedRouteId,
            initialLinkedActivityId = wp.linkedActivityId,
            lockAttachment = wp.source == com.nyasar.app.data.db.WaypointEntity.SOURCE_GPX,
            onDismiss = onDismissEditing,
            onSave = { name, cat, note, linkedRouteId, linkedActivityId ->
                onEditSave(name, cat, note, linkedRouteId, linkedActivityId)
            },
            onDelete = { onEditDelete(wp) }
        )
    }
}

/** Spec section 13: nama, koordinat, elevation, description saat waypoint dipilih. */
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
            DetailRow("Koordinat", "%.5f, %.5f".format(waypoint.lat, waypoint.lon))
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
