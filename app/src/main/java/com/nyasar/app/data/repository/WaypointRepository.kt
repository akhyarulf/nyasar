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
 * Single entry point for waypoints — user-dropped pins (spec P3E2) AND the
 * waypoints merged in from imported GPX files (v7). Both live in the same
 * table so they are categorizable, editable and renderable through one
 * pipeline; the [WaypointEntity.source] column tells them apart where that
 * matters (import dedup, Navigation next-waypoint, activity listings).
 *
 * Attachment model: a waypoint MAY be linked to one route
 * ([WaypointEntity.linkedRouteId]) or one activity
 * ([WaypointEntity.linkedActivityId]) — or neither (independent). Consumers
 * therefore query by context: [observeIndependent] for pins that show on
 * every map, [observeForRoute]/[getForRoute] for route screens,
 * [getForActivity] for history screens.
 */
class WaypointRepository(context: Context) {

    private val dao = AppDatabase.get(context).waypointDao()

    fun observeAll(): Flow<List<WaypointEntity>> = dao.observeAll()

    fun observeIndependent(): Flow<List<WaypointEntity>> = dao.observeIndependent()

    fun observeForRoute(routeId: String): Flow<List<WaypointEntity>> =
        dao.observeForRoute(routeId)

    /** Waypoints linked to ACTIVITIES recorded along [routeId] — reactive
     *  counterpart of [getForActivity] from the route's perspective (see
     *  WaypointDao.observeForRouteActivities for why RoutePreview needs it). */
    fun observeForRouteActivities(routeId: String): Flow<List<WaypointEntity>> =
        dao.observeForRouteActivities(routeId)

    suspend fun getById(id: String): WaypointEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun getForRoute(routeId: String): List<WaypointEntity> = withContext(Dispatchers.IO) {
        dao.getForRoute(routeId)
    }

    suspend fun getForActivity(activityId: String): List<WaypointEntity> = withContext(Dispatchers.IO) {
        dao.getForActivity(activityId)
    }

    /** Waypoints created during [startEpochMs]..[endEpochMs] (P3E3 fix #2:
     *  ActivityDetail showing which waypoints belong to a recording
     *  session). See WaypointDao.getCreatedBetween for why this is a time
     *  window rather than a foreign key. */
    suspend fun getCreatedBetween(startEpochMs: Long, endEpochMs: Long): List<WaypointEntity> =
        withContext(Dispatchers.IO) {
            dao.getCreatedBetween(startEpochMs, endEpochMs)
        }

    /** Create — spec: tap map → Add Waypoint. The optional links decide the
     *  waypoint's attachment at creation time (context-aware default, user
     *  can override in the form before saving); null/null = independent. */
    suspend fun create(
        name: String,
        category: WaypointCategory,
        lat: Double,
        lon: Double,
        elevationM: Double?,
        note: String?,
        linkedRouteId: String? = null,
        linkedActivityId: String? = null
    ): WaypointEntity = withContext(Dispatchers.IO) {
        val entity = WaypointEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            category = category.name,
            lat = lat,
            lon = lon,
            elevationM = elevationM,
            note = note?.ifBlank { null },
            createdAtEpochMs = System.currentTimeMillis(),
            linkedRouteId = linkedRouteId,
            linkedActivityId = linkedActivityId,
            source = WaypointEntity.SOURCE_USER
        )
        dao.insert(entity)
        entity
    }

    /** Edit — name/category/note only; coordinates and elevation are fixed
     *  at creation time (this is "where I tapped", not a route point to
     *  reposition — repositioning isn't in scope for P3E2). */
    suspend fun update(waypoint: WaypointEntity, name: String, category: WaypointCategory, note: String?) {
        updateWithLinks(waypoint, name, category, note, waypoint.linkedRouteId, waypoint.linkedActivityId)
    }

    /** Edit that can also change the attachment (v7 form picker). Source is
     *  never editable here — a GPX row stays a GPX row. */
    suspend fun updateWithLinks(
        waypoint: WaypointEntity,
        name: String,
        category: WaypointCategory,
        note: String?,
        linkedRouteId: String?,
        linkedActivityId: String?
    ) {
        withContext(Dispatchers.IO) {
            dao.update(
                waypoint.copy(
                    name = name,
                    category = category.name,
                    note = note?.ifBlank { null },
                    linkedRouteId = linkedRouteId,
                    linkedActivityId = linkedActivityId
                )
            )
        }
    }

    suspend fun delete(waypoint: WaypointEntity) = withContext(Dispatchers.IO) {
        dao.delete(waypoint)
    }

    /** Cleanup when a route is deleted: GPX-imported waypoints ARE route
     *  data and go with it; user pins linked to it are unlinked (kept as
     *  independent) rather than destroyed — deleting a route must never
     *  silently delete the user's own content. */
    suspend fun onRouteDeleted(routeId: String) = withContext(Dispatchers.IO) {
        dao.deleteGpxWaypointsForRoute(routeId)
        dao.unlinkUserWaypointsForRoute(routeId)
    }

    /** Cleanup when an activity is deleted: linked pins survive as
     *  independent waypoints. */
    suspend fun onActivityDeleted(activityId: String) = withContext(Dispatchers.IO) {
        dao.unlinkWaypointsForActivity(activityId)
    }

    /**
     * Merges a parsed route file's waypoints ([GpxWaypoint]s) into this
     * table as [WaypointEntity] rows linked to [routeId] (v7: GPX waypoints
     * become first-class, categorizable waypoints like user pins).
     *
     * Dedup — the "import the same file twice" trap: re-import always mints
     * a NEW route id, so matching on linkedRouteId can never dedup across
     * re-imports. Instead each incoming waypoint is matched against every
     * GPX-origin row in the table by content: same name AND coordinates
     * within [COORD_MATCH_DEGREES] (~1 m — far tighter than any pin drop,
     * loose enough for GPX decimal rounding when a file is re-encoded).
     * Matching skips creation; everything else is inserted with the route
     * link and source=GPX.
     *
     * @param isBackfill true when filling in waypoints for an OLDER route
     *        (imported before this feature existed) — same content rules,
     *        so repeated previews can never stack duplicates either.
     * @return number of rows actually created (0 for a pure re-import).
     */
    suspend fun importFromGpx(
        routeId: String,
        waypoints: List<com.nyasar.app.gpx.model.GpxWaypoint>,
        isBackfill: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        if (waypoints.isEmpty()) return@withContext 0

        val existing = dao.getGpxWaypointsByNames(waypoints.map { it.name }.distinct())
        val toInsert = mutableListOf<WaypointEntity>()
        for (wp in waypoints) {
            val duplicate = existing.any {
                it.name == wp.name &&
                    kotlin.math.abs(it.lat - wp.lat) <= WaypointEntity.GPX_COORD_MATCH_DEGREES &&
                    kotlin.math.abs(it.lon - wp.lon) <= WaypointEntity.GPX_COORD_MATCH_DEGREES
            }
            if (duplicate) continue
            toInsert += WaypointEntity(
                id = UUID.randomUUID().toString(),
                name = wp.name,
                category = WaypointCategory.POI.name, // GPX has no category info — POI is the neutral default (user can recategorize)
                lat = wp.lat,
                lon = wp.lon,
                elevationM = wp.elevationM,
                note = wp.description?.ifBlank { null },
                createdAtEpochMs = System.currentTimeMillis(),
                linkedRouteId = routeId,
                linkedActivityId = null,
                source = WaypointEntity.SOURCE_GPX
            )
        }
        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        toInsert.size
    }

}
