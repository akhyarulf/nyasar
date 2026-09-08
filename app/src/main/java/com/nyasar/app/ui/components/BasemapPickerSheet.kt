package com.nyasar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
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
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.OverlayLayer
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.map.MapSnapshotHelper

/**
 * Basemap + overlay picker — bottom sheet with two horizontally-scrollable
 * rows, one per section, styled to match (spec: reference Strava screenshot
 * — "Map Types" row shows exactly 4 tiles on screen at once with the rest
 * reachable by swipe; "Overlays" uses the identical tile layout/sizing,
 * just with however many entries it actually has rather than being padded
 * out to 4). Both rows share one tile-width formula computed from the
 * sheet's actual content width (BoxWithConstraints) so "exactly 4 fit" is
 * true on any screen size, not just the reference device's.
 *
 * Basemaps: all 9 World [BasemapEntry] catalog entries (Liberty Topo,
 * Liberty Satellite, OpenMapTiles OSM, OpenMapTiles OSM Topo,
 * OpenStreetMap, OpenTopoMap, OpenHikingMap, CyclOSM, UtagawaMTB) —
 * country variants were removed from the catalog entirely
 * (BasemapCatalog.kt), not merely hidden here.
 *
 * Overlays: the 3 Waymarked Trails layers (Hiking, Cycling, MTB) — was
 * previously a vertical checkbox list; now the same tile shape as
 * basemaps (icon tile + label, selection shown as a border + check badge
 * rather than a Material Checkbox) so the two sections read as one
 * consistent picker UI rather than two different UI languages on the same
 * sheet.
 *
 * Thumbnails: real map previews backed by
 * [com.nyasar.app.ui.map.MapSnapshotHelper.generateBasemapPreview] (each
 * entry's own real upstream style/tiles via MapLibre's snapshotter, disk
 * cached). Overlay tiles use a plain Material icon per layer instead —
 * Waymarked Trails' tile endpoints are transparent line overlays, not
 * standalone basemaps, so a snapshot of one alone renders as a mostly
 * empty image; an icon says what the layer *is* more clearly than that
 * would.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasemapPickerSheet(
    selected: BasemapEntry,
    onSelect: (BasemapEntry) -> Unit,
    /** Waymarked Trails overlays (spec: GPX Studio reference "Overlays"
     *  section) — defaults keep every existing call site working
     *  unchanged (no overlays shown/togglable) until a screen opts in. */
    activeOverlays: Set<OverlayLayer> = emptySet(),
    onToggleOverlay: (OverlayLayer) -> Unit = {},
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
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Jenis Peta", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            // Shared tile-width formula for both rows — "exactly 4 visible
            // at once, rest reachable by swipe" (spec), computed from this
            // Column's actual content width (already inset by the 20.dp
            // horizontal padding above) rather than a fixed dp constant, so
            // it holds on any screen size, not just one reference width.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val spacing = 10.dp
                val tileWidth = (maxWidth - spacing * 3) / 4

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(BasemapEntry.ordered) { entry ->
                        BasemapTile(
                            entry = entry,
                            isSelected = entry == selected,
                            onClick = { onSelect(entry) },
                            width = tileWidth
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Overlays", style = MaterialTheme.typography.titleLarge)
            Text(
                "Waymarked Trails",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // Same tile width formula as the basemap row above (spec:
            // "mirip untuk UI antara jenis peta dan overlay") — recomputed
            // from this row's own BoxWithConstraints rather than hoisted
            // out of the one above, since the two rows aren't guaranteed
            // to share a composition scope, but the formula (and therefore
            // the resulting width) is identical given the same content
            // width, so the tiles still end up pixel-for-pixel the same
            // size. Only 3 entries exist today so this row never needs to
            // scroll — that's a property of OverlayLayer's current entry
            // count, not a different layout mechanism from the row above.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val spacing = 10.dp
                val tileWidth = (maxWidth - spacing * 3) / 4

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(OverlayLayer.entries.toList()) { overlay ->
                        OverlayTile(
                            overlay = overlay,
                            isChecked = overlay in activeOverlays,
                            onClick = { onToggleOverlay(overlay) },
                            width = tileWidth
                        )
                    }
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
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
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
        Spacer(Modifier.height(8.dp))
        Text(
            entry.gpxName,
            style = MaterialTheme.typography.labelMedium,
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
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
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
                    imageVector = overlayIcon(overlay),
                    contentDescription = null,
                    tint = overlayTint(overlay),
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
        Spacer(Modifier.height(8.dp))
        Text(
            overlay.displayName,
            style = MaterialTheme.typography.labelMedium,
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
