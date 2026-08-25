package com.nyasar.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HistoryLoadState { LOADING, LOADED, ERROR }

data class ActivityHistoryUiState(
    val loadState: HistoryLoadState = HistoryLoadState.LOADING,
    val activities: List<ActivityEntity> = emptyList()
)

/**
 * Reads existing ActivityDao.observeCompleted() — already ordered newest
 * first by the query itself, no new database or table. Collected manually
 * (rather than stateIn on the DAO Flow directly) so a thrown exception from
 * Room surfaces as ERROR state instead of silently cancelling the collector,
 * which is what stateIn would do on an uncaught exception.
 */
class ActivityHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).activityDao()

    private val _uiState = MutableStateFlow(ActivityHistoryUiState())
    val uiState: StateFlow<ActivityHistoryUiState> = _uiState.asStateFlow()

    // Card thumbnail cache — keyed by activity id, holds a downsampled
    // (lon, lat) list. In-memory only, cleared with the ViewModel; not
    // persisted since it's cheap to regenerate and correctness (e.g. after
    // an activity edit) matters more than avoiding a re-query. Kept out of
    // ActivityHistoryUiState itself so a thumbnail arriving doesn't trigger
    // a full-list recomposition — each row observes only its own entry.
    private val _thumbnails = MutableStateFlow<Map<String, List<Pair<Double, Double>>>>(emptyMap())
    val thumbnails: StateFlow<Map<String, List<Pair<Double, Double>>>> = _thumbnails.asStateFlow()

    /** Loads (once) and downsamples the track for one activity's card
     *  thumbnail. Downsampling happens here — in Kotlin, after a lat/lon-only
     *  query — rather than in SQL, since SQLite has no simple "every Nth
     *  row" without window functions Room's version may not support; for
     *  typical hike-length tracks (thousands of points) this is a brief,
     *  one-time, off-main-thread cost per activity, not per recomposition. */
    fun loadThumbnail(activityId: String) {
        if (_thumbnails.value.containsKey(activityId)) return
        viewModelScope.launch {
            try {
                val rows = dao.getLatLonOnly(activityId)
                val maxPoints = 80
                val step = (rows.size / maxPoints).coerceAtLeast(1)
                val sampled = rows.filterIndexed { index, _ -> index % step == 0 }.map { it.lat to it.lon }
                _thumbnails.value = _thumbnails.value + (activityId to sampled)
            } catch (e: Exception) {
                _thumbnails.value = _thumbnails.value + (activityId to emptyList())
            }
        }
    }

    init {
        viewModelScope.launch {
            try {
                dao.observeCompleted().collect { activities ->
                    _uiState.value = ActivityHistoryUiState(
                        loadState = HistoryLoadState.LOADED,
                        activities = activities
                    )
                }
            } catch (e: Exception) {
                _uiState.value = ActivityHistoryUiState(loadState = HistoryLoadState.ERROR)
            }
        }
    }
}
