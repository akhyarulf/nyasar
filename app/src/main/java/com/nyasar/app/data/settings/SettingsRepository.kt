package com.nyasar.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nyasar_settings")

data class AppSettings(
    val providerId: String,
    val themeMode: String = "system",
    val languageMode: String = "system",
    val keepScreenOnWhileRecording: Boolean = true,
    /** Spec P3C: "auto pause ... berikan setting Auto Pause ON/OFF". Read
     *  once when a recording session starts — mid-hike settings changes
     *  are a separate concern. */
    val autoPauseEnabled: Boolean = true,
    /** Speed unit setting: "kmh" or "mph". Applied consistently across
     *  recording, activity, navigation, statistics, history, and route info. */
    val speedUnit: String = "kmh"
)

/**
 * The only place app-wide settings are read/written.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val THEME_MODE = stringPreferencesKey("theme_mode") // "system" | "light" | "dark"
        val LANGUAGE_MODE = stringPreferencesKey("language_mode") // "system" | "id" | "en"
        val KEEP_SCREEN_ON = androidx.datastore.preferences.core.booleanPreferencesKey("keep_screen_on_recording")
        val AUTO_PAUSE = androidx.datastore.preferences.core.booleanPreferencesKey("auto_pause_enabled")
        val SPEED_UNIT = stringPreferencesKey("speed_unit") // "kmh" | "mph"
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            providerId = prefs[Keys.PROVIDER_ID] ?: "maptiler",
            themeMode = prefs[Keys.THEME_MODE] ?: "system",
            languageMode = prefs[Keys.LANGUAGE_MODE] ?: "system",
            keepScreenOnWhileRecording = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            autoPauseEnabled = prefs[Keys.AUTO_PAUSE] ?: true,
            speedUnit = prefs[Keys.SPEED_UNIT] ?: "kmh"
        )
    }

    suspend fun setProvider(providerId: String) {
        context.dataStore.edit { it[Keys.PROVIDER_ID] = providerId }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setLanguageMode(mode: String) {
        context.dataStore.edit { it[Keys.LANGUAGE_MODE] = mode }
    }

    suspend fun setKeepScreenOnWhileRecording(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setAutoPauseEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PAUSE] = enabled }
    }

    suspend fun setSpeedUnit(unit: String) {
        context.dataStore.edit { it[Keys.SPEED_UNIT] = unit }
    }
}
