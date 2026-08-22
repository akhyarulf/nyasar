package com.nyasar.app.ui.preview

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.components.NyasarMapView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * Two entry modes, per spec §20-23:
 *  - routeId != null: preselects the route's track bounding box (shortcut
 *    from Route Preview → "Download Map Offline", §23). The picker is
 *    still shown and resizable, not a black-box auto-download.
 *  - routeId == null: free area picker (§20 "User HARUS bisa download map
 *    walaupun TIDAK ADA GPX"), previously not possible at all — this
 *    screen required a route unconditionally.
 *
 * Area selection UX (§22 "map, bounding box, drag/resize, Around Me"):
 * rather than a custom draggable overlay (which would fight MapLibre's own
 * pan/pinch gesture handling — see NyasarMapView's onUserGesture doc), the
 * box is a fixed-size frame overlaid on screen and the user pans/zooms the
 * MAP underneath it. The frame's screen corners are converted to lat/lon
 * via MapLibreMap.projection whenever the camera settles, so "drag to
 * resize" becomes "pinch to resize, pan to move" — same gesture vocabulary
 * as the rest of the app, no gesture-recognizer conflicts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDownloadScreen(
    routeId: String? = null,
    viewModel: OfflineDownloadViewModel = viewModel(),
    onOpenOfflineMaps: () -> Unit = {},
    onBack: () -> Unit
) {
    val isFreeArea = routeId == null
    LaunchedEffect(routeId) { routeId?.let { viewModel.load(it) } }
    val state by viewModel.uiState.collectAsState()
    val provider = remember { TileProviderFactory.default() }
    val context = LocalContext.current
    val locationRepository = remember { LocationRepository(context) }
    val scope = rememberCoroutineScope()

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapWidthPx by remember { mutableStateOf(0) }
    var mapHeightPx by remember { mutableStateOf(0) }

    // Recomputes the picker box's geographic bounds from whatever screen
    // rect the frame occupies, using the map's current projection. Wired to
    // MapLibre's camera-idle listener so it re-runs after every pan/pinch
    // settles — "drag to resize" becomes pinch-to-resize, pan-to-move.
    // Screen rect comes from the actual MapView pixel size (onSizeChanged
    // below), not any assumed viewport API, so this only depends on
    // confirmed MapLibreMap surface: .projection and addOnCameraIdleListener.
    fun recomputeBoundsFromViewport(map: MapLibreMap, framePaddingFraction: Float = 0.12f) {
        if (mapWidthPx == 0 || mapHeightPx == 0) return
        val proj = map.projection
        val left = mapWidthPx * framePaddingFraction
        val right = mapWidthPx * (1f - framePaddingFraction)
        val top = mapHeightPx * framePaddingFraction
        val bottom = mapHeightPx * (1f - framePaddingFraction)
        val ne = proj.fromScreenLocation(android.graphics.PointF(right, top))
        val sw = proj.fromScreenLocation(android.graphics.PointF(left, bottom))
        val bounds = LatLngBounds.from(ne.latitude, ne.longitude, sw.latitude, sw.longitude)
        viewModel.setBounds(bounds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isFreeArea) "Download Area Offline" else "Siapkan Peta Offline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!isFreeArea && state.bounds == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    NyasarMapView(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                mapWidthPx = size.width
                                mapHeightPx = size.height
                                mapInstance?.let { recomputeBoundsFromViewport(it) }
                            },
                        provider = provider,
                        track = emptyList(),
                        focusBounds = state.bounds,
                        onMapReady = { map ->
                            mapInstance = map
                            // Camera-idle listener is now the only trigger
                            // for the very first recompute too (it fires
                            // once the initial camera position settles).
                            // A manual call here used to race onSizeChanged
                            // (fires once MapView reports its real pixel
                            // size, from 0x0) — both landing back-to-back on
                            // first render made the camera visibly jump/
                            // zoom right after opening this screen.
                            map.addOnCameraIdleListener { recomputeBoundsFromViewport(map) }
                        }
                    )

                    // Fixed picker frame — see recomputeBoundsFromViewport doc.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(1f - 2 * 0.12f)
                            .fillMaxHeight(1f - 2 * 0.12f)
                            .border(2.dp, MaterialTheme.colorScheme.primary)
                    )

                    if (isFreeArea) {
                        FilledIconButton(
                            onClick = {
                                scope.launch {
                                    if (!locationRepository.hasLocationPermission()) return@launch
                                    val fix = locationRepository.observeLocation().first()
                                    mapInstance?.animateCamera(
                                        CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), 13.0)
                                    )
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Di sekitar saya")
                        }
                    }
                }
            }

            Surface(tonalElevation = 2.dp) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        if (isFreeArea) {
                            "Geser dan zoom map untuk memilih area yang ingin diunduh — area di dalam kotak akan tersedia offline."
                        } else {
                            "Area sekitar track \"${state.routeName ?: ""}\" akan diunduh untuk digunakan tanpa internet. Anda bisa menggeser/zoom untuk mengubah area."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    state.estimatedTileCount?.let { count ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // Explicit "Perkiraan ukuran" framing per spec —
                            // never state a size as if it were exact, since
                            // real tile weight varies a lot by style/zoom
                            // content density (see formatEstimatedSize doc).
                            "Perkiraan ukuran: ${formatEstimatedSize(count)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    // Free-area name field (spec §21 example: "Lawu",
                    // "Klotok" — a real label, not "area-offline-<ts>").
                    // Route downloads already have a real name (the
                    // route's own), so this only applies to isFreeArea.
                    if (isFreeArea && (state.downloadState is DownloadState.Idle || state.downloadState is DownloadState.Error)) {
                        OutlinedTextField(
                            value = state.areaName,
                            onValueChange = viewModel::setAreaName,
                            label = { Text("Nama area") },
                            placeholder = { Text("mis. Lawu, Klotok") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // Zoom level choice (spec §22, explicitly listed control).
                    // Only shown before a download starts — changing it
                    // mid/post-download wouldn't do anything meaningful.
                    if (state.downloadState is DownloadState.Idle || state.downloadState is DownloadState.Error) {
                        Text("Level detail", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = state.maxZoom == 16.0,
                                onClick = { viewModel.setMaxZoom(16.0) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) { Text("Standar") }
                            SegmentedButton(
                                selected = state.maxZoom == 18.0,
                                onClick = { viewModel.setMaxZoom(18.0) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) { Text("Detail tinggi") }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    when (val ds = state.downloadState) {
                        is DownloadState.Idle -> {
                            Button(
                                onClick = { viewModel.startDownload(routeId ?: "area") },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                enabled = state.bounds != null
                            ) {
                                Text("DOWNLOAD AREA")
                            }
                        }
                        is DownloadState.Loading -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("Menyiapkan unduhan…")
                        }
                        is DownloadState.InProgress -> {
                            LinearProgressIndicator(
                                progress = ds.percentage / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Mengunduh… ${ds.percentage.toInt()}% · ${formatBytes(state.completedSizeBytes)}"
                            )
                            Spacer(Modifier.height(8.dp))
                            // Cancel is only shown/wired once activeRegion is
                            // set (see ViewModel.cancelDownload doc) — that's
                            // the real signal the engine can actually stop
                            // this download, never a button that's shown
                            // regardless and silently does nothing.
                            if (state.activeRegion != null) {
                                OutlinedButton(
                                    onClick = viewModel::cancelDownload,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("CANCEL")
                                }
                            }
                        }
                        is DownloadState.Done -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Download selesai", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.routeName ?: state.areaName.trim().ifBlank { "Area" },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Perkiraan ukuran: ${formatBytes(state.completedSizeBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Peta ini tersedia untuk penggunaan offline.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onOpenOfflineMaps) { Text("BUKA PETA") }
                                TextButton(onClick = onBack) { Text("SELESAI") }
                            }
                        }
                        is DownloadState.Error -> {
                            Text(
                                "Gagal mengunduh: ${ds.message}",
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { viewModel.startDownload(routeId ?: "area") }) {
                                Text("Coba lagi")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Very rough MB estimate — real tile weight varies a lot by style/zoom
 *  content density, but a ballpark beats no number at all (spec §22).
 *  Framed explicitly as an estimate ("~X MB"), never a bare/implied-exact
 *  figure. */
private fun formatEstimatedSize(tileCount: Int): String {
    val estimatedMb = tileCount * 0.015 // ~15KB/tile rough average for vector styles
    return if (estimatedMb >= 1) "~%.0f MB (dari $tileCount tile)".format(estimatedMb) else "< 1 MB"
}

/** Real, measured size from OfflineRegionStatus — used once a download is
 *  actually in progress or done, never the pre-download estimate. */
private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
