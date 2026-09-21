package com.nyasar.app.ui.publish

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nyasar.app.R

/** Cloud metadata snapshot handed to [EditPublishSheet] (loose copy so the
 *  sheet UI never imports the repository's serializable row shape). */
data class PublishRepositoryMeta(
    val isPublic: Boolean,
    val difficulty: String?,
    val difficultyDescription: String?,
    val trailType: String?,
    val description: String?
)

/**
 * Edit form for an ALREADY-PUBLISHED source (activity or library route) —
 * the counterpart of the Review publish form / [PublishLibraryRouteSheet].
 * Wikiloc parity: once published, the owner can still revise the descriptive
 * metadata instead of being stuck with what they typed at the trailhead.
 *
 * Contract:
 *  - [onSave] receives every editable field INCLUDING the new title; the
 *    CALLER owns all persistence (Room rename + metadata sync + visibility
 *    toggle through setRouteVisibility) and closes the sheet itself.
 *  - The sheet renders the same building blocks as the publish forms, with
 *    fields prefilled from the cloud row's current metadata.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPublishSheet(
    initialName: String,
    meta: PublishRepositoryMeta,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        isPublic: Boolean
    ) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var difficultyDescription by rememberSaveable { mutableStateOf(meta.difficultyDescription.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(meta.description.orEmpty()) }
    var difficulty by rememberSaveable {
        mutableStateOf(
            meta.difficulty?.let { stored ->
                PublishDifficulty.values().firstOrNull { it.toApi() == stored }
            } ?: PublishDifficulty.NONE
        )
    }
    var trailType by rememberSaveable {
        mutableStateOf(
            meta.trailType?.let { stored ->
                PublishTrailType.values().firstOrNull { it.toApi() == stored }
            } ?: PublishTrailType.NONE
        )
    }
    var isPublic by rememberSaveable { mutableStateOf(meta.isPublic) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Text(
            stringResource(R.string.edit_publish_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            stringResource(R.string.edit_publish_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.edit_publish_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.publish_difficulty_label), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = difficulty == PublishDifficulty.NONE,
                    onClick = { difficulty = PublishDifficulty.NONE },
                    label = { Text(stringResource(R.string.publish_difficulty_none)) }
                )
                FilterChip(
                    selected = difficulty == PublishDifficulty.EASY,
                    onClick = { difficulty = PublishDifficulty.EASY },
                    label = { Text(stringResource(R.string.publish_difficulty_easy)) }
                )
                FilterChip(
                    selected = difficulty == PublishDifficulty.MODERATE,
                    onClick = { difficulty = PublishDifficulty.MODERATE },
                    label = { Text(stringResource(R.string.publish_difficulty_moderate)) }
                )
                FilterChip(
                    selected = difficulty == PublishDifficulty.DIFFICULT,
                    onClick = { difficulty = PublishDifficulty.DIFFICULT },
                    label = { Text(stringResource(R.string.publish_difficulty_difficult)) }
                )
                FilterChip(
                    selected = difficulty == PublishDifficulty.VERY_DIFFICULT,
                    onClick = { difficulty = PublishDifficulty.VERY_DIFFICULT },
                    label = { Text(stringResource(R.string.publish_difficulty_very_difficult)) }
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.publish_trail_label), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = trailType == PublishTrailType.NONE,
                    onClick = { trailType = PublishTrailType.NONE },
                    label = { Text(stringResource(R.string.publish_trail_none)) }
                )
                FilterChip(
                    selected = trailType == PublishTrailType.LOOP,
                    onClick = { trailType = PublishTrailType.LOOP },
                    label = { Text(stringResource(R.string.publish_trail_loop)) }
                )
                FilterChip(
                    selected = trailType == PublishTrailType.OUT_AND_BACK,
                    onClick = { trailType = PublishTrailType.OUT_AND_BACK },
                    label = { Text(stringResource(R.string.publish_trail_out_and_back)) }
                )
                FilterChip(
                    selected = trailType == PublishTrailType.POINT_TO_POINT,
                    onClick = { trailType = PublishTrailType.POINT_TO_POINT },
                    label = { Text(stringResource(R.string.publish_trail_point_to_point)) }
                )
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
            Text(stringResource(R.string.publish_visibility_label), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            // Text-only label: SegmentedButton renders its own leading check
            // when selected — Icon+Text inside the slot overlaps it (the
            // double-render bug fixed in the publish forms).
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = isPublic,
                    onClick = { isPublic = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text(stringResource(R.string.publish_visibility_public)) }
                SegmentedButton(
                    selected = !isPublic,
                    onClick = { isPublic = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text(stringResource(R.string.publish_visibility_private)) }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        name.trim(),
                        difficulty,
                        difficultyDescription.trim().takeIf { it.isNotEmpty() },
                        trailType,
                        description.trim().takeIf { it.isNotEmpty() },
                        isPublic
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.edit_publish_save))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
