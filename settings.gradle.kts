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
