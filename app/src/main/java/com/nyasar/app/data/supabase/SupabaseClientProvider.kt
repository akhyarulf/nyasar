package com.nyasar.app.data.supabase

import android.content.Context
import com.nyasar.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest

/**
 * App-wide [SupabaseClient] holder, mirroring the repo's singleton-repository
 * conventions (AppDatabase.get, SettingsRepository-per-context). The client is
 * created lazily on first access — never at process start — so a build without
 * Supabase credentials configured (BuildConfig.SUPABASE_URL == "", e.g. a CI
 * or fork build) never crashes at launch; [isConfigured] reports whether the
 * credentials were present and [client] throws only when account features are
 * actually used (AuthViewModel maps that case to a friendly "not configured"
 * screen instead of an error).
 *
 * Session persistence comes from gotrue-kt's Android integration: the AAR
 * bundles an androidx.startup Initializer that captures the application
 * context at process start and registers a SettingsSessionManager, so the
 * session survives process death without any extra wiring here.
 *
 * The publishable key (anon key) is designed by Supabase to be embedded in
 * clients — RLS on every table is the actual security boundary. The secret
 * key must never be placed in this app.
 */
object SupabaseClientProvider {

    @Volatile private var instance: SupabaseClient? = null

    /** True when both credentials were injected via local.properties. */
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    val client: SupabaseClient
        get() = instance ?: synchronized(this) {
            instance ?: createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
            ) {
                install(Auth)
                install(Postgrest)
            }.also { instance = it }
        }

    /** DI seam: returns the client, or null when credentials are absent. */
    fun getOrNull(context: Context): SupabaseClient? =
        if (isConfigured) client else null
}
