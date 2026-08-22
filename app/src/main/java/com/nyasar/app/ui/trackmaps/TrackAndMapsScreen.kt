package com.nyasar.app.ui.trackmaps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * PART 2 — combined "Track & Peta" screen. Both sections read existing
 * data only (RouteRepository via TrackAndMapsViewModel, OfflineMapManager
 * via the same ViewModel) — nothing here owns or mutates route/offline-map
 * state, it's a read-only combined view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackAndMapsScreen(
    viewModel: TrackAndMapsViewModel = viewModel(),
    onOpenRoute: (String) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenHome: () -> Unit = {},
    onOpenDrawRoute: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val importError by viewModel.importError.collectAsState()
    var searchExpanded by remember { mutableStateOf(false) }

    val pickGpx = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.importGpx(it) } }

    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Top bar
        TopAppBar(
            title = { Text("Library") },
            actions = {
                IconButton(onClick = { searchExpanded = !searchExpanded }) {
                    Icon(Icons.Default.Search, contentDescription = "Cari track")
                }
            }
        )

        // Import GPX + Gambar Rute
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { pickGpx.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import GPX")
            }
            OutlinedButton(
                onClick = onOpenDrawRoute,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Gambar Rute")
            }
        }

        importError?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (searchExpanded) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Cari nama track...") },
                singleLine = true
            )
        }

        // Filter pill row
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterPill("Semua", state.filter == TrackAndMapsFilter.ALL) { viewModel.setFilter(TrackAndMapsFilter.ALL) }
            FilterPill("Track", state.filter == TrackAndMapsFilter.TRACK) { viewModel.setFilter(TrackAndMapsFilter.TRACK) }
            FilterPill("Peta Offline", state.filter == TrackAndMapsFilter.OFFLINE) { viewModel.setFilter(TrackAndMapsFilter.OFFLINE) }
        }

        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (state.filter != TrackAndMapsFilter.TRACK) {
                item { SectionHeader("Peta Offline", onSeeAll = onOpenOfflineMaps) }
                item {
                    OfflineSummaryBanner(
                        count = state.offlineRegionCount,
                        previewNames = state.offlineRegionPreviewNames,
                        onClick = onOpenOfflineMaps
                    )
                }
            }

            if (state.filter != TrackAndMapsFilter.OFFLINE) {
                item { SectionHeader("Track (GPX)", onSeeAll = null) }
                val tracks = state.filteredTracks
                if (tracks.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (state.searchQuery.isBlank()) "Belum ada track. Import GPX atau gambar rute sendiri."
                                else "Tidak ada track yang cocok.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.searchQuery.isBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { pickGpx.launch("*/*") }) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Import GPX")
                                        }
                                        OutlinedButton(onClick = onOpenDrawRoute) {
                                            Icon(Icons.Default.Edit, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Gambar Rute")
                                        }
                                    }
                                }
                        }
                    }
                } else {
                    items(tracks, key = { it.route.id }) { row ->
                        TrackRow(row, onClick = {
                            onOpenRoute(row.route.id)
                        })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onSeeAll: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        onSeeAll?.let {
            Text(
                "Lihat >",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = it)
            )
        }
    }
}

@Composable
private fun OfflineSummaryBanner(count: Int, previewNames: List<String>, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (count == 0) {
                    Text("Belum ada peta offline", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Download area map untuk dipakai tanpa sinyal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("$count area tersimpan", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        previewNames.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TrackRow(row: TrackRowUi, onClick: () -> Unit) {
    val km = row.route.distanceMeters / 1000.0
    val statusText = when (row.hasOfflineCoverage) {
        true -> "\u2713 Siap dipakai offline"
        false -> "Belum lengkap"
        null -> "Memeriksa status\u2026"
    }
    ListItem(
        headlineContent = { Text(row.route.name) },
        supportingContent = {
            Text(
                "%.1f km \u00b7 $statusText".format(km),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                if (row.hasOfflineCoverage == true) Icons.Default.CloudDone else Icons.Default.CloudOff,
                contentDescription = null,
                tint = if (row.hasOfflineCoverage == true) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
