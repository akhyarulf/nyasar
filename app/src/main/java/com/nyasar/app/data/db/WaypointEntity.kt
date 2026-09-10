package com.nyasar.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A waypoint — either dropped by the USER on a map (spec P3E2) or imported
 * automatically from a route's GPX file (see [SOURCE_GPX]) — stored locally
 * in one table so both kinds are categorizable, editable, and renderable
 * through the same pipeline.
 *
 * Attachment model: a waypoint MAY be linked to one route ([linkedRouteId])
 * or one activity ([linkedActivityId]), or to neither (fully independent).
 * Exactly the flexibility the old "always independent" design lacked; both
 * columns are null for classic P3E2 pins, so pre-existing data needs no
 * backfill — only the v6→v7 column add.
 *
 * [source] distinguishes origin because several consumers must treat the
 * two kinds differently: import deduplication only considers GPX rows,
 * Navigation must not count a route's GPX waypoints twice (they arrive both
 * from the parsed file and now from this table), and an activity's
 * time-window listing must not swallow waypoints created by importing a GPX
 * mid-recording.
 */
@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey val id: String, // UUID, generated at creation time
    val name: String,
    val category: String, // WaypointCategory.name — stored as plain string, see WaypointCategory
    val lat: Double,
    val lon: Double,
    val elevationM: Double?, // null if unavailable at the moment of creation (e.g. no GPS fix yet)
    val note: String?,
    val createdAtEpochMs: Long,
    /** Route this waypoint belongs to, if any. Null = not route-linked. */
    val linkedRouteId: String? = null,
    /** Activity/recording this waypoint belongs to, if any. Null = not activity-linked. */
    val linkedActivityId: String? = null,
    /** Origin marker — [SOURCE_USER] (map tap) or [SOURCE_GPX] (auto-imported
     *  from a route file). DEFAULT 'USER' keeps the v6→v7 ALTER TABLE a pure
     *  column add: every pre-existing row is a user pin, no data rewrite. */
    @ColumnInfo(defaultValue = SOURCE_USER) val source: String = SOURCE_USER
) {
    companion object {
        const val SOURCE_USER = "USER"
        const val SOURCE_GPX = "GPX"

        /** ~0.000011° ≈ 1.1 m N-S — the coordinate tolerance for deciding
         *  "this DB row is the same physical pin as this GPX waypoint".
         *  Shared by the import dedup probe (WaypointRepository) and the
         *  RoutePreview merge-filter so both use one definition. */
        const val GPX_COORD_MATCH_DEGREES = 1e-5
    }
}
