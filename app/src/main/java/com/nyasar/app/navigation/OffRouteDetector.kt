package com.nyasar.app.navigation

enum class RouteStatus { ON_ROUTE, WARNING, OFF_ROUTE }

data class OffRouteConfig(
    val onRouteThresholdM: Double = 20.0,
    val warningThresholdM: Double = 50.0,
    /** How many consecutive bad readings before we actually declare OFF_ROUTE. */
    val consecutiveReadingsRequired: Int = 3,
    /**
     * If reported GPS accuracy (radius, meters) is worse than this, we
     * inflate the effective distance thresholds instead of firing a
     * warning — a single noisy fix should never trigger "off route".
     */
    val poorAccuracyThresholdM: Float = 25f
)

/**
 * Stateful off-route detector: call [update] on every GPS fix. It does NOT
 * flip to OFF_ROUTE on a single bad reading — per spec, a lone deviating
 * GPS point must not trigger a warning. It requires several consecutive
 * readings past the threshold, and it relaxes thresholds automatically
 * when reported GPS accuracy is poor.
 */
class OffRouteDetector(private val config: OffRouteConfig = OffRouteConfig()) {

    private var consecutiveOffCount = 0
    private var lastStatus = RouteStatus.ON_ROUTE

    /**
     * @param distanceFromTrackMeters output of [TrackMatcher.match]
     * @param gpsAccuracyMeters accuracy radius reported by Android's Location API
     */
    fun update(distanceFromTrackMeters: Double, gpsAccuracyMeters: Float): RouteStatus {
        // Poor accuracy → widen thresholds so we don't blame the trail for a noisy fix.
        val accuracyPadding = (gpsAccuracyMeters - config.poorAccuracyThresholdM)
            .coerceAtLeast(0f)
            .toDouble()

        val onRouteThreshold = config.onRouteThresholdM + accuracyPadding
        val warningThreshold = config.warningThresholdM + accuracyPadding

        val instantStatus = when {
            distanceFromTrackMeters <= onRouteThreshold -> RouteStatus.ON_ROUTE
            distanceFromTrackMeters <= warningThreshold -> RouteStatus.WARNING
            else -> RouteStatus.OFF_ROUTE
        }

        if (instantStatus == RouteStatus.OFF_ROUTE) {
            consecutiveOffCount++
        } else {
            consecutiveOffCount = 0
        }

        lastStatus = when {
            consecutiveOffCount >= config.consecutiveReadingsRequired -> RouteStatus.OFF_ROUTE
            instantStatus == RouteStatus.WARNING -> RouteStatus.WARNING
            else -> RouteStatus.ON_ROUTE
        }
        return lastStatus
    }

    fun reset() {
        consecutiveOffCount = 0
        lastStatus = RouteStatus.ON_ROUTE
    }
}
