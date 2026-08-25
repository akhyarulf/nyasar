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

    /** Waypoints created during a time window (P3E3 fix #2: showing which
     *  waypoints were dropped during a specific recording session).
     *  WaypointEntity has no activityId column — deliberately not adding
     *  one for this, since a waypoint is a standalone map pin the user can
     *  create with no recording running at all (spec P3E2), not something
     *  that belongs to an activity. Time-window is an honest approximation:
     *  it's exactly the waypoints created while that activity was being
     *  recorded, not a guess. */
    @Query("SELECT * FROM waypoints WHERE createdAtEpochMs BETWEEN :startEpochMs AND :endEpochMs ORDER BY createdAtEpochMs ASC")
    suspend fun getCreatedBetween(startEpochMs: Long, endEpochMs: Long): List<WaypointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waypoint: WaypointEntity)

    @Update
    suspend fun update(waypoint: WaypointEntity)

    @Delete
    suspend fun delete(waypoint: WaypointEntity)
}
