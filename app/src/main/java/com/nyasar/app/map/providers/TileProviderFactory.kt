package com.nyasar.app.map.providers

import com.nyasar.app.map.TileProvider

/**
 * Single place that knows about every [TileProvider] implementation that
 * exists in the app. Everything else (map screens, offline download UI,
 * settings) asks this factory for "the current provider" or "all
 * providers" — it never instantiates a provider directly. This is what
 * makes swapping/adding a provider a one-file change.
 *
 * MapTiler was removed (user request: OpenFreeMap only, no paid/API-key
 * dependency going forward). OpenFreeMap is now the only provider — the
 * TileProvider abstraction is kept as-is rather than collapsed away, so
 * adding a provider back later (or a new one) still only touches this
 * file + the new provider class, same guarantee as before.
 */
object TileProviderFactory {

    private val providers: List<TileProvider> by lazy {
        listOf(
            OpenFreeMapProvider()
        )
    }

    fun all(): List<TileProvider> = providers

    fun byId(id: String): TileProvider =
        providers.firstOrNull { it.id == id && it.isConfigured() }
            ?: fallback()

    /** Only provider currently registered — OpenFreeMap, no API key needed. */
    fun default(): TileProvider =
        providers.firstOrNull { it.id == "openfreemap" && it.isConfigured() } ?: fallback()

    /** If the preferred provider isn't configured (e.g. no API key set), degrade gracefully. */
    private fun fallback(): TileProvider =
        providers.first { it.isConfigured() }
}
