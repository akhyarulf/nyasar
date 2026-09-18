package com.nyasar.app.ui.browse

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.nyasar.app.ui.components.DifficultyChip
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

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.browse_title)) }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Filter/sort row (Wikiloc concept): a search-weighted Filters
            // chip opens BrowseFilterSheet (draft edits, applied on Apply);
            // sort stays an instant dropdown. The badge shows the APPLIED
            // active count (StateFlow — recomposes on apply/clear) so it
            // stays truthful while sheet edits are still draft.
            var showFilterSheet by rememberSaveable { mutableStateOf(false) }
            val currentSort = viewModel.currentSort()
            val activeFilters by viewModel.appliedCount.collectAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeFilters > 0,
                    onClick = { showFilterSheet = true },
                    label = { Text(stringResource(R.string.browse_filter_filters), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (activeFilters > 0) {
                    Surface(
                        shape = RoundedCornerShape(NyasarRadius.pill),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            activeFilters.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    var sortMenu by remember { mutableStateOf(false) }
                    FilterChip(
                        selected = currentSort != BrowseRepository.SortOrder.NEWEST,
                        onClick = { sortMenu = true },
                        label = { Text(stringResource(currentSort.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
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
            }

            if (showFilterSheet) {
                BrowseFilterSheet(
                    viewModel = viewModel,
                    onDismiss = { showFilterSheet = false }
                )
            }

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
                        Text(
                            stringResource(
                                if (s.fromSearch) R.string.browse_empty_search else R.string.browse_empty_all
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .wrapContentSize(Alignment.Center)
                                .padding(24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
        }
    }
}

@Composable
private fun PublicRouteCard(
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
    Surface(
        shape = RoundedCornerShape(NyasarRadius.md),
        tonalElevation = NyasarElevation.cardTonal,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Column {
            // ── Strava card order: (1) publisher header, (2) route name,
            // (3) description, (4) stats grid, (5) full-bleed map, then the
            // like/comment/share action row. Box kept from the old design.
            //
            // (1) Publisher header: avatar chip + username + age — the exact
            //     anatomy of Strava's byline, over our own visual language.
            Row(
                Modifier.padding(horizontal = NyasarSpacing.lg, vertical = NyasarSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                SportType.fromString(route.sportType).let { sport ->
                    Icon(
                        imageVector = sport.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // (2) Route name + difficulty/trail chips inline (chips stay —
            //     they carry publish-form data the byline has nowhere else
            //     to show).
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
                    route.difficulty?.let { d -> BrowseRepository.DifficultyFilter.fromWire(d) }?.let { chip ->
                        Spacer(Modifier.width(NyasarSpacing.sm))
                        DifficultyChip(label = stringResource(chip.labelRes), wire = chip.wire)
                    }
                    route.trailType?.let { t -> BrowseRepository.TrailTypeFilter.fromWire(t) }?.let { chip ->
                        Spacer(Modifier.width(NyasarSpacing.xs))
                        TrailTypeChip(label = stringResource(chip.labelRes))
                    }
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

                // (4) Stats grid — Strava's "label above, bold value below",
                //     up to 3 tiles on phones: Distance / Elev Gain / Time.
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
                        modifier = Modifier.weight(1f)
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

            // (6) Action row: like / comment / share — Strava's kudos row.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = NyasarSpacing.sm, vertical = NyasarSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (SupabaseClientProvider.client.auth.currentSessionOrNull() != null) {
                            onToggleLike()
                        } else {
                            onRequireSignIn()
                        }
                    },
                    enabled = !likePending
                ) {
                    Icon(
                        imageVector = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (route.likesCount > 0) route.likesCount.toString() else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenComments) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (route.commentsCount > 0) route.commentsCount.toString() else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                // Save (bookmark) — Wikiloc-style private to-do, distinct
                // from the public like. Filled = in the user's list.
                IconButton(
                    onClick = {
                        if (SupabaseClientProvider.client.auth.currentSessionOrNull() != null) {
                            onToggleSave()
                        } else {
                            onRequireSignIn()
                        }
                    },
                    enabled = !savePending
                ) {
                    Icon(
                        imageVector = if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = stringResource(if (saved) R.string.browse_saved else R.string.browse_save),
                        tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.browse_share_route),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** Strava stat tile: small muted label above a bold value. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
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
    val text = "${route.name} — Nyasar\nhttps://nyasar.app/route/${route.id}"
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
        }
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
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
