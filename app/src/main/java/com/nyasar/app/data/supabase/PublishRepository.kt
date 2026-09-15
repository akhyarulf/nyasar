package com.nyasar.app.data.supabase

import android.content.Context
import android.util.Log
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.ActivityPointEntity
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.gpx.GpxExporter
import com.nyasar.app.publish.PolylineEncoder
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Fase 2 publish pipeline (PROJECT_CONTEXT.md Keputusan poin 6 + Catatan
 * Teknis): ONE explicit user action turns a recorded activity into a public
 * `routes` row + a downloadable full-fidelity GPX file. Raw GPS points are
 * NEVER uploaded (local-first) — only the encoded polyline summary plus the
 * gzip'ed GPX in Storage.
 *
 * Steps, in order, with rollback:
 *   1. encode track_polyline (standard Google/Strava algorithm)
 *   2. INSERT the routes row (RETURNING id via select())
 *   3. serialize GPX 1.1 via GpxExporter (reuse, no rewrite) -> gzip (~10x)
 *      -> upload to the public `route-gpx` bucket at {userId}/{routeId}.gpx.gz
 *   4. backfill routes.gpx_file_url (best-effort; failure here is logged,
 *      not fatal — the file and row are already in place)
 * If step 3 fails, step 2's row is deleted so the unique source_activity_id
 * index stays reusable for a retry (schema_v1.sql idx_routes_source_activity).
 *
 * Error handling mirrors AuthRepository's contract: no SDK exception ever
 * escapes, every failure is classified into a sealed outcome the UI can
 * localize, and everything unexpected is Log.e'd.
 */
class PublishRepository {

    /** Everything the pipeline needs, pre-queried by the caller. */
    data class PublishInput(
        val activity: ActivityEntity,
        /** Ordered by sequence ASC (ActivityDao.getPoints order). */
        val points: List<ActivityPointEntity>,
        /** Activity-scoped waypoints only — same contract as GpxExporter. */
        val waypoints: List<WaypointEntity> = emptyList(),
        val mountainName: String? = null,
        val region: String? = null,
        /** One of schema's difficulty enum values or null (not rated). */
        val difficulty: String? = null,
        val difficultyDescription: String? = null,
        /** One of schema's trail_type enum values or null. */
        val trailType: String? = null,
        /** Free-form routes.description column. */
        val description: String? = null
    )

    sealed class PublishOutcome {
        data class Success(val routeId: String, val gpxUrl: String) : PublishOutcome()
        data class Failure(val error: PublishError) : PublishOutcome()
    }

    /** Neutral error categories the UI can localize (AuthError style). */
    enum class PublishError {
        NOT_CONFIGURED,       // Supabase credentials absent in this build
        NOT_SIGNED_IN,        // no session mid-flight (should not happen — UI gates it)
        EMPTY_TRACK,          // zero GPS points — nothing meaningful to publish
        ROUTE_INSERT_FAILED,  // DB rejected the routes row (RLS/validation/dup)
        STORAGE_UPLOAD_FAILED,// GPX upload failed (row was rolled back)
        NETWORK,              // connection problem / timeout / 5xx
        UNKNOWN
    }

    /** The routes row as INSERTed; only id is read back via RETURNING. */
    @Serializable
    private data class RouteInserted(val id: String)

    /** Exact column layout of schema_v1.sql `routes` (INSERT subset —
     *  likes_count/comments_count/is_public/is_draft/timestamps keep their
     *  defaults). @SerialName matches the snake_case columns. */
    @Serializable
    private data class RouteInsertRow(
        @SerialName("user_id") val userId: String,
        @SerialName("source_activity_id") val sourceActivityId: String?,
        @SerialName("source_route_id") val sourceRouteId: String?,
        val name: String,
        @SerialName("mountain_name") val mountainName: String?,
        val region: String?,
        val difficulty: String?,
        @SerialName("difficulty_description") val difficultyDescription: String?,
        @SerialName("trail_type") val trailType: String?,
        @SerialName("sport_type") val sportType: String,
        @SerialName("distance_meters") val distanceMeters: Double,
        @SerialName("elevation_gain_m") val elevationGainM: Double?,
        @SerialName("elevation_loss_m") val elevationLossM: Double?,
        @SerialName("max_elevation_m") val maxElevationM: Double?,
        @SerialName("min_elevation_m") val minElevationM: Double?,
        @SerialName("moving_time_ms") val movingTimeMs: Long,
        @SerialName("elapsed_time_ms") val elapsedTimeMs: Long,
        @SerialName("avg_speed_kmh") val avgSpeedKmh: Double?,
        @SerialName("max_speed_kmh") val maxSpeedKmh: Double?,
        @SerialName("track_polyline") val trackPolyline: String,
        @SerialName("start_lat") val startLat: Double?,
        @SerialName("start_lng") val startLng: Double?,
        @SerialName("gpx_file_url") val gpxFileUrl: String?,
        val description: String?
    )

    suspend fun publish(context: Context, input: PublishInput): PublishOutcome {
        if (!SupabaseClientProvider.isConfigured) {
            return PublishOutcome.Failure(PublishError.NOT_CONFIGURED)
        }
        val client = SupabaseClientProvider.client
        val userId = client.auth.currentUserOrNull()?.id
            ?: return PublishOutcome.Failure(PublishError.NOT_SIGNED_IN)
        if (input.points.isEmpty()) return PublishOutcome.Failure(PublishError.EMPTY_TRACK)

        // 1+2: polyline + routes INSERT (RETURNING id).
        val row = try {
            val elevations = input.points.mapNotNull { it.elevationM }
            client.postgrest["routes"].insert(
                RouteInsertRow(
                    userId = userId,
                    sourceActivityId = input.activity.id,
                    sourceRouteId = null,
                    name = input.activity.name,
                    mountainName = input.mountainName?.trim()?.takeIf { it.isNotEmpty() },
                    region = input.region?.trim()?.takeIf { it.isNotEmpty() },
                    difficulty = input.difficulty,
                    difficultyDescription = input.difficultyDescription?.trim()
                        ?.takeIf { it.isNotEmpty() },
                    trailType = input.trailType,
                    sportType = input.activity.sportType,
                    distanceMeters = input.activity.distanceMeters,
                    elevationGainM = input.activity.elevationGainM,
                    elevationLossM = input.activity.elevationLossM,
                    // Same on-the-fly source as elevationSummary in
                    // ActivityDetailViewModel — the raw points, not a
                    // separate stored field (PROJECT_CONTEXT Catatan 2).
                    maxElevationM = elevations.maxOrNull(),
                    minElevationM = elevations.minOrNull(),
                    movingTimeMs = input.activity.movingTimeMs,
                    elapsedTimeMs = input.activity.elapsedTimeMs,
                    avgSpeedKmh = input.activity.avgSpeedKmh,
                    maxSpeedKmh = input.activity.maxSpeedKmh,
                    trackPolyline = PolylineEncoder.encode(
                        input.points.map { it.lat to it.lon }
                    ),
                    startLat = input.points.first().lat,
                    startLng = input.points.first().lon,
                    gpxFileUrl = null,
                    description = input.description?.trim()?.takeIf { it.isNotEmpty() }
                )
            ) {
                // RETURNING id — Storage path needs the generated route id.
                select()
            }.decodeSingleOrNull<RouteInserted>()
        } catch (e: RestException) {
            Log.e(TAG, "routes insert failed: ${e.error} ${e.description ?: ""}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "routes insert failed", e)
            null
        } ?: return PublishOutcome.Failure(PublishError.ROUTE_INSERT_FAILED)

        // 3: GPX (reusing GpxExporter verbatim) -> gzip -> Storage.
        val path = "$userId/${row.id}.gpx.gz"
        val gpxUrl = try {
            withContext(Dispatchers.IO) {
                val file = GpxExporter.exportActivity(
                    context, input.activity, input.points, input.waypoints
                )
                val gzipped = gzip(file.readBytes())
                file.delete()
                // upload() returns the object path, not a URL — build the
                // public URL explicitly (bucket `route-gpx` is public per
                // supabase/migrations/0003_route_gpx_bucket.sql).
                client.storage["route-gpx"].upload(path, gzipped)
                client.storage["route-gpx"].publicUrl(path)
            }
        } catch (e: RestException) {
            Log.e(TAG, "gpx upload failed: ${e.error} ${e.description ?: ""}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "gpx upload failed", e)
            null
        }
        if (gpxUrl == null) {
            // Rollback so a retry is possible: the unique source_activity_id
            // index would otherwise reject the second attempt forever.
            try {
                client.postgrest["routes"].delete { filter { eq("id", row.id) } }
            } catch (e: Exception) {
                Log.e(TAG, "rollback of routes row ${row.id} failed", e)
            }
            return PublishOutcome.Failure(PublishError.STORAGE_UPLOAD_FAILED)
        }

        // 4: backfill gpx_file_url — best-effort. The route is already fully
        // usable without the column (track_polyline drives in-app navigation);
        // the file itself is reachable under its deterministic path.
        try {
            client.postgrest["routes"].update(
                update = { set("gpx_file_url", gpxUrl) }
            ) {
                filter { eq("id", row.id) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "gpx_file_url backfill failed (non-fatal) for ${row.id}", e)
        }

        return PublishOutcome.Success(routeId = row.id, gpxUrl = gpxUrl)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> gz.write(bytes) }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "PublishRepository"
    }
}
