package com.nyasar.app.data.supabase

import android.util.Log
import com.nyasar.app.R
import com.nyasar.app.publish.PolylineEncoder
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Read side of Fase 2 (Publish / Share Rute Publik): browse, search, filter,
 * and detail-fetch of PUBLIC routes from Supabase Postgres, plus the "full
 * open" Download GPX path (fetch the gzip'ed original GPX from Storage —
 * PROJECT_CONTEXT.md Keputusan poin 6, Wikiloc-style openness, unlike Strava
 * which only exposes the lossy summary polyline).
 *
 * Pattern mirrors [AuthRepository]/[PublishRepository] exactly: every public
 * function returns a sealed [Outcome], NO SDK exception reaches the UI, and
 * every failure path is Log.e'd and classified into a [BrowseError].
 *
 * RLS (schema_v1.sql) does the access control server-side: anon/authenticated
 * users see rows where `is_public AND NOT is_draft` (plus their own). This
 * layer only adds sorting + display filtering, never its own access rules.
 */
class BrowseRepository {

    /** Route list row — the INSERT subset PublishRepository writes, mirrored
     *  back for reading (display columns only; stats reuse existing strings).
     *  NOTE: deliberately NO mountain_name/region — Keputusan baru
     *  (PROJECT_CONTEXT.md): kedua kolom itu dihapus dari `routes`
     *  (migration 0004, kode dulu baru SQL), jadi browse tidak boleh
     *  menyentuhnya agar tetap hidup setelah migration jalan. */
    @Serializable
    data class PublicRoute(
        val id: String,
        @SerialName("user_id") val userId: String,
        val name: String,
        val difficulty: String? = null,
        @SerialName("trail_type") val trailType: String? = null,
        @SerialName("sport_type") val sportType: String = "UNSPECIFIED",
        @SerialName("distance_meters") val distanceMeters: Double,
        @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
        @SerialName("max_elevation_m") val maxElevationM: Double? = null,
        @SerialName("moving_time_ms") val movingTimeMs: Long? = null,
        val description: String? = null,
        @SerialName("track_polyline") val trackPolyline: String,
        @SerialName("gpx_file_url") val gpxFileUrl: String? = null,
        @SerialName("likes_count") val likesCount: Int = 0,
        @SerialName("comments_count") val commentsCount: Int = 0,
        @SerialName("created_at") val createdAt: String
    )

    /** Detail adds the publisher's username via the FK join to profiles —
     *  PostgREST nested-resource select, same shape AuthRepository's joined
     *  profile queries return. userId is kept explicitly because the Storage
     *  download path is rebuilt from it (not parsed out of gpx_file_url). */
    @Serializable
    data class RouteDetail(
        val id: String,
        @SerialName("user_id") val userId: String,
        val name: String,
        val difficulty: String? = null,
        @SerialName("difficulty_description") val difficultyDescription: String? = null,
        @SerialName("trail_type") val trailType: String? = null,
        @SerialName("sport_type") val sportType: String = "UNSPECIFIED",
        @SerialName("distance_meters") val distanceMeters: Double,
        @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
        @SerialName("elevation_loss_m") val elevationLossM: Double? = null,
        @SerialName("max_elevation_m") val maxElevationM: Double? = null,
        @SerialName("min_elevation_m") val minElevationM: Double? = null,
        @SerialName("moving_time_ms") val movingTimeMs: Long? = null,
        val description: String? = null,
        @SerialName("track_polyline") val trackPolyline: String,
        @SerialName("gpx_file_url") val gpxFileUrl: String? = null,
        @SerialName("likes_count") val likesCount: Int = 0,
        @SerialName("comments_count") val commentsCount: Int = 0,
        @SerialName("created_at") val createdAt: String,
        val profiles: PublicProfile? = null
    )

    @Serializable
    data class PublicProfile(val username: String)

    /** Wire values are schema CHECK constraints — same set PublishRouteSheet's
     *  chips write, so browse filters round-trip exactly. labelRes REUSES the
     *  existing publish-sheet strings (same concepts, same words) instead of
     *  adding a second ID/EN label set for the identical vocabulary. */
    enum class DifficultyFilter(val wire: String, val labelRes: Int) {
        EASY("easy", R.string.publish_difficulty_easy),
        MODERATE("moderate", R.string.publish_difficulty_moderate),
        DIFFICULT("difficult", R.string.publish_difficulty_difficult),
        VERY_DIFFICULT("very_difficult", R.string.publish_difficulty_very_difficult);
        companion object { fun fromWire(v: String) = entries.firstOrNull { it.wire == v } }
    }

    enum class TrailTypeFilter(val wire: String, val labelRes: Int) {
        LOOP("loop", R.string.publish_trail_loop),
        OUT_AND_BACK("out_and_back", R.string.publish_trail_out_and_back),
        POINT_TO_POINT("point_to_point", R.string.publish_trail_point_to_point);
        companion object { fun fromWire(v: String) = entries.firstOrNull { it.wire == v } }
    }

    enum class SortOrder(val labelRes: Int) {
        NEWEST(R.string.browse_sort_newest),
        MOST_LIKED(R.string.browse_sort_most_liked),
        DISTANCE_ASC(R.string.browse_sort_distance_asc),
        DISTANCE_DESC(R.string.browse_sort_distance_desc)
    }

    sealed class Outcome {
        data class Success(val routes: List<PublicRoute>) : Outcome()
        data class Failure(val error: BrowseError) : Outcome()
    }

    sealed class DetailOutcome {
        data class Success(val route: RouteDetail) : DetailOutcome()
        data class Failure(val error: BrowseError) : DetailOutcome()
    }

    sealed class GpxOutcome {
        /** Raw decompressed GPX XML — the caller writes it to a cache file and
         *  shares it via the same FileProvider intent path the P3G export uses. */
        data class Success(val gpxXml: String, val fileName: String) : GpxOutcome()
        data class Failure(val error: BrowseError) : GpxOutcome()
    }

    enum class BrowseError {
        NOT_CONFIGURED,
        NETWORK,
        NOT_FOUND,
        NO_GPX_FILE,
        UNKNOWN
    }

    /**
     * Browse/search public routes. [query] matches the route `name` via
     * PostgREST ilike (search-by-name only — see the data-class note above:
     * mountain_name/region are scheduled for removal, migration 0004).
     */
    suspend fun browse(
        client: SupabaseClient,
        query: String = "",
        difficulty: DifficultyFilter? = null,
        trailType: TrailTypeFilter? = null,
        sort: SortOrder = SortOrder.NEWEST,
        limit: Int = 50
    ): Outcome {
        if (!SupabaseClientProvider.isConfigured) {
            return Outcome.Failure(BrowseError.NOT_CONFIGURED)
        }
        return try {
            val result = client.postgrest["routes"]
                .select(columns = Columns.list(
                    "id", "user_id", "name",
                    "difficulty", "trail_type", "sport_type",
                    "distance_meters", "elevation_gain_m", "max_elevation_m",
                    "moving_time_ms", "description",
                    "track_polyline", "gpx_file_url",
                    "likes_count", "comments_count", "created_at"
                )) {
                    filter {
                        // RLS already excludes non-public rows server-side;
                        // this constraint is belt-and-braces so a future RLS
                        // loosening can never leak drafts into the browse list.
                        eq("is_public", true)
                        eq("is_draft", false)
                        difficulty?.let { eq("difficulty", it.wire) }
                        trailType?.let { eq("trail_type", it.wire) }
                    }
                    if (query.isNotBlank()) {
                        // Search matches the route name only. mountain_name/
                        // region are gone (Keputusan baru, migration 0004) and
                        // were never valid search targets in this layer.
                        val safe = query.trim().replace(",", "").replace("(", "").replace(")", "")
                        if (safe.isNotBlank()) {
                            ilike("name", "%$safe%")
                        }
                    }
                    limit(limit.toLong())
                    when (sort) {
                        SortOrder.NEWEST -> order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                        SortOrder.MOST_LIKED -> order("likes_count", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                        SortOrder.DISTANCE_ASC -> order("distance_meters", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                        SortOrder.DISTANCE_DESC -> order("distance_meters", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    }
                }
                .decodeList<PublicRoute>()
            Outcome.Success(result)
        } catch (e: RestException) {
            Log.e(TAG, "browse failed: ${e.message}", e)
            Outcome.Failure(BrowseError.UNKNOWN)
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "browse timeout: ${e.message}")
            Outcome.Failure(BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "browse network error: ${e.message}")
            Outcome.Failure(BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "browse unexpected: ${e.message}", e)
            Outcome.Failure(BrowseError.UNKNOWN)
        }
    }

    suspend fun detail(client: SupabaseClient, routeId: String): DetailOutcome {
        if (!SupabaseClientProvider.isConfigured) {
            return DetailOutcome.Failure(BrowseError.NOT_CONFIGURED)
        }
        return try {
            val row = client.postgrest["routes"]
                .select(columns = Columns.raw(
                    "id, user_id, name, difficulty, difficulty_description, " +
                        "trail_type, sport_type, distance_meters, elevation_gain_m, elevation_loss_m, " +
                        "max_elevation_m, min_elevation_m, moving_time_ms, description, " +
                        "track_polyline, gpx_file_url, likes_count, comments_count, created_at, " +
                        "profiles(username)"
                )) {
                    filter { eq("id", routeId) }
                    limit(1)
                }
                .decodeSingle<RouteDetail>()
            DetailOutcome.Success(row)
        } catch (e: RestException) {
            Log.e(TAG, "detail failed: ${e.message}", e)
            DetailOutcome.Failure(if (e.statusCode == 404) BrowseError.NOT_FOUND else BrowseError.UNKNOWN)
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "detail timeout: ${e.message}")
            DetailOutcome.Failure(BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "detail network error: ${e.message}")
            DetailOutcome.Failure(BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "detail unexpected: ${e.message}", e)
            DetailOutcome.Failure(BrowseError.UNKNOWN)
        }
    }

    /**
     * Download GPX — "full open" (Keputusan poin 6). Fetches the gzip'ed
     * original GPX from the PUBLIC `route-gpx` bucket (no session needed) and
     * decompresses it with the platform GZIPInputStream. The object path is
     * rebuilt from the row's own ids ({userId}/{routeId}.gpx.gz — the exact
     * format PublishRepository.upload writes) so URL-encoding differences in
     * the stored gpx_file_url string can't break the download.
     */
    suspend fun downloadGpx(client: SupabaseClient, route: RouteDetail): GpxOutcome {
        if (!SupabaseClientProvider.isConfigured) {
            return GpxOutcome.Failure(BrowseError.NOT_CONFIGURED)
        }
        if (route.gpxFileUrl.isNullOrBlank()) {
            return GpxOutcome.Failure(BrowseError.NO_GPX_FILE)
        }
        return try {
            val storage = client.pluginManager.getPlugin(io.github.jan.supabase.storage.Storage)
            val bucket = storage["route-gpx"]
            val path = storagePathFor(route.userId, route.id)
            val bytes = bucket.downloadPublic(path)
            val gpx = java.util.zip.GZIPInputStream(bytes.inputStream()).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            GpxOutcome.Success(gpx, gpxFileNameFor(route))
        } catch (e: RestException) {
            // 404/400 from storage usually means the object is absent — e.g. a
            // row created before migration 0003 was applied. Classify so the
            // UI can say "file belum ada", not a generic network error.
            Log.e(TAG, "downloadGpx storage failed: ${e.message}", e)
            GpxOutcome.Failure(BrowseError.NO_GPX_FILE)
        } catch (e: java.util.zip.ZipException) {
            Log.e(TAG, "downloadGpx corrupt gzip: ${e.message}")
            GpxOutcome.Failure(BrowseError.NO_GPX_FILE)
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "downloadGpx timeout: ${e.message}")
            GpxOutcome.Failure(BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "downloadGpx network error: ${e.message}")
            GpxOutcome.Failure(BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "downloadGpx unexpected: ${e.message}", e)
            GpxOutcome.Failure(BrowseError.UNKNOWN)
        }
    }

    companion object {
        private const val TAG = "BrowseRepository"

        /** File name for the shared .gpx — same slug rules as GpxExporter's
         *  buildFileName so exports look consistent across features. */
        fun gpxFileNameFor(route: RouteDetail): String {
            val datePart = route.createdAt.take(10).replace("-", "") // ISO 8601 prefix
            val slug = route.name.lowercase()
                .replace(Regex("\\s+"), "-")
                .replace(Regex("[^a-z0-9-]"), "")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .ifBlank { "rute" }
            return "$slug-$datePart.gpx"
        }

        /** Storage object path for a route's GPX (mirror of the upload path). */
        fun storagePathFor(userId: String, routeId: String) = "$userId/$routeId.gpx.gz"

        /** Quick map preview for cards/detail without hitting the GPX file. */
        fun decodeTrack(polyline: String): List<com.nyasar.app.gpx.model.TrackPoint> =
            PolylineEncoder.decode(polyline).map { (lat, lon) ->
                com.nyasar.app.gpx.model.TrackPoint(lat = lat, lon = lon)
            }
    }
}
