package com.nyasar.app.data.db

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Minimal category set from spec P3E2, each with a distinct icon+color so
 * markers are distinguishable on a small outdoor screen at a glance rather
 * than requiring a tap to tell them apart.
 */
enum class WaypointCategory(val label: String, val icon: ImageVector, val color: Color) {
    SUMMIT("Puncak", Icons.Default.Terrain, Color(0xFFD64545)),
    WATER("Sumber Air", Icons.Default.LocalDrink, Color(0xFF2979FF)),
    SHELTER("Shelter", Icons.Default.Cabin, Color(0xFF8D6E63)),
    CAMPSITE("Camp", Icons.Default.Home, Color(0xFF2E7D32)),
    DANGER("Bahaya", Icons.Default.Warning, Color(0xFFF2A900)),
    PARKING("Parkir", Icons.Default.DirectionsCar, Color(0xFF546E7A)),
    POI("POI", Icons.Default.LocationOn, Color(0xFF6A1B9A)),
    CUSTOM("Lainnya", Icons.Default.MoreHoriz, Color(0xFF424242));

    companion object {
        fun fromStorageValue(value: String): WaypointCategory =
            entries.firstOrNull { it.name == value } ?: CUSTOM
    }
}
