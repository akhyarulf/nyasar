package com.nyasar.app.ui.preview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
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
 * Route Viewer (route preview) — redesigned to match the rest of the app:
 *
 * - FULL-BLEED map: the map fills the entire screen behind everything; the
 *   info panel is a floating card on top of it instead of the rigid Column
 *   section that used to squeeze the map into a sliver on small phones.
 * - DRAGGABLE panel: a compact peek (grabber + route name + stat chips +
 *   Start Navigation) is always visible; dragging the handle (or tapping
 *   it) expands to the elevation profile. Collapsed by default so the map
 *   stays the hero on every screen size. The expanded height is the panel's
 *   NATURAL content height (measured unconstrained inside the scroll) capped
 *   at 70% of the screen, so the map keeps a visible share above it.
 * - Same visual family as Home/Recording map screens: floating pill map
 *   controls (shared RoundIconButton recipe), NyasarRadius/NyasarElevation
 *   tokens, AnimatedAppear entrances, snap-to-finger drag with the shared
 *   motion spec on release.
 * - Fully localized: every label comes from strings.xml (the old version
 *   hardcoded "m tertinggi"/"waypoint" — the i18n bug this redesign fixes).
 */
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
    val pendingWaypointTap by waypointViewModel.pendingTap.collectAsState()
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

    // ---------------------------------------------------------------------------
    // Draggable bottom panel. 0f = collapsed (peek), 1f = expanded. During a
    // handle drag the fraction follows the finger live (snap spec, no fighting
    // animation); on release it settles with the shared motion spec so the
    // feel matches every other transition in the app.
    //
    // Heights: the peek = draggable header + fixed CTA row. The expanded
    // height = peek + the expandable section's NATURAL height — measured on
    // the content INSIDE the verticalScroll, which measures its child with
    // infinite height, so the number is the real content size (not the
    // currently visible slice). Capped at 70% of the screen so the map and
    // its controls always keep a visible share above the panel.
    // ---------------------------------------------------------------------------
    var panelExpanded by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var headerHeightPx by remember { mutableStateOf(0) }
    var expandableContentHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val maxPanelHeightPx = with(density) { (screenHeightDp * 0.7f).toPx() }
    val ctaHeightPx = with(density) { RoutePreviewCtaHeight.toPx() }
    val peekHeightPx = headerHeightPx + ctaHeightPx
    val fullHeightPx = (peekHeightPx + expandableContentHeightPx).coerceAtMost(maxPanelHeightPx)
    val animatedFraction by animateFloatAsState(
        targetValue = if (isDragging) dragFraction else if (panelExpanded) 1f else 0f,
        animationSpec = if (isDragging) snap() else NyasarMotion.enter(),
        label = "routePanelFraction"
    )
    val panelHeightPx = lerp(peekHeightPx, fullHeightPx, animatedFraction)
    val panelHeightDp = with(density) { panelHeightPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        // --- Full-bleed map -------------------------------------------------
        // Build user location LatLng for the map marker
        val userLatLng = currentLocation?.let { LatLng(it.lat, it.lon) }

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
            // Long-press → Add-waypoint sheet, the same gesture every other
            // map screen offers (Home/Recording/Navigation). Context-seeded
            // to THIS route via WaypointContext set in the LaunchedEffect
            // above; the picker in the sheet lets the user detach it.
            onMapLongPress = { lat, lon ->
                waypointViewModel.onMapLongPress(lat, lon, currentLocation?.elevationM)
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

        // --- Floating top pills (replace the solid TopAppBar that used to
        // --- clamp the map below it): same CircleShape recipe as every other
        // --- map control, so the route line gets back the pixels the old app
        // --- bar covered.
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

        // Compass — top-end, below the floating action row
        CompassButton(
            bearingDeg = mapBearing,
            onClick = { mapInstance?.let { it.animateCamera(CameraUpdateFactory.bearingTo(0.0)) } },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 16.dp, top = 76.dp)
                .size(48.dp)
        )

        // Right-side map controls — lifted above the panel's CURRENT height
        // so they never hide behind it, collapsed or expanded, even mid-drag.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp)
                .offset(y = -(panelHeightDp + 12.dp)),
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

        // --- Draggable bottom panel -----------------------------------------
        AnimatedAppear(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = NyasarRadius.xl, topEnd = NyasarRadius.xl),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = NyasarElevation.cardTonal,
                shadowElevation = NyasarElevation.floatingShadow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeightDp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // Drag handle + peek content. The whole header block is
                    // draggable AND tappable — standard bottom-sheet
                    // affordance (drag follows the finger, tap toggles).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { headerHeightPx = it.height }
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val range = (fullHeightPx - peekHeightPx).coerceAtLeast(1f)
                                    dragFraction = (dragFraction - delta / range).coerceIn(0f, 1f)
                                },
                                onDragStarted = {
                                    isDragging = true
                                    dragFraction = if (panelExpanded) 1f else 0f
                                },
                                onDragStopped = {
                                    isDragging = false
                                    panelExpanded = dragFraction > 0.4f
                                }
                            )
                            .clickable { panelExpanded = !panelExpanded }
                            .padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Grabber pill
                        Box(
                            Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        // Route name — was trapped in the old TopAppBar; lives
                        // in the panel now so the map reclaims the top strip.
                        Text(
                            state.name ?: stringResource(R.string.default_route_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        // Compact stat chips (peek content) — icon + value +
                        // localized label, the same soft-tile visual language
                        // as ActivityDetail's StatsGrid. FlowRow wraps whole
                        // chips on narrow screens; labels come from
                        // strings.xml (no more hardcoded "tertinggi").
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 6.dp)
                        ) {
                            StatChip(Icons.Default.Straighten, stringResource(R.string.stat_distance), "%.1f km".format(state.distanceKm))
                            state.elevationGainM?.let { StatChip(Icons.Default.TrendingUp, stringResource(R.string.elevation_gain), "↑ ${it.roundToInt()} m") }
                            state.elevationLossM?.let { StatChip(Icons.Default.TrendingDown, stringResource(R.string.stat_elev_loss), "↓ ${it.roundToInt()} m") }
                            state.highestElevationM?.let { StatChip(Icons.Default.Landscape, stringResource(R.string.stat_highest_point), "${it.roundToInt()} m") }
                            StatChip(Icons.Default.Place, stringResource(R.string.waypoints), state.waypointCount.toString())
                        }
                        Icon(
                            if (panelExpanded || (isDragging && dragFraction > 0.4f)) Icons.Default.KeyboardArrowDown
                            else Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Expandable slice: fills whatever space the animated
                    // panel height leaves between header and CTA; its content
                    // scrolls when the natural content exceeds the slice.
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Measured UNCONSTRAINED (verticalScroll gives its
                            // child infinite height) — this onSizeChanged is
                            // the real content height feeding the expanded-
                            // panel arithmetic above, not the visible slice.
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { expandableContentHeightPx = it.height }
                                    .padding(horizontal = 16.dp)
                            ) {
                                ElevationSection(state, onHighlight = { highlightLatLng = it })
                                TextButton(onClick = { onDownloadOfflineMap(routeId) }) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.prepare_offline))
                                }
                            }
                        }
                    }

                    // Primary CTA — always visible, collapsed or expanded.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(RoutePreviewCtaHeight)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
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
        }
    }

    selectedWaypoint?.let { wp ->
        WaypointDetailSheet(waypoint = wp, onDismiss = { selectedWaypoint = null })
    }

    // Long-press Add sheet — same form as Home/Recording/Navigation. The
    // attachment picker is seeded with THIS route (Route Viewer context);
    // the user can switch to independent before saving.
    pendingWaypointTap?.let { tap ->
        WaypointFormSheet(
            title = stringResource(R.string.new_waypoint),
            initialName = "",
            initialCategory = com.nyasar.app.data.db.WaypointCategory.POI,
            initialNote = "",
            lat = tap.lat,
            lon = tap.lon,
            elevationM = tap.elevationM,
            attachments = com.nyasar.app.ui.waypoint.WaypointAttachments(
                routeId = routeId,
                routeName = state.name
            ),
            initialLinkedRouteId = routeId,
            onDismiss = waypointViewModel::dismissPendingTap,
            onSave = { name, category, note, linkedRouteId, linkedActivityId ->
                waypointViewModel.confirmAdd(name, category, note, linkedRouteId, linkedActivityId)
            }
        )
    }

    if (showBasemapSheet) {
        BasemapPickerSheet(
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

    // v7 "layar terakhir": the crosshair overlay reads THIS screen's live
    // camera, so it opens at exactly the last position+zoom the user was
    // viewing — no teleport, no re-zoom. Declared here because it needs
    // mapInstance/showCrosshair from the map Box above.
    val crosshairTarget by rememberCrosshairCameraState(mapInstance, showCrosshair)

    // v7 waypoint overlays (detail/crosshair/edit) — drawn after the main
    // content so they layer on top of it.
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
        crosshairTarget = crosshairTarget,
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

/** Fixed height of the always-visible Start Navigation CTA row — a constant
 *  so the peek-height arithmetic is stable across recompositions. */
private val RoutePreviewCtaHeight = 68.dp

// ---------------------------------------------------------------------------
// Panel content pieces
// ---------------------------------------------------------------------------

/** One compact stat chip: icon + value + localized label in a soft tile —
 *  the same visual language as ActivityDetailScreen's StatsGrid tiles, so
 *  both detail screens read as siblings. */
@Composable
private fun StatChip(icon: ImageVector, label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(NyasarRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** Elevation profile section — same chart component/data path as before,
 *  now living inside the expandable panel. */
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
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.elevation_profile),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))
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
    // (pressScale inside AnimatedAppear), and the shared fade-and-rise
    // entrance. Also used by the floating top pills this screen now uses
    // instead of a solid TopAppBar.
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

// ---------------------------------------------------------------------------
// v7 waypoint overlays. Composed AFTER the main content so they draw on top
// of it (a full-screen crosshair or bottom sheet emitted before the screen
// content would be covered by it).
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
    crosshairTarget: org.maplibre.android.geometry.LatLng?,
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
            cameraTarget = crosshairTarget,
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
