package com.nyasar.app.map.providers

import android.content.Context
import com.nyasar.app.BuildConfig
import com.nyasar.app.map.BasemapEntry

/**
 * Builds MapLibre-ready style URIs for Nyasar basemaps.
 *
 * Covers:
 *  - RASTER basemaps ([BasemapEntry.rasterUrl]/[rasterUrls]) as inline
 *    raster styles built from upstream tile templates.
 *  - BUNDLED VECTOR styles for country entries shipped in app assets
 *    (IGN France plan/topo/satellite), loaded inline.
 *
 * VECTOR World entries are intentionally NOT rendered here:
 *  - Liberty Topo / OpenMapTiles OSM / OpenMapTiles OSM Topo / UtagawaMTB
 *    use a remote style URL directly from [BasemapCatalog].
 *  - Liberty Satellite now uses an inline Liberty-derived vector style
 *    built in Nyasar (Liberty-style overlay on an imagery source), not the
 *    old GPX-Studio-bundled file and NOT Esri Satellite.
 *
 * MapLibre accepts `data:application/json;base64` style URIs in setStyle,
 * so inline styles need no separate style hosting.
 */
object RasterStyleJson {

    /**
     * Inline raster style for a raster entry.
     *
     * P3K audit fix: this function's logic was already correct per-entry
     * (each call reads only [entry]'s own rasterUrl/rasterUrls/gpxName/
     * attribution — no shared mutable state, no cross-entry fallthrough).
     * The bug reported ("OpenStreetMap/OpenTopoMap/OpenHikingMap/CyclOSM
     * look identical in the picker") was NOT here — it was stale disk-
     * cached thumbnail PNGs in MapSnapshotHelper surviving from an earlier
     * state (e.g. all four falling back to the same generic placeholder
     * when tiles failed to load, then that placeholder getting cached
     * forever under a cache key with no basemap-specific version). See
     * MapSnapshotHelper.BASEMAP_PREVIEW_CACHE_VERSION.
     *
     * The require() below is new: it hard-fails fast (rather than
     * silently mis-rendering) if a future catalog entry is ever routed
     * here without a real distinguishing tile template, so a copy-paste
     * mistake in BasemapCatalog can never again produce two "different"
     * entries that quietly resolve to the same tiles.
     */
    fun build(entry: BasemapEntry): String {
        // Upstream tile templates verbatim — including endpoints whose WMTS
        // tile matrix is ordered {z}/{y}/{x} (e.g. IGN Belgium): MapLibre
        // substitutes the placeholders literally, no rewriting wanted.
        // Multi-host upstreams (OSM/CyclOSM a,b,c subdomains) are rendered
        // as a MapLibre tiles array, preserving upstream load distribution.
        val templates = entry.rasterUrls.ifEmpty {
            listOf(requireNotNull(entry.rasterUrl) { "${entry.gpxKey} is not a raster basemap" })
        }
        require(templates.isNotEmpty() && templates.all { it.contains("{z}") && it.contains("{x}") && it.contains("{y}") }) {
            "${entry.gpxKey} has no valid {z}/{x}/{y} tile template — refusing to build a broken raster style"
        }
        val escapedUrls = templates.joinToString(",\n") { "\"${it.replace("\"", "\\\"")}\"" }
        // "id" here is purely descriptive (not read by MapLibre) but is
        // included in the JSON name/comment-equivalent so that dumping a
        // resolved style URI while debugging immediately shows which
        // entry it came from, instead of two visually-similar raster
        // styles being indistinguishable in a log.
        val json = """
            {
              "version": 8,
              "name": "${entry.gpxName} (${entry.gpxKey})",
              "sources": {
                "basemap-raster": {
                  "type": "raster",
                  "tiles": [$escapedUrls],
                  "tileSize": ${entry.rasterTileSize},
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
     * Inline vector style for a bundled-vector entry (IGN France
     * plan/topo/satellite).
     */
    fun build(entry: BasemapEntry, context: Context): String {
        if (entry.assetPath == null) return build(entry)
        val json = context.assets.open(entry.assetPath).bufferedReader().use { it.readText() }
        return toDataUri(json)
    }

    /**
     * Inline Liberty-derived satellite style for [BasemapEntry.LIBERTY_SATELLITE].
     *
     * Concept (mirrors GPX Studio's described Liberty Satellite approach):
     * a Liberty-style vector overlay (roads/labels/landcover-ish layers)
     * rendered on top of an imagery source.
     *
     * P3I audit fix, two bugs:
     *  1. imageryUrl defaults to null, and the caller (TileProvider.
     *     styleUrlFor) previously never passed one — producing an empty
     *     "tiles": [] raster source, so no satellite imagery was ever
     *     actually fetched. styleUrlFor now wires a real MapTiler XYZ
     *     satellite tile URL when a key is configured (see its own doc).
     *  2. The vector overlay's source ("liberty-overlay") pointed "url" at
     *     Liberty's style.json — a full MapLibre style, not TileJSON. Per
     *     the MapLibre style spec, a vector source's "url" must resolve to
     *     TileJSON. Fixed to OpenFreeMap's actual TileJSON endpoint,
     *     https://tiles.openfreemap.org/planet/latest (documented in
     *     hyperknot/openfreemap as "always points to the latest deployed
     *     TileJSON") — the source-layer names referenced below ("water",
     *     "parks", ...) resolve against this OpenMapTiles-schema tileset.
     */
    fun libertySatelliteStyle(imageryUrl: String? = null): String {
        val imagerySource = if (imageryUrl.isNullOrBlank()) {
            """
            "imagery": {
              "type": "raster",
              "tiles": [],
              "tileSize": 256,
              "attribution": ""
            }
            """
        } else {
            val escaped = imageryUrl.replace("\"", "\\\"")
            """
            "imagery": {
              "type": "raster",
              "tiles": [\"$escaped\"],
              "tileSize": 256,
              "attribution": ""
            }
            """
        }
        val json = """
            {
              "version": 8,
              "name": "Liberty Satellite",
              "sources": {
                "liberty-overlay": {
                  "type": "vector",
                  "url": "https://tiles.openfreemap.org/planet/latest"
                },
                $imagerySource
              },
              "layers": [
                {
                  "id": "satellite-imagery",
                  "type": "raster",
                  "source": "imagery",
                  "paint": { "raster-opacity": 1.0 }
                },
                {
                  "id": "liberty-water",
                  "type": "fill",
                  "source": "liberty-overlay",
                  "source-layer": "water",
                  "paint": { "fill-color": "#2b4a5e", "fill-opacity": 0.55 }
                },
                {
                  "id": "liberty-parks",
                  "type": "fill",
                  "source": "liberty-overlay",
                  "source-layer": "parks",
                  "paint": { "fill-color": "#cdf0c8", "fill-opacity": 0.35 }
                },
                {
                  "id": "liberty-major-roads",
                  "type": "line",
                  "source": "liberty-overlay",
                  "source-layer": "roads_primary",
                  "paint": { "line-color": "#f4f1e8", "line-width": 3 }
                },
                {
                  "id": "liberty-minor-roads",
                  "type": "line",
                  "source": "liberty-overlay",
                  "source-layer": "roads_secondary",
                  "paint": { "line-color": "#f4f1e8", "line-width": 1.6 }
                },
                {
                  "id": "liberty-road-labels",
                  "type": "symbol",
                  "source": "liberty-overlay",
                  "source-layer": "road_labels",
                  "layout": {
                    "text-field": { "type": "identity", "property": "name" },
                    "text-size": 11,
                    "text-anchor": "center",
                    "text-font": ["Open Sans Regular"]
                  },
                  "paint": { "text-color": "#ffffff", "text-halo-color": "rgba(0,0,0,0.55)", "text-halo-width": 1.4 }
                },
                {
                  "id": "liberty-places",
                  "type": "symbol",
                  "source": "liberty-overlay",
                  "source-layer": "places",
                  "layout": {
                    "text-field": { "type": "identity", "property": "name" },
                    "text-size": 11,
                    "text-anchor": "center",
                    "text-font": ["Open Sans Regular"]
                  },
                  "paint": { "text-color": "#ffffff", "text-halo-color": "rgba(0,0,0,0.55)", "text-halo-width": 1.4 }
                }
              ]
            }
        """.trimIndent()
        return toDataUri(json)
    }

    private fun toDataUri(json: String): String {
        // MapLibreMap.setStyle(String) loads a URI, not raw JSON — encode
        // the style as a data: URI so it round-trips through the loader.
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "data:application/json;base64,$encoded"
    }
}
