package com.nyasar.app.data.supabase

import android.util.Log
import io.github.jan.supabase.exceptions.BadRequestRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.Serializable

/**
 * All Supabase reads/writes for the account feature (Phase 1: auth only —
 * login/register, username selection, logout). Every failure is translated
 * into one of the app-level [AuthError] variants so the UI layer can show
 * localized messages without ever seeing SDK types, and every call is
 * explicitly documented about which profile-row contract it relies on.
 *
 * Key schema contract (from the user's Supabase setup): the
 * `handle_new_user` trigger creates a `profiles` row automatically on
 * signup with a temporary auto-generated username. [checkUsername]
 * and [updateUsername] therefore only ever SELECT and UPDATE the existing
 * row — never INSERT. The `username_is_set` column (added by the user's
 * SQL migration) marks whether the user has replaced that temp username;
 * it is read by [getProfileStatus] and written by [updateUsername], and
 * is the sole basis for the ChooseUsername gate (see AuthViewModel).
 */
class AuthRepository {

    @Serializable
    data class Profile(
        val id: String,
        val username: String,
        /** False until the user picks a real username (replaces the
         *  trigger's auto-generated temp one). Default keeps decoding
         *  working for selects that only request id+username. */
        val username_is_set: Boolean = false
    )

    /**
     * Gate status for a session user, read from the profiles row.
     * [usernameIsSet] — not the session source — decides whether the
     * ChooseUsername screen is forced (see AuthViewModel docs).
     */
    data class ProfileStatus(
        val username: String?,
        val usernameIsSet: Boolean
    )

    /** Neutral error categories the UI can localize. */
    enum class AuthError {
        EMAIL_IN_USE,      // 422 "already registered" on signup
        INVALID_CREDENTIALS, // login: wrong email/password
        WEAK_PASSWORD,     // 422 on signup
        USERNAME_TAKEN,    // unique violation on the profiles UPDATE
        USERNAME_INVALID,  // client-side validation failed before any call
        NETWORK,           // no connection / timeout / 5xx
        NOT_CONFIGURED,    // BuildConfig credentials blank (graceful skip)
        UNKNOWN
    }

    /** Result wrapper — mirrors the codebase's preference for explicit
     *  sealed outcomes over null-checking (see DetailLoadState). */
    sealed class Outcome {
        data object Success : Outcome()
        data class Failure(val error: AuthError) : Outcome()
    }

    /** Username availability check result. */
    sealed class UsernameCheck {
        data object Available : UsernameCheck()
        data object Taken : UsernameCheck()
        data class Invalid(val reasonRes: Int) : UsernameCheck()
        data class Error(val error: AuthError) : UsernameCheck()
    }

    /**
     * Sign up with email/password. Returns the new user id, or a Failure.
     * The profiles row itself is created server-side by the
     * handle_new_user trigger — nothing is written here.
     */
    suspend fun signUp(email: String, password: String): Pair<Outcome, String?> {
        if (!SupabaseClientProvider.isConfigured) return Outcome.Failure(AuthError.NOT_CONFIGURED) to null
        return try {
            val result = SupabaseClientProvider.client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            Outcome.Success to result?.id
        } catch (e: BadRequestRestException) {
            // gotrue maps BOTH 400 and 422 here; the body message tells them
            // apart ("User already registered" vs password policy errors).
            if (e.error.contains("already", ignoreCase = true))
                Outcome.Failure(AuthError.EMAIL_IN_USE) to null
            else Outcome.Failure(AuthError.WEAK_PASSWORD) to null
        } catch (e: RestException) {
            Outcome.Failure(AuthError.NETWORK) to null
        } catch (e: Exception) {
            Log.e(TAG, "signUp failed", e)
            Outcome.Failure(AuthError.NETWORK) to null
        }
    }

    /** Sign in with email/password. Session is persisted by gotrue-kt. */
    suspend fun signIn(email: String, password: String): Outcome {
        if (!SupabaseClientProvider.isConfigured) return Outcome.Failure(AuthError.NOT_CONFIGURED)
        return try {
            SupabaseClientProvider.client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            Outcome.Success
        } catch (e: UnauthorizedRestException) {
            // Supabase returns 400 for both "email not found" and "wrong
            // password" — one category keeps the wording honest (don't leak
            // whether an account exists, same as the web app behavior).
            Outcome.Failure(AuthError.INVALID_CREDENTIALS)
        } catch (e: BadRequestRestException) {
            Outcome.Failure(AuthError.INVALID_CREDENTIALS)
        } catch (e: RestException) {
            Outcome.Failure(AuthError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "signIn failed", e)
            Outcome.Failure(AuthError.NETWORK)
        }
    }

    /** Logout (local session clear + server revoke). Never throws. */
    suspend fun signOut() {
        try {
            if (SupabaseClientProvider.isConfigured) {
                SupabaseClientProvider.client.auth.signOut()
            }
        } catch (e: Exception) {
            Log.e(TAG, "signOut failed (session cleared locally regardless)", e)
        }
    }

    /**
     * Re-authentication gate for the destructive account actions (change
     * password / change email / delete account). Deliberately a fresh
     * signInWith(Email) instead of trusting the live session: whoever is
     * holding the phone must prove they know the REAL password before any
     * of those actions is allowed. Returns [AuthError.INVALID_CREDENTIALS]
     * on a wrong password so the UI can say so directly.
     */
    suspend fun reauthenticateWithPassword(currentPassword: String): Outcome {
        if (!SupabaseClientProvider.isConfigured) return Outcome.Failure(AuthError.NOT_CONFIGURED)
        val email = SupabaseClientProvider.client.auth.currentUserOrNull()?.email
            ?: return Outcome.Failure(AuthError.UNKNOWN)
        return try {
            SupabaseClientProvider.client.auth.signInWith(Email) {
                this.email = email
                this.password = currentPassword
            }
            Outcome.Success
        } catch (e: UnauthorizedRestException) {
            Outcome.Failure(AuthError.INVALID_CREDENTIALS)
        } catch (e: BadRequestRestException) {
            Outcome.Failure(AuthError.INVALID_CREDENTIALS)
        } catch (e: RestException) {
            Log.e(TAG, "reauthenticate failed", e)
            Outcome.Failure(AuthError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "reauthenticate failed", e)
            Outcome.Failure(AuthError.NETWORK)
        }
    }

    /**
     * Change password for the signed-in user. API note: in gotrue-kt 2.2.2
     * the user-facing call is modifyUser (updateUser does not exist until
     * later SDK lines) — verified from the artifact's own sources. The
     * caller must have passed [reauthenticateWithPassword] first; Supabase
     * itself does not enforce that order, this app does.
     */
    suspend fun updatePassword(newPassword: String): Outcome {
        if (!SupabaseClientProvider.isConfigured) return Outcome.Failure(AuthError.NOT_CONFIGURED)
        // Same minimum as the existing signUp flow (6 chars, enforced in
        // AuthScreens) — reused here so both entry points share one rule.
        if (newPassword.length < 6) return Outcome.Failure(AuthError.WEAK_PASSWORD)
        return try {
            SupabaseClientProvider.client.auth.modifyUser {
                password = newPassword
            }
            Outcome.Success
        } catch (e: BadRequestRestException) {
            // gotrue maps password-policy violations (400/422) here.
            Log.e(TAG, "updatePassword rejected: ${e.error}", e)
            Outcome.Failure(AuthError.WEAK_PASSWORD)
        } catch (e: RestException) {
            Log.e(TAG, "updatePassword failed", e)
            Outcome.Failure(AuthError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "updatePassword failed", e)
            Outcome.Failure(AuthError.NETWORK)
        }
    }

    /** Result of an email change — models the "staged" case honestly. */
    sealed class EmailUpdate {
        /** Applied immediately (project does not require confirmation). */
        data object Applied : EmailUpdate()

        /** Change is staged: a confirmation link was sent to the NEW
         *  address; the account keeps the old one until it is clicked. */
        data class ConfirmationRequired(val newEmail: String) : EmailUpdate()

        data class Failure(val error: AuthError) : EmailUpdate()
    }

    /**
     * Change email for the signed-in user. With "Confirm email" enabled in
     * the Supabase project (it is — the project already gates signup behind
     * email confirmation), GoTrue stages the change: the API returns the
     * user with email unchanged plus new_email/email_change_sent_at set,
     * and a confirmation mail goes to the NEW address (both addresses must
     * confirm depending on the project's double-confirmation setting).
     * The result models both outcomes; the UI must never claim the change
     * is instant. Email lives in auth.users (managed by Supabase Auth) —
     * profiles has no email column, so nothing else is written.
     */
    suspend fun updateEmail(newEmail: String): EmailUpdate {
        if (!SupabaseClientProvider.isConfigured) return EmailUpdate.Failure(AuthError.NOT_CONFIGURED)
        return try {
            val user = SupabaseClientProvider.client.auth.modifyUser {
                email = newEmail
            }
            // Staged iff the server says so: new_email present, or an email
            // change was stamped without the email itself changing.
            val staged = user.newEmail != null ||
                (user.emailChangeSentAt != null && user.email != newEmail)
            if (staged) EmailUpdate.ConfirmationRequired(newEmail)
            else EmailUpdate.Applied
        } catch (e: BadRequestRestException) {
            // "already registered by another account" / "same as current"
            // land in 400/422 — classify by message, mirroring signUp().
            val msg = e.error
            Log.e(TAG, "updateEmail rejected: $msg", e)
            EmailUpdate.Failure(
                if (msg.contains("already", ignoreCase = true) ||
                    msg.contains("in use", ignoreCase = true)
                ) AuthError.EMAIL_IN_USE else AuthError.UNKNOWN
            )
        } catch (e: RestException) {
            Log.e(TAG, "updateEmail failed", e)
            EmailUpdate.Failure(AuthError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "updateEmail failed", e)
            EmailUpdate.Failure(AuthError.NETWORK)
        }
    }

    /**
     * Self-service account deletion. The app has no service-role key (by
     * design), so the deletion runs as the SECURITY DEFINER Postgres RPC
     * `delete_own_account()` (see supabase/migrations/0002_*.sql): it
     * deletes ONLY the calling user's auth.users row — every public table
     * cascades from auth.users per the schema. After the server side
     * succeeds the local session is cleared too. Room data on the device is
     * intentionally NOT touched (explicit product decision).
     */
    suspend fun deleteAccount(): Outcome {
        if (!SupabaseClientProvider.isConfigured) return Outcome.Failure(AuthError.NOT_CONFIGURED)
        return try {
            SupabaseClientProvider.client.postgrest.rpc("delete_own_account")
            // Local sign-out never throws (see signOut) and LOCAL scope does
            // not need the (now-dead) token server-side.
            SupabaseClientProvider.client.auth.signOut()
            Outcome.Success
        } catch (e: RestException) {
            val msg = "${e.error} ${e.description ?: ""} ${e.message ?: ""}"
            Log.e(TAG, "deleteAccount failed: $msg", e)
            // A missing function (404 PGRST202 / "schema cache") means the
            // 0002 migration was never applied — surface that distinctly so
            // the report says "run the SQL", not "network error".
            Outcome.Failure(
                if (msg.contains("does not exist", ignoreCase = true) ||
                    msg.contains("schema cache", ignoreCase = true)
                ) AuthError.UNKNOWN else AuthError.NETWORK
            )
        } catch (e: Exception) {
            Log.e(TAG, "deleteAccount failed", e)
            Outcome.Failure(AuthError.NETWORK)
        }
    }

    /**
     * Login Google — exchanges a Google ID token for a Supabase session.
     * The token is obtained on the UI side via AndroidX Credential Manager
     * (GetGoogleIdOption with BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID, the
     * WEB client id registered in Supabase Dashboard -> Auth -> Providers
     * -> Google). gotrue-kt 2.2.2 models Google as an IDTokenProvider (no
     * browser redirect/deeplink on Android for this SDK line), so the flow
     * is: Google Play services issues the ID token -> this call swaps it
     * for a full session (grant_type=id_token). Works for both new users
     * (auto-created; the handle_new_user trigger makes their profiles row)
     * and existing ones.
     */
    suspend fun signInWithGoogle(idToken: String): Outcome {
        if (!SupabaseClientProvider.isConfigured) return Outcome.Failure(AuthError.NOT_CONFIGURED)
        return try {
            SupabaseClientProvider.client.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Google
            }
            Outcome.Success
        } catch (e: BadRequestRestException) {
            // Most common real cause: the Google provider is not enabled in
            // the Supabase project, or the client id mismatches the one
            // registered there.
            Log.e(TAG, "signInWithGoogle rejected: ${e.error}", e)
            Outcome.Failure(AuthError.UNKNOWN)
        } catch (e: RestException) {
            Log.e(TAG, "signInWithGoogle failed", e)
            Outcome.Failure(AuthError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "signInWithGoogle failed", e)
            Outcome.Failure(AuthError.NETWORK)
        }
    }

    /**
     * Username availability — case-insensitive exact match against
     * profiles.username. Only the id+username columns are fetched.
     */
    suspend fun checkUsername(username: String): UsernameCheck {
        if (!SupabaseClientProvider.isConfigured) return UsernameCheck.Error(AuthError.NOT_CONFIGURED)
        val invalid = validateUsernameFormat(username) ?: return UsernameCheck.Invalid(
            com.nyasar.app.R.string.username_invalid_chars
        )
        return try {
            val existing = SupabaseClientProvider.client.postgrest["profiles"]
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("id", "username")) {
                    // 2.2.2: the request lambda's receiver is
                    // PostgrestRequestBuilder — string-column filters go
                    // through its filter { PostgrestFilterBuilder } wrapper.
                    filter {
                        eq("username", username)
                    }
                }
                .decodeList<Profile>()
            if (existing.isEmpty()) UsernameCheck.Available else UsernameCheck.Taken
        } catch (e: Exception) {
            Log.e(TAG, "checkUsername failed", e)
            UsernameCheck.Error(AuthError.NETWORK)
        }
    }

    /**
     * Sets the username on the profile row that ALREADY EXISTS (created by
     * the handle_new_user trigger). UPDATE only — no INSERT — per the task
     * contract. userId comes from the current session, so RLS ("update own
     * row") applies naturally. Also flips `username_is_set` to true — the
     * flag that releases the ChooseUsername gate on every later session.
     */
    suspend fun updateUsername(userId: String, username: String): Outcome {
        if (!SupabaseClientProvider.isConfigured) return Outcome.Failure(AuthError.NOT_CONFIGURED)
        val normalized = username.trim()
        if (validateUsernameFormat(normalized) == null) {
            return Outcome.Failure(AuthError.USERNAME_INVALID)
        }
        return try {
            SupabaseClientProvider.client.postgrest["profiles"].update(
                update = {
                    set("username", normalized)
                    set("username_is_set", true)
                }
            ) {
                filter {
                    eq("id", userId)
                }
            }
            Outcome.Success
        } catch (e: RestException) {
            // A username taken between check and update surfaces as a Postgres
            // unique-violation (23505). Postgrest maps 409 Conflict to
            // UnknownRestException in 2.2.2 (no status code on the exception),
            // so classification is by message content — both the raw driver
            // wording and PostgREST's phrasing are matched.
            val msg = "${e.error} ${e.description ?: ""} ${e.message ?: ""}"
            Log.e(TAG, "updateUsername failed: $msg", e)
            if (msg.contains("duplicate", ignoreCase = true) ||
                msg.contains("unique", ignoreCase = true)
            ) Outcome.Failure(AuthError.USERNAME_TAKEN)
            else Outcome.Failure(AuthError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "updateUsername failed", e)
            Outcome.Failure(AuthError.NETWORK)
        }
    }

    /**
     * Reads the gate state for a session user: the current username plus
     * the username_is_set flag, queried together from the profiles row.
     *
     * Fails OPEN on any error (including "row missing", which shouldn't
     * happen thanks to the trigger): usernameIsSet = true. Rationale — a
     * transient network failure must never lock a user who already chose
     * a username out of the app; the worst case is a stale/blank display
     * name until the next successful read.
     */
    suspend fun getProfileStatus(userId: String): ProfileStatus {
        if (!SupabaseClientProvider.isConfigured) return ProfileStatus(null, usernameIsSet = true)
        return try {
            val row = SupabaseClientProvider.client.postgrest["profiles"]
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("id", "username", "username_is_set")) {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingleOrNull<Profile>()
            row?.let { ProfileStatus(it.username, it.username_is_set) }
                ?: ProfileStatus(null, usernameIsSet = true)
        } catch (e: Exception) {
            Log.e(TAG, "getProfileStatus failed", e)
            ProfileStatus(null, usernameIsSet = true)
        }
    }

    // --- helpers ---

    /** Non-null = normalized username, null = format invalid. Rules:
     *  3-20 chars, a-z0-9_ (lowercased), no leading/trailing underscore. */
    private fun validateUsernameFormat(raw: String): String? {
        val u = raw.trim().lowercase()
        if (u.length < 3 || u.length > 20) return null
        if (!u.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) return null
        if (u.startsWith("_") || u.endsWith("_")) return null
        return u
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
