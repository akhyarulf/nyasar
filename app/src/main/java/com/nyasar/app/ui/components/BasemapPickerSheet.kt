package com.nyasar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nyasar.app.R
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.StyleVariant

/**
 * GPX Studio-style basemap picker: bottom sheet with a scrolling grid of
 * thumbnail tiles (rounded thumbnail, name below, primary border + tinted
 * label for the active entry), grouped into the same World / Countries
 * sections as gpx.studio's layer tree. Covers the full [BasemapEntry]
 * catalog — every entry is keyless so the whole list works out of the box.
 *
 * [selectedBasemap] wins when set; otherwise the active legacy
 * [StyleVariant] highlights its mapped catalog entry so the picker never
 * shows "nothing selected" right after install.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasemapPickerSheet(
    selectedBasemap: BasemapEntry?,
    selectedVariant: StyleVariant,
    onSelect: (BasemapEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val activeEntry: BasemapEntry = selectedBasemap ?: selectedVariant.toBasemapEntry()

    // Build the sectioned list once: (section header, null) rows followed
    // by that section's entries, mirroring GPX Studio's basemapTree order
    // (World first, then each country in catalog order).
    val sections = remember {
        val bySection = LinkedHashMap<String, MutableList<BasemapEntry>>()
        BasemapEntry.ordered.forEach { entry ->
            bySection.getOrPut(entry.section) { mutableListOf() }.add(entry)
        }
        val order = listOf("World").filter { bySection.containsKey(it) } +
            bySection.keys.filter { it != "World" }
        order.flatMap { section ->
            listOf<Pair<String, BasemapEntry?>>(section to null) +
                bySection.getValue(section).map { section to it }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.map_types_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            sections.forEach { (section, entry) ->
                if (entry == null) {
                    // Full-width section header row (World / Belgium / France / ...)
                    item(key = "header-$section", span = { GridItemSpan(3) }) {
                        Text(
                            text = sectionLabel(section),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    item(key = entry.gpxKey) {
                        val selected = entry == activeEntry
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(entry) }
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 1.dp,
                                border = if (selected) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                }
                            ) {
                                BasemapThumbnail(
                                    variant = entry,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                entry.gpxName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Country section names resolve through string resources; "World" too. */
@Composable
private fun sectionLabel(section: String): String = when (section) {
    "World" -> stringResource(R.string.basemap_section_world)
    "Belgium" -> stringResource(R.string.basemap_section_belgium)
    "Bulgaria" -> stringResource(R.string.basemap_section_bulgaria)
    "Finland" -> stringResource(R.string.basemap_section_finland)
    "France" -> stringResource(R.string.basemap_section_france)
    "New Zealand" -> stringResource(R.string.basemap_section_new_zealand)
    "Norway" -> stringResource(R.string.basemap_section_norway)
    "Spain" -> stringResource(R.string.basemap_section_spain)
    "Switzerland" -> stringResource(R.string.basemap_section_switzerland)
    "United Kingdom" -> stringResource(R.string.basemap_section_united_kingdom)
    "United States" -> stringResource(R.string.basemap_section_united_states)
    else -> section
}

/**
 * Procedural thumbnails per catalog family — zero network cost, zero
 * assets, visually representative of each basemap's look.
 */
@Composable
private fun BasemapThumbnail(variant: BasemapEntry, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (variant) {
            // GPX Studio hosted vector family (Liberty-derived looks).
            BasemapEntry.LIBERTY_TOPO -> streetsThumb(
                base = listOf(Color(0xFF44603F), Color(0xFF2C4230)),
                road = Color(0xFFE8C468),
                water = Color(0xFF4E7C8C)
            )
            BasemapEntry.LIBERTY_SATELLITE -> satelliteThumb()
            BasemapEntry.OSM -> streetsThumb(
                base = listOf(Color(0xFFE8E8E4), Color(0xFFD5D5CF)),
                road = Color(0xFFF6F6F2),
                water = Color(0xFFA8C4D8)
            )
            BasemapEntry.OSM_TOPO -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFEFF0E4), Color(0xFFDDE0C8))))
                contours(Color(0xFFB4A26B), 5)
                val road = Path().apply {
                    moveTo(0f, h * 0.82f)
                    quadraticBezierTo(w * 0.5f, h * 0.5f, w, h * 0.62f)
                }
                drawPath(road, Color(0xFFE09A6A), style = Stroke(w * 0.07f, cap = StrokeCap.Round))
            }
            BasemapEntry.UTAGAWA_VTT -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFEDEADF), Color(0xFFD8D4C0))))
                streetsThumbRoads(Color(0xFFD98F4E))
                val mtb = Path().apply {
                    moveTo(w * 0.1f, h * 0.75f)
                    cubicTo(w * 0.4f, h * 0.35f, w * 0.6f, h * 0.9f, w * 0.92f, h * 0.4f)
                }
                drawPath(mtb, Color(0xFFB0522D), style = Stroke(w * 0.06f, cap = StrokeCap.Round))
            }

            // Classic raster world basemaps.
            BasemapEntry.OSM_STANDARD -> streetsThumb(
                base = listOf(Color(0xFFF2EFE9), Color(0xFFE0DACE)),
                road = Color(0xFFF6D78A),
                water = Color(0xFFAAD3DF)
            )
            BasemapEntry.OPEN_TOPO_MAP -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFDCCFA9), Color(0xFFC6B48D))))
                contours(Color(0xFF8A744E), 4)
                drawCircle(
                    Color(0xFF6B5636),
                    radius = w * 0.05f,
                    center = Offset(w * 0.44f, h * 0.46f)
                )
            }
            BasemapEntry.OPEN_HIKING_MAP -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFF4F1E4), Color(0xFFE4DFC9))))
                contours(Color(0xFF9C8B5A), 4)
                val trail = Path().apply {
                    moveTo(w * 0.08f, h * 0.85f)
                    cubicTo(w * 0.45f, h * 0.65f, w * 0.25f, h * 0.3f, w * 0.92f, h * 0.14f)
                }
                drawPath(trail, Color(0xFFC24E3A), style = Stroke(w * 0.06f, cap = StrokeCap.Round))
            }
            BasemapEntry.CYCLOSM -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFF4F2EA), Color(0xFFE2DFD2))))
                streetsThumbRoads(Color(0xFFD98F4E))
                val cycle = Path().apply {
                    moveTo(0f, h * 0.3f)
                    quadraticBezierTo(w * 0.5f, h * 0.16f, w, h * 0.34f)
                }
                drawPath(cycle, Color(0xFF4E7BC4), style = Stroke(w * 0.055f, cap = StrokeCap.Round))
            }
            BasemapEntry.ESRI_SATELLITE -> satelliteThumb()

            // Country basemaps.
            BasemapEntry.IGN_BE -> streetsThumb(
                base = listOf(Color(0xFFE9EDE4), Color(0xFFD6DECF)),
                road = Color(0xFFC86A4A),
                water = Color(0xFF8FB6C9)
            )
            BasemapEntry.BG_MOUNTAINS -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFE7E3D2), Color(0xFFCFC7A9))))
                contours(Color(0xFF84795A), 5)
                drawLine(
                    Color(0xFF7A4E3A),
                    Offset(0f, h * 0.7f), Offset(w, h * 0.55f),
                    strokeWidth = w * 0.05f
                )
            }
            BasemapEntry.FINLAND_TOPO -> streetsThumb(
                base = listOf(Color(0xFFF1EEE3), Color(0xFFDFE0CC)),
                road = Color(0xFFE4B8C8),
                water = Color(0xFF9CC4E4)
            )
            BasemapEntry.IGN_FR_PLAN -> streetsThumb(
                base = listOf(Color(0xFFF6F2EA), Color(0xFFE8DEC9)),
                road = Color(0xFFE8A13C),
                water = Color(0xFF8CB4D8)
            )
            BasemapEntry.IGN_FR_TOPO -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFF2EBD8), Color(0xFFDFD3B2))))
                contours(Color(0xFFA08A5C), 5)
                drawLine(
                    Color(0xFFD8784A),
                    Offset(0f, h * 0.62f), Offset(w, h * 0.7f),
                    strokeWidth = w * 0.055f
                )
            }
            BasemapEntry.IGN_FR_SCAN25 -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFEFDDB5), Color(0xFFDCC491))))
                contours(Color(0xFF9A7C48), 5)
                drawCircle(
                    Color(0xFF7C6236),
                    radius = w * 0.06f,
                    center = Offset(w * 0.5f, h * 0.44f)
                )
            }
            BasemapEntry.IGN_FR_SATELLITE -> satelliteThumb()
            BasemapEntry.LINZ -> streetsThumb(
                base = listOf(Color(0xFFEAEFE8), Color(0xFFD5DFD4)),
                road = Color(0xFFD89C5A),
                water = Color(0xFF86B8DC)
            )
            BasemapEntry.LINZ_TOPO -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFE6EDDD), Color(0xFFCFDCC8))))
                contours(Color(0xFF8A9A78), 4)
                drawLine(
                    Color(0xFFC87850),
                    Offset(0f, h * 0.68f), Offset(w, h * 0.58f),
                    strokeWidth = w * 0.05f
                )
            }
            BasemapEntry.NORWAY_TOPO -> streetsThumb(
                base = listOf(Color(0xFFE8EEE9), Color(0xFFD3DED8)),
                road = Color(0xFFE0B060),
                water = Color(0xFF9CC8E8)
            )
            BasemapEntry.IGN_ES -> streetsThumb(
                base = listOf(Color(0xFFEFEBDD), Color(0xFFDCD5BE)),
                road = Color(0xFFD89050),
                water = Color(0xFF96BEE0)
            )
            BasemapEntry.IGN_ES_SATELLITE -> satelliteThumb()
            BasemapEntry.SWISSTOPO_RASTER -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFE4E8D8), Color(0xFFCBD4BC))))
                contours(Color(0xFF7E8A6A), 5)
                drawLine(
                    Color(0xFFD06048),
                    Offset(w * 0.05f, h * 0.8f),
                    Offset(w * 0.95f, h * 0.35f),
                    strokeWidth = w * 0.055f
                )
            }
            BasemapEntry.SWISSTOPO_VECTOR -> streetsThumb(
                base = listOf(Color(0xFFECEFE6), Color(0xFFD8DFD0)),
                road = Color(0xFFE0A050),
                water = Color(0xFF8CB8DC)
            )
            BasemapEntry.SWISSTOPO_SATELLITE -> satelliteThumb()
            BasemapEntry.ORDNANCE_SURVEY -> streetsThumb(
                base = listOf(Color(0xFFEDE6C8), Color(0xFFDFD4A8)),
                road = Color(0xFFC86A3A),
                water = Color(0xFF8FB4D4)
            )
            BasemapEntry.USGS -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFE8E4D4), Color(0xFFD2CCB2))))
                contours(Color(0xFF9A8E6A), 4)
                drawLine(
                    Color(0xFFB86A4A),
                    Offset(0f, h * 0.55f), Offset(w, h * 0.68f),
                    strokeWidth = w * 0.05f
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.streetsThumb(
    base: List<Color>,
    road: Color,
    water: Color
) {
    drawRect(Brush.verticalGradient(base))
    streetsThumbRoads(road)
    drawCircle(
        water,
        radius = size.width * 0.14f,
        center = Offset(size.width * 0.78f, size.height * 0.76f)
    )
}

/** Shared satellite look (Esri / IGN Fr / IGN Es / swisstopo / Liberty Satellite). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.satelliteThumb() {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2A4227), Color(0xFF162415))))
    drawCircle(
        Color(0xFF3E5C2F),
        radius = w * 0.22f,
        center = Offset(w * 0.30f, h * 0.34f)
    )
    drawCircle(
        Color(0xFF576D35),
        radius = w * 0.18f,
        center = Offset(w * 0.72f, h * 0.58f)
    )
    val river = Path().apply {
        moveTo(0f, h * 0.78f)
        quadraticBezierTo(w * 0.5f, h * 0.52f, w, h * 0.72f)
    }
    drawPath(river, Color(0xFF35586D).copy(alpha = 0.95f), style = Stroke(w * 0.07f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.streetsThumbRoads(road: Color) {
    val w = size.width
    val h = size.height
    // Simple road grid: two horizontals, two verticals.
    drawLine(road, Offset(0f, h * 0.35f), Offset(w, h * 0.30f), strokeWidth = w * 0.075f)
    drawLine(road, Offset(0f, h * 0.72f), Offset(w, h * 0.78f), strokeWidth = w * 0.05f)
    drawLine(road, Offset(w * 0.3f, 0f), Offset(w * 0.36f, h), strokeWidth = w * 0.06f)
    drawLine(road, Offset(w * 0.68f, 0f), Offset(w * 0.64f, h), strokeWidth = w * 0.045f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.contours(color: Color, rings: Int) {
    val w = size.width
    val h = size.height
    for (i in 1..rings) {
        drawCircle(
            color.copy(alpha = 0.55f),
            radius = w * (0.10f + i * (0.62f / rings)),
            center = Offset(w * 0.44f, h * 0.46f),
            style = Stroke(w * 0.022f)
        )
    }
}
