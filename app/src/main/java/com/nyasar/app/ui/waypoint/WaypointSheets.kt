package com.nyasar.app.ui.waypoint

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nyasar.app.data.db.WaypointCategory
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

/**
 * Add/Edit form (spec P3E2): name, category, note. Coordinates/elevation
 * are shown read-only (they come from where the user tapped, or from the
 * existing waypoint being edited) — not editable fields, since P3E2 scope
 * is metadata editing, not repositioning a pin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointFormSheet(
    title: String,
    initialName: String,
    initialCategory: WaypointCategory,
    initialNote: String,
    lat: Double,
    lon: Double,
    elevationM: Double?,
    onDismiss: () -> Unit,
    onSave: (name: String, category: WaypointCategory, note: String?) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialName) }
    var category by remember { mutableStateOf(initialCategory) }
    var note by remember { mutableStateOf(initialNote) }
    // Guards the Save button against a double-tap firing two saves before
    // the sheet has a chance to dismiss (spec: "jangan membuat duplicate
    // waypoint karena UI event berulang").
    var saving by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.waypoint_name)) },
                placeholder = { Text(category.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.category), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(WaypointCategory.entries) { cat ->
                    CategoryChip(
                        category = cat,
                        selected = cat == category,
                        onClick = { category = cat }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.waypoint_note)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "%.5f, %.5f".format(lat, lon) + (elevationM?.let { " · ${it.toInt()} m" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.delete))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (!saving) {
                            saving = true
                            onSave(name.trim(), category, note.trim())
                        }
                    },
                    enabled = !saving
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(category: WaypointCategory, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) category.color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.5.dp, category.color) else null
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(category.color)
            )
            Spacer(Modifier.width(6.dp))
            Text(category.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Spec P3E2 detail: nama, kategori, elevasi, koordinat, catatan, jarak
 *  dari user (jika ada fix GPS). Edit/Delete actions live here too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointDetailSheet(
    waypoint: WaypointEntity,
    distanceFromUserMeters: Double?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val category = WaypointCategory.fromStorageValue(waypoint.category)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(category.color),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(category.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(waypoint.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(category.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))
            DetailRow(stringResource(R.string.coordinate), "%.5f, %.5f".format(waypoint.lat, waypoint.lon))
            waypoint.elevationM?.let { DetailRow(stringResource(R.string.elevation_label), "${it.toInt()} m") }
            distanceFromUserMeters?.let {
                val label = if (it >= 1000) "%.1f km".format(it / 1000.0) else "${it.toInt()} m"
                DetailRow(stringResource(R.string.distance_from_you), label)
            }
            if (!waypoint.note.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(waypoint.note, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.edit))
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
