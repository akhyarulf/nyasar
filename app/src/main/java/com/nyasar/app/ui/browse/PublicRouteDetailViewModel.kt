package com.nyasar.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import com.nyasar.app.gpx.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for a single PUBLIC route's detail screen (Fase 2 slice 2):
 * loads the row + publisher username, decodes track_polyline for the map
 * preview, and runs the Download-GPX flow ("full open", Keputusan poin 6).
 *
 * Sealed states, no boolean flags — same style as BrowseViewModel.
 */
class PublicRouteDetailViewModel : ViewModel() {

    private val repository = BrowseRepository()

    sealed class State {
        data object Loading : State()
        data class Ready(
            val route: BrowseRepository.RouteDetail,
            val track: List<TrackPoint>
        ) : State()
        data class Error(val messageRes: Int) : State()
    }

    sealed class DownloadState {
        data object Idle : DownloadState()
        data object InProgress : DownloadState()
        /** GPX XML + suggested file name — the screen writes the cache file
         *  and fires the share intent (same FileProvider path as P3G export). */
        data class Ready(val gpxXml: String, val fileName: String) : DownloadState()
        data class Error(val messageRes: Int) : DownloadState()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    fun load(routeId: String) {
        viewModelScope.launch {
            _state.value = State.Loading
            if (!SupabaseClientProvider.isConfigured) {
                _state.value = State.Error(R.string.browse_error_not_configured)
                return@launch
            }
            when (val outcome = repository.detail(SupabaseClientProvider.client, routeId)) {
                is BrowseRepository.DetailOutcome.Success -> {
                    _state.value = State.Ready(
                        route = outcome.route,
                        track = BrowseRepository.decodeTrack(outcome.route.trackPolyline)
                    )
                }
                is BrowseRepository.DetailOutcome.Failure -> {
                    _state.value = State.Error(errorResFor(outcome.error))
                }
            }
        }
    }

    fun downloadGpx(route: BrowseRepository.RouteDetail) {
        if (_download.value is DownloadState.InProgress) return
        viewModelScope.launch {
            _download.value = DownloadState.InProgress
            when (val outcome = repository.downloadGpx(SupabaseClientProvider.client, route)) {
                is BrowseRepository.GpxOutcome.Success ->
                    _download.value = DownloadState.Ready(outcome.gpxXml, outcome.fileName)
                is BrowseRepository.GpxOutcome.Failure ->
                    _download.value = DownloadState.Error(errorResFor(outcome.error))
            }
        }
    }

    fun downloadConsumed() {
        _download.value = DownloadState.Idle
    }

    private fun errorResFor(error: BrowseRepository.BrowseError): Int = when (error) {
        BrowseRepository.BrowseError.NOT_CONFIGURED -> R.string.browse_error_not_configured
        BrowseRepository.BrowseError.NETWORK -> R.string.browse_error_network
        BrowseRepository.BrowseError.NOT_FOUND -> R.string.browse_error_not_found
        BrowseRepository.BrowseError.NO_GPX_FILE -> R.string.browse_error_no_gpx
        BrowseRepository.BrowseError.UNKNOWN -> R.string.browse_error_unknown
    }
}
