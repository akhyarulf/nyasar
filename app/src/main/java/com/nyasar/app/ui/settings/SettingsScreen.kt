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
                title = { Text("Pengaturan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
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
            Text("MAP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("Map provider", style = MaterialTheme.typography.titleMedium)
            Text(
                "Mesin peta (MapLibre) tidak berubah — ini hanya mengganti sumber tile/style.",
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
                                "Belum dikonfigurasi (API key kosong)",
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

            Text("GPS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                    if (hasPermission) "Izin lokasi: aktif"
                    else "Izin lokasi: belum diberikan — aktifkan di Pengaturan Sistem"
                )
            }
            Text(
                "Recording di background membutuhkan izin lokasi selalu diizinkan agar tidak berhenti saat layar mati. Jika recording sering terputus, cek pengecualian battery optimization untuk Nyasar di pengaturan sistem HP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("OFFLINE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                    Text("Peta Offline")
                    Text(
                        "Lihat, kelola, dan hapus peta yang sudah didownload",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("RECORDING", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Jaga layar tetap menyala")
                    Text(
                        "Aktif saat recording berjalan, supaya jarak/waktu tetap terlihat",
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
                    Text("Auto Pause")
                    Text(
                        "Jeda otomatis saat berhenti ~20 detik, lanjut otomatis saat mulai bergerak lagi",
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

            Text("UNITS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Speed Unit", style = MaterialTheme.typography.titleMedium)
            Text(
                "Digunakan di recording, activity, navigation, statistik, dan route info.",
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

            Text("APPEARANCE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            listOf("system" to "Ikuti sistem", "light" to "Terang", "dark" to "Gelap").forEach { (mode, label) ->
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

            Text("DATA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            var cacheSize by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(Unit) { cacheSize = viewModel.cacheSizeBytes() }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text("Cache")
                    Text(
                        cacheSize?.let { "%.1f MB — file GPX hasil export sementara".format(it / (1024.0 * 1024.0)) }
                            ?: "Menghitung...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    viewModel.clearCache()
                    cacheSize = 0L
                }) { Text("Hapus") }
            }
            Text(
                "Routes dan activity tersimpan permanen di penyimpanan aplikasi, tidak terpengaruh oleh Hapus Cache di atas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("ABOUT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Nyasar — outdoor navigation & recording", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Versi ${com.nyasar.app.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
