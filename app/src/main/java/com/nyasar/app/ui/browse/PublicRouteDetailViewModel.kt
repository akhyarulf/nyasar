package com.nyasar.app.ui.browse

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import com.nyasar.app.gpx.GpxParser
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.ui.components.ElevationPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for a single PUBLIC route's detail screen (Fase 2 slice 2):
 * loads the row + publisher username, decodes track_polyline for the map
 * preview, runs the Download-GPX flow ("full open", Keputusan poin 6) and
 * the Save-to-Library flow (download -> import as a LOCAL RouteEntity ->
 * open Route Preview, from which MULAI NAVIGASI works — navigation needs
 * a real GPX on disk, which the lossy track_polyline can never provide).
 *
 * Sealed states, no boolean flags — same style as BrowseViewModel.
 */
class PublicRouteDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BrowseRepository()
    private val routeRepository = RouteRepository(application)

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

    /** Save-to-Library lifecycle: idle -> saving -> Saved(localRouteId) |
     *  Error. [Saved.localRouteId] is consumed once by the screen to
     *  navigate to the route's preview. */
    sealed class SaveState {
        data object Idle : SaveState()
        data object Saving : SaveState()
        data class Saved(val localRouteId: String) : SaveState()
        data class Error(val messageRes: Int) : SaveState()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _save = MutableStateFlow<SaveState>(SaveState.Idle)
    val save: StateFlow<SaveState> = _save.asStateFlow()

    /** Elevation-profile lifecycle for the detail chart (Strava-style
     *  "Elevation" section). track_polyline is LOSSY — lat/lon only, no
     *  elevation — so the honest source is the ORIGINAL GPX: downloaded
     *  once, parsed, converted via the same [ElevationStats] the activity
     *  detail chart uses. Any failure (no file, corrupt, no ele tags) is
     *  [Unavailable] and the section simply doesn't render — the screen
     *  never shows a broken/empty chart. */
    sealed class ElevationState {
        data object Idle : ElevationState()
        data object Loading : ElevationState()
        data class Ready(val profile: List<ElevationPoint>) : ElevationState()
        data object Unavailable : ElevationState()
    }

    private val _elevation = MutableStateFlow<ElevationState>(ElevationState.Idle)
    val elevation: StateFlow<ElevationState> = _elevation.asStateFlow()

    /** Fired once from the Ready screen — NOT inside load(), so the detail
     *  renders immediately and the chart streams in when the GPX arrives
     *  (same progressive pattern as the browse cards' tile previews). */
    fun loadElevation(route: BrowseRepository.RouteDetail) {
        if (_elevation.value is ElevationState.Loading) return
        viewModelScope.launch {
            _elevation.value = ElevationState.Loading
            _elevation.value = withContext(Dispatchers.IO) {
                try {
                    when (val outcome = repository.downloadGpx(SupabaseClientProvider.client, route)) {
                        is BrowseRepository.GpxOutcome.Success -> {
                            val points = GpxParser()
                                .parse(outcome.gpxXml.byteInputStream(), route.name)
                                .allTrackPoints
                            val profile = ElevationStats.toElevationProfile(points)
                            if (profile.size >= 2) ElevationState.Ready(profile)
                            else ElevationState.Unavailable
                        }
                        is BrowseRepository.GpxOutcome.Failure -> ElevationState.Unavailable
                    }
                } catch (e: Exception) {
                    Log.e("PublicRouteDetailVM", "elevation profile failed", e)
                    ElevationState.Unavailable
                }
            }
        }
    }

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

    /**
     * Save-to-Library: fetch + decompress the ORIGINAL GPX, import it as a
     * local RouteEntity (same path as a manual GPX import), and report the
     * new local route id. The lossy track_polyline is never used here —
     * saving must yield a route that can actually be navigated offline.
     */
    fun saveToLibrary(route: BrowseRepository.RouteDetail) {
        if (_save.value is SaveState.Saving) return
        viewModelScope.launch {
            _save.value = SaveState.Saving
            when (val outcome = repository.downloadGpx(SupabaseClientProvider.client, route)) {
                is BrowseRepository.GpxOutcome.Success -> {
                    _save.value = try {
                        val entity = routeRepository.importFromGpxXml(
                            xml = outcome.gpxXml,
                            displayName = route.name
                        )
                        SaveState.Saved(entity.id)
                    } catch (e: Exception) {
                        android.util.Log.e("PublicRouteDetailVM", "saveToLibrary import failed", e)
                        SaveState.Error(R.string.browse_save_failed)
                    }
                }
                is BrowseRepository.GpxOutcome.Failure ->
                    _save.value = SaveState.Error(errorResFor(outcome.error))
            }
        }
    }

    fun saveConsumed() {
        _save.value = SaveState.Idle
    }

    private fun errorResFor(error: BrowseRepository.BrowseError): Int = when (error) {
        BrowseRepository.BrowseError.NOT_CONFIGURED -> R.string.browse_error_not_configured
        BrowseRepository.BrowseError.NETWORK -> R.string.browse_error_network
        BrowseRepository.BrowseError.NOT_FOUND -> R.string.browse_error_not_found
        BrowseRepository.BrowseError.NO_GPX_FILE -> R.string.browse_error_no_gpx
        BrowseRepository.BrowseError.UNKNOWN -> R.string.browse_error_unknown
    }
}
