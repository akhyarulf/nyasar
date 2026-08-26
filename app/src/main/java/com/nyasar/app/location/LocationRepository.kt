package com.nyasar.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.nyasar.app.navigation.GpsFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin wrapper around FusedLocationProviderClient. Deliberately does NOT
 * depend on internet — fused location on a real device still resolves via
 * on-board GPS when offline (network/wifi location assistance is best-
 * effort only, not required), and PRIORITY_HIGH_ACCURACY is requested
 * specifically so navigation keeps working with no signal, per spec
 * section 9/14 (offline-first, GPS on-device).
 */
class LocationRepository(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * P3I battery audit: interval was 1000ms with PRIORITY_HIGH_ACCURACY and
     * no minimum distance filter — GPS chip stays on continuously and the
     * app processes a fix every second even while the user is standing
     * still (e.g. taking a photo, checking the map). For hiking, 3s is
     * still responsive enough for track fidelity and off-route detection,
     * and the distance filter (3m) skips redundant processing when
     * genuinely stationary, without discarding real movement — someone
     * walking at even a slow 1 km/h covers ~0.8m/s, so 3m is reached well
     * within one interval tick during actual movement.
     */
    @SuppressLint("MissingPermission") // caller is required to have checked permission first
    fun observeLocation(updateIntervalMs: Long = 3000L): Flow<GpsFix> = callbackFlow {
        // BUG FIX: Request a fast initial location fix first, then switch to
        // the regular interval. This ensures the user gets their position
        // quickly on first access (e.g., opening the app or starting recording)
        // without sacrificing battery efficiency during ongoing tracking.
        val fastRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(1) // Only one fast fix, then we switch to the regular interval
            .build()
        
        val fastCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                trySend(
                    GpsFix(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        elevationM = if (loc.hasAltitude()) loc.altitude else null,
                        speedMps = if (loc.hasSpeed()) loc.speed else null,
                        accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 9999f,
                        timestampMs = loc.time,
                        bearingDeg = if (loc.hasBearing()) loc.bearing else null
                    )
                )
            }
        }
        
        // Request a fast initial fix
        client.requestLocationUpdates(fastRequest, fastCallback, Looper.getMainLooper())
        
        // Now set up the regular interval callback
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalMs)
            .setMinUpdateIntervalMillis(updateIntervalMs / 2)
            .setMinUpdateDistanceMeters(3f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                trySend(
                    GpsFix(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        elevationM = if (loc.hasAltitude()) loc.altitude else null,
                        speedMps = if (loc.hasSpeed()) loc.speed else null,
                        accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 9999f,
                        timestampMs = loc.time,
                        // GPS-derived bearing is only meaningful while actually moving;
                        // hasBearing() is false when stationary, which is fine — the
                        // marker simply keeps its last known heading (handled in UI).
                        bearingDeg = if (loc.hasBearing()) loc.bearing else null
                    )
                )
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose {
            client.removeLocationUpdates(callback)
            client.removeLocationUpdates(fastCallback)
        }
    }

    fun hasLocationPermission(): Boolean {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
