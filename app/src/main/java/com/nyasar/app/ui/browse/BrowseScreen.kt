@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.nyasar.app.ui.browse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
// Pull-to-refresh (2026-09 fix): material3 1.2.1 (BOM 2024.06) TIDAK punya
// rememberPullToRefreshState/PullToRefreshContainer — itu API M3 1.3+. Versi
// stabil untuk BOM ini ada di compose material M2 (pullRefresh), yang
// tercakup penuh oleh compose-bom 2024.06.
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.AuthorAvatar
import com.nyasar.app.ui.components.DifficultyChip
import com.nyasar.app.ui.components.EmptyState
import com.nyasar.app.ui.components.NyasarTabHeader
import com.nyasar.app.ui.components.StaticMapPreview
import com.nyasar.app.ui.components.TrailTypeChip
import com.nyasar.app.ui.components.pressScale
import com.nyasar.app.ui.theme.NyasarElevation
import com.nyasar.app.ui.theme.NyasarRadius
import com.nyasar.app.ui.theme.NyasarSpacing
import android.content.Intent
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * "Jelajah / Explore" — the new Home tab (Fase 2 poin 3, IA rework 2026):
 * browse + search + filter + sort of PUBLIC routes (Fase 2). Read-only over
 * [BrowseRepository]; opening a route goes to [PublicRouteDetailScreen].
 *
 * One trailing detail on purpose: no onBack. This is a root tab — the
 * back gesture must exit the app, not pop the tab.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel = viewModel(),
    onOpenRoute: (String) -> Unit,
    onRequireSignIn: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val likePending by viewModel.likePending.collectAsState()
    val savedIds by viewModel.savedIds.collectAsState()
    val savePending by viewModel.savePending.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Filter + sort live in the header as icon-only actions (reference feed
    // anatomy: a title with a row of glyphs, no control bar eating the first
    // 60dp of the list). Filter is green only while it is applied — colour as
    // state, not decoration. Hoisted here because both the header and the
    // sheet need them.
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    val activeFilters by viewModel.appliedCount.collectAsState()
    val currentSort = viewModel.currentSort()

    Scaffold(
        topBar = {
            NyasarTabHeader(
                // Brand-first header, like the reference feed: the bottom bar
                // already labels this tab, so repeating "Jelajah Rute" up here
                // only competed with the logo chip. The wordmark makes the top
                // of the app read as the product, not as a page title.
                title = stringResource(R.string.app_name),
                // Subtitle dihapus (feedback user 2026-09): tagline tidak
                // menambah informasi, header cukup judul + aksi saja.
                actions = {
                    var sortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = stringResource(R.string.browse_sort_cd),
                                // Same rule as the filter glyph: green means
                                // "not the default state", so an active sort
                                // is visible without a label.
                                tint = if (currentSort != BrowseRepository.SortOrder.NEWEST)
                                           MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            BrowseRepository.SortOrder.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(s.labelRes)) },
                                    onClick = {
                                        viewModel.onSortSelected(s)
                                        sortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = stringResource(R.string.history_filter_button),
                            tint = if (activeFilters > 0) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // One control under the header: the search field, full width.
            // Filter and sort moved into the header as icon-only actions, so
            // the first route starts ~60dp higher than it used to and the
            // feed reads as content instead of a control panel.
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = { Text(stringResource(R.string.browse_search_hint), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChanged("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.browse_clear_search)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(NyasarRadius.md),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            )

            // Summoned from the header's filter glyph. Same draft semantics as
            // before: edits apply on "Terapkan", and the header glyph turns
            // green from the APPLIED count (StateFlow), so it never claims a
            // filter the list isn't actually using.
            if (showFilterSheet) {
                BrowseFilterSheet(
                    viewModel = viewModel,
                    onDismiss = { showFilterSheet = false }
                )
            }

            // Pull-to-refresh (M2 pullRefresh — lihat catatan import):
            // tarik ke bawah re-browse tanpa mengosongkan layar — list tetap
            // terlihat, hanya indicator yang muncul. Gesture hanya aktif saat
            // konten scrollable (LazyColumn) di posisi paling atas; Loading/
            // Error/empty tidak scrollable → tidak bisa ditarik (retry tetap
            // via tombol masing-masing).
            val isRefreshing = (state as? BrowseViewModel.BrowseState.Loaded)?.isRefreshing == true
            val pullState = rememberPullRefreshState(
                refreshing = isRefreshing,
                onRefresh = { viewModel.refresh() }
            )
            Box(Modifier.fillMaxSize().pullRefresh(pullState)) {
                when (val s = state) {
                BrowseViewModel.BrowseState.Idle, BrowseViewModel.BrowseState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is BrowseViewModel.BrowseState.Error -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            stringResource(s.error.messageRes),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = viewModel::retry) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                is BrowseViewModel.BrowseState.Loaded -> {
                    if (s.routes.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Explore,
                            title = stringResource(
                                if (s.fromSearch) R.string.browse_empty_search else R.string.browse_empty_all
                            ),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            // Full-bleed feed rows: the map and the hairlines
                            // run edge to edge, so the list itself carries no
                            // side padding — only a bottom gap.
                            contentPadding = PaddingValues(bottom = 16.dp),
                            // Filter chips row scrolls its own context; list
                            // items animate in with the shared stagger.
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(s.routes, key = { it.id }) { route ->
                                PublicRouteCard(
                                    route = route,
                    liked = route.id in likedIds,
                                    likePending = route.id in likePending,
                                    onToggleLike = { viewModel.toggleLike(route) },
                                    saved = route.id in savedIds,
                                    savePending = route.id in savePending,
                                    onToggleSave = { viewModel.toggleSave(route) },
                                    onOpenComments = { onOpenRoute(route.id) },
                                    onRequireSignIn = onRequireSignIn,
                                    onShare = { shareRoute(context, route) },
                                    onClick = { onOpenRoute(route.id) }
                                )
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
    }
}


@Composable
/** Shared with the Profile → Saved tab (same card everywhere a bookmarked
 *  route appears). internal, not private, for exactly that cross-package use. */
internal fun PublicRouteCard(
    route: BrowseRepository.PublicRoute,
    liked: Boolean,
    likePending: Boolean,
    onToggleLike: () -> Unit,
    saved: Boolean,
    savePending: Boolean,
    onToggleSave: () -> Unit,
    onOpenComments: () -> Unit,
    onRequireSignIn: () -> Unit,
    onShare: () -> Unit,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    // Feed row, not a card: hairline on top, full-bleed map, no rounded inset
    // frame. The rounded card with 16dp margins made every route look like an
    // equal-weight widget; a feed entry earns its emphasis from the map and
    // the numbers, not from a container.
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column {
            // ── Reference-feed order: (1) publisher byline, (2) route name,
            // (3) description, (4) stat row, (5) full-bleed map, then the
            // like/comment/save/share action row.
            //
            // (1) Publisher header: monogram + username + date — the exact
            //     anatomy of the reference byline, over our own visual language.
            Row(
                Modifier.padding(horizontal = NyasarSpacing.lg, vertical = NyasarSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Identity at the top of the row, same monogram treatment as
                // the Profile header: green-tinted circle + the author's
                // initial. The grey square with a generic person glyph this
                // replaces was indistinguishable from a broken-image state
                // once two cards from the same author stacked up.
                AuthorAvatar(name = route.username)
                Spacer(Modifier.width(NyasarSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        route.username ?: stringResource(R.string.browse_unknown_author),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatRelativeDate(route.createdAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                // End of the byline: difficulty/trail badges first (moved up
                // here from the name row — short pastel chips read better on
                // the byline line and long route names no longer push them
                // off a single line), then the sport icon. When neither
                // badge exists, the sport icon stands alone as before.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NyasarSpacing.xs)
                ) {
                    route.difficulty?.let { d -> BrowseRepository.DifficultyFilter.fromWire(d) }?.let { chip ->
                        DifficultyChip(label = stringResource(chip.labelRes), wire = chip.wire)
                    }
                    route.trailType?.let { t -> BrowseRepository.TrailTypeFilter.fromWire(t) }?.let { chip ->
                        TrailTypeChip(label = stringResource(chip.labelRes))
                    }
                    // Sport icon only when the publisher actually picked a
                    // sport — the old render drew HelpOutline ("?") for the
                    // UNSPECIFIED default, which read as a broken/unexplained
                    // glyph on every fresh card (user report).
                    SportType.fromString(route.sportType).takeIf { it != SportType.UNSPECIFIED }?.let { sport ->
                        Icon(
                            imageVector = sport.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // (2) Route name (full width — difficulty/trail badges live in
            //     the byline row above now, so long names get the whole line
            //     instead of squeezing chips off-screen).
            Column(Modifier.padding(horizontal = NyasarSpacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        route.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                // (3) Description (Strava shows it right under the name).
                route.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(Modifier.height(NyasarSpacing.xs))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

            // (4) Stat row — reference feed anatomy: small quiet label above,
            //     numbers much larger and heavier below (bodyMedium /
            //     headlineSmall). The old labelMedium/titleMedium pair made
            //     every number read like body copy, so the card had no focal
            //     point. Third block sits flush right, which is what gives the
            //     row its asymmetric rhythm instead of three equal weights.
                Spacer(Modifier.height(NyasarSpacing.md))
                Row(Modifier.fillMaxWidth()) {
                    StatTile(
                        label = stringResource(R.string.stat_distance),
                        value = "%.1f km".format(route.distanceMeters / 1000.0),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.browse_stat_elev_gain),
                        value = route.elevationGainM?.let { "+${it.roundToInt()} m" } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.stat_moving_time),
                        value = route.movingTimeMs?.takeIf { ms -> ms > 0 }
                            ?.let { ms -> formatCardDuration(ms) } ?: "—",
                        modifier = Modifier.weight(1f),
                        alignEnd = true
                    )
                }
            }

            Spacer(Modifier.height(NyasarSpacing.md))

            // (5) Hero map — full-bleed, taller (Strava's ~4:3 card map).
            //     Tile sources: MapTiler topo when keyed, then OSM standard,
            //     then OpenTopoMap (see StaticMapPreview fallback chain).
            Box {
                StaticMapPreview(
                    polyline = route.trackPolyline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                )
            }

            // (6) Action row — hairline, then four glyphs evenly spread
            //     across the full width at full-strength onSurface. The old
            //     row huddled 20dp muted icons inside text buttons, so the
            //     footer read as a toolbar instead of a feed footer. Counts
            //     stay beside their glyph so the number is still visible.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeedActionSlot(
                    icon = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(if (liked) R.string.browse_liked else R.string.browse_like),
                    onClick = {
                        if (SupabaseClientProvider.client.auth.currentSessionOrNull() != null) {
                            onToggleLike()
                        } else {
                            onRequireSignIn()
                        }
                    },
                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    label = route.likesCount.takeIf { it > 0 }?.toString(),
                    enabled = !likePending,
                    modifier = Modifier.weight(1f)
                )
                FeedActionSlot(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = stringResource(R.string.browse_comment),
                    onClick = onOpenComments,
                    label = route.commentsCount.takeIf { it > 0 }?.toString(),
                    modifier = Modifier.weight(1f)
                )
                // Save (bookmark) — Wikiloc-style private to-do, distinct
                // from the public like. Filled = in the user's list.
                FeedActionSlot(
                    icon = if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = stringResource(if (saved) R.string.browse_saved else R.string.browse_save),
                    onClick = {
                        if (SupabaseClientProvider.client.auth.currentSessionOrNull() != null) {
                            onToggleSave()
                        } else {
                            onRequireSignIn()
                        }
                    },
                    tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    enabled = !savePending,
                    modifier = Modifier.weight(1f)
                )
                FeedActionSlot(
                    icon = Icons.Default.Share,
                    contentDescription = stringResource(R.string.browse_share_route),
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Feed stat block: quiet label on top, number much larger and heavier below.
 * [alignEnd] parks the last block flush with the right text margin — that
 * asymmetry, not three equal columns, is what makes a stat row look designed.
 */
@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
) {
    Column(
        modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End
                        else androidx.compose.ui.text.style.TextAlign.Start
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One slot of the feed footer: 48dp tall, glyph centred inside its share of
 *  the width, count beside it when there is one. */
@Composable
private fun FeedActionSlot(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    label: String? = null,
    enabled: Boolean = true
) {
    Box(modifier.height(48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = tint
            )
            if (label != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Strava-style relative timestamp for the card byline ("3 j"). Falls back
 *  to the date when older than a week. */
internal fun formatRelativeDate(iso: String): String {
    val millis = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { setTimeZone(java.util.TimeZone.getTimeZone("UTC")) }
            .parse(iso.take(19))
            ?.time
    }.getOrNull() ?: return iso.take(10)
    val diffMin = ((System.currentTimeMillis() - millis) / 60000L).coerceAtLeast(0)
    return when {
        diffMin < 1 -> "now"
        diffMin < 60 -> "${diffMin}m"
        diffMin < 60 * 24 -> "${diffMin / 60}h"
        diffMin < 60 * 24 * 7 -> "${diffMin / (60 * 24)}d"
        else -> iso.take(10)
    }
}

/** System share sheet for a route — plain text deep link style; no server
 *  involvement. Uses the same chooser mechanism as the GPX share. */
internal fun shareRoute(context: android.content.Context, route: BrowseRepository.PublicRoute) {
    val text = "${route.name} — Nyasar\n${com.nyasar.app.AppLinks.routeLink(route.id)}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, route.name))
}

/** Compact moving-time label for cards — same h/m convention as the
 *  formatDuration helpers in history/navigation screens. */
private fun formatCardDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Equirectangular mini track sketch (aspect-correct via cos(midLat)), used
 *  by browse cards AND the detail screen's preview. Pure function of the
 *  polyline — no map tiles, no network, safe to draw during scroll. */
fun trackPath(points: List<com.nyasar.app.gpx.model.TrackPoint>, width: Float, height: Float): Path {
    val path = Path()
    if (points.size < 2 || width <= 0f || height <= 0f) return path
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val latSpan = (maxLat - minLat).takeIf { it > 1e-9 } ?: return path
    val lonSpan = (maxLon - minLon).takeIf { it > 1e-9 } ?: return path
    val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
    // Project lon with the cos(midLat) equirectangular factor so east-west
    // distances aren't stretched, then fit the bounding box with padding.
    val xSpan = lonSpan * cos(midLatRad)
    val scale = minOf(width * 0.86f / xSpan.toFloat(), height * 0.86f / latSpan.toFloat())
    val xMid = (minLon + maxLon) / 2.0
    val yMid = (minLat + maxLat) / 2.0
    points.forEachIndexed { i, p ->
        val x = (width / 2f + ((p.lon - xMid) * cos(midLatRad) * scale).toFloat())
        val y = (height / 2f - ((p.lat - yMid) * scale).toFloat())
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

@Composable
fun MiniTrackPreview(polyline: String, modifier: Modifier = Modifier) {
    val points = remember(polyline) { BrowseRepository.decodeTrack(polyline) }
    val lineColor = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val path = trackPath(points, size.width, size.height)
        drawPath(path, lineColor, style = Stroke(width = 3f))
    }
}

/**
 * Wikiloc-style Filters bottom sheet (konsep user): sport-type chips,
 * Distance & Elevation-Gain range sliders, multi-select difficulty buttons,
 * and a Loop-trails-only switch — all edited as a DRAFT that only reaches
 * the server when Apply is pressed. Swipe-dismiss with pending edits
 * commits them too (user intent — silently dropping them feels like a
 * lost Apply); back/X dismiss keeps the draft for the next open.
 *
 * Wikiloc's PREMIUM rows ("Only authors you follow", "Recorded") are
 * deliberately NOT reproduced — see the filter draft decision in
 * PROJECT_CONTEXT.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseFilterSheet(
    viewModel: BrowseViewModel,
    onDismiss: () -> Unit
) {
    val draft by viewModel.draftFilters.collectAsState()

    ModalBottomSheet(
        onDismissRequest = {
            if (viewModel.isDirty(draft)) viewModel.applyFilters()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.browse_filter_sport),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(NyasarSpacing.sm))
            // Reuse SportType labels/icons — the exact vocabulary routes are
            // published with (the schema CHECK copies these enum names).
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SportType.entries.forEach { sport ->
                    FilterChip(
                        selected = sport in draft.sportTypes,
                        onClick = {
                            viewModel.onDraftChanged(
                                draft.copy(
                                    sportTypes = if (sport in draft.sportTypes) draft.sportTypes - sport
                                    else draft.sportTypes + sport
                                )
                            )
                        },
                        label = { Text(stringResource(sport.labelRes), maxLines = 1) },
                        leadingIcon = {
                            Icon(sport.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
            Spacer(Modifier.height(NyasarSpacing.md))
            RangeSliderItem(
                title = stringResource(R.string.browse_filter_distance),
                unit = "km",
                value = draft.distanceMinKm..draft.distanceMaxKm,
                max = BrowseRepository.BrowseFilters.MAX_DISTANCE_KM,
                step = 1f,
                onValueChange = {
                    viewModel.onDraftChanged(
                        draft.copy(distanceMinKm = it.start, distanceMaxKm = it.endInclusive)
                    )
                }
            )
            Spacer(Modifier.height(NyasarSpacing.md))
            RangeSliderItem(
                title = stringResource(R.string.browse_filter_gain),
                unit = "m",
                value = draft.gainMinM..draft.gainMaxM,
                max = BrowseRepository.BrowseFilters.MAX_GAIN_M,
                step = 50f,
                onValueChange = {
                    viewModel.onDraftChanged(
                        draft.copy(gainMinM = it.start, gainMaxM = it.endInclusive)
                    )
                }
            )
            Spacer(Modifier.height(NyasarSpacing.md))
            Text(
                stringResource(R.string.browse_filter_difficulty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(NyasarSpacing.sm))
            // Multi-select buttons (Wikiloc) — wire values are the schema's
            // CHECK set; labels reused from the publish form.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrowseRepository.DifficultyFilter.entries.forEach { d ->
                    FilterChip(
                        selected = d in draft.difficulties,
                        onClick = {
                            viewModel.onDraftChanged(
                                draft.copy(
                                    difficulties = if (d in draft.difficulties) draft.difficulties - d
                                    else draft.difficulties + d
                                )
                            )
                        },
                        label = { Text(stringResource(d.labelRes), maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(NyasarSpacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.browse_filter_loop_only),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = draft.loopOnly,
                    onCheckedChange = { viewModel.onDraftChanged(draft.copy(loopOnly = it)) }
                )
            }
            Spacer(Modifier.height(NyasarSpacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.clearAllFilters() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.browse_filter_clear_all), maxLines = 1)
                }
                Button(
                    onClick = {
                        viewModel.applyFilters()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.browse_filter_apply), maxLines = 1)
                }
            }
        }
    }
}

/** Labeled float range slider with Wikiloc's "0 km … +200 km" end labels —
 *  the upper label gains its "+" exactly at the ceiling because that
 *  position is the OPEN bound (no upper filter is sent from there). */
@Composable
private fun RangeSliderItem(
    title: String,
    unit: String,
    value: ClosedFloatingPointRange<Float>,
    max: Float,
    step: Float,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                "%,d %s".format(value.start.roundToInt(), unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            val plus = if (value.endInclusive >= max - step / 2) "+" else ""
            Text(
                "%s%,d %s".format(plus, value.endInclusive.roundToInt(), unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RangeSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..max,
            steps = (max / step).toInt() - 1
        )
    }
}
