package com.nyasar.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.nyasar.app.ui.auth.AuthViewModel
import com.nyasar.app.ui.settings.AccountActionRow
import com.nyasar.app.ui.settings.AccountManageSheet
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Account screen (IA rev3): the user-profile page behind the Profile tab's
 * identity header — Wikiloc-style "tap your avatar → your profile page".
 *
 * Owns everything account-shaped that used to live inside Settings:
 * - hero: avatar-initial circle + username + email + "member since"
 * - edit username (same rules/gate as ChooseUsername: live availability
 *   check via AuthViewModel.usernameForm, save via updateProfileUsername)
 * - logout
 * - Manage account sheet (change password / change email / delete account)
 *   — the sheet composable is REUSED VERBATIM from ui/settings, which keeps
 *   one source of truth for those guarded flows.
 *
 * Signed-out visitors get a sign-in invite instead of the content; the
 * session ends (logout/delete) straight back onto the signed-out state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onOpenSignIn: () -> Unit,
    onBack: () -> Unit
) {
    val authViewModel: AuthViewModel = viewModel()
    val session by authViewModel.sessionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = session) {
                is AuthViewModel.SessionState.SignedIn -> AccountContent(
                    session = s,
                    authViewModel = authViewModel,
                    onBack = onBack
                )
                is AuthViewModel.SessionState.Restoring -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                // SignedOut (incl. right after logout/delete) + Unconfigured
                // both land here — one invite card, same as old Settings row.
                else -> SignInInvite(onOpenSignIn = onOpenSignIn)
            }
        }
    }
}

@Composable
private fun AccountContent(
    session: AuthViewModel.SessionState.SignedIn,
    authViewModel: AuthViewModel,
    onBack: () -> Unit
) {
    val usernameForm by authViewModel.usernameForm.collectAsState()
    var showManageAccount by remember { mutableStateOf(false) }
    if (showManageAccount) {
        AccountManageSheet(
            authViewModel = authViewModel,
            onDismiss = { showManageAccount = false }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // ── Hero: avatar initial + username + email + member since ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = session.username?.trim()?.take(1)?.uppercase()
                        ?: stringResource(R.string.account_no_username).take(1),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = session.username
                        ?: stringResource(R.string.account_no_username),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (!session.email.isNullOrBlank()) {
                    Text(
                        text = session.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                formatMemberSince(session.memberSince)?.let {
                    Text(
                        text = stringResource(R.string.account_member_since, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Edit username (live availability, same rules as signup) ──
        AccountCard {
            var username by rememberSaveable { mutableStateOf(session.username ?: "") }
            var checkVersion by remember { mutableStateOf(0) }

            // Debounced availability check — same pattern as ChooseUsername.
            LaunchedEffect(username) {
                if (username.trim().length >= 3 && username != session.username) {
                    val v = ++checkVersion
                    delay(400)
                    if (v == checkVersion) authViewModel.checkUsername(username)
                } else {
                    authViewModel.checkUsername(username) // resets state for short input
                }
            }

            Text(
                stringResource(R.string.account_edit_username),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { input ->
                    username = input.lowercase()
                        .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
                        .take(20)
                },
                label = { Text(stringResource(R.string.username_label)) },
                leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                supportingText = {
                    when {
                        username == session.username -> Text(stringResource(R.string.username_rules))
                        usernameForm.checking -> Text(stringResource(R.string.username_checking))
                        usernameForm.available == true && username.trim().length >= 3 -> Row {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.username_available))
                        }
                        usernameForm.available == false -> Text(
                            usernameForm.errorRes?.let { stringResource(it) }
                                ?: stringResource(R.string.auth_error_username_taken),
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> Text(stringResource(R.string.username_rules))
                    }
                },
                singleLine = true,
                enabled = !usernameForm.busy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { authViewModel.updateProfileUsername(username) },
                enabled = !usernameForm.busy &&
                    username.trim().length >= 3 &&
                    username != session.username &&
                    usernameForm.available == true,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (usernameForm.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.account_save_username))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Account management + logout ──
        AccountCard {
            AccountActionRow(
                icon = Icons.Default.ManageAccounts,
                title = stringResource(R.string.account_manage_title),
                subtitle = stringResource(R.string.account_manage_desc)
            ) { showManageAccount = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AccountActionRow(
                icon = Icons.AutoMirrored.Filled.Logout,
                title = stringResource(R.string.account_logout),
                subtitle = stringResource(R.string.account_logout_desc)
            ) { authViewModel.signOut() }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.account_close))
        }
    }
}

/** Grouped card with the app's shared radius — same tile language as the
 *  Settings section cards this screen inherited content from. */
@Composable
private fun AccountCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp)
    ) { content() }
}

/** Signed-out state: one centered invite card to the auth flow. */
@Composable
private fun SignInInvite(onOpenSignIn: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AlternateEmail,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.account_not_signed_in),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.account_sign_in_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenSignIn) {
            Text(stringResource(R.string.account_sign_in_cta))
        }
    }
}

/** ISO timestamptz → "d MMMM yyyy" in Indonesian; null when absent/invalid. */
private fun formatMemberSince(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso.take(19))
        parsed?.let { SimpleDateFormat("d MMMM yyyy", Locale("id", "ID")).format(it) }
    } catch (_: Exception) {
        null
    }
}
