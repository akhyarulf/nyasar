package com.nyasar.app

import android.app.Application
import org.maplibre.android.MapLibre

class NyasarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initializes MapLibre's global state once. No API key needed here
        // — OpenFreeMap, the only provider, requires none; any future
        // provider's credentials would live in its style URL, built by
        // TileProvider implementations, not in this SDK init call.
        MapLibre.getInstance(this)
    }
}
