package com.nyasar.app.navigation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in meters. */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return EARTH_RADIUS_M * c
    }

    /**
     * Approximate shortest distance in meters from [point] to the segment
     * a-b, by projecting into a local equirectangular plane (accurate
     * enough at the scale of GPS-vs-track distances, i.e. tens to low
     * hundreds of meters — not for long segments spanning many km).
     */
    fun distanceToSegmentMeters(point: LatLng, a: LatLng, b: LatLng): Double {
        val latRef = Math.toRadians(point.lat)
        val cosLat = cos(latRef)

        fun toXY(p: LatLng): Pair<Double, Double> {
            val x = Math.toRadians(p.lon) * cosLat * EARTH_RADIUS_M
            val y = Math.toRadians(p.lat) * EARTH_RADIUS_M
            return x to y
        }

        val (px, py) = toXY(point)
        val (ax, ay) = toXY(a)
        val (bx, by) = toXY(b)

        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby

        val t = if (lenSq == 0.0) 0.0 else
            (((px - ax) * abx + (py - ay) * aby) / lenSq).coerceIn(0.0, 1.0)

        val projX = ax + t * abx
        val projY = ay + t * aby

        val dx = px - projX
        val dy = py - projY
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Initial bearing in degrees clockwise from true north, from [from] to
     * [to] (P3E3: "Next Waypoint" direction). Standard great-circle initial
     * bearing formula — not the same as a straight line on an
     * equirectangular projection, but at waypoint-scale distances (tens of
     * meters to a few km) the difference is negligible and this stays
     * consistent with [distanceMeters] using the same great-circle model.
     */
    fun bearingDegrees(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }
}
