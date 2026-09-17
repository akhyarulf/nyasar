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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
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
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.DifficultyChip
import com.nyasar.app.ui.components.ElevationProfile
import com.nyasar.app.ui.components.StaticMapPreview
import com.nyasar.app.ui.components.TrailTypeChip
import com.nyasar.app.ui.theme.NyasarRadius
import io.github.jan.supabase.gotrue.auth
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
    onRequireSignIn: () -> Unit = {},
    onShare: () -> Unit = {},
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val download by viewModel.download.collectAsState()
    val save by viewModel.save.collectAsState()
    val elevation by viewModel.elevation.collectAsState()
    val liked by viewModel.liked.collectAsState()
    val likePending by viewModel.likePending.collectAsState()
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
                LaunchedEffect(route.id) {
                    viewModel.loadElevation(route)
                    viewModel.loadSocial(route.id)
                }

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
                        // ── Strava detail order: publisher header FIRST (above
                        // the map), then name/description/stats, then the map,
                        // then the like/comment action row, elevation, actions.

                        // (1) Publisher header.
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(23.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    route.profiles?.username
                                        ?: stringResource(R.string.browse_unknown_author),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    com.nyasar.app.ui.browse.formatRelativeDate(route.createdAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val diffChip = route.difficulty
                                ?.let { BrowseRepository.DifficultyFilter.fromWire(it) }
                            val trailChip = route.trailType
                                ?.let { BrowseRepository.TrailTypeFilter.fromWire(it) }
                            diffChip?.let {
                                DifficultyChip(label = stringResource(it.labelRes), wire = it.wire)
                                Spacer(Modifier.width(8.dp))
                            }
                            trailChip?.let {
                                TrailTypeChip(label = stringResource(it.labelRes))
                            }
                        }

                        // (2) Name + description.
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                route.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            route.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(12.dp))

                            // (3) Stats — Strava row format (label above, bold
                            // value below): Distance / Elev Gain / Time.
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.browse_stat_distance),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "%.1f km".format(route.distanceMeters / 1000.0),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.browse_stat_elev_gain),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        route.elevationGainM?.let { "+${it.roundToInt()} m" } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.browse_stat_time),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        route.movingTimeMs?.takeIf { ms -> ms > 0 }
                                            ?.let { formatHms(it) } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // (4) Map — full-bleed hero (tile map, not a bare
                        // polyline; fallback chain in StaticMapPreview).
                        StaticMapPreview(
                            polyline = route.trackPolyline,
                            modifier = Modifier.fillMaxWidth().height(240.dp)
                        )

                        // (5) Like / comment action row (Strava kudos row).
                        val signedIn = com.nyasar.app.data.supabase.SupabaseClientProvider
                            .client.auth.currentSessionOrNull() != null
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { if (signedIn) viewModel.toggleLike(route) else onRequireSignIn() },
                                enabled = likePending
                            ) {
                                Icon(
                                    imageVector = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = stringResource(
                                        if (liked) R.string.browse_liked else R.string.browse_like
                                    ),
                                    tint = if (liked) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (route.likesCount > 0) route.likesCount.toString() else "",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { /* comments section is right below */ }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Chat,
                                    contentDescription = stringResource(R.string.browse_comment),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                if (route.commentsCount > 0) route.commentsCount.toString() else "",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onShare) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = stringResource(R.string.browse_share_route),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(12.dp))

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

                            // ── Comments section (Strava-style thread).
                            Spacer(Modifier.height(20.dp))
                            val commentsState by viewModel.comments.collectAsState()
                            when (val cs = commentsState) {
                                is PublicRouteDetailViewModel.CommentsState.Ready -> {
                                    Text(
                                        stringResource(R.string.route_detail_comments_section, cs.comments.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    if (cs.comments.isEmpty()) {
                                        Text(
                                            stringResource(R.string.browse_comments_empty),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        cs.comments.forEach { c ->
                                            Row(Modifier.padding(vertical = 6.dp)) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            Icons.Default.Person,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(17.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            c.username ?: stringResource(R.string.browse_unknown_author),
                                                            style = MaterialTheme.typography.labelLarge,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            com.nyasar.app.ui.browse.formatRelativeDate(c.createdAt),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Text(
                                                        c.content,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    val signedInForComment = com.nyasar.app.data.supabase.SupabaseClientProvider
                                        .client.auth.currentSessionOrNull() != null
                                    var commentText by remember { mutableStateOf("") }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = commentText,
                                            onValueChange = { commentText = it },
                                            placeholder = { Text(stringResource(R.string.browse_comment_hint), maxLines = 1) },
                                            enabled = signedInForComment && !cs.posting,
                                            singleLine = true,
                                            shape = RoundedCornerShape(NyasarRadius.pill),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        if (cs.posting) {
                                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                        } else {
                                            IconButton(
                                                onClick = {
                                                    if (signedInForComment) {
                                                        viewModel.postComment(route.id, commentText)
                                                        commentText = ""
                                                    } else {
                                                        onRequireSignIn()
                                                    }
                                                },
                                                enabled = commentText.isNotBlank()
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.Send,
                                                    contentDescription = stringResource(R.string.browse_comment_send),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
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
