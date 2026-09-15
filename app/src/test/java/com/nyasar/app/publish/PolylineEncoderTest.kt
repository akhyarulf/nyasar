package com.nyasar.app.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PolylineEncoderTest {

    /** Canonical example from the Google polyline algorithm docs — the
     *  strongest known-answer test available for this format. */
    @Test
    fun `encodes the canonical Google docs example`() {
        val points = listOf(
            38.5 to -120.2,
            40.7 to -120.95,
            43.252 to -126.453
        )
        // Canonical string verbatim from the official docs table
        // (developers.google.com/maps/documentation/utilities/polylinealgorithm):
        // the third latitude chunk is _mqN, commonly mis-transcribed as _mN.
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineEncoder.encode(points))
    }

    @Test
    fun `empty input encodes to empty string`() {
        assertEquals("", PolylineEncoder.encode(emptyList()))
        assertTrue(PolylineEncoder.decode("").isEmpty())
    }

    @Test
    fun `single point round-trips`() {
        val p = listOf(-6.84321 to 107.12345)
        val decoded = PolylineEncoder.decode(PolylineEncoder.encode(p))
        assertEquals(1, decoded.size)
        assertEquals(p[0].first, decoded[0].first, 1e-5)
        assertEquals(p[0].second, decoded[0].second, 1e-5)
    }

    @Test
    fun `realistic hiking track round-trips within 1e-5 degrees`() {
        val rng = Random(42)
        var lat = -7.5
        var lon = 110.0
        val points = buildList {
            repeat(2880) { // ~4h recording at ~5s intervals, per PROJECT_CONTEXT table
                lat += (rng.nextDouble() - 0.5) * 2e-4
                lon += (rng.nextDouble() - 0.5) * 2e-4
                add(lat to lon)
            }
        }
        val encoded = PolylineEncoder.encode(points)
        val decoded = PolylineEncoder.decode(encoded)
        assertEquals(points.size, decoded.size)
        points.forEachIndexed { i, (lat, lon) ->
            assertEquals(lat, decoded[i].first, 1e-5)
            assertEquals(lon, decoded[i].second, 1e-5)
        }
        // Documented size expectation (~14 KB for ~2880 points) sanity bound
        assertTrue("encoded length ${encoded.length}", encoded.length in 3000..40000)
    }

    @Test
    fun `negative and crossing-zero coordinates round-trip`() {
        val points = listOf(
            -0.00001 to 0.00001,
            0.00001 to -0.00001,
            -45.5 to 170.25,
            89.99999 to -179.99999
        )
        val decoded = PolylineEncoder.decode(PolylineEncoder.encode(points))
        points.forEachIndexed { i, (lat, lon) ->
            assertEquals(lat, decoded[i].first, 1e-5)
            assertEquals(lon, decoded[i].second, 1e-5)
        }
    }
}
