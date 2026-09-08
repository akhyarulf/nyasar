package com.nyasar.app.map

/**
 * Waymarked Trails overlay layers (waymarkedtrails.org) — the "Overlays"
 * section in GPX Studio's Basemaps panel that Nyasar didn't have yet.
 *
 * These are transparent raster overlays (route relations drawn as colored
 * lines) meant to sit ON TOP OF a basemap, not replace it — a completely
 * different concept from [BasemapEntry], which is why this is its own
 * catalog rather than more entries bolted onto that enum. Multiple
 * overlays can be active at once (checkboxes, not radio buttons), same as
 * the reference UI.
 *
 * Waymarked Trails' tile service is public and keyless — confirmed by the
 * project's own documentation (waymarkedtrails.org/help) and its use as a
 * plain XYZ raster overlay by other open map tools (this is the same
 * public tileserver GPX Studio's own "Waymarked Trails" overlay section
 * draws from, hence the visual match in the reference screenshot — Nyasar
 * hits that public endpoint directly, no proxying through gpx.studio's
 * own domain).
 *
 * @param id stable id (persistence key + MapLibre source/layer id suffix).
 * @param displayName shown in the overlay picker.
 * @param rasterUrl XYZ template, {z}/{x}/{y}.png — served pre-rendered
 *        with transparency already baked in (no separate style needed).
 * @param maxZoom Waymarked Trails serves up to 18 for all three layers.
 */
enum class OverlayLayer(
    val id: String,
    val displayName: String,
    val rasterUrl: String,
    val maxZoom: Int = 18,
    val attribution: String = "Waymarked Trails, OpenStreetMap contributors"
) {
    HIKING(
        id = "waymarked_hiking",
        displayName = "Hiking",
        rasterUrl = "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png"
    ),
    CYCLING(
        id = "waymarked_cycling",
        displayName = "Cycling",
        rasterUrl = "https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png"
    ),
    MTB(
        id = "waymarked_mtb",
        displayName = "MTB",
        rasterUrl = "https://tile.waymarkedtrails.org/mtb/{z}/{x}/{y}.png"
    )
}
