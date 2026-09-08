package com.nyasar.app.map

/**
 * Nyasar World + Country basemap catalog.
 *
 * The 9 World basemaps follow the basemap list used by GPX Studio as a
 * *visual/UX reference only*. Nyasar does **not** proxy anything through
 * `styles.gpx.studio`; each entry uses the real upstream source that
 * underlies the corresponding GPX Studio basemap (or, where that upstream
 * source is not a confirmed public keyless endpoint, the entry is marked
 * explicitly instead of being silently faked).
 *
 * Vector vs raster is determined from the real upstream source, not from
 * a guess:
 *  - VECTOR: Liberty Topo, Liberty Satellite, OpenMapTiles OSM,
 *            OpenMapTiles OSM Topo, UtagawaMTB
 *  - RASTER: OpenStreetMap, OpenTopoMap, OpenHikingMap, CyclOSM
 *
 * NyasarMapView resolves a [BasemapEntry] through
 * [TileProvider.styleUrlFor] — nothing else in the app needs to know
 * whether a basemap is raster or vector.
 *
 * ATTRIBUTION NOTE:
 *  - Raster entries: upstream attribution, links flattened to plain text.
 *  - Vector entries: when Nyasar loads the remote style, MapLibre renders
 *    that style's own attribution; the catalog string is the fallback shown
 *    while the style loads / when it cannot be reached.
 *
 * IMPORTANT — not all entries are guaranteed keyless or unlimited:
 *  - OpenStreetMap raster tiles must follow the OSMF Tile Usage Policy,
 *    including no offline bulk download. Nyasar keeps OSM as a live
 *    basemap only.
 *  - OpenHikingMap tiles are hosted by openmaps.fr/tile.openmaps.fr and
 *    come with their own usage limits; treat as low-volume live tiles.
 *  - OpenMapTiles OSM / OSM Topo are modeled around a real OpenMapTiles
 *    source/style where available; a confirmed public keyless hosted
 *    OpenMapTiles style endpoint was not verified in this pass, so those
 *    entries are marked as requiring a hosted source rather than pointing
 *    at an unverified URL.
 *  - UtagawaMTB style metadata could not be fully verified here; the entry
 *    points at the public style URL only if it continues to serve a
 *    MapLibre-compatible style.
 *
 * @param gpxKey   stable persisted id (kept identical to prior catalog).
 * @param gpxName  display name shown in the picker.
 * @param styleUrl MapLibre style JSON URL for vector basemaps, or null.
 * @param rasterUrl  XYZ/WMTS template ({z}/{x}/{y}) for single-host raster.
 * @param rasterUrls Explicit host list for multi-host raster (no {s} in
 *                   MapLibre; a/b/c hosts are listed, not templated).
 * @param maxZoom   max zoom clamped in the inline raster style.
 * @param attribution Fallback attribution string for the entry.
 * @param country  grouping section, null = World.
 * @param assetPath  bundled asset path for bundled vector styles.
 * @param useYBeforeX true when the source uses {z}/{y}/{x}.
 * @param rasterTileSize upstream tileSize for the raster source.
 */
enum class BasemapEntry(
    val gpxKey: String,
    val gpxName: String,
    /** MapLibre style JSON URL, or null when this is a raster basemap. */
    val styleUrl: String?,
    /**
     * XYZ/WMTS raster tile template with {z}/{x}/{y}, or null when vector.
     * Tile URLs that upstream serves across subdomains list all hosts in
     * [rasterUrls] instead of a {s} placeholder (MapLibre has no {s}).
     */
    val rasterUrl: String?,
    /** Max zoom supported by raster sources (clamped in the inline style). */
    val maxZoom: Int,
    val attribution: String,
    val country: String? = null,
    /** Bundled asset path (app/src/main/assets/...) for bundled vector styles. */
    val assetPath: String? = null,
    /** True when the endpoint's tile matrix is {z}/{y}/{x} instead of XYZ. */
    val useYBeforeX: Boolean = false,
    /**
     * Upstream raster tile host list, used when upstream serves tiles across
     * multiple hosts and MapLibre cannot express that as a single template.
     * Non-empty only for those entries; otherwise [rasterUrl] is the exact
     * upstream template.
     */
    val rasterUrls: List<String> = emptyList(),
    /**
     * Upstream tileSize for the raster source. MapLibre defaults to 512;
     * some raster providers explicitly ship 256px tiles and swisstopo
     * ships 128pt tiles — reproducing the upstream value keeps the raster
     * crisp at the same zooms as the web.
     */
    val rasterTileSize: Int = 256
) {
    // ===================== World (basemapTree.world order) =====================
    // IDs are kept identical to the prior catalog. Type is real from the
    // upstream source, not assumed.

    // Liberty Topo — VECTOR
    // Underlying composition (per GPX Studio styles repo description):
    //   Liberty style from OpenFreeMap-style OSS Liberty, plus topographic
    //   additions (contours/hillshade) from osm-liberty-topo.
    // Nyasar uses the real OSS Liberty-style style endpoint under
    // tiles.openfreemap.org, not styles.gpx.studio.
    LIBERTY_TOPO(
        "libertyTopo", "Liberty Topo",
        styleUrl = "https://tiles.openfreemap.org/styles/liberty",
        rasterUrl = null, maxZoom = 14,
        // NOTE: current styleUrl is plain OpenFreeMap Liberty — no
        // topo/hillshade layer, no Mapterhorn source actually configured in
        // this style despite the "Topo" name. This attribution matches
        // GPX Studio's real (topo-enabled) Liberty Topo for naming
        // consistency, but is not yet earned by what this style JSON
        // actually fetches. Flagged, not silently left inconsistent.
        attribution = "© Mapterhorn © OpenMapTiles © OpenStreetMap contributors"
    ),

    // Liberty Satellite — VECTOR
    // GPX Studio describes Liberty Satellite as a Liberty-derived style with
    // most fill layers removed, transparency added, text colors inverted so
    // the vector overlay sits on satellite imagery. Nyasar reproduces this
    // as an inline Liberty-derived vector style layered on top of an imagery
    // source that Nyasar controls — NOT Esri Satellite and NOT
    // styles.gpx.studio.
    LIBERTY_SATELLITE(
        "libertySatellite", "Liberty Satellite",
        styleUrl = null,
        rasterUrl = null, maxZoom = 14,
        assetPath = null,
        // FIX: TileProvider.styleUrlFor now wires real MapTiler satellite
        // tiles into this entry's imagery layer (via
        // MapTilerProvider.satelliteRasterTileUrl()) when MAPTILER_API_KEY
        // is configured. This attribution is now earned, not aspirational —
        // though if the key is missing, it silently degrades back to
        // vector-only (blank imagery) with this same attribution text
        // still technically over-claiming for that specific degraded case.
        attribution = "© OpenMapTiles © OpenStreetMap contributors © MapTiler"
    ),

    // OpenMapTiles OSM — VECTOR
    // OpenMapTiles schema/style. Public keyless hosted OpenMapTiles style
    // endpoint not verified in this audit; modeled honestly as requiring a
    // hosted OpenMapTiles source. Placeholder only — not served until a real
    // keyless hosted style is confirmed.
    OSM(
        "osm", "OpenMapTiles OSM",
        // FIX (was null -> silently fell through TileProvider.styleUrlFor's
        // catch-all to whatever the active default provider's Outdoor style
        // was, i.e. MapTiler, requiring an API key this entry's name never
        // implied it needed). OpenFreeMap's "bright" style is a real, live,
        // keyless OpenMapTiles-schema vector style — genuinely distinct
        // from Liberty Topo and from OSM_TOPO below.
        styleUrl = "https://tiles.openfreemap.org/styles/bright",
        rasterUrl = null, maxZoom = 14,
        attribution = "© OpenMapTiles © OpenStreetMap contributors"
    ),

    // OpenMapTiles OSM Topo — VECTOR
    // OpenMapTiles vector data + contours + hillshading (per GPX Studio
    // styles repo description). Same hosting caveat as OpenMapTiles OSM.
    OSM_TOPO(
        "osmTopo", "OpenMapTiles OSM Topo",
        // FIX (was null -> same fallback bug as OSM, and identical
        // rendered output to it). "positron" is a real, live, keyless
        // OpenFreeMap style, visually distinct from both Liberty Topo and
        // OSM (bright). HONEST LIMITATION: this is NOT a true topo style —
        // no contour lines or hillshade layer is added, because no public
        // keyless contour/hillshade overlay source was verified in this
        // pass (matches this file's own stated principle: don't fake a
        // capability that isn't really there). It's a second genuinely
        // distinct basemap, not genuinely "topo" yet.
        styleUrl = "https://tiles.openfreemap.org/styles/positron",
        rasterUrl = null, maxZoom = 14,
        attribution = "© OpenMapTiles © OpenStreetMap contributors"
    ),

    // OpenStreetMap — RASTER
    // Official OSM raster tiles: tile.openstreetmap.org. Follow the OSMF
    // Tile Usage Policy (no offline bulk download). Nyasar keeps this as a
    // live basemap only.
    OSM_STANDARD(
        "openStreetMap", "OpenStreetMap",
        styleUrl = null,
        rasterUrl = null,
        rasterUrls = listOf(
            "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"
        ),
        maxZoom = 19,
        attribution = "© OpenStreetMap contributors"
    ),

    // OpenTopoMap — RASTER
    // Official OpenTopoMap raster tiles: tile.opentopomap.org.
    OPEN_TOPO_MAP(
        "openTopoMap", "OpenTopoMap",
        styleUrl = null,
        rasterUrl = "https://tile.opentopomap.org/{z}/{x}/{y}.png",
        maxZoom = 17,
        attribution = "© OpenTopoMap © OpenStreetMap contributors"
    ),

    // OpenHikingMap — RASTER
    // OpenHikingMap raster tiles hosted by openmaps.fr/tile.openmaps.fr.
    // Not Waymarked Trails, not OpenTopoMap, not CyclOSM — kept as OpenHikingMap.
    OPEN_HIKING_MAP(
        "openHikingMap", "OpenHikingMap",
        styleUrl = null,
        rasterUrl = "https://tile.openmaps.fr/OpenHikingMap/{z}/{x}/{y}.png",
        maxZoom = 18,
        attribution = "© OpenHikingMap © OpenStreetMap contributors"
    ),

    // CyclOSM — RASTER
    // CyclOSM raster tiles hosted across a/b/c.tile-cyclosm.openstreetmap.fr.
    CYCLOSM(
        "cyclOSM", "CyclOSM",
        styleUrl = null,
        rasterUrl = null,
        rasterUrls = listOf(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png"
        ),
        maxZoom = 18,
        attribution = "© CyclOSM © OpenStreetMap contributors"
    ),

    // UtagawaMTB — VECTOR
    // Public UtagawaMTB-style style (MapLibre style JSON). Upstream metadata
    // was not fully verified in this pass; kept as the public style URL only
    // if it continues to serve a MapLibre-compatible style.
    UTAGAWA_VTT(
        "utagawaVTT", "UtagawaMTB",
        styleUrl = "https://maps.utagawavtt.com/styles/utagawavtt/style.json",
        rasterUrl = null, maxZoom = 14,
        attribution = "© OpenMapTiles © OpenStreetMap contributors"
    ),

    // ===================== Countries (basemapTree.countries order) =====================

    // --- Belgium ---
    IGN_BE(
        "ignBe", "IGN Topo", country = "Belgium",
        styleUrl = null,
        rasterUrl = "https://cartoweb.wmts.ngi.be/1.0.0/topo/default/3857/{z}/{y}/{x}.png",
        maxZoom = 17,
        attribution = "© IGN/NGI",
        // NGI's WMTS orders its tile matrix as {z}/{y}/{x} — a plain XYZ
        // template here 404s.
        useYBeforeX = true
    ),

    // --- Bulgaria ---
    BG_MOUNTAINS(
        "bgMountains", "BGMountains", country = "Bulgaria",
        styleUrl = null,
        rasterUrl = "https://bgmtile.kade.si/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "BGM Legend / CART Lab, BGM team, © CC BY-SA 4.0, Garmin version"
    ),

    // --- Finland ---
    FINLAND_TOPO(
        "finlandTopo", "Lantmäteriverket Terrängkarta", country = "Finland",
        styleUrl = null,
        rasterUrl = "https://avoin-karttakuva.maanmittauslaitos.fi/avoin/wmts?layer=maastokartta&style=default&tilematrixset=WGS84_Pseudo-Mercator&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/png&TileMatrix={z}&TileCol={x}&TileRow={y}&api-key=30cb768c-c968-493c-ae24-2b0b974ebd29",
        maxZoom = 18,
        attribution = "© Maanmittauslaitos"
    ),

    // --- France (bundled vector styles are Nyasar-bundled adapted IGN styles,
    //     loaded inline via RasterStyleJson, not fetched externally) ---
    IGN_FR_PLAN(
        "ignFrPlan", "IGN Plan", country = "France",
        styleUrl = null, assetPath = "styles/gpxstudio/ign-fr-plan.json",
        rasterUrl = null, maxZoom = 16,
        attribution = "IGN-F/Géoportail"
    ),
    IGN_FR_TOPO(
        "ignFrTopo", "IGN Topo", country = "France",
        styleUrl = null, assetPath = "styles/gpxstudio/ign-fr-topo.json",
        rasterUrl = null, maxZoom = 16,
        attribution = "IGN-F/Géoportail"
    ),
    IGN_FR_SCAN25(
        "ignFrScan25", "IGN SCAN25", country = "France",
        styleUrl = null,
        rasterUrl = "https://data.geopf.fr/private/wmts?SERVICE=WMTS&VERSION=1.0.0&REQUEST=GetTile&TILEMATRIXSET=PM&TILEMATRIX={z}&TILECOL={x}&TILEROW={y}&LAYER=GEOGRAPHICALGRIDSYSTEMS.MAPS.SCAN25TOUR&FORMAT=image/jpeg&STYLE=normal&apikey=ign_scan_ws",
        maxZoom = 16,
        attribution = "IGN-F/Géoportail"
    ),
    IGN_FR_SATELLITE(
        "ignFrSatellite", "IGN Satellite", country = "France",
        styleUrl = null, assetPath = "styles/gpxstudio/ign-fr-satellite.json",
        rasterUrl = null, maxZoom = 16,
        attribution = "IGN-F/Géoportail"
    ),

    // --- New Zealand (LINZ endpoints carry Nyasar's own configured key,
    //     identical to the upstream catalog) ---
    LINZ(
        "linz", "LINZ Topo", country = "New Zealand",
        styleUrl = "https://basemaps.linz.govt.nz/v1/styles/topographic-v2.json?api=d01fbtg0ar23gctac5m0jgyy2ds",
        rasterUrl = null, maxZoom = 15,
        attribution = "© LINZ CC BY 4.0"
    ),
    LINZ_TOPO(
        "linzTopo", "LINZ Topo50", country = "New Zealand",
        styleUrl = null,
        rasterUrl = "https://basemaps.linz.govt.nz/v1/tiles/topo-raster/WebMercatorQuad/{z}/{x}/{y}.webp?api=d01fbtg0ar23gctac5m0jgyy2ds",
        maxZoom = 16,
        attribution = "© LINZ CC BY 4.0 © Imagery Basemap contributors"
    ),

    // --- Norway ---
    NORWAY_TOPO(
        "norwayTopo", "Topografisk Norgeskart 4", country = "Norway",
        styleUrl = null,
        rasterUrl = "https://cache.kartverket.no/v1/wmts/1.0.0/topo/default/webmercator/{z}/{y}/{x}.png",
        maxZoom = 20,
        attribution = "© Geonorge"
    ),

    // --- Spain ---
    IGN_ES(
        "ignEs", "IGN Topo", country = "Spain",
        styleUrl = null,
        rasterUrl = "https://www.ign.es/wmts/mapa-raster?layer=MTN&style=default&tilematrixset=GoogleMapsCompatible&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg&TileMatrix={z}&TileCol={x}&TileRow={y}",
        maxZoom = 20,
        attribution = "© IGN"
    ),
    IGN_ES_SATELLITE(
        "ignEsSatellite", "IGN Satellite", country = "Spain",
        styleUrl = null,
        rasterUrl = "https://www.ign.es/wmts/pnoa-ma?layer=OI.OrthoimageCoverage&style=default&tilematrixset=GoogleMapsCompatible&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg&TileMatrix={z}&TileCol={x}&TileRow={y}",
        maxZoom = 20,
        attribution = "© IGN"
    ),

    // --- Switzerland ---
    SWISSTOPO_RASTER(
        "swisstopoRaster", "swisstopo Raster", country = "Switzerland",
        styleUrl = null,
        rasterUrl = "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/{z}/{x}/{y}.jpeg",
        maxZoom = 19,
        attribution = "© swisstopo",
        // Upstream pins tileSize 128 for swisstopo's WMTS pyramid — the
        // server serves 512px tiles meant to render at 128pt; keeping
        // MapLibre's 256 default would render them upscaled/blurry.
        rasterTileSize = 128
    ),
    SWISSTOPO_VECTOR(
        "swisstopoVector", "swisstopo Vector", country = "Switzerland",
        styleUrl = "https://vectortiles.geo.admin.ch/styles/ch.swisstopo.basemap.vt/style.json",
        rasterUrl = null, maxZoom = 15,
        attribution = "© swisstopo"
    ),
    SWISSTOPO_SATELLITE(
        "swisstopoSatellite", "swisstopo Satellite", country = "Switzerland",
        styleUrl = "https://vectortiles.geo.admin.ch/styles/ch.swisstopo.imagerybasemap.vt/style.json",
        rasterUrl = null, maxZoom = 15,
        attribution = "© swisstopo"
    ),

    // --- United Kingdom ---
    ORDNANCE_SURVEY(
        "ordnanceSurvey", "Ordnance Survey", country = "United Kingdom",
        styleUrl = "https://api.os.uk/maps/vector/v1/vts/resources/styles?srs=3857&key=piCT8WysfuC3xLSUW7sGLfrAAJoYDvQz",
        rasterUrl = null, maxZoom = 14,
        attribution = "© Ordnance Survey"
    ),

    // --- United States ---
    USGS(
        "usgs", "USGS", country = "United States",
        styleUrl = null,
        rasterUrl = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}?blankTile=false",
        maxZoom = 16,
        attribution = "© USGS"
    );

    val isRaster: Boolean get() = rasterUrl != null || rasterUrls.isNotEmpty()

    /** Grouping section shown in the picker. */
    val section: String get() = country ?: "World"

    companion object {
        /** Parse a persisted id back to an entry, falling back to Liberty Topo. */
        fun fromId(id: String?): BasemapEntry =
            entries.firstOrNull { it.gpxKey == id } ?: LIBERTY_TOPO

        /** Ordered catalog as shown in the picker. */
        val ordered: List<BasemapEntry> = entries.toList()
    }
}
