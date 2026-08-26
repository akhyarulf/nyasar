package com.nyasar.app.gpx.model

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val elevationM: Double? = null,
    val timestampEpochMs: Long? = null
)

data class GpxWaypoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevationM: Double? = null,
    val description: String? = null
)

/** A single continuous line the user is expected to follow (a <trk>/<trkseg> or <rte>). */
data class GpxTrack(
    val name: String,
    val points: List<TrackPoint>
)

/** Fully parsed result of one .gpx file — this is what the rest of the app works with. */
data class GpxDocument(
    val name: String,
    val tracks: List<GpxTrack>,
    val waypoints: List<GpxWaypoint>
) {
    /** Flattened points across all tracks, in order — what off-route matching runs against. */
    val allTrackPoints: List<TrackPoint> get() = tracks.flatMap { it.points }
}
