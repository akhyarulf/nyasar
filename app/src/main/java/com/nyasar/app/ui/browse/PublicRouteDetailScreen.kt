package com.nyasar.app.ui.browse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.ui.components.pressScale
import com.nyasar.app.ui.theme.NyasarRadius
import java.io.File
import kotlin.math.roundToInt

/**
 * Detail screen for a PUBLIC route (Fase 2 slice: browse → detail). Shows the
 * publisher's username, stats, mini track preview, description, and the
 * "full open" actions: view the track on the map (navigating with the lossy
 * track_polyline) and Download GPX (decompresses the gzip'ed original from
 * Storage and shares it via the same FileProvider path as the P3G export —
 * PROJECT_CONTEXT.md Keputusan poin 6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicRouteDetailScreen(
    routeId: String,
    viewModel: PublicRouteDetailViewModel = viewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val download by viewModel.download.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(routeId) { viewModel.load(routeId) }

    // One-shot: when a downloaded GPX is ready, write it to the cache exports
    // dir and fire the share sheet (same mechanism shareActivityGpx uses).
    LaunchedEffect(download) {
        val ready = download as? PublicRouteDetailViewModel.DownloadState.Ready ?: return@LaunchedEffect
        viewModel.downloadConsumed()
        val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, ready.fileName).apply { writeText(ready.gpxXml) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, ready.fileName))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.route_detail_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        when (val s = state) {
            PublicRouteDetailViewModel.State.Loading -> {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is PublicRouteDetailViewModel.State.Error -> {
                Column(
                    Modifier.padding(padding).fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        stringResource(s.messageRes),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.load(routeId) }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            is PublicRouteDetailViewModel.State.Ready -> {
                val route = s.route
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        route.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val publisher = route.profiles?.username
                    if (publisher != null) {
                        Text(
                            stringResource(R.string.browse_by, publisher),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(NyasarRadius.md),
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        MiniTrackPreview(
                            polyline = route.trackPolyline,
                            modifier = Modifier.fillMaxWidth().height(180.dp).padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatBlock("%.1f km".format(route.distanceMeters / 1000.0), stringResource(R.string.distance))
                        route.elevationGainM?.let {
                            StatBlock("+${it.roundToInt()} m", stringResource(R.string.elev_gain))
                        }
                        route.maxElevationM?.let {
                            StatBlock("${it.roundToInt()} m", stringResource(R.string.route_detail_elev_max))
                        }
                    }
                    if (route.minElevationM != null || route.elevationLossM != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            route.minElevationM?.let {
                                StatBlock("${it.roundToInt()} m", stringResource(R.string.route_detail_elev_min))
                            }
                            route.elevationLossM?.let {
                                StatBlock("−${it.roundToInt()} m", stringResource(R.string.route_detail_elev_loss))
                            }
                        }
                    }
                    route.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.route_detail_description),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } ?: run {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.route_detail_no_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { onBack() },
                            enabled = download !is PublicRouteDetailViewModel.DownloadState.InProgress,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.browse_open_in_map), maxLines = 1)
                        }
                        Button(
                            onClick = { viewModel.downloadGpx(route) },
                            enabled = download !is PublicRouteDetailViewModel.DownloadState.InProgress,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (download is PublicRouteDetailViewModel.DownloadState.InProgress) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.browse_downloading), maxLines = 1)
                            } else {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.browse_download_gpx), maxLines = 1)
                            }
                        }
                    }
                    if (download is PublicRouteDetailViewModel.DownloadState.Error) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource((download as PublicRouteDetailViewModel.DownloadState.Error).messageRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
