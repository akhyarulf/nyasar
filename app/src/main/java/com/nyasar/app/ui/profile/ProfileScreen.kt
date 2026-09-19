package com.nyasar.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.R

/**
 * Profile tab (IA rework 2026, rev3): a Wikiloc-style profile header card
 * (avatar initial + username + email, or a sign-in invite when signed out)
 * sits ABOVE the History/Saved sub-tabs — above the tabs, so it reads as
 * the page identity, not a list row. Tapping it opens the standalone
 * Account screen ("account" route) when signed in, or sign-in when not;
 * Settings lives behind the gear icon in the top-right corner. Child
 * screens are REUSED VERBATIM — this file adds no new feature logic,
 * it only hosts them and owns the sub-tab state.
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

    val tabRow = @Composable {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == ProfileTab.HISTORY.ordinal,
                onClick = { selectedTab = ProfileTab.HISTORY.ordinal },
                text = { Text(stringResource(R.string.profile_tab_history)) }
            )
            Tab(
                selected = selectedTab == ProfileTab.SAVED.ordinal,
                onClick = { selectedTab = ProfileTab.SAVED.ordinal },
                text = { Text(stringResource(R.string.saved_title)) }
            )
        }
    }

    if (showHeader) {
        // Bottom-bar hosting: page title + gear + TabRow, no Scaffold (the
        // NavHost's Scaffold already pads for the bottom bar).
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                }
            )
            ProfileHeaderCard(
                onOpenProfile = onOpenProfile,
                onOpenSignIn = onOpenAccount
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
    } else {        // Nested-destination hosting (profile?tab=…): own Scaffold + back
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
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                ProfileHeaderCard(
                    onOpenProfile = onOpenProfile,
                    onOpenSignIn = onOpenAccount
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
        }
    }
}

/**
 * Wikiloc-style identity header ABOVE the sub-tabs.
 * - Signed in: avatar circle with the username initial + name + email;
 *   tap → the standalone Account screen (edit username, manage/delete,
 *   logout).
 * - Signed out / restoring: invite card; tap → sign-in.
 */
@Composable
private fun ProfileHeaderCard(
    onOpenProfile: () -> Unit,
    onOpenSignIn: () -> Unit
) {
    val authViewModel: com.nyasar.app.ui.auth.AuthViewModel = viewModel()
    val session by authViewModel.sessionState.collectAsState()

    val signedIn = session as? com.nyasar.app.ui.auth.AuthViewModel.SessionState.SignedIn
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (signedIn != null) onOpenProfile else onOpenSignIn)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (signedIn != null) {
                Text(
                    text = signedIn.username?.trim()?.take(1)?.uppercase()
                        ?: stringResource(R.string.account_no_username).take(1),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = signedIn?.username
                    ?: stringResource(R.string.profile_sign_in_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = signedIn?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.profile_sign_in_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
 *  the browse card component; opening a card lands on the public route
 *  detail, the same destination Browse uses. */
@Composable
private fun ProfileSavedPane(
    onOpenRoute: (String) -> Unit,
    onRequireSignIn: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        SavedEmbedded(
            onOpenRoute = onOpenRoute,
            onRequireSignIn = onRequireSignIn
        )
    }
}

enum class ProfileTab { HISTORY, SAVED }
