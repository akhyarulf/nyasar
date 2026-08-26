package com.nyasar.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R
import com.nyasar.app.location.LocationRepository

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
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.map_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.map_provider), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.map_provider_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            viewModel.availableProviders.forEach { provider ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = provider.id == current.providerId,
                            onClick = { viewModel.selectProvider(provider.id) }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = provider.id == current.providerId,
                        onClick = { viewModel.selectProvider(provider.id) },
                        enabled = provider.isConfigured()
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(provider.displayName)
                        if (!provider.isConfigured()) {
                            Text(
                                stringResource(R.string.not_configured),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.gps_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            val hasPermission = remember {
                LocationRepository(viewModel.getApplication()).hasLocationPermission()
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hasPermission) stringResource(R.string.gps_permission_active)
                    else stringResource(R.string.gps_permission_missing)
                )
            }
            Text(
                stringResource(R.string.gps_permission_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.offline_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = false, onClick = onOpenOfflineMaps)
                    .padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Map, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.offline_maps_setting))
                    Text(
                        stringResource(R.string.offline_maps_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.recording_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.keep_screen_on))
                    Text(
                        stringResource(R.string.keep_screen_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = current.keepScreenOnWhileRecording,
                    onCheckedChange = { viewModel.setKeepScreenOnWhileRecording(it) }
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_pause))
                    Text(
                        stringResource(R.string.auto_pause_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = current.autoPauseEnabled,
                    onCheckedChange = { viewModel.setAutoPauseEnabled(it) }
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.units_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.speed_unit), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.speed_unit_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            listOf("kmh" to "km/h", "mph" to "mph").forEach { (unit, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = current.speedUnit == unit,
                            onClick = { viewModel.setSpeedUnit(unit) }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(selected = current.speedUnit == unit, onClick = { viewModel.setSpeedUnit(unit) })
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            listOf("system" to stringResource(R.string.follow_system), "light" to stringResource(R.string.light), "dark" to stringResource(R.string.dark)).forEach { (mode, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = current.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(selected = current.themeMode == mode, onClick = { viewModel.setThemeMode(mode) })
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            listOf("system" to stringResource(R.string.follow_system), "id" to stringResource(R.string.indonesian), "en" to stringResource(R.string.english)).forEach { (mode, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = current.languageMode == mode,
                            onClick = { viewModel.setLanguageMode(mode) }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(selected = current.languageMode == mode, onClick = { viewModel.setLanguageMode(mode) })
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.data_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            var cacheSize by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(Unit) { cacheSize = viewModel.cacheSizeBytes() }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.cache))
                    Text(
                        cacheSize?.let { stringResource(R.string.cache_size_format, it / (1024.0 * 1024.0)) }
                            ?: stringResource(R.string.calculating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    viewModel.clearCache()
                    cacheSize = 0L
                }) { Text(stringResource(R.string.clear_cache)) }
            }
            Text(
                stringResource(R.string.cache_permanent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(stringResource(R.string.about_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.version, com.nyasar.app.BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
