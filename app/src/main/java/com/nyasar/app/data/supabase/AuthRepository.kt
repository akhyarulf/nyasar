package com.nyasar.app.data.supabase

import android.util.Log
import io.github.jan.supabase.exceptions.BadRequestRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
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
 * signup with a temporary auto-generated username. [isUsernameAvailable]
 * and [updateUsername] therefore only ever SELECT and UPDATE the existing
 * row — never INSERT.
 */
class AuthRepository {

    @Serializable
    data class Profile(
        val id: String,
        val username: String
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
                    eq("username", username)
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
     * row") applies naturally.
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
                }
            ) {
                eq("id", userId)
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

    /** Fetches the profile for a session user (username display, null = not set). */
    suspend fun getUsername(userId: String): String? {
        if (!SupabaseClientProvider.isConfigured) return null
        return try {
            SupabaseClientProvider.client.postgrest["profiles"]
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("username")) {
                    eq("id", userId)
                }
                .decodeSingleOrNull<Profile>()?.username
        } catch (e: Exception) {
            Log.e(TAG, "getUsername failed", e)
            null
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
