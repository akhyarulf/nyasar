package com.nyasar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.StyleVariant

/**
 * GPX Studio-style basemap picker: bottom sheet with a scrolling grid of
 * thumbnail tiles (rounded thumbnail, name below, primary border + tinted
 * label for the active entry). Covers the full [BasemapEntry] catalog —
 * every entry is keyless so the whole list works out of the box.
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                stringResource(com.nyasar.app.R.string.map_types_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(com.nyasar.app.R.string.basemap_world_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)
        ) {
            items(BasemapEntry.entries, key = { it.id }) { entry ->
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
                        entry.label,
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
            // OpenFreeMap family: colored street-map looks with road grid.
            BasemapEntry.LIBERTY_TOPO -> streetsThumb(
                base = listOf(Color(0xFF44603F), Color(0xFF2C4230)),
                road = Color(0xFFE8C468),
                water = Color(0xFF4E7C8C)
            )
            BasemapEntry.POSITRON -> streetsThumb(
                base = listOf(Color(0xFFE8E8E4), Color(0xFFD5D5CF)),
                road = Color(0xFFF6F6F2),
                water = Color(0xFFA8C4D8)
            )
            BasemapEntry.BRIGHT -> streetsThumb(
                base = listOf(Color(0xFFF3EFE6), Color(0xFFE3DCCB)),
                road = Color(0xFFE8A13C),
                water = Color(0xFF7FAECB)
            )
            BasemapEntry.FIORD -> streetsThumb(
                base = listOf(Color(0xFF2B3247), Color(0xFF1A2032)),
                road = Color(0xFF7B89A8),
                water = Color(0xFF37506E)
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
            BasemapEntry.OPEN_HIKING -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFF4F1E4), Color(0xFFE4DFC9))))
                contours(Color(0xFF9C8B5A), 4)
                val trail = Path().apply {
                    moveTo(w * 0.08f, h * 0.85f)
                    cubicTo(w * 0.45f, h * 0.65f, w * 0.25f, h * 0.3f, w * 0.92f, h * 0.14f)
                }
                drawPath(trail, Color(0xFFC24E3A), style = Stroke(w * 0.06f, cap = StrokeCap.Round))
            }
            // Classic raster family looks.
            BasemapEntry.OPEN_TOPO_RASTER -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFDCCFA9), Color(0xFFC6B48D))))
                contours(Color(0xFF8A744E), 4)
                drawCircle(
                    Color(0xFF6B5636),
                    radius = w * 0.05f,
                    center = Offset(w * 0.44f, h * 0.46f)
                )
            }
            BasemapEntry.OSM_STANDARD -> streetsThumb(
                base = listOf(Color(0xFFF2EFE9), Color(0xFFE0DACE)),
                road = Color(0xFFF6D78A),
                water = Color(0xFFAAD3DF)
            )
            BasemapEntry.CYCLOSM -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFF4F2EA), Color(0xFFE2DFD2))))
                streetsThumbRoads(Color(0xFFD98F4E))
                val cycle = Path().apply {
                    moveTo(0f, h * 0.3f)
                    quadraticBezierTo(w * 0.5f, h * 0.16f, w, h * 0.34f)
                }
                drawPath(cycle, Color(0xFF4E7BC4), style = Stroke(w * 0.055f, cap = StrokeCap.Round))
            }
            BasemapEntry.ESRI_SATELLITE -> {
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
            BasemapEntry.CARTO_DARK -> streetsThumb(
                base = listOf(Color(0xFF232323), Color(0xFF151515)),
                road = Color(0xFF4A4A4A),
                water = Color(0xFF26343E)
            )
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
