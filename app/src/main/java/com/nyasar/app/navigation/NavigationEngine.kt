package com.nyasar.app.navigation

import com.nyasar.app.gpx.model.TrackPoint

data class NavigationState(
    val distanceTraveledMeters: Double,
    val remainingDistanceMeters: Double,
    val distanceFromTrackMeters: Double,
    val currentElevationM: Double?,
    val elevationGainSoFarM: Double,
    val currentSpeedKmh: Double?,
    val movingTimeMs: Long,
    val gpsAccuracyMeters: Float,
    /** Index of the track point nearest the user's current match — exposed
     *  so callers (P3E1: "Remaining Elevation") can compute gain over just
     *  the unwalked portion of the planned route without NavigationEngine
     *  needing to own that calculation itself. */
    val nearestTrackIndex: Int
)

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
    val speedMps: Float?,
    val accuracyMeters: Float,
    val timestampMs: Long,
    /** Degrees clockwise from true north, or null if the device can't report one
     *  (e.g. stationary, or no bearing-capable fix yet). Spec section 6/7:
     *  "Heading/direction jika tersedia" — optional, never blocks navigation. */
    val bearingDeg: Float? = null
)

/**
 * Owns per-session tracking state for one GPX track: distance traveled,
 * remaining distance, elevation gain, speed, moving time — everything
 * needed to show "where the user is relative to their planned GPX line",
 * without any off-route detection or warning logic (removed — this app
 * doesn't do turn-by-turn navigation; the user reads and follows the GPX
 * line themselves). Pure Kotlin, no Android framework or network
 * dependency — everything it needs (the GPX track, each GPS fix) is
 * passed in, so it runs identically whether GPS comes from a live device
 * or a replayed fixture in a unit test.
 */
class NavigationEngine(
    trackPoints: List<TrackPoint>
) {
    private val matcher = TrackMatcher(trackPoints)

    private var movingStartMs: Long? = null
    private var lastFixMs: Long? = null
    private var movingTimeAccumMs: Long = 0
    private var elevationGain: Double = 0.0
    private var lastElevation: Double? = null

    val totalDistanceMeters: Double get() = matcher.totalDistanceMeters

    /**
     * Distance along the planned track to the nearest point of [target],
     * reusing the same [TrackMatcher] the engine already matches GPS fixes
     * against (P3E3: "Next Waypoint" distance/ordering) — never a second,
     * separate distance model. Null if the track has fewer than 2 points
     * (matcher can't match against a single point or empty track).
     */
    fun distanceAlongTrackMeters(target: LatLng): Double? = matcher.match(target)?.distanceTraveledMeters

    /**
     * Full match (along-track position AND lateral distance from the
     * track) for an arbitrary point. P3E3 fix #1: merging user-created
     * waypoints into NEXT WAYPOINT needs the lateral distance too, to tell
     * "a shelter just off this trail" apart from "a waypoint the user
     * dropped on a completely different hike, that just happens to
     * project somewhere onto this track's infinite line". GPX waypoints
     * (the only caller of [distanceAlongTrackMeters] before this) never
     * needed that check — they're authored inside the route file itself,
     * so relevance to the track was never in question.
     */
    fun matchWaypoint(target: LatLng): TrackMatcher.MatchResult? = matcher.match(target)

    fun onGpsFix(fix: GpsFix): NavigationState? {
        val match = matcher.match(LatLng(fix.lat, fix.lon)) ?: return null

        // Simple moving-time accrual: count time since last fix only while
        // reported speed indicates actual movement, so a stationary rest
        // stop at a shelter doesn't inflate "moving time".
        lastFixMs?.let { prev ->
            val isMoving = (fix.speedMps ?: 0f) > 0.3f
            if (isMoving) movingTimeAccumMs += (fix.timestampMs - prev).coerceAtLeast(0)
        }
        lastFixMs = fix.timestampMs

        fix.elevationM?.let { e ->
            lastElevation?.let { prev ->
                val delta = e - prev
                if (delta > 2.0) elevationGain += delta // same 2m noise floor as ElevationStats
            }
            lastElevation = e
        }

        return NavigationState(
            distanceTraveledMeters = match.distanceTraveledMeters,
            remainingDistanceMeters = match.remainingDistanceMeters,
            distanceFromTrackMeters = match.distanceFromTrackMeters,
            currentElevationM = fix.elevationM,
            elevationGainSoFarM = elevationGain,
            currentSpeedKmh = fix.speedMps?.let { it * 3.6 },
            movingTimeMs = movingTimeAccumMs,
            gpsAccuracyMeters = fix.accuracyMeters,
            nearestTrackIndex = match.nearestSegmentIndex
        )
    }

    fun reset() {
        movingTimeAccumMs = 0
        elevationGain = 0.0
        lastElevation = null
        lastFixMs = null
        movingStartMs = null
    }
}
