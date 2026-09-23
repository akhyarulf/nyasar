package com.nyasar.app.ui.browse

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.data.settings.SettingsRepository
import com.nyasar.app.data.supabase.BrowseRepository
import com.nyasar.app.data.supabase.SharedSocialState
import com.nyasar.app.data.supabase.SocialRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import com.nyasar.app.gpx.GpxParser
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.map.TileProvider
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.ui.components.ElevationPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val socialRepository = SocialRepository()
    private val publishRepository = com.nyasar.app.data.supabase.PublishRepository()
    private val routeRepository = RouteRepository(application)
    private val settingsRepository = SettingsRepository(application)

    /** Tile provider for the full-screen interactive map — same persisted
     *  setting every other map screen reads (Home/Recording/RoutePreview). */
    val provider: StateFlow<TileProvider> = settingsRepository.settings
        .map { TileProviderFactory.byId(it.providerId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TileProviderFactory.default())

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

    /** Signed-in user liked this route (filled heart on the action row). */
    private val _liked = MutableStateFlow(false)
    val liked = _liked.asStateFlow()

    /** Like toggle in flight (button disabled). */
    private val _likePending = MutableStateFlow(false)
    val likePending = _likePending.asStateFlow()

    /** Signed-in user bookmarked this route (saved_routes — private
     *  Wikiloc-style list, separate from the public like). */
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    /** Save toggle in flight (button disabled). */
    private val _savePending = MutableStateFlow(false)
    val savePending = _savePending.asStateFlow()

    /** Visibility toggle in flight (owner's Public⇄Private switch disabled
     *  while the file moves buckets). */
    private val _visibilityPending = MutableStateFlow(false)
    val visibilityPending = _visibilityPending.asStateFlow()

    /**
     * Owner-only: flip this route Everyone⇄Only-you. Optimistic on the row's
     * `isPublic`; the repository result (which reflects the ordered
     * file-move-then-row-flip dance and its failure modes) settles it
     * authoritatively. Only reachable from the owner's own detail — anon and
     * non-owner callers are ignored (the switch isn't even rendered). */
    fun setVisibility(route: BrowseRepository.RouteDetail, isPublic: Boolean) {
        val myId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id ?: return
        if (route.userId != myId) return
        if (_visibilityPending.value) return
        if (route.isPublic == isPublic) return
        _visibilityPending.value = true
        // Optimistic
        _state.value = when (val s = _state.value) {
            is State.Ready -> s.copy(route = s.route.copy(isPublic = isPublic))
            else -> s
        }
        viewModelScope.launch {
            val settled = publishRepository.setRouteVisibility(
                SupabaseClientProvider.client, route.id, isPublic
            )
            _state.value = when (val s = _state.value) {
                is State.Ready -> s.copy(route = s.route.copy(isPublic = settled))
                else -> s
            }
            _visibilityPending.value = false
        }
    }

    /** Comments thread + post lifecycle for the detail section. */
    sealed class CommentsState {
        data object Loading : CommentsState()
        data class Ready(
            val comments: List<SocialRepository.CommentRow>,
            /** True while the post request is in flight. */
            val posting: Boolean = false
        ) : CommentsState()
        data class Error(val messageRes: Int) : CommentsState()
    }

    private val _comments = MutableStateFlow<CommentsState>(CommentsState.Loading)
    val comments = _comments.asStateFlow()

    /** Likes are only toggled when signed in; the screen routes anonymous
     *  taps to auth. Server count is authoritative on response. */
    fun toggleLike(route: BrowseRepository.RouteDetail) {
        if (!SupabaseClientProvider.isConfigured) return
        if (_likePending.value) return
        val wasLiked = _liked.value
        _liked.value = !wasLiked
        _state.value = (_state.value as? State.Ready)?.let { s ->
            s.copy(route = s.route.copy(likesCount = (s.route.likesCount + if (wasLiked) -1 else 1).coerceAtLeast(0)))
        } ?: _state.value
        _likePending.value = true
        viewModelScope.launch {
            when (val outcome = socialRepository.toggleLike(SupabaseClientProvider.client, route.id)) {
                is SocialRepository.ToggleOutcome.Success -> {
                    _liked.value = outcome.liked
                    SharedSocialState.onLikedToggled(route.id, outcome.liked)
                    _state.value = (_state.value as? State.Ready)?.let { s ->
                        s.copy(route = s.route.copy(likesCount = outcome.likesCount))
                    } ?: _state.value
                }
                is SocialRepository.ToggleOutcome.Failure -> {
                    _liked.value = wasLiked
                    _state.value = (_state.value as? State.Ready)?.let { s ->
                        s.copy(route = s.route.copy(likesCount = route.likesCount))
                    } ?: _state.value
                }
            }
            _likePending.value = false
        }
    }

    /** Load liked/saved-state + comments once the route row is on screen.
     *  Reads the PROCESS-WIDE [SharedSocialState] first (a Browse/Saved
     *  toggle is already reflected), then refines with this route's
     *  authoritative fetch — cheap and always current. */
    fun loadSocial(routeId: String) {
        _liked.value = routeId in SharedSocialState.likedIds.value
        _saved.value = routeId in SharedSocialState.savedIds.value
        if (!SupabaseClientProvider.isConfigured) return
        viewModelScope.launch {
            val client = SupabaseClientProvider.client
            SharedSocialState.reload(client)
            _liked.value = routeId in SharedSocialState.likedIds.value
            _saved.value = routeId in SharedSocialState.savedIds.value
            when (val outcome = socialRepository.fetchComments(client, routeId)) {
                is SocialRepository.CommentsOutcome.Success ->
                    _comments.value = CommentsState.Ready(outcome.comments)
                is SocialRepository.CommentsOutcome.Failure ->
                    // Non-blocking: the detail's core content is the route,
                    // comments degrade to an empty section with retry via
                    // reopening the screen.
                    _comments.value = CommentsState.Ready(emptyList())
            }
        }
    }

    fun postComment(routeId: String, text: String) {
        val current = _comments.value
        if (current !is CommentsState.Ready || current.posting) return
        if (text.isBlank()) return
        _comments.value = current.copy(posting = true)
        viewModelScope.launch {
            when (val outcome = socialRepository.postComment(SupabaseClientProvider.client, routeId, text)) {
                is SocialRepository.PostOutcome.Success -> {
                    _comments.value = (current.copy(
                        comments = listOf(outcome.comment) + current.comments,
                        posting = false
                    ))
                    // Counter on the route row bumps via the DB trigger; sync UI.
                    _state.value = (_state.value as? State.Ready)?.let { s ->
                        s.copy(route = s.route.copy(commentsCount = s.route.commentsCount + 1))
                    } ?: _state.value
                }
                is SocialRepository.PostOutcome.Failure -> {
                    _comments.value = current.copy(posting = false)
                }
            }
        }
    }

    /** Save (bookmark) toggle on the detail action row: optimistic flip
     *  with rollback — no counter (saved_routes is private). */
    fun toggleSave(routeId: String) {
        if (!SupabaseClientProvider.isConfigured) return
        if (_savePending.value) return
        val wasSaved = _saved.value
        _saved.value = !wasSaved
        _savePending.value = true
        viewModelScope.launch {
            val nowSaved = when (val outcome = socialRepository.toggleSave(SupabaseClientProvider.client, routeId)) {
                is SocialRepository.SaveOutcome.Success -> outcome.saved
                is SocialRepository.SaveOutcome.Failure -> wasSaved // roll back
            }
            _saved.value = nowSaved
            // Write-through: Saved list & Browse cards update in the same frame.
            SharedSocialState.onSavedToggled(routeId, nowSaved)
            _savePending.value = false
        }
    }

    /** Delete the signed-in user's own comment: optimistic removal, restore
     *  on failure. comments_count syncs via the same DB-trigger path as post. */
    fun deleteComment(comment: SocialRepository.CommentRow) {
        val current = _comments.value
        if (current !is CommentsState.Ready) return
        _comments.value = current.copy(comments = current.comments.filterNot { it.id == comment.id })
        viewModelScope.launch {
            when (socialRepository.deleteComment(SupabaseClientProvider.client, comment.id)) {
                is SocialRepository.DeleteCommentOutcome.Success -> {
                    _state.value = (_state.value as? State.Ready)?.let { s ->
                        s.copy(route = s.route.copy(commentsCount = (s.route.commentsCount - 1).coerceAtLeast(0)))
                    } ?: _state.value
                }
                is SocialRepository.DeleteCommentOutcome.Failure -> {
                    // Roll back: re-insert at its original position.
                    val state = _comments.value as? CommentsState.Ready ?: return@launch
                    val without = state.comments.filterNot { it.id == comment.id }
                    val index = current.comments.indexOfFirst { it.id == comment.id }
                    val restored = if (index >= without.size) without + comment
                    else without.subList(0, index) + comment + without.subList(index, without.size)
                    _comments.value = state.copy(comments = restored)
                }
            }
        }
    }

    /** Submit an abuse report (route or comment target). Result delivered
     *  through [onDone] so the dialog can close + toast without the VM
     *  holding UI context. */
    fun submitReport(
        routeId: String? = null,
        commentId: String? = null,
        reason: SocialRepository.ReportReason,
        note: String?,
        onDone: (Boolean) -> Unit
    ) {
        if (!SupabaseClientProvider.isConfigured) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = when (socialRepository.submitReport(
                client = SupabaseClientProvider.client,
                routeId = routeId,
                commentId = commentId,
                reason = reason.wire,
                note = note
            )) {
                is SocialRepository.ReportOutcome.Success -> true
                is SocialRepository.ReportOutcome.Failure -> false
            }
            onDone(ok)
        }
    }

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
                        // Konsep backup tanpa tombol: rute tersimpan ke library
                        // langsung di-upsert ke backup pribadi.
                        com.nyasar.app.backup.BackupManager.scheduleRouteBackup(
                            getApplication(), entity.id
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
