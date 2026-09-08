package com.nyasar.app.map

import android.content.Context

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

    /**
 * Resolve a specific entry from the Nyasar basemap catalog
 * ([BasemapEntry]) to a MapLibre-ready style URI:
 *  - vector entry with a hosted style URL -> that URL
 *  - Liberty Satellite -> Nyasar-built inline Liberty-derived vector style
 *    on top of an imagery source (see [RasterStyleJson.libertySatelliteStyle])
 *  - raster entry -> generated inline raster style ([RasterStyleJson.build])
 *  - bundled vector entry (IGN France) -> inline data-URI style
 *    ([RasterStyleJson.build])
 *
 * [context] is only needed for asset-backed entries; passing null for
 * those falls back to a plain raster build.
 */
fun styleUrlFor(entry: BasemapEntry, context: Context? = null): String = when {
    entry == BasemapEntry.LIBERTY_SATELLITE -> {
        // FIX: was calling libertySatelliteStyle() with no imageryUrl,
        // producing an empty "tiles": [] raster source — no satellite
        // imagery rendered at all, only the vector overlay on a blank
        // layer. Now wires real MapTiler satellite tiles when a key is
        // configured, and gracefully keeps the old (blank-imagery, vector-
        // only) behavior when it isn't, rather than crashing or fetching
        // an unauthenticated/broken URL.
        val maptiler = com.nyasar.app.map.providers.MapTilerProvider()
        val imageryUrl = if (maptiler.isConfigured()) maptiler.satelliteRasterTileUrl() else null
        com.nyasar.app.map.providers.RasterStyleJson.libertySatelliteStyle(imageryUrl)
    }
    entry.styleUrl != null -> entry.styleUrl
    entry.assetPath != null && context != null ->
        com.nyasar.app.map.providers.RasterStyleJson.build(entry, context)
    entry.assetPath != null ->
        com.nyasar.app.map.providers.RasterStyleJson.build(entry)
    else -> styleUrl(StyleVariant.OUTDOOR)
}

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
