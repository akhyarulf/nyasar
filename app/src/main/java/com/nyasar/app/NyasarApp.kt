package com.nyasar.app

import android.app.Application
import org.maplibre.android.MapLibre

class NyasarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initializes MapLibre's global state once. No API key needed here —
        // provider credentials (e.g. MapTiler) live in the style URL, built
        // by TileProvider implementations, not in the SDK init call.
        MapLibre.getInstance(this)
    }
}
