package com.nyasar.app.ui.recording

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nyasar.app.data.db.ActivityPhotoEntity
import com.nyasar.app.recording.RecordingUiState
import kotlin.math.roundToInt

/**
 * Post-recording review screen that allows the user to:
 * - Review recording stats
 * - Add a custom title
 * - View and manage photos
 * - Save or discard the activity
 *
 * Flow: Stop Recording → Review → Save/Discard
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostRecordingForm(
    summary: RecordingUiState,
    photos: List<ActivityPhotoEntity>,
    onSave: (title: String) -> Unit,
    onDiscard: () -> Unit,
    onAddPhoto: () -> Unit,
    onDeletePhoto: (ActivityPhotoEntity) -> Unit,
    onBack: () -> Unit
) {
    var title by remember { mutableStateOf("Aktivitas ${formatTimeForTitle(summary.elapsedTimeMs)}") }
    var showDiscardDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Aktivitas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = { showDiscardDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Buang",
                            tint = MaterialTheme.colorScheme.error
                        )
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
                .padding(16.dp)
        ) {
            // Title input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Judul Aktivitas") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            // Stats section
            Text("Statistik", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Primary stats row
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem(
                            value = "%.2f km".format(summary.distanceMeters / 1000.0),
                            label = "Jarak"
                        )
                        StatItem(
                            value = formatDuration(summary.elapsedTimeMs),
                            label = "Durasi"
                        )
                        StatItem(
                            value = formatDuration(summary.movingTimeMs),
                            label = "Waktu Bergerak"
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    // Secondary stats row
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem(
                            value = "↑ ${summary.elevationGainM.roundToInt()} m",
                            label = "Elevasi Naik"
                        )
                        StatItem(
                            value = "↓ ${summary.elevationLossM.roundToInt()} m",
                            label = "Elevasi Turun"
                        )
                        StatItem(
                            value = summary.currentSpeedKmh?.let { "%.1f km/h".format(it) } ?: "-",
                            label = "Kecepatan"
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Photos section
            Text("Foto", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            // Add photo button
            OutlinedButton(
                onClick = onAddPhoto,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Tambah Foto")
            }

            Spacer(Modifier.height(12.dp))

            // Photo list
            if (photos.isEmpty()) {
                Text(
                    "Belum ada foto",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                photos.forEach { photo ->
                    PhotoItem(
                        photo = photo,
                        onDelete = { onDeletePhoto(photo) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Action buttons
            Button(
                onClick = { onSave(title) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Simpan Aktivitas")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showDiscardDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Buang Aktivitas")
            }
        }
    }

    // Discard confirmation dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Buang Aktivitas?") },
            text = {
                Text("Aktivitas ini akan dihapus permanen. Tindakan ini tidak dapat dibatalkan.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onDiscard()
                    }
                ) {
                    Text("Buang", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PhotoItem(photo: ActivityPhotoEntity, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Photo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Foto ${photo.sortOrder + 1}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Ditambahkan: ${formatTimestamp(photo.createdAtEpochMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Hapus",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatTimeForTitle(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (m > 0) "${m}m${s}s" else "${s}s"
}

private fun formatTimestamp(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMs))
}