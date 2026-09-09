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
    val speedUnit: String = "kmh",
    /** Last basemap picked in the in-map BasemapPickerSheet, persisted by
     *  BasemapEntry.gpxKey (stable id, survives renames — see the catalog's
     *  "OpenFreeMap" rename note). "libertyTopo" matches
     *  BasemapEntry.fromId(null)'s own fallback, so an unset key and the
     *  default entry agree. Read by Home/Recording/RoutePreview so ONE
     *  selection follows the user across all 3 map screens AND across
     *  process restarts (previously each ViewModel kept its own
     *  in-memory copy that reset on every relaunch). */
    val basemapId: String = "libertyTopo",
    /** Active Waymarked Trails overlays, persisted by OverlayLayer.id.
     *  MUST be shared across screens now that the 3 map screens share ONE
     *  MapView instance: the overlay effect edits the (single) loaded style,
     *  so a screen passing emptySet() would visibly strip the other screen's
     *  overlays on every switch. */
    val overlayIds: Set<String> = emptySet(),
    /** "Jalur Saya" overlay — when true, all saved Library routes are drawn
     *  as lines on the map (see MyRoutesOverlay). Same cross-screen rule as
     *  [overlayIds]: with ONE shared MapView, a per-screen flag would let
     *  the last-mounted screen decide whether everyone's routes are
     *  visible. Default false (off) — it's a user opt-in layer. */
    val myRoutesOverlayEnabled: Boolean = false,
    /** First-launch location onboarding ("why we need GPS" explainer before
     *  the system permission popup) has been shown+actioned. Once true it
     *  never shows again — the request itself lives in MainActivity. */
    val locationOnboardingShown: Boolean = false
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
        val BASEMAP_ID = stringPreferencesKey("basemap_id") // BasemapEntry.gpxKey
        val OVERLAY_IDS = androidx.datastore.preferences.core.stringSetPreferencesKey("overlay_ids") // OverlayLayer.id
        val MY_ROUTES_OVERLAY = androidx.datastore.preferences.core.booleanPreferencesKey("my_routes_overlay_enabled")
        val LOCATION_ONBOARDING = androidx.datastore.preferences.core.booleanPreferencesKey("location_onboarding_shown")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            providerId = prefs[Keys.PROVIDER_ID] ?: "maptiler",
            themeMode = prefs[Keys.THEME_MODE] ?: "system",
            languageMode = prefs[Keys.LANGUAGE_MODE] ?: "system",
            keepScreenOnWhileRecording = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            autoPauseEnabled = prefs[Keys.AUTO_PAUSE] ?: true,
            speedUnit = prefs[Keys.SPEED_UNIT] ?: "kmh",
            basemapId = prefs[Keys.BASEMAP_ID] ?: "libertyTopo",
            overlayIds = prefs[Keys.OVERLAY_IDS] ?: emptySet(),
            myRoutesOverlayEnabled = prefs[Keys.MY_ROUTES_OVERLAY] ?: false,
            locationOnboardingShown = prefs[Keys.LOCATION_ONBOARDING] ?: false
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

    /** Persist the 9-basemap picker selection. Called from every screen that
     *  shows BasemapPickerSheet (Home, Recording, RoutePreview) — one row in
     *  DataStore is the single source of truth, and the reactive `settings`
     *  flow propagates the change to the other screens within milliseconds. */
    suspend fun setBasemapId(id: String) {
        context.dataStore.edit { it[Keys.BASEMAP_ID] = id }
    }

    /** Persist the Waymarked Trails overlay toggles (see AppSettings.overlayIds
     *  for why this is app-wide, not per-screen). */
    suspend fun setOverlayIds(ids: Set<String>) {
        context.dataStore.edit { it[Keys.OVERLAY_IDS] = ids }
    }

    /** Persist the "Jalur Saya" overlay switch — same shared, app-wide
     *  reasoning as [setOverlayIds]. */
    suspend fun setMyRoutesOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MY_ROUTES_OVERLAY] = enabled }
    }

    /** Mark the first-launch location onboarding as done so the explainer
     *  dialog never appears again on subsequent launches. */
    suspend fun setLocationOnboardingShown() {
        context.dataStore.edit { it[Keys.LOCATION_ONBOARDING] = true }
    }
}
