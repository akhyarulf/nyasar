package com.nyasar.app.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nyasar.app.data.supabase.AuthRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.gotrue.SessionSource
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
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
 *      Authenticated → [applyAuthenticated] reads profiles.username_is_set
 *      from the database → flag still false (the trigger row carries the
 *      auto-generated temp username) → SessionState.NeedsUsername →
 *      ChooseUsernameScreen is forced by the NavHost gate.
 *   2. ChooseUsernameScreen → [confirmUsername] → server UPDATE profiles
 *      (username + username_is_set = true) → state becomes SignedIn →
 *      Home is reachable.
 *   3. The gate is enforced in MainActivity's NavHost: while
 *      SessionState.NeedsUsername is active, the start destination IS the
 *      username screen and the back stack holds nothing else — there is no
 *      path to any other screen (this is what makes the step non-skippable,
 *      not UI politeness).
 * Why the DB flag and not SessionSource: on projects with email
 * confirmation required, the first REAL session is an ordinary sign-IN
 * (user clicks the emailed link, then logs in manually) — SessionSource
 * is SignIn there, so gating on the source let fresh users through with
 * their temp username. profiles.username_is_set is the durable source of
 * truth instead: false → gated (fresh signup OR first login of a user
 * who never picked a username), true → never re-gated. [AuthRepository.getProfileStatus]
 * fails OPEN, so a transient network error can not lock a settled user
 * out of the app.
 */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AuthRepository()

    /** Full session state, driven directly by gotrue-kt's sessionStatus flow. */
    sealed class SessionState {
        /** No credentials configured in this build (graceful degrade). */
        data object Unconfigured : SessionState()

        /** Restoring a persisted session on app start. */
        data object Restoring : SessionState()

        /** The session is authenticated, but the profile lookup that
         * supplies username/email/member-since is still in flight. */
        data class LoadingAccount(
            val userId: String,
            val email: String? = null
        ) : SessionState()

        data object SignedOut : SessionState()

        /** profiles.username_is_set = false — a real username must be
         *  chosen before anything else (fresh signup, or the first login
         *  of a user who never went through the picker). */
        data class NeedsUsername(val userId: String) : SessionState()

        data class SignedIn(
            val userId: String,
            val email: String?,
            val username: String?,
            /** ISO-8601 profiles.created_at ("member since"), null = unknown. */
            val memberSince: String? = null
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

    /**
     * State for the account-management actions (change password, change
     * email, delete account) — one flow, sealed by flags instead of a
     * boolean-per-screen sprawl. Success flags are one-shot and consumed
     * via [clearAccountAction] when their UI (sheet/dialog) closes.
     */
    data class AccountActionState(
        val busy: Boolean = false,
        val errorRes: Int? = null,
        /** Password change succeeded. */
        val passwordChanged: Boolean = false,
        /** Email change applied immediately (no confirmation needed). */
        val emailChanged: Boolean = false,
        /** Email change staged by Supabase — confirmation link sent to
         *  this NEW address; the UI must say "check your inbox", not
         *  "email changed" (project requires email confirmation). */
        val emailConfirmationRequired: String? = null,
        /** Account deletion succeeded — session ends right after. */
        val accountDeleted: Boolean = false
    )

    private val _accountAction = MutableStateFlow(AccountActionState())
    val accountAction: StateFlow<AccountActionState> = _accountAction.asStateFlow()

    /** Google button is only offered when the WEB client id is injected. */
    val googleConfigured: Boolean
        get() = com.nyasar.app.BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID.isNotBlank()

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
                            // The session source is NOT the gate — it only
                            // seeds pendingEmail (see applyAuthenticated).
                            // The gate is the DB flag profiles.username_is_set,
                            // consulted for EVERY Authenticated emission.
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
        // The Auth token is ready before the profile row is known. Publish a
        // loading state while that request runs so screens do not look signed
        // out between Google sign-in and the account data becoming available.
        _sessionState.value = SessionState.LoadingAccount(
            userId = userId,
            email = session.user?.email
        )
        // The gate is DATABASE state, not the session source: on email-
        // confirmation projects the first real session is an ordinary sign-IN,
        // so the source can not distinguish a fresh user from a settled one.
        // profiles.username_is_set can — check it on every authenticated
        // emission, whichever way the session was created.
        val status = repo.getProfileStatus(userId)
        if (!status.usernameIsSet) {
            // Profile row exists (trigger) but the auto-generated temp
            // username was never replaced → force the chooser.
            if (fromFreshSignUp) {
                pendingEmail = session.user?.email
            }
            _sessionState.value = SessionState.NeedsUsername(userId)
            _usernameForm.value = UsernameFormState()
            _loginForm.value = FormState()
            return
        }
        _sessionState.value = SessionState.SignedIn(
            userId = userId,
            email = session.user?.email,
            username = status.username,
            memberSince = status.createdAt
        )
        _loginForm.value = FormState()
    }

    // --- login ---

    fun signIn(email: String, password: String) {
        if (_loginForm.value.busy) return
        _loginForm.value = FormState(busy = true)
        viewModelScope.launch {
            when (val r = repo.signIn(email.trim(), password)) {
                is AuthRepository.Outcome.Success -> {
                    // Keep the form busy until sessionStatus authenticates and
                    // applyAuthenticated finishes the profile lookup. Clearing
                    // it here creates a silent gap before the account UI opens.
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

    /**
     * Voluntary username change from the profile screen (differs from
     * [confirmUsername], which exits the NeedsUsername gate): updates the
     * profiles row and patches the CURRENT SignedIn state in place, so
     * email/memberSince never reset — confirmUsername's path rebuilds the
     * session from pendingEmail, which is null outside the fresh-signup
     * flow.
     */
    fun updateProfileUsername(username: String) {
        val current = _sessionState.value as? SessionState.SignedIn ?: return
        if (_usernameForm.value.busy) return
        _usernameForm.value = _usernameForm.value.copy(busy = true, errorRes = null)
        viewModelScope.launch {
            when (val r = repo.updateUsername(current.userId, username)) {
                is AuthRepository.Outcome.Success -> {
                    _usernameForm.value = UsernameFormState()
                    _sessionState.value = current.copy(username = username.trim().lowercase())
                }
                is AuthRepository.Outcome.Failure -> {
                    _usernameForm.value = _usernameForm.value.copy(busy = false, errorRes = r.error.messageRes())
                }
            }
        }
    }

    // --- account management (Phase 1: password / email / delete) ---

    /** Resets the one-shot success/error display of [accountAction]. */
    fun clearAccountAction() {
        _accountAction.value = AccountActionState()
    }

    /**
     * Change password with a re-authentication gate: the CURRENT password
     * is verified first (fresh signInWith), then the new one is applied.
     * Same 6-char minimum as the signUp flow — one shared rule.
     */
    fun changePassword(currentPassword: String, newPassword: String) {
        if (_accountAction.value.busy) return
        _accountAction.value = AccountActionState(busy = true)
        viewModelScope.launch {
            val reauth = repo.reauthenticateWithPassword(currentPassword)
            if (reauth is AuthRepository.Outcome.Failure) {
                _accountAction.value = AccountActionState(errorRes = reauth.error.messageRes())
                return@launch
            }
            when (val r = repo.updatePassword(newPassword)) {
                is AuthRepository.Outcome.Success ->
                    _accountAction.value = AccountActionState(passwordChanged = true)
                is AuthRepository.Outcome.Failure ->
                    _accountAction.value = AccountActionState(errorRes = r.error.messageRes())
            }
        }
    }

    /**
     * Change email with the same re-authentication gate. The result is
     * honest about staging: when the project requires email confirmation
     * (it does), the user sees "check your NEW inbox", never an instant
     * "email changed" claim.
     */
    fun changeEmail(currentPassword: String, newEmail: String) {
        if (_accountAction.value.busy) return
        _accountAction.value = AccountActionState(busy = true)
        viewModelScope.launch {
            val reauth = repo.reauthenticateWithPassword(currentPassword)
            if (reauth is AuthRepository.Outcome.Failure) {
                _accountAction.value = AccountActionState(errorRes = reauth.error.messageRes())
                return@launch
            }
            when (val r = repo.updateEmail(newEmail.trim())) {
                is AuthRepository.EmailUpdate.Applied ->
                    _accountAction.value = AccountActionState(emailChanged = true)
                is AuthRepository.EmailUpdate.ConfirmationRequired ->
                    _accountAction.value = AccountActionState(emailConfirmationRequired = r.newEmail)
                is AuthRepository.EmailUpdate.Failure ->
                    _accountAction.value = AccountActionState(errorRes = r.error.messageRes())
            }
        }
    }

    /**
     * Delete the account (SECURITY DEFINER RPC delete_own_account, see
     * supabase/migrations/0002_*.sql). Only cloud data dies — Room data on
     * this device is deliberately kept (explicit product decision, stated
     * in the confirmation dialog). The sessionStatus collector flips the
     * app to SignedOut as soon as the local session is cleared.
     */
    fun deleteAccount() {
        if (_accountAction.value.busy) return
        _accountAction.value = AccountActionState(busy = true)
        viewModelScope.launch {
            when (val r = repo.deleteAccount()) {
                is AuthRepository.Outcome.Success ->
                    _accountAction.value = AccountActionState(accountDeleted = true)
                is AuthRepository.Outcome.Failure ->
                    _accountAction.value = AccountActionState(errorRes = r.error.messageRes())
            }
        }
    }

    /**
     * Finish Google login: exchange the Credential-Manager ID token for a
     * Supabase session. Success is observed through sessionStatus (same as
     * email sign-in); failures land on the login form error slot.
     */
    fun signInWithGoogle(idToken: String) {
        if (_loginForm.value.busy) return
        _loginForm.value = FormState(busy = true)
        viewModelScope.launch {
            when (val r = repo.signInWithGoogle(idToken)) {
                is AuthRepository.Outcome.Success -> {
                    // Keep the form busy until sessionStatus authenticates and
                    // the profile lookup is complete. The login screen can
                    // then show one continuous loading state instead of briefly
                    // looking idle before the account/email data appears.
                }
                is AuthRepository.Outcome.Failure -> {
                    _loginForm.value = FormState(busy = false, errorRes = r.error.messageRes())
                }
            }
        }
    }

    // --- logout ---

    fun signOut() {
        viewModelScope.launch {
            repo.signOut()
            // Social mirror must not carry the previous account's
            // likes/bookmarks into the next session (realtime shared state).
            com.nyasar.app.data.supabase.SharedSocialState.clear()
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
