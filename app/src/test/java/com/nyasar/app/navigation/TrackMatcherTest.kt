package com.nyasar.app.navigation

import com.nyasar.app.gpx.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {

    // A straight ~1.1km north-south line along the same longitude for simple math.
    private val straightTrack = listOf(
        TrackPoint(lat = -7.9000, lon = 112.5300),
        TrackPoint(lat = -7.9050, lon = 112.5300),
        TrackPoint(lat = -7.9100, lon = 112.5300)
    )

    @Test
    fun `point exactly on track has near-zero distance`() {
        val matcher = TrackMatcher(straightTrack)
        val result = matcher.match(LatLng(-7.9050, 112.5300))
        assertTrue(result != null)
        assertTrue(result!!.distanceFromTrackMeters < 1.0)
    }

    @Test
    fun `distance traveled increases along the track`() {
        val matcher = TrackMatcher(straightTrack)
        val early = matcher.match(LatLng(-7.9010, 112.5300))!!
        val late = matcher.match(LatLng(-7.9090, 112.5300))!!
        assertTrue(late.distanceTraveledMeters > early.distanceTraveledMeters)
    }

    @Test
    fun `remaining plus traveled roughly equals total`() {
        val matcher = TrackMatcher(straightTrack)
        val result = matcher.match(LatLng(-7.9050, 112.5300))!!
        val sum = result.distanceTraveledMeters + result.remainingDistanceMeters
        assertEquals(matcher.totalDistanceMeters, sum, 0.5)
    }

    @Test
    fun `point far from track reports large distance`() {
        val matcher = TrackMatcher(straightTrack)
        val result = matcher.match(LatLng(-7.9050, 112.5450))!! // ~1.6km east
        assertTrue(result.distanceFromTrackMeters > 500.0)
    }
}
