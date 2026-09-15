package com.nyasar.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nyasar.app.BuildConfig
import com.nyasar.app.R
import com.nyasar.app.ui.components.AnimatedScreen
import com.nyasar.app.ui.theme.NyasarContentWidth
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialException
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 1 account screens: Login, Register, and the non-skippable
 * ChooseUsername step. All follow the app's shared visual language —
 * AnimatedScreen entrance, NyasarContentWidth.formMaxWidth centered form
 * column (responsive: full width on phones, capped + centered on
 * tablets/landscape), Material3 text fields, and motion tokens.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onGoToRegister: () -> Unit,
    onLoginBack: () -> Unit = {},
    viewModel: AuthViewModel = viewModel()
) {
    val form by viewModel.loginForm.collectAsState()
    val session by viewModel.sessionState.collectAsState()

    LaunchedEffect(session) {
        if (session is AuthViewModel.SessionState.SignedIn) onLoginSuccess()
    }

    AuthScaffold(
        title = stringResource(R.string.auth_login_title),
        onBackAction = onLoginBack
    ) {
        var email by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; viewModel.clearFormError() },
            label = { Text(stringResource(R.string.auth_email)) },
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it; viewModel.clearFormError() },
            label = stringResource(R.string.auth_password),
            enabled = !form.busy
        )

        form.errorRes?.let { res ->
            Spacer(Modifier.height(12.dp))
            ErrorBanner(stringResource(res))
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.signIn(email, password) },
            enabled = !form.busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (form.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.auth_login_cta), fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onGoToRegister,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.auth_no_account_yet))
        }

        // Login Google — offered only when the WEB client id is injected via
        // local.properties (BuildConfig). Credential Manager (Google Play
        // services) returns a Google ID token; gotrue-kt exchanges it for a
        // session via signInWith(IDToken). Outcome surfaces in the same
        // login form state / sessionStatus flow as email sign-in.
        if (BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID.isNotBlank()) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            val option = GetGoogleIdOption.Builder()
                                .setServerClientId(BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID)
                                .setFilterByAuthorizedAccounts(false)
                                .build()
                            val request = GetCredentialRequest.Builder()
                                .addCredentialOption(option)
                                .build()
                            val credential = CredentialManager.create(context)
                                .getCredential(context, request).credential
                            googleIdTokenOf(credential)?.let(viewModel::signInWithGoogle)
                        } catch (e: GetCredentialException) {
                            // user cancelled / no Google account on device —
                            // silently ignore, standard Credential-Manager UX
                        }
                    }
                },
                enabled = !form.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(
                    Icons.Default.Login,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.auth_google_cta))
            }
        }
    }
}

@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onBackToLogin: () -> Unit,
    onRegisterBack: () -> Unit = {},
    viewModel: AuthViewModel = viewModel()
) {
    val form by viewModel.registerForm.collectAsState()
    val session by viewModel.sessionState.collectAsState()

    // Two success paths, both gated through the username flow:
    //  - auto-confirm projects create a session immediately → the VM flips
    //    to NeedsUsername → the NavHost shows ChooseUsernameScreen.
    //  - email-confirmation projects stay SignedOut with a confirmation flag
    //    (see below) — user confirms in their inbox first.
    LaunchedEffect(session) {
        when (session) {
            is AuthViewModel.SessionState.NeedsUsername -> onRegisterSuccess()
            else -> Unit
        }
    }

    AuthScaffold(
        title = stringResource(R.string.auth_register_title),
        onBackAction = onRegisterBack
    ) {
        var email by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var confirm by rememberSaveable { mutableStateOf("") }

        val localError = when {
            password.isNotEmpty() && confirm.isNotEmpty() && password != confirm ->
                stringResource(R.string.auth_error_password_mismatch)
            else -> null
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; viewModel.clearFormError() },
            label = { Text(stringResource(R.string.auth_email)) },
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it; viewModel.clearFormError() },
            label = stringResource(R.string.auth_password),
            enabled = !form.busy
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it; viewModel.clearFormError() },
            label = stringResource(R.string.auth_confirm_password),
            enabled = !form.busy
        )

        val errorText = form.errorRes?.let { stringResource(it) } ?: localError
        if (errorText != null) {
            Spacer(Modifier.height(12.dp))
            ErrorBanner(errorText)
        }

        if (form.awaitingEmailConfirmation) {
            Spacer(Modifier.height(12.dp))
            SuccessBanner(stringResource(R.string.auth_check_email))
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.signUp(email, password) },
            enabled = !form.busy && email.isNotBlank() &&
                password.length >= 6 && password == confirm,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (form.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.auth_register_cta), fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onBackToLogin,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.auth_have_account))
        }
    }
}

/**
 * The non-skippable step after registration. Reached ONLY while the VM sits
 * in SessionState.NeedsUsername; the NavHost makes it the start destination
 * during that state and system back is a no-op here, so there is no path
 * into the app without completing it.
 */
@Composable
fun ChooseUsernameScreen(
    viewModel: AuthViewModel = viewModel()
) {
    val session by viewModel.sessionState.collectAsState()
    val form by viewModel.usernameForm.collectAsState()
    val needsUsername = session as? AuthViewModel.SessionState.NeedsUsername

    AuthScaffold(
        title = stringResource(R.string.username_title),
        showBack = false
    ) {
        var username by rememberSaveable { mutableStateOf("") }
        var checkVersion by remember { mutableStateOf(0) }

        // Debounced availability check while typing.
        LaunchedEffect(username) {
            if (username.trim().length >= 3) {
                val v = ++checkVersion
                delay(400)
                if (v == checkVersion) viewModel.checkUsername(username)
            } else {
                viewModel.checkUsername(username) // resets state for short input
            }
        }

        Text(
            stringResource(R.string.username_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { input ->
                // Enforce charset live (lowercase letters, digits, underscore).
                username = input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(20)
            },
            label = { Text(stringResource(R.string.username_label)) },
            leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
            supportingText = {
                when {
                    form.checking -> Text(stringResource(R.string.username_checking))
                    form.available == true && username.trim().length >= 3 -> Row {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.widthIn(min = 4.dp))
                        Text(stringResource(R.string.username_available))
                    }
                    form.available == false && username.trim().length >= 3 -> Text(
                        stringResource(form.errorRes ?: R.string.auth_error_username_taken),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            trailingIcon = {
                if (form.checking) CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                )
            },
            singleLine = true,
            enabled = !form.busy,
            isError = form.available == false && username.trim().length >= 3,
            modifier = Modifier.fillMaxWidth()
        )

        form.errorRes?.let { res ->
            if (form.available == null || form.available == false) {
                Spacer(Modifier.height(8.dp))
                if (form.available == null) ErrorBanner(stringResource(res))
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { needsUsername?.let { viewModel.confirmUsername(it.userId, username) } },
            enabled = !form.busy && form.available == true,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (form.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.username_confirm_cta), fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.username_rules),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// --- shared building blocks -------------------------------------------------

/** Centered, width-capped scaffold used by all three auth screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScaffold(
    title: String,
    showBack: Boolean = true,
    onBackAction: () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBack) {
                        // A real back arrow would offer escaping the flow —
                        // auth screens are entered intentionally, so the
                        // system back gesture remains the only exit.
                        IconButton(onClick = onBackAction) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedScreen {
                Column(
                    modifier = Modifier
                        .imePadding()
                        .widthIn(max = NyasarContentWidth.formMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Hiking,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(vertical = 20.dp)
                            .size(56.dp)
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
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
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.auth_hide_password else R.string.auth_show_password
                    )
                )
            }
        },
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ErrorBanner(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.widthIn(min = 6.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SuccessBanner(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.MarkEmailRead,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.widthIn(min = 6.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Extracts the Google ID token from a Credential Manager result, or null
 * when the credential is not a Google ID token (defensive — the request was
 * built with GetGoogleIdOption, but third-party credential providers can
 * technically answer a federated request too).
 */
fun googleIdTokenOf(credential: androidx.credentials.Credential): String? =
    if (credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        GoogleIdTokenCredential.createFrom(credential.data).idToken
    } else null
