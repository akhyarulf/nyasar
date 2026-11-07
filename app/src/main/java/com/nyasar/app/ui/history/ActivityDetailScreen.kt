package com.nyasar.app.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.gpx.GpxExporter
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.navigation.LatLng
import com.nyasar.app.recording.ShareMetric
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.ElevationPoint
import com.nyasar.app.ui.components.ElevationProfile
import com.nyasar.app.ui.components.InlineStat
import com.nyasar.app.ui.components.InlineStatsGrid
import com.nyasar.app.ui.components.SplitsTable
import com.nyasar.app.ui.components.NyasarMapView
import com.nyasar.app.ui.components.SummaryStatTile
import com.nyasar.app.ui.theme.NyasarTrackGreen
import com.nyasar.app.util.SpeedUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    activityId: String,
    viewModel: ActivityDetailViewModel = viewModel(),
    waypointViewModel: com.nyasar.app.ui.waypoint.WaypointViewModel = viewModel(),
    /** Opt-in (Settings › Navigasi, default OFF): open a navigation session
     *  that follows THIS activity's recorded track in reverse. Host wires
     *  this to the "backtrack/{activityId}" route. */
    onStartBacktrack: (String) -> Unit = {},
    onBack: () -> Unit
) {
    LaunchedEffect(activityId) {
        waypointViewModel.setContext(com.nyasar.app.ui.waypoint.WaypointContext.Recording(activityId = activityId, routeId = null))
        viewModel.load(activityId)
    }
    val state by viewModel.uiState.collectAsState()
    val backtrackEnabled by viewModel.backtrackEnabled.collectAsState()
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // --- Export GPX via Storage Access Framework (spec P3G final fix) ---
    // Distinct from Share GPX (FileProvider + content:// into the share
    // sheet, unchanged below): this lets the user pick a permanent
    // location/filename themselves — CreateDocument is the standard SAF
    // launcher for "save this file somewhere the user chooses", separate
    // from GpxExporter itself, which still only ever writes to the app's
    // own cache dir. The GPX bytes are generated once via the existing
    // GpxExporter (single source of truth for GPX content — not
    // duplicated here), then copied into whatever Uri SAF hands back.
    var pendingExportFile by remember { mutableStateOf<java.io.File?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { destinationUri ->
        val sourceFile = pendingExportFile
        pendingExportFile = null
        if (destinationUri == null || sourceFile == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            android.widget.Toast.makeText(
                context,
                if (ok) context.getString(R.string.gpx_saved) else context.getString(R.string.gpx_save_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun exportGpxToDevice(activity: ActivityEntity, points: List<com.nyasar.app.data.db.ActivityPointEntity>, waypoints: List<com.nyasar.app.data.db.WaypointEntity>) {
        scope.launch {
            // P3I audit fix (§16): GpxExporter.exportActivity does
            // unprotected file I/O (file.bufferedWriter().use{...}, no
            // internal try/catch — see its own doc comment: caller's
            // responsibility). A disk-full or I/O error writing to
            // context.cacheDir here would previously throw uncaught inside
            // this launch{} coroutine, crashing the app — directly
            // violating spec §16 ("Jangan: crash" on storage failure). The
            // SAF-copy half of this same flow (createDocumentLauncher
            // below) already had this protection; only this temp-file-
            // write half was missing it.
            val file = try {
                withContext(Dispatchers.IO) {
                    GpxExporter.exportActivity(context, activity, points, waypoints)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.gpx_create_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            pendingExportFile = file
            createDocumentLauncher.launch(file.name)
        }
    }

    // Waypoint tap interaction (this fix's one focus). Reuses
    // WaypointViewModel/WaypointDetailSheet/WaypointFormSheet exactly as
    // HomeScreen does for the live map — same underlying WaypointEntity,
    // same repository, no second waypoint system. Selection/editing state
    // lives in WaypointViewModel (shared instance scoped to this screen's
    // NavBackStackEntry), not duplicated here.
    val selectedWaypoint by waypointViewModel.selectedWaypoint.collectAsState()
    val editingWaypoint by waypointViewModel.editingWaypoint.collectAsState()

    // Full-screen map state + fit padding (browse-detail parity). Declared
    // here (screen scope) so the overlay can draw over the whole Scaffold.
    var activityMapExpanded by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = activityMapExpanded) {
        activityMapExpanded = false
    }
    val activityFitPaddingPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        72.dp.toPx()
    }.toInt()

    // Speed unit setting. `.map {}` is wrapped in `remember`: chaining a Flow
    // operator directly in composition builds a NEW Flow on every
    // recomposition — a wasted allocation, and the reason lint flagged this
    // as FlowOperatorInvokedInComposition. Same shape as
    // rememberDefaultPublishVisibility().
    val settingsRepository = remember {
        com.nyasar.app.data.settings.SettingsRepository(context)
    }
    val speedUnitFlow = remember(settingsRepository) {
        settingsRepository.settings.map { it.speedUnit }
    }
    val speedUnit by speedUnitFlow.collectAsState(initial = "kmh")

    // Data-section "Waypoint" toggle (picker sheet) — local read-only
    // collect (no VM changes); this screen is a private-instance viewer, so
    // a local flow can't fight a shared map. Waypoints during THIS activity
    // are part of the activity record being reviewed.
    val waypointsVisibleFlow = remember(settingsRepository) {
        settingsRepository.settings.map { it.waypointsVisible }
    }
    val waypointsVisible by waypointsVisibleFlow.collectAsState(initial = true)

    // Layer-picker state for the fullscreen map (2026-09 user request):
    // basemap/overlay toggles live in the same app-wide DataStore the other
    // map screens use, so the chosen layers follow the user everywhere.
    // Tile provider stays the UiState snapshot (state.provider) — this VM
    // has no provider flow; basemapEntry below supersedes it anyway.
    val currentBasemap by viewModel.selectedBasemap.collectAsState()
    val activeOverlays by viewModel.activeOverlays.collectAsState()
    val myRoutesEnabled by viewModel.myRoutesOverlayEnabled.collectAsState()
    val myRouteLines by viewModel.myRouteLines.collectAsState()
    var showLayerSheet by remember { mutableStateOf(false) }

    // P3H Activity Photos were removed entirely from the app — no camera
    // or photo-picker launchers remain here.

    // A waypoint edited or deleted from here must also disappear/update in
    // this screen's own waypointsDuringActivity list — that list is a
    // one-shot query result (ActivityDetailViewModel.load), not an observed
    // Flow, so it doesn't pick up the change on its own. Each edit/delete
    // action below explicitly calls viewModel.load(activityId) again right
    // after the write completes, rather than adding a second live observer
    // on the waypoint table here (which is the "duplicate observers" the
    // spec warns against).

    // Collapsing header: the headline inside the scroll content already IS the
    // title at rest, so the app bar repeated the same string twice and spent
    // ~70dp doing it. It now appears only after the headline has scrolled out.
    val detailScrollState = rememberScrollState()
    val density = LocalDensity.current
    // 210dp ≈ where the headline's baseline passes under the app bar on a
    // screen with the 240dp hero map. A smaller threshold showed the title in
    // the bar while the headline was still visible — the duplicate again.
    val titleThreshold = with(density) { 210.dp.toPx() }.toInt()
    val showBarTitle by remember(detailScrollState) {
        derivedStateOf { detailScrollState.value > titleThreshold }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showBarTitle) {
                        Text(
                            state.activity?.name ?: stringResource(R.string.activity),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.activity != null) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options_cd))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                if (state.rawPoints.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.share_gpx)) },
                                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            state.activity?.let { exportGpxToDevice(it, state.rawPoints, state.waypointsDuringActivity) }
                                        }
                                    )
                                }
                                // Publish menu item REMOVED (IA "save = publish"):
                                // publishing now happens automatically at Save
                                // time on the Review form; the only remaining
                                // manual publish UI lives on Library routes
                                // (RoutePreview). EXCEPTION: a Wikiloc-style
                                // DRAFT publishes from here (publish-now).
                                if (state.activity?.status == com.nyasar.app.data.db.ActivityStatus.DRAFT) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.publish_draft_now)) },
                                        leadingIcon = { Icon(Icons.Default.Public, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            viewModel.publishDraftNow(activityId)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_activity)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = { showMenu = false; showRenameDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_activity)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = { showMenu = false; showDeleteDialog = true }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.loadState) {
                DetailLoadState.LOADING -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                DetailLoadState.NOT_FOUND -> {
                    Text(
                        stringResource(R.string.activity_not_found),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                }
                DetailLoadState.ERROR -> {
                    Text(
                        stringResource(R.string.activity_load_error),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                DetailLoadState.LOADED -> {
                    val activity = state.activity
                    if (activity != null) {
                        ActivityDetailContent(
                            activity = activity,
                            actualTrack = state.actualTrack,
                            gpsQuality = state.gpsQuality,
                            plannedTrack = state.plannedTrack,
                            plannedDistanceMeters = state.plannedDistanceMeters,
                            elevationProfile = state.elevationProfile,
                            highestElevationM = state.highestElevationM,
                            lowestElevationM = state.lowestElevationM,
                            provider = state.provider,
                            waypointsDuringActivity = state.waypointsDuringActivity,
                            rawPoints = state.rawPoints,
                            onWaypointTap = { wp -> waypointViewModel.selectWaypoint(wp) },
                            onShareGpx = { act, pts, wps ->
                                scope.launch {
                                    try {
                                        shareActivityGpx(context, act, pts, wps)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.gpx_create_failed),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            speedUnit = speedUnit,
                            waypointsVisible = waypointsVisible,
                            onExpandMap = { activityMapExpanded = true },
                            backtrackEnabled = backtrackEnabled,
                            onStartBacktrack = onStartBacktrack,
                            scrollState = detailScrollState
                        )
                        // Full-screen interactive map (Wikiloc pattern, same
                        // overlay shape as browse Route Detail / RoutePreview):
                        // tap the hero or its expand button, back closes.
                        if (activityMapExpanded) {
                            // Plain Box, not BoxWithConstraints: nothing inside
                            // this overlay reads maxWidth/maxHeight, so the
                            // constraint scope only cost a SubcomposeLayout
                            // pass (lint: UnusedBoxWithConstraintsScope).
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                                NyasarMapView(
                                    modifier = Modifier.fillMaxSize(),
                                    fitBoundsPaddingPx = activityFitPaddingPx,
                                    provider = state.provider,
                                    basemapEntry = currentBasemap,
                                    activeOverlays = activeOverlays,
                                    myRoutes = myRouteLines,
                                    track = if (state.plannedTrack.isNotEmpty()) state.plannedTrack else state.actualTrack,
                                    actualTrack = if (state.plannedTrack.isNotEmpty()) state.actualTrack else emptyList(),
                                    userWaypoints = state.waypointsDuringActivity,
                                    waypointsVisible = waypointsVisible
                                )
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    tonalElevation = 3.dp,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .statusBarsPadding()
                                        .padding(start = 12.dp, top = 8.dp)
                                        .size(48.dp)
                                ) {
                                    IconButton(onClick = { activityMapExpanded = false }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.back),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                // Layer picker — bottom-right like every other
                                // map screen (Home/RoutePreview/DrawRoute/browse
                                // detail); same button + sheet, same persisted
                                // app-wide toggles.
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .navigationBarsPadding()
                                        .padding(end = 16.dp, bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Surface(
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        tonalElevation = 3.dp,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        IconButton(onClick = { showLayerSheet = true }) {
                                            Icon(
                                                Icons.Default.Layers,
                                                contentDescription = stringResource(R.string.map_layer_cd),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Layer picker sheet (2026-09 user request) — same component and same
    // persisted toggles as every other map screen. Waypoint pins here show
    // THIS activity's own pins (waypointsDuringActivity), so the Data
    // "Waypoints" toggle applies; downloaded-areas coverage stays hidden
    // (no per-style coverage data wired on this screen).
    if (showLayerSheet) {
        com.nyasar.app.ui.components.BasemapPickerSheet(
            selected = currentBasemap,
            onSelect = { entry ->
                viewModel.setBasemap(entry)
                showLayerSheet = false
            },
            activeOverlays = activeOverlays,
            onToggleOverlay = { overlay -> viewModel.toggleOverlay(overlay) },
            myRoutesEnabled = myRoutesEnabled,
            onToggleMyRoutes = { viewModel.setMyRoutesOverlayEnabled(!myRoutesEnabled) },
            waypointsVisible = waypointsVisible,
            onToggleWaypoints = {
                scope.launch {
                    settingsRepository.setWaypointsVisible(!waypointsVisible)
                }
            },
            showOfflineAreasToggle = false,
            onDismiss = { showLayerSheet = false }
        )
    }

    // Edit Activity dialog (formerly just "Rename"): title + difficulty /
    // trail type / description / visibility, so a finished activity stays
    // correctable WITHOUT re-publishing anything by hand. Wikiloc parity.
    state.activity?.let { activity ->
        if (showRenameDialog) {
            com.nyasar.app.ui.publish.EditActivityDialog(
                activityId = activity.id,
                initialTitle = activity.name,
                onDismiss = { showRenameDialog = false },
                onSave = { title, sportType, difficulty, diffDesc, trailType, desc, isPublic ->
                    showRenameDialog = false
                    scope.launch {
                        viewModel.editActivity(activity.id, title, sportType, difficulty, diffDesc, trailType, desc, isPublic)
                    }
                }
            )
        }
    }

    // Delete confirmation (spec P3F §10, WAJIB confirmation)
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_confirm)) },
            text = { Text(stringResource(R.string.delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Publish sheet host REMOVED (IA "save = publish"): publishing moved to
    // the Review form's single Save action + offline queue. See PublishViewModel
    // .saveAndPublish / flushPendingPublishes.

    // Waypoint detail (spec P3F priority: tap marker/list → detail). Same
    // sheet HomeScreen uses for the live map — Edit/Delete are legitimate
    // here too (a hiker reviewing history may notice a mistyped name or
    // want to drop a duplicate pin), not a new capability invented for this
    // screen. distanceFromUserMeters is omitted (null) — this is a past
    // activity, not a live position, so "jarak dari Anda" has no meaningful
    // answer here; WaypointDetailSheet already treats that field as
    // optional and simply omits the row when null.
    selectedWaypoint?.let { wp ->
        com.nyasar.app.ui.waypoint.WaypointDetailSheet(
            waypoint = wp,
            distanceFromUserMeters = null,
            onDismiss = { waypointViewModel.selectWaypoint(null) },
            onEdit = { waypointViewModel.startEditing(wp) },
            onDelete = {
                waypointViewModel.deleteWaypoint(wp)
                viewModel.load(activityId)
            }
        )
    }

    // Edit sheet — reuses the same Add/Edit form HomeScreen uses.
    // v7: this screen's re-link option = THIS activity (history context);
    // GPX rows keep their intrinsic route link locked.
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
            attachments = com.nyasar.app.ui.waypoint.WaypointAttachments(
                activityId = activityId,
                activityName = state.activity?.name
            ),
            initialLinkedRouteId = wp.linkedRouteId,
            initialLinkedActivityId = wp.linkedActivityId,
            lockAttachment = wp.source == com.nyasar.app.data.db.WaypointEntity.SOURCE_GPX,
            onDismiss = waypointViewModel::dismissEditing,
            onSave = { name, cat, note, linkedRouteId, linkedActivityId ->
                waypointViewModel.confirmEditWithLinks(name, cat, note, linkedRouteId, linkedActivityId)
                viewModel.load(activityId)
            },
            onDelete = {
                waypointViewModel.deleteWaypoint(wp)
                viewModel.load(activityId)
            }
        )
    }

}

@Composable
private fun ActivityDetailContent(
    activity: ActivityEntity,
    actualTrack: List<TrackPoint>,
    gpsQuality: GpsQualityMetrics,
    plannedTrack: List<TrackPoint>,
    plannedDistanceMeters: Double?,
    elevationProfile: List<TrackPoint>,
    highestElevationM: Double?,
    lowestElevationM: Double?,
    provider: com.nyasar.app.map.TileProvider,
    waypointsDuringActivity: List<com.nyasar.app.data.db.WaypointEntity>,
    rawPoints: List<com.nyasar.app.data.db.ActivityPointEntity>,
    onWaypointTap: (com.nyasar.app.data.db.WaypointEntity) -> Unit,
    onShareGpx: (ActivityEntity, List<com.nyasar.app.data.db.ActivityPointEntity>, List<com.nyasar.app.data.db.WaypointEntity>) -> Unit,
    speedUnit: String = "kmh",
    waypointsVisible: Boolean = true,
    onExpandMap: () -> Unit = {},
    backtrackEnabled: Boolean = false,
    onStartBacktrack: (String) -> Unit = {},
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState()
) {
    var scrubbedPoint by remember { mutableStateOf<ElevationPoint?>(null) }
    var showSplits by remember { mutableStateOf(false) }
    var showGpsDetail by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Per-app locale: the date used to be hardcoded id-ID, so an English build
    // said "Minggu, 25 Oktober 2026" above English stats.
    val locale = context.resources.configuration.locales.get(0)
    // 40dp, not the map's 80px default (~29dp here): that default is tuned for
    // a full-screen map, and on a 240dp hero it let the track touch the frame
    // edges and clipped the labels at the top.
    val heroFitPaddingPx = with(LocalDensity.current) { 40.dp.toPx() }.toInt()
    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        // ── Hero map: FIXED 240dp full-bleed at the very top — the unified
        // block every detail screen shares (browse Route Detail, Route
        // Preview). Tap-anywhere + expand button open the full-screen map,
        // same two entry points as the other detail screens.
        if (actualTrack.isNotEmpty() || plannedTrack.isNotEmpty()) {
            Box {
            NyasarMapView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clickable { onExpandMap() },
                provider = provider,
                fitBoundsPaddingPx = heroFitPaddingPx,
                track = if (plannedTrack.isNotEmpty()) plannedTrack else actualTrack,
                actualTrack = if (plannedTrack.isNotEmpty()) actualTrack else emptyList(),
                highlightPoint = scrubbedPoint?.let { org.maplibre.android.geometry.LatLng(it.lat, it.lon) },
                userWaypoints = waypointsDuringActivity,
                waypointsVisible = waypointsVisible,
                onUserWaypointClick = { id ->
                    waypointsDuringActivity.firstOrNull { it.id == id }?.let(onWaypointTap)
                }
            )
            // Expand button — same floating round control as the other
            // detail screens' hero maps.
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(44.dp)
            ) {
                IconButton(onClick = onExpandMap) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = stringResource(R.string.map_expand_cd),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            }
            // Legend BELOW the map, not floating on it: inside the Box it sat
            // on top of the basemap's own labels/attribution and was unreadable.
            if (plannedTrack.isNotEmpty()) {
                TrackLegend()
            }
        } else {
            // No points recorded — don't error, just skip the map (spec:
            // "jika activity tidak memiliki route, jangan error").
            Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_gps_points),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Unified header: NAME first (big, Strava-style — previously only
            // in the top bar), date below it, then the inline stats grid.
            Text(
                activity.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                formatActivityDateTime(activity.startedAtEpochMs, locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Stats used to be ONE flat 7-cell grid, so the third row held a
            // single orphan (Elevation loss) and the eye had to work out which
            // numbers belonged together. Two labeled blocks of exactly three
            // instead: what the activity WAS, then how fast it moved.
            val primaryMetric = SportType.fromString(activity.sportType).primaryMetric
            SectionHeader(stringResource(R.string.activity_section_summary))
            InlineStatsGrid(
                listOf(
                    InlineStat(
                        stringResource(R.string.stat_distance),
                        "%.2f km".format(activity.distanceMeters / 1000.0)
                    ),
                    InlineStat(
                        stringResource(R.string.stat_moving_time),
                        formatDuration(activity.movingTimeMs)
                    ),
                    InlineStat(
                        stringResource(R.string.elevation_gain),
                        activity.elevationGainM?.let { "+${it.roundToInt()} m" } ?: "—"
                    )
                )
            )

            // Pace only where it means something: "24:00 /km" on a hike is the
            // reciprocal of walking speed, not a number a hiker reads — hikes
            // get total time there instead. Either way: three cells, never a
            // two-cell row with a hole.
            SectionHeader(stringResource(R.string.speed))
            InlineStatsGrid(
                buildList {
                    if (primaryMetric == ShareMetric.PACE) {
                        add(
                            InlineStat(
                                stringResource(R.string.pace),
                                formatPace(activity.distanceMeters, activity.movingTimeMs)
                            )
                        )
                    }
                    add(
                        InlineStat(
                            stringResource(R.string.stat_avg_speed),
                            activity.avgSpeedKmh?.let { SpeedUtils.formatSpeed(it, speedUnit, 1) } ?: "—"
                        )
                    )
                    add(
                        InlineStat(
                            stringResource(R.string.stat_max_speed),
                            activity.maxSpeedKmh?.let { SpeedUtils.formatSpeed(it, speedUnit, 1) } ?: "—"
                        )
                    )
                    // Hikes keep a third cell too (no orphan row): total time
                    // is the number a hiker reads next to speed, long before
                    // pace. Elevation loss lives with the elevation summary.
                    if (primaryMetric != ShareMetric.PACE) {
                        add(
                            InlineStat(
                                stringResource(R.string.stat_total_time),
                                formatDuration(activity.elapsedTimeMs)
                            )
                        )
                    }
                }
            )

            // GPS quality: one verdict a person can act on, with the numbers
            // behind it one tap away — the old version led with "weak fixes"
            // and "largest fix gap" and read like a debug log.
            if (rawPoints.isNotEmpty()) {
                val quality = gpsQuality
                SectionHeader(stringResource(R.string.gps_quality_title))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GpsAccuracyBadge(quality.averageAccuracyMeters)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            R.string.gps_quality_summary,
                            "±${quality.averageAccuracyMeters?.roundToInt() ?: 0} m",
                            quality.pointCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = { showGpsDetail = !showGpsDetail },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text(
                        stringResource(
                            if (showGpsDetail) R.string.gps_quality_detail_hide
                            else R.string.gps_quality_detail_show
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (showGpsDetail) {
                    Text(
                        stringResource(
                            R.string.gps_quality_extra,
                            quality.weakPercent,
                            formatGap(quality.largestGapMs),
                            quality.bestAccuracyMeters?.roundToInt() ?: 0,
                            quality.worstAccuracyMeters?.roundToInt() ?: 0
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (plannedDistanceMeters != null) {
                SectionHeader(stringResource(R.string.planned_vs_actual))
                // Two numbers side by side made the reader subtract them by
                // hand; bars the reader can compare at a glance, in the same
                // colours as the map (blue planned, green actual).
                PlannedVsActualBars(
                    plannedDistanceMeters = plannedDistanceMeters,
                    actualDistanceMeters = activity.distanceMeters
                )
            }

            val elevationPoints = remember(elevationProfile) {
                ElevationStats.toElevationProfile(elevationProfile)
            }
            if (elevationPoints.size >= 2) {
                SectionHeader(stringResource(R.string.route_detail_elevation_title))
                ElevationProfile(
                    points = elevationPoints,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    onPointSelected = { _, point -> scrubbedPoint = point }
                )
                Spacer(Modifier.height(12.dp))
                // Summary cells — ONLY highest/lowest (de-dup): gain & loss
                // already appear in the header stats above, repeating them
                // here read as double data. Fed from this activity's OWN
                // elevation data.
                Row(Modifier.fillMaxWidth()) {
                    highestElevationM?.let {
                        SummaryStatTile(
                            "${it.roundToInt()} m",
                            stringResource(R.string.stat_highest_point),
                            Modifier.weight(1f)
                        )
                    } ?: Spacer(Modifier.weight(1f))
                    lowestElevationM?.let {
                        SummaryStatTile(
                            "${it.roundToInt()} m",
                            stringResource(R.string.stat_lowest_point),
                            Modifier.weight(1f)
                        )
                    } ?: Spacer(Modifier.weight(1f))
                    // Descent belongs with the profile that shows it, not in a
                    // speed block three sections up.
                    activity.elevationLossM?.let {
                        SummaryStatTile(
                            "−${it.roundToInt()} m",
                            stringResource(R.string.stat_elev_loss),
                            Modifier.weight(1f)
                        )
                    } ?: Spacer(Modifier.weight(1f))
                }
            }

            if (rawPoints.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TextButton(
                    onClick = { showSplits = !showSplits },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (showSplits) stringResource(R.string.splits) + " ▲" else stringResource(R.string.splits) + " ▼",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (showSplits) {
                    SplitsTable(points = rawPoints)
                }
            }

            if (rawPoints.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onShareGpx(activity, rawPoints, waypointsDuringActivity) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bagikan_gpx))
                }
            }

            // Backtrack (2026-11, opt-in — Settings › Navigasi, default OFF):
            // second button, outlined so it never competes with Share as the
            // primary action. Same recorded points, just walked backwards.
            if (rawPoints.isNotEmpty() && backtrackEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onStartBacktrack(activity.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backtrack_start_button))
                }
            }

            // P3E3 fix #2: list form of the same waypoints shown on the map
            // above — a marker on a 260dp map is easy to miss/mis-tap, the
            // list makes them scannable and gives each one a readable name.
            if (waypointsDuringActivity.isNotEmpty()) {
                SectionHeader("Waypoint (${waypointsDuringActivity.size})")
                waypointsDuringActivity.forEach { wp ->
                    ActivityWaypointRow(wp, onClick = { onWaypointTap(wp) })
                }
            } else {
                // Empty state (spec §11: "jika tidak memiliki waypoint,
                // tampilkan empty state ringan") — previously this section
                // simply didn't render at all when empty, giving no
                // indication waypoints were even a feature of the app.
                SectionHeader(stringResource(R.string.waypoint_label))
                Text(
                    stringResource(R.string.no_waypoints_recorded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

/** Route colours shared by the map legend and the planned-vs-actual bars, so
 *  "blue = planned, green = actual" means the same thing everywhere on screen. */
private val PlannedTrackBlue = Color(0xFF42A5F5)

/** One heading style for every section on this screen. Previously the same
 *  level used titleMedium, titleSmall and body weights, and the gaps before
 *  them were 12/16dp depending on which block you were in. */
@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
}

/** Accuracy verdict pill: good/fair/poor from the average fix radius, in the
 *  theme's own containers rather than invented colours. */
@Composable
private fun GpsAccuracyBadge(averageAccuracyMeters: Float?) {
    val (labelRes, container, content) = when {
        averageAccuracyMeters == null || averageAccuracyMeters > 20f -> Triple(
            R.string.gps_accuracy_poor,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        averageAccuracyMeters > 8f -> Triple(
            R.string.gps_accuracy_fair,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        else -> Triple(
            R.string.gps_accuracy_good,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** Two distance bars on a shared scale — the longer distance fills the track, so
 *  "planned 3.55 vs actual 3.83" is a length you can see instead of two numbers
 *  you have to compare in your head. */
@Composable
private fun PlannedVsActualBars(plannedDistanceMeters: Double, actualDistanceMeters: Double) {
    val maxDistance = maxOf(plannedDistanceMeters, actualDistanceMeters).coerceAtLeast(1.0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DistanceBar(
            label = stringResource(R.string.compare_planned),
            distanceMeters = plannedDistanceMeters,
            maxDistanceMeters = maxDistance,
            color = PlannedTrackBlue
        )
        DistanceBar(
            label = stringResource(R.string.compare_actual),
            distanceMeters = actualDistanceMeters,
            maxDistanceMeters = maxDistance,
            color = NyasarTrackGreen
        )
    }
}

@Composable
private fun DistanceBar(
    label: String,
    distanceMeters: Double,
    maxDistanceMeters: Double,
    color: Color
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((distanceMeters / maxDistanceMeters).toFloat().coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "%.2f km".format(distanceMeters / 1000.0),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp)
        )
    }
}

private fun formatActivityDateTime(epochMs: Long, locale: Locale): String =
    SimpleDateFormat("EEEE, d MMMM yyyy · HH:mm", locale).format(Date(epochMs))

@Composable
private fun TrackLegend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        LegendItem(color = PlannedTrackBlue, label = stringResource(R.string.route_planned))
        // Matches the line actually drawn on maps (NyasarTrackGreen).
        LegendItem(color = NyasarTrackGreen, label = stringResource(R.string.track_actual))
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(width = 16.dp, height = 4.dp)
                .background(color, MaterialTheme.shapes.small)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ActivityWaypointRow(waypoint: com.nyasar.app.data.db.WaypointEntity, onClick: () -> Unit) {
    val category = com.nyasar.app.data.db.WaypointCategory.fromStorageValue(waypoint.category)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            category.icon,
            contentDescription = null,
            tint = category.color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(waypoint.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(category.labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        waypoint.elevationM?.let {
            Text(
                "${it.roundToInt()} m",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


private fun formatPace(distanceMeters: Double, movingTimeMs: Long): String {
    if (distanceMeters <= 0.0 || movingTimeMs <= 0L) return "-"
    val secondsPerKm = movingTimeMs / 1000.0 / (distanceMeters / 1000.0)
    return "%d:%02d /km".format((secondsPerKm / 60).toInt(), (secondsPerKm % 60).toInt())
}

private fun formatGap(millis: Long): String {
    if (millis <= 0L) return "-"
    return if (millis < 60_000L) "${millis / 1000}s" else "${millis / 60_000L}m"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Spec: "Export GPX" / "Share activity" — writes recorded points (+ any
 *  waypoints dropped during the session) as GPX via [GpxExporter], then
 *  hands it to any app through the standard share sheet using FileProvider
 *  (declared in AndroidManifest) so no raw file:// Uri ever leaves the app.
 *
 *  `suspend` + Dispatchers.IO for the file write — spec §11: activities can
 *  have tens of thousands of points, and this used to run the write
 *  directly on the click handler's (main) thread, which would visibly
 *  freeze the UI for a moment on a long hike's worth of points. */
internal suspend fun shareActivityGpx(
    context: android.content.Context,
    activity: ActivityEntity,
    points: List<com.nyasar.app.data.db.ActivityPointEntity>,
    waypoints: List<com.nyasar.app.data.db.WaypointEntity>
) {
    val file = withContext(Dispatchers.IO) {
        GpxExporter.exportActivity(context, activity, points, waypoints)
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    // Spec §8: share text alongside the file — real Activity data, not a
    // placeholder. Duration uses elapsedTimeMs (total including pauses,
    // same figure the detail screen's own stat row shows) rather than
    // movingTimeMs, so this line matches what the user sees on-screen.
    val shareText = buildString {
        appendLine(activity.name)
        appendLine()
        append("Distance: %.2f km".format(activity.distanceMeters / 1000.0))
        appendLine()
        append("Duration: ${formatDuration(activity.elapsedTimeMs)}")
        activity.elevationGainM?.let {
            appendLine()
            append("Elevation Gain: +${it.roundToInt()} m")
        }
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
        putExtra(android.content.Intent.EXTRA_SUBJECT, activity.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.bagikan_gpx)))
}
