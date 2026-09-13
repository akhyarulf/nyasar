package com.nyasar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nyasar.app.R
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.OverlayLayer
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.map.MapSnapshotHelper
import com.nyasar.app.ui.theme.NyasarRadius

/**
 * Basemap + overlay + data picker — bottom sheet with three WRAPPED GRID
 * sections, styled to match (spec: reference Strava screenshot — tiles are
 * small, exactly 4 per row, and every option is visible at once instead of
 * hiding most of the catalog behind a horizontal swipe). Each section is a
 * FlowRow capped at 4 items per row: 8 basemaps render as 4+4 rows, so
 * the full catalog is discoverable without scrolling sideways.
 *
 * Two compactness guarantees, both hard-won from device testing:
 *  - EXACTLY 4 per row: tile width is computed in whole PIXELS
 *    (BoxWithConstraints maxWidth minus 3 gutters, divided by 4, floored)
 *    instead of raw dp — dp values round-trip through Modifier.width's
 *    pixel rounding, and a fraction-of-a-pixel overflow per row was enough
 *    for FlowRow to wrap the 4th tile (the 3+3+2 bug). Flooring can only
 *    underflow, so 4 tiles + 3 gaps always fit, on every density.
 *  - NO-EXPAND fit: the vertical rhythm is compact (titleMedium headers,
 *    labelSmall labels, 8dp gutters, tight spacers) so Map Types (2 rows)
 *    + Overlays (1 row) + Data (1 row) fit inside the sheet's default
 *    height on normal phones — nothing hidden below the fold.
 *    verticalScroll remains only as a fallback for very short screens.
 *
 * Basemaps: all 8 World [BasemapEntry] catalog entries (Liberty Topo,
 * Liberty Satellite, OpenMapTiles OSM Topo,
 * OpenStreetMap, OpenTopoMap, OpenHikingMap, CyclOSM, UtagawaMTB) —
 * country variants were removed from the catalog entirely
 * (BasemapCatalog.kt), not merely hidden here.
 *
 * Overlays: the 3 Waymarked Trails layers (Hiking, Cycling, MTB) —
 * third-party map content, same for every user. Styled identically to
 * basemaps (icon tile + label, selection shown as a border + check badge
 * rather than a Material Checkbox) so the sections read as one picker UI.
 *
 * Data: the user's OWN map data — "Jalur Saya" (all saved Library routes),
 * Waypoint pins, and downloaded-area coverage. Split out from the Waymarked
 * row because it's a different category (personal data vs third-party map
 * content), and to give the downloaded-areas overlay a discoverable home
 * screen-side (previously reachable only via Settings → Offline). All three
 * toggles are persisted app-wide in DataStore (see SettingsRepository) and
 * default OFF.
 *
 * Thumbnails: real map previews backed by
 * [com.nyasar.app.ui.map.MapSnapshotHelper.generateBasemapPreview] (each
 * entry's own real upstream style/tiles via MapLibre's snapshotter, disk
 * cached). Overlay/Data tiles use a plain Material icon per layer instead —
 * none of them are standalone basemaps, so an icon says what the layer
 * *is* more clearly than a mostly-empty snapshot would.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BasemapPickerSheet(
    selected: BasemapEntry,
    onSelect: (BasemapEntry) -> Unit,
    /** Waymarked Trails overlays (spec: GPX Studio reference "Overlays"
     *  section) — defaults keep every existing call site working
     *  unchanged (no overlays shown/togglable) until a screen opts in. */
    activeOverlays: Set<OverlayLayer> = emptySet(),
    onToggleOverlay: (OverlayLayer) -> Unit = {},
    /** "Jalur Saya" overlay — draws every saved Library route as a line
     *  on the map. Independent of the Waymarked Trails set: those are
     *  raster tile layers from a server, this is the user's own GPX data
     *  (see MyRoutesOverlay), so it gets its own persisted boolean. */
    myRoutesEnabled: Boolean = false,
    onToggleMyRoutes: () -> Unit = {},
    /** Waypoint pins on the map (GPX route waypoints + user-created
     *  waypoints). Hidden on every screen while false. */
    waypointsVisible: Boolean = false,
    onToggleWaypoints: () -> Unit = {},
    /** Downloaded-area coverage overlay — green = complete, gray =
     *  incomplete, only for the active basemap's style. */
    offlineAreasEnabled: Boolean = false,
    onToggleOfflineAreas: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val purgeContext = LocalContext.current
    // P3K audit fix: one-time purge of any basemap thumbnail cached under
    // a stale version, so a wrong/identical-looking PNG left over from
    // before the RasterStyleJson/MapSnapshotHelper cache-key fix can
    // never be served again, even on a device that already had bad
    // thumbnails on disk. Cheap (single directory listing) and safe to
    // run every time the sheet opens.
    LaunchedEffect(Unit) {
        MapSnapshotHelper.purgeStaleBasemapPreviews(purgeContext)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // verticalScroll: FALLBACK for very short screens only — the compact
        // rhythm below (2+1+1 tile rows, small headers, tight spacers) is
        // designed to fit the sheet's default height WITHOUT scrolling, so
        // every section is visible the moment the sheet opens. On tiny
        // screens this scroll prevents the Data section from clipping.
        Column(
            Modifier
                .widthIn(max = com.nyasar.app.ui.theme.NyasarContentWidth.sheetMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(stringResource(R.string.map_types_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            // Shared tile-width formula for every section — Strava-style
            // small tiles, exactly 4 per row, wrapped (not scrolled). Width
            // is derived from this Column's real content width in whole
            // PIXELS: dp-perfect arithmetic still round-trips through
            // Modifier.width's pixel rounding, and a fractional-px overflow
            // was silently wrapping rows to 3+3+2 on some densities. Flooring
            // can only underflow, never overflow, so 4 tiles + 3 gaps always
            // fit regardless of screen width/density.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val spacing = 8.dp
                val tileWidth = with(LocalDensity.current) {
                    ((maxWidth - spacing * 3).toPx() / 4).toInt().toDp()
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    maxItemsInEachRow = 4,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasemapEntry.ordered.forEach { entry ->
                        BasemapTile(
                            entry = entry,
                            isSelected = entry == selected,
                            onClick = { onSelect(entry) },
                            width = tileWidth
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.overlays_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.overlays_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            // Overlays grid: the 3 Waymarked Trails layers — third-party map
            // content, same for every user. User-owned layers (routes/
            // waypoints/downloaded areas) live in the "Data" section below.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val spacing = 8.dp
                val tileWidth = with(LocalDensity.current) {
                    ((maxWidth - spacing * 3).toPx() / 4).toInt().toDp()
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    maxItemsInEachRow = 4,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OverlayLayer.entries.forEach { overlay ->
                        OverlayTile(
                            overlay = overlay,
                            isChecked = overlay in activeOverlays,
                            onClick = { onToggleOverlay(overlay) },
                            width = tileWidth
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.data_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.data_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            // Data grid: the user's own layers — Jalur Saya (saved routes),
            // waypoint pins, downloaded-area coverage. Same 4-per-row wrap;
            // same border+check-badge toggle language as the sections above;
            // each toggle persists app-wide via SettingsRepository.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val spacing = 8.dp
                val tileWidth = with(LocalDensity.current) {
                    ((maxWidth - spacing * 3).toPx() / 4).toInt().toDp()
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    maxItemsInEachRow = 4,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DataToggleTile(
                        label = stringResource(R.string.data_my_routes),
                        icon = Icons.Filled.Route,
                        tint = Color(0xFF42A5F5),
                        isChecked = myRoutesEnabled,
                        onClick = onToggleMyRoutes,
                        width = tileWidth
                    )
                    DataToggleTile(
                        label = stringResource(R.string.data_waypoints),
                        icon = Icons.Filled.Place,
                        tint = Color(0xFFE8734D),
                        isChecked = waypointsVisible,
                        onClick = onToggleWaypoints,
                        width = tileWidth
                    )
                    DataToggleTile(
                        label = stringResource(R.string.data_offline_areas),
                        icon = Icons.Filled.Layers,
                        tint = Color(0xFF6BAE4D),
                        isChecked = offlineAreasEnabled,
                        onClick = onToggleOfflineAreas,
                        width = tileWidth
                    )
                }
            }
        }
    }
}

/** One basemap tile — real map-snapshot thumbnail, label below, selection
 *  shown as a primary-color border (radio-style: exactly one basemap is
 *  ever selected). [width] comes from the shared formula in the sheet
 *  above so every tile in the row is identically sized. */
@Composable
private fun BasemapTile(
    entry: BasemapEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    width: Dp
) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(NyasarRadius.sm))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(NyasarRadius.md),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            border = if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            }
        ) {
            BasemapThumbnail(
                entry = entry,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.gpxName,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** One overlay tile — same shape/size/selection-border language as
 *  [BasemapTile] (spec: the two sections should look like one picker),
 *  but overlays are multi-select (checkboxes, not radio) so on top of the
 *  border, a checked tile also gets a small check badge — without that
 *  second cue, a selected overlay tile and a selected (radio) basemap
 *  tile would be visually identical despite meaning different things
 *  ("the" choice vs. "one of possibly several" choices). */
@Composable
private fun OverlayTile(
    overlay: OverlayLayer,
    isChecked: Boolean,
    onClick: () -> Unit,
    width: Dp
) {
    DataToggleTile(
        label = stringResource(overlay.labelRes),
        icon = overlayIcon(overlay),
        tint = overlayTint(overlay),
        isChecked = isChecked,
        onClick = onClick,
        width = width
    )
}

/** One Data-section tile — the user's own layers (Jalur Saya, waypoint
 *  pins, downloaded-area coverage). Same shape/selection language as
 *  [OverlayTile] (border + check badge) with its own icon/tint and no
 *  per-layer variants: each is a single on/off switch over one data
 *  source, persisted app-wide via SettingsRepository. */
@Composable
private fun DataToggleTile(
    label: String,
    icon: ImageVector,
    tint: Color,
    isChecked: Boolean,
    onClick: () -> Unit,
    width: Dp
) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(NyasarRadius.sm))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(NyasarRadius.md),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            border = if (isChecked) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            }
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.fillMaxSize(0.42f).align(Alignment.Center)
                )
                if (isChecked) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(18.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(3.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isChecked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun overlayIcon(overlay: OverlayLayer): ImageVector = when (overlay) {
    OverlayLayer.HIKING -> Icons.Default.DirectionsWalk
    OverlayLayer.CYCLING -> Icons.Default.DirectionsBike
    OverlayLayer.MTB -> Icons.Default.Terrain
}

@Composable
private fun overlayTint(overlay: OverlayLayer): Color = when (overlay) {
    OverlayLayer.HIKING -> Color(0xFFE8734D)
    OverlayLayer.CYCLING -> Color(0xFF4D8FE8)
    OverlayLayer.MTB -> Color(0xFF6BAE4D)
}

/**
 * Real map preview per entry (P3J follow-up: was purely procedural Canvas
 * art, which is why it never visually matched any reference screenshot —
 * it was never meant to be a map, just a colored placeholder). Now backed
 * by [com.nyasar.app.ui.map.MapSnapshotHelper.generateBasemapPreview],
 * which renders each entry's own real upstream style/tiles (the exact
 * same [TileProvider.styleUrlFor] URL the actual map uses) via MapLibre's
 * own snapshotter — no gpx.studio involved, no live network required at
 * grid-render time beyond the one-shot snapshot itself, and it's disk
 * cached (one file per entry) so switching sheets/screens doesn't
 * re-fetch. Falls back to a flat tinted placeholder only while the
 * snapshot is loading or if it fails outright (offline, upstream down).
 */
@Composable
private fun BasemapThumbnail(entry: BasemapEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var bitmap by remember(entry) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(entry) { mutableStateOf(false) }

    LaunchedEffect(entry) {
        bitmap = null
        failed = false
        val sizePx = with(density) { 200.dp.roundToPx() }
        val styleUrl = try {
            entryStyleUrl(entry, context)
        } catch (e: Exception) {
            null
        }
        if (styleUrl == null) {
            failed = true
            return@LaunchedEffect
        }
        // P3K audit fix: cache key now folds in the entry's own source
        // fingerprint (its real tile template / style URL, not just its
        // gpxKey) so that if two entries ever ended up with the same
        // gpxKey by mistake, or one entry's rasterUrl changes later, they
        // can never collide on — or reuse — the same cached thumbnail
        // file. Actual invalidation is still driven by
        // MapSnapshotHelper.BASEMAP_PREVIEW_CACHE_VERSION; this is a
        // second, independent safeguard against key collisions.
        val sourceFingerprint = styleUrl.hashCode().toUInt().toString(16)
        val result = MapSnapshotHelper.generateBasemapPreview(
            context = context,
            cacheKey = "basemap_${entry.gpxKey}_$sourceFingerprint",
            styleUrl = styleUrl,
            widthPx = sizePx,
            heightPx = sizePx
        )
        if (result != null) bitmap = result else failed = true
    }

    Box(modifier.fillMaxSize()) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            failed -> GenericScene(entry, Modifier.fillMaxSize())
            else -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** Same resolution TileProvider.styleUrlFor uses for the real map — kept
 *  as a tiny local wrapper only so this file doesn't need to carry a
 *  TileProvider instance just to call one function on it. */
private fun entryStyleUrl(entry: BasemapEntry, context: android.content.Context): String {
    val provider = TileProviderFactory.default()
    return provider.styleUrlFor(entry, context)
}

/** Shared look for the 6 catalog entries with no bespoke scene — a flat
 *  tint (stable per entry, from its ordinal, so it doesn't shift between
 *  recompositions) plus a centered icon distinguishing raster (globe —
 *  OpenStreetMap-family tile servers) from vector (layered stack icon —
 *  hosted MapLibre style JSON). Also the fallback whenever a real
 *  snapshot fails to load (offline, upstream down), for any entry. */
@Composable
private fun GenericScene(entry: BasemapEntry, modifier: Modifier = Modifier) {
    val tints = listOf(
        Color(0xFF3D5A73), Color(0xFF5C6B3D), Color(0xFF734B3D),
        Color(0xFF3D6B5C), Color(0xFF56497A), Color(0xFF7A5649)
    )
    val tint = tints[entry.ordinal % tints.size]
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.75f))))
        }
        Icon(
            imageVector = if (entry.isRaster) Icons.Default.Public else Icons.Default.Layers,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxSize(0.4f)
        )
    }
}
