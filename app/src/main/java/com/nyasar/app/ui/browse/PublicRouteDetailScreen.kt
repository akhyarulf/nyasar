@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.nyasar.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
// Pull-to-refresh (2026-09 fix): material3 1.2.1 (BOM 2024.06) TIDAK punya
// rememberPullToRefreshState/PullToRefreshContainer — itu API M3 1.3+. Versi
// stabil untuk BOM ini ada di compose material M2 (pullRefresh), yang
// tercakup penuh oleh compose-bom 2024.06.
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SocialRepository
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.DifficultyChip
import com.nyasar.app.ui.components.ElevationProfile
import com.nyasar.app.ui.components.NyasarMapView
import com.nyasar.app.ui.components.StaticMapPreview
import com.nyasar.app.ui.components.TrailTypeChip
import com.nyasar.app.ui.theme.NyasarRadius
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Target of a Fase-4 abuse report — exactly one side non-null (schema CHECK). */
private sealed interface ReportTarget {
    data class Route(val routeId: String) : ReportTarget
    data class Comment(val commentId: String) : ReportTarget
}

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
    val saved by viewModel.saved.collectAsState()
    val savePending by viewModel.savePending.collectAsState()
    val context = LocalContext.current

    // Full-screen interactive map (Wikiloc pattern — same as RoutePreview's
    // expand): one flag drives BOTH entry points (tap on the hero preview /
    // the expand button) and the system back gesture. While true, a
    // full-size overlay draws ON TOP of this screen; closing restores the
    // detail exactly as left. Map engine = the real NyasarMapView GL
    // surface (pan/zoom/bearing), not the static snapshot.
    var mapExpanded by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = mapExpanded) { mapExpanded = false }

    // Layer-picker state for the fullscreen map (2026-09 user request):
    // basemap/overlay toggles live in the same app-wide DataStore the other
    // map screens use, so the chosen layers follow the user everywhere.
    val currentBasemap by viewModel.selectedBasemap.collectAsState()
    val currentProvider by viewModel.provider.collectAsState()
    val activeOverlays by viewModel.activeOverlays.collectAsState()
    val myRoutesEnabled by viewModel.myRoutesOverlayEnabled.collectAsState()
    val myRouteLines by viewModel.myRouteLines.collectAsState()
    var showLayerSheet by remember { mutableStateOf(false) }

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

    // Report dialog state (Fase 4): null = closed; non-null = open with the
    // target pair. Route target carries the detail row; comment target the
    // reported comment. Toast feedback via the VM callback.
    var reportTarget by remember { mutableStateOf<ReportTarget?>(null) }
    var reportPending by remember { mutableStateOf(false) }
    val showToast: (Int) -> Unit = { res ->
        android.widget.Toast.makeText(context, context.getString(res), android.widget.Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.route_detail_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Report route — only when a route is actually loaded.
                    val readyForReport = state as? PublicRouteDetailViewModel.State.Ready
                    if (readyForReport != null) {
                        IconButton(onClick = { reportTarget = ReportTarget.Route(readyForReport.route.id) }) {
                            Icon(Icons.Default.Flag, contentDescription = stringResource(R.string.report_action))
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Pull-to-refresh (M2 pullRefresh — lihat catatan import): re-fetch
        // detail tanpa mengosongkan layar — konten Ready tetap tampil, hanya
        // indicator yang berputar. Juga re-sync state like/save dari server.
        // Gesture aktif saat konten Ready (scrollable di posisi atas);
        // Loading/Error tetap pakai spinner / tombol retry masing-masing.
        val isRefreshing = (state as? PublicRouteDetailViewModel.State.Ready)?.isRefreshing == true
        val pullState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = { viewModel.refresh(routeId) }
        )
        Box(Modifier.fillMaxSize().pullRefresh(pullState)) {
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
                        // ── Detail order (request user): map as the full-bleed
                        // hero at the VERY TOP, then publisher header, name/
                        // description/stats, like/comment action row,
                        // elevation, actions.

                        // (1) Map — full-bleed hero (tile map, not a bare
                        // polyline; fallback chain in StaticMapPreview).
                        // Tap ANYWHERE on it opens the full-screen interactive
                        // map (Wikiloc pattern), plus the explicit expand
                        // button floating bottom-right for discoverability.
                        Box {
                            StaticMapPreview(
                                polyline = route.trackPolyline,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .clickable { mapExpanded = true }
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 3.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp)
                                    .size(44.dp)
                            ) {
                                IconButton(onClick = { mapExpanded = true }) {
                                    Icon(
                                        Icons.Default.OpenInFull,
                                        contentDescription = stringResource(R.string.map_expand_cd),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // (2) Publisher header.
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

                            // (2b) Visibility row — ONLY on one's own route:
                            // Wikiloc-style Everyone⇄Only-you switch. RLS hides
                            // private rows from everyone but the owner, so a
                            // viewer never sees this row at all on their routes.
                            val myUserId = com.nyasar.app.data.supabase.SupabaseClientProvider
                                .client.auth.currentUserOrNull()?.id
                            if (myUserId != null && route.userId == myUserId) {
                                val visibilityPending by viewModel.visibilityPending.collectAsState()
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (route.isPublic) Icons.Default.Public else Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                stringResource(
                                                    if (route.isPublic) R.string.route_visibility_public
                                                    else R.string.route_visibility_private
                                                ),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                stringResource(
                                                    if (route.isPublic) R.string.route_visibility_public_hint
                                                    else R.string.route_visibility_private_hint
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = route.isPublic,
                                            enabled = !visibilityPending,
                                            onCheckedChange = { checked ->
                                                viewModel.setVisibility(route, checked)
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }

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

                        // (4) Like / comment action row (Strava kudos row).
                        val signedIn = com.nyasar.app.data.supabase.SupabaseClientProvider
                            .client.auth.currentSessionOrNull() != null
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { if (signedIn) viewModel.toggleLike(route) else onRequireSignIn() },
                                enabled = !likePending
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
                            // Save (bookmark) — private Wikiloc-style list,
                            // next to the public like (Strava shows both too).
                            IconButton(
                                onClick = { if (signedIn) viewModel.toggleSave(route.id) else onRequireSignIn() },
                                enabled = !savePending
                            ) {
                                Icon(
                                    imageVector = if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = stringResource(
                                        if (saved) R.string.browse_saved else R.string.browse_save
                                    ),
                                    tint = if (saved) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                                // Own comment → delete; others' → report.
                                                val myUserId = com.nyasar.app.data.supabase.SupabaseClientProvider
                                                    .client.auth.currentUserOrNull()?.id
                                                if (c.userId == myUserId) {
                                                    var confirmDelete by remember(c.id) { mutableStateOf(false) }
                                                    IconButton(onClick = { confirmDelete = true }) {
                                                        Icon(
                                                            Icons.Default.MoreVert,
                                                            contentDescription = stringResource(R.string.comment_delete_cd),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    if (confirmDelete) {
                                                        AlertDialog(
                                                            onDismissRequest = { confirmDelete = false },
                                                            title = { Text(stringResource(R.string.comment_delete_confirm_title)) },
                                                            text = { Text(stringResource(R.string.comment_delete_confirm_body)) },
                                                            confirmButton = {
                                                                TextButton(onClick = {
                                                                    confirmDelete = false
                                                                    viewModel.deleteComment(c)
                                                                }) { Text(stringResource(R.string.delete)) }
                                                            },
                                                            dismissButton = {
                                                                TextButton(onClick = { confirmDelete = false }) {
                                                                    Text(stringResource(R.string.cancel))
                                                                }
                                                            }
                                                        )
                                                    }
                                                } else {
                                                    IconButton(onClick = { reportTarget = ReportTarget.Comment(c.id) }) {
                                                        Icon(
                                                            Icons.Default.Flag,
                                                            contentDescription = stringResource(R.string.comment_report_cd),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
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
            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        } // pull-refresh Box
    }

    // ==================== FULL-SCREEN INTERACTIVE MAP OVERLAY ====================
    // Real NyasarMapView GL surface (pan/zoom/rotate) covering the whole
    // screen — the Wikiloc-style expanded map for browse routes. Uses the
    // lossy track_polyline (browse routes have no local GPX until saved),
    // fits the track bounds, and a single back/collapse button. Waypoint
    // pins need the original GPX which browse rows don't carry — omitted
    // here by design (they appear after Save-to-Library, in Route Preview).
    if (mapExpanded) {
        val ready = state as? PublicRouteDetailViewModel.State.Ready
        if (ready != null) {
        // Zoom-out ekstra merata (72dp, pola RoutePreview) supaya ujung atas
        // track tidak tersembunyi di bawah pill nama rute + tombol back.
        val fitPaddingPx = with(LocalDensity.current) { 72.dp.toPx() }.toInt()
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            NyasarMapView(
                modifier = Modifier.fillMaxSize(),
                fitBoundsPaddingPx = fitPaddingPx,
                provider = currentProvider,
                basemapEntry = currentBasemap,
                shared = false,
                activeOverlays = activeOverlays,
                myRoutes = myRouteLines,
                track = ready.track,
                trackColorOverride = "#42A5F5"
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(onClick = { mapExpanded = false }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                // Layer picker — same button + sheet as RoutePreview's
                // fullscreen map; selection persists app-wide.
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(onClick = { showLayerSheet = true }) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = stringResource(R.string.map_layer_cd),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            // Route name pill top-center — orientation context over the map.
            Surface(
                shape = RoundedCornerShape(NyasarRadius.pill),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                tonalElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .widthIn(max = maxWidth - 140.dp)
            ) {
                Text(
                    ready.route.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        }
    }

    // Layer picker sheet (2026-09 user request) — same component and same
    // persisted toggles as every other map screen. Waymarked overlay + Data
    // toggles all apply; "Jalur Saya" renders the saved Library routes with
    // none accented (the cloud route has no local id — see the VM).
    if (showLayerSheet) {
        com.nyasar.app.ui.components.BasemapPickerSheet(
            selected = currentBasemap,
            onSelect = { entry ->
                viewModel.setBasemap(entry)
                showLayerSheet = false
            },
            activeOverlays = activeOverlays,
            onToggleOverlay = { overlay -> viewModel.toggleOverlay(overlay) },
            myRoutesEnabled = myRoutesEnabled,
            onToggleMyRoutes = { viewModel.setMyRoutesOverlayEnabled(!myRoutesEnabled) },
            // Waypoint pins + downloaded-areas coverage need the local GPX /
            // per-style coverage data this screen doesn't carry — keep both
            // tiles hidden instead of dead toggles (same reasoning DrawRoute
            // documents for hiding them).
            showWaypointsToggle = false,
            showOfflineAreasToggle = false,
            onDismiss = { showLayerSheet = false }
        )
    }

    // Report dialog (Fase 4) — route target from the top-bar flag, comment
    // target from the per-comment flag. VM callback closes + toasts.
    reportTarget?.let { target ->
        val (titleRes, routeId, commentId) = when (target) {
            is ReportTarget.Route -> Triple(R.string.report_route_title, target.routeId, null)
            is ReportTarget.Comment -> Triple(R.string.report_comment_title, null, target.commentId)
        }
        ReportDialog(
            titleRes = titleRes,
            pending = reportPending,
            onSubmit = { reason, note ->
                reportPending = true
                viewModel.submitReport(routeId, commentId, reason, note) { ok ->
                    reportPending = false
                    reportTarget = null
                    showToast(if (ok) R.string.report_submitted else R.string.report_failed)
                }
            },
            onDismiss = { if (!reportPending) reportTarget = null }
        )
    }
}

/**
 * Abuse-report dialog (Fase 4 — schema_v1 `reports`): radio choice of the
 * five schema reasons + optional note. Insert-only server-side; the user
 * never sees report status again (fire-and-forget with a toast).
 */
@Composable
private fun ReportDialog(
    titleRes: Int,
    pending: Boolean,
    onSubmit: (SocialRepository.ReportReason, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(SocialRepository.ReportReason.SPAM) }
    var note by remember { mutableStateOf("") }
    val reasonLabel: (SocialRepository.ReportReason) -> Int = {
        when (it) {
            SocialRepository.ReportReason.SPAM -> R.string.report_reason_spam
            SocialRepository.ReportReason.MISLEADING -> R.string.report_reason_misleading
            SocialRepository.ReportReason.OFFENSIVE -> R.string.report_reason_offensive
            SocialRepository.ReportReason.DANGER -> R.string.report_reason_danger
            SocialRepository.ReportReason.OTHER -> R.string.report_reason_other
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                SocialRepository.ReportReason.entries.forEach { reason ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = reason },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == reason,
                            onClick = { selected = reason },
                            enabled = !pending
                        )
                        Text(stringResource(reasonLabel(reason)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text(stringResource(R.string.report_note_hint)) },
                    enabled = !pending,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            if (pending) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { onSubmit(selected, note.trim().takeIf { it.isNotEmpty() }) }) {
                    Text(stringResource(R.string.report_submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !pending) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
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
