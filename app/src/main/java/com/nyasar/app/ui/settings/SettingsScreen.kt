package com.nyasar.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    // Standalone wrapper (kept for the "settings" route used by map screens'
    // gear buttons): own Scaffold header with back arrow, then the shared
    // content — identical rows to the Profile tab's embedded Settings pane.
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            SettingsContent(
                onOpenOfflineMaps = onOpenOfflineMaps,
                onBack = onBack
            )
        }
    }
}

/**
 * Settings content without its own Scaffold/TopAppBar — reused by the
 * standalone [SettingsScreen] wrapper. Account management no longer lives
 * here (IA rev3): identity, username edit and manage/delete moved to the
 * Account screen (ui/profile/AccountScreen.kt).
 */
/** Internal on purpose: only [SettingsScreen] and [SettingsEmbedded]
 *  (same module/package) may host it — but `internal` keeps that guarantee
 *  while letting the embedded wrapper live in its own file. */
@Composable
internal fun SettingsContent(
    onOpenOfflineMaps: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val storage by viewModel.storage.collectAsState()
    val storageBusy by viewModel.storageBusy.collectAsState()
    val storageMessage by viewModel.storageMessage.collectAsState()
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showClearMapsDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshStorage() }
    val authViewModel: com.nyasar.app.ui.auth.AuthViewModel = viewModel()
    val sessionState by authViewModel.sessionState.collectAsState()

    Box(Modifier.fillMaxSize()) {
        val current = settings
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Box
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
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // NOTE: "Map provider" picker intentionally removed from Settings —
                // basemap selection now lives in the in-map BasemapPickerSheet
                // (Strava-style grid). The persisted providerId state in
                // SettingsRepository is untouched: map screens still read it.
                // NOTE: the Account section moved out (IA rev3): identity +
                // username edit + manage/delete live on the Account screen,
                // reached from the profile header card.

                val hasPermission = remember {
                    LocationRepository(viewModel.getApplication()).hasLocationPermission()
                }
                // (Fase 3 manual backup actions removed — backup/restore is
                // now fully automatic: data-creation auto-upserts + login
                // auto-sync. BackupSettingsViewModel intentionally remains
                // compiled for potential future diagnostics use.)
                // ── Backup otomatis (konsep "tanpa tombol") — status saja.
                // Backup dan restore terjadi sendiri: backup = efek samping
                // setiap penciptaan data (activity selesai, GPX import, rute
                // gambar, save-to-library), restore = efek samping login.
                // Tidak ada aksi manual di sini — hanya penjelasan status.
                if (sessionState is com.nyasar.app.ui.auth.AuthViewModel.SessionState.SignedIn) {
                    SettingsSection(stringResource(R.string.backup_section)) {
                        SettingRow(
                            icon = Icons.Default.CloudUpload,
                            title = stringResource(R.string.backup_auto_title),
                            subtitle = stringResource(R.string.backup_auto_desc)
                        )
                    }
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
                    // Option labels: km/h / mph.
                    val kmhLabel = stringResource(R.string.speed_unit_kmh)
                    val mphLabel = stringResource(R.string.speed_unit_mph)
                    listOf("kmh" to kmhLabel, "mph" to mphLabel).forEach { (unit, label) ->
                        RadioOption(
                            label = label,
                            selected = current.speedUnit == unit,
                            onSelect = { viewModel.setSpeedUnit(unit) }
                        )
                    }
                }

                SettingsSection(stringResource(R.string.appearance)) {
                    // Header + description anchors the otherwise bare radio
                    // list (same anatomy as Speed Unit) — user feedback that
                    // the card read as a tall, unexplained stack.
                    OptionRow(
                        icon = Icons.Default.Palette,
                        title = stringResource(R.string.appearance),
                        subtitle = stringResource(R.string.appearance_desc)
                    )
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
                    OptionRow(
                        icon = Icons.Default.Translate,
                        title = stringResource(R.string.language),
                        subtitle = stringResource(R.string.language_desc)
                    )
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
                    LaunchedEffect(storage.cacheBytes) { cacheSize = storage.cacheBytes }
                    SettingRow(
                        icon = Icons.Default.CleaningServices,
                        title = stringResource(R.string.cache),
                        subtitle = cacheSize?.let { formatStorageBytes(it) } ?: stringResource(R.string.calculating),
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

                    Text(
                        stringResource(R.string.storage_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 2.dp)
                    )
                    Text(
                        stringResource(
                            R.string.storage_summary,
                            formatStorageBytes(storage.totalBytes),
                            storage.routeCount,
                            storage.activityCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                    )
                    StorageBreakdownRow(Icons.Default.Map, stringResource(R.string.storage_routes), formatStorageBytes(storage.routeBytes), storage.routeCount)
                    StorageBreakdownRow(Icons.Default.DirectionsWalk, stringResource(R.string.storage_activities), formatStorageBytes(storage.activityBytes), storage.activityCount)
                    StorageBreakdownRow(Icons.Default.Radio, stringResource(R.string.storage_waypoints), null, storage.waypointCount)
                    StorageBreakdownRow(Icons.Default.CloudUpload, stringResource(R.string.storage_pending), null, storage.pendingCount)
                    StorageBreakdownRow(Icons.Default.Map, stringResource(R.string.storage_offline_maps), formatStorageBytes(storage.offlineBytes), null)
                    StorageBreakdownRow(Icons.Default.Map, stringResource(R.string.storage_database), formatStorageBytes(storage.databaseBytes), null)
                    StorageBreakdownRow(Icons.Default.CleaningServices, stringResource(R.string.storage_files), formatStorageBytes(storage.filesBytes), null)
                    SettingRow(
                        icon = Icons.Default.CleaningServices,
                        title = stringResource(R.string.storage_clear_maps_desc),
                        subtitle = formatStorageBytes(storage.offlineBytes),
                        trailing = { TextButton(onClick = { showClearMapsDialog = true }, enabled = !storageBusy && storage.offlineBytes > 0) { Text(stringResource(R.string.clear_cache)) } }
                    )
                    SettingRow(
                        icon = Icons.Default.Delete,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.storage_clear_all),
                        subtitle = stringResource(R.string.storage_clear_all_desc),
                        trailing = { TextButton(onClick = { showClearAllDialog = true }, enabled = !storageBusy) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } }
                    )
                    storageMessage?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (message.contains("Gagal") || message.contains("failed", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
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

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { if (!storageBusy) showClearAllDialog = false },
            title = { Text(stringResource(R.string.storage_confirm_clear_all)) },
            text = { Text(stringResource(R.string.storage_clear_all_message)) },
            confirmButton = { TextButton(onClick = { showClearAllDialog = false; viewModel.clearAllLocalData() }, enabled = !storageBusy) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showClearAllDialog = false }, enabled = !storageBusy) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (showClearMapsDialog) {
        AlertDialog(
            onDismissRequest = { if (!storageBusy) showClearMapsDialog = false },
            title = { Text(stringResource(R.string.storage_confirm_clear_maps)) },
            text = { Text(stringResource(R.string.storage_clear_maps_message)) },
            confirmButton = { TextButton(onClick = { showClearMapsDialog = false; viewModel.clearOfflineMaps() }, enabled = !storageBusy) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showClearMapsDialog = false }, enabled = !storageBusy) { Text(stringResource(R.string.cancel)) } }
        )
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
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        shape = RoundedCornerShape(NyasarRadius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
    Spacer(Modifier.height(16.dp))
}

/** One settings row: 20dp leading icon, compact title + optional
 *  description, optional trailing slot (switch/chevron/button), optional
 *  click. Rows without a click still render identically — only the ripple
 *  differs. */
@Composable
private fun StorageBreakdownRow(icon: ImageVector, title: String, size: String?, count: Int?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull(size, count?.let { "$it" }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

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
            .padding(horizontal = 14.dp, vertical = 11.dp),
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

/** One compact radio choice inside a section card. The custom indicator keeps
 *  the row compact while avoiding the oversized Material radio control that
 *  made these sections feel like a long list of empty space.
 *
 *  Row density (user feedback 2026-09): option rows inside these grouped
 *  cards sit at 44dp — the dense end of the M3 list spec (40-48dp) — so
 *  three-option cards don't read as tall stacks of empty air. 48dp remains
 *  the standard for STANDALONE rows elsewhere in this screen; embedded
 *  grouped options are a denser pattern, the 20dp indicator and label still
 *  clear it comfortably, and the ripple makes the target obvious. */
@Composable
private fun RadioOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(start = 46.dp, end = 14.dp, top = 2.dp, bottom = 2.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ── Phase 1: account management sheet ───────────────────────────────────────

/**
 * Manage-account sheet: change password, change email, delete account.
 * Every result flows through AuthViewModel.accountAction (sealed data class,
 * same convention as the auth forms); one-shot success/error state is
 * consumed on sheet close via clearAccountAction(). The delete flow is the
 * most guarded: typed confirmation (locale-aware keyword) + an honest
 * description that only CLOUD data dies — Room data on this device stays
 * (explicit product decision, stated verbatim in the dialog).
 */
/** Hosted by the Account screen (ui/profile/AccountScreen.kt) — the
 *  account section left Settings, so the sheet moved with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountManageSheet(
    authViewModel: com.nyasar.app.ui.auth.AuthViewModel,
    onDismiss: () -> Unit
) {
    val action by authViewModel.accountAction.collectAsState()
    val session by authViewModel.sessionState.collectAsState()
    val currentEmail =
        (session as? com.nyasar.app.ui.auth.AuthViewModel.SessionState.SignedIn)?.email

    var mode by rememberSaveable { mutableStateOf("menu") } // menu|password|email|delete
    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var newEmail by rememberSaveable { mutableStateOf("") }
    var confirmWord by rememberSaveable { mutableStateOf("") }
    val deleteWord = stringResource(R.string.account_delete_confirm_word)

    // Account deletion ends the session; close the sheet the moment it
    // lands (the Settings screen re-renders as SignedOut underneath).
    LaunchedEffect(action.accountDeleted) {
        if (action.accountDeleted) {
            authViewModel.clearAccountAction()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            authViewModel.clearAccountAction()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                stringResource(R.string.account_manage_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            when (mode) {
                "menu" -> {
                    AccountActionRow(
                        icon = Icons.Default.Password,
                        title = stringResource(R.string.account_manage_password),
                        subtitle = stringResource(R.string.account_manage_password_desc)
                    ) { mode = "password" }
                    AccountActionRow(
                        icon = Icons.Default.AlternateEmail,
                        title = stringResource(R.string.account_manage_email),
                        subtitle = stringResource(R.string.account_manage_email_desc)
                    ) { mode = "email" }
                    AccountActionRow(
                        icon = Icons.Default.Delete,
                        title = stringResource(R.string.account_manage_delete),
                        subtitle = stringResource(R.string.account_manage_delete_desc),
                        destructive = true
                    ) { mode = "delete" }
                }

                "password" -> {
                    if (action.passwordChanged) {
                        AccountActionDone(
                            text = stringResource(R.string.account_password_changed),
                            onClose = {
                                authViewModel.clearAccountAction()
                                onDismiss()
                            }
                        )
                    } else {
                        AccountPasswordField(
                            value = oldPassword,
                            onValueChange = { oldPassword = it },
                            label = stringResource(R.string.account_current_password),
                            enabled = !action.busy
                        )
                        Spacer(Modifier.height(8.dp))
                        AccountPasswordField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = stringResource(R.string.account_new_password),
                            enabled = !action.busy
                        )
                        AccountActionError(action.errorRes)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { authViewModel.changePassword(oldPassword, newPassword) },
                            enabled = !action.busy && oldPassword.isNotBlank() && newPassword.length >= 6,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (action.busy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.account_change_password_cta))
                            }
                        }
                    }
                }

                "email" -> {
                    val staged = action.emailConfirmationRequired
                    if (action.emailChanged || staged != null) {
                        AccountActionDone(
                            text = if (staged != null) {
                                // Honest staging: the change only applies after
                                // the NEW inbox is confirmed (project requires
                                // email confirmation).
                                stringResource(R.string.account_email_confirmation_sent, staged)
                            } else {
                                stringResource(R.string.account_email_changed)
                            },
                            onClose = {
                                authViewModel.clearAccountAction()
                                onDismiss()
                            }
                        )
                    } else {
                        if (currentEmail != null) {
                            Text(
                                stringResource(R.string.account_current_email) + ": " + currentEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = newEmail,
                            onValueChange = { newEmail = it },
                            label = { Text(stringResource(R.string.account_new_email)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            enabled = !action.busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        AccountPasswordField(
                            value = oldPassword,
                            onValueChange = { oldPassword = it },
                            label = stringResource(R.string.account_current_password),
                            enabled = !action.busy
                        )
                        AccountActionError(action.errorRes)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { authViewModel.changeEmail(oldPassword, newEmail) },
                            enabled = !action.busy &&
                                newEmail.contains("@") && newEmail.length > 3 &&
                                oldPassword.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (action.busy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.account_change_email_cta))
                            }
                        }
                    }
                }

                "delete" -> {
                    Text(
                        stringResource(R.string.account_delete_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.account_delete_keep_local),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    AccountActionError(
                        if (action.errorRes == com.nyasar.app.R.string.auth_error_generic)
                            R.string.account_delete_rpc_missing else action.errorRes
                    )
                    OutlinedTextField(
                        value = confirmWord,
                        onValueChange = { confirmWord = it },
                        label = { Text(stringResource(R.string.account_delete_type_hint)) },
                        singleLine = true,
                        enabled = !action.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { authViewModel.deleteAccount() },
                        enabled = !action.busy && confirmWord == deleteWord,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (action.busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.account_delete_cta))
                        }
                    }
                }
            }
        }
    }
}

/** Also hosted by the Account screen (ui/profile) — internal suffices
 *  (same module); AccountScreen imports it. */
@Composable
internal fun AccountActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Success message + explicit close button shown after a completed action. */
@Composable
internal fun AccountActionDone(text: String, onClose: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = onClose) {
        Text(stringResource(R.string.account_close))
    }
}

@Composable
internal fun AccountActionError(errorRes: Int?) {
    if (errorRes != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(errorRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
internal fun AccountPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
