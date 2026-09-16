package com.nyasar.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Settings content without its own Scaffold/TopAppBar — embedded directly by
 * ProfileScreen's Settings sub-tab (IA rework 2026). Everything below the
 * header (account card, GPS, offline, recording, units, appearance, language,
 * data, about) is REUSED VERBATIM from SettingsScreen via the shared
 * [SettingsContent] composable; this wrapper adds no feature logic.
 */
@Composable
fun SettingsEmbedded(
    onOpenOfflineMaps: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        SettingsContent(
            onOpenOfflineMaps = onOpenOfflineMaps,
            onOpenAccount = onOpenAccount,
            onBack = onBack
        )
    }
}
