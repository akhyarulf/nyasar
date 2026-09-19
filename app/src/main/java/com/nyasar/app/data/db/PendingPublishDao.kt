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

    /** Queue is keyed by sourceId — re-saving/re-queuing overwrites. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(row: PendingPublishEntity)

    /** Oldest first — publish order follows request order. Non-destructive
     *  SELECT: rows leave the queue only via dequeue. The flush fetches a
     *  bounded batch (draft rows are held back IN MEMORY, not here — a
     *  draft must never starve the completed rows behind it). */
    @Query("SELECT * FROM pending_publishes ORDER BY queuedAtEpochMs ASC LIMIT :limit")
    suspend fun takeOldestBatch(limit: Int): List<PendingPublishEntity>

    /** Direct row for one source — the draft editor's publish-now path.
     *  Also doubles as the activity metadata edit's write target: editing a
     *  published activity's form re-queues the fields here so any later
     *  flush re-publishes fresh data (AlreadyPublished short-circuit keeps
     *  it a single cloud row). */
    @Query("SELECT * FROM pending_publishes WHERE sourceId = :sourceId LIMIT 1")
    suspend fun takeOldestForSource(sourceId: String): PendingPublishEntity?

    @Query("SELECT COUNT(*) FROM pending_publishes")
    suspend fun count(): Int

    /** Success (or terminal rejection) — the source no longer owes the cloud anything. */
    @Query("DELETE FROM pending_publishes WHERE sourceId = :sourceId")
    suspend fun dequeue(sourceId: String)

    /** Discard/delete the source → its queue row must not outlive it. */
    @Query("DELETE FROM pending_publishes WHERE sourceId = :sourceId")
    suspend fun removeForSource(sourceId: String)
}
