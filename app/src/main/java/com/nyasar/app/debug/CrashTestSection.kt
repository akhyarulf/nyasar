package com.nyasar.app.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nyasar.app.BuildConfig

/**
 * Crashlytics verification section — reachable ONLY in debug builds:
 * the sole call site (SettingsScreen) is guarded by BuildConfig.DEBUG,
 * and in release builds (minify on) R8 strips both the guarded call site
 * and this dead code, so no crash-test path exists in shipped APKs.
 *
 * "Force crash" throws an uncaught RuntimeException on the UI thread,
 * which Crashlytics picks up exactly like a real crash — Firebase's own
 * recommended way to verify the integration (Test your Crashlytics
 * implementation, developer.android.com / firebase.google.com docs).
 *
 * The version label exists so the person testing can confirm on-screen
 * that the installed APK really contains this test code — the #1 cause of
 * "no report shows up" is testing an old install.
 */
@Composable
fun CrashTestSection() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "Firebase Crashlytics test (debug-only)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            "Tap to throw an uncaught test exception. The app dies; the report appears in Firebase Console > Crashlytics within a few minutes (app must be reopened once after the crash so it can upload).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(Modifier.padding(top = 8.dp)) {
            Button(
                onClick = { throw RuntimeException("Test Crash — Crashlytics verification (Nova)") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                )
            ) {
                Text("Force crash")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                // Non-fatal: visible in Crashlytics under "Non-fatals" —
                // lets you verify wiring without killing the process. If this
                // APK was built without google-services.json (e.g. the CI
                // APK), fail loudly with the reason instead of an opaque
                // IllegalStateException from deep inside Firebase.
                if (com.google.firebase.FirebaseApp.getApps(LocalContext.current).isEmpty()) {
                    throw IllegalStateException(
                        "Firebase not initialized in this APK — app/google-services.json was missing at build time"
                    )
                }
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                    .recordException(RuntimeException("Test non-fatal — Crashlytics verification"))
            }) {
                Text("Log non-fatal")
            }
        }
        Text(
            "App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
