package com.nyasar.app.ui.publish

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.ui.theme.NyasarContentWidth

/**
 * Publish flow for one finished activity (Fase 2 slice 1). Collects only the
 * descriptive fields the `routes` schema wants (mountain/region/difficulty/
 * trail type/description); all statistics and the track itself come from the
 * recorded activity, so the form stays short and cannot contradict the data.
 *
 * The transparency contract is spelled out inside the sheet (see
 * publish_points_line / publish_waypoints_line / publish_privacy_note):
 * raw GPS points never leave the device — only the encoded polyline summary
 * and the gzip'ed GPX file, matching PROJECT_CONTEXT.md's local-first rule.
 *
 * [waypoints] is the caller's already-computed union (created-during +
 * linked — the same list Export/Share uses), so publish never recomputes or
 * disagrees with the other GPX-producing paths.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishRouteSheet(
    activityId: String,
    pointCount: Int,
    waypoints: List<WaypointEntity>,
    onDismiss: () -> Unit,
    viewModel: PublishViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val canPublish = remember { viewModel.canPublish }

    var mountain by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var difficulty by rememberSaveable { mutableStateOf(PublishDifficulty.NONE.name) }
    var difficultyDescription by rememberSaveable { mutableStateOf("") }
    var trailType by rememberSaveable { mutableStateOf(PublishTrailType.NONE.name) }
    var description by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        val s = state
        if (s is PublishState.Success) {
            Toast.makeText(context, R.string.publish_success, Toast.LENGTH_LONG).show()
            viewModel.reset()
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = NyasarContentWidth.formMaxWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.publish_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )

            if (!canPublish) {
                ErrorBanner(stringResource(R.string.publish_error_not_signed_in))
            }

            val idleError = (state as? PublishState.Idle)?.error
            idleError?.let { ErrorBanner(it.toMessage()) }

            OutlinedTextField(
                value = mountain,
                onValueChange = { mountain = it },
                label = { Text(stringResource(R.string.publish_mountain_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = region,
                onValueChange = { region = it },
                label = { Text(stringResource(R.string.publish_region_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                stringResource(R.string.publish_difficulty_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                difficultyOptions().forEach { option ->
                    FilterChip(
                        selected = difficulty == option.name,
                        onClick = { difficulty = option.name },
                        label = { Text(option.toLabel()) }
                    )
                }
            }
            if (difficulty != PublishDifficulty.NONE.name) {
                OutlinedTextField(
                    value = difficultyDescription,
                    onValueChange = { difficultyDescription = it },
                    label = { Text(stringResource(R.string.publish_difficulty_desc_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                stringResource(R.string.publish_trail_type_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                trailTypeOptions().forEach { option ->
                    FilterChip(
                        selected = trailType == option.name,
                        onClick = { trailType = option.name },
                        label = { Text(option.toLabel()) }
                    )
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.publish_desc_label)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            stringResource(R.string.publish_points_line, pointCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    if (waypoints.isNotEmpty()) {
                        Text(
                            stringResource(R.string.publish_waypoints_line, waypoints.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(start = 24.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            stringResource(R.string.publish_privacy_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            val publishing = state is PublishState.Publishing
            Button(
                onClick = {
                    viewModel.publish(
                        activityId = activityId,
                        waypoints = waypoints,
                        mountainName = mountain,
                        region = region,
                        difficulty = PublishDifficulty.valueOf(difficulty),
                        difficultyDescription = difficultyDescription,
                        trailType = PublishTrailType.valueOf(trailType),
                        description = description
                    )
                },
                enabled = canPublish && !publishing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (publishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.publish_cta))
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
    }
}

@Composable
private fun PublishUiError.toMessage(): String = stringResource(
    when (this) {
        PublishUiError.NOT_SIGNED_IN -> R.string.publish_error_not_signed_in
        PublishUiError.EMPTY_TRACK -> R.string.publish_error_empty_track
        PublishUiError.INSERT -> R.string.publish_error_insert
        PublishUiError.UPLOAD -> R.string.publish_error_upload
        PublishUiError.NETWORK -> R.string.publish_error_network
        PublishUiError.GENERIC -> R.string.publish_error_generic
    }
)

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
