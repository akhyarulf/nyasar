package com.nyasar.app.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R

/**
 * Profile tab (IA rework 2026): History + Settings merged behind one tab
 * with two sub-tabs. Both child screens are REUSED VERBATIM — this file adds
 * no new feature logic, it only hosts them and owns the sub-tab state.
 *
 * Two hosting modes, selected by [showHeader]:
 * - showHeader = true  — opened from the bottom bar as plain "profile":
 *   plain page-title TopAppBar + TabRow, content fills the rest.
 * - showHeader = false — opened as a nested destination ("profile?tab=…"):
 *   [Scaffold] with a back-arrow TopAppBar titled by the sub-tab, then the
 *   TabRow — the same visual language as every other sub-screen
 *   (settings/preview/detail), and back pops to the caller.
 *
 * [onGoToRecord] powers the History empty-state CTA ("start recording"):
 * MainActivity wires it to the same tab-switch semantics the bottom bar uses,
 * because only the NavHost level knows how to switch tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    initialTab: ProfileTab = ProfileTab.HISTORY,
    showHeader: Boolean,
    onOpenActivity: (String) -> Unit,
    onShareActivity: (String) -> Unit,
    onShareGpx: (String) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenAccount: () -> Unit,
    onGoToRecord: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    // Keep the remembered tab in sync when the caller passes a different
    // initial tab (e.g. deep navigation into "profile?tab=settings" while a
    // "profile" instance was already on the back stack).
    LaunchedEffect(initialTab) { selectedTab = initialTab.ordinal }

    if (showHeader) {
        // Bottom-bar hosting: page title + TabRow, no back arrow, no Scaffold
        // (the NavHost's Scaffold already pads for the bottom bar).
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(title = { Text(stringResource(R.string.profile_title)) })
            ProfileTabRow(selectedTab, onTabSelected = { selectedTab = it })
            when (selectedTab) {
                ProfileTab.HISTORY.ordinal -> ProfileHistoryPane(
                    onOpenActivity = onOpenActivity,
                    onShareActivity = onShareActivity,
                    onShareGpx = onShareGpx,
                    onGoToRecord = onGoToRecord
                )
                else -> ProfileSettingsPane(
                    onOpenOfflineMaps = onOpenOfflineMaps,
                    onOpenAccount = onOpenAccount
                )
            }
        }
    } else {
        // Nested-destination hosting (profile?tab=…): own Scaffold + back
        // arrow titled by the sub-tab, so it reads like settings/preview/etc.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (selectedTab == ProfileTab.HISTORY.ordinal) R.string.history
                                else R.string.settings
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                ProfileTabRow(selectedTab, onTabSelected = { selectedTab = it })
                when (selectedTab) {
                    ProfileTab.HISTORY.ordinal -> ProfileHistoryPane(
                        onOpenActivity = onOpenActivity,
                        onShareActivity = onShareActivity,
                        onShareGpx = onShareGpx,
                        onGoToRecord = onGoToRecord
                    )
                    else -> ProfileSettingsPane(
                        onOpenOfflineMaps = onOpenOfflineMaps,
                        onOpenAccount = onOpenAccount
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    TabRow(selectedTabIndex = selectedTab) {
        Tab(
            selected = selectedTab == ProfileTab.HISTORY.ordinal,
            onClick = { onTabSelected(ProfileTab.HISTORY.ordinal) },
            text = { Text(stringResource(R.string.profile_tab_history)) }
        )
        Tab(
            selected = selectedTab == ProfileTab.SETTINGS.ordinal,
            onClick = { onTabSelected(ProfileTab.SETTINGS.ordinal) },
            text = { Text(stringResource(R.string.profile_tab_settings)) }
        )
    }
}

/** History sub-tab content — the embedded variant of ActivityHistoryScreen;
 *  empty-state CTA jumps to the Record tab via the caller-provided switch. */
@Composable
private fun ProfileHistoryPane(
    onOpenActivity: (String) -> Unit,
    onShareActivity: (String) -> Unit,
    onShareGpx: (String) -> Unit,
    onGoToRecord: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        com.nyasar.app.ui.history.ActivityHistoryEmbedded(
            onOpenActivity = onOpenActivity,
            onShareActivity = onShareActivity,
            onShareGpx = onShareGpx,
            emptyCta = onGoToRecord
        )
    }
}

/** Settings sub-tab content — embedded variant of SettingsScreen (reused
 *  verbatim; same rows as the old standalone Settings tab). */
@Composable
private fun ProfileSettingsPane(
    onOpenOfflineMaps: () -> Unit,
    onOpenAccount: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        com.nyasar.app.ui.settings.SettingsEmbedded(
            onOpenOfflineMaps = onOpenOfflineMaps,
            onOpenAccount = onOpenAccount,
            onBack = {}
        )
    }
}

enum class ProfileTab { HISTORY, SETTINGS }
