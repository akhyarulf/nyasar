package com.nyasar.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    @Query("SELECT * FROM activities WHERE status = 'completed' ORDER BY startedAtEpochMs DESC")
    fun observeCompleted(): Flow<List<ActivityEntity>>

    /** History + Saved pane (IA "History | Saved") — drafts ride along so
     *  an un-published Wikiloc-style draft is never invisible; the UI
     *  marks them with a chip. */
    @Query("SELECT * FROM activities WHERE status IN ('completed', 'draft') ORDER BY startedAtEpochMs DESC")
    fun observeCompletedAndDrafts(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getById(id: String): ActivityEntity?

    /** One-shot list for backup-all (Fase 3) — unlike observeCompleted
     *  this is a plain suspend read and includes non-completed rows:
     *  the schema deliberately allows backing up 'recording'/'paused'
     *  leftovers for crash recovery on restore. */
    @Query("SELECT * FROM activities ORDER BY startedAtEpochMs ASC")
    suspend fun getAllOnce(): List<ActivityEntity>

    /** Dipakai saat app dibuka kembali untuk cek apakah ada recording yang
     *  belum ditutup dengan benar (proses di-kill OS saat status masih
     *  "recording"/"paused"). Hanya ada 0 atau 1 activity aktif pada satu
     *  waktu — recording tidak mendukung multi-session paralel. */
    @Query("SELECT * FROM activities WHERE status IN ('recording', 'paused') LIMIT 1")
    suspend fun getActiveOrNull(): ActivityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(activity: ActivityEntity)

    @Update
    suspend fun update(activity: ActivityEntity)

    @Delete
    suspend fun delete(activity: ActivityEntity)

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert
    suspend fun insertPoint(point: ActivityPointEntity)

    /** Batch insert for restore (Fase 3) — one call per restored activity
     *  instead of thousands of suspend round-trips. */
    @Insert
    suspend fun insertPoints(points: List<ActivityPointEntity>)

    @Query("SELECT * FROM activity_points WHERE activityId = :activityId ORDER BY sequence ASC")
    suspend fun getPoints(activityId: String): List<ActivityPointEntity>

    @Query("SELECT COUNT(*) FROM activity_points WHERE activityId = :activityId")
    suspend fun getPointCount(activityId: String): Int

    /** Lat/lon only, not the full row — used for the History-list thumbnail
     *  where hundreds/thousands of rows per activity would otherwise be
     *  pulled into memory just to draw a small preview line. Kept as a
     *  separate lightweight query rather than reusing getPoints() +
     *  mapping, since Room can skip the unused columns entirely this way. */
    @Query("SELECT lat, lon FROM activity_points WHERE activityId = :activityId ORDER BY sequence ASC")
    suspend fun getLatLonOnly(activityId: String): List<LatLonRow>

    /** Tidak pakai Room FK cascade di sini — dihapus manual di repository
     *  bersamaan delete activity, konsisten dengan cara RouteRepository
     *  menghapus file GPX + row secara eksplisit (bukan mengandalkan FK). */
    @Query("DELETE FROM activity_points WHERE activityId = :activityId")
    suspend fun deletePointsForActivity(activityId: String)
}

/** Projection for [ActivityDao.getLatLonOnly] — Room maps query columns to
 *  this by name, doesn't need to be a full entity. */
data class LatLonRow(val lat: Double, val lon: Double)
