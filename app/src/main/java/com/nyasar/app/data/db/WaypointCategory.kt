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
enum class WaypointCategory(val labelRes: Int, val icon: ImageVector, val color: Color) {
    SUMMIT(R.string.waypoint_cat_summit, Icons.Default.Terrain, Color(0xFFD64545)),
    WATER(R.string.waypoint_cat_water, Icons.Default.LocalDrink, Color(0xFF2979FF)),
    SHELTER(R.string.waypoint_cat_shelter, Icons.Default.Cabin, Color(0xFF8D6E63)),
    CAMPSITE(R.string.waypoint_cat_campsite, Icons.Default.Home, Color(0xFF2E7D32)),
    DANGER(R.string.waypoint_cat_danger, Icons.Default.Warning, Color(0xFFF2A900)),
    PARKING(R.string.waypoint_cat_parking, Icons.Default.DirectionsCar, Color(0xFF546E7A)),
    POI(R.string.waypoint_cat_poi, Icons.Default.LocationOn, Color(0xFF6A1B9A)),
    CUSTOM(R.string.waypoint_cat_custom, Icons.Default.MoreHoriz, Color(0xFF424242));

    companion object {
        fun fromStorageValue(value: String): WaypointCategory =
            entries.firstOrNull { it.name == value } ?: CUSTOM
    }
}
