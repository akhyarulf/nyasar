package com.nyasar.app.map

import com.nyasar.app.gpx.model.TrackPoint
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * "Jalur Saya" overlay — every saved Library route drawn as a line on the
 * map when the overlay is ON. This is deliberately NOT an entry in
 * [OverlayLayer]: those are Waymarked Trails RASTER tile layers (an XYZ
 * template downloaded per tile), while this overlay is GeoJSON Vector data
 * parsed from the user's own GPX files — a different source mechanism, a
 * different data owner (RouteRepository, not a tile server), and a
 * per-route visual distinction (active vs. other) that a single raster
 * template could never express. Forcing it into that enum would have made
 * both concepts worse; a sibling type keeps each one honest.
 *
 * Data comes straight from the existing route storage (RouteEntity +
 * RouteRepository.loadDocument, spec section 16's local-GPX architecture)
 * — no new storage, no second GPX parser.
 *
 * Performance contract (spec: "rendering tetap ringan ... GPX cukup
 * banyak"): [decimate] bounds each route's vertex count BEFORE the points
 * reach MapLibre, so N saved routes cost N × ~1500 vertices max, and the
 * mapping itself runs on Dispatchers.Default (see the refresh effect in
 * NyasarMapView) — the main thread only ever receives the finished
 * FeatureCollection for setGeoJson.
 */
data class MyRouteLine(
    val routeId: String,
    val name: String,
    /** Decimated polyline for map rendering — full fidelity stays on disk. */
    val points: List<TrackPoint>
) {
    /** GeoJSON feature with a stable id property for tap attribution. */
    fun toFeature(): Feature = Feature.fromGeometry(
        LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
    ).apply {
        addStringProperty(PROP_ROUTE_ID, routeId)
        addStringProperty(PROP_ROUTE_NAME, name)
    }

    companion object {
        const val PROP_ROUTE_ID = "routeId"
        const val PROP_ROUTE_NAME = "name"

        /** Ceiling per route — generous enough that real hiking tracks keep
         *  essentially all their shape; only pathological imports
         *  (100k+ point traces) get thinned. */
        const val MAX_VERTICES_PER_ROUTE = 1500

        /**
         * Spacing-based thinning: keeps every [stride]-th vertex when a
         * track exceeds [maxVertices]. Uniform spacing preserves the
         * overall shape (endpoints always kept) far better than
         * truncation, and is O(n) with zero allocations beyond the output
         * list — appropriate for a per-refresh call on Default dispatcher.
         * Tracks already under the ceiling are returned as-is (same list,
         * no copy), so the common case costs nothing.
         */
        fun decimate(points: List<TrackPoint>, maxVertices: Int = MAX_VERTICES_PER_ROUTE): List<TrackPoint> {
            if (points.size <= maxVertices) return points
            val stride = (points.size + maxVertices - 1) / maxVertices
            val out = ArrayList<TrackPoint>(maxVertices + 1)
            var i = 0
            while (i < points.size) {
                out.add(points[i])
                i += stride
            }
            // Always keep the true endpoint — a thinned route must still
            // visually terminate where the GPX says it ends.
            val last = points.last()
            if (out.last() !== last) out.add(last)
            return out
        }
    }
}
