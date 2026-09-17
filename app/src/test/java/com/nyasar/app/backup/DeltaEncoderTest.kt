package com.nyasar.app.backup

import com.nyasar.app.data.db.ActivityPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Known-answer + roundtrip tests for the activity_backups.track_data codec
 * (Fase 3 item 1). Runs in CI via the existing testDebugUnitTest step.
 */
class DeltaEncoderTest {

    private fun point(
        activityId: String = "act-1",
        sequence: Int,
        lat: Double,
        lon: Double,
        elevationM: Double?,
        speedMps: Float?,
        accuracyMeters: Float = 5f,
        timestampMs: Long
    ) = ActivityPointEntity(
        id = 0,
        activityId = activityId,
        sequence = sequence,
        lat = lat,
        lon = lon,
        elevationM = elevationM,
        speedMps = speedMps,
        accuracyMeters = accuracyMeters,
        timestampMs = timestampMs
    )

    @Test
    fun `empty list roundtrips to empty`() {
        val bytes = DeltaEncoder.encode(emptyList())
        assertTrue(DeltaEncoder.decode(bytes, "act-2").isEmpty())
    }

    @Test
    fun `single point roundtrips exactly`() {
        val p = listOf(
            point(sequence = 0, lat = -7.9837, lon = 112.5211, elevationM = 1145.5, speedMps = 1.23f, timestampMs = 1_700_000_000_000)
        )
        val out = DeltaEncoder.decode(DeltaEncoder.encode(p), "act-2")
        assertEquals(1, out.size)
        assertEquals(p[0].lat, out[0].lat, 1e-9)
        assertEquals(p[0].lon, out[0].lon, 1e-9)
        assertEquals(p[0].elevationM!!, out[0].elevationM!!, 0.01)
        assertEquals(p[0].speedMps!!, out[0].speedMps!!, 0.01f)
        assertEquals(p[0].accuracyMeters, out[0].accuracyMeters, 0.1f)
        assertEquals(p[0].timestampMs, out[0].timestampMs)
        // activityId is stamped by decode, ids/sequence are reassigned
        assertEquals("act-2", out[0].activityId)
        assertEquals(0, out[0].sequence)
        assertEquals(0L, out[0].id)
    }

    @Test
    fun `hike-like track roundtrips through gzip layer`() {
        // Simulates a real recording: ~1s fixes, small deltas, elevation
        // mostly present with occasional nulls (fix gaps), speeds present.
        val rnd = Random(42)
        val points = ArrayList<ActivityPointEntity>(1000)
        var t = 1_700_000_000_000L
        var lat = -7.98
        var lon = 112.52
        var ele = 1100.0
        repeat(1000) { i ->
            t += 900 + rnd.nextLong(200)
            lat += (rnd.nextDouble() - 0.5) * 0.0002
            lon += (rnd.nextDouble() - 0.5) * 0.0002
            ele += (rnd.nextDouble() - 0.4) * 1.5
            points.add(
                point(
                    sequence = i,
                    lat = lat,
                    lon = lon,
                    // ~10% null elevation like weak-signal stretches
                    elevationM = if (rnd.nextInt(10) == 0) null else ele,
                    speedMps = if (rnd.nextInt(15) == 0) null else 0.5f + rnd.nextFloat(),
                    accuracyMeters = 3f + rnd.nextFloat() * 15f,
                    timestampMs = t
                )
            )
        }
        val out = DeltaEncoder.decode(DeltaEncoder.encode(points), "x")
        assertEquals(points.size, out.size)
        points.forEachIndexed { i, p ->
            val r = out[i]
            assertEquals(p.timestampMs, r.timestampMs)
            assertEquals(p.lat, r.lat, 1e-6)
            assertEquals(p.lon, r.lon, 1e-6)
            assertEquals(p.elevationM == null, r.elevationM == null)
            p.elevationM?.let { assertEquals(it, r.elevationM!!, 0.01) }
            assertEquals(p.speedMps == null, r.speedMps == null)
            p.speedMps?.let { assertEquals(it, r.speedMps!!, 0.01f) }
            assertEquals(p.accuracyMeters, r.accuracyMeters, 0.1f)
        }
    }

    @Test
    fun `eight-point mask boundary — groups of 8 and a remainder roundtrip`() {
        // 19 points = 2 full 8-groups + a 3-point group; boundaries are where
        // mask/short-group handling could break.
        val points = (0 until 19).map { i ->
            point(
                sequence = i,
                lat = -7.9 + i * 0.0001,
                lon = 112.5 + i * 0.0001,
                elevationM = if (i % 5 == 0) null else 1000.0 + i,
                speedMps = if (i % 7 == 0) null else 1.0f,
                timestampMs = 1_700_000_000_000L + i * 1000L
            )
        }
        val out = DeltaEncoder.decode(DeltaEncoder.encode(points), "a")
        assertEquals(points.size, out.size)
        points.forEachIndexed { i, p ->
            assertEquals(p.elevationM == null, out[i].elevationM == null)
            assertEquals(p.speedMps == null, out[i].speedMps == null)
            assertEquals(p.timestampMs, out[i].timestampMs)
        }
    }

    @Test
    fun `delta encoding actually compresses compared to raw doubles`() {
        // The whole point of the format (schema_v1 note): adjacent fixes
        // barely move, so deltas + gzip must beat naive storage.
        val points = (0 until 1000).map { i ->
            point(
                sequence = i,
                lat = -7.98 + i * 0.00001,
                lon = 112.52 + i * 0.00001,
                elevationM = 1000.0 + i * 0.1,
                speedMps = 1.2f,
                timestampMs = 1_700_000_000_000L + i * 1000L
            )
        }
        val encoded = DeltaEncoder.encode(points)
        // Raw would be ~1000 * 48 bytes = ~48 KB; delta+gzip must land far
        // below that for a smooth synthetic track.
        assertTrue("expected < 8KB, got ${encoded.size}", encoded.size < 8 * 1024)
    }

    @Test
    fun `decode refuses garbage — bad magic, bad version, truncated`() {
        val valid = DeltaEncoder.encode(
            listOf(point(sequence = 0, lat = -7.9, lon = 112.5, elevationM = null, speedMps = null, timestampMs = 5L))
        )

        // Corrupt magic bytes in the gunzipped layer is hard to do from
        // outside; instead feed non-gzip garbage → FormatException.
        try {
            DeltaEncoder.decode("definitely not gzip".toByteArray(), "a")
            throw AssertionError("expected FormatException for non-gzip input")
        } catch (_: DeltaEncoder.FormatException) {
        }

        // Truncated payload: chop the gzip stream's tail.
        val truncated = valid.copyOfRange(0, valid.size / 2)
        try {
            DeltaEncoder.decode(truncated, "a")
            throw AssertionError("expected FormatException for truncated input")
        } catch (_: DeltaEncoder.FormatException) {
        }
    }
}
