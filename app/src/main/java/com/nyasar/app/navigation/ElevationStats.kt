package com.nyasar.app.navigation

import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.ui.components.ElevationPoint

data class ElevationSummary(
    val gainM: Double,
    val lossM: Double,
    val highestM: Double?,
    val lowestM: Double?
)

object ElevationStats {

    /**
     * Ignores tiny fluctuations below [noiseThresholdM] before accumulating
     * gain/loss — raw barometric/GPS elevation is noisy enough that summing
     * every up/down tick wildly overstates total gain.
     */
    fun summarize(points: List<TrackPoint>, noiseThresholdM: Double = 2.0): ElevationSummary? {
        val elevations = points.mapNotNull { it.elevationM }
        if (elevations.isEmpty()) return null

        var gain = 0.0
        var loss = 0.0
        var last = elevations.first()
        var pendingDelta = 0.0

        for (e in elevations.drop(1)) {
            val delta = e - last
            pendingDelta += delta
            if (Math.abs(pendingDelta) >= noiseThresholdM) {
                if (pendingDelta > 0) gain += pendingDelta else loss += -pendingDelta
                pendingDelta = 0.0
            }
            last = e
        }

        return ElevationSummary(
            gainM = gain,
            lossM = loss,
            highestM = elevations.max(),
            lowestM = elevations.min()
        )
    }

    /**
     * Converts a track into ElevationProfile's chart input: cumulative
     * distance walked so far, paired with elevation. P3E1: chart previously
     * plotted elevation against point *index*, which distorts the shape
     * whenever points aren't evenly spaced — this restores a real distance
     * X-axis using the same great-circle math TrackMatcher/RecordingEngine
     * already use elsewhere, so distances here are consistent with the
     * distance stats shown next to the chart.
     *
     * Points without elevation are dropped (can't plot what isn't there),
     * but distance still accumulates across them so the remaining points'
     * X positions stay correct relative to the whole track.
     */
    fun toElevationProfile(points: List<TrackPoint>): List<ElevationPoint> {
        var cumulative = 0.0
        var last: TrackPoint? = null
        val result = mutableListOf<ElevationPoint>()
        for (p in points) {
            last?.let { prev ->
                cumulative += GeoMath.distanceMeters(LatLng(prev.lat, prev.lon), LatLng(p.lat, p.lon))
            }
            last = p
            p.elevationM?.let { e -> result.add(ElevationPoint(cumulative, e, p.lat, p.lon)) }
        }
        return result
    }
}
