pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MapLibre native SDK releases
        maven { url = uri("https://repo.maplibre.org/releases") }
    }
}

rootProject.name = "Nyasar"
include(":app")
// Modul ukur performa (Macrobenchmark). Bukan bagian APK: hanya APK test
// yang dijalankan di perangkat lewat :benchmark:connectedBenchmarkAndroidTest.
include(":benchmark")
