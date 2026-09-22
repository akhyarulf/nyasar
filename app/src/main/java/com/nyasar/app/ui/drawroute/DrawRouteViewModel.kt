package com.nyasar.app.ui.drawroute

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.R
import com.nyasar.app.data.db.RouteEntity
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.gpx.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DrawRouteUiState(
    /** The user's taps (anchors) — the undo/history model. */
    val points: List<TrackPoint> = emptyList(),
    /** What is RENDERED and SAVED: with "ikuti jalur" on, the snapped
     *  (OSRM foot) geometry; off / while a snap is in flight / after a
     *  failed snap, the straight anchor lines exactly as before snapping
     *  existed. One source of truth for both the map line and the saved
     *  route — the screen never merges geometry itself. */
    val pathPoints: List<TrackPoint> = emptyList(),
    /** Straight-line sum between consecutive ANCHOR points — instant and
     *  stable (doesn't flicker while snaps resolve). The saved route's
     *  distance is recomputed from the final saved geometry on disk, which
     *  is the honest real-path number. */
    val distanceMeters: Double = 0.0,
    /** True while at least one segment snap request is in flight — feeds
     *  the small "mengikuti jalur…" hint; never blocks finishing. */
    val snapping: Boolean = false,
    /** "Ikuti jalur" toggle (default ON). OFF = pure manual straight-line
     *  drawing, byte-identical behavior to the pre-snap screen. */
    val followPaths: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    /** Set once a save completes — screen navigates away on seeing this,
     *  then the caller is done with this ViewModel instance. */
    val savedRouteId: String? = null
) {
    val canUndo: Boolean get() = points.isNotEmpty()
    val canFinish: Boolean get() = points.size >= 2
}

/**
 * Backs the draw-route screen (tap points on a map to build a route by
 * hand, no GPS recording involved — see the "belum ada GPX" discussion:
 * this is specifically for when the user doesn't have a route yet and
 * wants to create one before ever stepping outside). State lives entirely
 * here as a plain in-memory list; nothing is persisted until
 * [finish] is called, matching how a not-yet-saved draft should behave —
 * navigating away without finishing simply discards it, same as leaving
 * any other unsaved form.
 *
 * Snap-to-path ("ikuti jalur", the GPX Studio feel): each NEW tap renders
 * straight immediately, then its segment is snapped through [PathSnapper]
 * (OSRM foot — trails AND roads) and the line upgrades in place. Anchors
 * stay the source of truth for undo; per-segment results are cached so a
 * tap costs exactly one OSRM call for the whole session (no O(n²)
 * re-snap). Every failure — offline, unroutable pair, detour guard —
 * degrades that segment to a straight line. Nothing here can block or
 * crash drawing.
 */
class DrawRouteViewModel(app: Application) : AndroidViewModel(app) {

    private val routeRepository = RouteRepository(app)

    private val _uiState = MutableStateFlow(DrawRouteUiState())
    val uiState: StateFlow<DrawRouteUiState> = _uiState.asStateFlow()

    /**
     * Per-segment snap results. segments[i] = geometry of
     * anchors[i] -> anchors[i+1] INCLUDING both endpoints (snapped), or
     * the straight two-point fallback (never requested / failed). Kept in
     * lockstep with [DrawRouteUiState.points]: one entry per segment,
     * appended on addPoint, dropped by undo, rebuilt on toggle.
     */
    private val segments = ArrayList<List<TrackPoint>?>()

    /** Bumps on undo / toggle so stale in-flight snaps can't repaint a
     *  line that no longer exists. */
    private var generation = 0

    /** Number of snap requests currently in flight (for the hint + so
     *  snapping only turns false when ALL pending results landed). */
    private var inFlightCount = 0

    fun addPoint(lat: Double, lon: Double) {
        val anchor = TrackPoint(lat = lat, lon = lon, elevationM = null, timestampEpochMs = null)
        val anchors = _uiState.value.points + anchor
        generation++ // a new edit invalidates any still-flying snap for this tail

        if (anchors.size >= 2) {
            // Optimistic straight placeholder for the new segment.
            segments.add(listOf(anchors[anchors.size - 2], anchor))
        }
        publishState(anchors)

        if (_uiState.value.followPaths && anchors.size >= 2) {
            snapSegment(index = segments.size - 1, from = anchors[anchors.size - 2], to = anchor)
        }
    }

    fun undoLastPoint() {
        val current = _uiState.value.points
        if (current.isEmpty()) return
        generation++ // stale snap responses must not resurrect removed taps
        if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
        publishState(current.dropLast(1))
    }

    fun setFollowPaths(enabled: Boolean) {
        if (_uiState.value.followPaths == enabled) return
        generation++
        _uiState.value = _uiState.value.copy(followPaths = enabled)
        val anchors = _uiState.value.points
        if (!enabled || anchors.size < 2) {
            segments.clear()
            if (anchors.size >= 2) {
                for (i in 0 until anchors.size - 1) segments.add(listOf(anchors[i], anchors[i + 1]))
            }
            publishState(anchors)
            return
        }
        // Toggle ON after straight drawing: segments have no snap yet —
        // rebuild the cache straight and re-snap everything sequentially.
        // One call per segment, only on this toggle (never per tap).
        segments.clear()
        for (i in 0 until anchors.size - 1) segments.add(listOf(anchors[i], anchors[i + 1]))
        publishState(anchors)
        for (i in 0 until anchors.size - 1) {
            snapSegment(index = i, from = anchors[i], to = anchors[i + 1])
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Snaps ONE segment, writes the result into [segments], and repaints.
     * The generation check means a result is applied only if no undo /
     * toggle / new tap re-owned the line meanwhile; otherwise the result
     * is dropped silently (the newer edit re-snaps what it needs).
     */
    private fun snapSegment(index: Int, from: TrackPoint, to: TrackPoint) {
        val myGen = generation
        inFlightCount++
        publishState(_uiState.value.points) // flipping snapping=true into the hint
        viewModelScope.launch {
            val snapped = PathSnapper.snap(from, to)
            inFlightCount--
            if (myGen != generation) return@launch // stale — a newer edit owns the line
            segments[index] = snapped ?: listOf(from, to)
            publishState(_uiState.value.points)
        }
    }

    /** Concatenates the per-segment geometry into pathPoints (endpoints
     *  shared between consecutive segments are included once). */
    private fun joinedPath(): List<TrackPoint> {
        val anchors = _uiState.value.points
        if (segments.isEmpty()) return anchors
        val out = ArrayList<TrackPoint>()
        for ((i, seg) in segments.withIndex()) {
            val s = seg ?: run {
                // Defensive: cache shorter than anchors (shouldn't happen —
                // every mutation keeps them in lockstep). Straight fallback.
                if (i + 1 < anchors.size) listOf(anchors[i], anchors[i + 1]) else emptyList()
            }
            if (out.isEmpty()) {
                out.addAll(s)
            } else {
                out.addAll(s.drop(1))
            }
        }
        return out
    }

    private fun publishState(anchors: List<TrackPoint>) {
        _uiState.value = _uiState.value.copy(
            points = anchors,
            pathPoints = joinedPath(),
            distanceMeters = totalDistance(anchors),
            snapping = inFlightCount > 0
        )
    }

    /** @param name blank is allowed here — RouteRepository.importFromDrawnPoints
     *  falls back to "Rute Baru", same as leaving any name field empty. */
    fun finish(name: String) {
        val points = _uiState.value.pathPoints.ifEmpty { _uiState.value.points }
        if (points.size < 2) return // canFinish already gates the button; defensive floor here too
        _uiState.value = _uiState.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val route: RouteEntity = routeRepository.importFromDrawnPoints(name, points)
                // Konsep backup tanpa tombol: rute gambar = data backup.
                com.nyasar.app.backup.BackupManager.scheduleRouteBackup(getApplication(), route.id)
                _uiState.value = _uiState.value.copy(saving = false, savedRouteId = route.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(saving = false, error = getApplication<android.app.Application>().getString(R.string.error_saving_route))
            }
        }
    }

    private fun totalDistance(points: List<TrackPoint>): Double =
        points.zipWithNext().sumOf { (a, b) ->
            com.nyasar.app.navigation.GeoMath.distanceMeters(
                com.nyasar.app.navigation.LatLng(a.lat, a.lon),
                com.nyasar.app.navigation.LatLng(b.lat, b.lon)
            )
        }
}
