package com.nyasar.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.recording.RecordingStatus
import com.nyasar.app.ui.components.CameraFollowMode
import com.nyasar.app.ui.components.CompassButton
import com.nyasar.app.ui.components.NyasarMapView
import com.nyasar.app.ui.components.ZoomControls
import com.nyasar.app.ui.recording.RecordingViewModel
import kotlinx.coroutines.flow.map
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * Map stays the dominant element (spec section 7: "jangan membuat tampilan
 * seperti dashboard fitness") — a slim stat bar below it. Shows the planned
 * GPX track and the user's live GPS position; the user reads and follows
 * the line themselves — no off-route detection or warning (removed, this
 * app isn't a turn-by-turn navigator).
 *
 * withRecording: when true (Start Activity flow with both Recording and
 * Navigation on), this screen also renders a compact pause/resume/stop
 * strip driven by [RecordingViewModel] — the same RecordingService the
 * caller already started before navigating here, not a second one.
 * NavigationViewModel independently detects that running service and
 * shares its GPS stream (see NavigationViewModel.start), so stopping
 * navigation here never touches the recording session, and vice versa
 * (Task 6 requirement).
 */
@Composable
fun NavigationScreen(
    routeId: String,
    withRecording: Boolean = false,
    viewModel: NavigationViewModel = viewModel(),
    recordingViewModel: RecordingViewModel = viewModel(),
    // P3E3 acceptance gap: user waypoints could only be added from Home,
    // so "Recording + Waypoint" / "Navigation + Waypoint" had no path to
    // even create one together. Same WaypointViewModel Home already uses —
    // waypoints are process-wide (not tied to a route or activity), so
    // there's no separate "navigation waypoints" concept to build.
    waypointViewModel: com.nyasar.app.ui.waypoint.WaypointViewModel = viewModel(),
    onExit: () -> Unit
) {
    LaunchedEffect(routeId) { viewModel.start(routeId) }
    val state by viewModel.uiState.collectAsState()
    val cameraMode by viewModel.cameraMode.collectAsState()
    val followMode by viewModel.followMode.collectAsState()
    val rotateWithHeading by viewModel.rotateWithHeading.collectAsState()
    val recordingState by recordingViewModel.uiState.collectAsState()
    val userWaypoints by waypointViewModel.waypoints.collectAsState()
    val pendingWaypointTap by waypointViewModel.pendingTap.collectAsState()
    val selectedWaypoint by waypointViewModel.selectedWaypoint.collectAsState()
    val editingWaypoint by waypointViewModel.editingWaypoint.collectAsState()
    var mapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var mapBearing by remember { mutableStateOf(0f) }
    val navContext = androidx.compose.ui.platform.LocalContext.current
    val settingsRepository = remember(navContext) {
        com.nyasar.app.data.settings.SettingsRepository(navContext)
    }
    val speedUnit by settingsRepository.settings
        .map { it.speedUnit }
        .collectAsState(initial = "kmh")
    // Real measured height of the bottom stat Surface, not a guessed fixed
    // dp value — the stat bar's actual height varies with font scale/screen
    // width (3 rows of stats can wrap differently), so a hardcoded 180dp/
    // 220dp offset could fall short and let the stat bar visually cover the
    // recenter/zoom buttons on some devices ("recenter ketutup"). Buttons
    // are composed before the stat Surface, so an undersized guess means
    // the surface literally draws on top of them.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var statBarHeight by remember { mutableStateOf(180.dp) }
    val bottomClearance = statBarHeight + 12.dp

    Box(Modifier.fillMaxSize()) {
        NyasarMapView(
            modifier = Modifier.fillMaxSize(),
            provider = state.provider,
            track = state.track,
            // Planned route + actual walked track together (spec section 3:
            // "Jika navigation + recording: planned route terlihat, actual
            // recorded track terlihat"). recordedTrack only has entries when
            // withRecording is true — RecordingService publishes it, empty
            // list otherwise, so this is a no-op for plain navigation.
            actualTrack = if (withRecording) recordingState.recordedTrack else emptyList(),
            waypoints = state.waypoints,
            userWaypoints = userWaypoints,
            onUserWaypointClick = { id ->
                waypointViewModel.selectWaypoint(userWaypoints.firstOrNull { it.id == id })
            },
            onMapLongPress = { lat, lon ->
                waypointViewModel.onMapLongPress(lat, lon, state.currentElevationM)
            },
            userLocation = state.userLocation?.let { LatLng(it.lat, it.lon) },
            accuracyMeters = state.userLocation?.accuracyMeters,
            userHeadingDeg = state.displayHeadingDeg,
            followUser = followMode,
            rotateWithHeading = rotateWithHeading,
            onUserGesture = viewModel::onUserPanned,
            onBearingChanged = { mapBearing = it },
            onMapReady = { mapInstance = it }
        )

        // Compass: pure "reset to north" again. No longer also toggles
        // heading-up — that job moved entirely to the recenter button's
        // 3-state cycle below, so each control now has exactly one job.
        CompassButton(
            bearingDeg = mapBearing,
            onClick = {
                viewModel.resetToNorth()
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

        // Recenter — 3-state cycle on tap, same as Google Maps' own
        // location button: lepas dari GPS (FREE) -> ikut posisi, utara di
        // atas (FOLLOW_NORTH_UP) -> ikut posisi + peta muter sesuai arah
        // hadap (FOLLOW_HEADING) -> balik ke FREE. Icon and color change
        // per state so which mode is active is visible without reading
        // any label.
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
                    CameraFollowMode.FREE -> "Lepas dari lokasi — ketuk untuk mengikuti posisi GPS"
                    CameraFollowMode.FOLLOW_NORTH_UP -> "Mengikuti posisi, utara di atas — ketuk untuk mengikuti arah hadap"
                    CameraFollowMode.FOLLOW_HEADING -> "Mengikuti posisi dan arah hadap — ketuk untuk lepas dari lokasi"
                }
            )
        }

        // Next Waypoint (P3E3) — small persistent chip.
        AnimatedVisibility(
            visible = state.nextWaypoint != null,
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 64.dp)
        ) {
            state.nextWaypoint?.let { NextWaypointChip(it, userHeadingDeg = state.displayHeadingDeg) }
        }

        IconButton(
            onClick = onExit,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Keluar",
                tint = Color.White
            )
        }

        // Zoom +/- — bottom-start, clear of the recenter button (top-end)
        // and the bottom stat bar (its own surface).
        ZoomControls(
            map = mapInstance,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = bottomClearance)
        )

        // Bottom stat bar
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    statBarHeight = with(density) { size.height.toDp() }
                },
            tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatBlock("%.2f km".format(state.distanceTraveledMeters / 1000.0), "Jarak tempuh")
                    // P3E1: current elevation was computed in NavigationEngine
                    // but never surfaced here — only gain-so-far was shown.
                    StatBlock(
                        state.currentElevationM?.let { "${it.roundToInt()} m" } ?: "-",
                        "Elevasi saat ini"
                    )
                    StatBlock("↑ ${state.elevationGainSoFarM.roundToInt()} m", "Elevation gain")
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatBlock(formatDuration(state.movingTimeMs), "Waktu bergerak")
                    StatBlock(
                        com.nyasar.app.util.SpeedUtils.formatSpeed(state.currentSpeedKmh, speedUnit, 1),
                        "Kecepatan"
                    )
                    // Null (route has no elevation data for what's left) is
                    // shown as "-", never a fabricated 0 m.
                    StatBlock(
                        state.remainingElevationGainM?.let { "↑ ${it.roundToInt()} m" } ?: "-",
                        "Sisa elevasi"
                    )
                }
                Spacer(Modifier.height(12.dp))
                state.userLocation?.accuracyMeters?.let { acc ->
                    Text(
                        "GPS ±${acc.roundToInt()} m",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (withRecording) {
                    Spacer(Modifier.height(12.dp))
                    RecordingStrip(
                        status = recordingState.status,
                        distanceMeters = recordingState.distanceMeters,
                        onPause = recordingViewModel::pauseRecording,
                        onResume = recordingViewModel::resumeRecording,
                        onStop = recordingViewModel::stopRecording
                    )
                }
            }
        }
    }

    // P3E3: same Add-Waypoint sheet Home uses, opened by the long-press
    // wired into NyasarMapView above — Navigation previously had no path
    // to create a user waypoint at all, only view GPX ones.
    pendingWaypointTap?.let { tap ->
        com.nyasar.app.ui.waypoint.WaypointFormSheet(
            title = "Waypoint Baru",
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

    // P3E3: tapping an existing user waypoint marker opens this detail
    // sheet — same composable and distance calc Home uses.
    selectedWaypoint?.let { wp ->
        val distance = state.userLocation?.let {
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

    // P3E3: Edit sheet for an existing waypoint, reuses the same form as Add.
    editingWaypoint?.let { wp ->
        val category = com.nyasar.app.data.db.WaypointCategory.fromStorageValue(wp.category)
        com.nyasar.app.ui.waypoint.WaypointFormSheet(
            title = "Edit Waypoint",
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

/** Compact — this is a secondary control on top of navigation, not a
 *  second dashboard (spec: navigation UI should not turn into a fitness
 *  dashboard even when recording is layered on top of it). */
@Composable
private fun RecordingStrip(
    status: RecordingStatus,
    distanceMeters: Double,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "● Recording · %.2f km".format(distanceMeters / 1000.0),
            style = MaterialTheme.typography.labelLarge
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (status) {
                RecordingStatus.RECORDING -> IconButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, contentDescription = "Jeda recording")
                }
                RecordingStatus.PAUSED -> IconButton(onClick = onResume) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Lanjutkan recording")
                }
                else -> {}
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Selesai recording")
            }
        }
    }
}

/** P3E3: name, distance, direction, elevation diff — compact single row,
 *  deliberately not a card with its own stats section (spec: "jangan
 *  membuat navigation terlalu ramai").
 *
 *  Direction (fix #1, P3E3): shown relative to where the user is actually
 *  facing ("depan kanan", "belok kiri") when a heading is available —
 *  far more actionable while walking than an absolute compass letter,
 *  since the user doesn't have to mentally subtract their own facing to
 *  figure out which way to turn. Falls back to the absolute 8-point
 *  cardinal only when [userHeadingDeg] is null (no sensor, no reliable
 *  GPS bearing yet) — never a fabricated "in front of you". */
@Composable
private fun NextWaypointChip(next: NextWaypoint, userHeadingDeg: Float?) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Navigation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                // Icon's rest orientation points "up" on screen. Rotating
                // by (waypoint bearing - user heading) points it at the
                // waypoint relative to the direction the user is actually
                // facing. Falls back to the raw absolute bearing (points
                // as if screen-up were north) when heading is unknown —
                // matches the absolute-cardinal fallback text next to it.
                modifier = Modifier
                    .size(18.dp)
                    .rotate(
                        if (userHeadingDeg != null) (next.bearingDeg - userHeadingDeg).toFloat() else next.bearingDeg.toFloat()
                    )
            )
            Column {
                Text(next.waypoint.name, style = MaterialTheme.typography.labelLarge)
                Text(
                    buildString {
                        append(formatWaypointDistance(next.distanceMeters))
                        append(" · ${directionLabel(next.bearingDeg, userHeadingDeg)}")
                        next.elevationDiffM?.let { diff ->
                            append(if (diff >= 0) " · ↑ ${diff.roundToInt()} m" else " · ↓ ${(-diff).roundToInt()} m")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatWaypointDistance(meters: Double): String =
    if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "${meters.roundToInt()} m"

/** Relative turn-style label when a heading is available ("di depan",
 *  "belok kanan", "di belakang"), else the absolute 8-point cardinal.
 *  Same 8-way granularity either way so the label never implies more
 *  precision than a phone compass actually has. */
private fun directionLabel(waypointBearingDeg: Double, userHeadingDeg: Float?): String {
    if (userHeadingDeg == null) return cardinalDirection(waypointBearingDeg)
    val relative = (((waypointBearingDeg - userHeadingDeg) % 360) + 360) % 360
    val labels = listOf(
        "di depan", "depan kanan", "belok kanan", "belakang kanan",
        "di belakang", "belakang kiri", "belok kiri", "depan kiri"
    )
    val index = (relative / 45.0).roundToInt() % 8
    return labels[index]
}

/** 8-point compass label — fallback for when no heading is available to
 *  compute a relative direction from (spec §6/13 compass conventions
 *  apply here too). */
private fun cardinalDirection(bearingDeg: Double): String {
    val directions = listOf("U", "TL", "T", "TG", "S", "BD", "B", "BL")
    val index = (((bearingDeg % 360) + 360) % 360 / 45.0).roundToInt() % 8
    return directions[index]
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
