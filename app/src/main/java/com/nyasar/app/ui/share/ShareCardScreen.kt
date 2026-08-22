package com.nyasar.app.ui.share

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
 * Share card screen for activities.
 * Allows users to:
 * - Preview the share card
 * - Choose background type (plain, gradient, route)
 * - Share as image
 * - Save to device
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
    var selectedBackground by remember { mutableStateOf("gradient") }
    var shareBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    // Generate bitmap when background changes
    LaunchedEffect(selectedBackground) {
        isGenerating = true
        shareBitmap = withContext(Dispatchers.Default) {
            ShareCardGenerator.generateShareCard(context, activity, trackPoints, selectedBackground)
        }
        isGenerating = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bagikan Aktivitas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Preview
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f),
                shape = RoundedCornerShape(16.dp)
            ) {
                shareBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Share Card Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } ?: Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Gray)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Background options
            Text("Pilih Background", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BackgroundOption(
                    label = "Gradient",
                    selected = selectedBackground == "gradient",
                    onClick = { selectedBackground = "gradient" }
                )
                BackgroundOption(
                    label = "Route",
                    selected = selectedBackground == "route",
                    onClick = { selectedBackground = "route" }
                )
                BackgroundOption(
                    label = "Plain",
                    selected = selectedBackground == "plain",
                    onClick = { selectedBackground = "plain" }
                )
            }

            Spacer(Modifier.height(24.dp))

            // Action buttons
            Button(
                onClick = {
                    shareBitmap?.let { bitmap ->
                        scope.launch {
                            shareImage(context, bitmap, activity.name)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = shareBitmap != null
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Bagikan")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    shareBitmap?.let { bitmap ->
                        scope.launch {
                            saveToGallery(context, bitmap, activity.name) { success ->
                                showSaveSuccess = success
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = shareBitmap != null
            ) {
                Text("Simpan ke Galeri")
            }

            if (showSaveSuccess) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tersimpan ke galeri!",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BackgroundOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .then(
                    when (label) {
                        "Gradient" -> Modifier.background(Brush.verticalGradient(listOf(Color(0xFF5A7562), Color(0xFF3A5542))))
                        "Route" -> Modifier.background(Color(0xFF5A7562))
                        else -> Modifier.background(Color(0xFFE0E0E0))
                    }
                )
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

private suspend fun shareImage(context: android.content.Context, bitmap: Bitmap, name: String) {
    withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "share_$name.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_TEXT, "Check out my activity: $name")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(android.content.Intent.createChooser(intent, "Bagikan via"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private suspend fun saveToGallery(
    context: android.content.Context,
    bitmap: Bitmap,
    name: String,
    onResult: (Boolean) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val filename = "Nyasar_${name}_${System.currentTimeMillis()}.png"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Nyasar")
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                    onResult(true)
                } ?: onResult(false)
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val imageFile = File(imagesDir, filename)

                FileOutputStream(imageFile).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                onResult(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(false)
        }
    }
}