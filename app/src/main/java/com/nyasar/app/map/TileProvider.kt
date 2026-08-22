package com.nyasar.app.map

/**
 * Abstraction over "where map tiles/styles come from".
 *
 * The navigation engine, off-route detection, GPX parsing, and storage
 * layers NEVER reference MapTiler (or any provider) directly. They only
 * ever see a lat/lon track and a GPS position. The map layer is the only
 * place a provider name appears, and it is reached exclusively through
 * this interface + [TileProviderFactory].
 *
 * To add a new provider (OpenFreeMap, MapTiler, a future self-hosted
 * tileserver, etc.) implement this interface and register it in
 * [TileProviderFactory]. Nothing else in the app needs to change.
 */
interface TileProvider {

    /** Stable id used for persistence (settings, offline region metadata). */
    val id: String

    /** Human readable name shown in Settings. */
    val displayName: String

    /**
     * Returns a MapLibre-compatible style URL (or inline style JSON string
     * prefixed with the style: scheme handled by [resolveStyleUri]) for the
     * given style variant (e.g. "outdoor", "satellite"). Implementations
     * that require an API key read it from BuildConfig / local.properties,
     * never hardcoded.
     */
    fun styleUrl(variant: StyleVariant = StyleVariant.OUTDOOR): String

    /** Whether this provider currently has the credentials/config needed to work. */
    fun isConfigured(): Boolean

    /**
     * Whether this provider's style/tiles can be pre-downloaded for a
     * bounding box via MapLibre's OfflineManager. All providers we ship
     * with support this since MapLibre's offline system works off any
     * vector/raster style URL — it's provider-agnostic by construction.
     */
    val supportsOfflineDownload: Boolean get() = true
}

enum class StyleVariant {
    OUTDOOR,
    SATELLITE,
    TOPO
}
