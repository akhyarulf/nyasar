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
    /** Off-route warning while a route is attached ("Menyimpang X m dari
     *  jalur"). OPT-IN (default off) per user decision: leaving the route is
     *  often deliberate on a hike, so the app stays quiet until the user
     *  asks to be warned. Turned on from the recording options sheet (gear),
     *  and turned back off either there or from "Diamkan" on the banner
     *  itself. Read live (not snapshotted at session start) so that in-session
     *  action takes effect immediately. */
    val offRouteAlertsEnabled: Boolean = false,
    /** Last basemap picked in the in-map BasemapPickerSheet, persisted by
     *  BasemapEntry.gpxKey (stable id, survives renames and removals —
     *  see BasemapEntry.fromId's fallback note: an id that no longer
     *  exists in the catalog, e.g. the removed "osm" entry, lands on the
     *  default). "libertyTopo" matches
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
     *  visible. Default true since 2026-09 (user request: every Data layer
     *  ships ON; it stays a user toggle in the layer picker). */
    val myRoutesOverlayEnabled: Boolean = true,
    /** Show downloaded-areas overlay on the main maps: green = complete,
     *  gray = incomplete, only for the active basemap's style. User opt-in
     *  true since 2026-09 (user request: layers on by default) — it draws
     *  on every map screen only when offline areas exist. */
    val offlineOverlayEnabled: Boolean = true,
    /** Map waypoint pins (GPX route waypoints + user-created waypoints) —
     *  when false the pins are hidden on EVERY map screen (Home, Recording,
     *  RoutePreview, Navigation, ActivityDetail). One of the three "Data"
     *  picker-sheet toggles, all persisted app-wide with the same rule: with
     *  ONE shared MapView a per-screen flag would let the last-mounted
     *  screen decide visibility for everyone. Default true since 2026-09
     *  (user request: pins ship ON; toggle off in the layer picker).
     *  NOTE: this hides the PINS
     *  only — Navigation's next-waypoint guidance logic is functional, not
     *  an overlay, and deliberately keeps working while pins are hidden. */
    val waypointsVisible: Boolean = true,
    /** First-launch location onboarding ("why we need GPS" explainer before
     *  the system permission popup) has been shown+actioned. Once true it
     *  never shows again — the request itself lives in MainActivity. */
    val locationOnboardingShown: Boolean = false,
    /** First-time-relevant-moment notification onboarding ("why Nyasar wants
     *  to post notifications" explainer, shown when the user first reaches
     *  the Recording screen on API 33+) has been shown+actioned. Fully
     *  independent of [locationOnboardingShown] — either decision can be
     *  made without the other. */
    val notificationOnboardingShown: Boolean = false,
    /** Battery-optimization onboarding ("why Nyasar wants to run without
     *  battery saver restrictions" explainer, shown once right before the
     *  first actual start attempt when PowerManager says we're still
     *  optimized) has been shown+actioned. Once true the start-flow gate
     *  never raises it again — manual access stays available via the
     *  Settings screen row, which opens the same system sheet on demand. */
    val batteryOptimizationOnboardingShown: Boolean = false,
    /** Default visibility for a NEW publish (Wikiloc 2-level): true = Semua
     *  orang, false = Hanya saya. Only a DEFAULT — the publish form still
     *  shows both options and whatever the user picks there wins. Opt-in
     *  privacy, so the default stays true to preserve the shipped behavior. */
    val defaultRouteVisibilityPublic: Boolean = true,
    /** Show the "Backtrack this track" action on an activity. OFF by
     *  default: it puts an extra navigation entry point on every activity
     *  detail page, which is only worth the clutter once the user has asked
     *  for it (same opt-in rule as [offRouteAlertsEnabled]). */
    val backtrackEnabled: Boolean = false,
    /** Estimated arrival time + sunset on the navigation screen, plus the
     *  "still out after dark" warning. OFF by default: it is a second,
     *  denser line in an already-slim stat bar, and the app's rule is that
     *  navigation must not turn into a dashboard until asked. Read at
     *  navigation start (like [providerId]) — changing it mid-hike is not
     *  applied to the running session. */
    val etaSunEnabled: Boolean = false
)

/**
 * The only place app-wide settings are read/written.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val WAYPOINTS_VISIBLE = androidx.datastore.preferences.core.booleanPreferencesKey("map_waypoints_visible")
        val THEME_MODE = stringPreferencesKey("theme_mode") // "system" | "light" | "dark"
        val LANGUAGE_MODE = stringPreferencesKey("language_mode") // "system" | "id" | "en"
        val KEEP_SCREEN_ON = androidx.datastore.preferences.core.booleanPreferencesKey("keep_screen_on_recording")
        val AUTO_PAUSE = androidx.datastore.preferences.core.booleanPreferencesKey("auto_pause_enabled")
        val SPEED_UNIT = stringPreferencesKey("speed_unit") // "kmh" | "mph"
        val OFF_ROUTE_ALERTS = androidx.datastore.preferences.core.booleanPreferencesKey("off_route_alerts_enabled")
        val BASEMAP_ID = stringPreferencesKey("basemap_id") // BasemapEntry.gpxKey
        val OVERLAY_IDS = androidx.datastore.preferences.core.stringSetPreferencesKey("overlay_ids") // OverlayLayer.id
        val MY_ROUTES_OVERLAY = androidx.datastore.preferences.core.booleanPreferencesKey("my_routes_overlay_enabled")
        val OFFLINE_OVERLAY = androidx.datastore.preferences.core.booleanPreferencesKey("offline_coverage_overlay_enabled")
        val LOCATION_ONBOARDING = androidx.datastore.preferences.core.booleanPreferencesKey("location_onboarding_shown")
        val NOTIFICATION_ONBOARDING = androidx.datastore.preferences.core.booleanPreferencesKey("notification_onboarding_shown")
        val BATTERY_ONBOARDING = androidx.datastore.preferences.core.booleanPreferencesKey("battery_optimization_onboarding_shown")
        val DEFAULT_ROUTE_VISIBILITY = androidx.datastore.preferences.core.booleanPreferencesKey("default_route_visibility_public")
        val BACKTRACK_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("backtrack_enabled")
        val ETA_SUN_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("eta_sun_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            providerId = prefs[Keys.PROVIDER_ID] ?: "maptiler",
            themeMode = prefs[Keys.THEME_MODE] ?: "system",
            languageMode = prefs[Keys.LANGUAGE_MODE] ?: "system",
            keepScreenOnWhileRecording = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            autoPauseEnabled = prefs[Keys.AUTO_PAUSE] ?: true,
            speedUnit = prefs[Keys.SPEED_UNIT] ?: "kmh",
            offRouteAlertsEnabled = prefs[Keys.OFF_ROUTE_ALERTS] ?: false,
            basemapId = prefs[Keys.BASEMAP_ID] ?: "libertyTopo",
            overlayIds = prefs[Keys.OVERLAY_IDS] ?: emptySet(),
            myRoutesOverlayEnabled = prefs[Keys.MY_ROUTES_OVERLAY] ?: true,
            offlineOverlayEnabled = prefs[Keys.OFFLINE_OVERLAY] ?: true,
            waypointsVisible = prefs[Keys.WAYPOINTS_VISIBLE] ?: true,
            locationOnboardingShown = prefs[Keys.LOCATION_ONBOARDING] ?: false,
            notificationOnboardingShown = prefs[Keys.NOTIFICATION_ONBOARDING] ?: false,
            batteryOptimizationOnboardingShown = prefs[Keys.BATTERY_ONBOARDING] ?: false,
            defaultRouteVisibilityPublic = prefs[Keys.DEFAULT_ROUTE_VISIBILITY] ?: true,
            backtrackEnabled = prefs[Keys.BACKTRACK_ENABLED] ?: false,
            etaSunEnabled = prefs[Keys.ETA_SUN_ENABLED] ?: false
        )
    }

    /** Persist the Backtrack action switch — see
     *  [AppSettings.backtrackEnabled]. */
    suspend fun setBacktrackEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKTRACK_ENABLED] = enabled }
    }

    /** Persist the arrival-estimate/sunset line — see
     *  [AppSettings.etaSunEnabled]. */
    suspend fun setEtaSunEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ETA_SUN_ENABLED] = enabled }
    }

    /** Persist the default visibility applied to a NEW publish — see
     *  [AppSettings.defaultRouteVisibilityPublic]. */
    suspend fun setDefaultRouteVisibilityPublic(isPublic: Boolean) {
        context.dataStore.edit { it[Keys.DEFAULT_ROUTE_VISIBILITY] = isPublic }
    }

    suspend fun setBatteryOptimizationOnboardingShown() {
        context.dataStore.edit { it[Keys.BATTERY_ONBOARDING] = true }
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

    suspend fun setOffRouteAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OFF_ROUTE_ALERTS] = enabled }
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

    /** Persist the downloaded-areas overlay switch — same shared,
     *  app-wide reasoning as [setMyRoutesOverlayEnabled]. */
    suspend fun setOfflineOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OFFLINE_OVERLAY] = enabled }
    }

    /** Persist the map-waypoint-pins switch — same shared, app-wide
     *  reasoning as [setMyRoutesOverlayEnabled]. */
    suspend fun setWaypointsVisible(visible: Boolean) {
        context.dataStore.edit { it[Keys.WAYPOINTS_VISIBLE] = visible }
    }

    /** Mark the first-launch location onboarding as done so the explainer
     *  dialog never appears again on subsequent launches. */
    suspend fun setLocationOnboardingShown() {
        context.dataStore.edit { it[Keys.LOCATION_ONBOARDING] = true }
    }

    /** Mark the Recording-screen notification explainer as done (see
     *  [AppSettings.notificationOnboardingShown]). */
    suspend fun setNotificationOnboardingShown() {
        context.dataStore.edit { it[Keys.NOTIFICATION_ONBOARDING] = true }
    }
}
