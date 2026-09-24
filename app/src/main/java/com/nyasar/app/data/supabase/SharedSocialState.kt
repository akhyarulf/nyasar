package com.nyasar.app.data.supabase

import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide reactive state for the social surfaces (Fase 4): WHICH routes
 * the signed-in user has LIKED and SAVED (bookmarked).
 *
 * Why this exists (realtime requirement, 2026-09): Browse, Saved, and Public
 * Route Detail each used to own a private fetch-once copy of these sets, so
 * a toggle on one screen stayed invisible on the others until the user
 * restarted the app — save a route on Browse, open Profile→Saved: the new
 * bookmark wasn't there; unsave it from Saved, return to Browse: the card
 * still showed a filled bookmark. This object makes the WHOLE process one
 * subscriber of the truth:
 *
 *   - VMs READ [savedIds]/[likedIds] reactively (StateFlow collect).
 *   - EVERY successful toggle (Browse card, Saved card, Route Detail action
 *     row) writes through [onSavedToggled]/[onLikedToggled] — all subscribers
 *     update in the same frame, no screen ever goes stale.
 *   - [reload] re-fetches from Supabase on first sign-in, sign-out, and
 *     whenever a screen resumes without data — the server stays the
 *     single source of truth, this is only the in-process mirror.
 *
 * Anonymous users carry empty sets (same semantics the per-VM copies had:
 * state hidden, content visible, toggles routed to sign-in).
 */
object SharedSocialState {

    private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
    val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    /** Write-through after a successful save/bookmark toggle. */
    fun onSavedToggled(routeId: String, nowSaved: Boolean) {
        _savedIds.value = if (nowSaved) _savedIds.value + routeId else _savedIds.value - routeId
    }

    /** Merge a server list into the saved set WITHOUT dropping ids other
     *  screens toggled in since (union, never overwrite — see SavedViewModel). */
    fun syncSaved(serverIds: Set<String>) {
        _savedIds.value = _savedIds.value + serverIds
    }

    /** Write-through after a successful like toggle. */
    fun onLikedToggled(routeId: String, nowLiked: Boolean) {
        _likedIds.value = if (nowLiked) _likedIds.value + routeId else _likedIds.value - routeId
    }

    /**
     * Re-sync from Supabase (sign-in, sign-out, or a screen resuming with
     * unknown state). Never throws: failures keep the current mirror —
     * identical degradation to the per-VM fetches this replaces.
     *
     * Stale-fetch guard: the session is snapshotted BEFORE the fetches and
     * re-checked AFTER — without it a slow fetch from account A could land
     * after the user signed out/switched to account B (MainActivity's
     * SignedOut hook has already cleared the mirror) and resurrect A's
     * likes/bookmarks under B's session. Every concurrent reload writes
     * through this single generation counter, so only the fetch that ran
     * for the session that is STILL current at completion time commits.
     */
    suspend fun reload(client: SupabaseClient) {
        val generation = ++reloadGeneration
        val social = SocialRepository()
        val liked = social.fetchLikedRouteIds(client)
        val saved = social.fetchSavedRouteIds(client)
        if (generation != reloadGeneration) return
        _likedIds.value = liked
        _savedIds.value = saved
    }

    /** Bumped on every reload() entry AND by [clear] — see the stale-fetch
     *  guard on [reload]. Not atomic across threads, but all callers run
     *  on the main thread's coroutine context, so increments never race. */
    private var reloadGeneration: Int = 0

    /** Sign-out hygiene: bookmarks/likes of a previous account must not
     *  leak into the next session's icon states. Also invalidates any
     *  in-flight reload for the ended session (generation bump) so its
     *  result can never commit over the cleared mirror. */
    fun clear() {
        reloadGeneration++
        _savedIds.value = emptySet()
        _likedIds.value = emptySet()
    }
}
