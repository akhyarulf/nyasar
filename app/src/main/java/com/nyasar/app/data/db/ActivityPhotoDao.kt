package com.nyasar.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityPhotoDao {

    /** Observed (not one-shot) so the Photos grid updates live as photos
     *  are added/deleted, without ActivityDetailViewModel needing to
     *  re-run its full [ActivityDetailViewModel.load] just for a photo
     *  change (that reloads route/track/waypoints too — wasteful for
     *  what's a small, frequent, independent mutation). */
    @Query("SELECT * FROM activity_photos WHERE activityId = :activityId ORDER BY sortOrder ASC")
    fun observeForActivity(activityId: String): Flow<List<ActivityPhotoEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM activity_photos WHERE activityId = :activityId")
    suspend fun maxSortOrder(activityId: String): Int

    @Insert
    suspend fun insert(photo: ActivityPhotoEntity)

    @Delete
    suspend fun delete(photo: ActivityPhotoEntity)

    @Query("SELECT * FROM activity_photos WHERE activityId = :activityId ORDER BY sortOrder ASC")
    suspend fun getForActivity(activityId: String): List<ActivityPhotoEntity>

    /** Used only by ActivityPhotoRepository.deleteAllForActivity, which
     *  deletes each row's file first, then this — not relied on alone,
     *  since a bulk SQL delete alone would leak the on-disk files (spec
     *  §21 "delete Activity → photo associations ikut dibersihkan", which
     *  implies the files too, not just DB rows). */
    @Query("DELETE FROM activity_photos WHERE activityId = :activityId")
    suspend fun deleteAllForActivity(activityId: String)
}
