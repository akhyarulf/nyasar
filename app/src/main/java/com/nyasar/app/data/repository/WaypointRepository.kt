package com.nyasar.app.data.repository

import android.content.Context
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.db.WaypointCategory
import com.nyasar.app.data.db.WaypointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Single entry point for user-created waypoints (spec P3E2). Entirely
 * separate from [RouteRepository]/GPX parsing — a waypoint here is never
 * tied to a route file, it's a standalone pin the user dropped on the map.
 */
class WaypointRepository(context: Context) {

    private val dao = AppDatabase.get(context).waypointDao()

    fun observeAll(): Flow<List<WaypointEntity>> = dao.observeAll()

    suspend fun getById(id: String): WaypointEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    /** Waypoints created during [startEpochMs]..[endEpochMs] (P3E3 fix #2:
     *  ActivityDetail showing which waypoints belong to a recording
     *  session). See WaypointDao.getCreatedBetween for why this is a time
     *  window rather than a foreign key. */
    suspend fun getCreatedBetween(startEpochMs: Long, endEpochMs: Long): List<WaypointEntity> =
        withContext(Dispatchers.IO) {
            dao.getCreatedBetween(startEpochMs, endEpochMs)
        }

    /** Create — spec: tap map → Add Waypoint. */
    suspend fun create(
        name: String,
        category: WaypointCategory,
        lat: Double,
        lon: Double,
        elevationM: Double?,
        note: String?
    ): WaypointEntity = withContext(Dispatchers.IO) {
        val entity = WaypointEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            category = category.name,
            lat = lat,
            lon = lon,
            elevationM = elevationM,
            note = note?.ifBlank { null },
            createdAtEpochMs = System.currentTimeMillis()
        )
        dao.insert(entity)
        entity
    }

    /** Edit — name/category/note only; coordinates and elevation are fixed
     *  at creation time (this is "where I tapped", not a route point to
     *  reposition — repositioning isn't in scope for P3E2). */
    suspend fun update(waypoint: WaypointEntity, name: String, category: WaypointCategory, note: String?) {
        withContext(Dispatchers.IO) {
            dao.update(waypoint.copy(name = name, category = category.name, note = note?.ifBlank { null }))
        }
    }

    suspend fun delete(waypoint: WaypointEntity) = withContext(Dispatchers.IO) {
        dao.delete(waypoint)
    }
}
