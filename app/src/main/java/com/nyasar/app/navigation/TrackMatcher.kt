package com.nyasar.app.navigation

import com.nyasar.app.gpx.model.TrackPoint

/**
 * Precomputes cumulative distance along a track once (on GPX import / nav
 * start), then answers two questions cheaply per GPS fix:
 *  1. How far is the user from the track right now (for off-route detection)?
 *  2. How far along the track has the user progressed (for distance
 *     traveled / remaining distance)?
 */
class TrackMatcher(points: List<TrackPoint>) {

    private val latLngs: List<LatLng> = points.map { LatLng(it.lat, it.lon) }

    /** cumulativeDistance[i] = distance from start of track to point i, in meters. */
    private val cumulativeDistance: DoubleArray = DoubleArray(latLngs.size).also { arr ->
        for (i in 1 until latLngs.size) {
            arr[i] = arr[i - 1] + GeoMath.distanceMeters(latLngs[i - 1], latLngs[i])
        }
    }

    val totalDistanceMeters: Double get() = cumulativeDistance.lastOrNull() ?: 0.0

    data class MatchResult(
        val distanceFromTrackMeters: Double,
        val distanceTraveledMeters: Double,
        val remainingDistanceMeters: Double,
        val nearestSegmentIndex: Int
    )

    /**
     * Finds the closest point on the whole polyline to [position].
     * O(n) per call — fine for typical GPX tracks (thousands of points) at
     * GPS update rates of ~1 Hz. If profiling ever shows this is too slow
     * for very large tracks, restrict the search to a window around the
     * last matched segment index instead of a full rewrite.
     */
    fun match(position: LatLng): MatchResult? {
        if (latLngs.size < 2) return null

        var bestDist = Double.MAX_VALUE
        var bestSegment = 0
        var bestProjectedDistanceAlong = 0.0

        for (i in 0 until latLngs.size - 1) {
            val a = latLngs[i]
            val b = latLngs[i + 1]
            val d = GeoMath.distanceToSegmentMeters(position, a, b)
            if (d < bestDist) {
                bestDist = d
                bestSegment = i
                // distance along track = cumulative to segment start + progress into this segment
                val segLen = cumulativeDistance[i + 1] - cumulativeDistance[i]
                val progressFraction = projectedFraction(position, a, b)
                bestProjectedDistanceAlong = cumulativeDistance[i] + segLen * progressFraction
            }
        }

        return MatchResult(
            distanceFromTrackMeters = bestDist,
            distanceTraveledMeters = bestProjectedDistanceAlong,
            remainingDistanceMeters = (totalDistanceMeters - bestProjectedDistanceAlong).coerceAtLeast(0.0),
            nearestSegmentIndex = bestSegment
        )
    }

    private fun projectedFraction(point: LatLng, a: LatLng, b: LatLng): Double {
        // Re-derive t the same way GeoMath.distanceToSegmentMeters does internally,
        // kept separate so GeoMath stays a pure "distance" utility.
        val latRef = Math.toRadians(point.lat)
        val cosLat = Math.cos(latRef)
        fun toXY(p: LatLng): Pair<Double, Double> {
            val x = Math.toRadians(p.lon) * cosLat * 6_371_000.0
            val y = Math.toRadians(p.lat) * 6_371_000.0
            return x to y
        }
        val (px, py) = toXY(point)
        val (ax, ay) = toXY(a)
        val (bx, by) = toXY(b)
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        return if (lenSq == 0.0) 0.0 else
            (((px - ax) * abx + (py - ay) * aby) / lenSq).coerceIn(0.0, 1.0)
    }
}
