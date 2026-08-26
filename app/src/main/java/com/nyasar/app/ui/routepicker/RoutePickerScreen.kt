package com.nyasar.app.ui.routepicker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.ui.trackmaps.TrackAndMapsViewModel
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

/**
 * Dedicated route picker screen for Record tab's "Pilih Jalur" flow.
 * Separated from TrackAndMapsScreen (Library) to keep the two flows
 * independent — this screen shows ONLY the route list without the
 * Library's offline maps section, filter pills, or other Library UI.
 *
 * When a route is tapped, it sets the selected route and pops back
 * to RecordingScreen — no preview screen involved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePickerScreen(
    viewModel: TrackAndMapsViewModel = viewModel(),
    onRouteSelected: (String) -> Unit,
    onOpenDrawRoute: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val importError by viewModel.importError.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    val pickGpx = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.importGpx(it) } }

    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.pick_jalur)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                var searchExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { searchExpanded = !searchExpanded }) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_track_cd))
                }
                if (searchExpanded) {
                    // Inline search field replaces the icon temporarily
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
                Text(stringResource(R.string.import_gpx))
            }
            OutlinedButton(
                onClick = onOpenDrawRoute,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.draw_route))
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

        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_track_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )

        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val tracks = state.tracks.filter {
                searchQuery.isBlank() || it.route.name.contains(searchQuery, ignoreCase = true)
            }

            if (tracks.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (searchQuery.isBlank())
                            "Belum ada track.\nImport GPX atau gambar rute sendiri."
                        else "Tidak ada track yang cocok.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(tracks, key = { it.route.id }) { row ->
                        RoutePickerRow(
                            name = row.route.name,
                            distanceKm = row.route.distanceMeters / 1000.0,
                            onClick = { onRouteSelected(row.route.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePickerRow(
    name: String,
    distanceKm: Double,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                "%.1f km".format(distanceKm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}
