package com.nyasar.app.map.providers

import android.content.Context
import com.nyasar.app.map.BasemapEntry
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.map.TileProvider

/**
 * OpenFreeMap-backed provider. No API key required. Kept as a second,
 * fully working implementation from day one specifically to prove the
 * [TileProvider] abstraction is real — switching the active provider in
 * Settings should never require touching navigation/off-route code.
 *
 * OpenFreeMap does not currently publish a dedicated outdoor/topo style,
 * so all variants resolve to its general "liberty" style for now.
 */
class OpenFreeMapProvider : TileProvider {

    override val id: String = "openfreemap"
    override val displayName: String = "OpenFreeMap"

    override fun isConfigured(): Boolean = true // no key needed

    override fun styleUrl(variant: StyleVariant): String {
        return "https://tiles.openfreemap.org/styles/liberty"
    }

    override fun styleUrlFor(entry: BasemapEntry, context: Context?): String {
        return when {
            entry.styleUrl != null -> entry.styleUrl
            entry.assetPath != null && context != null -> RasterStyleJson.build(entry, context)
            else -> RasterStyleJson.build(entry)
        }
    }
}
