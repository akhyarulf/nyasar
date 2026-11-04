package com.nyasar.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R
import com.nyasar.app.ui.components.AuthorAvatar
import com.nyasar.app.ui.components.NyasarTabHeader
import com.nyasar.app.ui.components.TabHeaderAction

/**
 * Profile tab (IA rework 2026, rev4): identity lives IN the top bar — the
 * avatar sits beside the gear, so the page reads title-left / picture-right
 * like the reference app and the History/Saved tabs begin directly under the
 * header with no second identity row eating a full screen row. Tapping the
 * avatar opens the standalone Account screen ("account" route) when signed
 * in, or sign-in when not. Child screens are REUSED VERBATIM — this file
 * adds no new feature logic, it only hosts them and owns the sub-tab state.
 *
 * Two hosting modes, selected by [showHeader]:
 * - showHeader = true  — opened from the bottom bar as plain "profile":
 *   page-title TopAppBar + gear (→ settings) + TabRow, content fills rest.
 * - showHeader = false — opened as a nested destination ("profile?tab=…",
 *   tab values: history|saved):
 *   [Scaffold] with a back-arrow TopAppBar, gear, then the TabRow — the same
 *   visual language as every other sub-screen, and back pops to the caller.
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
    onOpenRoute: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** Header tap while signed in → the standalone Account screen. */
    onOpenProfile: () -> Unit,
    /** Header tap while signed out + Saved-pane sign-in prompt → auth. */
    onOpenAccount: () -> Unit,
    onGoToRecord: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    // Keep the remembered tab in sync when the caller passes a different
    // initial tab (e.g. deep navigation into "profile?tab=saved" while a
    // "profile" instance was already on the back stack).
    LaunchedEffect(initialTab) { selectedTab = initialTab.ordinal }

    // History filters: the trigger sits in the page header (reference-app
    // placement — controls in the bar, content below), so the sheet state
    // lives here instead of inside the embedded history pane. This is the
    // same ViewModel instance that pane resolves: both go through this
    // destination's ViewModelStore, so toggling here filters the list there.
    val historyViewModel: com.nyasar.app.ui.history.ActivityHistoryViewModel = viewModel()
    val historySportFilter by historyViewModel.sportFilter.collectAsState()
    val historyMonthFilter by historyViewModel.monthFilter.collectAsState()
    var showHistoryFilters by rememberSaveable { mutableStateOf(false) }
    val historyFiltersActive = historySportFilter != null || historyMonthFilter != null

    // Account entry point, rendered in the top bar next to settings. This used
    // to be a 72dp identity row under the header (avatar + name + email); the
    // picture moved up beside the gear, which puts the tabs right under the
    // bar and matches the reference layout. Signed out / restoring it degrades
    // to a person glyph that leads to sign-in — the same role the old card's
    // invite had, without the extra row.
    val authViewModel: com.nyasar.app.ui.auth.AuthViewModel = viewModel()
    val session by authViewModel.sessionState.collectAsState()
    val signedInAccount =
        session as? com.nyasar.app.ui.auth.AuthViewModel.SessionState.SignedIn
    val accountLoading =
        session is com.nyasar.app.ui.auth.AuthViewModel.SessionState.LoadingAccount ||
            session is com.nyasar.app.ui.auth.AuthViewModel.SessionState.Restoring

    val accountAction: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .padding(end = 4.dp)
                // 48dp touch target around a 32dp avatar — the circle stays
                // small next to the 24dp toolbar glyphs, but the tap area
                // doesn't shrink with it.
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = !accountLoading) {
                    if (signedInAccount != null) onOpenProfile() else onOpenAccount()
                },
            contentAlignment = Alignment.Center
        ) {
            if (accountLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                AuthorAvatar(name = signedInAccount?.username, size = 32.dp)
            }
        }
    }

    val tabRow = @Composable {
        // Secondary-style tab row: full-width underline + labelMedium caps
        // feels lighter than the primary indicator bar, and the hairline
        // divider under the whole row keeps it attached to the header card
        // instead of floating in blank space.
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    height = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            divider = {}
        ) {
            Tab(
                selected = selectedTab == ProfileTab.HISTORY.ordinal,
                onClick = { selectedTab = ProfileTab.HISTORY.ordinal },
                text = { Text(stringResource(R.string.profile_tab_history), style = MaterialTheme.typography.labelLarge) }
            )
            Tab(
                selected = selectedTab == ProfileTab.SAVED.ordinal,
                onClick = { selectedTab = ProfileTab.SAVED.ordinal },
                text = { Text(stringResource(R.string.saved_title), style = MaterialTheme.typography.labelLarge) }
            )
        }
    }

    if (showHeader) {
        // Bottom-bar hosting: page title + gear + TabRow, no Scaffold (the
        // NavHost's Scaffold already pads for the bottom bar).
        Column(modifier = Modifier.fillMaxSize()) {
            // Unified tab header (same recipe as Maps/Library/Explore).
            NyasarTabHeader(
                title = stringResource(R.string.profile_title),
                actions = {
                    if (selectedTab == ProfileTab.HISTORY.ordinal) {
                        // Green = a filter is applied (state), otherwise a
                        // plain toolbar glyph like the reference's icons.
                        IconButton(onClick = { showHistoryFilters = true }) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = stringResource(R.string.history_filter_button),
                                tint = if (historyFiltersActive) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    TabHeaderAction(
                        icon = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings),
                        onClick = onOpenSettings
                    )
                    accountAction()
                }
            )
            tabRow()
            when (selectedTab) {
                ProfileTab.HISTORY.ordinal -> ProfileHistoryPane(
                    onOpenActivity = onOpenActivity,
                    onShareActivity = onShareActivity,
                    onShareGpx = onShareGpx,
                    onGoToRecord = onGoToRecord
                )
                else -> ProfileSavedPane(
                    onOpenRoute = onOpenRoute,
                    onRequireSignIn = onOpenAccount
                )
            }
        }
    } else {
        // Nested-destination hosting (profile?tab=…): own Scaffold + back
        // arrow, so it reads like settings/preview/etc.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (selectedTab == ProfileTab.HISTORY.ordinal) R.string.history
                                else R.string.saved_title
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
                    },
                    actions = {
                        if (selectedTab == ProfileTab.HISTORY.ordinal) {
                            IconButton(onClick = { showHistoryFilters = true }) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = stringResource(R.string.history_filter_button),
                                    tint = if (historyFiltersActive) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(
                                    R.string.settings
                                )
                            )
                        }
                        accountAction()
                    }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                tabRow()
                when (selectedTab) {
                    ProfileTab.HISTORY.ordinal -> ProfileHistoryPane(
                        onOpenActivity = onOpenActivity,
                        onShareActivity = onShareActivity,
                        onShareGpx = onShareGpx,
                        onGoToRecord = onGoToRecord
                    )
                    else -> ProfileSavedPane(
                        onOpenRoute = onOpenRoute,
                        onRequireSignIn = onOpenAccount
                    )
                }
            }
        }
    }

    // Sibling of both hosts above: a ModalBottomSheet is its own overlay, so
    // it doesn't need to live inside either branch's layout.
    if (showHistoryFilters) {
        com.nyasar.app.ui.history.HistoryFilterSheet(
            viewModel = historyViewModel,
            onDismiss = { showHistoryFilters = false }
        )
    }
}

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

/** Saved sub-tab content — the bookmark list (saved_routes), rendered with
 * the browse card component; opening a card lands on the public route
 * detail, the same destination Browse uses. */
@Composable
private fun ProfileSavedPane(
    onOpenRoute: (String) -> Unit,
    onRequireSignIn: () -> Unit
) {
    com.nyasar.app.ui.profile.SavedEmbedded(
        onOpenRoute = onOpenRoute,
        onRequireSignIn = onRequireSignIn
    )
}

enum class ProfileTab {
    HISTORY,
    SAVED
}
