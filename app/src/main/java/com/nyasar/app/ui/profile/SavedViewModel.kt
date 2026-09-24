package com.nyasar.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.CloudSyncSignals
import com.nyasar.app.data.supabase.SharedSocialState
import com.nyasar.app.data.supabase.SupabaseClientProvider
import com.nyasar.app.data.supabase.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backing VM for the Profile → Saved tab (Fase 4 saved_routes UI): the
 * user's PRIVATE bookmark list of public routes, rendered with the same
 * [com.nyasar.app.ui.browse.PublicRouteCard] used on Browse so the two
 * surfaces stay visually identical.
 *
 * Unsave-from-list: the card's bookmark action deletes the row server-side
 * and the item is REMOVED from the list (with the same optimistic
 * pending-guard + rollback as BrowseViewModel.toggleSave) — you can't keep a
 * bookmark on a screen whose whole purpose is listing bookmarks.
 */
class SavedViewModel : ViewModel() {

    private val repository = BrowseRepository()
    private val socialRepository = SocialRepository()

    data class SavedUiError(val messageRes: Int)

    sealed class SavedState {
        data object Loading : SavedState()
        data class Loaded(val routes: List<BrowseRepository.PublicRoute>) : SavedState()
        data class Error(val error: SavedUiError) : SavedState()
    }

    private val _state = MutableStateFlow<SavedState>(SavedState.Loading)
    val state: StateFlow<SavedState> = _state.asStateFlow()

    /** routeIds with an in-flight toggle — anti double-tap, like Browse. */
    private val _savePending = MutableStateFlow<Set<String>>(emptySet())
    val savePending: StateFlow<Set<String>> = _savePending.asStateFlow()

    init {
        refresh()
        // AUTO-REFRESH (realtime requirement): a save from another device,
        // an unsave done on Browse (row deleted server-side), a like-count
        // change by another user, or the app returning from background —
        // all silently re-sync this list. refresh() is already non-blanking
        // (keeps the loaded list on failure) and in-flight-guarded.
        if (SupabaseClientProvider.isConfigured) {
            viewModelScope.launch {
                CloudSyncSignals.events.collect { _ -> refresh() }
            }
        }
    }

    /** In-flight guard: pane entry + VM init may both call refresh(). */
    private var refreshInFlight = false

    /**
     * Re-sync the saved list. Called on VM init AND on every pane entry
     * (SavedScreen's LaunchedEffect) — the realtime guarantee: a route
     * saved on Browse while another tab was open joins this list the
     * moment the tab is opened. Only surface the spinner when there is no
     * list to keep on screen; a background re-sync never blanks the UI.
     */
    fun refresh() {
        if (refreshInFlight) return
        refreshInFlight = true
        viewModelScope.launch {
            try {
                if (_state.value !is SavedState.Loaded) _state.value = SavedState.Loading
                if (!SupabaseClientProvider.isConfigured) {
                    if (_state.value !is SavedState.Loaded) {
                        _state.value = SavedState.Error(SavedUiError(R.string.browse_error_not_configured))
                    }
                    return@launch
                }
                when (val outcome = repository.savedRoutes(SupabaseClientProvider.client)) {
                    is BrowseRepository.Outcome.Success -> {
                        _state.value = SavedState.Loaded(routes = outcome.routes)
                        // Merge the freshly fetched list INTO the shared state
                        // (not overwrite — Browse may have just bookmarked a
                        // route that isn't on this screen's snapshot yet; the
                        // union keeps both surfaces truthful until the next
                        // authoritative reload).
                        SharedSocialState.syncSaved(outcome.routes.map { it.id }.toSet())
                    }
                    is BrowseRepository.Outcome.Failure -> {
                        // A failed re-sync keeps the loaded list (better than
                        // blanking it); only a first load surfaces the error.
                        if (_state.value !is SavedState.Loaded) {
                            _state.value = SavedState.Error(SavedUiError(errorResFor(outcome.error)))
                        }
                    }
                }
            } finally {
                // Exception-safe: a crashed fetch must never wedge the guard
                // shut (it would block every future pane-entry re-sync).
                refreshInFlight = false
            }
        }
    }

    /** Liked/bookmarked ids come from the PROCESS-WIDE [SharedSocialState]
     *  (realtime: a save on Browse appears here the moment this screen is
     *  open, and an unsave here clears Browse's filled bookmark instantly). */
    val savedIds: StateFlow<Set<String>> = SharedSocialState.savedIds
    val likedIds: StateFlow<Set<String>> = SharedSocialState.likedIds

    private val _likePending = MutableStateFlow<Set<String>>(emptySet())
    val likePending: StateFlow<Set<String>> = _likePending.asStateFlow()

    fun toggleLike(route: BrowseRepository.PublicRoute) {
        if (!SupabaseClientProvider.isConfigured) return
        if (route.id in _likePending.value) return
        val wasLiked = route.id in likedIds.value
        val originalCount = route.likesCount
        // Optimistic: flip icon + shift count once (never below zero).
        SharedSocialState.onLikedToggled(route.id, !wasLiked)
        _state.update { current ->
            if (current is SavedState.Loaded) {
                current.copy(routes = current.routes.map {
                    if (it.id == route.id) {
                        it.copy(likesCount = (it.likesCount + if (wasLiked) -1 else 1).coerceAtLeast(0))
                    } else it
                })
            } else current
        }
        _likePending.value = _likePending.value + route.id
        viewModelScope.launch {
            val nowLiked = when (val outcome = socialRepository.toggleLike(SupabaseClientProvider.client, route.id)) {
                is SocialRepository.ToggleOutcome.Success -> outcome.liked
                is SocialRepository.ToggleOutcome.Failure -> {
                    // Roll back BOTH the icon and the optimistic count shift.
                    _state.update { current ->
                        if (current is SavedState.Loaded) {
                            current.copy(routes = current.routes.map {
                                if (it.id == route.id) it.copy(likesCount = originalCount) else it
                            })
                        } else current
                    }
                    wasLiked
                }
            }
            SharedSocialState.onLikedToggled(route.id, nowLiked)
            _likePending.value = _likePending.value - route.id
        }
    }

    fun toggleSave(route: BrowseRepository.PublicRoute) {
        if (!SupabaseClientProvider.isConfigured) return
        if (route.id in _savePending.value) return
        _savePending.value = _savePending.value + route.id
        viewModelScope.launch {
            val nowSaved = when (val outcome = socialRepository.toggleSave(SupabaseClientProvider.client, route.id)) {
                is SocialRepository.SaveOutcome.Success -> outcome.saved
                is SocialRepository.SaveOutcome.Failure -> true // roll back to listed
            }
            // Write-through so Browse's bookmark icon flips in the same frame.
            SharedSocialState.onSavedToggled(route.id, nowSaved)
            _savePending.value = _savePending.value - route.id
            // Unsave removes the row from the list (WIkiloc-style: a Saved
            // screen only lists what is still bookmarked); a failed unsave
            // rolls back and the card stays.
            if (!nowSaved) {
                _state.update { current ->
                    if (current is SavedState.Loaded) {
                        current.copy(routes = current.routes.filterNot { it.id == route.id })
                    } else current
                }
            }
        }
    }

    private fun errorResFor(error: BrowseRepository.BrowseError): Int = when (error) {
        BrowseRepository.BrowseError.NOT_CONFIGURED -> R.string.browse_error_not_configured
        BrowseRepository.BrowseError.NETWORK -> R.string.browse_error_network
        BrowseRepository.BrowseError.NOT_FOUND -> R.string.browse_error_not_found
        BrowseRepository.BrowseError.NO_GPX_FILE -> R.string.browse_error_no_gpx
        BrowseRepository.BrowseError.UNKNOWN -> R.string.browse_error_unknown
    }
}
