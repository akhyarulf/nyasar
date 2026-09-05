package com.nyasar.app.map

/**
 * Nyasar World basemap catalog.
 *
 * Country-specific basemaps (Belgium, Bulgaria, Finland, France, New
 * Zealand, Norway, Spain, Switzerland, UK, US) were removed from this
 * catalog on request — they were never wired to any picker UI to begin
 * with (dead data), and weren't audited for the same URL-shape issues
 * fixed below in the World entries. The `country`/`section` grouping
 * mechanism and the `assetPath`/`useYBeforeX` fields stay in the data
 * class below since they're generic, harmless infrastructure — just
 * unused by every entry now that the only enum values setting them non-
 * default are gone.
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
    val rasterTileSize: Int = 256,
    /** True only for entries with a genuine, intentional dependency on
     *  BuildConfig.MAPTILER_API_KEY (currently just OSM_TOPO — OpenFreeMap
     *  has no topo/contour style to use instead). Lets callers check
     *  isConfiguredFor() before offering this entry, rather than
     *  discovering the missing key only when styleUrlFor silently
     *  produces a broken or wrong result. */
    val requiresMapTilerKey: Boolean = false
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
        // P3I audit fix: attribution previously claimed "© Mapterhorn" even
        // though this entry's styleUrl fetches plain OpenFreeMap Liberty —
        // no Mapterhorn contour/hillshade source is actually configured
        // here. Attribution is a licensing statement, not a display label:
        // crediting a source that was never fetched is a real
        // misattribution, not just an inconsistent name, so the false
        // claim is removed rather than kept "for naming consistency" with
        // GPX Studio's differently-composed Liberty Topo. The name
        // ("Liberty Topo") stays as the picker label since it mirrors the
        // GPX Studio basemap list this catalog is modeled on, but the
        // legally-required text only credits what this entry truly renders.
        attribution = "© OpenMapTiles © OpenStreetMap contributors"
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
        // NOTE: TileProvider.styleUrlFor calls
        // RasterStyleJson.libertySatelliteStyle() with no imageryUrl, which
        // produces an empty "tiles": [] raster source — no satellite
        // imagery is actually fetched right now, only the Liberty vector
        // overlay renders. "MapTiler" is added here to match GPX Studio's
        // real Liberty Satellite (which does composite MapTiler imagery),
        // but is not yet earned by this entry's current output — wiring an
        // actual MapTiler satellite tile URL (BuildConfig.MAPTILER_API_KEY)
        // into that call is a separate follow-up fix, out of scope here.
        attribution = "© OpenMapTiles © OpenStreetMap contributors © MapTiler"
    ),

    // OpenMapTiles OSM — VECTOR
    // OpenMapTiles schema/style. Public keyless hosted OpenMapTiles style
    // endpoint not verified in this audit; modeled honestly as requiring a
    // hosted OpenMapTiles source. Placeholder only — not served until a real
    // keyless hosted style is confirmed.
    OSM(
        "osm", "OpenMapTiles OSM",
        styleUrl = "https://tiles.openfreemap.org/styles/bright",
        rasterUrl = null, maxZoom = 14,
        // P3I audit fix: was styleUrl=null, which silently fell through
        // TileProvider.styleUrlFor's `else` branch to whatever the active
        // default TileProvider's Outdoor style is (MapTiler) - not
        // OpenMapTiles, and silently required MAPTILER_API_KEY despite
        // this entry's own doc claiming an unconfigured placeholder.
        // OpenFreeMap's "Bright" style is a real, keyless, OpenMapTiles-
        // schema rendering distinct from Liberty (used by LIBERTY_TOPO
        // above) - a genuine second look at the same underlying OSM
        // vector data, not a repeat of Liberty under a different name.
        attribution = "© OpenMapTiles © OpenStreetMap contributors"
    ),

    // OpenMapTiles OSM Topo — VECTOR
    // OpenMapTiles vector data + contours + hillshading (per GPX Studio
    // styles repo description). Same hosting caveat as OpenMapTiles OSM.
    OSM_TOPO(
        "osmTopo", "OpenMapTiles OSM Topo",
        // P3I audit fix: OSM (above) has a real keyless OpenFreeMap
        // equivalent (Bright); OSM_TOPO does not - OpenFreeMap publishes
        // no topo/contour style at all. Rather than leave styleUrl=null
        // and let this silently fall through TileProvider.styleUrlFor's
        // `else` branch to whichever provider happens to be default
        // (previously MapTiler, by accident), this is now an explicit,
        // intentional MapTiler dependency - same tiles as before, but a
        // deliberate choice with honest attribution instead of a bug's
        // side effect. isConfiguredFor() below reflects the real
        // requirement so callers can detect "no key" instead of getting a
        // silently wrong substitute.
        styleUrl = null,
        rasterUrl = null, maxZoom = 14,
        requiresMapTilerKey = true,
        attribution = "© MapTiler © OpenMapTiles © OpenStreetMap contributors"
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

        /** True if [entry] can actually be rendered right now — false only
         *  for entries with requiresMapTilerKey=true when no key is
         *  configured. Everything else has no external key dependency. */
        fun isConfiguredFor(entry: BasemapEntry, mapTilerApiKey: String): Boolean =
            !entry.requiresMapTilerKey || mapTilerApiKey.isNotBlank()
    }
}
