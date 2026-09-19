package com.nyasar.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Offline publish queue (konsep "save = publish", 2026-09): one row per
 * activity OR library route whose publish could not complete at request
 * time — no account yet, airplane mode at the trailhead, or a mid-upload
 * network drop.
 *
 * The source itself is ALREADY in Room (activity save / library save never
 * depend on network); this table only remembers "this source still owes
 * the cloud a routes row". Rows are consumed by the flush job (login
 * initial-sync / network-regain) and deleted on success; a
 * permanently-rejected publish (server 4xx) is dropped with a log so one
 * bad source can never wedge the queue.
 *
 * [sourceKind] picks the flush pipeline: "activity" → publish from
 * ActivityEntity + points + waypoints; "route" → publish the library
 * route's original GPX file (same split as the two PublishRepository
 * pipelines). Form metadata captured at request time
 * (difficulty/trailType/description/isPublic) rides along so the deferred
 * upload produces EXACTLY the row the user filled in — no re-prompting.
 */
@Entity(tableName = "pending_publishes")
data class PendingPublishEntity(
    @PrimaryKey val sourceId: String,   // ActivityEntity.id OR RouteEntity.id
    val sourceKind: String,             // "activity" | "route"
    val difficulty: String?,            // schema enum literal or null (not rated)
    val difficultyDescription: String?,
    val trailType: String?,             // schema enum literal or null
    val description: String?,
    val isPublic: Boolean,
    val queuedAtEpochMs: Long
) {
    companion object {
        const val KIND_ACTIVITY = "activity"
        const val KIND_ROUTE = "route"
    }
}
