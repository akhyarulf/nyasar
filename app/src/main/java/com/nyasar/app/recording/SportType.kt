package com.nyasar.app.recording

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.RunCircle
import androidx.compose.material.icons.filled.Sports
import androidx.compose.material.icons.filled.WheelchairPickup
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.ui.graphics.vector.ImageVector
import com.nyasar.app.R

/**
 * Metric utama yang ditonjolkan di share card per jenis olahraga.
 * Run/Trail Run/Walk → Pace (menit per km).
 * Hike/Wheelchair → Elevation Gain (total naik).
 * UNSPECIFIED → Pace juga (metric paling netral yang tersedia; ShareMetric
 * sengaja biner — ELEVATION terlalu terrain-spesifik untuk "belum ditentukan").
 */
enum class ShareMetric { PACE, ELEVATION }

/**
 * Jenis olahraga yang didukung Nyasar. Disimpan sebagai String (nama enum)
 * di Room, konsisten dengan pola status yang sudah ada di ActivityEntity.
 *
 * Label ditampilkan lewat [labelRes] (string resource) supaya ikut locale
 * yang aktif; [enLabel] disimpan sebagai fallback pencarian & identitas
 * internal yang stabil.
 */
enum class SportType(
    val labelRes: Int,
    val enLabel: String,
    val icon: ImageVector,
    val category: SportCategory,
    val primaryMetric: ShareMetric
) {
    // Not a sport — explicit "belum ditentukan" default (sinkron dengan
    // default UNSPECIFIED di sisi Supabase). Netral, bukan ikon/label
    // olahraga tertentu.
    UNSPECIFIED(R.string.sport_unspecified, "Unspecified", Icons.Default.HelpOutline, SportCategory.OTHER, ShareMetric.PACE),

    // Foot Sports
    RUN(R.string.sport_run, "Run", Icons.Default.RunCircle, SportCategory.FOOT, ShareMetric.PACE),
    TRAIL_RUN(R.string.sport_trail_run, "Trail Run", Icons.Default.Sports, SportCategory.FOOT, ShareMetric.PACE),
    WALK(R.string.sport_walk, "Walk", Icons.AutoMirrored.Filled.DirectionsWalk, SportCategory.FOOT, ShareMetric.PACE),
    HIKE(R.string.sport_hike, "Hike", Icons.Default.Hiking, SportCategory.FOOT, ShareMetric.ELEVATION),
    WHEELCHAIR(R.string.sport_wheelchair, "Wheelchair", Icons.Default.WheelchairPickup, SportCategory.FOOT, ShareMetric.ELEVATION),

    // Cycle Sports
    RIDE(R.string.sport_ride, "Ride", Icons.Default.DirectionsBike, SportCategory.CYCLE, ShareMetric.PACE);

    companion object {
        fun fromString(value: String?): SportType =
            entries.find { it.name == value } ?: UNSPECIFIED
    }
}

enum class SportCategory(val titleRes: Int) {
    FOOT(R.string.sport_category_foot),
    CYCLE(R.string.sport_category_cycle),
    // UNSPECIFIED tidak cocok masuk FOOT/CYCLE — kelompok "Lainnya".
    OTHER(R.string.sport_category_other)
}
