package com.nyasar.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.recording.SportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HistoryLoadState { LOADING, LOADED, ERROR }

data class ActivityHistoryUiState(
    val loadState: HistoryLoadState = HistoryLoadState.LOADING,
    val activities: List<ActivityEntity> = emptyList(),
    /** True while sport/month chips narrow the list to zero rows — the
     *  screen must then show a "no matches" state instead of the
     *  get-started empty state (whose CTA would be wrong mid-filter). */
    val filtersActive: Boolean = false
)

/**
 * Reads ActivityDao.observeCompletedAndDrafts() — already ordered newest
 * first by the SQL query itself, no new database or table. Collected manually
 * (rather than stateIn on the DAO Flow directly) so a thrown exception from
 * Room surfaces as ERROR state instead of silently cancelling the collector,
 * which is what stateIn would do on an uncaught exception.
 *
 * v0.9.3 filters: sport-type chips + month chips (both optional, both
 * combined). Filtering runs in Kotlin over the already-observed list — the
 * table is human-scaled (hundreds of rows at most) — rather than adding a
 * third DAO method; the DAO stays one query and the beans stay one file.
 */
class ActivityHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).activityDao()

    private val _uiState = MutableStateFlow(ActivityHistoryUiState())
    val uiState: StateFlow<ActivityHistoryUiState> = _uiState.asStateFlow()

    /** Unfiltered rows straight from the DAO — the source the filters
     *  re-apply against; never published to the screen directly. */
    private val rawActivities = MutableStateFlow<List<ActivityEntity>>(emptyList())

    /** Chip options derived from the raw data (only sport types / months the
     *  user actually has), so the rows never advertise a filter that yields
     *  an empty list by construction. */
    private val _sportOptions = MutableStateFlow<List<String>>(emptyList())
    val sportOptions: StateFlow<List<String>> = _sportOptions.asStateFlow()
    private val _monthOptions = MutableStateFlow<List<MonthBucket>>(emptyList())
    val monthOptions: StateFlow<List<MonthBucket>> = _monthOptions.asStateFlow()

    /** Active filter values — null = "Semua" (chip unchecked). */
    private val _sportFilter = MutableStateFlow<String?>(null)
    val sportFilter: StateFlow<String?> = _sportFilter.asStateFlow()
    private val _monthFilter = MutableStateFlow<String?>(null)
    val monthFilter: StateFlow<String?> = _monthFilter.asStateFlow()

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
                dao.observeCompletedAndDrafts().collect { activities ->
                    rawActivities.value = activities
                    rebuildOptions()
                    publishFiltered()
                }
            } catch (e: Exception) {
                _uiState.value = ActivityHistoryUiState(loadState = HistoryLoadState.ERROR)
            }
        }
    }

    fun setSportFilter(value: String?) {
        _sportFilter.value = value
        publishFiltered()
    }

    fun setMonthFilter(value: String?) {
        _monthFilter.value = value
        publishFiltered()
    }

    /** Re-applies both chips in one pass — the "gabung" contract: sport and
     *  month narrow the same list, AND-combined, not replacing each other. */
    private fun publishFiltered() {
        val raw = rawActivities.value
        val sf = _sportFilter.value
        val mf = _monthFilter.value
        var list = raw
        if (sf != null) list = list.filter { it.sportType == sf }
        if (mf != null) list = list.filter { monthKeyOf(it.startedAtEpochMs) == mf.key }
        _uiState.value = ActivityHistoryUiState(
            loadState = HistoryLoadState.LOADED,
            activities = list,
            filtersActive = sf != null || mf != null
        )
    }

    /** Sport chips = SportType enum order (stable UI), restricted to types
     *  present in the data. UNSPECIFIED included when present — its card
     *  already renders a neutral label, so it must remain reachable. */
    private fun rebuildOptions() {
        val raw = rawActivities.value
        val present = raw.map { it.sportType }.toSet()
        _sportOptions.value = SportType.entries.map { it.name }.filter { it in present }
        val byMonth = raw.groupBy { monthKeyOf(it.startedAtEpochMs) }
        _monthOptions.value = byMonth.keys
            .sortedDescending()
            .mapNotNull { key ->
                // Count lives in the bucket so chips can show "Nov (3)"; the
                // label is rendered on the screen with the same ID locale the
                // cards themselves use (SimpleDateFormat, device zone).
                byMonth[key]?.let { MonthBucket(key, it.size, it.minOf { a -> a.startedAtEpochMs }) }
            }
    }

    /** "2026-11" bucket key from startedAtEpochMs (device local zone — same
     *  zone the cards' own date text uses, so chips and rows can't disagree
     *  about which month a midnight-hike belongs to). */
    private fun monthKeyOf(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        return "%04d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1
        )
    }

    /** Filter chip payload: stable "yyyy-MM" key + latest-first data. */
    data class MonthBucket(val key: String, val count: Int, val newestEpochMs: Long)
}
