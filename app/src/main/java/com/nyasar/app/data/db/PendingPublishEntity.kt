package com.nyasar.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Offline publish queue (konsep "save = publish", 2026-09): one row per
 * activity whose user chose to publish (or left the default on) but whose
 * upload could not complete at save time — no account yet, airplane mode
 * at the trailhead, or a mid-upload network drop.
 *
 * The activity itself is ALREADY in Room (save never depends on network);
 * this table only remembers "this activity still owes the cloud a routes
 * row". Rows are consumed by the flush job (login initial-sync / app-start
 * online flush) and deleted on success; a permanently-rejected publish
 * (server 4xx) is dropped with a log so one bad activity can never wedge
 * the queue.
 *
 * Form metadata captured at save time (difficulty/trailType/description/
 * isPublic) rides along so the deferred upload produces EXACTLY the row
 * the user filled in — no re-prompting later.
 */
@Entity(tableName = "pending_publishes")
data class PendingPublishEntity(
    @PrimaryKey val activityId: String, // ActivityEntity.id — one queue row per activity
    val difficulty: String?,            // schema enum literal or null (not rated)
    val difficultyDescription: String?,
    val trailType: String?,             // schema enum literal or null
    val description: String?,
    val isPublic: Boolean,
    val queuedAtEpochMs: Long
)
