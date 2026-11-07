package com.nyasar.app.navigation

/**
 * Satu fix GPS yang sudah dinormalisasi dari LocationRepository, dipakai
 * bersama oleh RecordingEngine dan layar yang menggambar posisi user.
 */
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
