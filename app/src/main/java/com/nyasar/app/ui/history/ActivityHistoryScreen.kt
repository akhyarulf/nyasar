package com.nyasar.app.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.data.db.ActivityEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Card-per-activity layout (spec ref: Strava's Activities feed) — was a
 * flat single-line ListItem before, which read as "kesederhana banget"
 * next to the reference. Each card shows the same stats as before
 * (distance/elevation gain/time) laid out like the reference's 3-column
 * row, plus a drawn route-shape thumbnail where the old row had nothing
 * visual at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityHistoryScreen(
    viewModel: ActivityHistoryViewModel = viewModel(),
    onOpenActivity: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Riwayat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.loadState) {
                HistoryLoadState.LOADING -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                HistoryLoadState.ERROR -> {
                    Text(
                        "Gagal memuat riwayat aktivitas.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                HistoryLoadState.LOADED -> {
                    if (state.activities.isEmpty()) {
                        Text(
                            "Belum ada aktivitas.\nMulai recording untuk melihat riwayat di sini.",
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(state.activities, key = { it.id }) { activity ->
                                ActivityCard(
                                    activity = activity,
                                    thumbnail = thumbnails[activity.id],
                                    onThumbnailNeeded = { viewModel.loadThumbnail(activity.id) },
                                    onClick = { onOpenActivity(activity.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: ActivityEntity,
    thumbnail: List<Pair<Double, Double>>?,
    onThumbnailNeeded: () -> Unit,
    onClick: () -> Unit
) {
    LaunchedEffect(activity.id) { onThumbnailNeeded() }

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column {
            Column(Modifier.padding(16.dp)) {
                // Header: icon + activity type context + date, same info
                // the old row's date-only trailing text had, just given a
                // clearer place to live (matches the reference's
                // name/date header above the stats).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Hiking,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatDate(activity.startedAtEpochMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(activity.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(12.dp))
                // 3-column stat row (spec ref layout: Distance / Elev Gain / Time)
                Row(Modifier.fillMaxWidth()) {
                    StatColumn(
                        label = "Distance",
                        value = "%.2f km".format(activity.distanceMeters / 1000.0),
                        modifier = Modifier.weight(1f)
                    )
                    StatColumn(
                        label = "Elev Gain",
                        value = "${activity.elevationGainM?.roundToInt() ?: 0} m",
                        modifier = Modifier.weight(1f)
                    )
                    StatColumn(
                        label = "Time",
                        value = formatDuration(activity.elapsedTimeMs),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Route-shape thumbnail — drawn directly from the activity's
            // own downsampled points (see ViewModel.loadThumbnail), not a
            // static/fake image. No map tiles behind it (that would mean
            // either a real MapLibre instance per card, too heavy for a
            // scrolling list, or bundling map imagery) — a tinted panel
            // with the track drawn on top reads clearly as "this
            // activity's shape" without pretending to be a live map.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                when {
                    thumbnail == null -> {
                        CircularProgressIndicator(
                            Modifier.align(Alignment.Center).size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    thumbnail.size < 2 -> { /* no track to draw (e.g. GPS never locked) — leave the tinted panel bare */ }
                    else -> {
                        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                            val lons = thumbnail.map { it.first }
                            val lats = thumbnail.map { it.second }
                            val minLon = lons.min(); val maxLon = lons.max()
                            val minLat = lats.min(); val maxLat = lats.max()
                            val lonSpan = (maxLon - minLon).takeIf { it > 0.0 } ?: 1.0
                            val latSpan = (maxLat - minLat).takeIf { it > 0.0 } ?: 1.0

                            val path = Path()
                            thumbnail.forEachIndexed { index, (lon, lat) ->
                                // Flip Y: lat increases north but canvas Y increases downward.
                                val x = ((lon - minLon) / lonSpan * size.width).toFloat()
                                val y = (size.height - (lat - minLat) / latSpan * size.height).toFloat()
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, color = Color(0xFFFC5200), style = Stroke(width = 6f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(epochMs))
