import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 1
        versionName = "0.1.0-p0"

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
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Navigation between Compose screens
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // P3H: Activity Photos. Coil loads local files (java.io.File) directly
    // for thumbnails/fullscreen with built-in memory caching + downsampling
    // — spec §17/18 "jangan decode full-resolution semua foto sekaligus";
    // no other image-loading library exists in this project yet (audited).
    implementation("io.coil-kt:coil-compose:2.6.0")
    // EXIF read-only access (spec §14: timestamp/lat/lon if present) — never
    // used to write/modify, only androidx.exifinterface.media.ExifInterface's
    // getters are called anywhere in this codebase.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

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
