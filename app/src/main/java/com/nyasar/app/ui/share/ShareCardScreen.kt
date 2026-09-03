package com.nyasar.app.ui.share

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.map.MapSnapshotHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

/**
 * Strava-like share activity screen with a HorizontalPager carousel of
 * 6 free templates. All templates are immediately usable — no subscription
 * or paywall of any kind.
 *
 * Flow: pager shows card previews → user swipes to pick template →
 * taps "Bagikan" → Android share sheet (Intent.ACTION_SEND).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShareCardScreen(
    activity: ActivityEntity,
    trackPoints: List<TrackPoint>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val templates = ShareCardGenerator.TEMPLATES

    // Pre-generate map snapshot + all bitmaps on first composition
    var bitmaps by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    LaunchedEffect(activity.id, trackPoints.size) {
        // Generate map snapshot first (cached to disk)
        val provider = TileProviderFactory.default()
        val snapshotResult = withContext(Dispatchers.IO) {
            MapSnapshotHelper.generateSync(
                context = context,
                activityId = activity.id,
                trackPoints = trackPoints.map { it.lat to it.lon },
                widthPx = 1080,
                heightPx = 1344, // 70% of 1920
                styleUrl = provider.styleUrl()
                // NOTE: verticalOffsetFraction removed — it was shifting the
                // visible camera area upward by 15%, which pushed the
                // bottom end of longer routes toward/under the map's edge
                // and made the route look "cropped" compared to List
                // History's snapshot (which uses no offset at all, at a
                // different 1080x640 aspect ratio). The dark gradient at
                // the bottom of this template is for text legibility only
                // and doesn't need the whole route composition sacrificed
                // to avoid it — computeBounds() already fits the full
                // route with padding for this card's own aspect ratio,
                // same as it does for List History.
            )
        }
        bitmaps = withContext(Dispatchers.Default) {
            templates.associateWith { tpl ->
                ShareCardGenerator.generate(
                    context, activity, trackPoints, tpl,
                    mapSnapshot = snapshotResult?.bitmap,
                    mapBounds = snapshotResult?.bounds
                )
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { templates.size })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_activity)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_cd2))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Card carousel
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
                pageSpacing = 16.dp
            ) { page ->
                val tpl = templates[page]
                val bmp = bitmaps[tpl]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (bmp != null) {
                        // For transparent templates, show checkerboard hint
                        val isTransparent = tpl in listOf("stats", "route", "grid")
                        Box(Modifier.fillMaxSize()) {
                            if (isTransparent) {
                                // Checkerboard background to indicate transparency
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFFCCCCCC))
                                )
                                // Draw checkerboard pattern
                                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                    val tileSize = 24.dp.toPx()
                                    for (row in 0..(size.height / tileSize).toInt()) {
                                        for (col in 0..(size.width / tileSize).toInt()) {
                                            if ((row + col) % 2 == 0) {
                                                drawRect(
                                                    Color(0xFFAAAAAA),
                                                    topLeft = androidx.compose.ui.geometry.Offset(
                                                        col * tileSize, row * tileSize
                                                    ),
                                                    size = androidx.compose.ui.geometry.Size(tileSize, tileSize)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = ShareCardGenerator.templateLabel(tpl),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Gray)
                        ) {
                            CircularProgressIndicator(
                                Modifier.align(Alignment.Center).size(32.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }

            // Dot indicators
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                templates.forEachIndexed { index, _ ->
                    val isActive = pagerState.currentPage == index
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isActive) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            // Share + Save buttons
            Button(
                onClick = {
                    val tpl = templates[pagerState.currentPage]
                    val bmp = bitmaps[tpl] ?: return@Button
                    scope.launch {
                        val ok = shareImage(context, bmp, activity.name)
                        // Bug fix: this silently did nothing on failure
                        // before (share sheet just never opened, no error,
                        // no indication anything was wrong) — only show a
                        // Toast on failure since a successful share already
                        // has its own visible confirmation, the system
                        // chooser sheet opening.
                        if (!ok) {
                            Toast.makeText(context, context.getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(56.dp),
                enabled = bitmaps.isNotEmpty(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_activity), style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    val tpl = templates[pagerState.currentPage]
                    val bmp = bitmaps[tpl] ?: return@OutlinedButton
                    scope.launch {
                        val ok = saveToGallery(context, bmp, activity.name)
                        // Bug fix: saved successfully but gave no feedback
                        // at all — no toast, no snackbar, nothing — so the
                        // user had no way to tell it worked short of
                        // opening their gallery app to check.
                        val messageRes = if (ok) R.string.saved_to_gallery_success else R.string.saved_to_gallery_failed
                        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(48.dp),
                enabled = bitmaps.isNotEmpty(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.save_to_gallery))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private suspend fun shareImage(context: android.content.Context, bitmap: Bitmap, name: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(context.cacheDir, "share_${safeName}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_TEXT, "Check out my activity: $name #Nyasar")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(intent, context.getString(R.string.share_via))
            // Bug fix: context here can be a non-Activity context depending
            // on how LocalContext.current resolves in this composition —
            // startActivity() on ACTION_CHOOSER from a non-Activity context
            // requires FLAG_ACTIVITY_NEW_TASK or it silently throws
            // (caught below, but previously swallowed with only
            // e.printStackTrace() — invisible to the user, which is
            // exactly "tombol gaada lanjutannya": the chooser sheet simply
            // never opened, no crash, no error shown, nothing).
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

private suspend fun saveToGallery(
    context: android.content.Context, bitmap: Bitmap, name: String
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val filename = "Nyasar_${safeName}_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Nyasar")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return@withContext false
                context.contentResolver.openOutputStream(uri)?.use { s ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, s)
                } ?: return@withContext false
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                FileOutputStream(File(dir, filename)).use { s ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, s)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
