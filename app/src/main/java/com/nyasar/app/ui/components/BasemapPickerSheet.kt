package com.nyasar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nyasar.app.map.BasemapEntry

/**
 * Basemap picker — bottom sheet with a grid of all 9 World [BasemapEntry]
 * catalog entries (Liberty Topo, Liberty Satellite, OpenMapTiles OSM,
 * OpenMapTiles OSM Topo, OpenStreetMap, OpenTopoMap, OpenHikingMap,
 * CyclOSM, UtagawaMTB) — country variants were removed from the catalog
 * entirely (BasemapCatalog.kt), not merely hidden here, so there is
 * nothing left to filter out.
 *
 * Previously this sheet only chose between the 3 legacy [StyleVariant]s
 * (Outdoor/Satellite/Terrain resolved via the active TileProvider); the
 * catalog's 9 [BasemapEntry] values existed as pure data with no picker UI
 * anywhere in the app. This is that missing UI — callers now hold a
 * [BasemapEntry] selection instead of a StyleVariant (see HomeViewModel /
 * RecordingViewModel / RoutePreviewScreen for the per-screen state; each
 * pattern mirrors exactly how they already held the old StyleVariant, no
 * new persistence mechanism introduced).
 *
 * Thumbnails: still procedural Canvas art (zero network, zero bundled
 * assets) — 3 of the 9 entries reuse the original hand-drawn scenes (they
 * map naturally: Liberty Topo≈Outdoor, Liberty Satellite≈Satellite,
 * OpenMapTiles OSM Topo≈Terrain), the other 6 get a simpler shared
 * treatment (a flat tint + a small distinguishing icon/motif) rather than
 * six more bespoke illustrations — keeps the picker genuinely showing 9
 * *distinct* choices without a disproportionate amount of hand-drawn art
 * for entries the user hasn't seen yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasemapPickerSheet(
    selected: BasemapEntry,
    onSelect: (BasemapEntry) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Jenis Peta", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().heightForRows(BasemapEntry.ordered.size)
            ) {
                items(BasemapEntry.ordered) { entry ->
                    val isSelected = entry == selected
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(entry) }
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
            }
        }
    }
}

/** 3 columns -> ceil(count/3) rows, each thumbnail is a square card ~132dp
 *  tall including its label, plus grid spacing — a fixed height (rather
 *  than nested-scroll weight tricks) keeps this sheet's height stable and
 *  avoids the LazyVerticalGrid-inside-ModalBottomSheet measurement issues
 *  an unconstrained/weighted height can cause. */
private fun Modifier.heightForRows(itemCount: Int): Modifier {
    val rows = (itemCount + 2) / 3
    return this.height((rows * 132).dp)
}

/**
 * Procedural thumbnail. Entries that correspond conceptually to the three
 * original StyleVariant scenes reuse that exact art; the rest share a
 * flatter "generic map style" treatment distinguished by a per-entry tint
 * and a small icon (globe for the plain OSM-family styles, a layered-lines
 * glyph for the others) so all 9 tiles are visually distinct at a glance
 * without six new illustrations.
 */
@Composable
private fun BasemapThumbnail(entry: BasemapEntry, modifier: Modifier = Modifier) {
    when (entry) {
        BasemapEntry.LIBERTY_TOPO -> OutdoorScene(modifier)
        BasemapEntry.LIBERTY_SATELLITE -> SatelliteScene(modifier)
        BasemapEntry.OSM_TOPO -> TerrainScene(modifier)
        else -> GenericScene(entry, modifier)
    }
}

@Composable
private fun OutdoorScene(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF44603F), Color(0xFF2C4230))))
        drawCircle(
            Color(0xFF4E7C8C).copy(alpha = 0.9f),
            radius = size.width * 0.15f,
            center = Offset(size.width * 0.78f, size.height * 0.75f)
        )
        val trail = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.85f)
            cubicTo(
                size.width * 0.45f, size.height * 0.65f,
                size.width * 0.25f, size.height * 0.30f,
                size.width * 0.92f, size.height * 0.14f
            )
        }
        drawPath(trail, Color(0xFFE8C468), style = Stroke(width = size.width * 0.08f, cap = StrokeCap.Round))
    }
}

@Composable
private fun SatelliteScene(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2A4227), Color(0xFF162415))))
        drawCircle(Color(0xFF3E5C2F), radius = size.width * 0.22f, center = Offset(size.width * 0.30f, size.height * 0.34f))
        drawCircle(Color(0xFF576D35), radius = size.width * 0.18f, center = Offset(size.width * 0.72f, size.height * 0.58f))
        drawCircle(Color(0xFF24401F), radius = size.width * 0.26f, center = Offset(size.width * 0.66f, size.height * 0.18f))
        val river = Path().apply {
            moveTo(0f, size.height * 0.78f)
            quadraticBezierTo(size.width * 0.5f, size.height * 0.52f, size.width, size.height * 0.72f)
        }
        drawPath(river, Color(0xFF35586D).copy(alpha = 0.95f), style = Stroke(width = size.width * 0.07f))
    }
}

@Composable
private fun TerrainScene(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFDCCFA9), Color(0xFFC6B48D))))
        for (i in 1..4) {
            drawCircle(
                Color(0xFF8A744E).copy(alpha = 0.55f),
                radius = size.width * (0.10f + i * 0.09f),
                center = Offset(size.width * 0.44f, size.height * 0.46f),
                style = Stroke(width = size.width * 0.022f)
            )
        }
        drawCircle(Color(0xFF6B5636), radius = size.width * 0.05f, center = Offset(size.width * 0.44f, size.height * 0.46f))
    }
}

/** Shared look for the 6 catalog entries with no bespoke scene — a flat
 *  tint (stable per entry, from its ordinal, so it doesn't shift between
 *  recompositions) plus a centered icon distinguishing raster (globe —
 *  OpenStreetMap-family tile servers) from vector (layered stack icon —
 *  hosted MapLibre style JSON). */
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
