package com.nyasar.app.data.supabase

import android.util.Log
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Storage-quota hygiene: deletes the gzip'ed GPX a user's route left in
 * Supabase Storage. Free-tier quota counts files, and NOTHING else removes
 * them — no FK connects storage.objects to the routes/auth tables (and
 * deleting the storage.objects ROW via SQL would not remove the actual file
 * anyway; per Supabase's own docs object deletion must go through the
 * Storage API). So every route deleted locally, and every account deleted
 * via delete_own_account(), used to leave its {uid}/{routeId}.gpx.gz files
 * behind forever, silently eating quota.
 *
 * Every call here is BEST-EFFORT: cleanup failures never block the
 * operation the user actually asked for (route deletion, account deletion),
 * and an already-absent object is treated as success. All failures are
 * swallowed by design — a leaked 10KB gpx.gz is a much smaller problem than
 * a route the user cannot delete.
 */
object GpxStorageCleanup {

    private const val TAG = "GpxStorageCleanup"

    /** The two buckets the publish pipeline writes to (migrations 0003/0006). */
    private val BUCKETS = listOf("route-gpx", "route-gpx-private")

    /**
     * Delete every GPX one route owns in both buckets. Path is rebuilt from
     * the owning user id + route id — the exact format uploadPipeline writes
     * ({uid}/{routeId}.gpx.gz) — so stored-URL encoding differences cannot
     * break the delete (same reasoning as BrowseRepository.storagePathFor).
     *
     * @param userId the row's routes_user_id (the uploader's auth uid)
     * @param cloudRouteId the SUPABASE routes.id — NOT the local Room id
     *        (activity publishes get a fresh server-side id)
     */
    suspend fun deleteRouteGpx(userId: String, cloudRouteId: String) {
        if (userId.isBlank() || cloudRouteId.isBlank()) return
        withContext(Dispatchers.IO) {
            val storage = SupabaseClientProvider.client.pluginManager.getPlugin(Storage)
            for (bucket in BUCKETS) {
                try {
                    storage[bucket].delete("$userId/$cloudRouteId.gpx.gz")
                    Log.i(TAG, "deleted $bucket/$userId/$cloudRouteId.gpx.gz")
                } catch (_: RestException) {
                    // 4xx (usually 404 — object absent; nothing to clean)
                } catch (_: HttpRequestTimeoutException) {
                    // offline — nothing we can do
                } catch (_: IOException) {
                    // network-level failure — nothing we can do
                } catch (e: Exception) {
                    // Any other transport error — same policy: never block
                    // the caller's operation over a cleanup miss.
                    Log.e(TAG, "cleanup failure ($bucket/$userId/$cloudRouteId)", e)
                }
            }
        }
    }

    /**
     * Resolve-and-delete for LOCAL deletions: the caller only knows a local
     * Room id, while the Storage path needs the SERVER routes.id (activity
     * publishes get a fresh server-side id). Probes the routes table via the
     * unique source indexes — source_route_id for library routes,
     * source_activity_id for recorded-activity publishes — then deletes the
     * files. Signed-out / offline / never-published all degrade to no-op.
     * Needs a session (RLS owner-only): a signed-out user's local deletion
     * was never able to reach a cloud row anyway.
     */
    suspend fun deleteForLocalSource(
        localRouteId: String? = null,
        localActivityId: String? = null
    ) {
        if (!SupabaseClientProvider.isConfigured) return
        try {
            val client = SupabaseClientProvider.client
            val userId = client.auth.currentUserOrNull()?.id ?: return
            val projection = io.github.jan.supabase.postgrest.query.Columns.list("id", "user_id")
            val rows = buildList {
                localRouteId?.let {
                    addAll(
                        client.postgrest["routes"].select(columns = projection) {
                            filter { eq("source_route_id", it) }
                        }.decodeList<CloudRouteRef>()
                    )
                }
                localActivityId?.let {
                    addAll(
                        client.postgrest["routes"].select(columns = projection) {
                            filter { eq("source_activity_id", it) }
                        }.decodeList<CloudRouteRef>()
                    )
                }
            }
            rows.forEach { deleteRouteGpx(it.userId, it.id) }
        } catch (e: Exception) {
            Log.e(TAG, "deleteForLocalSource probe failed (non-fatal)", e)
        }
    }

    /** Minimal projection for [deleteForLocalSource]. */
    @kotlinx.serialization.Serializable
    private data class CloudRouteRef(
        val id: String,
        @kotlinx.serialization.SerialName("user_id") val userId: String
    )
}
