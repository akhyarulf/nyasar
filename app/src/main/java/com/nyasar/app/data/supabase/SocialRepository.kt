package com.nyasar.app.data.supabase

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Write side of the browse social UI (like / comment — Fase 2+: Strava-style
 * kudos row on browse cards & route detail). Reads stay in [BrowseRepository]
 * (likes_count already flows through PublicRoute/RouteDetail); this class owns
 * the three writes the schema already prepared:
 *
 *  - route_likes: insert = like, delete = unlike (RLS: own rows only)
 *  - route_comments: insert + select (RLS: public read, own insert/delete)
 *
 * likes_count/comments_count on routes are maintained by the migration-0005
 * DB trigger + backfill, so NO client code ever writes the counters directly
 * (a client-maintained counter would race with concurrent likes).
 *
 * Pattern mirrors [BrowseRepository]: sealed Outcomes, no SDK exception ever
 * reaches the UI, every failure Log.e'd and classified.
 */
class SocialRepository {

    @Serializable
    private data class LikeRow(
        @SerialName("user_id") val userId: String,
        @SerialName("route_id") val routeId: String
    )

    @Serializable
    data class CommentRow(
        val id: String,
        @SerialName("route_id") val routeId: String,
        @SerialName("user_id") val userId: String,
        val content: String,
        @SerialName("created_at") val createdAt: String,
        val profiles: CommentProfile? = null
    ) {
        /** Hide-by-default when the author was hard-deleted (RLS-filtered). */
        val username: String? get() = profiles?.username
    }

    @Serializable
    data class CommentProfile(val username: String)

    sealed class ToggleOutcome {
        /** New authoritative like count AFTER the toggle (re-read from the
         *  route row — the trigger already applied the change). */
        data class Success(val likesCount: Int, val liked: Boolean) : ToggleOutcome()
        data class Failure(val error: BrowseRepository.BrowseError) : ToggleOutcome()
    }

    sealed class CommentsOutcome {
        data class Success(val comments: List<CommentRow>) : CommentsOutcome()
        data class Failure(val error: BrowseRepository.BrowseError) : CommentsOutcome()
    }

    sealed class PostOutcome {
        data class Success(val comment: CommentRow) : PostOutcome()
        data class Failure(val error: BrowseRepository.BrowseError) : PostOutcome()
    }

    /** Which likes belong to the signed-in user (heart filled on browse cards).
     *  Anonymous/signed-out users simply get an empty set — likes stay visible,
     *  only the toggle is unavailable (Strava behaves the same). */
    suspend fun fetchLikedRouteIds(client: SupabaseClient): Set<String> {
        val userId = client.auth.currentUserOrNull()?.id ?: return emptySet()
        return try {
            client.postgrest["route_likes"]
            .select(columns = Columns.list("route_id")) {
                filter { eq("user_id", userId) }
            }
            .decodeList<LikeIdRow>()
            .map { it.routeId }
                .toSet()
        } catch (e: Exception) {
            // Non-fatal: the browse list must still render; hearts just start
            // unfilled and the next toggle fixes them authoritatively.
            Log.e(TAG, "fetchLikedRouteIds failed: ${e.message}")
            emptySet()
        }
    }

    @Serializable
    private data class LikeIdRow(@SerialName("route_id") val routeId: String)

    /**
     * Like ↔ unlike toggle. Insert-first then delete-on-conflict would flash
     * error on the second tap; instead we ask the DB which state it is in
     * RIGHT NOW (single source of truth), then apply the opposite write.
     * PostgREST insert with a duplicate PK returns 409 → caught as "already
     * liked", treated as success-not-idempotent-race.
     */
    suspend fun toggleLike(client: SupabaseClient, routeId: String): ToggleOutcome {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return ToggleOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        return try {
            val liked = client.postgrest["route_likes"]
                .select(columns = Columns.list("route_id")) {
                    filter {
                        eq("user_id", userId)
                        eq("route_id", routeId)
                    }
                    limit(1)
                }
                .decodeList<LikeIdRow>()
                .isNotEmpty()

            if (liked) {
                client.postgrest["route_likes"].delete {
                    filter {
                        eq("user_id", userId)
                        eq("route_id", routeId)
                    }
                }
            } else {
                client.postgrest["route_likes"].insert(LikeRow(userId = userId, routeId = routeId))
            }

            // Authoritative post-toggle count (migration-0005 trigger keeps it).
            val count = client.postgrest["routes"]
                .select(columns = Columns.list("likes_count")) {
                    filter { eq("id", routeId) }
                    limit(1)
                }
                .decodeSingle<LikeCountRow>()
                .likesCount
            ToggleOutcome.Success(likesCount = count, liked = !liked)
        } catch (e: RestException) {
            Log.e(TAG, "toggleLike failed: ${e.message}", e)
            ToggleOutcome.Failure(classify(e))
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "toggleLike timeout: ${e.message}")
            ToggleOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "toggleLike network: ${e.message}")
            ToggleOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "toggleLike unexpected: ${e.message}", e)
            ToggleOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        }
    }

    suspend fun fetchComments(client: SupabaseClient, routeId: String): CommentsOutcome = try {
        val rows = client.postgrest["route_comments"]
            .select(columns = Columns.list(
                "id", "route_id", "user_id", "content", "created_at",
                "profiles!route_comments_user_id_fkey(username)"
            )) {
                filter { eq("route_id", routeId) }
                // idx_comments_route serves this exact (route_id, created_at desc).
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList<CommentRow>()
        CommentsOutcome.Success(rows)
    } catch (e: RestException) {
        Log.e(TAG, "fetchComments failed: ${e.message}", e)
        CommentsOutcome.Failure(classify(e))
    } catch (e: HttpRequestTimeoutException) {
        Log.e(TAG, "fetchComments timeout: ${e.message}")
        CommentsOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
    } catch (e: IOException) {
        Log.e(TAG, "fetchComments network: ${e.message}")
        CommentsOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
    } catch (e: Exception) {
        Log.e(TAG, "fetchComments unexpected: ${e.message}", e)
        CommentsOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
    }

    /** Post a comment. Server-side trim enforcement: PostgREST rejects an
     *  all-whitespace content only if the column has a CHECK; the UI layer
     *  never sends blank content anyway (send button disabled), this is the
     *  last line of defense. */
    suspend fun postComment(client: SupabaseClient, routeId: String, content: String): PostOutcome {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return PostOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return PostOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        }
        return try {
            val row = client.postgrest["route_comments"]
                .insert(CommentInsert(userId = userId, routeId = routeId, content = trimmed)) {
                    select(Columns.list(
                        "id", "route_id", "user_id", "content", "created_at",
                        "profiles!route_comments_user_id_fkey(username)"
                    ))
                }
                .decodeSingle<CommentRow>()
            PostOutcome.Success(row)
        } catch (e: RestException) {
            Log.e(TAG, "postComment failed: ${e.message}", e)
            PostOutcome.Failure(classify(e))
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "postComment timeout: ${e.message}")
            PostOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "postComment network: ${e.message}")
            PostOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "postComment unexpected: ${e.message}", e)
            PostOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        }
    }

    @Serializable
    private data class CommentInsert(
        @SerialName("user_id") val userId: String,
        @SerialName("route_id") val routeId: String,
        val content: String
    )

    @Serializable
    private data class LikeCountRow(@SerialName("likes_count") val likesCount: Int = 0)

    private fun classify(e: RestException): BrowseRepository.BrowseError =
        if (e.message?.contains("network", ignoreCase = true) == true) {
            BrowseRepository.BrowseError.NETWORK
        } else {
            BrowseRepository.BrowseError.UNKNOWN
        }

    companion object {
        private const val TAG = "SocialRepository"
    }
}
