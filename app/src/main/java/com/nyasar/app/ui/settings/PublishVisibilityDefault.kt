package com.nyasar.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nyasar.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.map

/**
 * The persisted "default visibility for a new route" preference, read as
 * Compose state — one shared entry point for both publish forms (the Review
 * screen after recording and the Library route sheet), so the two can never
 * disagree about the default.
 *
 * This is a DEFAULT, not a lock: each form seeds its visibility toggle from
 * it once (via LaunchedEffect) and stops listening as soon as the user
 * touches the toggle, so an incoming DataStore emission can never overwrite
 * a deliberate choice mid-form.
 *
 * Initial value is `true` (Everyone) — the same default the app shipped with
 * before the preference existed, so an unset key changes nothing.
 */
@Composable
fun rememberDefaultPublishVisibility(): State<Boolean> {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    return remember(repo) { repo.settings.map { it.defaultRouteVisibilityPublic } }
        .collectAsState(initial = true)
}
