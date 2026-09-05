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
        // P3I audit fix: was called with no argument, so imageryUrl
        // defaulted to null and produced an empty "tiles": [] raster
        // source below — no satellite imagery was ever actually fetched,
        // despite this entry's attribution already claiming "© MapTiler".
        // MapTiler's raw XYZ raster tile endpoint (not their style.json,
        // which a raster source's "tiles" array cannot consume) is
        // https://api.maptiler.com/maps/{mapId}/{tileSize}/{z}/{x}/{y}.{format}
        // per MapTiler's own Tiles API docs. Falls back to the previous
        // no-imagery behavior only if no key is configured, rather than
        // hard-failing the whole entry.
        val apiKey = com.nyasar.app.BuildConfig.MAPTILER_API_KEY
        val imageryUrl = if (apiKey.isNotBlank()) {
            "https://api.maptiler.com/maps/satellite/256/{z}/{x}/{y}.jpg?key=$apiKey"
        } else null
        com.nyasar.app.map.providers.RasterStyleJson.libertySatelliteStyle(imageryUrl)
    }
    entry.styleUrl != null -> entry.styleUrl
    entry.assetPath != null && context != null ->
        com.nyasar.app.map.providers.RasterStyleJson.build(entry, context)
    entry.assetPath != null ->
        com.nyasar.app.map.providers.RasterStyleJson.build(entry)
    // P3I audit fix: explicit branch for entries whose only real source
    // IS the default provider's style (currently just OSM_TOPO — see its
    // requiresMapTilerKey doc). Previously this went through the same
    // catch-all `else` as OSM (now fixed above with its own OpenFreeMap
    // styleUrl), which is what made OSM and OSM_TOPO render identical
    // tiles despite being meant to look different. Named explicitly here
    // so it's a deliberate choice, not indistinguishable from an
    // unconfigured/placeholder entry falling through by accident.
    entry.requiresMapTilerKey -> styleUrl(StyleVariant.TOPO)
    // Defensive fallback only — no current catalog entry reaches this
    // (every entry above either has styleUrl, assetPath, or
    // requiresMapTilerKey set). Kept so a future entry added without one
    // of those degrades to *something* renderable instead of crashing,
    // rather than being relied upon as the normal path for any entry.
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
