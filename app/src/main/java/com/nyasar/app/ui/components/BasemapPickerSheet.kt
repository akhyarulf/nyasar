package com.nyasar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nyasar.app.R
import com.nyasar.app.map.StyleVariant

/**
 * Strava-style basemap picker (redesign request): a bottom sheet with a
 * grid of thumbnail tiles — rounded thumbnail on top, name below, border +
 * tinted label marking the active choice — replacing the old text-only
 * DropdownMenu ("Standard/Satellite/Terrain/Ganti provider") on every map
 * screen.
 *
 * Scope per request: only the three variants that already exist. The tile
 * provider / style-variant persistence layers are untouched — callers keep
 * whatever state flow they already had and just receive a [StyleVariant].
 *
 * Thumbnails: generated procedurally with Canvas (gradient + a few
 * stylized shapes per style) instead of live tile snapshots — zero network
 * cost, zero assets, and visually representative of each style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasemapPickerSheet(
    selectedVariant: StyleVariant,
    onSelect: (StyleVariant) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.map_types_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(20.dp))

            val options = listOf(
                StyleVariant.OUTDOOR to R.string.layer_standard,
                StyleVariant.SATELLITE to R.string.layer_satellite,
                StyleVariant.TOPO to R.string.layer_terrain
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                options.forEach { (variant, labelRes) ->
                    val selected = variant == selectedVariant
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(variant) }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 1.dp,
                            border = if (selected) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            }
                        ) {
                            BasemapThumbnail(
                                variant = variant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Procedural thumbnail suggesting each map style:
 * - OUTDOOR: forest-green base, winding trail, small lake
 * - SATELLITE: dark canopy mosaic with a river
 * - TOPO: parchment base with concentric contour rings
 */
@Composable
private fun BasemapThumbnail(variant: StyleVariant, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (variant) {
            StyleVariant.OUTDOOR -> {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFF44603F), Color(0xFF2C4230))
                    )
                )
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
                drawPath(
                    trail,
                    Color(0xFFE8C468),
                    style = Stroke(width = size.width * 0.08f, cap = StrokeCap.Round)
                )
            }
            StyleVariant.SATELLITE -> {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFF2A4227), Color(0xFF162415))
                    )
                )
                drawCircle(
                    Color(0xFF3E5C2F),
                    radius = size.width * 0.22f,
                    center = Offset(size.width * 0.30f, size.height * 0.34f)
                )
                drawCircle(
                    Color(0xFF576D35),
                    radius = size.width * 0.18f,
                    center = Offset(size.width * 0.72f, size.height * 0.58f)
                )
                drawCircle(
                    Color(0xFF24401F),
                    radius = size.width * 0.26f,
                    center = Offset(size.width * 0.66f, size.height * 0.18f)
                )
                val river = Path().apply {
                    moveTo(0f, size.height * 0.78f)
                    quadraticBezierTo(
                        size.width * 0.5f, size.height * 0.52f,
                        size.width, size.height * 0.72f
                    )
                }
                drawPath(
                    river,
                    Color(0xFF35586D).copy(alpha = 0.95f),
                    style = Stroke(width = size.width * 0.07f)
                )
            }
            StyleVariant.TOPO -> {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFFDCCFA9), Color(0xFFC6B48D))
                    )
                )
                for (i in 1..4) {
                    drawCircle(
                        Color(0xFF8A744E).copy(alpha = 0.55f),
                        radius = size.width * (0.10f + i * 0.09f),
                        center = Offset(size.width * 0.44f, size.height * 0.46f),
                        style = Stroke(width = size.width * 0.022f)
                    )
                }
                drawCircle(
                    Color(0xFF6B5636),
                    radius = size.width * 0.05f,
                    center = Offset(size.width * 0.44f, size.height * 0.46f)
                )
            }
        }
    }
}
