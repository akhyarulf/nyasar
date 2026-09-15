package com.nyasar.app.publish

/**
 * Standard polyline encoding (Google Maps / Strava format) — the exact
 * algorithm the `routes.track_polyline` column expects per schema_v1.sql.
 *
 * Pure Kotlin, no Android dependencies — same spirit as GpxExporter's
 * "plain function, trivially testable" contract — so the round-trip can be
 * unit tested on the JVM (PolylineEncoderTest runs in CI via
 * testDebugUnitTest).
 *
 * Format recap: each coordinate is multiplied by 1e5 and rounded, then
 * delta-encoded against the previous coordinate (first against 0,0). Each
 * delta is zig-zag mapped (left-shift 1, inverted when negative) and
 * chunked into 5-bit little-endian groups; all but the last chunk get the
 * continuation bit 0x20. Chars are plain ASCII (value + 63).
 */
object PolylineEncoder {

    private const val COORD_FACTOR = 1e5

    fun encode(points: List<Pair<Double, Double>>): String {
        if (points.isEmpty()) return ""
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for ((lat, lng) in points) {
            val latE5 = Math.round(lat * COORD_FACTOR)
            val lngE5 = Math.round(lng * COORD_FACTOR)
            encodeDelta(latE5 - prevLat, sb)
            encodeDelta(lngE5 - prevLng, sb)
            prevLat = latE5
            prevLng = lngE5
        }
        return sb.toString()
    }

    /** Inverse of [encode] — used by tests for the round-trip guarantee and
     *  later by the public-route viewer (Fase 2 slice 2) to draw tracks. */
    fun decode(polyline: String): List<Pair<Double, Double>> {
        if (polyline.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < polyline.length) {
            var shift = 0
            var value = 0L
            while (true) {
                require(index < polyline.length) { "Truncated polyline" }
                val c = polyline[index++].code - 63
                value = value or ((c and 0x1f).toLong() shl shift)
                shift += 5
                if (c < 0x20) break
            }
            lat += if (value and 1L == 1L) (value ushr 1).inv() else value ushr 1
            shift = 0
            value = 0L
            while (true) {
                require(index < polyline.length) { "Truncated polyline" }
                val c = polyline[index++].code - 63
                value = value or ((c and 0x1f).toLong() shl shift)
                shift += 5
                if (c < 0x20) break
            }
            lng += if (value and 1L == 1L) (value ushr 1).inv() else value ushr 1
            result.add(lat / COORD_FACTOR to lng / COORD_FACTOR)
        }
        return result
    }

    private fun encodeDelta(value: Long, sb: StringBuilder) {
        var v = value shl 1
        if (value < 0) v = v.inv()
        while (v >= 0x20) {
            // Chars are (chunk + 63) per the format — without the +63 offset
            // the emitted control characters break every external decoder.
            // toChar() is mandatory: append(Int) would emit decimal digits.
            sb.append((((v and 0x1f) or 0x20) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }
}
