package com.nyasar.app.map.providers

import com.nyasar.app.map.StyleVariant
import com.nyasar.app.map.TileProvider

/**
 * OpenFreeMap-backed provider. No API key required. Kept as a second,
 * fully working implementation from day one specifically to prove the
 * [TileProvider] abstraction is real — switching the active provider in
 * Settings should never require touching navigation/off-route code.
 *
 * OpenFreeMap does not publish a dedicated outdoor/topo style, but it does
 * publish an official "Dark" style (dark navy base + green land/contour
 * tones) — see https://openfreemap.org/quick_start/ and the
 * hyperknot/openfreemap-styles repo. Mapped to TOPO here since it's the
 * closest visual match this provider has to a Strava-like dark map (user
 * request: "warna dari peta gw disamain kayak strava"), without needing
 * any API key or a hosted custom style.
 */
class OpenFreeMapProvider : TileProvider {

    override val id: String = "openfreemap"
    override val displayName: String = "OpenFreeMap"

    override fun isConfigured(): Boolean = true // no key needed

    override fun styleUrl(variant: StyleVariant): String {
        return when (variant) {
            StyleVariant.TOPO -> "https://tiles.openfreemap.org/styles/dark"
            StyleVariant.OUTDOOR -> "https://tiles.openfreemap.org/styles/liberty"
            StyleVariant.SATELLITE -> "https://tiles.openfreemap.org/styles/liberty" // OpenFreeMap has no satellite imagery; falls back to Liberty rather than an empty/broken map.
        }
    }
}
