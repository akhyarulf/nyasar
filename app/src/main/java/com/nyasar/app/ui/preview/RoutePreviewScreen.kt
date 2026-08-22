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
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.gpx.model.GpxWaypoint
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.ui.components.CompassButton
import com.nyasar.app.ui.components.ElevationProfile
import com.nyasar.app.ui.components.NyasarMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

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

    // GPS state collection (mirrors HomeScreen pattern)
    val currentLocation by viewModel.currentLocation.collectAsState()
    val followMode by viewModel.followMode.collectAsState()
    val rotateWithHeading by viewModel.rotateWithHeading.collectAsState()

    // Start location updates once permission is granted
    LaunchedEffect(Unit) { viewModel.startLocationUpdatesIfPermitted() }

    var selectedWaypoint by remember { mutableStateOf<GpxWaypoint?>(null) }
    // Highlight marker position when user scrubs the elevation chart
    var highlightLatLng by remember { mutableStateOf<LatLng?>(null) }

    // Map controls state
    var mapInstance by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var mapBearing by remember { mutableStateOf(0f) }
    var showLayerMenu by remember { mutableStateOf(false) }
    var currentStyleVariant by remember { mutableStateOf(StyleVariant.OUTDOOR) }
    var currentProvider by remember { mutableStateOf(state.provider) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.name ?: "Route",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    state.gpxFilePath?.let { path ->
                        IconButton(onClick = { shareRouteGpx(context, path, state.name ?: "route") }) {
                            Icon(Icons.Default.Share, contentDescription = "Export/Share GPX")
                        }
                    }
                    IconButton(onClick = { onDownloadOfflineMap(routeId) }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Siapkan peta offline")
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
                    track = state.track,
                    waypoints = state.waypoints,
                    highlightPoint = highlightLatLng,
                    onWaypointClick = { selectedWaypoint = it },
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
                    // Layer button
                    Box {
                        RoundIconButton(icon = Icons.Default.Layers, contentDescription = "Layer peta") {
                            showLayerMenu = true
                        }
                        DropdownMenu(expanded = showLayerMenu, onDismissRequest = { showLayerMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Standard") },
                                onClick = { currentStyleVariant = StyleVariant.OUTDOOR; showLayerMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Satellite") },
                                onClick = { currentStyleVariant = StyleVariant.SATELLITE; showLayerMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Terrain") },
                                onClick = { currentStyleVariant = StyleVariant.TOPO; showLayerMenu = false }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Ganti provider (${currentProvider.displayName})") },
                                onClick = {
                                    val providers = TileProviderFactory.all()
                                    val idx = providers.indexOf(currentProvider)
                                    currentProvider = providers[(idx + 1) % providers.size]
                                    showLayerMenu = false
                                }
                            )
                        }
                    }
                    // Location button — center on user GPS position + toggle heading
                    RoundIconButton(
                        icon = if (rotateWithHeading) Icons.Default.Navigation else Icons.Default.MyLocation,
                        contentDescription = if (rotateWithHeading) "Mode heading-up aktif — tap untuk north-up" else "Ke lokasi saya",
                        tint = if (followMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        onClick = { viewModel.centerOnLocation() }
                    )
                }
            }

            // Bottom info section — scrollable for responsive layout
            Surface(tonalElevation = 2.dp) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Stats row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                        Text("Siapkan peta offline untuk area ini")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onStartNavigation(routeId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("MULAI NAVIGASI", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    selectedWaypoint?.let { wp ->
        WaypointDetailSheet(waypoint = wp, onDismiss = { selectedWaypoint = null })
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = modifier.size(48.dp)
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = tint)
        }
    }
}

@Composable
private fun Stat(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

/** Spec section 13: nama, koordinat, elevation, description saat waypoint dipilih. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaypointDetailSheet(waypoint: GpxWaypoint, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(waypoint.name, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            DetailRow("Koordinat", "%.5f, %.5f".format(waypoint.lat, waypoint.lon))
            waypoint.elevationM?.let { DetailRow("Elevation", "${it.roundToInt()} m") }
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
    context.startActivity(android.content.Intent.createChooser(intent, "Bagikan route: $routeName"))
}
