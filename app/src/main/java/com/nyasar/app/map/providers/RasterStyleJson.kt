package com.nyasar.app.map.providers

import android.content.Context
import com.nyasar.app.map.BasemapEntry

/**
 * Builds a MapLibre-ready style URI for raster XYZ basemaps
 * ([BasemapEntry.isRaster]) and for bundled vector styles
 * ([BasemapEntry.assetPath] — GPX Studio's own adapted IGN France styles
 * shipped in app/src/main/assets/styles/gpxstudio/).
 *
 * MapLibre accepts `data:application/json;base64` style URIs directly in
 * setStyle, so raster sources and bundled vector styles need no separate
 * style hosting — the same trick GPX Studio uses for its classic raster
 * basemaps and IGN styles.
 */
object RasterStyleJson {

    /** Inline raster style for a raster entry. */
    fun build(entry: BasemapEntry): String {
        val url = requireNotNull(entry.rasterUrl) { "${entry.gpxKey} is not a raster basemap" }
        // GPX Studio's exact tile templates are kept verbatim — including
        // endpoints whose WMTS tile matrix is ordered {z}/{y}/{x}
        // (e.g. IGN Belgium): MapLibre substitutes the placeholders
        // literally, so no rewriting is needed or wanted here.
        val escapedUrl = url.replace("\"", "\\\"")
        val json = """
            {
              "version": 8,
              "name": "${entry.gpxName}",
              "sources": {
                "basemap-raster": {
                  "type": "raster",
                  "tiles": ["$escapedUrl"],
                  "tileSize": 256,
                  "maxzoom": ${entry.maxZoom},
                  "attribution": "${entry.attribution.replace("\"", "'")}"
                }
              },
              "layers": [
                { "id": "basemap-raster-layer", "type": "raster", "source": "basemap-raster" }
              ]
            }
        """.trimIndent()
        return toDataUri(json)
    }

    /**
     * Inline vector style for an asset-backed entry (IGN France): the
     * bundled JSON is read from APK assets and wrapped in a data URI.
     */
    fun build(entry: BasemapEntry, context: Context): String {
        if (entry.assetPath == null) return build(entry)
        val json = context.assets.open(entry.assetPath).bufferedReader().use { it.readText() }
        return toDataUri(json)
    }

    private fun toDataUri(json: String): String {
        // MapLibreMap.setStyle(String) loads a URI, not raw JSON — encode
        // the style as a data: URI so it round-trips through the loader.
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "data:application/json;base64,$encoded"
    }
}
