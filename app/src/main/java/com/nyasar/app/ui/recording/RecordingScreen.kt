package com.nyasar.app.ui.recording

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.recording.RecordingService
import com.nyasar.app.recording.RecordingStatus
import com.nyasar.app.recording.RecordingUiState
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.CameraFollowMode
import com.nyasar.app.ui.components.CompassButton
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R
import com.nyasar.app.ui.components.NyasarMapView
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * P2 gap closed: the map is now the dominant element here too (spec section
 * 3, "SANGAT PENTING: saat recording, map harus menampilkan posisi, heading,
 * dan jejak yang sudah dilewati, digambar realtime") — this used to be a
 * stats-only screen with no map at all.
 *
 * PART 4: shows the picked GPX track (previewTrack, from "Pilih Jalur" or
 * routeId) alongside the live recorded track (state.recordedTrack) and the
 * current GPS position — together, for the whole session (IDLE through
 * PAUSED), not just before recording starts. No off-route detection or
 * warning: the user reads both lines on the map and judges for themselves
 * whether they're near the planned route.
 *
 * routeId is accepted primarily to link the saved activity in the
 * database, but its track (via previewRouteId/previewTrack below) is now
 * also drawn on the map like a Track Picker selection.
 *
 * Provider is read directly via TileProviderFactory.default() (same
 * pattern as OfflineDownloadScreen) rather than through Settings — this
 * screen has no ViewModel dependency on SettingsRepository yet; wiring that
 * up is a separate, smaller follow-up, not blocking the live-map fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    routeId: String? = null,
    autoStart: Boolean = true,
    viewModel: RecordingViewModel = viewModel(),
    // Same gap fix as NavigationScreen (P3E3): route-less recording had no
    // path to drop a waypoint either, only Home did. Same WaypointViewModel,
    // waypoints are process-wide and not tied to any route/activity.
    waypointViewModel: com.nyasar.app.ui.waypoint.WaypointViewModel = viewModel(),
    onExit: () -> Unit,
    // Only shown before the user taps Play (RecordingStatus.IDLE) — see
    // RecordingControls below. Once recording has actually started there's
    // no sensible "add a route" action anymore (the session is already
    // running route-less or with the route it started with), so this is
    // never shown mid-recording.
    onAddRoute: () -> Unit = {},
    // PART 3: return path for a track picked via "Pilih Jalur" — set by
    // MainActivity when TrackAndMapsScreen (in pick mode) pops back with a
    // selection, same MainActivity-hoisted-state pattern already used for
    // pendingImportUri/pendingFocusBounds elsewhere in this codebase, not
    // a new mechanism.
    pendingSelectedRouteId: String? = null,
    onSelectedRouteConsumed: () -> Unit = {},
    // PART 3 bottom-bar-overlap fix: reports true exactly while the bottom
    // tab bar must stay hidden (RECORDING, PAUSED, or the Summary overlay)
    // so MainActivity can suppress it without this screen needing any
    // knowledge of the bar itself.
    onRecordingActiveChanged: (Boolean) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val recoveryCandidate by viewModel.recoveryCandidate.collectAsState()
    var recoveryChecked by remember { mutableStateOf(false) }
    // PART 3.5 QA fix — see the LaunchedEffect below for the bug this
    // guards against. Consumed exactly once per screen visit, same
    // "boolean flips true and never back" shape as `recoveryChecked` right
    // above it.
    var autoStartConsumed by remember { mutableStateOf(false) }
    // P3J §6: guards the Stop button — see the AlertDialog near the bottom
    // of this function for why.
    var showStopConfirm by remember { mutableStateOf(false) }
    var showNotMovingFromStop by remember { mutableStateOf(false) }
    var showBasemapSheet by remember { mutableStateOf(false) }
    val styleVariant by viewModel.styleVariant.collectAsState()
    val selectedBasemap by viewModel.selectedBasemap.collectAsState()
    val provider = remember { TileProviderFactory.default() }
    val userWaypoints by waypointViewModel.waypoints.collectAsState()
    val pendingWaypointTap by waypointViewModel.pendingTap.collectAsState()
    val selectedWaypoint by waypointViewModel.selectedWaypoint.collectAsState()
    val editingWaypoint by waypointViewModel.editingWaypoint.collectAsState()

    // Part 3: the service can legitimately report STOPPED right after a
    // just-finished session (or a stray leftover from before this screen's
    // current visit) — per the spec, the UI must treat that identically to
    // IDLE (same two buttons, same everything), never show a dead-end
    // "STOPPED" state with nothing to press. This is purely a display-time
    // normalization; state.status itself (and everything derived from it
    // in RecordingViewModel, e.g. the readyForNewSession autostart guard
    // already elsewhere in this file) is untouched.
    val effectiveStatus = if (state.status == RecordingStatus.STOPPED) RecordingStatus.IDLE else state.status

    // --- PART 3: "Pilih Jalur" — a route picked from Track & Peta while
    // still IDLE, previewed on the map before recording starts. Also seeded
    // from the `routeId` parameter (PART 4 fix — arriving here already
    // "attached" to a route, e.g. from Route Preview via Start Activity's
    // "Record Only" option, previously never populated previewTrack at all,
    // so that route's GPX line silently never appeared on this screen even
    // though the activity being recorded was linked to it in the database).
    // Cleared the moment the user returns from the Summary screen (a
    // finished session shouldn't silently carry a stale picked-track into
    // the next one) — never re-seeded from routeId after that, since a
    // fresh IDLE session from here on is genuinely route-less unless the
    // user picks again via "Pilih Jalur".
    var previewRouteId by remember { mutableStateOf(routeId) }
    var previewRouteName by remember { mutableStateOf<String?>(null) }
    var previewTrack by remember { mutableStateOf<List<com.nyasar.app.gpx.model.TrackPoint>>(emptyList()) }
    var showSportFilterSheet by remember { mutableStateOf(false) }
    val pickerContext = androidx.compose.ui.platform.LocalContext.current
    val routeRepository = remember { com.nyasar.app.data.repository.RouteRepository(pickerContext) }

    LaunchedEffect(pendingSelectedRouteId) {
        val picked = pendingSelectedRouteId ?: return@LaunchedEffect
        previewRouteId = picked
        onSelectedRouteConsumed()
    }

    LaunchedEffect(previewRouteId) {
        val id = previewRouteId
        if (id == null) {
            previewRouteName = null
            previewTrack = emptyList()
            return@LaunchedEffect
        }
        val route = routeRepository.getRoute(id) ?: return@LaunchedEffect
        previewRouteName = route.name
        previewTrack = try {
            routeRepository.loadDocument(route).allTrackPoints
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- PART 3: Summary screen, shown after Stop is confirmed instead of
    // exiting immediately. Snapshotted once (not read live from `state`)
    // so it stays stable on screen even if the service moves on / resets
    // for a future session while the user is still looking at it.
    var stopRequested by remember { mutableStateOf(false) }
    var summarySnapshot by remember { mutableStateOf<RecordingUiState?>(null) }
    var postRecordingPhotos by remember { mutableStateOf<List<com.nyasar.app.data.db.ActivityPhotoEntity>>(emptyList()) }
    var showPostRecordingPhotoChooser by remember { mutableStateOf(false) }
    var pendingPostRecordingCameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val postRecordingContext = androidx.compose.ui.platform.LocalContext.current
    val postRecordingScope = rememberCoroutineScope()
    val postRecordingPhotoRepository = remember {
        com.nyasar.app.data.repository.ActivityPhotoRepository(postRecordingContext)
    }

    // Photo launcher for post-recording form
    val postRecordingTakePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingPostRecordingCameraFile
        pendingPostRecordingCameraFile = null
        if (file != null && success) {
            postRecordingScope.launch {
                summarySnapshot?.activityId?.let { activityId ->
                    postRecordingPhotoRepository.confirmCameraCapture(activityId, file)
                    postRecordingPhotos = postRecordingPhotoRepository.getPhotosForActivity(activityId)
                }
            }
        } else if (file != null) {
            postRecordingScope.launch {
                postRecordingPhotoRepository.discardCameraCapture(file)
            }
        }
    }

    val postRecordingPickPhotosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            postRecordingScope.launch {
                summarySnapshot?.activityId?.let { activityId ->
                    postRecordingPhotoRepository.addFromGallery(activityId, uris)
                    postRecordingPhotos = postRecordingPhotoRepository.getPhotosForActivity(activityId)
                }
            }
        }
    }

    LaunchedEffect(stopRequested, state.status) {
        if (stopRequested && state.status == RecordingStatus.STOPPED) {
            summarySnapshot = state
            stopRequested = false
            // Load existing photos (should be empty for new recordings)
            state.activityId?.let { activityId ->
                postRecordingScope.launch {
                    postRecordingPhotos = postRecordingPhotoRepository.getPhotosForActivity(activityId)
                }
            }
        }
    }

    LaunchedEffect(effectiveStatus, summarySnapshot) {
        onRecordingActiveChanged(
            effectiveStatus == RecordingStatus.RECORDING ||
                effectiveStatus == RecordingStatus.PAUSED ||
                summarySnapshot != null
        )
    }

    // Settings > Recording > "keep screen awake" (spec) — a real device
    // effect, not a stored-but-unused flag: keeps the screen on for as long
    // as this composable is on screen while recording is actually active.
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepository = remember {
        com.nyasar.app.data.settings.SettingsRepository(context)
    }
    val keepScreenOn by settingsRepository.settings
        .map { it.keepScreenOnWhileRecording }
        .collectAsState(initial = true)
    val speedUnit by settingsRepository.settings
        .map { it.speedUnit }
        .collectAsState(initial = "kmh")
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(keepScreenOn, state.status) {
        val shouldKeepOn = keepScreenOn && state.status != RecordingStatus.STOPPED
        view.keepScreenOn = shouldKeepOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        viewModel.checkForRecovery()
        recoveryChecked = true
    }

    // Permission was already requested app-wide in MainActivity (same
    // comment/reasoning as HomeScreen's identical block); this just starts
    // the pre-record GPS preview once it's actually granted.
    LaunchedEffect(Unit) {
        viewModel.startLocationUpdatesIfPermitted()
    }

    LaunchedEffect(recoveryChecked, recoveryCandidate, state.status) {
        // Part 2 fix (BUG #2/#15 "Stop -> Start membuat session baru"): the
        // guard used to require state.status == IDLE specifically, which
        // meant a STOPPED status still lingering in this ViewModel's
        // observed state (e.g. this screen re-entered before the previous
        // RecordingService instance's onServiceDisconnected/rebind had
        // fully cycled — see RecordingServiceConnection's autoCreate
        // binding) would silently prevent autoStart from ever firing again,
        // stranding the screen showing a stale "SELESAI" status forever.
        // engine.start() at the service level now always begins a fresh
        // RecordingEngine() instance regardless of the previous session's
        // final status (see RecordingService.handleStart()), so it's safe
        // to also treat STOPPED here as "ready for a new session" — a
        // STOPPED status can only mean the previous session already fully
        // completed and persisted, never an in-progress one.
        //
        // PART 3.5 QA fix: this effect is keyed on `state.status`, which
        // also flips to STOPPED the moment the user's OWN "Selesaikan" on
        // *this* screen visit completes — without the autoStartConsumed
        // guard below, that re-satisfied readyForNewSession + autoStart
        // and silently called startRecording() again the instant Stop
        // finished, *while the Summary overlay was still showing*, with no
        // user action at all. That's a session starting behind the user's
        // back between STATE 4 (Summary) and STATE 1 (Idle) — not merely
        // cosmetic: handleStart()'s RECORDING/PAUSED guard would then
        // silently no-op the user's actual next "Mulai Rekam" tap, since a
        // ghost session would already be RECORDING. autoStart is only ever
        // meant to fire once per screen visit (the original "arrived here
        // already meaning to record" case) — a genuine second recording
        // must come from the user's own tap on RecordingControls' Start
        // button (unaffected by this guard, since that's a separate,
        // direct viewModel.startRecording() call, not this effect).
        val readyForNewSession = state.status == RecordingStatus.IDLE || state.status == RecordingStatus.STOPPED
        if (recoveryChecked && recoveryCandidate == null && autoStart && !autoStartConsumed && readyForNewSession) {
            autoStartConsumed = true
            viewModel.startRecording(routeId)
        }
    }

    // Bug fix: "MEMULAI..." was able to hang forever with no explanation
    // and no way out. autoStart always fires startRecording() above, but
    // nothing previously verified the service actually left IDLE — if
    // binding raced or silently failed (see RecordingServiceConnection's
    // BIND_AUTO_CREATE-vs-checkForRecovery timing), the screen just sat on
    // the IDLE default forever. This gives it a bounded wait, one retry,
    // then a real error state instead of an infinite spinner.
    var startStuck by remember { mutableStateOf(false) }
    LaunchedEffect(recoveryChecked, recoveryCandidate, autoStart) {
        if (!recoveryChecked || recoveryCandidate != null || !autoStart) return@LaunchedEffect
        kotlinx.coroutines.delay(6_000L)
        if (state.status == RecordingStatus.IDLE || state.status == RecordingStatus.STOPPED) {
            // One retry — covers the case where the first startRecording()
            // call landed on a service instance that hadn't finished
            // binding yet (autoCreate binds and creates near-simultaneously
            // with the first ACTION_START intent being sent).
            viewModel.startRecording(routeId)
            kotlinx.coroutines.delay(6_000L)
            if (state.status == RecordingStatus.IDLE || state.status == RecordingStatus.STOPPED) {
                startStuck = true
            }
        }
    }

    recoveryCandidate?.let { candidate ->
        RecoveryDialog(
            activityName = candidate.name,
            onResume = viewModel::resumeRecovered,
            onStopAndSave = {
                viewModel.stopAndSaveRecovered()
                onExit()
            },
            onDiscard = {
                viewModel.discardRecovered()
                onExit()
            }
        )
    }

    // Pre-record preview: RecordingService (state.currentLat/Lon) has no
    // GPS fix at all until the user actually taps Start (ACTION_START).
    // Before this, that meant recenter/"where am I" was dead on the SIAP
    // screen — this falls back to the ViewModel's own preview subscription
    // (same LocationRepository pattern HomeScreen already uses) so the
    // very first fix on-screen isn't gated behind starting a recording.
    // RecordingViewModel itself stops this the moment recording actually
    // starts, so it's never a second GPS source alongside the service.
    val previewLocation by viewModel.previewLocation.collectAsState()
    val userLatLng = if (state.currentLat != null && state.currentLon != null) {
        LatLng(state.currentLat!!, state.currentLon!!)
    } else previewLocation?.let { LatLng(it.lat, it.lon) }

    // Camera/follow/orientation state (spec P3 §14-16, gap closed —
    // previously followUser was hardcoded true with no manual-pan escape,
    // no Recenter, no compass, no Heading-Up option at all).
    val cameraMode by viewModel.cameraMode.collectAsState()
    val followMode by viewModel.followMode.collectAsState()
    val headingUp by viewModel.headingUp.collectAsState()
    val displayHeadingDeg by viewModel.displayHeadingDeg.collectAsState()
    var mapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var mapBearing by remember { mutableStateOf(0f) }
    // Same fix as NavigationScreen: measure the stat bar's real height
    // instead of guessing a fixed dp offset for the buttons above it.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var statBarHeight by remember { mutableStateOf(180.dp) }
    val bottomClearance = statBarHeight + 12.dp

    Box(Modifier.fillMaxSize()) {
        NyasarMapView(
            modifier = Modifier.fillMaxSize(),
            provider = provider,
            styleVariant = styleVariant,
            basemapEntry = selectedBasemap,
            // PART 4 fix: previously this only showed the picked GPX line
            // while IDLE, then went empty the moment recording started —
            // based on a mistaken assumption that actualTrack (the live
            // recorded line) would "replace" it. They're not the same
            // thing: track is the planned GPX route to follow, actualTrack
            // is the trail the user has actually walked so far. Spec Part 4
            // Test 2/4 require the GPX line to stay visible through
            // RECORDING and PAUSED too, not just IDLE — the user reads both
            // lines together to judge for themselves whether they're still
            // near the planned route (no off-route warning, just the map).
            track = previewTrack,
            actualTrack = state.recordedTrack,
            userLocation = userLatLng,
            userHeadingDeg = displayHeadingDeg,
            followUser = followMode,
            rotateWithHeading = headingUp,
            onUserGesture = viewModel::onUserPanned,
            onBearingChanged = { mapBearing = it },
            onMapReady = { mapInstance = it },
            userWaypoints = userWaypoints,
            onUserWaypointClick = { id ->
                waypointViewModel.selectWaypoint(userWaypoints.firstOrNull { it.id == id })
            },
            onMapLongPress = { lat, lon ->
                waypointViewModel.onMapLongPress(lat, lon, state.recordedTrack.lastOrNull()?.elevationM)
            }
        )

        // Floating back/minimize button (Strava-style) with semi-transparent
        // circular background and proper status bar inset.
        Surface(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 12.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.exit),
                tint = Color.White,
                modifier = Modifier.padding(10.dp)
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp
        ) {
            StatusChip(effectiveStatus, state.isAutoPaused, state.gpsHealth)
        }

        // P3I §20/26: surfaced separately from GPS health — this is about
        // whether points are actually being saved (storage full, disk
        // error), not about signal quality. Recording keeps running
        // in-memory regardless; this just tells the user not to trust that
        // everything will still be there after Stop.
        if (state.storageError) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp
            ) {
                Text(
                    "⚠ Gagal menyimpan data — storage penuh?",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // Compass: pure "reset to north" — same split of responsibility as
        // NavigationScreen. Heading-up is now only reachable through the
        // recenter button's 3-state cycle below.
        CompassButton(
            bearingDeg = mapBearing,
            onClick = {
                viewModel.resetToNorthUp()
                mapInstance?.let { map ->
                    val reset = org.maplibre.android.camera.CameraPosition.Builder()
                        .target(map.cameraPosition.target)
                        .zoom(map.cameraPosition.zoom)
                        .bearing(0.0)
                        .build()
                    map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(reset))
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(top = 12.dp, end = 12.dp)
        )

        // Layer switcher — same pattern as HomeScreen: opens the Strava-style
        // BasemapPickerSheet (grid with thumbnails), positioned above the recenter button.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(end = 12.dp, bottom = bottomClearance + 60.dp)
        ) {
            Surface(
                shape = CircleShape,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(onClick = { showBasemapSheet = true }) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = stringResource(R.string.map_layer_cd),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Recenter — 3-state cycle, same as NavigationScreen: lepas dari
        // GPS -> ikut posisi (utara di atas) -> ikut posisi + arah hadap.
        FilledIconButton(
            onClick = viewModel::recenter,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (cameraMode != CameraFollowMode.FREE) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
                contentColor = if (cameraMode != CameraFollowMode.FREE) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(end = 12.dp, bottom = bottomClearance)
                .size(48.dp)
        ) {
            Icon(
                when (cameraMode) {
                    CameraFollowMode.FREE -> Icons.Outlined.LocationSearching
                    CameraFollowMode.FOLLOW_NORTH_UP -> Icons.Filled.MyLocation
                    CameraFollowMode.FOLLOW_HEADING -> Icons.Filled.Navigation
                },
                contentDescription = when (cameraMode) {
                    CameraFollowMode.FREE -> stringResource(R.string.recenter_free_cd)
                    CameraFollowMode.FOLLOW_NORTH_UP -> stringResource(R.string.recenter_follow_cd)
                    CameraFollowMode.FOLLOW_HEADING -> stringResource(R.string.recenter_heading_cd)
                }
            )
        }

        if (userLatLng == null && !startStuck) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    stringResource(R.string.gps_searching),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Bug fix: replaces an infinite "MEMULAI..." with an actual
        // explanation + a way out once the retry above has also failed.
        // Deliberately doesn't try to guess *why* (permission vs airplane
        // mode vs a genuine bind race) — RecordingService's own
        // permission/GPS-health surfaces already cover the specific
        // reasons; this is just the fallback for "nothing happened and the
        // user has been staring at a spinner".
        if (startStuck) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.recording_not_started),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.check_gps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onExit) { Text(stringResource(R.string.exit_recording)) }
                        Button(onClick = {
                            startStuck = false
                            viewModel.startRecording(routeId)
                        }) { Text(stringResource(R.string.try_again)) }
                    }
                }
            }
        }

        // Strava-style stat card (spec: dark solid panel, not theme-adaptive
        // surface) — big 3-column primary stats (Time/Distance/Elevation
        // gain), expand affordance top-right, secondary stats (moving-time-
        // only vs speed) folded into the expanded state instead of always
        // shown, keeping the collapsed card matching the reference design.
        var statsExpanded by remember { mutableStateOf(false) }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Bug fix: card had no bottom safe-area padding at all, so
                // the last stat row (and the controls below it) got cut off
                // by the system navigation bar on devices with a gesture
                // bar/nav buttons — visible in the reported screenshot as
                // "Elevation gain (m)" being sliced off at the bottom edge.
                // Compass/Recenter already had .safeDrawingPadding(); this
                // card just never got the same treatment.
                .navigationBarsPadding()
                .onSizeChanged { size ->
                    statBarHeight = with(density) { size.height.toDp() }
                },
            color = Color(0xFF16181A),
            contentColor = Color.White
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { statsExpanded = !statsExpanded }) {
                        Icon(
                            if (statsExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = if (statsExpanded) stringResource(R.string.collapse_stats_cd) else stringResource(R.string.expand_stats_cd),
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Strava-style: Distance as hero metric (center), Time left, Elevation right
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BigStatBlock(
                        formatDuration(state.elapsedTimeMs),
                        "Time",
                        modifier = Modifier.weight(1f)
                    )
                    BigStatBlock(
                        "%.2f".format(state.distanceMeters / 1000.0),
                        "Distance (km)",
                        modifier = Modifier.weight(1.2f),
                        isHero = true
                    )
                    BigStatBlock(
                        state.elevationGainM.roundToInt().toString(),
                        "Naik (m)",
                        modifier = Modifier.weight(1f)
                    )
                }

                if (statsExpanded) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BigStatBlock(formatDuration(state.movingTimeMs), stringResource(R.string.recording_stat_moving_time), compact = true, modifier = Modifier.weight(1f))
                        BigStatBlock(
                            com.nyasar.app.util.SpeedUtils.formatSpeed(state.currentSpeedKmh, speedUnit, 1),
                            stringResource(R.string.recording_stat_speed),
                            compact = true,
                            modifier = Modifier.weight(1f)
                        )
                        BigStatBlock(
                            com.nyasar.app.util.SpeedUtils.formatSpeed(state.avgSpeedKmh, speedUnit, 1),
                            stringResource(R.string.recording_stat_avg_speed),
                            compact = true,
                            modifier = Modifier.weight(1f)
                        )
                        BigStatBlock(
                            com.nyasar.app.util.SpeedUtils.formatPace(state.avgSpeedKmh, speedUnit),
                            stringResource(R.string.pace),
                            compact = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                RecordingControls(
                    status = effectiveStatus,
                    routeName = previewRouteName,
                    onStart = { viewModel.startRecording(routeId = previewRouteId ?: routeId) },
                    onPause = viewModel::pauseRecording,
                    onResume = viewModel::resumeRecording,
                    onStop = { showStopConfirm = true },
                    onAddRoute = onAddRoute,
                    onClearRoute = { previewRouteId = null },
                selectedSportType = state.sportType,
                onSportSelected = { type -> viewModel.selectSportType(type) },
                onShowSportFilter = { showSportFilterSheet = true }
                )
            }
        }

        // PART 3: full-screen review form shown once a Stop is confirmed
        // and the service has actually finished persisting it (see the
        // stopRequested/summarySnapshot effect above) — replaces the old
        // behavior of exiting the screen immediately on Stop. This form
        // allows the user to review stats, add photos, and save/discard.
        // "Kembali" does NOT call onExit(): the user stays on this same
        // screen, which is now back to the two-button IDLE state.
        summarySnapshot?.let { summary ->
            PostRecordingForm(
                summary = summary,
                photos = postRecordingPhotos,
                onSave = { title ->
                    viewModel.updateActivityTitle(summary.activityId, title)
                    summarySnapshot = null
                    previewRouteId = null
                },
                onDiscard = {
                    viewModel.discardRecording(summary.activityId)
                    summarySnapshot = null
                    previewRouteId = null
                },
                onAddPhoto = { showPostRecordingPhotoChooser = true },
                onDeletePhoto = { photo ->
                    viewModel.deletePhotoForPostRecording(photo)
                },
                onBack = {
                    summarySnapshot = null
                    previewRouteId = null
                }
            )
        }
    }

    if (showSportFilterSheet) {
        SportFilterSheet(
            selectedSport = SportType.fromString(state.sportType),
            onSelectSport = { type ->
                viewModel.selectSportType(type)
                showSportFilterSheet = false
            },
            onDismiss = { showSportFilterSheet = false }
        )
    }

    if (showBasemapSheet) {
        com.nyasar.app.ui.components.BasemapPickerSheet(
            selected = selectedBasemap,
            onSelect = { entry ->
                viewModel.setBasemap(entry)
                showBasemapSheet = false
            },
            onDismiss = { showBasemapSheet = false }
        )
    }

    // P3J §6 fix: Stop used to end the recording immediately on a single
    // tap — no protection against an accidental press, unlike RecoveryDialog
    // below which already required a deliberate choice for the
    // crash-recovery case. This closes that gap for the everyday "I meant
    // to tap Pause" case too, without touching what happens after the user
    // actually confirms (still the exact same viewModel.stopRecording() +
    // onExit() call as before).
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(stringResource(R.string.stop_confirm_title)) },
            text = { Text(stringResource(R.string.stop_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    // Check if user never moved before actually stopping.
                    // If distance < 5m AND recording has been running > 5s,
                    // show "Belum bergerak?" instead of stopping.
                    val totalDistance = state.distanceMeters
                    val elapsedMs = state.elapsedTimeMs
                    if (totalDistance < RecordingService.NOT_MOVING_DISTANCE_THRESHOLD_METERS
                        && elapsedMs > 5_000L
                    ) {
                        showNotMovingFromStop = true
                    } else {
                        stopRequested = true
                        viewModel.stopRecording()
                    }
                }) { Text(stringResource(R.string.stop_and_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text(stringResource(R.string.cancel_btn)) }
            }
        )
    }

    // "Belum bergerak?" prompt: shown when (a) auto-pause fires but the
    // user never moved (distance < 5m), OR (b) user taps Finish while
    // distance is still < 5m. Non-blocking — recording continues in the
    // background. "Buang" stops + deletes the activity.
    val showNotMovingDialog = state.showNotMovingPrompt || showNotMovingFromStop
    if (showNotMovingDialog) {
        AlertDialog(
            onDismissRequest = {
                if (showNotMovingFromStop) {
                    showNotMovingFromStop = false
                } else {
                    viewModel.dismissNotMovingPrompt()
                }
            },
            title = { Text(stringResource(R.string.not_moving_title)) },
            text = {
                Text(stringResource(R.string.not_moving_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (showNotMovingFromStop) {
                        showNotMovingFromStop = false
                    } else {
                        viewModel.dismissNotMovingPrompt()
                    }
                }) { Text(stringResource(R.string.continue_recording)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotMovingFromStop = false
                    viewModel.discardNotMoving()
                    onExit()
                }) { Text(stringResource(R.string.discard)) }
            }
        )
    }

    // Same waypoint sheets as NavigationScreen (P3E3) — Add on long-press,
    // Detail on marker tap, Edit from Detail. Reused verbatim, no second
    // form/detail implementation.
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

    selectedWaypoint?.let { wp ->
        val distance = userLatLng?.let {
            com.nyasar.app.navigation.GeoMath.distanceMeters(
                com.nyasar.app.navigation.LatLng(it.latitude, it.longitude),
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

@Composable
fun RecoveryDialog(
    activityName: String,
    onResume: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* must pick one — no dismiss-to-lose-data */ },
        title = { Text(stringResource(R.string.previous_recording_active)) },
        text = { Text(stringResource(R.string.unsaved_recording_message, activityName)) },
        confirmButton = {
            TextButton(onClick = onResume) { Text(stringResource(R.string.resume_btn)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onStopAndSave) { Text(stringResource(R.string.stop_and_save)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) }
            }
        }
    )
}

@Composable
private fun StatusChip(status: RecordingStatus, isAutoPaused: Boolean = false, gpsHealth: com.nyasar.app.recording.GpsHealth = com.nyasar.app.recording.GpsHealth.OK) {
    // GPS health takes priority when it's actually degraded — spec P3C:
    // "RECORDING STATUS harus jelas: ... GPS WEAK, GPS LOST", and a weak/
    // lost signal is the more urgent thing for the user to notice, since
    // it affects whether new points are even being recorded accurately.
    val (color, label) = when {
        gpsHealth == com.nyasar.app.recording.GpsHealth.LOST -> MaterialTheme.colorScheme.error to "⚠ GPS HILANG"
        gpsHealth == com.nyasar.app.recording.GpsHealth.WEAK -> Color(0xFFF9A825) to "⚠ GPS LEMAH"
        // Part 5 cosmetic fix: "MEMULAI…" implied recording was already in
        // progress/starting up, even while the user was still sitting on
        // the two-button IDLE screen having tapped nothing yet — genuinely
        // misleading, not just imprecise wording. "SIAP" matches what's
        // actually true at this point: idle and ready for the user's next
        // action, no process running behind the scenes.
        status == RecordingStatus.IDLE -> MaterialTheme.colorScheme.outline to "SIAP"
        status == RecordingStatus.RECORDING -> Color(0xFF2E7D32) to "● RECORDING"
        status == RecordingStatus.PAUSED && isAutoPaused -> Color(0xFFF9A825) to "❚❚ DIJEDA OTOMATIS"
        status == RecordingStatus.PAUSED -> Color(0xFFF9A825) to "❚❚ DIJEDA"
        else -> MaterialTheme.colorScheme.outline to "SELESAI"
    }
    Text(
        label,
        color = color,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge
    )
}

@Composable
private fun BigStatBlock(
    value: String,
    label: String,
    compact: Boolean = false,
    isHero: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Clamp fontScale so 3 big stat numbers never overlap on narrow
    // screens or when the user has system font scaling > 130%.
    // Only the stat value is clamped; the label inherits the normal
    // font scale so accessibility text elsewhere is unaffected.
    val currentDensity = LocalDensity.current
    val clampedDensity = Density(
        density = currentDensity.density,
        fontScale = currentDensity.fontScale.coerceAtMost(1.15f)
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides clampedDensity) {
            Text(
                value,
                style = when {
                    isHero -> MaterialTheme.typography.headlineLarge
                    compact -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.headlineMedium
                },
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecordingControls(
    status: RecordingStatus,
    routeName: String?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onAddRoute: () -> Unit,
    onClearRoute: (() -> Unit)? = null,
    selectedSportType: String = "TRAIL_RUN",
    onSportSelected: (com.nyasar.app.recording.SportType) -> Unit = {},
    onShowSportFilter: () -> Unit = {}
) {
    val sportType = com.nyasar.app.recording.SportType.fromString(selectedSportType)
    
    when (status) {
        // Spec PART 3 STATE 1: exactly two labeled buttons, always both
        // visible regardless of whether a track is attached — "Pilih
        // Jalur" is how you attach one, not something that disappears
        // once you have. No Stop/Pause/Resume/third button here at all.
        RecordingStatus.IDLE -> {
            // Three-column layout: Sport button | Start | Add Route
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sport button (bottom-left)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(onClick = onShowSportFilter)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            sportType.icon,
                            contentDescription = stringResource(R.string.select_sport_cd),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        sportType.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                
                // Start button (center)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    routeName?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (onClearRoute != null) {
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = onClearRoute,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.delete_route_cd),
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                    FilledIconButton(
                        onClick = onStart,
                        modifier = Modifier.size(80.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(36.dp))
                    }
                    Text(
                        stringResource(R.string.start_recording),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                
                // Add Route button (bottom-right)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(onClick = onAddRoute)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Route,
                            contentDescription = stringResource(R.string.pick_route_cd),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.add_route_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
        // Spec PART 3 STATE 2: ONLY Pause. Stop is deliberately absent
        // here — the previous build showed both, which is exactly the
        // "gampang salah pencet Stop sambil jalan" risk the spec calls
        // out. Selesaikan only becomes reachable from PAUSED below.
        RecordingStatus.RECORDING -> {
            FilledIconButton(
                onClick = onPause,
                modifier = Modifier.size(80.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.pause_recording), modifier = Modifier.size(36.dp))
            }
        }
        // Spec PART 3 STATE 3: Lanjutkan + Selesaikan, side by side.
        RecordingStatus.PAUSED -> {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onResume,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.continue_recording))
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.finish_recording))
                }
            }
        }
        // STOPPED never reaches here — RecordingScreen normalizes it to
        // IDLE before calling this (see effectiveStatus).
        else -> {}
    }
}

/**
 * Spec PART 3 STATE 4. Reads only fields RecordingUiState already tracks
 * (distance/duration/movingTime/elevationGain/elevationLoss) plus max/min
 * elevation, derived here from the same recordedTrack points already used
 * to draw the live line — not a new tracked stat, just a display-layer
 * reduction over data that already exists. Shown/omitted individually per
 * spec ("jika tersedia").
 */
@Composable
private fun RecordingSummaryOverlay(summary: RecordingUiState, onBack: () -> Unit) {
    val elevations = remember(summary.recordedTrack) { summary.recordedTrack.mapNotNull { it.elevationM } }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF16181A), contentColor = Color.White) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).verticalScrollCompat(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.recording_finished), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(32.dp))

            SummaryRow(stringResource(R.string.stat_distance), "%.2f km".format(summary.distanceMeters / 1000.0))
            SummaryRow(stringResource(R.string.stat_duration), formatDuration(summary.elapsedTimeMs))
            if (summary.movingTimeMs > 0) {
                SummaryRow(stringResource(R.string.stat_walking_time), formatDuration(summary.movingTimeMs))
            }
            SummaryRow(stringResource(R.string.stat_elev_gain_up), "+${summary.elevationGainM.roundToInt()} m")
            SummaryRow(stringResource(R.string.stat_elev_gain_down), "-${summary.elevationLossM.roundToInt()} m")
            elevations.maxOrNull()?.let { SummaryRow(stringResource(R.string.stat_max_elevation), "${it.roundToInt()} m") }
            elevations.minOrNull()?.let { SummaryRow(stringResource(R.string.stat_min_elevation), "${it.roundToInt()} m") }

            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.back_to_recording), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
