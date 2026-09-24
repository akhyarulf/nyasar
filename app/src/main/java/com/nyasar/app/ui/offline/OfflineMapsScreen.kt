package com.nyasar.app.ui.offline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nyasar.app.map.StyleVariant
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.components.EmptyState
import com.nyasar.app.ui.map.MapSnapshotHelper
import com.nyasar.app.ui.components.NyasarMapView
import com.nyasar.app.R
import com.nyasar.app.ui.theme.NyasarRadius
import androidx.compose.ui.res.stringResource

/**
 * "Peta Offline" — spec P3 gap: download existed (Route Preview), management
 * didn't, and coverage was invisible (spec §24, WAJIB). Reachable from
 * Settings > Offline. Shows: coverage map at the top (so "area mana yang
 * sudah saya download?" has an actual answer), list below with size/status/
 * view/delete, and a "+ Download Area" FAB for the route-free entry point
 * (spec §22).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    viewModel: OfflineMapsViewModel = viewModel(),
    onBack: () -> Unit,
    onDownloadArea: () -> Unit = {},
    // "Lihat di Peta" wiring back to Home with the area focused is PART 4
    // scope (per spec, explicitly not this part). Until that route exists,
    // this reuses the exact same real, already-working action the old
    // "eye" icon had — focusing the coverage map already rendered at the
    // top of this screen — rather than a no-op TODO stub (spec: "JANGAN
    // bikin behavior asal-asalan").
    onOpenInMap: (OfflineRegionUi) -> Unit = { viewModel.focus(it) }
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val state by viewModel.uiState.collectAsState()
    val provider = remember { TileProviderFactory.default() }
    // Delete confirmation (spec: "tidak accidental, confirmation bila
    // diperlukan") — previously a single tap deleted a downloaded region
    // immediately, no way back for something that can be tens/hundreds of
    // MB and took real time to download.
    var pendingDelete by remember { mutableStateOf<OfflineRegionUi?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.offline_maps_setting)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onDownloadArea,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.download_area)) }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val boundsWithCoverage = state.regions.mapNotNull { it.bounds }
            val focusedBounds = state.regions.firstOrNull {
                System.identityHashCode(it.region) == state.focusedRegionKey
            }?.bounds

            // Coverage map (spec §24, WAJIB) — always visible when there's
            // at least one region, regardless of status-check completion,
            // since bounds come from the region definition, not the status
            // callback (see OfflineMapsViewModel).
            if (boundsWithCoverage.isNotEmpty()) {
                Box(Modifier.height(220.dp).fillMaxWidth()) {
                    NyasarMapView(
                        modifier = Modifier.fillMaxSize(),
                        provider = provider,
                        track = emptyList(),
                        offlineCoverage = boundsWithCoverage,
                        // Camera bounds padded ~18% beyond the coverage
                        // rectangle: fitting the camera EXACTLY to the bounds
                        // put the highlight flush against the viewport (stroke
                        // clipped at the edges) — the #1 reason this preview
                        // read as "just a map" instead of "map + my area".
                        focusBounds = padForVisibility(
                            focusedBounds ?: boundsWithCoverage.reduce { a, b ->
                                org.maplibre.android.geometry.LatLngBounds.Builder()
                                    .include(a.northEast).include(a.southWest)
                                    .include(b.northEast).include(b.southWest)
                                    .build()
                            }
                        )
                    )
                }
                HorizontalDivider()
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.regions.isEmpty() -> EmptyState(
                        icon = Icons.Default.Download,
                        title = stringResource(R.string.no_offline_maps),
                        description = stringResource(R.string.no_offline_maps_desc),
                        // Spec §5: empty state needs a real central CTA, not
                        // just the corner FAB — the FAB stays too (still
                        // useful once the list has content), this is
                        // additive for the zero-region case specifically.
                        ctaText = stringResource(R.string.download_map),
                        onCtaClick = onDownloadArea,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.regions, key = { System.identityHashCode(it.region) }) { item ->
                            com.nyasar.app.ui.components.AnimatedAppear(
                                delayMs = com.nyasar.app.ui.components.Stagger.forIndex(state.regions.indexOf(item))
                            ) {
                                OfflineRegionCard(
                                    item = item,
                                    isDeleting = System.identityHashCode(item.region) == state.deletingRegionKey,
                                    isResuming = System.identityHashCode(item.region) == state.resumingRegionKey,
                                    onPrimaryAction = {
                                        if (item.completed) onOpenInMap(item) else viewModel.resumeDownload(item)
                                    },
                                    onDeleteRequest = { pendingDelete = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.delete_map_message, item.name, formatSize(item.sizeBytes)))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}

/** Expand bounds by [fraction] on every side so the top coverage map's
 *  camera (newLatLngBounds fits EXACTLY) leaves breathing room around the
 *  highlight rectangle instead of clipping its stroke at the viewport edge —
 *  the #1 reason the preview read as "just a map, where's my area?". */
private fun padForVisibility(
    bounds: org.maplibre.android.geometry.LatLngBounds,
    fraction: Double = 0.18
): org.maplibre.android.geometry.LatLngBounds {
    val latSpan = bounds.northEast.latitude - bounds.southWest.latitude
    val lonSpan = bounds.northEast.longitude - bounds.southWest.longitude
    val latPad = (latSpan * fraction).coerceAtLeast(0.0015)
    val lonPad = (lonSpan * fraction).coerceAtLeast(0.0015)
    return org.maplibre.android.geometry.LatLngBounds.Builder()
        .include(
            org.maplibre.android.geometry.LatLng(
                bounds.northEast.latitude + latPad,
                bounds.northEast.longitude + lonPad
            )
        )
        .include(
            org.maplibre.android.geometry.LatLng(
                bounds.southWest.latitude - latPad,
                bounds.southWest.longitude - lonPad
            )
        )
        .build()
}

/**
 * PART 3 redesign: banner card with mini-map preview showing the actual
 * coverage area. Each card now shows:
 * - Mini-map preview of the downloaded area
 * - Area name
 * - Download size
 * - Status (ready/incomplete)
 * - Coverage bounds info
 */
@Composable
private fun OfflineRegionCard(
    item: OfflineRegionUi,
    isDeleting: Boolean,
    isResuming: Boolean,
    onPrimaryAction: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val provider = remember { TileProviderFactory.default() }

    Surface(shape = RoundedCornerShape(NyasarRadius.md), tonalElevation = 2.dp) {
        Column {
            // Static snapshot preview of the coverage area (lightweight:
            // one MapSnapshotter render, disk-cached — no live GL map per
            // card) with the coverage rectangle drawn as a visible inset.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                RegionStaticPreview(
                    item = item,
                    provider = provider,
                    modifier = Modifier.fillMaxSize()
                )

                // Status tag overlay
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    StatusTag(completed = item.completed, statusKnown = item.statusKnown, statusError = item.statusError)
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options_cd), tint = Color.White)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { showMenu = false; onDeleteRequest() }
                            )
                        }
                    }
                }
            }

            // Info section
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    // Which map style + when — the "sudah unduh yang mana," answer.
                    // Both optional: legacy regions report nulls and simply skip
                    // this line instead of showing "—" noise.
                    listOfNotNull(
                        item.basemapName,
                        item.createdAtEpochMs?.let {
                            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                                .format(java.util.Date(it))
                        }
                    ).joinToString(" • ").takeIf { it.isNotBlank() }?.let { metaLine ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    // Size and status info. "Ready to use" is NOT repeated
                    // here — the overlay tag on the preview already says it.
                    Text(
                        when {
                            !item.statusKnown -> stringResource(R.string.checking)
                            item.statusError -> stringResource(R.string.checking_status_error)
                            item.completed -> formatSize(item.sizeBytes)
                            else -> "${formatSize(item.sizeBytes)} ${stringResource(R.string.downloaded_suffix)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Human-readable footprint instead of raw degree pairs —
                    // "≈ 2.6 × 1.9 km" answers "seberapa luas unduhannya" at a
                    // glance and never wraps (the old degree line broke onto a
                    // second line and ended in a lone "B").
                    item.bounds?.let { bounds ->
                        val midLat = (bounds.northEast.latitude + bounds.southWest.latitude) / 2.0
                        val cosMid = kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.01)
                        val kmW = (bounds.northEast.longitude - bounds.southWest.longitude) * 111.32 * cosMid
                        val kmH = (bounds.northEast.latitude - bounds.southWest.latitude) * 111.32
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "≈ %.1f × %.1f km".format(kmW, kmH),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                when {
                    isDeleting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    isResuming -> Button(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.downloading))
                    }
                    item.completed -> Button(onClick = onPrimaryAction) { Text(stringResource(R.string.view_on_map)) }
                    else -> Button(onClick = onPrimaryAction) { Text(stringResource(R.string.continue_download)) }
                }
            }
        }
    }
}

@Composable
private fun StatusTag(completed: Boolean, statusKnown: Boolean, statusError: Boolean) {
    val label = when {
        !statusKnown -> stringResource(R.string.checking)
        statusError -> stringResource(R.string.status_check_failed)
        completed -> stringResource(R.string.track_ready_offline)
        else -> stringResource(R.string.track_incomplete)
    }
    Surface(
        color = Color.Black.copy(alpha = 0.35f),
        shape = RoundedCornerShape(NyasarRadius.xs)
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Lightweight card preview for one downloaded region: a single MapLibre
 * MapSnapshotter render (same engine and disk cache as the basemap picker
 * thumbnails — MapSnapshotHelper.generateRegionPreview) with the coverage
 * rectangle drawn on top as a visible inset.
 *
 * Why not NyasarMapView (the previous implementation): one live GL map per
 * card costs a texture surface, continuous rendering, and a full style
 * load per composition — measurable memory/jank cost in a list, to show a
 * static rectangle. The snapshot is produced once and disk-cached; the
 * overlay rect is plain Canvas math (the region is padded by the helper
 * precisely so this inset is honest about the true footprint).
 */
@Composable
private fun RegionStaticPreview(
    item: OfflineRegionUi,
    provider: com.nyasar.app.map.TileProvider,
    modifier: Modifier = Modifier
) {
    val bounds = item.bounds
    val density = LocalDensity.current
    val context = LocalContext.current
    // ~2x render for crisp high-dpi cards, drawn back down 1:1 by the Canvas.
    val pixelRatio = minOf(2f, density.density.coerceAtLeast(1f))
    val rectColor = MaterialTheme.colorScheme.primary
    val fallbackBg = MaterialTheme.colorScheme.surfaceVariant

    var canvasW by remember { mutableStateOf(0) }
    var canvasH by remember { mutableStateOf(0) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(bounds, canvasW, canvasH) {
        val b = bounds ?: return@LaunchedEffect
        if (canvasW < 8 || canvasH < 8) return@LaunchedEffect
        val w = (canvasW * pixelRatio).toInt()
        val h = (canvasH * pixelRatio).toInt()
        // Cache identity: region bounds + basemap style + size — stable
        // across restarts (disk-cached by the helper, independent version).
        val cacheKey = "offline-region-${b.longitudeWest}-${b.latitudeSouth}-${b.longitudeEast}-${b.latitudeNorth}-$w"
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                MapSnapshotHelper.generateRegionPreview(
                    context = context,
                    cacheKey = cacheKey,
                    bounds = b,
                    styleUrl = provider.styleUrl(StyleVariant.OUTDOOR),
                    widthPx = w,
                    heightPx = h
                )
            }.getOrNull()
        }
    }

    Canvas(modifier.onSizeChanged { size ->
        canvasW = size.width
        canvasH = size.height
    }) {
        val bmp = bitmap
        if (bmp != null) {
            drawImage(
                bmp.asImageBitmap(),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        } else {
            // Loading / offline-fallback surface — never an error state.
            drawRect(fallbackBg)
        }
        if (bounds != null) {
            // The snapshot covers bounds padded 14% per side; the coverage
            // rectangle sits inset accordingly and is always fully visible.
            val insetX = size.width * 0.12f
            val insetY = size.height * 0.12f
            val topLeft = Offset(insetX, insetY)
            val rectSize = Size(size.width - insetX * 2, size.height - insetY * 2)
            // Soft fill so the area reads as "selected" even without strokes.
            drawRect(rectColor.copy(alpha = 0.20f), topLeft = topLeft, size = rectSize)
            // Crisp double outline (light casing + brand core), the same
            // two-layer trick the track line uses on every other preview.
            drawRect(Color.White.copy(alpha = 0.9f), topLeft = topLeft, size = rectSize, style = Stroke(2.dp.toPx()))
            drawRect(rectColor.copy(alpha = 0.95f), topLeft = topLeft, size = rectSize, style = Stroke(1.5.dp.toPx()))
        }
    }
}
