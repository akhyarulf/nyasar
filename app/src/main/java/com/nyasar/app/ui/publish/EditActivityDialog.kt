package com.nyasar.app.ui.publish

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nyasar.app.R
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.supabase.PublishRepository

/**
 * Edit-Activity dialog (the menu item formerly called "Rename"). Prefills
 * from the best available metadata source:
 *  1. the cloud `routes` row when this activity is already published
 *     (owner edit — Wikiloc parity), falling back to
 *  2. the pending-publish row (draft / queued form data), or defaults.
 *
 * Saving runs through [PublishViewModel.editPublished] via the caller, which
 * persists locally (rename + pending row) and best-effort patches the cloud
 * row — never a second publish, never a duplicate (anti-double probe).
 */
@Composable
fun EditActivityDialog(
    activityId: String,
    initialTitle: String,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        sportType: com.nyasar.app.recording.SportType,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        isPublic: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    var meta by remember { mutableStateOf<PublishRepositoryMeta?>(null) }
    // Current sport from the LOCAL activity row — the source of truth for
    // the activity itself (cloud meta carries publish fields only).
    var currentSport by remember {
        mutableStateOf(com.nyasar.app.recording.SportType.UNSPECIFIED)
    }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(activityId) {
        currentSport = runCatching {
            com.nyasar.app.recording.SportType.fromString(
                AppDatabase.get(context).activityDao().getById(activityId)?.sportType
            )
        }.getOrDefault(com.nyasar.app.recording.SportType.UNSPECIFIED)
        // Prefill source priority: cloud (published truth) → pending queue
        // (draft/queued form data) → defaults. Both probes are cheap and
        // null-safe offline.
        val pending = runCatching {
            AppDatabase.get(context).pendingPublishDao().takeOldestForSource(activityId)
        }.getOrNull()
        val cloud = runCatching {
            PublishRepository().fetchPublishedMeta(sourceActivityId = activityId)
        }.getOrNull()
        meta = cloud?.let {
            PublishRepositoryMeta(it.isPublic, it.difficulty, it.difficultyDescription, it.trailType, it.description)
        } ?: pending?.let {
            PublishRepositoryMeta(it.isPublic, it.difficulty, it.difficultyDescription, it.trailType, it.description)
        } ?: PublishRepositoryMeta(
            isPublic = true, difficulty = null, difficultyDescription = null,
            trailType = null, description = null
        )
        loading = false
    }

    if (loading) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.edit_activity)) },
            text = {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        )
    } else {
        EditPublishSheet(
            initialName = initialTitle,
            meta = meta ?: PublishRepositoryMeta(true, null, null, null, null),
            initialSportType = currentSport,
            onDismiss = onDismiss,
            // The sheet's sport slot is nullable (routes hide the section);
            // this dialog always has a concrete activity sport — coalesce.
            onSave = { title, sportType, difficulty, diffDesc, trailType, desc, isPublic ->
                onSave(
                    title,
                    sportType ?: com.nyasar.app.recording.SportType.UNSPECIFIED,
                    difficulty, diffDesc, trailType, desc, isPublic
                )
            }
        )
    }
}
