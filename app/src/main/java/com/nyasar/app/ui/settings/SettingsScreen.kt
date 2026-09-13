package com.nyasar.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R
import com.nyasar.app.location.LocationRepository
import com.nyasar.app.ui.components.AnimatedScreen
import com.nyasar.app.ui.theme.NyasarRadius
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onOpenOfflineMaps: () -> Unit = {},
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        val current = settings
        if (current == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        // Entrance: whole page fades+rises once (shared AnimatedScreen).
        // Layout: every section is ONE grouped card (hairline border +
        // shared radius, same tile language as the map picker) with
        // consistent icon rows inside — replaces the old loose rows +
        // divider stack. Type scale is deliberately one step smaller:
        // row titles bodyMedium, descriptions labelMedium, section
        // headers labelMedium.
        AnimatedScreen {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // NOTE: "Map provider" picker intentionally removed from Settings —
                // basemap selection now lives in the in-map BasemapPickerSheet
                // (Strava-style grid). The persisted providerId state in
                // SettingsRepository is untouched: map screens still read it.

                val hasPermission = remember {
                    LocationRepository(viewModel.getApplication()).hasLocationPermission()
                }
                SettingsSection(stringResource(R.string.gps_section)) {
                    SettingRow(
                        icon = Icons.Default.Map,
                        iconTint = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        title = if (hasPermission) stringResource(R.string.gps_permission_active)
                        else stringResource(R.string.gps_permission_missing),
                        subtitle = stringResource(R.string.gps_permission_desc)
                    )

                    // Gap 2 manual access: the battery-optimization exemption is
                    // normally raised once by the pre-start gate chain on the
                    // Recording screen; this row re-opens the same system sheet on
                    // demand, any time, regardless of the onboarding flag. Status
                    // line is read live from PowerManager so it reflects reality,
                    // not a stored preference.
                    val batteryContext = LocalContext.current
                    val batteryIgnoring = remember {
                        val pm = batteryContext.getSystemService(android.os.PowerManager::class.java)
                        pm?.isIgnoringBatteryOptimizations(batteryContext.packageName) ?: true
                    }
                    val batteryScope = rememberCoroutineScope()
                    val batterySettings = remember { com.nyasar.app.data.settings.SettingsRepository(batteryContext) }
                    val batteryLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { _ -> /* user decided on the system sheet; PowerManager is re-read on next visit */ }
                    SettingRow(
                        icon = Icons.Default.BatterySaver,
                        iconTint = if (batteryIgnoring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.battery_optimization_title),
                        subtitle = if (batteryIgnoring) stringResource(R.string.ready_to_use)
                        else stringResource(R.string.battery_optimization_hint),
                        onClick = {
                            batteryScope.launch { batterySettings.setBatteryOptimizationOnboardingShown() }
                            val packageUri = android.net.Uri.parse("package:${batteryContext.packageName}")
                            val intents = listOf(
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri),
                                // Same OEM fallback as the Recording gate.
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
                            )
                            for (intent in intents) {
                                try {
                                    batteryLauncher.launch(intent)
                                    break
                                } catch (_: Exception) { /* try the next intent */ }
                            }
                        }
                    )
                }

                SettingsSection(stringResource(R.string.offline_section)) {
                    SettingRow(
                        icon = Icons.Default.Map,
                        title = stringResource(R.string.offline_maps_setting),
                        subtitle = stringResource(R.string.offline_maps_desc),
                        onClick = onOpenOfflineMaps,
                        trailing = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    // Downloaded-areas overlay switch — draws green (complete) /
                    // gray (downloading) rectangles for the active basemap on every
                    // main map. Same DataStore flag the picker sheet's
                    // "Area Offline" tile drives.
                    SettingRow(
                        icon = Icons.Default.Radio,
                        title = stringResource(R.string.offline_overlay_title),
                        subtitle = stringResource(R.string.offline_overlay_desc)
                    ) {
                        Switch(
                            checked = current.offlineOverlayEnabled,
                            onCheckedChange = { viewModel.setOfflineOverlayEnabled(it) }
                        )
                    }
                }

                SettingsSection(stringResource(R.string.recording_section)) {
                    SettingRow(
                        icon = Icons.Default.ScreenLockPortrait,
                        title = stringResource(R.string.keep_screen_on),
                        subtitle = stringResource(R.string.keep_screen_desc)
                    ) {
                        Switch(
                            checked = current.keepScreenOnWhileRecording,
                            onCheckedChange = { viewModel.setKeepScreenOnWhileRecording(it) }
                        )
                    }
                    SettingRow(
                        icon = Icons.Default.DirectionsWalk,
                        title = stringResource(R.string.auto_pause),
                        subtitle = stringResource(R.string.auto_pause_desc)
                    ) {
                        Switch(
                            checked = current.autoPauseEnabled,
                            onCheckedChange = { viewModel.setAutoPauseEnabled(it) }
                        )
                    }
                }

                SettingsSection(stringResource(R.string.units_section)) {
                    OptionRow(
                        icon = Icons.Default.Straighten,
                        title = stringResource(R.string.speed_unit),
                        subtitle = stringResource(R.string.speed_unit_desc)
                    )
                    listOf("kmh" to "km/h", "mph" to "mph").forEach { (unit, label) ->
                        RadioOption(
                            label = label,
                            selected = current.speedUnit == unit,
                            onSelect = { viewModel.setSpeedUnit(unit) }
                        )
                    }
                }

                SettingsSection(stringResource(R.string.appearance)) {
                    listOf(
                        "system" to stringResource(R.string.follow_system),
                        "light" to stringResource(R.string.light),
                        "dark" to stringResource(R.string.dark)
                    ).forEach { (mode, label) ->
                        RadioOption(
                            label = label,
                            selected = current.themeMode == mode,
                            onSelect = { viewModel.setThemeMode(mode) }
                        )
                    }
                }

                SettingsSection(stringResource(R.string.language)) {
                    listOf(
                        "system" to stringResource(R.string.follow_system),
                        "id" to stringResource(R.string.indonesian),
                        "en" to stringResource(R.string.english)
                    ).forEach { (mode, label) ->
                        RadioOption(
                            label = label,
                            selected = current.languageMode == mode,
                            onSelect = { viewModel.setLanguageMode(mode) }
                        )
                    }
                }

                SettingsSection(stringResource(R.string.data_section)) {
                    var cacheSize by remember { mutableStateOf<Long?>(null) }
                    LaunchedEffect(Unit) { cacheSize = viewModel.cacheSizeBytes() }
                    SettingRow(
                        icon = Icons.Default.CleaningServices,
                        title = stringResource(R.string.cache),
                        subtitle = cacheSize?.let { stringResource(R.string.cache_size_format, it / (1024.0 * 1024.0)) }
                            ?: stringResource(R.string.calculating),
                        trailing = {
                            TextButton(onClick = {
                                viewModel.clearCache()
                                cacheSize = 0L
                            }) { Text(stringResource(R.string.clear_cache)) }
                        }
                    )
                    Row(Modifier.padding(start = 40.dp, end = 14.dp, bottom = 10.dp)) {
                        Text(
                            stringResource(R.string.cache_permanent),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SettingsSection(stringResource(R.string.about_section)) {
                    SettingRow(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.about),
                        subtitle = stringResource(R.string.version, com.nyasar.app.BuildConfig.VERSION_NAME)
                    )
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** One grouped section: small colored header above a bordered card whose
 *  children share the card's background. Keeps every section visually
 *  identical (same radius/border/padding) so the page reads as one system. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
    Card(
        shape = RoundedCornerShape(NyasarRadius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
    Spacer(Modifier.height(18.dp))
}

/** One settings row: 20dp leading icon, compact title + optional
 *  description, optional trailing slot (switch/chevron/button), optional
 *  click. Rows without a click still render identically — only the ripple
 *  differs. */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}

/** Section-level descriptor row (no trailing, no click) — used above radio
 *  groups so the group has the same icon/title treatment as other rows. */
@Composable
private fun OptionRow(icon: ImageVector, title: String, subtitle: String? = null) {
    SettingRow(icon = icon, title = title, subtitle = subtitle)
}

/** One radio choice inside a section card — selectable row with a compact
 *  label, indented to align under the section's icon column. */
@Composable
private fun RadioOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(start = 46.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
