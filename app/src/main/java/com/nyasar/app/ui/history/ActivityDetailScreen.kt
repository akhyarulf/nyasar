package com.nyasar.app.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.gpx.GpxExporter
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.navigation.LatLng
import com.nyasar.app.ui.components.ElevationPoint
import com.nyasar.app.ui.components.ElevationProfile
import com.nyasar.app.ui.components.SplitsTable
import com.nyasar.app.ui.components.NyasarMapView
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
    onBack: () -> Unit
) {
    LaunchedEffect(activityId) { viewModel.load(activityId) }
    val state by viewModel.uiState.collectAsState()
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

    // Speed unit setting
    val settingsRepository = remember {
        com.nyasar.app.data.settings.SettingsRepository(context)
    }
    val speedUnit by settingsRepository.settings
        .map { it.speedUnit }
        .collectAsState(initial = "kmh")

    // --- P3H: Activity Photos ---
    val photos by viewModel.photos.collectAsState()
    var showAddPhotoChooser by remember { mutableStateOf(false) }
    var viewerStartIndex by remember { mutableStateOf<Int?>(null) }
    // Holds the camera destination between "launch camera" and "camera
    // returned" — needed in both the success and cancel branches of the
    // TakePicture callback below (confirm vs. discardCameraCapture).
    var pendingCameraFile by remember { mutableStateOf<java.io.File?>(null) }

    // P3I audit fix (§16/§22): surfaces confirmCameraCapture/
    // addPhotosFromGallery/deletePhoto failures as a Toast instead of
    // leaving viewModel.photoError set with nothing ever reading it.
    val photoError by viewModel.photoError.collectAsState()
    LaunchedEffect(photoError) {
        photoError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearPhotoError()
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (file == null) return@rememberLauncherForActivityResult
        if (success) {
            viewModel.confirmCameraCapture(activityId, file)
        } else {
            // Spec §2: cancelled capture must not create an empty record —
            // confirmCameraCapture is simply never called; this only cleans
            // up whatever placeholder file the camera app may have touched.
            viewModel.discardCameraCapture(file)
        }
    }

    val pickPhotosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPhotosFromGallery(activityId, uris)
    }

    fun launchCamera() {
        scope.launch {
            // P3I audit fix (§16): prepareCameraCapture does file I/O
            // (mkdirs for the destination) which can fail on a full/
            // inaccessible disk — same crash risk pattern as the other
            // photo operations above, fixed the same way.
            val result = try {
                viewModel.prepareCameraCapture(activityId)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.camera_storage_error),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val (file, uri) = result
            pendingCameraFile = file
            takePictureLauncher.launch(uri)
        }
    }

    // A waypoint edited or deleted from here must also disappear/update in
    // this screen's own waypointsDuringActivity list — that list is a
    // one-shot query result (ActivityDetailViewModel.load), not an observed
    // Flow, so it doesn't pick up the change on its own. Each edit/delete
    // action below explicitly calls viewModel.load(activityId) again right
    // after the write completes, rather than adding a second live observer
    // on the waypoint table here (which is the "duplicate observers" the
    // spec warns against).

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.activity?.name ?: stringResource(R.string.activity), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
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
                            photos = photos,
                            onAddPhotoClick = { showAddPhotoChooser = true },
                            onPhotoClick = { index -> viewerStartIndex = index },
                            speedUnit = speedUnit
                        )
                    }
                }
            }
        }
    }

    // Rename dialog (spec P3F §9)
    if (showRenameDialog) {
        var text by remember(state.activity?.id) { mutableStateOf(state.activity?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_activity)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(text)
                    showRenameDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
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
            onSave = { name, cat, note ->
                waypointViewModel.confirmEdit(name, cat, note)
                viewModel.load(activityId)
            },
            onDelete = {
                waypointViewModel.deleteWaypoint(wp)
                viewModel.load(activityId)
            }
        )
    }

    // --- P3H sheets/dialogs ---
    if (showAddPhotoChooser) {
        AddPhotoChooserSheet(
            onTakePhoto = { launchCamera() },
            onChooseFromGallery = {
                pickPhotosLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onDismiss = { showAddPhotoChooser = false }
        )
    }

    viewerStartIndex?.let { index ->
        FullscreenPhotoViewer(
            photos = photos,
            startIndex = index,
            onDismiss = { viewerStartIndex = null },
            onDelete = { photo -> viewModel.deletePhoto(photo) }
        )
    }
}

@Composable
private fun ActivityDetailContent(
    activity: ActivityEntity,
    actualTrack: List<TrackPoint>,
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
    photos: List<com.nyasar.app.data.db.ActivityPhotoEntity>,
    onAddPhotoClick: () -> Unit,
    onPhotoClick: (Int) -> Unit,
    speedUnit: String = "kmh"
) {
    var scrubbedPoint by remember { mutableStateOf<ElevationPoint?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (actualTrack.isNotEmpty() || plannedTrack.isNotEmpty()) {
            // and overlay the actual walked track via `actualTrack` (green)
            // — the same second-line mechanism live recording already uses.
            // Route-less recordings (no plannedTrack) keep the old
            // single-line look: actualTrack drawn as `track` so it isn't
            // shown twice in the same color for no reason.
            NyasarMapView(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                provider = provider,
                track = if (plannedTrack.isNotEmpty()) plannedTrack else actualTrack,
                actualTrack = if (plannedTrack.isNotEmpty()) actualTrack else emptyList(),
                highlightPoint = scrubbedPoint?.let { org.maplibre.android.geometry.LatLng(it.lat, it.lon) },
                // P3E3 fix #2: waypoints dropped during this recording
                // session, shown on the same map as the actual track —
                // previously ActivityDetail never queried waypoints at
                // all, so a hiker's own trail markers vanished from view
                // the moment they left Home.
                userWaypoints = waypointsDuringActivity,
                // This fix's one focus: tap a marker → open its detail.
                // Uses the same onUserWaypointClick callback + LAYER_USER_
                // WAYPOINTS hit-testing NyasarMapView already implements for
                // HomeScreen's live map — no map-engine change needed here,
                // just wiring the existing callback through. queryRendered
                // Features hit-testing only fires on an actual tap on the
                // marker, so 1-finger pan and pinch zoom (which never reach
                // addOnMapClickListener) are unaffected — see NyasarMapView.
                onUserWaypointClick = { id ->
                    waypointsDuringActivity.firstOrNull { it.id == id }?.let(onWaypointTap)
                }
            )
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
            Modifier.fillMaxWidth().padding(16.dp)
        ) {
            // Header date/start time (spec P3F §2 "HEADER: Date, Start time
            // jika tersedia"). activity.name is already the TopAppBar title,
            // so this only adds the when — startedAtEpochMs always exists
            // (set when recording begins, never null), unlike endedAtEpochMs.
            Text(
                formatActivityDateTime(activity.startedAtEpochMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            StatsGrid(activity, actualTrack.size, highestElevationM, lowestElevationM, speedUnit)

            // Planned vs Actual (spec P3F §4) — distance only, see
            // ActivityDetailViewModel for why elevation isn't compared here.
            // Hidden entirely when there's no route, per spec: "jika tidak
            // ada planned route, sembunyikan bagian comparison."
            if (plannedDistanceMeters != null) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.planned_vs_actual), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                PlannedVsActualRow(
                    plannedDistanceMeters = plannedDistanceMeters,
                    actualDistanceMeters = activity.distanceMeters
                )
            }

            val elevationPoints = remember(elevationProfile) {
                ElevationStats.toElevationProfile(elevationProfile)
            }
            if (elevationPoints.size >= 2) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.elevation_profile), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ElevationProfile(
                    points = elevationPoints,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    onPointSelected = { _, point -> scrubbedPoint = point }
                )
            }

            // Splits per-km table — only shown if activity >= 1 km
            if (rawPoints.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.splits), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SplitsTable(points = rawPoints)
            }

            // Share GPX button — moved from top bar to below elevation profile
            if (rawPoints.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { onShareGpx(activity, rawPoints, waypointsDuringActivity) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bagikan_gpx))
                }
            }

            // P3E3 fix #2: list form of the same waypoints shown on the map
            // above — a marker on a 260dp map is easy to miss/mis-tap, the
            // list makes them scannable and gives each one a readable name.
            if (waypointsDuringActivity.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Waypoint (${waypointsDuringActivity.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                waypointsDuringActivity.forEach { wp ->
                    ActivityWaypointRow(wp, onClick = { onWaypointTap(wp) })
                }
            } else {
                // Empty state (spec §11: "jika tidak memiliki waypoint,
                // tampilkan empty state ringan") — previously this section
                // simply didn't render at all when empty, giving no
                // indication waypoints were even a feature of the app.
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.waypoint_label), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.no_waypoints_recorded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // P3H: Photos section — spec §20 places this after Waypoints,
            // before Export/Share (Export/Share is the TopAppBar action,
            // already above; nothing else to reorder here).
            Spacer(Modifier.height(24.dp))
            PhotosSection(
                photos = photos,
                onAddClick = onAddPhotoClick,
                onPhotoClick = onPhotoClick
            )
        }
    }
}

@Composable
private fun PlannedVsActualRow(plannedDistanceMeters: Double, actualDistanceMeters: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("%.2f km".format(plannedDistanceMeters / 1000.0), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.planned_distance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text("%.2f km".format(actualDistanceMeters / 1000.0), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.actual_distance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatActivityDateTime(epochMs: Long): String =
    SimpleDateFormat("EEEE, d MMMM yyyy · HH:mm", Locale("id", "ID")).format(Date(epochMs))

@Composable
private fun TrackLegend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        LegendItem(color = androidx.compose.ui.graphics.Color(0xFF42A5F5), label = stringResource(R.string.route_planned))
        LegendItem(color = androidx.compose.ui.graphics.Color(0xFF00C853), label = stringResource(R.string.track_actual))
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
                category.label,
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

@Composable
private fun StatsGrid(
    activity: ActivityEntity,
    pointCount: Int,
    highestElevationM: Double?,
    lowestElevationM: Double?,
    speedUnit: String = "kmh"
) {
    val context = LocalContext.current
    val rows = buildList {
        add(context.getString(R.string.stat_distance) to "%.2f km".format(activity.distanceMeters / 1000.0))
        add(context.getString(R.string.stat_moving_time) to formatDuration(activity.movingTimeMs))
        add(context.getString(R.string.stat_total_time) to formatDuration(activity.elapsedTimeMs))
        activity.avgSpeedKmh?.let { add(context.getString(R.string.stat_avg_speed) to com.nyasar.app.util.SpeedUtils.formatSpeed(it, speedUnit, 1)) }
        activity.maxSpeedKmh?.let { add(context.getString(R.string.stat_max_speed) to com.nyasar.app.util.SpeedUtils.formatSpeed(it, speedUnit, 1)) }
        activity.elevationGainM?.let { add(context.getString(R.string.elevation_gain) to "↑ ${it.roundToInt()} m") }
        activity.elevationLossM?.let { add(context.getString(R.string.stat_elev_loss) to "↓ ${it.roundToInt()} m") }
        highestElevationM?.let { add(context.getString(R.string.stat_highest_point) to "${it.roundToInt()} m") }
        lowestElevationM?.let { add(context.getString(R.string.stat_lowest_point) to "${it.roundToInt()} m") }
        if (pointCount > 0) add(context.getString(R.string.stat_gps_points) to "$pointCount")
    }

    Column {
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(value, style = MaterialTheme.typography.titleLarge)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
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
