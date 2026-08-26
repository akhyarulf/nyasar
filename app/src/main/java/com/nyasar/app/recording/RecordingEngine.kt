package com.nyasar.app.recording

import com.nyasar.app.navigation.GeoMath
import com.nyasar.app.navigation.GpsFix
import com.nyasar.app.navigation.LatLng

enum class RecordingStatus { IDLE, RECORDING, PAUSED, STOPPED }

data class RecordingState(
    val status: RecordingStatus,
    val distanceMeters: Double,
    val movingTimeMs: Long,
    val elapsedTimeMs: Long,
    val currentSpeedKmh: Double?,
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val currentElevationM: Double?,
    val pointCount: Int
)

/**
 * Owns per-session state for one recording. Pure Kotlin, no Android/DB
 * dependency — mirrors NavigationEngine's design (same reasoning: runs
 * identically on a live device or a replayed fixture in a unit test).
 *
 * Deliberately separate from NavigationEngine rather than reusing it:
 * NavigationEngine matches incoming fixes against an *existing* track
 * (TrackMatcher needs points up front). RecordingEngine does the opposite —
 * it has no track yet, it's the thing producing one. The two are designed
 * to run side by side off the same GpsFix stream (recording + navigation
 * simultaneously, per spec), not to replace each other.
 */
class RecordingEngine {

    companion object {
        private const val MIN_MOVEMENT_METERS = 3.0
        /** P3I §12 (GPS jump protection): only catches genuine GPS
         *  teleports (a fix landing kilometers away with no plausible
         *  travel time) — deliberately NOT tuned as a "walking speed"
         *  filter. 60 m/s (~216 km/h) is far beyond any hiking, running, or
         *  even vehicle-assisted trail speed, so it never touches
         *  legitimate fast movement or GPS's normal several-meter jitter;
         *  it only fires for the spec's example case (100m, 150m, then a
         *  sudden 20km jump). Spec explicitly warns against an aggressive
         *  filter that would delete valid mountain track segments — this
         *  threshold is set to only catch the unambiguous case. */
        private const val MAX_PLAUSIBLE_SPEED_MPS = 60.0
        /** P3I audit fix (§11): was 30f as a hard REJECT threshold — every
         *  fix worse than 30m accuracy was silently dropped, no point
         *  written at all. Spec §11 explicitly requires the opposite:
         *  "Jangan langsung membuang semua GPS fix hanya karena accuracy
         *  besar... gunakan accuracy sebagai status/warning" — with an
         *  explicit example of exactly the scenario this threshold was
         *  breaking: "Hiking di lembah/gunung dapat menghasilkan accuracy
         *  buruk sementara". A canopy/valley stretch could see EVERY fix
         *  rejected under the old threshold — a real gap in the recorded
         *  track with no indication why beyond the separate GPS-health
         *  WEAK badge (which reports connectivity health, not "and also
         *  nothing is being written right now"). 30m is still meaningful —
         *  RecordingService's own watchdog uses it to flag WEAK status —
         *  it's just no longer also a silent hard reject. 100m only
         *  rejects fixes accurate enough to be genuinely useless (e.g. a
         *  cold-start fix hundreds of meters off), not merely "worse than
         *  ideal", which is the distinction spec §11 draws. */
        private const val MAX_ACCEPTABLE_ACCURACY_METERS = 100f
    }

    private var status: RecordingStatus = RecordingStatus.IDLE

    private var distanceMeters = 0.0
    private var movingTimeAccumMs = 0L
    private var elapsedTimeAccumMs = 0L
    private var maxSpeedKmh = 0.0
    private var elevationGain = 0.0
    private var elevationLoss = 0.0

    private var lastPoint: LatLng? = null
    private var lastFixMs: Long? = null
    private var lastElevation: Double? = null
    private var pointCount = 0

    /** Titik terakhir yang lolos filter akurasi, untuk dipersist oleh
     *  pemanggil (service). Engine sendiri tidak menyentuh database. */
    var lastAcceptedFix: GpsFix? = null
        private set

    fun start() {
        check(status == RecordingStatus.IDLE) { "start() hanya valid dari IDLE, status saat ini: $status" }
        status = RecordingStatus.RECORDING
    }

    fun pause() {
        if (status != RecordingStatus.RECORDING) return
        status = RecordingStatus.PAUSED
        lastFixMs = null // reset moving-time accrual supaya jeda pause tidak terhitung
    }

    fun resume() {
        if (status != RecordingStatus.PAUSED) return
        status = RecordingStatus.RECORDING
        lastFixMs = null
    }

    fun stop(): RecordingState {
        status = RecordingStatus.STOPPED
        return currentState()
    }

    /**
     * Proses satu GPS fix baru. Return null kalau fix ditolak (akurasi
     * benar-benar tidak berguna, atau engine sedang tidak RECORDING) —
     * pemanggil tidak boleh menulis titik ke database untuk fix yang
     * ditolak.
     *
     * accuracyThresholdMeters default 100m (P3I audit — was 30m, which
     * silently dropped every fix in a canopy/valley stretch; see
     * MAX_ACCEPTABLE_ACCURACY_METERS doc above for the spec §11
     * reasoning). This only rejects fixes accurate enough to be genuinely
     * useless; 30m-100m fixes are now accepted and recorded, with only
     * their WEAK status surfaced separately (RecordingService's watchdog).
     */
    fun onGpsFix(fix: GpsFix, accuracyThresholdMeters: Float = MAX_ACCEPTABLE_ACCURACY_METERS): RecordingState? {
        if (status != RecordingStatus.RECORDING) return null
        if (fix.accuracyMeters > accuracyThresholdMeters) return null

        val point = LatLng(fix.lat, fix.lon)

        // P3I §12: reject a fix implying an implausible sustained speed
        // (GPS jump/teleport) BEFORE it can corrupt distance/speed stats —
        // but only skip this one fix, never move lastPoint/lastFixMs to it.
        // That's the key difference from a track-cleanup pass: the next
        // fix is still compared against the last *good* point, so if the
        // jump was transient (one bad fix, then GPS recovers nearby), the
        // real distance from before the jump to after it is still computed
        // correctly rather than losing that whole segment.
        lastPoint?.let { prev ->
            lastFixMs?.let { prevMs ->
                val jumpDistance = GeoMath.distanceMeters(prev, point)
                val elapsedS = (fix.timestampMs - prevMs) / 1000.0
                if (elapsedS > 0 && jumpDistance / elapsedS > MAX_PLAUSIBLE_SPEED_MPS) {
                    return null
                }
            }
        }

        // Noise floor for distance, same idea as the elevation one below.
        // Without this, GPS drift while genuinely stationary (typically
        // 2-8m even with good accuracy) accumulates into real distance —
        // e.g. "0.01 km jarak tempuh" while "00:00:00 waktu bergerak" and
        // speed ~0 km/h, which is exactly drift, not movement. Only commit
        // distance (and advance the reference point) once displacement
        // since the last accepted point clears the floor; sub-floor fixes
        // are simply ignored rather than accumulated, so slow real motion
        // still adds up correctly over a few fixes, it's just quantized to
        // ~3m steps instead of every single GPS wobble.
        lastPoint?.let { prev ->
            val delta = GeoMath.distanceMeters(prev, point)
            if (delta >= MIN_MOVEMENT_METERS) {
                distanceMeters += delta
                lastPoint = point
            }
        } ?: run { lastPoint = point }

        lastFixMs?.let { prev ->
            val isMoving = (fix.speedMps ?: 0f) > 0.3f
            if (isMoving) movingTimeAccumMs += (fix.timestampMs - prev).coerceAtLeast(0)
            elapsedTimeAccumMs += (fix.timestampMs - prev).coerceAtLeast(0)
        }
        lastFixMs = fix.timestampMs

        fix.speedMps?.let { speedMps ->
            val kmh = speedMps * 3.6
            if (kmh > maxSpeedKmh) maxSpeedKmh = kmh
        }

        fix.elevationM?.let { e ->
            lastElevation?.let { prev ->
                val delta = e - prev
                // Noise floor 2m sama seperti ElevationStats/NavigationEngine,
                // supaya angka gain/loss activity konsisten dengan yang
                // dipakai di tempat lain untuk data GPX yang sudah ada.
                when {
                    delta > 2.0 -> elevationGain += delta
                    delta < -2.0 -> elevationLoss += -delta
                }
            }
            lastElevation = e
        }

        pointCount += 1
        lastAcceptedFix = fix

        return currentState()
    }

    fun currentState(): RecordingState {
        val hours = movingTimeAccumMs / 3_600_000.0
        val avgSpeedKmh = if (hours > 0) (distanceMeters / 1000.0) / hours else null

        return RecordingState(
            status = status,
            distanceMeters = distanceMeters,
            movingTimeMs = movingTimeAccumMs,
            elapsedTimeMs = elapsedTimeAccumMs,
            currentSpeedKmh = lastAcceptedFix?.speedMps?.let { it * 3.6 },
            avgSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            elevationGainM = elevationGain,
            elevationLossM = elevationLoss,
            currentElevationM = lastAcceptedFix?.elevationM,
            pointCount = pointCount
        )
    }
}
