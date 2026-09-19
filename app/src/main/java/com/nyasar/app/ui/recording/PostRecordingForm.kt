package com.nyasar.app.ui.recording

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nyasar.app.recording.RecordingUiState
import com.nyasar.app.ui.components.AnimatedScreen
import com.nyasar.app.ui.publish.PublishDifficulty
import com.nyasar.app.ui.publish.PublishTrailType
import com.nyasar.app.ui.theme.NyasarRadius
import kotlin.math.roundToInt
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

/**
 * Post-recording review — now the ONE place "save = publish" happens
 * (konsep 2026-09): stats + title + the old publish form fields (difficulty,
 * trail type, description, visibility) inline, and a single "Simpan
 * Aktivitas" button that persists locally, auto-backs-up and publishes in
 * one tap. Publishing needs an account/signal: without them the save still
 * completes (never network-dependent) and the publish rides the offline
 * queue — the note under the button says so.
 *
 * Flow: Stop Recording → Review (this) → Simpan → History.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostRecordingForm(
    summary: RecordingUiState,
    /** True when the account can publish right now — decides whether the
     *  publish fields render active or with a "queued" hint. */
    canPublish: Boolean,
    onSave: (
        title: String,
        publishEnabled: Boolean,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        isPublic: Boolean
    ) -> Unit,
    /** Wikiloc-style "Save as Draft": everything above still applies —
     *  the activity saves + backs up, but publish is DEFERRED until the
     *  user opens the draft later from History. */
    onSaveDraft: (
        title: String,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        isPublic: Boolean
    ) -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember { mutableStateOf(context.getString(R.string.activity_title_format, formatTimeForTitle(summary.elapsedTimeMs))) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Publish form state — defaults mirror PublishFormSheet: not rated,
    // unspecified trail, no description, public. Folding it INTO the save
    // form removes the second round-trip the old activity-publish sheet
    // required.
    var difficulty by remember { mutableStateOf(PublishDifficulty.NONE) }
    var difficultyDescription by remember { mutableStateOf("") }
    var trailType by remember { mutableStateOf(PublishTrailType.NONE) }
    var description by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_activity)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showDiscardDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.discard_cd),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { padding ->
        // Shared page entrance (fade + rise) — consistent with Settings and
        // the other static form screens.
        AnimatedScreen {
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
                label = { Text(stringResource(R.string.activity_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            // Stats section
            Text(stringResource(R.string.statistics), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(NyasarRadius.md),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                            label = stringResource(R.string.stat_distance)
                        )
                        StatItem(
                            value = formatDuration(summary.elapsedTimeMs),
                            label = stringResource(R.string.stat_duration)
                        )
                        StatItem(
                            value = formatDuration(summary.movingTimeMs),
                            label = stringResource(R.string.stat_walking_time)
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
                            label = stringResource(R.string.stat_elev_gain_up)
                        )
                        StatItem(
                            value = "↓ ${summary.elevationLossM.roundToInt()} m",
                            label = stringResource(R.string.stat_elev_gain_down)
                        )
                        StatItem(
                            value = summary.currentSpeedKmh?.let { "%.1f km/h".format(it) } ?: "-",
                            label = stringResource(R.string.speed)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Publish fields (formerly the PublishRouteSheet) — the same
            //    difficulty/trail/description/visibility contract, inline.
            Text(stringResource(R.string.publish_section_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.publish_difficulty_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                difficultyOptions().forEach { option ->
                    FilterChip(
                        selected = difficulty == option,
                        onClick = { difficulty = option },
                        label = { Text(option.toLabel()) }
                    )
                }
            }
            if (difficulty != PublishDifficulty.NONE) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = difficultyDescription,
                    onValueChange = { difficultyDescription = it },
                    label = { Text(stringResource(R.string.publish_difficulty_desc_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.publish_trail_type_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                trailTypeOptions().forEach { option ->
                    FilterChip(
                        selected = trailType == option,
                        onClick = { trailType = option },
                        label = { Text(option.toLabel()) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.publish_desc_label)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.publish_visibility_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = isPublic,
                    onClick = { isPublic = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.publish_visibility_public))
                }
                SegmentedButton(
                    selected = !isPublic,
                    onClick = { isPublic = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.publish_visibility_private))
                }
            }
            Text(
                stringResource(
                    if (isPublic) R.string.publish_visibility_public_hint
                    else R.string.publish_visibility_private_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            // Transparency note — same contract the publish sheet carried:
            // raw points never leave the device.
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(R.string.publish_points_line, summary.pointCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Action buttons — ONE save action now. Save is local-first and
            // never blocked by the account state; publishing happens behind
            // it (immediately, or queued offline — the hint says which).
            // v7 fix retained: an empty recording must not be saveable.
            val canSave = summary.distanceMeters >= 10.0 ||
                summary.elapsedTimeMs >= 10_000L
            Button(
                onClick = {
                    onSave(
                        title.ifBlank {
                            context.getString(R.string.activity_title_format, formatTimeForTitle(summary.elapsedTimeMs))
                        },
                        true,
                        difficulty,
                        difficultyDescription.trim().takeIf { it.isNotEmpty() },
                        trailType,
                        description.trim().takeIf { it.isNotEmpty() },
                        isPublic
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save_activity))
            }
            if (!canSave) {
                Text(
                    stringResource(R.string.save_activity_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )
            } else if (!canPublish) {
                // Account/signal missing: save still works, publish queues.
                Text(
                    stringResource(R.string.save_publish_queued_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(12.dp))

            // Wikiloc-style draft: same form data, publish deferred. Can
            // save a draft even when canSave is false? No — an empty track
            // has nothing to publish later either; keep the canSave gate.
            OutlinedButton(
                onClick = {
                    onSaveDraft(
                        title.ifBlank {
                            context.getString(R.string.activity_title_format, formatTimeForTitle(summary.elapsedTimeMs))
                        },
                        difficulty,
                        difficultyDescription.trim().takeIf { it.isNotEmpty() },
                        trailType,
                        description.trim().takeIf { it.isNotEmpty() },
                        isPublic
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(stringResource(R.string.save_as_draft))
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showDiscardDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.discard_activity))
            }
        }
        }
    }

    // Discard confirmation dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.discard_confirm)) },
            text = {
                Text(stringResource(R.string.discard_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onDiscard()
                    }
                ) {
                    Text(stringResource(R.string.discard_btn), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.cancel))
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

private fun difficultyOptions() = listOf(
    PublishDifficulty.NONE,
    PublishDifficulty.EASY,
    PublishDifficulty.MODERATE,
    PublishDifficulty.DIFFICULT,
    PublishDifficulty.VERY_DIFFICULT
)

private fun trailTypeOptions() = listOf(
    PublishTrailType.NONE,
    PublishTrailType.LOOP,
    PublishTrailType.OUT_AND_BACK,
    PublishTrailType.POINT_TO_POINT
)

@Composable
private fun PublishDifficulty.toLabel(): String = stringResource(
    when (this) {
        PublishDifficulty.NONE -> R.string.publish_difficulty_none
        PublishDifficulty.EASY -> R.string.publish_difficulty_easy
        PublishDifficulty.MODERATE -> R.string.publish_difficulty_moderate
        PublishDifficulty.DIFFICULT -> R.string.publish_difficulty_difficult
        PublishDifficulty.VERY_DIFFICULT -> R.string.publish_difficulty_very_difficult
    }
)

@Composable
private fun PublishTrailType.toLabel(): String = stringResource(
    when (this) {
        PublishTrailType.NONE -> R.string.publish_trail_none
        PublishTrailType.LOOP -> R.string.publish_trail_loop
        PublishTrailType.OUT_AND_BACK -> R.string.publish_trail_out_and_back
        PublishTrailType.POINT_TO_POINT -> R.string.publish_trail_point_to_point
    }
)

/** Carried over verbatim from the pre-merge PostRecordingForm. */
private fun formatTimeForTitle(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (m > 0) "${m}m${s}s" else "${s}s"
}

/** Private in RecordingScreen — duplicated here (5 lines) rather than
 *  widening its visibility for one caller. */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
