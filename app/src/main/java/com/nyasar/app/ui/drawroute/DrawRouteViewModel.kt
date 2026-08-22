package com.nyasar.app.ui.drawroute

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.RouteEntity
import com.nyasar.app.data.repository.RouteRepository
import com.nyasar.app.gpx.model.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DrawRouteUiState(
    val points: List<TrackPoint> = emptyList(),
    /** Straight-line sum between consecutive tapped points — a live
     *  preview distance, not a claim about what the real trail distance
     *  will be (spec: manual point-to-point, no snap-to-road/trail engine
     *  in this pass, see the "3 app reference" discussion). */
    val distanceMeters: Double = 0.0,
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
 */
class DrawRouteViewModel(app: Application) : AndroidViewModel(app) {

    private val routeRepository = RouteRepository(app)

    private val _uiState = MutableStateFlow(DrawRouteUiState())
    val uiState: StateFlow<DrawRouteUiState> = _uiState.asStateFlow()

    fun addPoint(lat: Double, lon: Double) {
        val updated = _uiState.value.points + TrackPoint(lat = lat, lon = lon, elevationM = null, timestampEpochMs = null)
        _uiState.value = _uiState.value.copy(points = updated, distanceMeters = totalDistance(updated))
    }

    fun undoLastPoint() {
        val current = _uiState.value.points
        if (current.isEmpty()) return
        val updated = current.dropLast(1)
        _uiState.value = _uiState.value.copy(points = updated, distanceMeters = totalDistance(updated))
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** @param name blank is allowed here — RouteRepository.importFromDrawnPoints
     *  falls back to "Rute Baru", same as leaving any name field empty. */
    fun finish(name: String) {
        val points = _uiState.value.points
        if (points.size < 2) return // canFinish already gates the button; defensive floor here too
        _uiState.value = _uiState.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val route: RouteEntity = routeRepository.importFromDrawnPoints(name, points)
                _uiState.value = _uiState.value.copy(saving = false, savedRouteId = route.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(saving = false, error = "Gagal menyimpan rute")
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
