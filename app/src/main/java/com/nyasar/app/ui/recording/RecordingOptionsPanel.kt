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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nyasar.app.R

/**
 * Options that belong to the CURRENT recording session, pulled out of the
 * far-away Settings screen so they can be reached from the map in two taps
 * (hero screen: the map, not the menu).
 *
 * Sebelumnya ini adalah sheet MODAL yang dibuka dari ikon gear di atas peta.
 * Sekarang panelnya hidup DI DALAM sheet kontrol, tepat di bawah tombol
 * rekam, dan dibuka dengan menggeser/mengetuk handle atau mengetuk gear —
 * satu permukaan, dua jalan masuk, pola yang sama dengan aplikasi referensi
 * (gear di kanan atas dan panel di bawah memimpin ke opsi yang sama).
 * Alasannya bukan sekadar kosmetik: sheet modal menutupi peta dan membuat
 * rute yang sedang direkam hilang dari pandangan hanya untuk mematikan jeda
 * otomatis.
 *
 * Rows keep the reference-sheet anatomy — round icon chip, title, one-line
 * explanation, switch on the trailing edge — with Nyasar's own theme colors.
 * Every row here maps to a real DataStore preference (SettingsRepository):
 * no placeholder rows, and a toggle never lies about having an effect.
 */
@Composable
fun RecordingOptionsPanel(
    autoPauseEnabled: Boolean,
    onAutoPauseChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    offRouteAlertsEnabled: Boolean,
    onOffRouteAlertsChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        // Garis pemisah tipis dari baris kontrol di atasnya: panel ini
        // lapisan yang bisa dibuka-tutup, bukan bagian dari tombol rekam.
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
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
        OptionDivider()
        // Pengaturan penuh (audio cue, GPS, data) tetap di layar Settings —
        // baris ini jalan pintasnya, sama seperti baris "Pengaturan" di sheet
        // referensi.
        RecordingNavRow(
            icon = Icons.Default.Settings,
            title = stringResource(R.string.settings),
            onClick = onOpenSettings
        )
        Spacer(Modifier.height(10.dp))
        // Section footnote (borrowed from the reference app's settings
        // pages): the 60 m threshold is the one number a label cannot
        // convey, and an unexplained switch is what makes a settings page
        // feel machine-written. Same copy as the Settings row.
        Text(
            stringResource(R.string.rec_options_off_route_footnote),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
        )
        Spacer(Modifier.height(8.dp))
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
        OptionIconChip(icon)
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

/** Baris navigasi (buka layar lain): ikon yang sama, chevron di ujung. */
@Composable
private fun RecordingNavRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        OptionIconChip(icon)
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun OptionIconChip(icon: ImageVector) {
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
}
