package com.nyasar.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SharedSocialState
import com.nyasar.app.data.supabase.SocialRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Home tab's public-route browser (Fase 2 poin 3). Sealed
 * states — no boolean flags — matching AuthViewModel/PublishViewModel style.
 *
 * Search is debounced 350ms and browses server-side on every change; applied
 * filters + sort re-browse immediately. Supabase failures surface as
 * [BrowseUiError] mapped from the repository's [BrowseRepository.BrowseError],
 * so the UI never sees an SDK exception.
 *
 * Filters follow the Wikiloc Filters-sheet concept the user chose: edits
 * inside the sheet stay DRAFT until Apply (no network churn per slider
 * tick), Clear all resets the draft, and the Filters button badge shows the
 * APPLIED active count so it stays truthful while the sheet is open.
 */
class BrowseViewModel : ViewModel() {

    private val repository = BrowseRepository()
    private val socialRepository = SocialRepository()

    data class BrowseUiError(val messageRes: Int)

    sealed class BrowseState {
        data object Idle : BrowseState()
        data object Loading : BrowseState()
        data class Loaded(
            val routes: List<BrowseRepository.PublicRoute>,
            /** True when this result came from an active search query. */
            val fromSearch: Boolean
        ) : BrowseState()
        data class Error(val error: BrowseUiError) : BrowseState()
    }

    private val _state = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val state: StateFlow<BrowseState> = _state.asStateFlow()
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    /** Filter selections the sheet is currently editing — not yet applied. */
    private val _draftFilters = MutableStateFlow(BrowseRepository.BrowseFilters())
    val draftFilters = _draftFilters.asStateFlow()

    /** Last APPLIED filter set — what the list is actually showing. */
    private var appliedFilters = BrowseRepository.BrowseFilters()

    /** Reactive badge source: APPLIED active-filter count (updated only on
     *  apply/clear, so it stays truthful while sheet edits are still draft). */
    private val _appliedCount = MutableStateFlow(0)
    val appliedCount = _appliedCount.asStateFlow()
    private var sort: BrowseRepository.SortOrder = BrowseRepository.SortOrder.NEWEST

    /** Liked/bookmarked ids come from the PROCESS-WIDE [SharedSocialState]
     *  (realtime: a toggle on Saved/Route Detail updates these cards in the
     *  same frame — no restart, no stale fetch-once copy). Kept as val
     *  StateFlows so the screen's collectAsState() wiring is unchanged. */
    val likedIds = SharedSocialState.likedIds

    /** Route ids with an in-flight like toggle (prevents double-tap races
     *  and shows the tap registered instantly). */
    private val _likePending = MutableStateFlow<Set<String>>(emptySet())
    val likePending = _likePending.asStateFlow()

    val savedIds = SharedSocialState.savedIds

    private val _savePending = MutableStateFlow<Set<String>>(emptySet())
    val savePending = _savePending.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _query.debounce(350).collect { q -> browse(q) }
        }
        browse("")
        // First population of the shared social state — guarded so late
        // initializations (Saved tab opened first, etc.) don't refetch and
        // clobber a toggle that raced the fetch.
        viewModelScope.launch {
            if (SupabaseClientProvider.isConfigured && SharedSocialState.savedIds.value.isEmpty()) {
                SharedSocialState.reload(SupabaseClientProvider.client)
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }

    // ── Draft edits (Filters sheet) ──

    fun onDraftChanged(filters: BrowseRepository.BrowseFilters) {
        _draftFilters.value = filters
    }

    /** Apply: commit the draft and re-browse once. */
    fun applyFilters() {
        appliedFilters = _draftFilters.value
        _appliedCount.value = appliedFilters.activeCount
        browse(_query.value)
    }

    /** Clear all: reset draft AND applied, and re-browse immediately. */
    fun clearAllFilters() {
        _draftFilters.value = BrowseRepository.BrowseFilters()
        appliedFilters = _draftFilters.value
        _appliedCount.value = 0
        browse(_query.value)
    }

    fun onSortSelected(order: BrowseRepository.SortOrder) {
        sort = order
        browse(_query.value)
    }

    fun retry() = browse(_query.value)

    /** Current sort getter for the chip label (read-only UI state). */
    fun currentSort(): BrowseRepository.SortOrder = sort

    /** True when [draft] differs from the applied set — used to commit
     *  pending sheet edits on swipe-dismiss (Apply without pressing Apply). */
    fun isDirty(draft: BrowseRepository.BrowseFilters): Boolean = draft != appliedFilters

    /**
     * Like toggle from a card's action row: optimistic flip + count ±1
     * immediately (feels instant), then the server's authoritative count
     * replaces the guess. A failure rolls the optimistic change back.
     */
    fun toggleLike(route: BrowseRepository.PublicRoute) {
        if (!SupabaseClientProvider.isConfigured) return
        if (route.id in _likePending.value) return
        val wasLiked = route.id in likedIds.value
        // Optimistic — write through the shared state so Saved/Detail update too.
        SharedSocialState.onLikedToggled(route.id, !wasLiked)
        _state.update { current ->
            if (current is BrowseState.Loaded) {
                current.copy(routes = current.routes.map {
                    if (it.id == route.id) it.copy(likesCount = (it.likesCount + if (wasLiked) -1 else 1).coerceAtLeast(0)) else it
                })
            } else current
        }
        _likePending.value = _likePending.value + route.id
        viewModelScope.launch {
            when (val outcome = socialRepository.toggleLike(SupabaseClientProvider.client, route.id)) {
                is SocialRepository.ToggleOutcome.Success -> {
                    SharedSocialState.onLikedToggled(route.id, outcome.liked)
                    _state.update { current ->
                        if (current is BrowseState.Loaded) {
                            current.copy(routes = current.routes.map {
                                if (it.id == route.id) it.copy(likesCount = outcome.likesCount) else it
                            })
                        } else current
                    }
                }
                is SocialRepository.ToggleOutcome.Failure -> {
                    // Roll back the optimistic flip.
                    SharedSocialState.onLikedToggled(route.id, wasLiked)
                    _state.update { current ->
                        if (current is BrowseState.Loaded) {
                            current.copy(routes = current.routes.map {
                                if (it.id == route.id) it.copy(likesCount = route.likesCount) else it
                            })
                        } else current
                    }
                }
            }
            _likePending.value = _likePending.value - route.id
        }
    }

    /**
     * Save (bookmark) toggle from a card: optimistic flip with rollback on
     * failure — same contract as toggleLike minus the counter (saved_routes
     * is a private list; nothing to recount server-side).
     */
    fun toggleSave(route: BrowseRepository.PublicRoute) {
        if (!SupabaseClientProvider.isConfigured) return
        if (route.id in _savePending.value) return
        val wasSaved = route.id in savedIds.value
        // Optimistic — write through the shared state so Saved/Detail update too.
        SharedSocialState.onSavedToggled(route.id, !wasSaved)
        _savePending.value = _savePending.value + route.id
        viewModelScope.launch {
            val nowSaved = when (val outcome = socialRepository.toggleSave(SupabaseClientProvider.client, route.id)) {
                is SocialRepository.SaveOutcome.Success -> outcome.saved
                is SocialRepository.SaveOutcome.Failure -> wasSaved // roll back
            }
            SharedSocialState.onSavedToggled(route.id, nowSaved)
            _savePending.value = _savePending.value - route.id
        }
    }

    private fun browse(query: String) {
        viewModelScope.launch {
            _state.value = BrowseState.Loading
            if (!SupabaseClientProvider.isConfigured) {
                _state.value = BrowseState.Error(BrowseUiError(R.string.browse_error_not_configured))
                return@launch
            }
            when (val outcome = repository.browse(
                client = SupabaseClientProvider.client,
                query = query,
                filters = appliedFilters,
                sort = sort
            )) {
                is BrowseRepository.Outcome.Success -> _state.value = BrowseState.Loaded(
                    routes = outcome.routes,
                    fromSearch = query.isNotBlank()
                )
                is BrowseRepository.Outcome.Failure -> _state.value = BrowseState.Error(
                    BrowseUiError(errorResFor(outcome.error))
                )
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
