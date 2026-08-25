package com.nyasar.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A waypoint the USER created by tapping the map (spec P3E2), stored
 * locally and independent of any GPX file. Deliberately a separate table
 * from route-imported waypoints (which live inside a route's .gpx file,
 * parsed on demand as [com.nyasar.app.gpx.model.GpxWaypoint] — see
 * RouteRepository/GpxParser) so this feature never touches that existing,
 * working path.
 */
@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey val id: String, // UUID, generated at creation time
    val name: String,
    val category: String, // WaypointCategory.name — stored as plain string, see WaypointCategory
    val lat: Double,
    val lon: Double,
    val elevationM: Double?, // null if unavailable at the moment of creation (e.g. no GPS fix yet)
    val note: String?,
    val createdAtEpochMs: Long
)
