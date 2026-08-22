package com.nyasar.app.ui.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nyasar.app.data.db.ActivityPhotoEntity
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Spec P3H §7/§20/§26: the Photos section inside Activity Detail — grid of
 * thumbnails, "+ Add Photo", and the "No photos yet" empty state. Uses a
 * fixed-height LazyVerticalGrid (not a full grid consuming its own scroll)
 * since this section lives inside ActivityDetailScreen's single outer
 * Column, same pattern as the Waypoint list above it.
 */
@Composable
fun PhotosSection(
    photos: List<ActivityPhotoEntity>,
    onAddClick: () -> Unit,
    onPhotoClick: (Int) -> Unit
) {
    Text("Photos" + if (photos.isNotEmpty()) " (${photos.size})" else "", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    if (photos.isEmpty()) {
        Text(
            "No photos yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    } else {
        // Fixed 3 columns, height derived from row count — spec §18
        // "gunakan lazy grid" so scrolling past 6+ photos doesn't decode
        // every thumbnail up front; each cell is a Coil AsyncImage, which
        // downsamples to the target size rather than full resolution.
        val rows = (photos.size + 2) / 3
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().height((rows * 96).dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            userScrollEnabled = false
        ) {
            items(photos.size) { index ->
                val photo = photos[index]
                AsyncImage(
                    model = File(photo.filePath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPhotoClick(index) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    OutlinedButton(onClick = onAddClick) {
        Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add Photo")
    }
}

/** Spec §1: "Take Photo" / "Choose from Gallery" chooser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPhotoChooserSheet(
    onTakePhoto: () -> Unit,
    onChooseFromGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text("Take Photo") },
                leadingContent = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                modifier = Modifier.clickable { onDismiss(); onTakePhoto() }
            )
            ListItem(
                headlineContent = { Text("Choose from Gallery") },
                leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                modifier = Modifier.clickable { onDismiss(); onChooseFromGallery() }
            )
        }
    }
}

/**
 * Spec §10/§19: fullscreen, pinch-zoom, swipe between photos, close —
 * explicitly NOT a photo editor (no crop/filter/etc). Built on
 * HorizontalPager (Compose foundation, no extra pager library) + a small
 * pointerInput-based pinch/pan (no zoom library dependency either) so this
 * doesn't pull in a third image-viewer package on top of Coil.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullscreenPhotoViewer(
    photos: List<ActivityPhotoEntity>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onDelete: (ActivityPhotoEntity) -> Unit
) {
    if (photos.isEmpty()) {
        onDismiss()
        return
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, photos.size - 1),
        pageCount = { photos.size }
    )
    var confirmingDelete by remember { mutableStateOf(false) }

    // A delete can shrink `photos` out from under the current page (spec
    // §11 "remove association -> update UI") — if the list becomes empty,
    // close the viewer entirely rather than showing a blank pager.
    LaunchedEffect(photos.size) {
        if (photos.isEmpty()) onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                if (page < photos.size) {
                    ZoomableImage(file = File(photos[page].filePath))
                }
            }

            TopAppBar(
                title = {
                    Text(
                        "Photo ${(pagerState.currentPage + 1).coerceAtMost(photos.size)} / ${photos.size}",
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tutup", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Hapus foto", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.4f))
            )
        }
    }

    if (confirmingDelete) {
        val current = photos.getOrNull(pagerState.currentPage)
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Hapus foto?") },
            text = { Text("Foto ini akan dihapus dari Activity. Foto asli di Gallery tidak terpengaruh.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    current?.let(onDelete)
                }) { Text("HAPUS") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("BATAL") }
            }
        )
    }
}

/** Minimal pinch-to-zoom + pan for one image — spec §10 "zoom jika library
 *  existing mendukung"; implemented directly since no zoom library exists
 *  in this project (audited), and a Modifier.pointerInput block is a small
 *  enough addition that pulling in a whole new dependency for it isn't
 *  warranted. Double-tap resets to 1x. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ZoomableImage(file: File) {
    var scale by remember(file) { mutableStateOf(1f) }
    var offsetX by remember(file) { mutableStateOf(0f) }
    var offsetY by remember(file) { mutableStateOf(0f) }

    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale, scaleY = scale,
                translationX = offsetX, translationY = offsetY
            )
            .pointerInput(file) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offsetX = if (scale > 1f) offsetX + pan.x else 0f
                    offsetY = if (scale > 1f) offsetY + pan.y else 0f
                }
            }
            .pointerInput(file) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f; offsetX = 0f; offsetY = 0f
                })
            }
    )
}
