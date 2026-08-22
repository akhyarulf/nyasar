package com.nyasar.app.ui.share

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Strava-like share activity screen with a HorizontalPager carousel of
 * 6 free templates. All templates are immediately usable — no subscription
 * or paywall of any kind.
 *
 * Flow: pager shows card previews → user swipes to pick template →
 * taps "Bagikan" → Android share sheet (Intent.ACTION_SEND).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCardScreen(
    activity: ActivityEntity,
    trackPoints: List<TrackPoint>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val templates = ShareCardGenerator.TEMPLATES

    // Pre-generate all bitmaps on first composition (on IO, not main)
    var bitmaps by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    LaunchedEffect(activity.id, trackPoints.size) {
        bitmaps = withContext(Dispatchers.Default) {
            templates.associateWith { tpl ->
                ShareCardGenerator.generate(activity, trackPoints, tpl)
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { templates.size })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share Activity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup")
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
                    modifier = Modifier.fillMaxSize(),
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
                    scope.launch { shareImage(context, bmp, activity.name) }
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
                Text("Bagikan", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    val tpl = templates[pagerState.currentPage]
                    val bmp = bitmaps[tpl] ?: return@OutlinedButton
                    scope.launch { saveToGallery(context, bmp, activity.name) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(48.dp),
                enabled = bitmaps.isNotEmpty(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Simpan ke Galeri")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private suspend fun shareImage(context: android.content.Context, bitmap: Bitmap, name: String) {
    withContext(Dispatchers.IO) {
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
            context.startActivity(android.content.Intent.createChooser(intent, "Bagikan via"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private suspend fun saveToGallery(
    context: android.content.Context, bitmap: Bitmap, name: String
) {
    withContext(Dispatchers.IO) {
        try {
            val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val filename = "Nyasar_${safeName}_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Nyasar")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { s ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, s)
                    }
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                FileOutputStream(File(dir, filename)).use { s ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, s)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
