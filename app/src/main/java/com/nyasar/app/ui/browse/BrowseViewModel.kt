package com.nyasar.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * State for the Home tab's public-route browser (Fase 2 poin 3). Sealed
 * states — no boolean flags — matching AuthViewModel/PublishViewModel style.
 *
 * Search is debounced 350ms and browses server-side on every change; filters
 * + sort re-browse immediately. Supabase failures surface as [BrowseUiError]
 * mapped from the repository's [BrowseRepository.BrowseError], so the UI never
 * sees an SDK exception.
 */
class BrowseViewModel : ViewModel() {

    private val repository = BrowseRepository()

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

    private var difficulty: BrowseRepository.DifficultyFilter? = null
    private var trailType: BrowseRepository.TrailTypeFilter? = null
    private var sort: BrowseRepository.SortOrder = BrowseRepository.SortOrder.NEWEST

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _query.debounce(350).collect { q -> browse(q) }
        }
        browse("")
    }

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }

    fun onDifficultySelected(filter: BrowseRepository.DifficultyFilter?) {
        difficulty = if (difficulty == filter) null else filter
        browse(_query.value)
    }

    fun onTrailTypeSelected(filter: BrowseRepository.TrailTypeFilter?) {
        trailType = if (trailType == filter) null else filter
        browse(_query.value)
    }

    fun onSortSelected(order: BrowseRepository.SortOrder) {
        sort = order
        browse(_query.value)
    }

    fun retry() = browse(_query.value)

    /** Current filter/sort getters for the chip labels (read-only UI state). */
    fun currentDifficulty(): BrowseRepository.DifficultyFilter? = difficulty
    fun currentTrailType(): BrowseRepository.TrailTypeFilter? = trailType
    fun currentSort(): BrowseRepository.SortOrder = sort

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
                difficulty = difficulty,
                trailType = trailType,
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
