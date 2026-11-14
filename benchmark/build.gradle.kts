// Modul pengukuran performa (Macrobenchmark). Tidak ikut di APK yang
// dikirim ke pengguna: modul ini menghasilkan APK test sendiri dan hanya
// berjalan di perangkat/emulator lewat `connectedBenchmarkAndroidTest`.
//
// Kenapa ada: semua perbaikan performa di Explore, History, dan layar rekam
// sebelumnya hanya berbekal alasan teknis — belum pernah ada angka frame
// time. Tanpa modul ini, "lebih lancar" tetap opini.
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nyasar.app.benchmark"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Target = aplikasinya sendiri (bukan modul terpisah): benchmark harus
    // mengukur build yang benar-benar dipakai, dan `self-instrumenting`
    // membuat APK benchmark berisi runner-nya sendiri sehingga tidak perlu
    // modul test tambahan.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Satu-satunya varian yang masuk akal diukur: non-debuggable, sama
        // seperti build release yang dirilis (debug build punya overhead
        // JIT/verifier yang tidak ada di tangan pengguna).
        create("benchmark") {
            // APK TEST boleh debuggable (ia hanya menjalankan test); yang
            // harus non-debuggable adalah APLIKASI yang diukur — itu diatur
            // oleh build type `benchmark` di :app.
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.0")
}
