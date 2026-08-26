package com.nyasar.app.navigation

import com.nyasar.app.gpx.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationStatsTest {

    @Test
    fun `no elevation data returns null`() {
        val points = listOf(TrackPoint(0.0, 0.0, elevationM = null))
        assertNull(ElevationStats.summarize(points))
    }

    @Test
    fun `simple monotonic climb sums correctly`() {
        val points = listOf(
            TrackPoint(0.0, 0.0, elevationM = 100.0),
            TrackPoint(0.0, 0.0, elevationM = 110.0),
            TrackPoint(0.0, 0.0, elevationM = 125.0)
        )
        val summary = ElevationStats.summarize(points, noiseThresholdM = 2.0)!!
        assertEquals(25.0, summary.gainM, 0.01)
        assertEquals(0.0, summary.lossM, 0.01)
        assertEquals(125.0, summary.highestM!!, 0.01)
        assertEquals(100.0, summary.lowestM!!, 0.01)
    }

    @Test
    fun `tiny fluctuations below noise threshold are ignored`() {
        val points = listOf(
            TrackPoint(0.0, 0.0, elevationM = 100.0),
            TrackPoint(0.0, 0.0, elevationM = 100.5), // +0.5, noise
            TrackPoint(0.0, 0.0, elevationM = 100.8), // +0.3, still under 2m combined... but combined = 0.8
            TrackPoint(0.0, 0.0, elevationM = 103.0)  // pushes pending over threshold
        )
        val summary = ElevationStats.summarize(points, noiseThresholdM = 2.0)!!
        assertEquals(3.0, summary.gainM, 0.01)
    }

    @Test
    fun `toElevationProfile accumulates real distance not point index`() {
        // ~0.001 deg longitude at the equator is ~111m — points spaced
        // unevenly so an index-based X axis (the old behavior) would have
        // produced a visibly different, wrong shape than a distance-based one.
        val points = listOf(
            TrackPoint(0.0, 0.0, elevationM = 100.0),
            TrackPoint(0.0, 0.001, elevationM = 110.0),  // ~111m from previous
            TrackPoint(0.0, 0.002, elevationM = 120.0)   // ~111m from previous
        )
        val profile = ElevationStats.toElevationProfile(points)
        assertEquals(3, profile.size)
        assertEquals(0.0, profile[0].distanceMeters, 0.01)
        assertTrue(profile[1].distanceMeters in 100.0..120.0)
        assertTrue(profile[2].distanceMeters in 200.0..240.0)
        assertEquals(120.0, profile[2].elevationM, 0.01)
    }

    @Test
    fun `toElevationProfile skips points without elevation but keeps distance continuous`() {
        val points = listOf(
            TrackPoint(0.0, 0.0, elevationM = 100.0),
            TrackPoint(0.0, 0.001, elevationM = null), // dropped from output, distance still counted
            TrackPoint(0.0, 0.002, elevationM = 120.0)
        )
        val profile = ElevationStats.toElevationProfile(points)
        assertEquals(2, profile.size)
        // second kept point's distance includes the gap through the dropped point
        assertTrue(profile[1].distanceMeters in 200.0..240.0)
    }
}
