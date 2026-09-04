package com.nyasar.app.map

/**
 * GPX Studio's official basemap catalog, mirrored 1:1 (same layer IDs/keys,
 * same display names, same tile endpoints, same grouping). Sources:
 *  - gpx.studio  website/src/lib/assets/layers.ts  (basemaps object)
 *  - gpx.studio  website/src/locales/en.json       (layers.label = names)
 *
 * Everything here is keyless, so the full list works out of the box —
 * exactly like GPX Studio's default list. The IGN France vector styles
 * (ignFrPlan/ignFrTopo/ignFrSatellite) are bundled copies of GPX Studio's
 * own adapted styles shipped in app assets and served inline (see
 * [InlineStyleJson] in TileProvider.kt) — same behavior, no extra hosting.
 *
 * [NyasarMapView] resolves a [BasemapEntry] through
 * [TileProvider.styleUrlFor] — nothing else in the app needs to know
 * whether a basemap is raster or vector.
 *
 * @param gpxKey   the exact layer key used by gpx.studio (persisted id).
 * @param gpxName  the exact display name from gpx.studio's en.json label.
 * @param country  grouping level: null = World section, otherwise the
 *                 country section name (same grouping as gpx.studio's
 *                 basemapTree).
 */
enum class BasemapEntry(
    val gpxKey: String,
    val gpxName: String,
    /** MapLibre style JSON URL, or null when this is a raster basemap. */
    val styleUrl: String?,
    /** XYZ/WMTS raster tile template with {z}/{x}/{y}, or null when vector. */
    val rasterUrl: String?,
    /** Max zoom supported by raster sources (clamped in the inline style). */
    val maxZoom: Int,
    val attribution: String,
    val country: String? = null,
    /** Bundled asset path (app/src/main/assets/...) for inline vector styles. */
    val assetPath: String? = null,
    /** True when the endpoint's tile matrix is {z}/{y}/{x} instead of XYZ. */
    val useYBeforeX: Boolean = false
) {
    // ===================== World (basemapTree.world order) =====================

    // 'libertyTopo' = https://styles.gpx.studio/liberty-topo.json
    // (OpenFreeMap "liberty" + GPX Studio's contours/hillshading sources)
    LIBERTY_TOPO(
        "libertyTopo", "Liberty Topo",
        styleUrl = "https://styles.gpx.studio/liberty-topo.json",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenMapTiles / OpenStreetMap"
    ),
    LIBERTY_SATELLITE(
        "libertySatellite", "Liberty Satellite",
        styleUrl = "https://styles.gpx.studio/liberty-satellite.json",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenMapTiles / OpenStreetMap"
    ),
    // 'osm' = "OpenMapTiles OSM" in GPX Studio
    OSM(
        "osm", "OpenMapTiles OSM",
        styleUrl = "https://styles.gpx.studio/osm.json",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenMapTiles / OpenStreetMap"
    ),
    // 'osmTopo' = "OpenMapTiles OSM Topo" in GPX Studio
    OSM_TOPO(
        "osmTopo", "OpenMapTiles OSM Topo",
        styleUrl = "https://styles.gpx.studio/osm-topo.json",
        rasterUrl = null, maxZoom = 14,
        attribution = "OpenMapTiles / OpenStreetMap"
    ),
    ESRI_SATELLITE(
        "esriSatellite", "Esri Satellite",
        styleUrl = null,
        rasterUrl = "https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/WMTS/tile/1.0.0/World_Imagery/default/default028mm/{z}/{y}/{x}.jpg",
        maxZoom = 19,
        attribution = "Esri, Vantor, Earthstar Geographics, and the GIS User Community"
    ),
    OSM_STANDARD(
        "openStreetMap", "OpenStreetMap",
        styleUrl = null,
        rasterUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "(C) OpenStreetMap contributors"
    ),
    OPEN_TOPO_MAP(
        "openTopoMap", "OpenTopoMap",
        styleUrl = null,
        rasterUrl = "https://tile.opentopomap.org/{z}/{x}/{y}.png",
        maxZoom = 17,
        attribution = "(C) OpenTopoMap (CC-BY-SA) / OpenStreetMap"
    ),
    // 'openHikingMap' in GPX Studio = refuges.info hiking raster
    // (https://maps.refuges.info/hiking/), NOT waymarkedtrails.
    OPEN_HIKING_MAP(
        "openHikingMap", "OpenHikingMap",
        styleUrl = null,
        rasterUrl = "https://maps.refuges.info/hiking/{z}/{x}/{y}.png",
        maxZoom = 18,
        attribution = "(C) Hiking/mri / OpenStreetMap"
    ),
    CYCLOSM(
        "cyclOSM", "CyclOSM",
        styleUrl = null,
        rasterUrl = "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
        maxZoom = 18,
        attribution = "CyclOSM / OpenStreetMap"
    ),
    UTAGAWA_VTT(
        "utagawaVTT", "UtagawaMTB",
        styleUrl = "https://maps.utagawavtt.com/styles/utagawavtt/style.json",
        rasterUrl = null, maxZoom = 14,
        attribution = "UtagawaVTT / OpenStreetMap"
    ),

    // ===================== Countries (basemapTree.countries order) =====================

    // --- Belgium ---
    IGN_BE(
        "ignBe", "IGN Topo", country = "Belgium",
        styleUrl = null,
        rasterUrl = "https://cartoweb.wmts.ngi.be/1.0.0/topo/default/3857/{z}/{y}/{x}.png",
        maxZoom = 17,
        attribution = "(C) IGN/NGI",
        // NGI's WMTS is the one endpoint in the whole catalog that orders
        // its tile matrix as {z}/{y}/{x} — a plain XYZ template here 404s.
        useYBeforeX = true
    ),

    // --- Bulgaria ---
    BG_MOUNTAINS(
        "bgMountains", "BGMountains", country = "Bulgaria",
        styleUrl = null,
        rasterUrl = "https://bgmtile.kade.si/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "BGM team / CART Lab, CC BY-SA 4.0"
    ),

    // --- Finland ---
    FINLAND_TOPO(
        "finlandTopo", "Lantmäteriverket Terrängkarta", country = "Finland",
        styleUrl = null,
        rasterUrl = "https://avoin-karttakuva.maanmittauslaitos.fi/avoin/wmts?layer=maastokartta&style=default&tilematrixset=WGS84_Pseudo-Mercator&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/png&TileMatrix={z}&TileCol={x}&TileRow={y}&api-key=30cb768c-c968-493c-ae24-2b0b974ebd29",
        maxZoom = 18,
        attribution = "(C) Maanmittauslaitos"
    ),

    // --- France (vector styles are GPX Studio's own adapted IGN styles,
    //     bundled from their repo as APK assets and loaded inline) ---
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

    // --- New Zealand (LINZ endpoints carry GPX Studio's own public key,
    //     identical to the upstream catalog) ---
    LINZ(
        "linz", "LINZ Topo", country = "New Zealand",
        styleUrl = "https://basemaps.linz.govt.nz/v1/styles/topographic-v2.json?api=d01fbtg0ar23gctac5m0jgyy2ds",
        rasterUrl = null, maxZoom = 15,
        attribution = "LINZ CC BY 4.0"
    ),
    LINZ_TOPO(
        "linzTopo", "LINZ Topo50", country = "New Zealand",
        styleUrl = null,
        rasterUrl = "https://basemaps.linz.govt.nz/v1/tiles/topo-raster/WebMercatorQuad/{z}/{x}/{y}.webp?api=d01fbtg0ar23gctac5m0jgyy2ds",
        maxZoom = 16,
        attribution = "LINZ CC BY 4.0, Imagery Basemap contributors"
    ),

    // --- Norway ---
    NORWAY_TOPO(
        "norwayTopo", "Topografisk Norgeskart 4", country = "Norway",
        styleUrl = null,
        rasterUrl = "https://cache.kartverket.no/v1/wmts/1.0.0/topo/default/webmercator/{z}/{y}/{x}.png",
        maxZoom = 20,
        attribution = "(C) Geonorge"
    ),

    // --- Spain ---
    IGN_ES(
        "ignEs", "IGN Topo", country = "Spain",
        styleUrl = null,
        rasterUrl = "https://www.ign.es/wmts/mapa-raster?layer=MTN&style=default&tilematrixset=GoogleMapsCompatible&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg&TileMatrix={z}&TileCol={x}&TileRow={y}",
        maxZoom = 20,
        attribution = "(C) IGN"
    ),
    IGN_ES_SATELLITE(
        "ignEsSatellite", "IGN Satellite", country = "Spain",
        styleUrl = null,
        rasterUrl = "https://www.ign.es/wmts/pnoa-ma?layer=OI.OrthoimageCoverage&style=default&tilematrixset=GoogleMapsCompatible&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg&TileMatrix={z}&TileCol={x}&TileRow={y}",
        maxZoom = 20,
        attribution = "(C) IGN"
    ),

    // --- Switzerland ---
    SWISSTOPO_RASTER(
        "swisstopoRaster", "swisstopo Raster", country = "Switzerland",
        styleUrl = null,
        rasterUrl = "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/{z}/{x}/{y}.jpeg",
        maxZoom = 19,
        attribution = "(C) swisstopo"
    ),
    SWISSTOPO_VECTOR(
        "swisstopoVector", "swisstopo Vector", country = "Switzerland",
        styleUrl = "https://vectortiles.geo.admin.ch/styles/ch.swisstopo.basemap.vt/style.json",
        rasterUrl = null, maxZoom = 15,
        attribution = "(C) swisstopo"
    ),
    SWISSTOPO_SATELLITE(
        "swisstopoSatellite", "swisstopo Satellite", country = "Switzerland",
        styleUrl = "https://vectortiles.geo.admin.ch/styles/ch.swisstopo.imagerybasemap.vt/style.json",
        rasterUrl = null, maxZoom = 15,
        attribution = "(C) swisstopo"
    ),

    // --- United Kingdom ---
    ORDNANCE_SURVEY(
        "ordnanceSurvey", "Ordnance Survey", country = "United Kingdom",
        styleUrl = "https://api.os.uk/maps/vector/v1/vts/resources/styles?srs=3857&key=piCT8WysfuC3xLSUW7sGLfrAAJoYDvQz",
        rasterUrl = null, maxZoom = 14,
        attribution = "(C) Ordnance Survey"
    ),

    // --- United States ---
    USGS(
        "usgs", "USGS", country = "United States",
        styleUrl = null,
        rasterUrl = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}?blankTile=false",
        maxZoom = 16,
        attribution = "(C) USGS"
    );

    val isRaster: Boolean get() = rasterUrl != null

    /** Grouping section shown in the picker, mirroring GPX Studio's tree. */
    val section: String get() = country ?: "World"

    companion object {
        /** Parse a persisted id back to an entry, falling back to Liberty Topo. */
        fun fromId(id: String?): BasemapEntry =
            entries.firstOrNull { it.gpxKey == id } ?: LIBERTY_TOPO

        /** Ordered catalog as shown in the picker (GPX Studio tree order). */
        val ordered: List<BasemapEntry> = entries.toList()
    }
}
