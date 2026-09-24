package com.nyasar.app.ui.components

import com.nyasar.app.data.db.ActivityPointEntity
import com.nyasar.app.ui.history.calculateGpsQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitsTableTest {
    private fun point(sequence: Int, lat: Double, timestampMs: Long, accuracy: Float, speed: Float?) =
        ActivityPointEntity(
            sequence = sequence,
            activityId = "activity",
            lat = lat,
            lon = 0.0,
            elevationM = 100.0,
            speedMps = speed,
            accuracyMeters = accuracy,
            timestampMs = timestampMs
        )

    @Test
    fun `pace uses moving time and scales partial distance`() {
        assertEquals("5:00", formatSplitPace(5 * 60_000L, 1000.0))
        assertEquals("10:00", formatSplitPace(5 * 60_000L, 500.0))
    }

    @Test
    fun `split excludes stationary elapsed time from pace`() {
        val points = listOf(
            point(0, 0.0, 0L, 5f, 1.0f),
            point(1, 0.005, 5 * 60_000L, 8f, 0.0f),
            point(2, 0.01, 8 * 60_000L, 8f, 1.0f)
        )
        val splits = computeSplits(points)
        assertTrue(splits.isNotEmpty())
        val split = splits.first()
        assertTrue(split.timeMs in 1L until 8 * 60_000L)
        assertTrue(split.movingTimeMs in 1L until split.timeMs)
        assertTrue(split.distanceMeters in 1_000.0..1_100.0)
    }

    @Test
    fun `gps quality summarizes accuracy and largest fix gap`() {
        val metrics = calculateGpsQuality(listOf(
            point(0, 0.0, 0L, 5f, null),
            point(1, 0.001, 3_000L, 40f, null),
            point(2, 0.002, 20_000L, 120f, null)
        ))
        assertEquals(3, metrics.pointCount)
        assertEquals(55f, metrics.averageAccuracyMeters)
        assertEquals(2, metrics.weakPointCount)
        assertEquals(1, metrics.poorPointCount)
        assertEquals(17_000L, metrics.largestGapMs)
    }
}
