package com.nyasar.app.ui.history

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.nyasar.app.recording.ShareMetric
import com.nyasar.app.recording.SportType
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.map.MapSnapshotHelper
import com.nyasar.app.ui.map.MapSnapshotResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

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
    onShareActivity: (String) -> Unit,
    onShareGpx: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                        stringResource(R.string.history_load_error),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                HistoryLoadState.LOADED -> {
                    if (state.activities.isEmpty()) {
                        Text(
                            stringResource(R.string.no_history),
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
                                    onClick = { onOpenActivity(activity.id) },
                                    onShare = { onShareActivity(activity.id) },
                                    onShareGpx = { onShareGpx(activity.id) }
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
    onClick: () -> Unit,
    onShare: () -> Unit,
    onShareGpx: () -> Unit
) {
    LaunchedEffect(activity.id) { onThumbnailNeeded() }

    // Real map snapshot state — stores bitmap + bounds together
    val context = LocalContext.current
    val provider = remember { TileProviderFactory.default() }
    var mapSnapshotResult by remember { mutableStateOf<MapSnapshotResult?>(null) }
    LaunchedEffect(activity.id, thumbnail) {
        val track = thumbnail ?: return@LaunchedEffect
        if (track.size < 2) return@LaunchedEffect
        mapSnapshotResult = MapSnapshotHelper.getOrGenerate(
            context = context,
            activityId = activity.id,
            trackPoints = track,
            widthPx = 1080,
            heightPx = 640,
            styleUrl = provider.styleUrl()
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column {
            Column(Modifier.padding(16.dp)) {
                // Header: icon + sport label + date
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                SportType.fromString(activity.sportType).icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            SportType.fromString(activity.sportType).label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            formatDate(activity.startedAtEpochMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(activity.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)

                Spacer(Modifier.height(12.dp))
                // 3-column stat row — sport-aware: middle column is Pace for running, Elev Gain for hiking
                Row(Modifier.fillMaxWidth()) {
                    StatColumn(
                        label = "Distance",
                        value = "%.2f km".format(activity.distanceMeters / 1000.0),
                        modifier = Modifier.weight(1f)
                    )
                    val metric = SportType.fromString(activity.sportType).primaryMetric
                    if (metric == ShareMetric.PACE) {
                        val pace = if (activity.distanceMeters > 0) {
                            val paceMinPerKm = (activity.elapsedTimeMs / 60000.0) / (activity.distanceMeters / 1000.0)
                            val pm = paceMinPerKm.toInt()
                            val ps = ((paceMinPerKm - pm) * 60).toInt()
                            "%d:%02d /km".format(pm, ps)
                        } else "- /km"
                        StatColumn(label = "Pace", value = pace, modifier = Modifier.weight(1f))
                    } else {
                        StatColumn(
                            label = "Elev Gain",
                            value = "${activity.elevationGainM?.roundToInt() ?: 0} m",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    StatColumn(
                        label = "Time",
                        value = formatDuration(activity.elapsedTimeMs),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Route thumbnail — real map snapshot (fallback to grid)
            // Aspect ratio matches the snapshot bitmap (1080×640) so
            // ContentScale.FillBounds doesn't distort the map tiles.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1080f / 640f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                when {
                    thumbnail == null -> {
                        CircularProgressIndicator(
                            Modifier.align(Alignment.Center).size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    thumbnail.size < 2 -> { /* no track to draw */ }
                    else -> {
                        // Show real map snapshot if available, else fallback to grid
                        val snapshotResult = mapSnapshotResult
                        if (snapshotResult != null) {
                            Image(
                                bitmap = snapshotResult.bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                        // Draw the route line in the SAME coordinate space this
                        // Canvas actually occupies on screen (size.width/height —
                        // the Compose layout size), NOT the raw pixel dimensions
                        // of the snapshot bitmap. The Image above already stretches
                        // the bitmap to fill this exact container via
                        // ContentScale.FillBounds, so drawing against size.width/
                        // height here is what keeps the two aligned. Using the
                        // bitmap's own width/height instead (as before) put the
                        // route line in a different coordinate space than what
                        // FillBounds actually renders on screen, so the line ended
                        // up misaligned or nearly invisible depending on how far
                        // the bitmap's native resolution diverged from the
                        // on-screen size.
                        val routeLineColor = MaterialTheme.colorScheme.primary
                        Canvas(Modifier.fillMaxSize()) {
                            val canvasWidth = size.width
                            val canvasHeight = size.height

                            // Fallback grid if no snapshot
                            if (snapshotResult == null) {
                                val gridColor = Color(0x18000000)
                                val gridSpacing = 40.dp.toPx()
                                var gx = 0f
                                while (gx <= canvasWidth) {
                                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, canvasHeight), strokeWidth = 0.5.dp.toPx())
                                    gx += gridSpacing
                                }
                                var gy = 0f
                                while (gy <= canvasHeight) {
                                    drawLine(gridColor, Offset(0f, gy), Offset(canvasWidth, gy), strokeWidth = 0.5.dp.toPx())
                                    gy += gridSpacing
                                }
                            }

                            // Route line — use snapshot bounds if available (so it
                            // aligns with map tiles), otherwise fallback to fit-in-center
                            val bounds = snapshotResult?.bounds
                            if (bounds != null) {
                                MapSnapshotHelper.drawTrackOnCanvas(
                                    canvas = this,
                                    trackPoints = thumbnail,
                                    bounds = bounds,
                                    strokeWidth = 6f,
                                    color = routeLineColor
                                )
                            } else {
                                // No snapshot bounds — compute local bounds and center
                                val lons = thumbnail.map { it.first }
                                val lats = thumbnail.map { it.second }
                                val minLon = lons.min(); val maxLon = lons.max()
                                val minLat = lats.min(); val maxLat = lats.max()
                                val lonSpan = (maxLon - minLon).takeIf { it > 0.0 } ?: 1.0
                                val latSpan = (maxLat - minLat).takeIf { it > 0.0 } ?: 1.0
                                val cosLat = cos(Math.toRadians((minLat + maxLat) / 2.0)).toFloat()
                                val lonDegWidth = (lonSpan * cosLat).toFloat()
                                val latDegHeight = latSpan.toFloat()
                                val maxDimen = maxOf(lonDegWidth, latDegHeight)
                                val targetSize = min(canvasWidth, canvasHeight) * 0.8f
                                val scale = if (maxDimen > 0f) targetSize / maxDimen else 1f
                                val routeWidth = lonDegWidth * scale
                                val routeHeight = latDegHeight * scale
                                val offsetX = (canvasWidth - routeWidth) / 2f
                                val offsetY = (canvasHeight - routeHeight) / 2f
                                val path = Path()
                                thumbnail.forEachIndexed { index, (lon, lat) ->
                                    val x = offsetX + ((lon - minLon).toFloat() * cosLat) * scale
                                    val y = offsetY + routeHeight - ((lat - minLat).toFloat() * scale)
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path, color = routeLineColor, style = Stroke(width = 6f))
                            }
                        }
                    }
                }
            }

            // Action row at bottom — two share buttons (GPX + Card), centered
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onShareGpx, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.bagikan_gpx),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = stringResource(R.string.share_card_cd),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(epochMs))
