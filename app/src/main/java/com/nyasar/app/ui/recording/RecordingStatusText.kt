package com.nyasar.app.ui.recording

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nyasar.app.R
import com.nyasar.app.recording.GpsHealth
import com.nyasar.app.recording.LocationProvider
import com.nyasar.app.recording.RecordingStatus
import com.nyasar.app.ui.theme.NyasarMotion
import com.nyasar.app.ui.theme.WarningAmber
import kotlin.math.roundToInt

/**
 * Theme-aware status chip text for the recording screen — GPS health takes
 * priority when degraded, per spec P3C ("RECORDING STATUS harus jelas: ...
 * GPS WEAK, GPS LOST"), because a weak/lost signal is the more urgent thing
 * for the user to notice while recording.
 *
 * Extracted from RecordingScreen's private StatusChip so the colors could be
 * fixed for dark mode: the original hardcoded dark green (0xFF2E7D32) was
 * nearly invisible on the dark theme's tonal Surface, and the amber
 * (0xFFF9A825) duplicated the [WarningAmber] token instead of using it.
 *
 * That duplicate StatusChip has since been deleted (2026-11) — it had no
 * callers left and had drifted from this composable. Labels now come from
 * string resources so the English build no longer shows Indonesian status
 * text.
 */
@Composable
fun RecordingStatusText(
    status: RecordingStatus,
    isAutoPaused: Boolean = false,
    gpsHealth: GpsHealth = GpsHealth.OK,
    gpsAccuracyMeters: Float? = null,
    gpsProvider: LocationProvider? = null
) {
    val gpsDetail = listOfNotNull(
        gpsAccuracyMeters?.let { "±${it.roundToInt()} m" },
        gpsProvider?.name
    ).joinToString(" · ")
    // Label diambil dari resources, bukan literal: sebelumnya semua teks di
    // sini hardcoded Bahasa Indonesia, jadi pengguna EN melihat "❚❚ DIJEDA"
    // di layar utama recording meski resources EN-nya sudah ada. Tren warna
    // tetap di sini (tema-aware) karena itu keputusan visual, bukan bahasa.
    val (color, baseLabel) = when {
        gpsHealth == GpsHealth.LOST -> MaterialTheme.colorScheme.error to stringResource(R.string.gps_lost)
        gpsHealth == GpsHealth.WEAK -> WarningAmber to stringResource(R.string.gps_weak)
        // "MEMULAI…" dulu menyiratkan recording sudah berjalan padahal
        // pengguna masih di layar IDLE — "SIAP/READY" yang benar.
        // onSurfaceVariant, bukan outline: pita di kepala kartu berlatar
        // surfaceContainerHighest, dan outline terlalu pudar di atasnya —
        // "READY" terbaca abu-abu mati di screenshot perangkat.
        status == RecordingStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.recording_status_ready)
        // Theme primary (not a hardcoded dark green): this Text sits on a tonal
        // Surface, so light mode gets the dark primary and dark mode the light
        // primary — 0xFF2E7D32 was near-invisible against the dark theme.
        status == RecordingStatus.RECORDING -> MaterialTheme.colorScheme.primary to stringResource(R.string.recording_status_recording)
        status == RecordingStatus.PAUSED && isAutoPaused -> WarningAmber to stringResource(R.string.recording_status_auto_paused)
        status == RecordingStatus.PAUSED -> WarningAmber to stringResource(R.string.recording_status_paused)
        else -> MaterialTheme.colorScheme.outline to stringResource(R.string.recording_status_finished)
    }
    val label = if (gpsDetail.isBlank() || status == RecordingStatus.IDLE) baseLabel else "$baseLabel · $gpsDetail"
    // Status transitions (SIAP -> RECORDING -> DIJEDA -> ...) animate with
    // the shared fast tween instead of hard-swapping text, matching every
    // other animated state change in the app.
    AnimatedContent(
        targetState = label,
        transitionSpec = {
            (fadeIn(animationSpec = tween(NyasarMotion.FAST_MS)))
                .togetherWith(
                    fadeOut(animationSpec = tween(NyasarMotion.FAST_MS))
                )
        },
        label = "statusChip"
    ) { animatedLabel ->
        Text(
            animatedLabel,
            color = color,
            // Tanpa padding samping: teks ini kini duduk langsung di pita
            // kepala kartu (padding horizontal diatur pita), bukan lagi chip
            // yang berdiri sendiri — 14dp samping membuatnya menjorok 28dp
            // dari tepi kartu dan tidak sejajar dengan judul lain.
            modifier = Modifier.padding(vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}
