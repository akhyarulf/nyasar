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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
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
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.recording.RecordingService
import com.nyasar.app.recording.RecordingStatus
import com.nyasar.app.recording.RecordingUiState
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.CameraFollowMode
import com.nyasar.app.ui.components.CompassButton
import com.nyasar.app.ui.components.AnimatedAppear
import com.nyasar.app.ui.components.AnimatedStatText
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.nyasar.app.ui.components.pressScale
import com.nyasar.app.ui.theme.NyasarMotion
import com.nyasar.app.ui.theme.NyasarRadius
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R
import com.nyasar.app.ui.waypoint.WaypointCrosshairScreen
import android.Manifest
import android.os.Build
import com.nyasar.app.data.settings.SettingsRepository
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
 * Provider is exposed by RecordingViewModel from the persisted Settings
 * value (same source Home/RoutePreview read) so the basemap+provider pair —
 * and therefore the shared-map style key — is identical on all 3 map screens.
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

    // POST_NOTIFICATIONS explainer (API 33+), own first-time-relevant-moment
    // onboarding — separate from MainActivity's location explainer. Audit
    // showed notifications in this app serve exactly ONE purpose: the
    // RecordingService foreground notification ("Recording aktif" + live
    // distance, channel "Recording", IMPORTANCE_LOW) — so the right moment
    // to explain/request is when the user first reaches the Recording
    // screen, not app-open (previously it was piggybacked onto the location
    // dialog's buttons with zero notification context). Persisted via
    // DataStore so it shows once per install; every exit path marks it
    // shown. "Nanti Saja" skips the request entirely — recording itself is
    // unaffected (the service still runs; only the visible progress
    // notification is lost, see RecordingService's updateNotification
    // comments). Requesting here (context: user is about to record) instead
    // of at app-open follows the same "explain why, then ask" pattern as
    // the location onboarding, independently of that dialog's outcome.
    val notifContext = androidx.compose.ui.platform.LocalContext.current
    val settingsRepository = remember { SettingsRepository(notifContext) }
    val settingsForNotif by settingsRepository.settings.collectAsState(initial = null)
    val notifScope = rememberCoroutineScope()
    var showNotifOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(settingsForNotif, state.status) {
        val s = settingsForNotif ?: return@LaunchedEffect
        // Only raise it while IDLE: the user resuming an already-running
        // session (RECORDING/PAUSED) or staring at the Summary overlay
        // (STOPPED) must not get ambushed by a permission dialog — in those
        // states notifications are already moot for the running session.
        // It still pops the moment they're back on a fresh IDLE screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            state.status == RecordingStatus.IDLE &&
            !s.notificationOnboardingShown
        ) showNotifOnboarding = true
    }
    val notifGateArmed = settingsForNotif?.notificationOnboardingShown == false
    // Remembers which routeId the gated start was meant to use (manual Start
    // can pass previewRouteId ?: routeId), so the dialog's own buttons
    // reproduce the exact call the gate intercepted.
    var pendingNotifGateStartRoute by remember { mutableStateOf<String?>(null) }
    // ------------------------------------------------------------------
    // Start-flow gate chain: notifikasi -> lokasi -> battery.
    //
    // Same architecture as the original notification gate (BUG FIX
    // "setelah dialog permission notification, langsung otomatis
    // recording"): each gate holds the routeId of the REAL start attempt it
    // intercepted in its own pending*GateStartRoute slot, and a gate's
    // dialog answers only ever continue that held attempt. A dialog raised
    // by a mere screen visit has nothing held — answering it can never
    // start a recording, however the user answers. Gates chain forward:
    // resolving one gate hands the held route to the next, and only the
    // last gate (battery) finally calls viewModel.startRecording(). No two
    // dialogs ever overlap: each gate's dialog is raised from the previous
    // gate's RESULT callback (system popup already gone), never from the
    // button click itself.
    val gateScope = rememberCoroutineScope()
    val gateContext = androidx.compose.ui.platform.LocalContext.current
    var pendingBatteryGateStartRoute by remember { mutableStateOf<String?>(null) }
    var showBatteryOnboarding by remember { mutableStateOf(false) }
    var pendingLocationGateStartRoute by remember { mutableStateOf<String?>(null) }
    var showLocationOnboarding by remember { mutableStateOf(false) }
    var showLocationDeniedBanner by remember { mutableStateOf(false) }

    fun advanceToBatteryGate(startRouteId: String?) {
        // Battery-optimization gate (Gap 2) — advisory only: recording works
        // without the exemption, vendor battery savers (MIUI/Samsung/etc.)
        // just make screen-off/background tracking less reliable. One-time
        // DataStore flag keeps the explainer from nagging on every start;
        // the Settings screen row re-opens the same system sheet on demand.
        val pm = gateContext.getSystemService(android.os.PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(gateContext.packageName) ?: true
        if (!ignoring && settingsForNotif?.batteryOptimizationOnboardingShown == false) {
            pendingBatteryGateStartRoute = startRouteId
            showBatteryOnboarding = true
        } else {
            viewModel.startRecording(startRouteId)
        }
    }

    // Location gate (Gap 1 fix) — startLocationCollection() silently returns
    // without ACCESS_FINE_LOCATION (RecordingService), which used to leave a
    // "RECORDING" session with a ticking timer and a foreground notification
    // but ZERO GPS points. No start attempt proceeds past this gate unless
    // the permission is granted right now.
    fun advanceToLocationGate(startRouteId: String?) {
        if (!viewModel.hasLocationPermission()) {
            pendingLocationGateStartRoute = startRouteId
            showLocationOnboarding = true
        } else {
            advanceToBatteryGate(startRouteId)
        }
    }

    fun gateAutoStart(startRouteId: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notifGateArmed) {
            pendingNotifGateStartRoute = startRouteId
            showNotifOnboarding = true
        } else {
            advanceToLocationGate(startRouteId)
        }
    }

    // Battery-optimization explainer resolution. The system sheet is raised
    // through a StartActivityForResult launcher so the held start continues
    // from its RESULT callback — our activity is foreground again by then,
    // which matters on Android 12+ (a foreground service must not be started
    // while a system sheet fully covers the activity).
    val batteryPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Allow or deny both land here; isIgnoringBatteryOptimizations()
        // says which, but recording proceeds either way — the exemption is
        // optional. This is the last gate: continue the held attempt now.
        pendingBatteryGateStartRoute?.let { heldRouteId ->
            pendingBatteryGateStartRoute = null
            viewModel.startRecording(heldRouteId)
        }
    }
    fun launchBatterySheet(): Boolean {
        val packageUri = android.net.Uri.parse("package:${gateContext.packageName}")
        val intents = listOf(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri),
            // Fallback: some OEM builds strip the direct dialog intent —
            // degrade to the app's system settings page instead of crashing.
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
        )
        for (intent in intents) {
            try {
                batteryPermission.launch(intent)
                return true
            } catch (_: Exception) { /* try the next intent */ }
        }
        return false
    }
    fun resolveBatteryOnboarding(allow: Boolean) {
        showBatteryOnboarding = false
        gateScope.launch { settingsRepository.setBatteryOptimizationOnboardingShown() }
        if (allow && launchBatterySheet()) return // continues from the sheet's result callback
        pendingBatteryGateStartRoute?.let { heldRouteId ->
            pendingBatteryGateStartRoute = null
            viewModel.startRecording(heldRouteId)
        }
    }

    // Location explainer resolution. Grant hands the held attempt to the
    // next gate; skip DROPS it (deliberate — see the denied banner below:
    // this app refuses to run a session that would silently record
    // nothing). A fresh Start tap later re-enters the chain as a new
    // attempt.
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Refresh the IDLE screen's GPS preview dot with the newly
            // granted permission (same call the screen makes on entry).
            showLocationDeniedBanner = false
            viewModel.startLocationUpdatesIfPermitted()
            // Continue the chain ONLY when a real start attempt is held.
            // The denied banner's standalone "Izinkan" path raises this
            // same dialog with nothing held — granting from there must
            // merely clear the banner, never start a recording.
            pendingLocationGateStartRoute?.let { heldRouteId ->
                pendingLocationGateStartRoute = null
                advanceToBatteryGate(heldRouteId)
            }
        } else {
            // Denied (or "don't ask again" — the system popup will never
            // re-appear, so a retry loop would dead-end). Drop the held
            // start and surface the persistent banner with a shortcut to
            // system settings; the user grants there and taps Start again.
            showLocationDeniedBanner = true
        }
        pendingLocationGateStartRoute = null
    }
    fun resolveLocationOnboarding(requestPermission: Boolean) {
        showLocationOnboarding = false
        if (requestPermission) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            pendingLocationGateStartRoute = null
        }
    }

    // Notification gate (existing behavior — see BUG FIX below). Its
    // launcher lives here, after the location gate, because its RESULT
    // callback is what hands the held attempt to advanceToLocationGate —
    // chaining from the button click instead would raise the location
    // dialog while the SYSTEM notification popup is still up (two dialogs
    // overlapping — exactly what the gate chain must never do).
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Denial is non-fatal — recording still runs, just without the
        // progress notification. The gate chain continues here, not while
        // the system popup is up.
        pendingNotifGateStartRoute?.let { interceptedRouteId ->
            pendingNotifGateStartRoute = null
            advanceToLocationGate(interceptedRouteId)
        }
    }
    // BUG FIX ("setelah dialog permission notification, langsung otomatis
    // recording"): the dialog's buttons used to call viewModel.startRecording()
    // unconditionally. The dialog is raised by TWO different triggers — an
    // intercepted start attempt (gateAutoStart above, which records the route
    // in pendingNotifGateStartRoute) and the user's FIRST VISIT to this screen
    // while IDLE (the LaunchedEffect near the top of this function). For the
    // visit trigger there is no start to reproduce, yet the old buttons
    // started one anyway (falling back to `routeId`), so merely answering the
    // explainer — Allow or "Nanti Saja" — kicked off a recording session the
    // user never asked for. Fix (now extended to the whole 3-gate chain):
    // answering the dialog only resolves that gate's decision; recording
    // proceeds ONLY when a real start attempt was intercepted
    // (pending*GateStartRoute != null), replaying the exact call the gate
    // held back.
    fun resolveNotifOnboarding(requestPermission: Boolean) {
        showNotifOnboarding = false
        notifScope.launch { settingsRepository.setNotificationOnboardingShown() }
        if (requestPermission) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            // Chain continues in the launcher's result callback.
        } else {
            pendingNotifGateStartRoute?.let { interceptedRouteId ->
                pendingNotifGateStartRoute = null
                advanceToLocationGate(interceptedRouteId)
            }
        }
    }
    // P3J §6: guards the Stop button — see the AlertDialog near the bottom
    // of this function for why.
    var showStopConfirm by remember { mutableStateOf(false) }
    var showNotMovingFromStop by remember { mutableStateOf(false) }
    var showBasemapSheet by remember { mutableStateOf(false) }
    val styleVariant by viewModel.styleVariant.collectAsState()
    val selectedBasemap by viewModel.selectedBasemap.collectAsState()
    // Waymarked Trails overlays — shared persisted state from the ViewModel
    // (same DataStore row Home/RoutePreview read). With one shared MapView,
    // per-screen overlay sets would strip/restore overlays on every switch.
    val activeOverlays by viewModel.activeOverlays.collectAsState()
    // "Jalur Saya" overlay: app-wide persisted switch + reactive lines.
    val myRoutesEnabled by viewModel.myRoutesOverlayEnabled.collectAsState()
    val myRouteLines by viewModel.myRouteLines.collectAsState()
    // Provider comes from the ViewModel (persisted setting, same source Home
    // uses) — the old `remember { TileProviderFactory.default() }` pinned
    // MapTiler even after the user changed provider in Settings, and could
    // disagree with Home's provider (which also broke shared-map style-key
    // equality between the two screens).
    val provider by viewModel.provider.collectAsState()
    val userWaypoints by waypointViewModel.waypoints.collectAsState()
    // v7: independent pins + waypoints linked to THIS session's activity
    // (or its route while still IDLE) — other routes'/activities' pins stay
    // out of the recording map. Explicit null checks on purpose: a null
    // member in the session-id set would otherwise make `null in set`
    // match every unlinked id field and leak OTHER routes' pins in.
    val independentWaypoints by waypointViewModel.independentWaypoints.collectAsState()
    val visibleUserWaypoints = buildList {
        addAll(independentWaypoints)
        addAll(userWaypoints.filter { wp ->
            wp.source != com.nyasar.app.data.db.WaypointEntity.SOURCE_GPX && (
                (state.activityId != null && wp.linkedActivityId == state.activityId) ||
                    (routeId != null && wp.linkedRouteId == routeId)
                )
        })
    }.distinctBy { it.id }
    val pendingWaypointTap by waypointViewModel.pendingTap.collectAsState()
    val selectedWaypoint by waypointViewModel.selectedWaypoint.collectAsState()
    val editingWaypoint by waypointViewModel.editingWaypoint.collectAsState()
    val waypointContext by waypointViewModel.context.collectAsState()

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
    // v7: full-screen crosshair picker state.
    var showCrosshair by remember { mutableStateOf(false) }
    val pickerContext = androidx.compose.ui.platform.LocalContext.current
    val routeRepository = remember { com.nyasar.app.data.repository.RouteRepository(pickerContext) }

    // v7: waypoint attachment context = the LIVE recording. While IDLE the
    // activity row doesn't exist yet, so the context falls back to the
    // attached route; once recording starts (state.activityId mints) the
    // context switches to activity-linking automatically.
    LaunchedEffect(state.activityId, routeId) {
        waypointViewModel.setContext(
            com.nyasar.app.ui.waypoint.WaypointContext.Recording(
                activityId = state.activityId,
                routeId = routeId
            )
        )
    }

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
    // Reuses the `settingsRepository` created for the POST_NOTIFICATIONS
    // explainer above — same SettingsRepository singleton-per-context over
    // the same DataStore file; a second declaration here is a Kotlin
    // "Conflicting declarations" compile error, not a separate instance.
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
            gateAutoStart(routeId)
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
        // While any gate-explainer dialog is up, the user hasn't had a
        // chance to start anything yet — the watchdog must not "retry"
        // into the gate chain again or flip startStuck behind the modal.
        if (showNotifOnboarding || showLocationOnboarding || showBatteryOnboarding) return@LaunchedEffect
        if (state.status == RecordingStatus.IDLE || state.status == RecordingStatus.STOPPED) {
            // One retry — covers the case where the first startRecording()
            // call landed on a service instance that hadn't finished
            // binding yet (autoCreate binds and creates near-simultaneously
            // with the first ACTION_START intent being sent).
            gateAutoStart(routeId)
            kotlinx.coroutines.delay(6_000L)
            if (!showNotifOnboarding && !showLocationOnboarding && !showBatteryOnboarding &&
                (state.status == RecordingStatus.IDLE || state.status == RecordingStatus.STOPPED)
            ) {
                startStuck = true
            }
        }
    }

    // Own-explainer dialog for POST_NOTIFICATIONS (API 33+) — see the
    // comment block at its state declarations above. Triggered by the
    // first start attempt (auto or the startStuck retry) or by the user's
    // manual Start tap; never on mere screen visits.
    if (showNotifOnboarding) {
        AlertDialog(
            onDismissRequest = {
                showNotifOnboarding = false
                // Outside-tap dismiss: close only — any intercepted start is
                // dropped and the user stays on IDLE, free to tap Start
                // themselves (recording must never be a side effect of
                // dismissing this dialog).
                pendingNotifGateStartRoute = null
                notifScope.launch { settingsRepository.setNotificationOnboardingShown() }
            },
            title = { Text(stringResource(R.string.notif_onboarding_title)) },
            text = { Text(stringResource(R.string.notif_onboarding_body)) },
            confirmButton = {
                TextButton(onClick = { resolveNotifOnboarding(requestPermission = true) }) {
                    Text(stringResource(R.string.notif_onboarding_allow))
                }
            },
            dismissButton = {
                // Nanti Saja: no request — recording still runs fine
                // without the progress notification.
                TextButton(onClick = { resolveNotifOnboarding(requestPermission = false) }) {
                    Text(stringResource(R.string.notif_onboarding_skip))
                }
            }
        )
    }

    // Location explainer (Gate 2 of 3) — raised ONLY from gateAutoStart's
    // chain (a real start attempt was intercepted) or by the user granting
    // from the denied banner. Never by a screen visit: with nothing held in
    // pendingLocationGateStartRoute, answering this dialog can never start
    // a recording.
    if (showLocationOnboarding) {
        AlertDialog(
            onDismissRequest = {
                showLocationOnboarding = false
                // Same dismiss semantics as the notification gate: an
                // outside tap drops the held attempt without starting.
                pendingLocationGateStartRoute = null
            },
            title = { Text(stringResource(R.string.location_gate_title)) },
            text = { Text(stringResource(R.string.location_gate_body)) },
            confirmButton = {
                TextButton(onClick = { resolveLocationOnboarding(requestPermission = true) }) {
                    Text(stringResource(R.string.location_gate_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { resolveLocationOnboarding(requestPermission = false) }) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
        )
    }

    // Battery-optimization explainer (Gate 3 of 3) — same held-attempt
    // contract. "Nanti Saja" skips without opening any system UI; "Izinkan"
    // opens the PowerManager sheet and the start continues from its result.
    if (showBatteryOnboarding) {
        AlertDialog(
            onDismissRequest = {
                showBatteryOnboarding = false
                pendingBatteryGateStartRoute = null
            },
            title = { Text(stringResource(R.string.battery_gate_title)) },
            text = { Text(stringResource(R.string.battery_gate_body)) },
            confirmButton = {
                TextButton(onClick = { resolveBatteryOnboarding(allow = true) }) {
                    Text(stringResource(R.string.battery_gate_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { resolveBatteryOnboarding(allow = false) }) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
        )
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
            // Opt into the shared MapView so Home ↔ RoutePreview ↔ Recording
            // reuse one GL surface/tile cache/style instead of rebuilding the
            // map on every screen switch.
            shared = true,
            activeOverlays = activeOverlays,
            // "Jalur Saya" overlay — app-wide persisted flag + reactive line
            // data. previewRouteId (the "Pilih Jalur" pick, or the routeId
            // this screen was launched with) is the active route: it renders
            // solid accent while the user's other saved routes stay gray/
            // dashed. The Pilih Jalur flow itself is untouched — this only
            // restyles the line it already draws via track = previewTrack.
            myRoutes = myRouteLines,
            activeRouteId = previewRouteId,
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
            userWaypoints = visibleUserWaypoints,
            onUserWaypointClick = { id ->
                waypointViewModel.selectWaypoint(visibleUserWaypoints.firstOrNull { it.id == id })
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
            // Appears with the shared fade-and-rise (AnimatedAppear starts
            // invisible and animates in — same family as Home banners)
            // instead of popping in mid-recording.
            com.nyasar.app.ui.components.AnimatedAppear(
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    modifier = Modifier.padding(top = 56.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(com.nyasar.app.ui.theme.NyasarRadius.sm),
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp
                ) {
                    Text(
                        stringResource(R.string.storage_error),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // Gap 1 companion UX: a start attempt that survived the whole gate
        // chain with location still denied CAN legitimately happen (grant
        // flow raced, OEM quirk) — and the service would then run a session
        // that never accepts a single GPS fix. Rather than silently doing
        // that, the IDLE screen carries a persistent banner explaining
        // exactly that, with a one-tap grant (re-enters the gate chain with
        // the currently attached route) and a system-settings shortcut for
        // the "don't ask again" case. Persist until permission is granted —
        // same persistence philosophy as storageError above.
        if (showLocationDeniedBanner) {
            com.nyasar.app.ui.components.AnimatedAppear(
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    modifier = Modifier.padding(top = 56.dp).padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(com.nyasar.app.ui.theme.NyasarRadius.sm),
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            stringResource(R.string.location_denied_banner),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TextButton(onClick = {
                            showLocationDeniedBanner = false
                            showLocationOnboarding = true
                        }) {
                            Text(stringResource(R.string.location_denied_grant), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = {
                            gateContext.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(android.net.Uri.parse("package:${gateContext.packageName}"))
                            )
                        }) {
                            Text(stringResource(R.string.location_denied_settings), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
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

        // v7: crosshair waypoint picker — saved pin links to the live
        // activity (context set above), user can switch to independent
        // in-form. IDLE: falls back to the attached route via context.
        // Same map-control recipe as Home's RoundIconButton (theme surface,
        // shared elevation tokens, press spring, fade-and-rise entrance).
        val addWaypointInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        com.nyasar.app.ui.components.AnimatedAppear(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(end = 12.dp, bottom = bottomClearance + 120.dp)
        ) {
            Surface(
                onClick = { showCrosshair = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = com.nyasar.app.ui.theme.NyasarElevation.mapControlTonal,
                shadowElevation = com.nyasar.app.ui.theme.NyasarElevation.mapControlShadow,
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(addWaypointInteraction)
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = stringResource(R.string.add_waypoint_cd),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Layer switcher — same pattern as HomeScreen: opens the Strava-style
        // BasemapPickerSheet (grid with thumbnails), positioned above the recenter button.
        // Same shared map-control recipe as the waypoint button above.
        val layersInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        com.nyasar.app.ui.components.AnimatedAppear(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(end = 12.dp, bottom = bottomClearance + 60.dp)
        ) {
            Surface(
                onClick = { showBasemapSheet = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = com.nyasar.app.ui.theme.NyasarElevation.mapControlTonal,
                shadowElevation = com.nyasar.app.ui.theme.NyasarElevation.mapControlShadow,
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(layersInteraction)
            ) {
                Icon(
                    Icons.Default.Layers,
                    contentDescription = stringResource(R.string.map_layer_cd),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
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

        // "Searching for GPS" banner fades+rises in while searching and
        // fades out on lock, instead of hard-popping in/out.
        AnimatedVisibility(
            visible = userLatLng == null && !startStuck,
            enter = fadeIn(animationSpec = NyasarMotion.enter()) +
                slideInVertically(initialOffsetY = { it / 4 }, animationSpec = NyasarMotion.enter()),
            exit = fadeOut(animationSpec = NyasarMotion.exit()),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(NyasarRadius.md)
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
                            gateAutoStart(routeId)
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
            shape = RoundedCornerShape(topStart = NyasarRadius.xl, topEnd = NyasarRadius.xl),
            color = Color(0xFF16181A),
            contentColor = Color.White
        ) {
            Column(Modifier.padding(20.dp)) {
                // Expand affordance: compact 36dp button (was a full-height
                // icon row that wasted vertical space) with press feedback,
                // and the icon itself crossfade+zooms on toggle so the
                // state change is felt, not hard-swapped.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val expandInteraction = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { statsExpanded = !statsExpanded },
                        modifier = Modifier.size(36.dp).pressScale(expandInteraction),
                        interactionSource = expandInteraction
                    ) {
                        AnimatedContent(
                            targetState = statsExpanded,
                            transitionSpec = {
                                (fadeIn(animationSpec = NyasarMotion.fast()) +
                                    scaleIn(initialScale = 0.6f, animationSpec = NyasarMotion.fast()))
                                    .togetherWith(
                                        fadeOut(animationSpec = NyasarMotion.exit()) +
                                            scaleOut(targetScale = 0.6f, animationSpec = NyasarMotion.exit())
                                    )
                            },
                            label = "expandIcon"
                        ) { expanded ->
                            Icon(
                                if (expanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                                contentDescription = if (expanded) stringResource(R.string.collapse_stats_cd) else stringResource(R.string.expand_stats_cd),
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
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

                // Secondary stats fold in/out with the shared emphasized
                // expand curve instead of popping (was a hard if-cut).
                AnimatedVisibility(
                    visible = statsExpanded,
                    enter = expandVertically(animationSpec = NyasarMotion.enter()) + fadeIn(animationSpec = NyasarMotion.enter()),
                    exit = shrinkVertically(animationSpec = NyasarMotion.exit()) + fadeOut(animationSpec = NyasarMotion.exit())
                ) {
                    Column(Modifier.fillMaxWidth()) {
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
                }

                Spacer(Modifier.height(24.dp))
                RecordingControls(
                    status = effectiveStatus,
                    routeName = previewRouteName,
                    onStart = { gateAutoStart(previewRouteId ?: routeId) },
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
            activeOverlays = activeOverlays,
            onToggleOverlay = { overlay ->
                viewModel.toggleOverlay(overlay)
            },
            myRoutesEnabled = myRoutesEnabled,
            onToggleMyRoutes = { viewModel.setMyRoutesOverlayEnabled(!myRoutesEnabled) },
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

    // v7: crosshair picker — same screen the other map screens open.
    if (showCrosshair) {
        // Local copies: `state` is a delegated property, so its nullable
        // fields can't smart-cast — these locals can.
        val crosshairLat = state.currentLat
        val crosshairLon = state.currentLon
        WaypointCrosshairScreen(
            initialLatLng = if (crosshairLat != null && crosshairLon != null)
                LatLng(crosshairLat, crosshairLon) else null,
            attachments = com.nyasar.app.ui.waypoint.WaypointAttachments(
                routeId = routeId,
                activityId = state.activityId
            ),
            initialLinkedRouteId = waypointContext.defaultRouteId,
            initialLinkedActivityId = waypointContext.defaultActivityId,
            onSave = { lat, lon, name, category, linkedRouteId, linkedActivityId ->
                waypointViewModel.confirmCrosshairWaypointFrom(lat, lon, name, category, null, linkedRouteId, linkedActivityId)
                showCrosshair = false
            },
            onDismiss = { showCrosshair = false }
        )
    }

    // Same waypoint sheets as NavigationScreen (P3E3) — Add on long-press,
    // Detail on marker tap, Edit from Detail. Reused verbatim, no second
    // form/detail implementation.
    pendingWaypointTap?.let { tap ->
        val ctx = waypointContext
        com.nyasar.app.ui.waypoint.WaypointFormSheet(
            title = stringResource(R.string.new_waypoint),
            initialName = "",
            initialCategory = com.nyasar.app.data.db.WaypointCategory.POI,
            initialNote = "",
            lat = tap.lat,
            lon = tap.lon,
            elevationM = tap.elevationM,
            attachments = com.nyasar.app.ui.waypoint.WaypointAttachments(
                routeId = (ctx as? com.nyasar.app.ui.waypoint.WaypointContext.Recording)?.routeId
                    ?: (ctx as? com.nyasar.app.ui.waypoint.WaypointContext.Route)?.routeId,
                activityId = (ctx as? com.nyasar.app.ui.waypoint.WaypointContext.Recording)?.activityId
            ),
            initialLinkedRouteId = ctx.defaultRouteId,
            initialLinkedActivityId = ctx.defaultActivityId,
            onDismiss = waypointViewModel::dismissPendingTap,
            onSave = { name, category, note, linkedRouteId, linkedActivityId ->
                waypointViewModel.confirmAdd(name, category, note, linkedRouteId, linkedActivityId)
            }
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
            attachments = com.nyasar.app.ui.waypoint.WaypointAttachments(
                routeId = routeId,
                activityId = state.activityId
            ),
            initialLinkedRouteId = wp.linkedRouteId,
            initialLinkedActivityId = wp.linkedActivityId,
            lockAttachment = wp.source == com.nyasar.app.data.db.WaypointEntity.SOURCE_GPX,
            onDismiss = waypointViewModel::dismissEditing,
            onSave = { name, cat, note, linkedRouteId, linkedActivityId ->
                waypointViewModel.confirmEditWithLinks(name, cat, note, linkedRouteId, linkedActivityId)
            },
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
    // Status transitions (SIAP -> RECORDING -> DIJEDA -> ...) animate with
    // the shared fast tween instead of hard-swapping text, matching every
    // other animated state change in the app.
    androidx.compose.animation.AnimatedContent(
        targetState = label,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(com.nyasar.app.ui.theme.NyasarMotion.FAST_MS)))
                .togetherWith(
                    androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(com.nyasar.app.ui.theme.NyasarMotion.FAST_MS))
                )
        },
        label = "statusChip"
    ) { animatedLabel ->
        Text(
            animatedLabel,
            color = color,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
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
            // Live stats tick via the shared AnimatedStatText (fast slide+
            // fade between values) so GPS updates feel continuous rather
            // than flashing — same animation family as the Home pill.
            AnimatedStatText(
                value = value,
                style = when {
                    isHero -> MaterialTheme.typography.headlineLarge
                    compact -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.headlineMedium
                },
                color = Color.White
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

    // State swaps (Start <-> Pause <-> Resume/Finish) crossfade+scale
    // through the shared motion tokens instead of hard-cutting between
    // completely different layouts; the default SizeTransform also
    // animates the height change between them.
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (fadeIn(animationSpec = NyasarMotion.enter()) +
                scaleIn(initialScale = 0.94f, animationSpec = NyasarMotion.enter()))
                .togetherWith(
                    fadeOut(animationSpec = NyasarMotion.exit()) +
                        scaleOut(targetScale = 0.94f, animationSpec = NyasarMotion.exit())
                )
        },
        label = "recordingControls"
    ) { controlStatus ->
    when (controlStatus) {
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
                // Sport button (bottom-left) — press-scale circle, and the
                // icon crossfade+zooms when the user picks a different sport
                // from the filter sheet instead of hard-swapping.
                val sportInteraction = remember { MutableInteractionSource() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(
                        interactionSource = sportInteraction,
                        indication = null,
                        onClick = onShowSportFilter
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .pressScale(sportInteraction)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = sportType,
                            transitionSpec = {
                                (fadeIn(animationSpec = NyasarMotion.fast()) +
                                    scaleIn(initialScale = 0.6f, animationSpec = NyasarMotion.fast()))
                                    .togetherWith(
                                        fadeOut(animationSpec = NyasarMotion.exit()) +
                                            scaleOut(targetScale = 0.6f, animationSpec = NyasarMotion.exit())
                                    )
                            },
                            label = "sportIcon"
                        ) { animatedSport ->
                            Icon(
                                animatedSport.icon,
                                contentDescription = stringResource(R.string.select_sport_cd),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    AnimatedContent(
                        targetState = sportType,
                        transitionSpec = {
                            fadeIn(animationSpec = NyasarMotion.fast())
                                .togetherWith(fadeOut(animationSpec = NyasarMotion.exit()))
                        },
                        label = "sportLabel"
                    ) { animatedSport ->
                        Text(
                            stringResource(animatedSport.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
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
                    // Hero button: a gentle breathing pulse (2% scale, slow
                    // cycle) invites the tap while idle, and the shared
                    // press-scale gives tactile feedback on touch.
                    val startInteraction = remember { MutableInteractionSource() }
                    val pulseScale by androidx.compose.animation.core.rememberInfiniteTransition(label = "startPulse").animateFloat(
                        initialValue = 1f,
                        targetValue = 1.03f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        ),
                        label = "startPulseScale"
                    )
                    FilledIconButton(
                        onClick = onStart,
                        modifier = Modifier
                            .size(80.dp)
                            .scale(pulseScale)
                            .pressScale(startInteraction, pressedScale = 0.92f),
                        interactionSource = startInteraction,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.start_recording),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                
                // Add Route button (bottom-right) — same press feedback as
                // the sport button so both side controls feel identical.
                val addRouteInteraction = remember { MutableInteractionSource() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(
                        interactionSource = addRouteInteraction,
                        indication = null,
                        onClick = onAddRoute
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .pressScale(addRouteInteraction)
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
            // Centered full-width so the pause button sits mid-panel like the
            // Start button does (it used to hang on the panel's left edge).
            val pauseInteraction = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilledIconButton(
                    onClick = onPause,
                    modifier = Modifier
                        .size(80.dp)
                        .pressScale(pauseInteraction, pressedScale = 0.92f),
                    interactionSource = pauseInteraction,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.pause_recording), modifier = Modifier.size(36.dp))
                }
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
