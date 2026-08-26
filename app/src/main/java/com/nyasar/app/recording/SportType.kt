package com.nyasar.app.recording

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.RunCircle
import androidx.compose.material.icons.filled.Sports
import androidx.compose.material.icons.filled.WheelchairPickup
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Metric utama yang ditonjolkan di share card per jenis olahraga.
 * Run/Trail Run/Walk → Pace (menit per km).
 * Hike/Wheelchair → Elevation Gain (total naik).
 */
enum class ShareMetric { PACE, ELEVATION }

/**
 * Jenis olahraga yang didukung Nyasar. Disimpan sebagai String (nama enum)
 * di Room, konsisten dengan pola status yang sudah ada di ActivityEntity.
 */
enum class SportType(
    val label: String,
    val icon: ImageVector,
    val category: SportCategory,
    val primaryMetric: ShareMetric
) {
    // Foot Sports
    RUN("Run", Icons.Default.RunCircle, SportCategory.FOOT, ShareMetric.PACE),
    TRAIL_RUN("Trail Run", Icons.Default.Sports, SportCategory.FOOT, ShareMetric.PACE),
    WALK("Walk", Icons.AutoMirrored.Filled.DirectionsWalk, SportCategory.FOOT, ShareMetric.PACE),
    HIKE("Hike", Icons.Default.Hiking, SportCategory.FOOT, ShareMetric.ELEVATION),
    WHEELCHAIR("Wheelchair", Icons.Default.WheelchairPickup, SportCategory.FOOT, ShareMetric.ELEVATION),

    // Cycle Sports
    RIDE("Ride", Icons.Default.DirectionsBike, SportCategory.CYCLE, ShareMetric.PACE);

    companion object {
        fun fromString(value: String?): SportType =
            entries.find { it.name == value } ?: TRAIL_RUN
    }
}

enum class SportCategory(val displayName: String) {
    FOOT("Foot Sports"),
    CYCLE("Cycle Sports")
}
