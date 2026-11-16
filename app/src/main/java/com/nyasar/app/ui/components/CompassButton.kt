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
import androidx.compose.ui.unit.Dp
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
 *   cincin supaya proporsinya tetap rapi di ukuran 44–52dp.
 *
 * Selalu terlihat (versi sebelumnya menghilang begitu peta utara-up, sehingga
 * kontrol di kolom kanan tiba-tiba berpindah tempat); saat utara-up ia hanya
 * meredup sebagai tanda "tidak ada yang perlu direset" — 0.8, bukan 0.55:
 * redup 0.55 terbaca seperti tombol nonaktif di samping gear yang pekat.
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
    modifier: Modifier = Modifier,
    // Default 44dp: Home memakai ukuran lama. Layar rekam memakai 52dp agar
    // satu kolom dengan gear dan tombol peta di bawahnya. Nama `diameter`
    // dipakai — bukan `size` — karena `size` di dalam Canvas adalah ukuran
    // gambar, dan parameter bernama sama akan menutupinya.
    diameter: Dp = 44.dp
) {
    val normalized = ((bearingDeg % 360f) + 360f) % 360f
    val facingNorth = normalized < 1f || normalized > 359f

    val northColor = MaterialTheme.colorScheme.error
    val southColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurface

    val contentAlpha by animateFloatAsState(
        targetValue = if (facingNorth) 0.8f else 1f,
        label = "compassAlpha"
    )

    // Huruf N diukur sekali per style (bukan per frame) supaya Canvas tidak
    // mengukur teks di setiap redraw saat bearing berubah.
    val textMeasurer = rememberTextMeasurer()
    val northLabel = textMeasurer.measure(
        text = "N",
        style = TextStyle(
            color = labelColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    )

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = NyasarElevation.mapControlTonal,
        shadowElevation = NyasarElevation.mapControlShadow,
        modifier = modifier
            .size(diameter)
            .alpha(contentAlpha)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val ringRadius = size.minDimension * 0.42f

                drawCircle(
                    color = ringColor.copy(alpha = 0.7f),
                    radius = ringRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx())
                )

                rotate(degrees = -normalized, pivot = Offset(cx, cy)) {
                    // Jarum pendek berpangkal di PUSAT (bukan segitiga yang
                    // melayang dekat cincin): bentuknya jadi terbaca sebagai
                    // penunjuk arah, dan ujungnya berhenti di 0.55r supaya
                    // tidak menabrak huruf N di bagian atas dial.
                    drawPath(
                        path = Path().apply {
                            moveTo(cx, cy - ringRadius * 0.55f)
                            lineTo(cx - ringRadius * 0.18f, cy)
                            lineTo(cx + ringRadius * 0.18f, cy)
                            close()
                        },
                        color = northColor,
                        style = Fill
                    )
                    // Ekor selatan — lebih tipis dan pudar: yang dicari user
                    // adalah utara, sisanya hanya penyeimbang visual.
                    drawPath(
                        path = Path().apply {
                            moveTo(cx, cy + ringRadius * 0.48f)
                            lineTo(cx - ringRadius * 0.14f, cy)
                            lineTo(cx + ringRadius * 0.14f, cy)
                            close()
                        },
                        color = southColor.copy(alpha = 0.45f),
                        style = Fill
                    )
                }

                // Huruf N di bibir atas dial, tegak (tidak ikut rotasi jarum).
                drawText(
                    textLayoutResult = northLabel,
                    topLeft = Offset(
                        cx - northLabel.size.width / 2f,
                        cy - ringRadius * 0.86f - northLabel.size.height / 2f
                    )
                )
            }
        }
    }
}
