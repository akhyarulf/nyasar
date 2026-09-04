package com.nyasar.app.map.providers

import com.nyasar.app.BuildConfig
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.map.TileProvider

/**
 * MapTiler-backed provider. Default/primary provider for launch because it
 * has a solid outdoor/topo style, a generous free tier, and legal offline
 * caching terms suitable for an outdoor navigation app.
 *
 * This class is the ONLY place a MapTiler URL is constructed. If MapTiler
 * ever needs to be dropped, deleting this file + its factory entry is
 * enough — nothing in map/, navigation/, gpx/, or data/ references it.
 */
class MapTilerProvider(
    private val apiKey: String = BuildConfig.MAPTILER_API_KEY
) : TileProvider {

    override val id: String = "maptiler"
    override val displayName: String = "MapTiler"

    override fun isConfigured(): Boolean = apiKey.isNotBlank()

    override fun styleUrl(variant: StyleVariant): String {
        val styleId = when (variant) {
            StyleVariant.OUTDOOR -> "outdoor-v2"
            StyleVariant.SATELLITE -> "satellite"
            StyleVariant.TOPO -> "topo-v2"
        }
        return "https://api.maptiler.com/maps/$styleId/style.json?key=$apiKey"
    }

    override fun styleUrlFor(entry: BasemapEntry): String {
        // Keyless catalog entries bypass the key-locked provider entirely
        // so the full GPX Studio-style list works even without a MapTiler
        // key. Only the legacy 3-variant mapping uses MapTiler styles.
        return entry.styleUrl ?: RasterStyleJson.build(entry)
    }
}
