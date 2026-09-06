package com.nyasar.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class OffRouteDetectorTest {

    @Test
    fun `single noisy reading past threshold does not trigger off-route`() {
        val detector = OffRouteDetector(OffRouteConfig(consecutiveReadingsRequired = 3))
        val status = detector.update(distanceFromTrackMeters = 80.0, gpsAccuracyMeters = 5f)
        assertEquals(RouteStatus.WARNING, status) // WARNING immediately, but not OFF_ROUTE yet
    }

    @Test
    fun `three consecutive far readings trigger off-route`() {
        val detector = OffRouteDetector(OffRouteConfig(consecutiveReadingsRequired = 3))
        detector.update(80.0, 5f)
        detector.update(85.0, 5f)
        val status = detector.update(90.0, 5f)
        assertEquals(RouteStatus.OFF_ROUTE, status)
    }

    @Test
    fun `returning on track resets consecutive counter`() {
        val detector = OffRouteDetector(OffRouteConfig(consecutiveReadingsRequired = 3))
        detector.update(80.0, 5f)
        detector.update(85.0, 5f)
        detector.update(5.0, 5f) // back on route
        val status = detector.update(90.0, 5f)
        assertEquals(RouteStatus.WARNING, status) // counter reset, needs 3 more
    }

    @Test
    fun `poor gps accuracy widens thresholds`() {
        val detector = OffRouteDetector(OffRouteConfig(poorAccuracyThresholdM = 25f))
        // 40m off track, but accuracy is terrible (60m) -> padding = 35m -> effective on-route threshold = 55m
        val status = detector.update(distanceFromTrackMeters = 40.0, gpsAccuracyMeters = 60f)
        assertEquals(RouteStatus.ON_ROUTE, status)
    }

    @Test
    fun `within on-route threshold stays on route`() {
        val detector = OffRouteDetector()
        val status = detector.update(distanceFromTrackMeters = 10.0, gpsAccuracyMeters = 5f)
        assertEquals(RouteStatus.ON_ROUTE, status)
    }
}
