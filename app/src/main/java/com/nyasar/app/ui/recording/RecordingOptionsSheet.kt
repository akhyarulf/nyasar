package com.nyasar.app.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nyasar.app.R
import com.nyasar.app.ui.theme.NyasarContentWidth
import com.nyasar.app.ui.theme.NyasarRadius

/**
 * Options that belong to the CURRENT recording session, pulled out of the
 * far-away Settings screen so they can be reached from the map in two taps
 * (hero screen: the map, not the menu). Rows follow the same anatomy as the
 * reference app's quick-options sheet — round icon chip, title, one-line
 * explanation, switch on the trailing edge — but the accent color is Nyasar's
 * own theme primary (green), not an outside palette, so the sheet reads as
 * part of the app in both light and dark themes.
 *
 * Every row here maps to a real DataStore preference (SettingsRepository):
 * no placeholder rows, and a toggle never lies about having an effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingOptionsSheet(
    autoPauseEnabled: Boolean,
    onAutoPauseChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    offRouteAlertsEnabled: Boolean,
    onOffRouteAlertsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = NyasarContentWidth.sheetMaxWidth)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            Text(
                stringResource(R.string.rec_options_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
            )
            Text(
                stringResource(R.string.rec_options_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp)
            )
            Spacer(Modifier.height(14.dp))

            // Rows live in ONE rounded card with hairline dividers between
            // them (reference-sheet anatomy) — a flat list of separate
            // surfaces would read as three unrelated switches.
            Surface(
                shape = RoundedCornerShape(NyasarRadius.lg),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column {
                    RecordingOptionRow(
                        icon = Icons.Default.Pause,
                        title = stringResource(R.string.rec_options_auto_pause),
                        subtitle = stringResource(R.string.rec_options_auto_pause_desc),
                        checked = autoPauseEnabled,
                        onCheckedChange = onAutoPauseChange
                    )
                    OptionDivider()
                    RecordingOptionRow(
                        icon = Icons.Default.ScreenLockPortrait,
                        title = stringResource(R.string.rec_options_keep_screen_on),
                        subtitle = stringResource(R.string.rec_options_keep_screen_on_desc),
                        checked = keepScreenOn,
                        onCheckedChange = onKeepScreenOnChange
                    )
                    OptionDivider()
                    RecordingOptionRow(
                        icon = Icons.Default.Route,
                        title = stringResource(R.string.rec_options_off_route),
                        subtitle = stringResource(R.string.rec_options_off_route_desc),
                        checked = offRouteAlertsEnabled,
                        onCheckedChange = onOffRouteAlertsChange
                    )
                }
            }

            // Section footnote (borrowed from the reference app's settings
            // pages): the 60 m threshold is the one number a label cannot
            // convey, and an unexplained switch is what makes a settings
            // page feel machine-written. Same copy as the Settings row.
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.rec_options_off_route_footnote),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
    }
}

@Composable
private fun OptionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

/**
 * One option: icon chip + title + explanation + switch. The whole row is the
 * tap target (a switch alone is a small target on a moving hike), while the
 * switch keeps its own semantics for TalkBack.
 */
@Composable
private fun RecordingOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
