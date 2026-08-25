package com.nyasar.app.ui.drawroute

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.components.NyasarMapView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

/**
 * Manual point-by-point route drawing (spec: "tap titik satu-satu di peta,
 * garis lurus otomatis nyambung antar titik" — the Ride with GPS "manual
 * mode" reference, deliberately NOT snap-to-road/community-heatmap like
 * Strava/Komoot default to, since those need a routing engine + hosted
 * road/trail data this app doesn't have, and hiking trails are often
 * unmapped anyway — see the earlier discussion on why P1 stays manual).
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
    val provider = remember { TileProviderFactory.default() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationRepository = remember { LocationRepository(context) }
    var mapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var showFinishSheet by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

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
            provider = provider,
            track = emptyList(),
            // drawnPoints (not track) — track's LaunchedEffect key would
            // re-run the full style-setup effect (incl. a camera bounds
            // refit) on every single tap; drawnPoints has its own isolated
            // update path that doesn't touch the camera at all, letting
            // the user keep tapping without the map jumping around.
            drawnPoints = state.points,
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
                // Manual fallback for the automatic centering above — that
                // one silently no-ops on permission-denied or a slow fix,
                // so this button exists for the user to retry on demand
                // instead of being stuck on a default view with no
                // recourse.
                IconButton(onClick = {
                    scope.launch {
                        if (!locationRepository.hasLocationPermission()) return@launch
                        val fix = locationRepository.observeLocation().first()
                        mapInstance?.animateCamera(
                            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                                org.maplibre.android.geometry.LatLng(fix.lat, fix.lon), 14.0
                            )
                        )
                    }
                }) {
                    Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.go_to_location_cd))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

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
                }
                Button(
                    onClick = { showFinishSheet = true },
                    enabled = state.canFinish
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinishRouteSheet(
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, alsoNavigate: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp)) {
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
