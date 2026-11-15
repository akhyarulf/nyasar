package com.nyasar.app.ui.profile

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.ui.theme.NyasarSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToLong

/** Rentang waktu ringkasan profil — "tahun berjalan" vs "seumur hidup". */
enum class ProfileStatsRange { THIS_YEAR, ALL_TIME }

/** Satu set angka yang ditampilkan sebagai satu baris ringkasan. */
data class ProfileTotals(
    val distanceMeters: Double = 0.0,
    val activityCount: Int = 0,
    val elevationGainM: Double = 0.0
)

data class ProfileStatsUiState(
    val thisYear: ProfileTotals = ProfileTotals(),
    val allTime: ProfileTotals = ProfileTotals(),
    val loading: Boolean = true
)

/**
 * Angka ringkasan perjalanan untuk header tab Profil.
 *
 * Sumbernya ActivityDao.observeCompletedAndDrafts() — daftar yang SUDAH
 * dibaca layar Riwayat, tanpa tabel/kolom baru dan tanpa query agregat
 * tambahan: tabel ini berskala manusia (ratusan baris), jadi penjumlahan di
 * Kotlin cukup dan otomatis reaktif saat satu aktivitas diubah/dihapus.
 * Kedua rentang dihitung sekali per emisi (bukan saat chip ditekan) supaya
 * berpindah chip tidak pernah memicu kerja disk.
 */
class ProfileStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).activityDao()

    private val _state = MutableStateFlow(ProfileStatsUiState())
    val state: StateFlow<ProfileStatsUiState> = _state.asStateFlow()

    private val _range = MutableStateFlow(ProfileStatsRange.THIS_YEAR)
    val range: StateFlow<ProfileStatsRange> = _range.asStateFlow()

    init {
        viewModelScope.launch {
            dao.observeCompletedAndDrafts()
                .map { rows -> totalsFor(rows) }
                .flowOn(Dispatchers.Default)
                .catch {
                    // Riwayat sudah punya jalur error sendiri; strip ini
                    // dekoratif, jadi gagal baca cukup menampilkan nol
                    // daripada menggagalkan header tab.
                    _state.value = ProfileStatsUiState(loading = false)
                }
                .collect { (year, all) ->
                    _state.value = ProfileStatsUiState(
                        thisYear = year,
                        allTime = all,
                        loading = false
                    )
                }
        }
    }

    fun setRange(range: ProfileStatsRange) {
        _range.value = range
    }

    private fun totalsFor(rows: List<ActivityEntity>): Pair<ProfileTotals, ProfileTotals> {
        val yearStart = startOfYearEpochMs()
        var yearDistance = 0.0
        var yearGain = 0.0
        var yearCount = 0
        var allDistance = 0.0
        var allGain = 0.0
        for (row in rows) {
            // observeCompletedAndDrafts() hanya berisi completed + draft,
            // tapi penjaga ini menjaga kalau query DAO melebar nanti.
            if (row.status == "discarded") continue
            val gain = row.elevationGainM ?: 0.0
            allDistance += row.distanceMeters
            allGain += gain
            if (row.startedAtEpochMs >= yearStart) {
                yearDistance += row.distanceMeters
                yearGain += gain
                yearCount++
            }
        }
        return ProfileTotals(yearDistance, yearCount, yearGain) to
            ProfileTotals(allDistance, rows.size, allGain)
    }

    private fun startOfYearEpochMs(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * Strip angka di atas daftar Riwayat: tiga metrik + pemilih rentang.
 *
 * Sengaja TIDAK ikut ter-scroll bersama daftar: satu-satunya alasan orang
 * membuka tab ini adalah melihat angkanya, dan strip setinggi ±88dp masih
 * menyisakan ruang daftar yang lega.
 */
@Composable
fun ProfileStatsStrip(
    modifier: Modifier = Modifier,
    viewModel: ProfileStatsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val range by viewModel.range.collectAsState()
    val totals = if (range == ProfileStatsRange.THIS_YEAR) state.thisYear else state.allTime
    val placeholder = "\u2014"

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(
                start = NyasarSpacing.lg,
                end = NyasarSpacing.lg,
                top = NyasarSpacing.md,
                bottom = NyasarSpacing.sm
            )
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(
                    value = if (state.loading) placeholder else formatTotalDistance(totals.distanceMeters),
                    label = stringResource(R.string.distance),
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    value = if (state.loading) placeholder else totals.activityCount.toString(),
                    label = stringResource(R.string.profile_stats_activities),
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    value = if (state.loading) placeholder else formatTotalGain(totals.elevationGainM),
                    label = stringResource(R.string.elevation_gain),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(NyasarSpacing.sm))
            Row {
                FilterChip(
                    selected = range == ProfileStatsRange.THIS_YEAR,
                    onClick = { viewModel.setRange(ProfileStatsRange.THIS_YEAR) },
                    label = { Text(stringResource(R.string.profile_stats_this_year)) }
                )
                Spacer(Modifier.width(NyasarSpacing.sm))
                FilterChip(
                    selected = range == ProfileStatsRange.ALL_TIME,
                    onClick = { viewModel.setRange(ProfileStatsRange.ALL_TIME) },
                    label = { Text(stringResource(R.string.profile_stats_all_time)) }
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Total jarak: satu desimal di bawah 100 km supaya "3,4 km" tetap terbaca,
 *  tanpa desimal di atasnya — "1.284 km" lebih jujur daripada "1.284,37 km". */
private fun formatTotalDistance(meters: Double): String =
    if (meters >= 100_000.0) "%.0f km".format(meters / 1000.0)
    else "%.1f km".format(meters / 1000.0)

/** Total elevasi selalu bilangan bulat meter — pecahan desimal di angka
 *  ribuan hanya menambah noise. */
private fun formatTotalGain(meters: Double): String =
    "%,d m".format(meters.roundToLong())
