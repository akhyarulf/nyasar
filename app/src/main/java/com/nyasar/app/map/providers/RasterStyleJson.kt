package com.nyasar.app.map.providers

import com.nyasar.app.map.BasemapEntry

/**
 * Builds an inline MapLibre style JSON for raster XYZ basemaps
 * ([BasemapEntry.isRaster]). MapLibre accepts `style:` data-URI style JSON
 * directly in setStyle, so raster sources need no separate style hosting —
 * same trick GPX Studio uses for its classic raster basemaps.
 */
object RasterStyleJson {

    fun build(entry: BasemapEntry): String {
        val url = requireNotNull(entry.rasterUrl) { "${entry.id} is a vector basemap" }
        val escapedUrl = url.replace("\"", "\\\"")
        val json = """
            {
              "version": 8,
              "name": "${entry.label}",
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
        // MapLibreMap.setStyle(String) loads a URL, not raw JSON — encode
        // the style as a data: URI so it round-trips through the loader.
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "data:application/json;base64,$encoded"
    }
}
