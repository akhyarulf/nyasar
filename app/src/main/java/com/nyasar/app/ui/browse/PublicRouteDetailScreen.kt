package com.nyasar.app.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
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
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.DifficultyChip
import com.nyasar.app.ui.components.ElevationProfile
import com.nyasar.app.ui.components.StaticMapPreview
import com.nyasar.app.ui.components.TrailTypeChip
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Detail screen for a PUBLIC route (Fase 2) — Strava-informed structure on
 * Nyasar's own visual language:
 *  - full-bleed static map hero with the route trace (same component as the
 *    browse cards — canvas fallback when tiles fail, hiking context),
 *  - author row (avatar chip + username + likes), difficulty/trail chips,
 *  - centered 2-column stats grid (3 columns on wide screens), Strava-style,
 *  - elevation profile section streamed from the ORIGINAL GPX (track_polyline
 *    is lossy lat/lon-only) via the same chart the activity detail uses;
 *    section silently absent when no GPX/elevation exists,
 *  - the "full open" actions unchanged: Save-to-Library + Download GPX.
 *
 * Content is capped at 640dp and centered on large screens; every label is
 * stringResource'd (ID/EN).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicRouteDetailScreen(
    routeId: String,
    viewModel: PublicRouteDetailViewModel = viewModel(),
    onOpenRoutePreview: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val download by viewModel.download.collectAsState()
    val save by viewModel.save.collectAsState()
    val elevation by viewModel.elevation.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(routeId) { viewModel.load(routeId) }

    // Save-to-Library completion: navigate to the new LOCAL route's preview
    // (from there "MULAI NAVIGASI" works — it's a real GPX-backed route).
    // One-shot consumption, same pattern as the download state below.
    LaunchedEffect(save) {
        val saved = save as? PublicRouteDetailViewModel.SaveState.Saved ?: return@LaunchedEffect
        viewModel.saveConsumed()
        onOpenRoutePreview(saved.localRouteId)
    }

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

                // Kick the elevation-profile load once the route is here —
                // chart streams in when the GPX has been parsed (progressive,
                // detail never blocks on it).
                LaunchedEffect(route.id) { viewModel.loadElevation(route) }

                // Large screens: cap content width and center (Strava caps its
                // detail feed the same way; full-width text rows are unreadable).
                BoxWithConstraints(
                    Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        Modifier
                            .width(maxWidth.coerceAtMost(640.dp))
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // ── Hero: static topo map + trace, full-bleed to the
                        // content column (canvas fallback while loading/offline).
                        StaticMapPreview(
                            polyline = route.trackPolyline,
                            modifier = Modifier.fillMaxWidth().height(220.dp)
                        )

                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(14.dp))

                            // Chips row: sport icon + difficulty + trail shape —
                            // identical chips to the browse cards.
                            val diffChip = route.difficulty
                                ?.let { BrowseRepository.DifficultyFilter.fromWire(it) }
                            val trailChip = route.trailType
                                ?.let { BrowseRepository.TrailTypeFilter.fromWire(it) }
                            if (diffChip != null || trailChip != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = SportType.fromString(route.sportType).icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    diffChip?.let {
                                        DifficultyChip(label = stringResource(it.labelRes), wire = it.wire)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    trailChip?.let {
                                        TrailTypeChip(label = stringResource(it.labelRes))
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            Text(
                                route.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))

                            // Author row: avatar chip + username + likes on the
                            // trailing edge (Strava's byline, Wikiloc's data).
                            val publisher = route.profiles?.username
                            if (publisher != null || route.likesCount > 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    publisher?.let {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.browse_by, it),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (route.likesCount > 0) {
                                        Icon(
                                            Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            route.likesCount.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            route.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(14.dp))
                            } ?: Spacer(Modifier.height(4.dp))

                            // ── Stats grid, Strava-style centered tiles:
                            // 2 per row on phones, 3 on wide screens.
                            val stats = buildList {
                                add("%.1f km".format(route.distanceMeters / 1000.0) to stringResource(R.string.distance))
                                route.elevationGainM?.let { add("+${it.roundToInt()} m" to stringResource(R.string.elev_gain)) }
                                route.movingTimeMs?.takeIf { ms -> ms > 0 }
                                    ?.let { add(formatHms(it) to stringResource(R.string.moving_time)) }
                                route.elevationLossM?.let { add("−${it.roundToInt()} m" to stringResource(R.string.route_detail_elev_loss)) }
                                route.maxElevationM?.let { add("${it.roundToInt()} m" to stringResource(R.string.route_detail_elev_max)) }
                                route.minElevationM?.let { add("${it.roundToInt()} m" to stringResource(R.string.route_detail_elev_min)) }
                            }
                            StatGrid(stats)

                            Spacer(Modifier.height(8.dp))

                            // ── Elevation profile (from the ORIGINAL GPX —
                            // track_polyline has no elevation). Hidden entirely
                            // when unavailable; placeholder while streaming.
                            when (val e = elevation) {
                                PublicRouteDetailViewModel.ElevationState.Loading -> {
                                    Box(
                                        Modifier.fillMaxWidth().height(180.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                is PublicRouteDetailViewModel.ElevationState.Ready -> {
                                    Text(
                                        stringResource(R.string.route_detail_elevation_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    ElevationProfile(
                                        points = e.profile,
                                        modifier = Modifier.fillMaxWidth().height(180.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    // Summary tiles from the parsed GPX — gain/loss
                                    // use the same 2m noise threshold as
                                    // ElevationStats.summarize so the numbers agree
                                    // with the rest of the app.
                                    val elevs = e.profile.map { it.elevationM }
                                    var gain = 0.0
                                    var loss = 0.0
                                    var pending = 0.0
                                    var last = elevs.first()
                                    for (cur in elevs.drop(1)) {
                                        pending += cur - last
                                        if (kotlin.math.abs(pending) >= 2.0) {
                                            if (pending > 0) gain += pending else loss -= pending
                                            pending = 0.0
                                        }
                                        last = cur
                                    }
                                    StatGrid(
                                        listOf(
                                            "+${gain.roundToInt()} m" to stringResource(R.string.elev_gain),
                                            "−${loss.roundToInt()} m" to stringResource(R.string.route_detail_elev_loss),
                                            "${elevs.max().roundToInt()} m" to stringResource(R.string.route_detail_elev_max),
                                            "${elevs.min().roundToInt()} m" to stringResource(R.string.route_detail_elev_min)
                                        )
                                    )
                                }
                                else -> {}
                            }

                            Spacer(Modifier.height(20.dp))

                            // ── Actions — unchanged behavior: Save-to-Library
                            // imports the original GPX locally; Download GPX
                            // shares the lossless file.
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.saveToLibrary(route) },
                                    enabled = save !is PublicRouteDetailViewModel.SaveState.Saving &&
                                        download !is PublicRouteDetailViewModel.DownloadState.InProgress,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (save is PublicRouteDetailViewModel.SaveState.Saving) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.browse_saving), maxLines = 1)
                                    } else {
                                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.browse_save_library), maxLines = 1)
                                    }
                                }
                                Button(
                                    onClick = { viewModel.downloadGpx(route) },
                                    enabled = download !is PublicRouteDetailViewModel.DownloadState.InProgress &&
                                        save !is PublicRouteDetailViewModel.SaveState.Saving,
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
                            val saveError = save as? PublicRouteDetailViewModel.SaveState.Error
                            if (saveError != null) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    stringResource(saveError.messageRes),
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
    }
}

@Composable
private fun StatGrid(stats: List<Pair<String, String>>) {
    if (stats.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp) 3 else 2
        // Column wrapper is REQUIRED here: BoxWithConstraints' content scope is
        // BoxScope — Rows emitted directly into it stack on top of each other
        // (the "stat rows tumpuk" bug), they don't lay out vertically.
        Column {
            stats.chunked(columns).forEach { rowStats ->
                Row(Modifier.fillMaxWidth()) {
                    rowStats.forEach { (value, label) ->
                        StatTile(value, label, Modifier.weight(1f))
                    }
                    repeat(columns - rowStats.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Moving-time label Strava-style: h:mm:ss over an hour, m:ss below. */
private fun formatHms(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val sec = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
