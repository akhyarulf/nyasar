package com.nyasar.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Offline publish queue (Room v9) — see [PendingPublishEntity].
 * All methods suspend; the flush job runs on Dispatchers.IO.
 */
@Dao
interface PendingPublishDao {

    /** Queue is keyed by activityId — re-saving/re-queuing overwrites. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(row: PendingPublishEntity)

    /** Oldest first — publish order follows recording order. */
    @Query("SELECT * FROM pending_publishes ORDER BY queuedAtEpochMs ASC")
    suspend fun takeOldest(): PendingPublishEntity?

    @Query("SELECT COUNT(*) FROM pending_publishes")
    suspend fun count(): Int

    /** Success (or terminal rejection) — the activity no longer owes the cloud anything. */
    @Query("DELETE FROM pending_publishes WHERE activityId = :activityId")
    suspend fun dequeue(activityId: String)

    /** Discard/delete the activity → its queue row must not outlive it. */
    @Query("DELETE FROM pending_publishes WHERE activityId = :activityId")
    suspend fun removeForActivity(activityId: String)
}
