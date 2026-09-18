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
 * Write side of the browse social UI (Fase 4 — like / comment / save /
 * report). Reads stay in [BrowseRepository] (likes_count already flows
 * through PublicRoute/RouteDetail); this class owns the writes the schema
 * already prepared since schema_v1 (NO migration needed):
 *
 *  - route_likes: insert = like, delete = unlike (RLS: own rows only)
 *  - route_comments: insert + select + delete own (RLS: public read)
 *  - saved_routes: private bookmark list — insert/delete, RLS owner-only
 *  - reports: insert-only for users (RLS: reporter sees own, no update)
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

    /** Save (bookmark) toggle result — no counter, purely private. */
    sealed class SaveOutcome {
        data class Success(val saved: Boolean) : SaveOutcome()
        data class Failure(val error: BrowseRepository.BrowseError) : SaveOutcome()
    }

    sealed class DeleteCommentOutcome {
        data object Success : DeleteCommentOutcome()
        data class Failure(val error: BrowseRepository.BrowseError) : DeleteCommentOutcome()
    }

    sealed class ReportOutcome {
        data object Success : ReportOutcome()
        data class Failure(val error: BrowseRepository.BrowseError) : ReportOutcome()
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

    /** Which routes the signed-in user has bookmarked (Save/bookmark state
     *  on browse cards & route detail). Anonymous → empty set, same deal as
     *  likes: state hidden, not the content. */
    suspend fun fetchSavedRouteIds(client: SupabaseClient): Set<String> {
        val userId = client.auth.currentUserOrNull()?.id ?: return emptySet()
        return try {
            client.postgrest["saved_routes"]
                .select(columns = Columns.list("route_id")) {
                    filter { eq("user_id", userId) }
                }
                .decodeList<SavedIdRow>()
                .map { it.routeId }
                .toSet()
        } catch (e: Exception) {
            // Non-fatal: browse must render; bookmarks just start unfilled.
            Log.e(TAG, "fetchSavedRouteIds failed: ${e.message}")
            emptySet()
        }
    }

    @Serializable
    private data class SavedIdRow(@SerialName("route_id") val routeId: String)

    /** Save ↔ unsave toggle on saved_routes (Wikiloc "Save Trail" — a
     *  PRIVATE to-do list, unlike the public like). Read-state-then-write
     *  like toggleLike; PK (user_id, route_id) makes a duplicate insert a
     *  no-op error we treat as already-saved success. */
    suspend fun toggleSave(client: SupabaseClient, routeId: String): SaveOutcome {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return SaveOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        return try {
            val saved = client.postgrest["saved_routes"]
                .select(columns = Columns.list("route_id")) {
                    filter {
                        eq("user_id", userId)
                        eq("route_id", routeId)
                    }
                    limit(1)
                }
                .decodeList<SavedIdRow>()
                .isNotEmpty()

            if (saved) {
                client.postgrest["saved_routes"].delete {
                    filter {
                        eq("user_id", userId)
                        eq("route_id", routeId)
                    }
                }
            } else {
                client.postgrest["saved_routes"].insert(SavedRow(userId = userId, routeId = routeId))
            }
            SaveOutcome.Success(saved = !saved)
        } catch (e: RestException) {
            Log.e(TAG, "toggleSave failed: ${e.message}", e)
            SaveOutcome.Failure(classify(e))
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "toggleSave timeout: ${e.message}")
            SaveOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "toggleSave network: ${e.message}")
            SaveOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "toggleSave unexpected: ${e.message}", e)
            SaveOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        }
    }

    @Serializable
    private data class SavedRow(
        @SerialName("user_id") val userId: String,
        @SerialName("route_id") val routeId: String
    )

    /** Delete OWN comment (RLS: delete using auth.uid() = user_id). The UI
     *  only offers this on rows the signed-in user authored; the server
     *  would reject anything else anyway. The comments_count trigger also
     *  decrements on DELETE, keeping the detail header honest. */
    suspend fun deleteComment(client: SupabaseClient, commentId: String): DeleteCommentOutcome {
        return try {
            client.postgrest["route_comments"].delete {
                filter { eq("id", commentId) }
            }
            DeleteCommentOutcome.Success
        } catch (e: RestException) {
            Log.e(TAG, "deleteComment failed: ${e.message}", e)
            DeleteCommentOutcome.Failure(classify(e))
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "deleteComment timeout: ${e.message}")
            DeleteCommentOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "deleteComment network: ${e.message}")
            DeleteCommentOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "deleteComment unexpected: ${e.message}", e)
            DeleteCommentOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        }
    }

    /** Submit an abuse report for a route OR a comment (schema CHECK:
     *  exactly one target non-null). Fire-and-forget from the user's view —
     *  status stays 'open' for manual review; RLS gives reporters select on
     *  their own reports only. reason wire values come from the schema CHECK
     *  ('spam','misleading','offensive','danger','other'). */
    suspend fun submitReport(
        client: SupabaseClient,
        routeId: String? = null,
        commentId: String? = null,
        reason: String,
        note: String? = null
    ): ReportOutcome {
        if (routeId == null && commentId == null) {
            return ReportOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        }
        val userId = client.auth.currentUserOrNull()?.id
            ?: return ReportOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            client.postgrest["reports"].insert(
                ReportInsert(
                    reporterId = userId,
                    routeId = routeId,
                    commentId = commentId,
                    reason = reason,
                    note = trimmedNote
                )
            )
            ReportOutcome.Success
        } catch (e: RestException) {
            Log.e(TAG, "submitReport failed: ${e.message}", e)
            ReportOutcome.Failure(classify(e))
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "submitReport timeout: ${e.message}")
            ReportOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "submitReport network: ${e.message}")
            ReportOutcome.Failure(BrowseRepository.BrowseError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "submitReport unexpected: ${e.message}", e)
            ReportOutcome.Failure(BrowseRepository.BrowseError.UNKNOWN)
        }
    }

    @Serializable
    private data class ReportInsert(
        @SerialName("reporter_id") val reporterId: String,
        @SerialName("route_id") val routeId: String? = null,
        @SerialName("comment_id") val commentId: String? = null,
        val reason: String,
        val note: String? = null
    )

    /** Wire values of the schema_v1 CHECK constraint on reports.reason —
     *  single source of truth stays the DB; this mirrors it for the UI. */
    enum class ReportReason(val wire: String) {
        SPAM("spam"),
        MISLEADING("misleading"),
        OFFENSIVE("offensive"),
        DANGER("danger"),
        OTHER("other")
    }

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
