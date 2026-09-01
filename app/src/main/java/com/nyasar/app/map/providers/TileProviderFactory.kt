package com.nyasar.app.map.providers

import com.nyasar.app.map.TileProvider

/**
 * Single place that knows about every [TileProvider] implementation that
 * exists in the app. Everything else (map screens, offline download UI,
 * settings) asks this factory for "the current provider" or "all
 * providers" — it never instantiates MapTilerProvider/OpenFreeMapProvider
 * directly. This is what makes swapping/adding a provider a one-file change.
 */
object TileProviderFactory {

    private val providers: List<TileProvider> by lazy {
        listOf(
            MapTilerProvider(),
            OpenFreeMapProvider()
        )
    }

    fun all(): List<TileProvider> = providers

    fun byId(id: String): TileProvider =
        providers.firstOrNull { it.id == id && it.isConfigured() }
            ?: fallback()

    /** Default provider — OpenFreeMap (dark style, matches the look the
     *  user asked for: "warna dari peta gw disamain kayak strava"). No API
     *  key needed either, which is a bonus, not the reason for the switch.
     *  Falls back to MapTiler, then to any configured provider, same
     *  safety net the previous MapTiler-first default had. */
    fun default(): TileProvider =
        providers.firstOrNull { it.id == "openfreemap" && it.isConfigured() }
            ?: providers.firstOrNull { it.id == "maptiler" && it.isConfigured() }
            ?: fallback()

    /** If the preferred provider isn't configured (e.g. no API key set), degrade gracefully. */
    private fun fallback(): TileProvider =
        providers.first { it.isConfigured() }
}
