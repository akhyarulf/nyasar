package com.nyasar.app.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.supabase.AuthRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.gotrue.SessionSource
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.user.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for account/session state, following the app's
 * MutableStateFlow/StateFlow ViewModel convention (see SettingsRepository /
 * RecordingViewModel). Screen other than Auth obtain this VM through the
 * standard `viewModel()` factory — it is scoped per NavBackStackEntry, and
 * screens that need it app-wide observe it from their own `viewModel()`
 * instance (all instances share the same process-wide session via the
 * SupabaseClientProvider-backed [sessionState] collector below).
 *
 * Registration flow and the non-skippable username gate:
 *   1. RegisterScreen → [signUp] succeeds → sessionStatus emits
 *      Authenticated(source = SignUp) → SessionState.NeedsUsername →
 *      ChooseUsernameScreen is forced by the NavHost gate.
 *   2. ChooseUsernameScreen → [confirmUsername] → server UPDATE profiles →
 *      state becomes SignedIn → Home is reachable.
 *   3. The gate is enforced in MainActivity's NavHost: while
 *      SessionState.NeedsUsername is active, the start destination IS the
 *      username screen and the back stack holds nothing else — there is no
 *      path to any other screen (this is what makes the step non-skippable,
 *      not UI politeness).
 * Ordinary sign-IN also emits Authenticated, but with source = SignIn —
 * deliberately NOT routed to NeedsUsername (a returning user must never
 * be re-gated).
 */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AuthRepository()

    /** Full session state, driven directly by gotrue-kt's sessionStatus flow. */
    sealed class SessionState {
        /** No credentials configured in this build (graceful degrade). */
        data object Unconfigured : SessionState()

        /** Restoring a persisted session on app start. */
        data object Restoring : SessionState()

        data object SignedOut : SessionState()

        /** Fresh signup — username must be chosen before anything else. */
        data class NeedsUsername(val userId: String) : SessionState()

        data class SignedIn(
            val userId: String,
            val email: String?,
            val username: String?
        ) : SessionState()
    }

    data class FormState(
        val busy: Boolean = false,
        val errorRes: Int? = null,
        /** Register only: sign-up accepted but no session (email
         *  confirmation required by the project) — screen shows the
         *  "check your inbox" banner instead of doing nothing. */
        val awaitingEmailConfirmation: Boolean = false
    )

    data class UsernameFormState(
        val busy: Boolean = false,
        val checking: Boolean = false,
        val available: Boolean? = null, // null = belum dicek / input berubah
        val errorRes: Int? = null
    )

    private val _sessionState = MutableStateFlow<SessionState>(
        if (SupabaseClientProvider.isConfigured) SessionState.Restoring
        else SessionState.Unconfigured
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _loginForm = MutableStateFlow(FormState())
    val loginForm: StateFlow<FormState> = _loginForm.asStateFlow()

    private val _registerForm = MutableStateFlow(FormState())
    val registerForm: StateFlow<FormState> = _registerForm.asStateFlow()

    private val _usernameForm = MutableStateFlow(UsernameFormState())
    val usernameForm: StateFlow<UsernameFormState> = _usernameForm.asStateFlow()

    init {
        if (SupabaseClientProvider.isConfigured) {
            // Drive the whole app's session from the SDK's own StateFlow —
            // covers app restarts (restored session), refreshes, and logout
            // from anywhere, with a single collector.
            viewModelScope.launch {
                SupabaseClientProvider.client.auth.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.LoadingFromStorage -> {
                            _sessionState.value = SessionState.Restoring
                        }
                        is SessionStatus.NotAuthenticated -> {
                            _sessionState.value = SessionState.SignedOut
                        }
                        is SessionStatus.NetworkError -> {
                            // Keep last known state; a refresh retry will
                            // re-emit. Surfaces nothing scary to the user.
                        }
                        is SessionStatus.Authenticated -> {
                            // Gate ONLY on a fresh sign-UP. status.isNew would
                            // also be true for source = SignIn (and External),
                            // which would wrongly force every returning user
                            // through the username chooser on each login.
                            applyAuthenticated(
                                status.session,
                                fromFreshSignUp = status.source is SessionSource.SignUp
                            )
                        }
                    }
                }
            }
        }
    }

    /** Email captured from the session before the username gate. */
    private var pendingEmail: String? = null

    private suspend fun applyAuthenticated(session: UserSession, fromFreshSignUp: Boolean) {
        val userId = session.user?.id ?: run {
            _sessionState.value = SessionState.SignedOut
            return
        }
        if (fromFreshSignUp) {
            // Fresh registration: the profile row exists (trigger) but still
            // carries the auto-generated temp username → force the chooser.
            pendingEmail = session.user?.email
            _sessionState.value = SessionState.NeedsUsername(userId)
            _usernameForm.value = UsernameFormState()
            return
        }
        val username = repo.getUsername(userId)
        _sessionState.value = SessionState.SignedIn(
            userId = userId,
            email = session.user?.email,
            username = username
        )
    }

    // --- login ---

    fun signIn(email: String, password: String) {
        if (_loginForm.value.busy) return
        _loginForm.value = FormState(busy = true)
        viewModelScope.launch {
            when (val r = repo.signIn(email.trim(), password)) {
                is AuthRepository.Outcome.Success -> {
                    // sessionStatus collector flips state to SignedIn.
                    _loginForm.value = FormState()
                }
                is AuthRepository.Outcome.Failure -> {
                    _loginForm.value = FormState(busy = false, errorRes = r.error.messageRes())
                }
            }
        }
    }

    // --- register ---

    fun signUp(email: String, password: String) {
        if (_registerForm.value.busy) return
        _registerForm.value = FormState(busy = true)
        viewModelScope.launch {
            // signUp returns (outcome, newUserId?) — destructure it here;
            // newUserId is what distinguishes "session created" from the
            // email-confirmation-required path below.
            val (outcome, newUserId) = repo.signUp(email.trim(), password)
            when (outcome) {
                is AuthRepository.Outcome.Success -> {
                    _registerForm.value = FormState()
                    if (newUserId == null &&
                        SupabaseClientProvider.client.auth.currentSessionOrNull() == null
                    ) {
                        // Project requires email confirmation: no session was
                        // created, so sessionStatus will NOT flip to
                        // Authenticated. Tell the user to check their inbox
                        // rather than sitting on a silent screen.
                        _registerForm.value = FormState(awaitingEmailConfirmation = true)
                    }
                    // If a session IS created (auto-confirm on), the collector
                    // routes to NeedsUsername automatically.
                }
                is AuthRepository.Outcome.Failure -> {
                    _registerForm.value = FormState(busy = false, errorRes = outcome.error.messageRes())
                }
            }
        }
    }

    // --- username gate ---

    /** Live availability check as the user types (debounced by the caller). */
    fun checkUsername(username: String) {
        val normalized = username.trim()
        if (normalized.length < 3) {
            _usernameForm.value = _usernameForm.value.copy(
                checking = false, available = null, errorRes = null
            )
            return
        }
        _usernameForm.value = _usernameForm.value.copy(checking = true, errorRes = null)
        viewModelScope.launch {
            when (val r = repo.checkUsername(normalized)) {
                is AuthRepository.UsernameCheck.Available ->
                    _usernameForm.value = _usernameForm.value.copy(checking = false, available = true)
                is AuthRepository.UsernameCheck.Taken ->
                    _usernameForm.value = _usernameForm.value.copy(checking = false, available = false)
                is AuthRepository.UsernameCheck.Invalid ->
                    _usernameForm.value = _usernameForm.value.copy(checking = false, available = false, errorRes = r.reasonRes)
                is AuthRepository.UsernameCheck.Error ->
                    _usernameForm.value = _usernameForm.value.copy(checking = false, available = null, errorRes = r.error.messageRes())
            }
        }
    }

    /**
     * Confirms the username choice — the ONLY exit from NeedsUsername.
     * Updates the existing trigger-created profile row; on success the
     * state becomes SignedIn and the NavHost releases the gate.
     */
    fun confirmUsername(userId: String, username: String) {
        if (_usernameForm.value.busy) return
        _usernameForm.value = _usernameForm.value.copy(busy = true, errorRes = null)
        viewModelScope.launch {
            when (val r = repo.updateUsername(userId, username)) {
                is AuthRepository.Outcome.Success -> {
                    _usernameForm.value = UsernameFormState()
                    _sessionState.value = SessionState.SignedIn(
                        userId = userId,
                        email = pendingEmail,
                        username = username.trim().lowercase()
                    )
                    pendingEmail = null
                }
                is AuthRepository.Outcome.Failure -> {
                    _usernameForm.value = _usernameForm.value.copy(busy = false, errorRes = r.error.messageRes())
                }
            }
        }
    }

    // --- logout ---

    fun signOut() {
        viewModelScope.launch {
            repo.signOut()
            // sessionStatus collector flips to SignedOut.
        }
    }

    fun clearFormError() {
        if (_loginForm.value.errorRes != null) _loginForm.value = _loginForm.value.copy(errorRes = null)
        if (_registerForm.value.errorRes != null) _registerForm.value = _registerForm.value.copy(errorRes = null)
    }
}

/** Maps repository error categories to string resources — localized in both
 *  app languages (values/ + values-en/). */
fun AuthRepository.AuthError.messageRes(): Int = when (this) {
    AuthRepository.AuthError.EMAIL_IN_USE -> com.nyasar.app.R.string.auth_error_email_in_use
    AuthRepository.AuthError.INVALID_CREDENTIALS -> com.nyasar.app.R.string.auth_error_invalid_credentials
    AuthRepository.AuthError.WEAK_PASSWORD -> com.nyasar.app.R.string.auth_error_weak_password
    AuthRepository.AuthError.USERNAME_TAKEN -> com.nyasar.app.R.string.auth_error_username_taken
    AuthRepository.AuthError.USERNAME_INVALID -> com.nyasar.app.R.string.username_invalid_chars
    AuthRepository.AuthError.NETWORK -> com.nyasar.app.R.string.auth_error_network
    AuthRepository.AuthError.NOT_CONFIGURED -> com.nyasar.app.R.string.auth_error_not_configured
    AuthRepository.AuthError.UNKNOWN -> com.nyasar.app.R.string.auth_error_generic
}
