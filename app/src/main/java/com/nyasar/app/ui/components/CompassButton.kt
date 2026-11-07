package com.nyasar.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyasar.app.ui.theme.NyasarElevation

/**
 * Kompas peta — menunjukkan ke arah mana peta sedang diputar relatif utara
 * sejati. Tap = kembali utara-up; long-press = ikuti arah hadap (heading-up).
 *
 * Tampilan sekarang mengikuti dial kompas aplikasi peta modern (referensi:
 * Strava), bukan ikon panah:
 * - cincin tipis sebagai badan dial, jadi widget terbaca "kompas" tanpa
 *   bingkai tebal;
 * - huruf N tegak di tengah sebagai label arah — rotasi hanya berlaku untuk
 *   jarum, jadi huruf tidak pernah terbaca terbalik saat peta diputar 180°;
 * - jarum utara merah ramping + ekor selatan yang lebih tenang, di dalam
 *   cincin supaya proporsinya tetap rapi di ukuran 44dp.
 *
 * Selalu terlihat (versi sebelumnya menghilang begitu peta utara-up, sehingga
 * kontrol di kolom kanan tiba-tiba berpindah tempat); saat utara-up ia hanya
 * meredup sebagai tanda "tidak ada yang perlu direset".
 *
 * bearingDeg adalah bearing KAMERA (derajat searah jarum jam dari utara
 * sejati), bukan arah hadap GPS user.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompassButton(
    bearingDeg: Float,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val normalized = ((bearingDeg % 360f) + 360f) % 360f
    val facingNorth = normalized < 1f || normalized > 359f

    val northColor = MaterialTheme.colorScheme.error
    val southColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurface

    val contentAlpha by animateFloatAsState(
        targetValue = if (facingNorth) 0.55f else 1f,
        label = "compassAlpha"
    )

    // Huruf N diukur sekali per style (bukan per frame) supaya Canvas tidak
    // mengukur teks di setiap redraw saat bearing berubah.
    val textMeasurer = rememberTextMeasurer()
    val northLabel = textMeasurer.measure(
        text = "N",
        style = TextStyle(
            color = labelColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    )

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = NyasarElevation.mapControlTonal,
        shadowElevation = NyasarElevation.mapControlShadow,
        modifier = modifier
            .size(44.dp)
            .alpha(contentAlpha)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val ringRadius = size.minDimension * 0.36f

                drawCircle(
                    color = ringColor.copy(alpha = 0.75f),
                    radius = ringRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx())
                )

                rotate(degrees = -normalized, pivot = Offset(cx, cy)) {
                    // Jarum utara — segitiga ramping menempel di cincin.
                    drawPath(
                        path = Path().apply {
                            moveTo(cx, cy - ringRadius * 0.98f)
                            lineTo(cx - ringRadius * 0.30f, cy - ringRadius * 0.54f)
                            lineTo(cx + ringRadius * 0.30f, cy - ringRadius * 0.54f)
                            close()
                        },
                        color = northColor,
                        style = Fill
                    )
                    // Ekor selatan — sengaja lebih tipis dan pudar: yang dicari
                    // user adalah utara, sisanya hanya penyeimbang visual.
                    drawPath(
                        path = Path().apply {
                            moveTo(cx, cy + ringRadius * 0.70f)
                            lineTo(cx - ringRadius * 0.20f, cy + ringRadius * 0.44f)
                            lineTo(cx + ringRadius * 0.20f, cy + ringRadius * 0.44f)
                            close()
                        },
                        color = southColor.copy(alpha = 0.5f),
                        style = Fill
                    )
                }

                drawText(
                    textLayoutResult = northLabel,
                    topLeft = Offset(
                        cx - northLabel.size.width / 2f,
                        cy - northLabel.size.height / 2f
                    )
                )
            }
        }
    }
}
