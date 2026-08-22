package com.nyasar.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One imported GPX file, stored fully locally (spec section 16/18: no
 * cloud/route database in the MVP — GitHub/Nyasar Nyaman is only ever an
 * optional *source* of the .gpx file, never a runtime dependency).
 */
@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: String, // UUID, generated at import time
    val name: String,
    val localGpxFilePath: String, // copied into app-private storage on import
    val distanceMeters: Double,
    val elevationGainM: Double?,
    val elevationLossM: Double?,
    val highestElevationM: Double?,
    val lowestElevationM: Double?,
    val waypointCount: Int,
    val importedAtEpochMs: Long,
    val lastOpenedAtEpochMs: Long?
)
