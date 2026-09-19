package com.nyasar.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Settings content without its own Scaffold/TopAppBar — currently without a
 * host (IA rev3 moved Settings to a standalone route only; ProfileScreen no
 * longer hosts a Settings sub-tab). Kept as the embeddable seam so a future
 * host can reuse [SettingsContent] verbatim.
 */
@Composable
fun SettingsEmbedded(
    onOpenOfflineMaps: () -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        SettingsContent(
            onOpenOfflineMaps = onOpenOfflineMaps,
            onBack = onBack
        )
    }
}
