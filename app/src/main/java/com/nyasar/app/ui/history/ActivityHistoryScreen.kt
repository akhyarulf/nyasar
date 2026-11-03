package com.nyasar.app.ui.history

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.nyasar.app.recording.ShareMetric
import com.nyasar.app.recording.SportType
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.map.MapSnapshotHelper
import com.nyasar.app.ui.map.MapSnapshotResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import com.nyasar.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nyasar.app.ui.components.EmptyState
import com.nyasar.app.ui.components.pressScale
import com.nyasar.app.ui.theme.NyasarRadius

/**
 * Card-per-activity layout (spec ref: Strava's Activities feed) — was a
 * flat single-line ListItem before, which read as "kesederhana banget"
 * next to the reference. Each card shows the same stats as before
 * (distance/elevation gain/time) laid out like the reference's 3-column
 * row, plus a drawn route-shape thumbnail where the old row had nothing
 * visual at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityHistoryScreen(
    viewModel: ActivityHistoryViewModel = viewModel(),
    onOpenActivity: (String) -> Unit,
    onShareActivity: (String) -> Unit,
    onShareGpx: (String) -> Unit,
    onBack: () -> Unit
) {
    // Standalone wrapper (kept for the "profile?tab=history" nested entry,
    // which needs its own back arrow): same content, own Scaffold header.
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            ActivityHistoryContent(
                viewModel = viewModel,
                onOpenActivity = onOpenActivity,
                onShareActivity = onShareActivity,
                onShareGpx = onShareGpx,
                emptyCta = onBack
            )
        }
    }
}

/**
 * History content without its own Scaffold/TopAppBar — embedded directly by
 * ProfileScreen's History sub-tab (IA rework 2026). [emptyCta] is the action
 * for the empty-state button (standalone: pop back; embedded: go to Record).
 */
@Composable
fun ActivityHistoryEmbedded(
    viewModel: ActivityHistoryViewModel = viewModel(),
    onOpenActivity: (String) -> Unit,
    onShareActivity: (String) -> Unit,
    onShareGpx: (String) -> Unit,
    emptyCta: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        ActivityHistoryContent(
            viewModel = viewModel,
            onOpenActivity = onOpenActivity,
            onShareActivity = onShareActivity,
            onShareGpx = onShareGpx,
            emptyCta = emptyCta
        )
    }
}

@Composable
private fun ActivityHistoryContent(
    viewModel: ActivityHistoryViewModel,
    onOpenActivity: (String) -> Unit,
    onShareActivity: (String) -> Unit,
    onShareGpx: (String) -> Unit,
    emptyCta: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val monthFilter by viewModel.monthFilter.collectAsState()

    Box(Modifier.fillMaxSize()) {
        when (state.loadState) {
                HistoryLoadState.LOADING -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                HistoryLoadState.ERROR -> {
                    Text(
                        stringResource(R.string.history_load_error),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                HistoryLoadState.LOADED -> {
                    // v0.9.3: search + sort bar, filter bar (sport + bulan
                    // chips, AND-combined) and a summary strip always sit
                    // between the header and the list — they render as
                    // nothing (no rows to advertise) until the data has
                    // something to filter/sort/summarize, so a fresh history
                    // looks exactly like it did before this feature.
                    Column(Modifier.fillMaxSize()) {
                        if (state.activities.isNotEmpty() || state.filtersActive) {
                            HistorySearchSortBar(viewModel)
                        }
                        HistoryFilterBar(viewModel)
                        if (state.activities.isNotEmpty()) {
                            HistorySummaryStrip(
                                monthKey = monthFilter,
                                count = state.activities.size,
                                totalMeters = state.activities.sumOf { it.distanceMeters }
                            )
                        }
                        if (state.activities.isEmpty()) {
                            if (state.filtersActive) {
                                // Filtered to zero — a plain no-matches state,
                                // NOT the get-started EmptyState: its CTA
                                // ("Kamu belum punya apa-apa, mulai rekam")
                                // is a lie while a filter is on.
                                Column(
                                    Modifier.fillMaxWidth().weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        stringResource(R.string.history_filter_no_matches),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(24.dp)
                                    )
                                    TextButton(onClick = {
                                        viewModel.setSportFilter(null)
                                        viewModel.setMonthFilter(null)
                                        viewModel.setSearchQuery("")
                                    }) {
                                        Text(stringResource(R.string.history_filter_reset))
                                    }
                                }
                            } else {
                                EmptyState(
                                    icon = Icons.Default.Hiking,
                                    title = stringResource(R.string.empty_history_title),
                                    description = stringResource(R.string.empty_history_desc),
                                    ctaText = stringResource(R.string.start_recording),
                                    onCtaClick = emptyCta,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                            items(state.activities, key = { it.id }) { activity ->
                                // Staggered entrance — each card fades+rises
                                // slightly after the previous one (capped so
                                // long lists don't wait), the app-wide list
                                // entrance.
                                com.nyasar.app.ui.components.AnimatedAppear(
                                    delayMs = com.nyasar.app.ui.components.Stagger.forIndex(state.activities.indexOfFirst { it.id == activity.id })
                                ) {
                                    ActivityCard(
                                        activity = activity,
                                        thumbnail = thumbnails[activity.id],
                                        onThumbnailNeeded = { viewModel.loadThumbnail(activity.id) },
                                        onClick = { onOpenActivity(activity.id) },
                                        onShare = { onShareActivity(activity.id) },
                                        onShareGpx = { onShareGpx(activity.id) }
                                    )
                                }
                            }
                        }                        // LazyColumn close
                    }                            // else (list non-empty) close
                    }                            // Column (filter bar + list) close
                }                                // LOADED branch close
            }                                    // when close
        }                                        // Box close
    }

/**
 * v0.9.3 Riwayat filter bar (poin 1–3 saran): dua baris chip yang bisa
 * discroll horizontal — jenis olahraga + bulan. Digabung = AND. Opsi chip
 * diambil dari data (hanya jenis/bulan yang benar-benar ada), semua chip
 * awalnya tidak terpilih = "Semua", jadi riwayat yang masih sedikit
 * tampak persis seperti sebelum fitur ini ada.
 */
@Composable
private fun HistoryFilterBar(
    viewModel: ActivityHistoryViewModel,
    modifier: Modifier = Modifier
) {
    val sportOptions by viewModel.sportOptions.collectAsState()
    val monthOptions by viewModel.monthOptions.collectAsState()
    val sportFilter by viewModel.sportFilter.collectAsState()
    val monthFilter by viewModel.monthFilter.collectAsState()
    if (sportOptions.size <= 1 && monthOptions.size <= 1) return
    // Active locale — AppCompatDelegate.setApplicationLocales keeps this in
    // sync with the LANGUAGE setting, so chip labels follow it (EN must
    // read "January 2026", not "Januari 2026").
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier) {
        if (sportOptions.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = sportFilter == null,
                    onClick = { viewModel.setSportFilter(null) },
                    label = { Text(stringResource(com.nyasar.app.R.string.filter_all)) }
                )
                sportOptions.forEach { sport ->
                    FilterChip(
                        selected = sportFilter == sport,
                        onClick = {
                            viewModel.setSportFilter(if (sportFilter == sport) null else sport)
                        },
                        label = {
                            Icon(
                                SportType.fromString(sport).icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(stringResource(SportType.fromString(sport).labelRes))
                        }
                    )
                }
            }
        }
        if (monthOptions.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = monthFilter == null,
                    onClick = { viewModel.setMonthFilter(null) },
                    label = { Text(stringResource(com.nyasar.app.R.string.filter_all)) }
                )
                monthOptions.forEach { bucket ->
                    FilterChip(
                        selected = monthFilter == bucket.key,
                        onClick = {
                            viewModel.setMonthFilter(if (monthFilter == bucket.key) null else bucket.key)
                        },
                        label = { Text(formatMonthLabel(bucket, locale)) }
                    )
                }
            }
        }
    }
}

/** Label chip bulan — memakai locale aktif (setting LANGUAGE), jadi bahasa
 *  chip, ringkasan, dan tanggal kartu selalu sejalan. */
private fun formatMonthLabel(
    bucket: ActivityHistoryViewModel.MonthBucket,
    locale: Locale
): String =
    SimpleDateFormat("MMMM yyyy", locale).format(Date(bucket.newestEpochMs)) +
        " (" + bucket.count + ")"

/** "2026-11" → "November 2026" — locale aktif, sama dengan chip bulan. */
private fun formatMonthKey(key: String, locale: Locale): String =
    runCatching {
        SimpleDateFormat("yyyy-MM", Locale.ROOT).parse(key)?.let {
            SimpleDateFormat("MMMM yyyy", locale).format(it)
        }
    }.getOrNull() ?: key

/**
 * v0.9.3 Paket C: kolom search (nama aktivitas) + menu urut (Terbaru /
 * Terlama / Terjauh). Keduanya menyetel state ViewModel yang di-AND-kan
 * dengan chip olahraga/bulan — search tidak menggantikan filter, hanya
 * menyempitkannya; urutan berlaku setelah penyaringan.
 */
@Composable
private fun HistorySearchSortBar(
    viewModel: ActivityHistoryViewModel,
    modifier: Modifier = Modifier
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(stringResource(R.string.history_search_hint), style = MaterialTheme.typography.bodyMedium)
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(50)
        )
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(
                    Icons.Default.Sort,
                    contentDescription = stringResource(R.string.history_sort_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                HistorySort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sort.labelRes)) },
                        trailingIcon = {
                            if (sort == sortMode) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        onClick = {
                            viewModel.setSortMode(sort)
                            sortMenuOpen = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Statistik bulanan yang mengikuti tampilan saat ini: jumlah aktivitas +
 * total km dari daftar yang sedang tampil, disebut bulannya bila filter
 * bulan aktif ("November 2026 · 3 aktivitas · 42,50 km"). Tanpa filter
 * bulan → hanya jumlah + total. Dihitung dari state, bukan state baru.
 */
@Composable
private fun HistorySummaryStrip(
    monthKey: String?,
    count: Int,
    totalMeters: Double,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(NyasarRadius.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        val locale = LocalConfiguration.current.locales[0]
        val activities = pluralStringResource(R.plurals.history_summary_activities, count, count)
        val distance = "%.2f km".format(totalMeters / 1000.0)
        val summary = if (monthKey != null) {
            stringResource(
                R.string.history_summary_month,
                formatMonthKey(monthKey, locale),
                activities,
                distance
            )
        } else {
            stringResource(R.string.history_summary_all, activities, distance)
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ActivityCard(
    activity: ActivityEntity,
    thumbnail: List<Pair<Double, Double>>?,
    onThumbnailNeeded: () -> Unit,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onShareGpx: () -> Unit
) {
    LaunchedEffect(activity.id) { onThumbnailNeeded() }
    val cardInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val locale = LocalConfiguration.current.locales[0]

    // Real map snapshot state — stores bitmap + bounds together
    val context = LocalContext.current
    val provider = remember { TileProviderFactory.default() }
    var mapSnapshotResult by remember { mutableStateOf<MapSnapshotResult?>(null) }
    LaunchedEffect(activity.id, thumbnail) {
        val track = thumbnail ?: return@LaunchedEffect
        if (track.size < 2) return@LaunchedEffect
        mapSnapshotResult = MapSnapshotHelper.getOrGenerate(
            context = context,
            activityId = activity.id,
            trackPoints = track,
            widthPx = 1080,
            heightPx = 640,
            styleUrl = provider.styleUrl()
        )
    }

    Surface(
        shape = RoundedCornerShape(NyasarRadius.md),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(cardInteraction)
            .clickable(
                interactionSource = cardInteraction,
                indication = null,
                onClick = onClick
            )
    ) {
        Column {
            Column(Modifier.padding(16.dp)) {
                // Header: icon + sport label + date
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // Hike icon fallback for UNSPECIFIED — the raw
                            // HelpOutline "?" glyph read as a broken card
                            // (same report as the Browse card).
                            Icon(
                                (SportType.fromString(activity.sportType).takeIf { it != SportType.UNSPECIFIED }
                                    ?: SportType.HIKE).icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(SportType.fromString(activity.sportType).labelRes),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            formatDate(activity.startedAtEpochMs, locale),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                // Wikiloc-style draft chip — a draft must be visibly NOT a
                // regular finished activity (its publish is deferred).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(activity.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (activity.status == com.nyasar.app.data.db.ActivityStatus.DRAFT) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                stringResource(R.string.draft_chip),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // 3-column stat row — sport-aware: middle column is Pace for running, Elev Gain for hiking
                Row(Modifier.fillMaxWidth()) {
                    StatColumn(
                        label = stringResource(R.string.distance),
                        value = "%.2f km".format(activity.distanceMeters / 1000.0),
                        modifier = Modifier.weight(1f)
                    )
                    val metric = SportType.fromString(activity.sportType).primaryMetric
                    if (metric == ShareMetric.PACE) {
                        val pace = if (activity.distanceMeters > 0 && activity.movingTimeMs > 0) {
                            val paceMinPerKm = (activity.movingTimeMs / 60000.0) / (activity.distanceMeters / 1000.0)
                            val pm = paceMinPerKm.toInt()
                            val ps = ((paceMinPerKm - pm) * 60).toInt()
                            "%d:%02d /km".format(pm, ps)
                        } else "- /km"
                        StatColumn(label = stringResource(R.string.pace), value = pace, modifier = Modifier.weight(1f))
                    } else {
                        StatColumn(
                            label = stringResource(R.string.elev_gain),
                            value = "${activity.elevationGainM?.roundToInt() ?: 0} m",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    StatColumn(
                        label = stringResource(R.string.time),
                        value = formatDuration(activity.elapsedTimeMs),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Route thumbnail — real map snapshot (fallback to grid)
            // Aspect ratio matches the snapshot bitmap (1080×640) so
            // ContentScale.FillBounds doesn't distort the map tiles.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1080f / 640f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                when {
                    thumbnail == null -> {
                        CircularProgressIndicator(
                            Modifier.align(Alignment.Center).size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    thumbnail.size < 2 -> { /* no track to draw */ }
                    else -> {
                        // Show real map snapshot if available, else fallback to grid
                        val snapshotResult = mapSnapshotResult
                        if (snapshotResult != null) {
                            Image(
                                bitmap = snapshotResult.bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                        // Draw the route line in the SAME coordinate space this
                        // Canvas actually occupies on screen (size.width/height —
                        // the Compose layout size), NOT the raw pixel dimensions
                        // of the snapshot bitmap. The Image above already stretches
                        // the bitmap to fill this exact container via
                        // ContentScale.FillBounds, so drawing against size.width/
                        // height here is what keeps the two aligned. Using the
                        // bitmap's own width/height instead (as before) put the
                        // route line in a different coordinate space than what
                        // FillBounds actually renders on screen, so the line ended
                        // up misaligned or nearly invisible depending on how far
                        // the bitmap's native resolution diverged from the
                        // on-screen size.
                        val routeLineColor = MaterialTheme.colorScheme.primary
                        // Fallback grid color (derived from onSurface, not hardcoded
                        // black): 0x18000000 vanished against the dark theme's
                        // near-black card background. Hoisted out of the Canvas:
                        // MaterialTheme.colorScheme is a composable read and may
                        // not be called inside the DrawScope lambda.
                        val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                        Canvas(Modifier.fillMaxSize()) {
                            val canvasWidth = size.width
                            val canvasHeight = size.height

                            // Fallback grid if no snapshot
                            if (snapshotResult == null) {
                                val gridSpacing = 40.dp.toPx()
                                var gx = 0f
                                while (gx <= canvasWidth) {
                                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, canvasHeight), strokeWidth = 0.5.dp.toPx())
                                    gx += gridSpacing
                                }
                                var gy = 0f
                                while (gy <= canvasHeight) {
                                    drawLine(gridColor, Offset(0f, gy), Offset(canvasWidth, gy), strokeWidth = 0.5.dp.toPx())
                                    gy += gridSpacing
                                }
                            }

                            // Route line — use snapshot bounds if available (so it
                            // aligns with map tiles), otherwise fallback to fit-in-center
                            val bounds = snapshotResult?.bounds
                            if (bounds != null) {
                                MapSnapshotHelper.drawTrackOnCanvas(
                                    canvas = this,
                                    trackPoints = thumbnail,
                                    bounds = bounds,
                                    strokeWidth = 6f,
                                    color = routeLineColor
                                )
                            } else {
                                // No snapshot bounds — compute local bounds and center
                                val lons = thumbnail.map { it.first }
                                val lats = thumbnail.map { it.second }
                                val minLon = lons.min(); val maxLon = lons.max()
                                val minLat = lats.min(); val maxLat = lats.max()
                                val lonSpan = (maxLon - minLon).takeIf { it > 0.0 } ?: 1.0
                                val latSpan = (maxLat - minLat).takeIf { it > 0.0 } ?: 1.0
                                val cosLat = cos(Math.toRadians((minLat + maxLat) / 2.0)).toFloat()
                                val lonDegWidth = (lonSpan * cosLat).toFloat()
                                val latDegHeight = latSpan.toFloat()
                                val maxDimen = maxOf(lonDegWidth, latDegHeight)
                                val targetSize = min(canvasWidth, canvasHeight) * 0.8f
                                val scale = if (maxDimen > 0f) targetSize / maxDimen else 1f
                                val routeWidth = lonDegWidth * scale
                                val routeHeight = latDegHeight * scale
                                val offsetX = (canvasWidth - routeWidth) / 2f
                                val offsetY = (canvasHeight - routeHeight) / 2f
                                val path = Path()
                                thumbnail.forEachIndexed { index, (lon, lat) ->
                                    val x = offsetX + ((lon - minLon).toFloat() * cosLat) * scale
                                    val y = offsetY + routeHeight - ((lat - minLat).toFloat() * scale)
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path, color = routeLineColor, style = Stroke(width = 6f))
                            }
                        }
                    }
                }
            }

            // Action row at bottom — two share buttons (GPX + Card), centered
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onShareGpx, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.bagikan_gpx),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = stringResource(R.string.share_card_cd),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}

private fun formatDate(epochMs: Long, locale: Locale): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(epochMs))
