package com.nyasar.app.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.components.NyasarMapView

/**
 * "Peta Offline" — spec P3 gap: download existed (Route Preview), management
 * didn't, and coverage was invisible (spec §24, WAJIB). Reachable from
 * Settings > Offline. Shows: coverage map at the top (so "area mana yang
 * sudah saya download?" has an actual answer), list below with size/status/
 * view/delete, and a "+ Download Area" FAB for the route-free entry point
 * (spec §22).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    viewModel: OfflineMapsViewModel = viewModel(),
    onBack: () -> Unit,
    onDownloadArea: () -> Unit = {},
    // "Lihat di Peta" wiring back to Home with the area focused is PART 4
    // scope (per spec, explicitly not this part). Until that route exists,
    // this reuses the exact same real, already-working action the old
    // "eye" icon had — focusing the coverage map already rendered at the
    // top of this screen — rather than a no-op TODO stub (spec: "JANGAN
    // bikin behavior asal-asalan").
    onOpenInMap: (OfflineRegionUi) -> Unit = { viewModel.focus(it) }
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val state by viewModel.uiState.collectAsState()
    val provider = remember { TileProviderFactory.default() }
    // Delete confirmation (spec: "tidak accidental, confirmation bila
    // diperlukan") — previously a single tap deleted a downloaded region
    // immediately, no way back for something that can be tens/hundreds of
    // MB and took real time to download.
    var pendingDelete by remember { mutableStateOf<OfflineRegionUi?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Peta Offline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onDownloadArea,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Download Area") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val boundsWithCoverage = state.regions.mapNotNull { it.bounds }
            val focusedBounds = state.regions.firstOrNull {
                System.identityHashCode(it.region) == state.focusedRegionKey
            }?.bounds

            // Coverage map (spec §24, WAJIB) — always visible when there's
            // at least one region, regardless of status-check completion,
            // since bounds come from the region definition, not the status
            // callback (see OfflineMapsViewModel).
            if (boundsWithCoverage.isNotEmpty()) {
                Box(Modifier.height(220.dp).fillMaxWidth()) {
                    NyasarMapView(
                        modifier = Modifier.fillMaxSize(),
                        provider = provider,
                        track = emptyList(),
                        offlineCoverage = boundsWithCoverage,
                        focusBounds = focusedBounds ?: boundsWithCoverage.reduce { a, b ->
                            org.maplibre.android.geometry.LatLngBounds.Builder()
                                .include(a.northEast).include(a.southWest)
                                .include(b.northEast).include(b.southWest)
                                .build()
                        }
                    )
                }
                HorizontalDivider()
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.regions.isEmpty() -> Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Belum ada peta offline.\nDownload untuk dipakai tanpa sinyal di jalur.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(20.dp))
                        // Spec §5: empty state needs a real central CTA, not
                        // just the corner FAB — the FAB stays too (still
                        // useful once the list has content), this is
                        // additive for the zero-region case specifically.
                        Button(onClick = onDownloadArea, modifier = Modifier.height(48.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Download Map")
                        }
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.regions, key = { System.identityHashCode(it.region) }) { item ->
                            OfflineRegionCard(
                                item = item,
                                isDeleting = System.identityHashCode(item.region) == state.deletingRegionKey,
                                isResuming = System.identityHashCode(item.region) == state.resumingRegionKey,
                                onPrimaryAction = {
                                    if (item.completed) onOpenInMap(item) else viewModel.resumeDownload(item)
                                },
                                onDeleteRequest = { pendingDelete = item }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus peta offline?") },
            text = {
                Text("\"${item.name}\" (${formatSize(item.sizeBytes)}) akan dihapus dari penyimpanan. Tindakan ini tidak bisa dibatalkan.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    pendingDelete = null
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Batal") }
            }
        )
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}

/**
 * PART 3 redesign: banner card with mini-map preview showing the actual
 * coverage area. Each card now shows:
 * - Mini-map preview of the downloaded area
 * - Area name
 * - Download size
 * - Status (ready/incomplete)
 * - Coverage bounds info
 */
@Composable
private fun OfflineRegionCard(
    item: OfflineRegionUi,
    isDeleting: Boolean,
    isResuming: Boolean,
    onPrimaryAction: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val provider = remember { TileProviderFactory.default() }

    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
        Column {
            // Mini-map preview showing the actual coverage area
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                if (item.bounds != null) {
                    // Real map preview focused on the downloaded area
                    NyasarMapView(
                        modifier = Modifier.fillMaxSize(),
                        provider = provider,
                        track = emptyList(),
                        offlineCoverage = listOf(item.bounds),
                        focusBounds = item.bounds
                    )
                } else {
                    // Fallback gradient if bounds not available
                    val gradient = if (item.completed) {
                        Brush.verticalGradient(listOf(Color(0xFF2E7D32), Color(0xFF1B5E20)))
                    } else {
                        Brush.verticalGradient(listOf(Color(0xFF9E9E9E), Color(0xFF757575)))
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(gradient)
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.align(Alignment.Center).size(40.dp)
                        )
                    }
                }

                // Status tag overlay
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    StatusTag(completed = item.completed, statusKnown = item.statusKnown, statusError = item.statusError)
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Opsi", tint = Color.White)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Hapus") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { showMenu = false; onDeleteRequest() }
                            )
                        }
                    }
                }
            }

            // Info section
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    
                    // Size and status info
                    Text(
                        when {
                            !item.statusKnown -> "Memeriksa status…"
                            item.statusError -> "Gagal memeriksa status"
                            item.completed -> "${formatSize(item.sizeBytes)} • Siap digunakan"
                            else -> "${formatSize(item.sizeBytes)} terunduh"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Coverage bounds info if available
                    item.bounds?.let { bounds ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "%.4f° - %.4f° L, %.4f° - %.4f° B".format(
                                bounds.latitudeSouth, bounds.latitudeNorth,
                                bounds.longitudeWest, bounds.longitudeEast
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                when {
                    isDeleting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    isResuming -> Button(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Mengunduh…")
                    }
                    item.completed -> Button(onClick = onPrimaryAction) { Text("Lihat di Peta") }
                    else -> Button(onClick = onPrimaryAction) { Text("Lanjut unduh") }
                }
            }
        }
    }
}

@Composable
private fun StatusTag(completed: Boolean, statusKnown: Boolean, statusError: Boolean) {
    val label = when {
        !statusKnown -> "Memeriksa…"
        statusError -> "Gagal memeriksa"
        completed -> "\u2713 Siap dipakai offline"
        else -> "Belum lengkap"
    }
    Surface(
        color = Color.Black.copy(alpha = 0.35f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
