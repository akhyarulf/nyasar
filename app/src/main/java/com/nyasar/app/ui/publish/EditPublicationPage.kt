package com.nyasar.app.ui.publish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nyasar.app.R
import com.nyasar.app.recording.SportType

/** Cloud metadata snapshot handed to [EditPublicationPage] (loose copy so the
 *  UI never imports the repository's serializable row shape). */
data class PublishRepositoryMeta(
    val isPublic: Boolean,
    val difficulty: String?,
    val difficultyDescription: String?,
    val trailType: String?,
    val description: String?
)

private const val NAME_MAX_CHARS = 60
private const val DESC_MAX_CHARS = 300
private const val DIFFICULTY_NOTE_MAX_CHARS = 120

/**
 * Edit form for an ALREADY-PUBLISHED source (activity or library route) — the
 * counterpart of the Review publish form / [PublishLibraryRouteSheet].
 *
 * Rendered as a FULL PAGE in its own window rather than a bottom sheet: six
 * sections plus three chip rows never fit a sheet comfortably, the keyboard
 * fought `imePadding` inside it, and the save button scrolled away with the
 * content. The page keeps a fixed footer instead, guards unsaved edits, and
 * wraps the chip rows so no option is hidden off the right edge.
 *
 * Contract:
 *  - [onSave] receives every editable field INCLUDING the new title; the
 *    CALLER owns all persistence (Room rename + metadata sync + visibility
 *    toggle through setRouteVisibility) and closes the page itself.
 *  - Fields are prefilled from the cloud row's current metadata.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditPublicationPage(
    title: String,
    initialName: String,
    meta: PublishRepositoryMeta,
    /** Activity-source pages get an editable sport type (routes have no sport
     *  column — callers pass null and the section hides itself). */
    initialSportType: SportType? = null,
    /** Non-editable identity line (date · distance) so the page says WHICH
     *  activity is being edited now that the map behind it is covered. */
    contextLine: String? = null,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        sportType: SportType?,
        difficulty: PublishDifficulty,
        difficultyDescription: String?,
        trailType: PublishTrailType,
        description: String?,
        isPublic: Boolean
    ) -> Unit
) {
    val baselineDifficulty = remember(meta) {
        meta.difficulty?.let { stored ->
            PublishDifficulty.values().firstOrNull { it.toApi() == stored }
        } ?: PublishDifficulty.NONE
    }
    val baselineTrailType = remember(meta) {
        meta.trailType?.let { stored ->
            PublishTrailType.values().firstOrNull { it.toApi() == stored }
        } ?: PublishTrailType.NONE
    }
    val baselineDesc = remember(meta) { meta.description.orEmpty() }
    val baselineDiffNote = remember(meta) { meta.difficultyDescription.orEmpty() }

    var name by rememberSaveable { mutableStateOf(initialName) }
    var sportType by rememberSaveable { mutableStateOf(initialSportType) }
    var difficulty by rememberSaveable { mutableStateOf(baselineDifficulty) }
    var difficultyNote by rememberSaveable { mutableStateOf(baselineDiffNote) }
    var trailType by rememberSaveable { mutableStateOf(baselineTrailType) }
    var description by rememberSaveable { mutableStateOf(baselineDesc) }
    var isPublic by rememberSaveable { mutableStateOf(meta.isPublic) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Dirty check drives the save button: an unchanged form can't be "saved",
    // so the action never looks available when it would do nothing.
    val dirty = name.trim() != initialName.trim() ||
        sportType != initialSportType ||
        difficulty != baselineDifficulty ||
        difficultyNote.trim() != baselineDiffNote.trim() ||
        trailType != baselineTrailType ||
        description.trim() != baselineDesc.trim() ||
        isPublic != meta.isPublic

    fun requestClose() {
        if (dirty) showDiscardConfirm = true else onDismiss()
    }

    fun submit() {
        onSave(
            name.trim(),
            sportType,
            difficulty,
            difficultyNote.trim().takeIf { it.isNotEmpty() },
            trailType,
            description.trim().takeIf { it.isNotEmpty() },
            isPublic
        )
    }

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { requestClose() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    }
                )

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    if (!contextLine.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Text(
                                contextLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= NAME_MAX_CHARS) name = it },
                        label = { Text(stringResource(R.string.edit_publish_name_label)) },
                        singleLine = true,
                        isError = name.isBlank(),
                        supportingText = {
                            Text(
                                text = if (name.isBlank()) {
                                    stringResource(R.string.edit_name_required)
                                } else {
                                    "${name.length}/$NAME_MAX_CHARS"
                                },
                                textAlign = if (name.isBlank()) TextAlign.Start else TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (initialSportType != null) {
                        EditSectionHeader(stringResource(R.string.activity_type_label))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SportType.entries.forEach { st ->
                                FilterChip(
                                    selected = sportType == st,
                                    onClick = { sportType = st },
                                    leadingIcon = {
                                        Icon(
                                            st.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text(stringResource(st.labelRes)) }
                                )
                            }
                        }
                    }

                    EditSectionHeader(stringResource(R.string.publish_difficulty_label))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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

                    // The difficulty note lives in the same row as difficulty,
                    // so it only makes sense once something is rated; the value
                    // is preserved untouched while "Not rated" is selected.
                    if (difficulty != PublishDifficulty.NONE) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = difficultyNote,
                            onValueChange = {
                                if (it.length <= DIFFICULTY_NOTE_MAX_CHARS) difficultyNote = it
                            },
                            label = { Text(stringResource(R.string.edit_difficulty_note_label)) },
                            singleLine = true,
                            supportingText = {
                                Text(
                                    "${difficultyNote.length}/$DIFFICULTY_NOTE_MAX_CHARS",
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    EditSectionHeader(stringResource(R.string.publish_trail_label))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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

                    EditSectionHeader(stringResource(R.string.publish_desc_label))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { if (it.length <= DESC_MAX_CHARS) description = it },
                        placeholder = { Text(stringResource(R.string.edit_desc_placeholder)) },
                        minLines = 4,
                        supportingText = {
                            Text(
                                "${description.length}/$DESC_MAX_CHARS",
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    EditSectionHeader(stringResource(R.string.publish_visibility_label))
                    // Text-only label: SegmentedButton renders its own leading
                    // check when selected — Icon+Text inside the slot overlaps
                    // it (the double-render bug fixed in the publish forms).
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
                    Spacer(Modifier.height(8.dp))
                    // The toggle is not cosmetic: it is the difference between
                    // "in Explore, anyone can save it" and "only I can see it".
                    Text(
                        stringResource(
                            if (isPublic) R.string.edit_visibility_public_hint
                            else R.string.edit_visibility_private_hint
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                }

                // Fixed footer: the primary action never scrolls out of reach,
                // with an explicit way out next to it (no swipe gesture here).
                Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { requestClose() },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = { submit() },
                            enabled = dirty && name.isNotBlank(),
                            modifier = Modifier.weight(1.6f)
                        ) { Text(stringResource(R.string.edit_publish_save)) }
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onDismiss()
                    }
                ) {
                    Text(
                        stringResource(R.string.edit_discard_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun EditSectionHeader(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
}
