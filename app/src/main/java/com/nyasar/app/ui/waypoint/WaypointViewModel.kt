package com.nyasar.app.ui.waypoint

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.WaypointCategory
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.data.repository.WaypointRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

/** A map tap that hasn't been turned into a saved waypoint yet — holds the
 *  coordinate (and elevation, if we have a current GPS fix) while the Add
 *  sheet is open for name/category/note input. */
data class PendingWaypointTap(
    val lat: Double,
    val lon: Double,
    val elevationM: Double?
)

class WaypointViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WaypointRepository(app)

    val waypoints: StateFlow<List<WaypointEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingTap = MutableStateFlow<PendingWaypointTap?>(null)
    val pendingTap: StateFlow<PendingWaypointTap?> = _pendingTap.asStateFlow()

    private val _selectedWaypoint = MutableStateFlow<WaypointEntity?>(null)
    val selectedWaypoint: StateFlow<WaypointEntity?> = _selectedWaypoint.asStateFlow()

    private val _editingWaypoint = MutableStateFlow<WaypointEntity?>(null)
    val editingWaypoint: StateFlow<WaypointEntity?> = _editingWaypoint.asStateFlow()

    private val _crosshairMode = MutableStateFlow(false)
    val crosshairMode: StateFlow<Boolean> = _crosshairMode.asStateFlow()

    private val _crosshairPosition = MutableStateFlow<LatLng?>(null)
    val crosshairPosition: StateFlow<LatLng?> = _crosshairPosition.asStateFlow()

    /** Long-press on the map (spec: "Tap map → Add Waypoint") calls this
     *  once. [_pendingTap] being non-null is what opens the Add sheet, and
     *  it's cleared the instant the sheet is dismissed/confirmed — a
     *  second long-press fire (or a recomposition replaying the same
     *  gesture callback) simply overwrites the same pending state instead
     *  of queuing a second add, which is what would create the duplicate
     *  waypoints the spec explicitly warns against. */
    fun onMapLongPress(lat: Double, lon: Double, elevationM: Double?) {
        _pendingTap.value = PendingWaypointTap(lat, lon, elevationM)
    }

    fun dismissPendingTap() {
        _pendingTap.value = null
    }

    fun selectWaypoint(waypoint: WaypointEntity?) {
        _selectedWaypoint.value = waypoint
    }

    fun startEditing(waypoint: WaypointEntity) {
        _selectedWaypoint.value = null
        _editingWaypoint.value = waypoint
    }

    fun dismissEditing() {
        _editingWaypoint.value = null
    }

    /** Confirms the pending tap into a saved waypoint. Clears the pending
     *  tap first so the Add sheet can't be re-submitted twice from a
     *  double-tap on the save button while the coroutine is still running. */
    fun confirmAdd(name: String, category: WaypointCategory, note: String?) {
        val tap = _pendingTap.value ?: return
        _pendingTap.value = null
        viewModelScope.launch {
            repository.create(
                name = name.ifBlank { category.label },
                category = category,
                lat = tap.lat,
                lon = tap.lon,
                elevationM = tap.elevationM,
                note = note
            )
        }
    }

    fun confirmEdit(name: String, category: WaypointCategory, note: String?) {
        val waypoint = _editingWaypoint.value ?: return
        _editingWaypoint.value = null
        viewModelScope.launch {
            repository.update(waypoint, name.ifBlank { category.label }, category, note)
        }
    }

    fun deleteWaypoint(waypoint: WaypointEntity) {
        _selectedWaypoint.value = null
        _editingWaypoint.value = null
        viewModelScope.launch { repository.delete(waypoint) }
    }

    /** Toggle crosshair selection mode */
    fun toggleCrosshairMode() {
        _crosshairMode.value = !_crosshairMode.value
        if (!_crosshairMode.value) {
            _crosshairPosition.value = null
        }
    }

    /** Update crosshair position as map moves */
    fun updateCrosshairPosition(lat: Double, lon: Double) {
        _crosshairPosition.value = LatLng(lat, lon)
    }

    /** Confirm waypoint from crosshair selection */
    fun confirmCrosshairWaypoint(name: String, category: WaypointCategory, note: String?) {
        val position = _crosshairPosition.value ?: return
        viewModelScope.launch {
            repository.create(
                name = name.ifBlank { category.label },
                category = category,
                lat = position.latitude,
                lon = position.longitude,
                elevationM = null,
                note = note
            )
        }
        _crosshairMode.value = false
        _crosshairPosition.value = null
    }

    /** Exit crosshair mode without saving */
    fun exitCrosshairMode() {
        _crosshairMode.value = false
        _crosshairPosition.value = null
    }
}
