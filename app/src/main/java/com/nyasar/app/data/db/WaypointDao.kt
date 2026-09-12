package com.nyasar.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WaypointDao {

    @Query("SELECT * FROM waypoints ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM waypoints WHERE id = :id")
    suspend fun getById(id: String): WaypointEntity?

    /** Waypoints with NO link at all (spec P3E2 pins: not tied to a route
     *  or an activity). [NyasarMapView] draws these everywhere — a pin the
     *  user dropped from Home belongs on every map, unlike route/activity
     *  ones which are context-filtered by the showing screen. */
    @Query("SELECT * FROM waypoints WHERE linkedRouteId IS NULL AND linkedActivityId IS NULL ORDER BY createdAtEpochMs DESC")
    fun observeIndependent(): Flow<List<WaypointEntity>>

    /** Route-linked waypoints, for RoutePreview/Navigation contexts. */
    @Query("SELECT * FROM waypoints WHERE linkedRouteId = :routeId ORDER BY createdAtEpochMs ASC")
    fun observeForRoute(routeId: String): Flow<List<WaypointEntity>>

    /** Waypoints whose ACTIVITY link points at an activity recorded along
     *  [routeId] (activities.routeId). RoutePreview must include these: a
     *  pin dropped DURING a recording is activity-attached (the recording
     *  context seeds linkedActivityId, not linkedRouteId), and Route
     *  Viewer showing only route-linked rows is exactly the "pin muncul
     *  di Activity Detail tapi tidak di Route Viewer" asymmetry — same
     *  row, visible on one screen, missing on the other. */
    @Query(
        "SELECT w.* FROM waypoints w JOIN activities a ON w.linkedActivityId = a.id " +
            "WHERE a.routeId = :routeId ORDER BY w.createdAtEpochMs ASC"
    )
    fun observeForRouteActivities(routeId: String): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM waypoints WHERE linkedRouteId = :routeId ORDER BY createdAtEpochMs ASC")
    suspend fun getForRoute(routeId: String): List<WaypointEntity>

    /** Activity-linked waypoints, for History-detail style contexts. */
    @Query("SELECT * FROM waypoints WHERE linkedActivityId = :activityId ORDER BY createdAtEpochMs ASC")
    suspend fun getForActivity(activityId: String): List<WaypointEntity>

    /** Import dedup probe (v7): every GPX-origin row with one of the
     *  candidate names. RouteRepository narrows this to an exact
     *  name + coordinate-proximity match per incoming GPX waypoint, so
     *  re-importing the same file (even as a NEW route id — re-import
     *  always mints one) never double-creates its waypoints. */
    @Query("SELECT * FROM waypoints WHERE source = 'GPX' AND name IN (:names)")
    suspend fun getGpxWaypointsByNames(names: List<String>): List<WaypointEntity>

    /** Waypoints created during a time window (P3E3 fix #2: showing which
     *  waypoints were dropped during a specific recording session).
     *  Time-window remains an honest approximation for user pins (they
     *  have no activityId); activity-LINKED waypoints are queried by
     *  [getForActivity] instead. */
    @Query("SELECT * FROM waypoints WHERE createdAtEpochMs BETWEEN :startEpochMs AND :endEpochMs ORDER BY createdAtEpochMs ASC")
    suspend fun getCreatedBetween(startEpochMs: Long, endEpochMs: Long): List<WaypointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waypoint: WaypointEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(waypoints: List<WaypointEntity>)

    @Update
    suspend fun update(waypoint: WaypointEntity)

    @Delete
    suspend fun delete(waypoint: WaypointEntity)

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Route deleted (RouteRepository.delete): its GPX-imported waypoints
     *  ARE route data — they go with it. */
    @Query("DELETE FROM waypoints WHERE linkedRouteId = :routeId AND source = 'GPX'")
    suspend fun deleteGpxWaypointsForRoute(routeId: String)

    /** Route deleted: user pins merely LINKED to it are the user's own —
     *  they survive as independent waypoints instead of dangling. */
    @Query("UPDATE waypoints SET linkedRouteId = NULL WHERE linkedRouteId = :routeId AND source = 'USER'")
    suspend fun unlinkUserWaypointsForRoute(routeId: String)

    /** Activity deleted: linked pins survive as independent waypoints
     *  (same philosophy as the route unlink above). */
    @Query("UPDATE waypoints SET linkedActivityId = NULL WHERE linkedActivityId = :activityId")
    suspend fun unlinkWaypointsForActivity(activityId: String)
}
