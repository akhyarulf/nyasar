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
import androidx.compose.ui.unit.dp
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
    val (color, baseLabel) = when {
        gpsHealth == GpsHealth.LOST -> MaterialTheme.colorScheme.error to "⚠ GPS HILANG"
        gpsHealth == GpsHealth.WEAK -> WarningAmber to "⚠ GPS LEMAH"
        // Part 5 cosmetic fix (carried over): "MEMULAI…" implied recording was
        // already in progress while the user was still on the IDLE screen.
        // "SIAP" matches what's actually true at this point.
        status == RecordingStatus.IDLE -> MaterialTheme.colorScheme.outline to "SIAP"
        // Theme primary (not a hardcoded dark green): this Text sits on a tonal
        // Surface, so light mode gets the dark primary and dark mode the light
        // primary — 0xFF2E7D32 was near-invisible against the dark theme.
        status == RecordingStatus.RECORDING -> MaterialTheme.colorScheme.primary to "● RECORDING"
        status == RecordingStatus.PAUSED && isAutoPaused -> WarningAmber to "❚❚ DIJEDA OTOMATIS"
        status == RecordingStatus.PAUSED -> WarningAmber to "❚❚ DIJEDA"
        else -> MaterialTheme.colorScheme.outline to "SELESAI"
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}
