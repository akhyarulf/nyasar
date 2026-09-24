import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Release signing — every value comes from environment variables so the same
// config serves local builds (`export KEYSTORE_FILE=...` etc.) and CI (GitHub
// Actions secrets). Nothing signing-related is hardcoded or committed. When
// the keystore file is absent (clone-and-build without signing material),
// the config below is simply not attached and release builds fall back to
// unsigned output instead of failing at configuration time.
val releaseKeystoreFile = rootProject.file(
    System.getenv("KEYSTORE_FILE") ?: "keystore/nyasar-release.keystore"
)

android {
    namespace = "com.nyasar.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nyasar.app"
        minSdk = 26
        targetSdk = 34
        // Versi dari CI, bukan hardcoded: workflow release-apk.yaml
        // mengoper VERSION_NAME dari tag (v1.2.3 → "1.2.3") dan
        // VERSION_CODE dari jumlah commit (monoton naik, penting kalau
        // nanti migrasi ke Play). Build lokal tanpa env → fallback ke
        // nilai di bawah, jadi `./gradlew assembleDebug` tetap jalan.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeUnless { it.isBlank() } ?: "0.1.0-p0"

        // MapTiler API key is injected via local.properties -> BuildConfig,
        // never hardcoded and never committed.
        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localProps.load(localPropsFile.inputStream())
        }
        buildConfigField(
            "String",
            "MAPTILER_API_KEY",
            "\"${localProps.getProperty("MAPTILER_API_KEY", "")}\""
        )
        // Supabase (publishable key only — the secret key must never reach
        // the client). Same local.properties -> BuildConfig pattern as the
        // MapTiler key above: empty default keeps builds green in
        // environments where the values are not configured yet.
        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${localProps.getProperty("SUPABASE_URL", "")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${localProps.getProperty("SUPABASE_PUBLISHABLE_KEY", "")}\""
        )
        // Google Sign-In (Login Google, Phase 1): the WEB client id from
        // Google Cloud Console (the one registered in the Supabase Dashboard
        // under Auth -> Providers -> Google). Empty default keeps the build
        // green before the user completes the manual GCP/Supabase setup;
        // the UI hides the Google button when it is blank (same graceful
        // pattern as the Supabase credentials above). Never hardcoded.
        buildConfigField(
            "String",
            "GOOGLE_OAUTH_WEB_CLIENT_ID",
            "\"${localProps.getProperty("GOOGLE_OAUTH_WEB_CLIENT_ID", "")}\""
        )

        // Locale filter (ringankan APK, tetap satu universal): app hanya
        // menyediakan strings default (Indonesia) + values-en. Tanpa filter,
        // semua library (appcompat/material3/play-services) ikut menyetir
        // teks terjemahan 80+ bahasa — ratusan KB terbuang. "in" adalah
        // qualifier resmi Android untuk Indonesia (folder values-in).
        resourceConfigurations.addAll(listOf("in", "en"))
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
        // COMMITTED debug keystore (konsep "debug key tanpa ribet",
        // 2026-09): satu key yang sama untuk debug build lokal & CI,
        // supaya SHA-256 debug di web/.well-known/assetlinks.json
        // (App Links autoVerify) cocok selamanya. Debug key bukan
        // rahasia — hanya menandatangani APK debug. Kalau file hilang
        // (clone partial), Gradle fallback ke debug default-nya sendiri.
        create("sharedDebug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Buang resource yang tidak direferensikan kode/manifest.
            // material-icons-extended menyertakan ribuan ikon vektor dari
            // semua set; hanya yang benar-benar dipakai Compose yang lolos.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with the release keystore only when it is actually present
            // (CI after secrets are configured, or a local machine that has
            // the file). Otherwise keep Gradle's default unsigned output so
            // anyone can still build release without the signing material.
            if (releaseKeystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            if (rootProject.file("keystore/debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    // Official Android 12+ SplashScreen API back-ported to API 23+
    // (Theme.SplashScreen / installSplashScreen) — powers the launch
    // splash screen. 1.0.1 (stable): the 1.2.x line requires
    // compileSdk 35, this project compiles against 34.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Extended icon set — core only ships a small curated subset (Add, Settings,
    // ArrowBack, etc.), Icons like CloudDownload used in the offline-map screen
    // live here.
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // MapLibre GL Native SDK for Android — the map engine.
    // Provider (MapTiler / OpenFreeMap / other) is injected as a style URL,
    // MapLibre itself has no vendor lock-in.
    implementation("org.maplibre.gl:android-sdk:12.0.1")

    // Explicit OkHttp: MapLibre 12.0.1 publishes okhttp 4.12.0 only on its
    // runtimeElements (not apiElements), so app code cannot reference
    // okhttp3.* to build the custom Call.Factory passed to
    // HttpRequestUtil.setOkHttpClient (custom User-Agent for tile
    // requests, OSMF policy) without declaring it. Version pinned to the
    // exact one MapLibre runs with — no version skew.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room — local storage for imported GPX/routes/waypoints/settings
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore for lightweight settings (off-route thresholds, active provider, etc.)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Supabase Kotlin SDK — auth (email/password) + Postgres access for the
    // account feature. 2.2.2 is the newest line built against Kotlin
    // 1.9.x (verified from its Maven POM: kotlin-stdlib 1.9.22, ktor
    // 2.3.9); 2.3+ requires Kotlin 2.0 and would force a compiler bump.
    // The BOM keeps gotrue/postgrest/core mutually version-locked. The
    // serialization runtime + plugin are required by the SDK's JSON layer.
    // Credentials come from local.properties via BuildConfig (same pattern
    // as MAPTILER_API_KEY); when empty, SupabaseClientProvider reports an
    // unconfigured state instead of crashing and every account feature
    // degrades gracefully (see ui/auth/AuthViewModel).
    implementation(platform("io.github.jan-tennert.supabase:bom:2.2.2"))
    implementation("io.github.jan-tennert.supabase:supabase-kt")
    implementation("io.github.jan-tennert.supabase:gotrue-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    // Storage (Fase 2 Publish): gzip'ed full-fidelity GPX goes to the
    // public route-gpx bucket — same BOM-locked line as the rest of the SDK.
    implementation("io.github.jan-tennert.supabase:storage-kt")
    // Realtime (auto-refresh 2026-09): websocket push on routes/likes/saves
    // changes — the Browse/Saved/Detail lists update WITHOUT pull-to-refresh
    // or an app restart. BOM-locked, same 2.2.2 line.
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    // Foreground-refresh hook: ON_START/ON_STOP events for the
    // "app came back from background → silent re-sync" trigger.
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("io.ktor:ktor-client-android:2.3.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Google Sign-In (Login Google): androidx Credential Manager requests a
    // Google ID token (GetGoogleIdOption from the googleid artifact), which
    // gotrue-kt exchanges via signInWith(IDToken). credentials-play-services-
    // auth bridges the request to Google Play services on devices that have
    // it. All three versions verified from their google() maven POMs as the
    // newest stable, Kotlin-1.9-compatible lines. No google-services plugin
    // change — the JSON already present (or absent) is untouched.
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Navigation between Compose screens
    implementation("androidx.navigation:navigation-compose:2.7.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // Firebase Crashlytics — automatic crash reporting. Analytics is a
    // prerequisite of Crashlytics (per Firebase docs). All versions come
    // from the BoM so the artifacts stay mutually compatible.
    // BoM 33.1.2 is the newest line that still supports compileSdk 34
    // (this project's target); newer BoMs require compileSdk 35.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
}

// Both Firebase build plugins apply together, in documented order
// (google-services reads app/google-services.json first, then the
// Crashlytics plugin wires up mapping-file uploads). Conditional so CI —
// which doesn't have the file — still builds; the full Firebase setup
// activates the moment the file exists in app/. The file is intentionally
// NOT gitignored: it only contains the app id + API key already compiled
// into every APK (Google's own guidance: not a secret for Android apps).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}
