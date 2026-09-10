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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

/**
 * Where a waypoint-creation request came from. Decides the default
 * attachment the Add form starts with (user can still override in the
 * form) and which attachment options the form offers at all:
 * - HOME: independent pin only (no route/activity context exists).
 * - ROUTE ([routeId]): linked to that route.
 * - RECORDING ([activityId], [routeId]): linked to the live activity,
 *   falling back to the attached route while still IDLE (no activity row
 *   exists yet — it's minted when recording actually starts).
 */
sealed interface WaypointContext {
    data object Home : WaypointContext
    data class Route(val routeId: String) : WaypointContext
    data class Recording(val activityId: String?, val routeId: String?) : WaypointContext

    /** Attachment values seeded into the Add form for this context.
     *  Recording links to the live ACTIVITY once one exists (minted when
     *  recording actually starts); while still IDLE (activityId null) it
     *  falls back to the attached route. */
    val defaultRouteId: String?
        get() = when (this) {
            is Route -> routeId
            is Recording -> if (activityId != null) null else routeId
            Home -> null
        }

    val defaultActivityId: String?
        get() = when (this) {
            is Recording -> activityId
            else -> null
        }
}

class WaypointViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WaypointRepository(app)

    /** Every waypoint (all attachments) — used by Navigation's next-waypoint
     *  fold, which applies its own track-proximity filter. */
    val waypoints: StateFlow<List<WaypointEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Independent pins only (no route/activity link) — the classic P3E2
     *  set. These render on EVERY map (Home/Recording/Navigation), unlike
     *  linked ones which the showing screen filters by context. */
    val independentWaypoints: StateFlow<List<WaypointEntity>> = repository.observeIndependent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Route-linked waypoints for a specific route — reactive. */
    fun routeWaypoints(routeId: String): StateFlow<List<WaypointEntity>> =
        repository.observeForRoute(routeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingTap = MutableStateFlow<PendingWaypointTap?>(null)
    val pendingTap: StateFlow<PendingWaypointTap?> = _pendingTap.asStateFlow()

    private val _selectedWaypoint = MutableStateFlow<WaypointEntity?>(null)
    val selectedWaypoint: StateFlow<WaypointEntity?> = _selectedWaypoint.asStateFlow()

    private val _editingWaypoint = MutableStateFlow<WaypointEntity?>(null)
    val editingWaypoint: StateFlow<WaypointEntity?> = _editingWaypoint.asStateFlow()

    /** Attachment context for the NEXT Add (set by each screen before/when
     *  the map long-press or crosshair save arrives; defaults to Home). */
    private val _context = MutableStateFlow<WaypointContext>(WaypointContext.Home)
    val context: StateFlow<WaypointContext> = _context.asStateFlow()

    fun setContext(context: WaypointContext) {
        _context.value = context
    }

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
     *  double-tap on the save button while the coroutine is still running.
     *  [linkedRouteId]/[linkedActivityId] come from the form's attachment
     *  picker (seeded from the screen's [WaypointContext]); the user's
     *  choice wins over the default. */
    fun confirmAdd(
        name: String,
        category: WaypointCategory,
        note: String?,
        linkedRouteId: String? = _context.value.defaultRouteId,
        linkedActivityId: String? = _context.value.defaultActivityId
    ) {
        val tap = _pendingTap.value ?: return
        _pendingTap.value = null
        viewModelScope.launch {
            repository.create(
                name = name.ifBlank { getApplication<Application>().getString(category.labelRes) },
                category = category,
                lat = tap.lat,
                lon = tap.lon,
                elevationM = tap.elevationM,
                note = note,
                linkedRouteId = linkedRouteId,
                linkedActivityId = linkedActivityId
            )
        }
    }

    fun confirmEdit(name: String, category: WaypointCategory, note: String?) {
        confirmEditWithLinks(
            name, category, note,
            _editingWaypoint.value?.linkedRouteId,
            _editingWaypoint.value?.linkedActivityId
        )
    }

    /** Edit with explicit attachment values (v7 form picker). GPX-origin
     *  waypoints keep their intrinsic route link: callers pass the row's
     *  existing link and the form locks the picker for them. */
    fun confirmEditWithLinks(
        name: String,
        category: WaypointCategory,
        note: String?,
        linkedRouteId: String?,
        linkedActivityId: String?
    ) {
        val waypoint = _editingWaypoint.value ?: return
        _editingWaypoint.value = null
        viewModelScope.launch {
            repository.updateWithLinks(waypoint, name.ifBlank { getApplication<Application>().getString(category.labelRes) }, category, note, linkedRouteId, linkedActivityId)
        }
    }

    /** Crosshair save from a screen that owns its own crosshair instance
     *  (RoutePreview v7): explicit coordinates + links instead of this VM's
     *  crosshair position state. */
    fun confirmCrosshairWaypointFrom(
        lat: Double,
        lon: Double,
        name: String,
        category: WaypointCategory,
        note: String?,
        linkedRouteId: String?,
        linkedActivityId: String?
    ) {
        viewModelScope.launch {
            repository.create(
                name = name.ifBlank { getApplication<Application>().getString(category.labelRes) },
                category = category,
                lat = lat,
                lon = lon,
                elevationM = null,
                note = note,
                linkedRouteId = linkedRouteId,
                linkedActivityId = linkedActivityId
            )
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

    /** Confirm waypoint from crosshair selection — same attachment rules
     *  as [confirmAdd] (context default, caller may override). */
    fun confirmCrosshairWaypoint(
        name: String,
        category: WaypointCategory,
        note: String?,
        linkedRouteId: String? = _context.value.defaultRouteId,
        linkedActivityId: String? = _context.value.defaultActivityId
    ) {
        val position = _crosshairPosition.value ?: return
        viewModelScope.launch {
            repository.create(
                name = name.ifBlank { getApplication<Application>().getString(category.labelRes) },
                category = category,
                lat = position.latitude,
                lon = position.longitude,
                elevationM = null,
                note = note,
                linkedRouteId = linkedRouteId,
                linkedActivityId = linkedActivityId
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
