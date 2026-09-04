package com.nyasar.app.map

/**
 * GPX Studio-style basemap catalog (feature request: "basemap banyak kayak
 * gpx studio"). Each entry is a self-contained style/tile URL that requires
 * NO API key — every source below has a free, keyless endpoint, so the
 * whole catalog works out of the box like GPX Studio's default list.
 *
 * Two kinds of entries:
 *  - VECTOR: MapLibre style JSON URLs (loaded via map.setStyle) — full
 *    styling control, offline-downloadable, overlay layers (track lines,
 *    waypoints) draw on top automatically.
 *  - RASTER: XYZ raster tile templates (loaded via map.setStyle with an
 *    inline style JSON) — classic topo/satellite sources like OpenTopoMap.
 *
 * [NyasarMapView] resolves a [BasemapEntry] through
 * [TileProvider.styleUrlFor] — nothing else in the app needs to know
 * whether a basemap is raster or vector.
 */
enum class BasemapEntry(
    val id: String,
    val label: String,
    /** MapLibre style JSON URL, or null when this is a raster basemap. */
    val styleUrl: String?,
    /** XYZ raster tile template with {z}/{x}/{y}, or null when vector. */
    val rasterUrl: String?,
    /** Max zoom supported by raster sources (clamped in the inline style). */
    val maxZoom: Int,
    val attribution: String
) {
    // ---- Free vector styles (keyless, offline-downloadable) ----
    LIBERTY_TOPO(
        "liberty", "Liberty Topo",
        styleUrl = "https://tiles.openfreemap.org/styles/liberty",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenFreeMap / OpenMapTiles / OpenStreetMap"
    ),
    POSITRON(
        "positron", "Positron Light",
        styleUrl = "https://tiles.openfreemap.org/styles/positron",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenFreeMap / OpenMapTiles / OpenStreetMap"
    ),
    BRIGHT(
        "bright", "OSM Bright",
        styleUrl = "https://tiles.openfreemap.org/styles/bright",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenFreeMap / OpenMapTiles / OpenStreetMap"
    ),
    FIORD(
        "fiord", "Fiord Dark",
        styleUrl = "https://tiles.openfreemap.org/styles/fiord",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenFreeMap / OpenMapTiles / OpenStreetMap"
    ),
    OSM_TOPO(
        "opentoquitus", "OpenTopoMap (vector)",
        styleUrl = "https://tiles.openquitous.org/styles/topo.json",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenQuitus / OpenStreetMap"
    ),
    OPEN_HIKING(
        "openhikingmap", "OpenHikingMap",
        styleUrl = "https://hiking.waymarkedtiles.org/styles/hiking/style.json",
        rasterUrl = null, maxZoom = 14,
        attribution = "Waymarked Trails / OpenStreetMap"
    ),

    // ---- Classic raster XYZ sources (keyless) ----
    OPEN_TOPO_RASTER(
        "opentopomap", "OpenTopoMap",
        styleUrl = null,
        rasterUrl = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        maxZoom = 17,
        attribution = "(C) OpenTopoMap (CC-BY-SA) / OpenStreetMap"
    ),
    OSM_STANDARD(
        "osm", "OpenStreetMap",
        styleUrl = null,
        rasterUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "(C) OpenStreetMap contributors"
    ),
    CYCLOSM(
        "cyclosm", "CyclOSM",
        styleUrl = null,
        rasterUrl = "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "CyclOSM / OpenStreetMap"
    ),
    ESRI_SATELLITE(
        "esri-satellite", "Esri Satellite",
        styleUrl = null,
        rasterUrl = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        maxZoom = 19,
        attribution = "Esri, Maxar, Earthstar Geographics"
    ),
    CARTO_DARK(
        "carto-dark", "Carto Dark",
        styleUrl = null,
        rasterUrl = "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "(C) CARTO / OpenStreetMap"
    );

    val isRaster: Boolean get() = rasterUrl != null

    companion object {
        /** Parse a persisted id back to an entry, falling back to Liberty. */
        fun fromId(id: String?): BasemapEntry =
            entries.firstOrNull { it.id == id } ?: LIBERTY_TOPO
    }
}
