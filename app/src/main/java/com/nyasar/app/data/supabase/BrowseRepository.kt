package com.nyasar.app.data.supabase

import android.util.Log
import com.nyasar.app.R
import com.nyasar.app.publish.PolylineEncoder
import com.nyasar.app.recording.SportType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
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
     *  menyentuhnya agar tetap hidup setelah migration jalan.
     *
     *  `profiles` comes from the SAME pinned FK embed as the detail select
     *  (`profiles!routes_user_id_fkey(username)`): verified live, the hint
     *  is required in LIST queries too (same PGRST201 ambiguity otherwise).
     *  Nested object + nullable (never non-null), mirroring [RouteDetail]:
     *  rows whose publisher was hard-deleted must still render — the card
     *  just hides the author pill. */
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
        @SerialName("created_at") val createdAt: String,
        val profiles: PublicProfile? = null
    ) { val username: String? get() = profiles?.username }

    /** Detail adds the publisher's username via the FK join to profiles —
     *  PostgREST nested-resource select with an explicit FK hint (see
     *  detail() — the bare "profiles" embed is ambiguous). userId is kept
     *  explicitly because the Storage download path is rebuilt from it
     *  (not parsed out of gpx_file_url). */
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

    /**
     * The full browse filter set (Wikiloc-style Filters sheet, draft rencana
     * filter di PROJECT_CONTEXT.md — sport type/difficulty/distance/loop-only,
     * TANPA "authors you follow" & "recorded" yang sengaja tidak diikuti).
     *
     * All constraints are applied SERVER-SIDE in browse() so pagination stays
     * honest. Wire values are schema CHECK constraints: difficulty/trail_type
     * from the enums below, sport_type from [SportType] enum NAMES (schema
     * check constraint copies that exact list — single source of truth).
     *
     * Range semantics: min 0 = no lower bound; max at [MAX_DISTANCE_KM] /
     * [MAX_GAIN_M] means "+" (open upper bound, no lte sent) — matching the
     * "+200 km" label convention of the Wikiloc sheet this mirrors.
     *
     * Elevation-gain filtering is STRICT: rows with NULL elevation_gain_m
     * (map-tap/drawn routes without elevation) do NOT match a gain range —
     * an unknown gain must not silently satisfy "100–2000 m". SQL null
     * comparison semantics give this for free (NULL >= x is NULL → filtered
     * out); verified live against PostgREST.
     */
    data class BrowseFilters(
        /** Multi-select (Wikiloc difficulty buttons). Empty = no constraint. */
        val difficulties: Set<DifficultyFilter> = emptySet(),
        /** Multi-select sport types; wire = enum names, e.g. "HIKE". Empty = all. */
        val sportTypes: Set<SportType> = emptySet(),
        /** Loop trails only — trail_type = 'loop'. */
        val loopOnly: Boolean = false,
        val distanceMinKm: Float = 0f,
        val distanceMaxKm: Float = MAX_DISTANCE_KM,
        val gainMinM: Float = 0f,
        val gainMaxM: Float = MAX_GAIN_M
    ) {
        /** Number of active filter choices for the Filters-button badge:
         *  per selected chip, plus 1 per adjusted range/toggle group. */
        val activeCount: Int
            get() = difficulties.size + sportTypes.size +
                (if (loopOnly) 1 else 0) +
                (if (distanceMinKm > 0f || distanceMaxKm < MAX_DISTANCE_KM) 1 else 0) +
                (if (gainMinM > 0f || gainMaxM < MAX_GAIN_M) 1 else 0)

        companion object {
            /** Slider ceilings; the max position is the OPEN bound ("+200 km"). */
            const val MAX_DISTANCE_KM = 200f
            const val MAX_GAIN_M = 2000f
        }
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
        filters: BrowseFilters = BrowseFilters(),
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
                    "likes_count", "comments_count", "created_at",
                    // Same FK-hinted embed as detail() — without the hint
                    // this list query is ALSO ambiguous (3 relationships
                    // routes<->profiles) and dies with PGRST201. Decodes into
                    // PublicRoute's nested `profiles` object; null-safe when
                    // the profile row is filtered/missing.
                    "profiles!routes_user_id_fkey(username)"
                )) {
                    filter {
                        // RLS already excludes non-public rows server-side;
                        // this constraint is belt-and-braces so a future RLS
                        // loosening can never leak drafts into the browse list.
                        eq("is_public", true)
                        eq("is_draft", false)
                        // Filters sheet (Wikiloc-style): every constraint
                        // server-side. isIn() = PostgREST in.(a,b) — verified
                        // live; ranges via gte/lte with open-upper at the
                        // slider ceilings (see BrowseFilters doc).
                        if (filters.difficulties.isNotEmpty()) {
                            isIn("difficulty", filters.difficulties.map { it.wire })
                        }
                        if (filters.sportTypes.isNotEmpty()) {
                            isIn("sport_type", filters.sportTypes.map { it.name })
                        }
                        if (filters.loopOnly) {
                            eq("trail_type", TrailTypeFilter.LOOP.wire)
                        }
                        if (filters.distanceMinKm > 0f) {
                            gte("distance_meters", (filters.distanceMinKm * 1000.0))
                        }
                        if (filters.distanceMaxKm < BrowseFilters.MAX_DISTANCE_KM) {
                            lte("distance_meters", (filters.distanceMaxKm * 1000.0))
                        }
                        // Strict gain range: NULL-gain rows never match
                        // (documented on BrowseFilters).
                        if (filters.gainMinM > 0f) {
                            gte("elevation_gain_m", filters.gainMinM.toDouble())
                        }
                        if (filters.gainMaxM < BrowseFilters.MAX_GAIN_M) {
                            lte("elevation_gain_m", filters.gainMaxM.toDouble())
                        }
                        // Search matches the route name only (case-insensitive
                        // ilike — must live INSIDE the filter scope). Strip
                        // PostgREST pattern delimiters so a typed query can't
                        // inject filter clauses. mountain_name/region are gone
                        // (Keputusan baru, migration 0004) and were never valid
                        // search targets in this layer.
                        if (query.isNotBlank()) {
                            val safe = query.trim().replace(",", "").replace("(", "").replace(")", "")
                            if (safe.isNotBlank()) {
                                ilike("name", "%$safe%")
                            }
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

    /** Saved/bookmark list (Fase 4 saved_routes): the user's PRIVATE
     *  to-do list of public routes, newest bookmark first. Join back to the
     *  same column set as browse() (FK-hinted profiles embed, same PGRST201
     *  reason) so the Saved screen can reuse [PublicRouteCard] verbatim.
     *  One round-trip — no fetch-ids-then-fetch-routes waterfalls. */
    suspend fun savedRoutes(client: SupabaseClient, limit: Int = 100): Outcome {
        if (!SupabaseClientProvider.isConfigured) {
            return Outcome.Failure(BrowseError.NOT_CONFIGURED)
        }
        return try {
            val result = client.postgrest["saved_routes"]
                .select(columns = Columns.list(
                    "created_at",
                    "routes!saved_routes_route_id_fkey(" +
                        "id, user_id, name, difficulty, trail_type, sport_type, " +
                        "distance_meters, elevation_gain_m, max_elevation_m, " +
                        "moving_time_ms, description, track_polyline, gpx_file_url, " +
                        "likes_count, comments_count, created_at, " +
                        "profiles!routes_user_id_fkey(username)"
                )) {
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<SavedRouteRow>()
                // route can legitimately be null here: RLS hides non-public
                // rows from the routes embed, so a bookmark whose route was
                // un-published/deleted decodes as null and is SKIPPED (never
                // a crash, never a ghost row) instead of failing the list.
                .mapNotNull { it.routes }
            Outcome.Success(result)
        } catch (e: RestException) {
            Log.e(TAG, "savedRoutes failed: ${e.message}", e)
            Outcome.Failure(BrowseError.UNKNOWN)
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "savedRoutes timeout: ${e.message}")
            Outcome.Failure(BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "savedRoutes network error: ${e.message}")
            Outcome.Failure(BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "savedRoutes unexpected: ${e.message}", e)
            Outcome.Failure(BrowseError.UNKNOWN)
        }
    }

    @Serializable
    private data class SavedRouteRow(
        @SerialName("route_id") val routeId: String,
        /** Null when the embed is filtered out by RLS (route un-published or
         *  deleted) — see the mapNotNull in savedRoutes(). */
        val routes: PublicRoute? = null
    )

    suspend fun detail(client: SupabaseClient, routeId: String): DetailOutcome {
        if (!SupabaseClientProvider.isConfigured) {
            return DetailOutcome.Failure(BrowseError.NOT_CONFIGURED)
        }
        return try {
            // Columns.list (comma-separated, NO spaces) — NOT Columns.raw:
            // raw with ", " produces PGRST100 "could not parse select
            // parameter" (PostgREST rejects whitespace after commas).
            //
            // The embed MUST carry the FK hint "profiles!routes_user_id_fkey":
            // routes has THREE relationships to profiles (the direct user_id
            // FK plus many-to-many via route_likes and saved_routes), so a
            // bare "profiles(username)" is rejected with PGRST201 "Could not
            // embed because more than one relationship was found" (HTTP 300;
            // supabase-kt 2.2.2 surfaces that as UnknownRestException
            // "Unknown error" — the real body never reached our logs until
            // reproduced via curl). The hint pins the DIRECT fk, which is
            // exactly the publisher-of-this-route join we want. profiles is
            // world-readable (public select policy, schema_v1.sql).
            val row = client.postgrest["routes"]
                .select(columns = Columns.list(
                    "id", "user_id", "name", "difficulty", "difficulty_description",
                    "trail_type", "sport_type", "distance_meters", "elevation_gain_m",
                    "elevation_loss_m", "max_elevation_m", "min_elevation_m", "moving_time_ms",
                    "description", "track_polyline", "gpx_file_url",
                    "likes_count", "comments_count", "created_at",
                    "profiles!routes_user_id_fkey(username)"
                )) {
                    filter { eq("id", routeId) }
                    limit(1)
                }
                .decodeSingle<RouteDetail>()
            DetailOutcome.Success(row)
        } catch (e: RestException) {
            // Message-based classification (same pattern as AuthRepository —
            // this supabase-kt line's RestException exposes error/description
            // strings, not a statusCode property). PostgREST answers a 0-row
            // single-object select with PGRST116 / "JSON object requested,
            // multiple (or no) rows returned" — i.e. the id is gone or hidden
            // by RLS, which for the user is simply "route not found".
            val msg = "${e.error} ${e.description ?: ""} ${e.message ?: ""}"
            Log.e(TAG, "detail failed: $msg", e)
            DetailOutcome.Failure(
                if (msg.contains("PGRST116", ignoreCase = true) ||
                    msg.contains("no rows", ignoreCase = true) ||
                    msg.contains("JSON object requested", ignoreCase = true)
                ) BrowseError.NOT_FOUND else BrowseError.UNKNOWN
            )
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
