package com.nyasar.app.recording

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.RunCircle
import androidx.compose.material.icons.filled.Sports
import androidx.compose.material.icons.filled.WheelchairPickup
import androidx.compose.material.icons.filled.Walk
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Jenis olahraga yang didukung Nyasar. Disimpan sebagai String (nama enum)
 * di Room, konsisten dengan pola status yang sudah ada di ActivityEntity.
 */
enum class SportType(
    val label: String,
    val icon: ImageVector,
    val category: SportCategory
) {
    // Foot Sports
    RUN("Run", Icons.Default.RunCircle, SportCategory.FOOT),
    TRAIL_RUN("Trail Run", Icons.Default.Hiking, SportCategory.FOOT),
    WALK("Walk", Icons.Default.Walk, SportCategory.FOOT),
    HIKE("Hike", Icons.Default.Hiking, SportCategory.FOOT),
    WHEELCHAIR("Wheelchair", Icons.Default.WheelchairPickup, SportCategory.FOOT),
    
    // Cycle Sports
    RIDE("Ride", Icons.Default.DirectionsBike, SportCategory.CYCLE);

    companion object {
        fun fromString(value: String?): SportType =
            entries.find { it.name == value } ?: TRAIL_RUN
    }
}

enum class SportCategory(val displayName: String) {
    FOOT("Foot Sports"),
    CYCLE("Cycle Sports")
}
