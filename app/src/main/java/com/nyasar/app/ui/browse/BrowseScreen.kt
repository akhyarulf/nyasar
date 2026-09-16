package com.nyasar.app.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.recording.SportType
import com.nyasar.app.ui.components.StaticMapPreview
import com.nyasar.app.ui.components.pressScale
import com.nyasar.app.ui.theme.NyasarElevation
import com.nyasar.app.ui.theme.NyasarRadius
import com.nyasar.app.ui.theme.NyasarSpacing
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
    onOpenRoute: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()

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

            // Filter/sort row: chips open small dropdown menus (same pattern
            // as PublishRouteSheet's FilterChips). Toggling the same value
            // off clears that filter (ViewModel handles the toggle).
            var difficultyMenu by remember { mutableStateOf(false) }
            var trailTypeMenu by remember { mutableStateOf(false) }
            var sortMenu by remember { mutableStateOf(false) }
            val currentSort = viewModel.currentSort()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box {
                    FilterChip(
                        selected = viewModel.currentDifficulty() != null,
                        onClick = { difficultyMenu = true },
                        label = {
                            Text(
                                stringResource(
                                    viewModel.currentDifficulty()?.labelRes
                                        ?: R.string.browse_filter_difficulty
                                ),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    DropdownMenu(expanded = difficultyMenu, onDismissRequest = { difficultyMenu = false }) {
                        BrowseRepository.DifficultyFilter.entries.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(stringResource(f.labelRes)) },
                                onClick = {
                                    viewModel.onDifficultySelected(f)
                                    difficultyMenu = false
                                }
                            )
                        }
                    }
                }
                Box {
                    FilterChip(
                        selected = viewModel.currentTrailType() != null,
                        onClick = { trailTypeMenu = true },
                        label = {
                            Text(
                                stringResource(
                                    viewModel.currentTrailType()?.labelRes
                                        ?: R.string.browse_filter_trail_type
                                ),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    DropdownMenu(expanded = trailTypeMenu, onDismissRequest = { trailTypeMenu = false }) {
                        BrowseRepository.TrailTypeFilter.entries.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(stringResource(f.labelRes)) },
                                onClick = {
                                    viewModel.onTrailTypeSelected(f)
                                    trailTypeMenu = false
                                }
                            )
                        }
                    }
                }
                Box {
                    FilterChip(
                        selected = currentSort != BrowseRepository.SortOrder.NEWEST,
                        onClick = { sortMenu = true },
                        label = { Text(stringResource(currentSort.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
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
                                PublicRouteCard(route = route, onClick = { onOpenRoute(route.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicRouteCard(route: BrowseRepository.PublicRoute, onClick: () -> Unit) {
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
            // Info section keeps the card padding; the hero map below is
            // full-bleed (Wikiloc photo-style), clipped by the Surface shape.
            Column(Modifier.padding(horizontal = NyasarSpacing.lg, vertical = NyasarSpacing.md)) {
            // Top row: sport icon + Wikiloc-style colored difficulty chip +
            // neutral trail-shape chip (all from columns we already store —
            // mountain_name/region are gone, Keputusan baru, migration 0004).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = SportType.fromString(route.sportType).icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.weight(1f))
                route.difficulty?.let { d -> BrowseRepository.DifficultyFilter.fromWire(d) }?.let { chip ->
                    DifficultyChip(label = stringResource(chip.labelRes), wire = chip.wire)
                    Spacer(Modifier.width(NyasarSpacing.sm))
                }
                route.trailType?.let { t -> BrowseRepository.TrailTypeFilter.fromWire(t) }?.let { chip ->
                    TrailTypeChip(label = stringResource(chip.labelRes))
                }
            }
            Spacer(Modifier.height(NyasarSpacing.sm))
            Text(
                route.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(NyasarSpacing.xs))
            // Stats row (Wikiloc order): distance leads, then elevation gain,
            // moving time and likes — only non-null values take space.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%.1f km".format(route.distanceMeters / 1000.0),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                route.elevationGainM?.let {
                    Spacer(Modifier.width(NyasarSpacing.md))
                    Icon(
                        Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "${it.roundToInt()} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                route.movingTimeMs?.takeIf { ms -> ms > 0 }?.let { ms ->
                    Spacer(Modifier.width(NyasarSpacing.md))
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        formatCardDuration(ms),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (route.likesCount > 0) {
                    Spacer(Modifier.width(NyasarSpacing.md))
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        route.likesCount.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
            Spacer(Modifier.height(NyasarSpacing.md))
            // Hero: static topo-tile snapshot with the route trace — the
            // Strava-style visual that replaces the removed photo slot.
            // Degrades to the pure-canvas polyline (old look) while loading
            // or with no signal. Author pill overlays the map, Wikiloc-style.
            Box {
                StaticMapPreview(
                    polyline = route.trackPolyline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
                route.username?.let { author ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(NyasarRadius.pill),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(NyasarSpacing.sm)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(NyasarSpacing.xs))
                            Text(
                                author,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Compact moving-time label for cards — same h/m convention as the
 *  formatDuration helpers in history/navigation screens. */
private fun formatCardDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Wikiloc-style fixed-pastel difficulty badge: same hue per level across
 *  themes/locales so the level reads at a glance while scrolling. */
@Composable
private fun DifficultyChip(label: String, wire: String) {
    val (fg, bg) = when (wire) {
        "easy" -> Color(0xFF1B5E20) to Color(0xFFE3F2E4)
        "moderate" -> Color(0xFF9A6A00) to Color(0xFFFFF1DB)
        "difficult" -> Color(0xFFB3261E) to Color(0xFFFCE8E6)
        else -> Color(0xFF6A1B9A) to Color(0xFFF3E5F5)
    }
    Surface(color = bg, shape = RoundedCornerShape(NyasarRadius.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Neutral chip for the trail shape (loop / out-and-back / point-to-point). */
@Composable
private fun TrailTypeChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(NyasarRadius.xs)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
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
