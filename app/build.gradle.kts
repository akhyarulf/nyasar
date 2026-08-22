import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

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

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("org.maplibre.gl:android-sdk:11.5.2")

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
}
