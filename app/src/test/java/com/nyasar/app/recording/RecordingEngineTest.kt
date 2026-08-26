package com.nyasar.app.recording

import com.nyasar.app.navigation.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEngineTest {

    private fun fix(
        lat: Double,
        lon: Double,
        elevationM: Double? = null,
        speedMps: Float? = null,
        accuracyMeters: Float = 5f,
        timestampMs: Long
    ) = GpsFix(lat, lon, elevationM, speedMps, accuracyMeters, timestampMs)

    @Test
    fun `fix before start is ignored`() {
        val engine = RecordingEngine()
        val result = engine.onGpsFix(fix(0.0, 0.0, timestampMs = 0))
        assertNull(result)
    }

    @Test
    fun `distance accumulates across fixes while recording`() {
        val engine = RecordingEngine()
        engine.start()

        engine.onGpsFix(fix(0.0, 0.0, speedMps = 1.5f, timestampMs = 0))
        // 60s gap: ~111m / 60s ≈ 1.85 m/s, an ordinary walking pace —
        // avoids implying an implausible speed now that onGpsFix rejects
        // GPS-jump-like speeds (P3I §12); this test is about distance
        // accumulation, not about exercising that specific speed.
        val state = engine.onGpsFix(fix(0.0, 0.001, speedMps = 1.5f, timestampMs = 60_000))!!

        // ~111m per 0.001 deg longitude at the equator
        assertTrue("expected distance near 111m, was ${state.distanceMeters}", state.distanceMeters in 100.0..120.0)
        assertEquals(2, state.pointCount)
    }

    @Test
    fun `fixes with poor accuracy are rejected`() {
        val engine = RecordingEngine()
        engine.start()

        engine.onGpsFix(fix(0.0, 0.0, timestampMs = 0))
        val rejected = engine.onGpsFix(fix(0.0, 0.001, accuracyMeters = 999f, timestampMs = 1000))

        assertNull(rejected)
        assertEquals(1, engine.currentState().pointCount)
    }

    @Test
    fun `pause stops moving time accrual and resume continues distance`() {
        val engine = RecordingEngine()
        engine.start()

        // Timestamps spaced for plausible walking speed (~1.85 m/s per
        // 111m/60s step) now that onGpsFix rejects GPS-jump-like speeds
        // (P3I §12) — this test is about pause/resume behavior, not speed.
        engine.onGpsFix(fix(0.0, 0.0, speedMps = 1.5f, timestampMs = 0))
        engine.onGpsFix(fix(0.0, 0.001, speedMps = 1.5f, timestampMs = 60_000))
        val distanceBeforePause = engine.currentState().distanceMeters

        engine.pause()
        // fix while paused must be ignored entirely (no distance, no point count bump)
        val duringPause = engine.onGpsFix(fix(0.0, 0.002, speedMps = 1.5f, timestampMs = 120_000))
        assertNull(duringPause)

        engine.resume()
        val afterResume = engine.onGpsFix(fix(0.0, 0.003, speedMps = 1.5f, timestampMs = 180_000))!!

        assertTrue(afterResume.distanceMeters > distanceBeforePause)
    }

    @Test
    fun `elevation gain and loss respect noise floor`() {
        val engine = RecordingEngine()
        engine.start()

        engine.onGpsFix(fix(0.0, 0.0, elevationM = 100.0, timestampMs = 0))
        engine.onGpsFix(fix(0.0, 0.0, elevationM = 100.5, timestampMs = 1000)) // +0.5, noise
        val state = engine.onGpsFix(fix(0.0, 0.0, elevationM = 105.0, timestampMs = 2000))!! // +4.5 from 100.5

        assertEquals(5.0, state.elevationGainM, 0.01)
        assertEquals(0.0, state.elevationLossM, 0.01)
    }

    @Test
    fun `max speed tracks the highest reported speed`() {
        val engine = RecordingEngine()
        engine.start()

        // Timestamps spaced for plausible speed — this test is about which
        // reported speedMps value wins as the max, not about exercising
        // the GPS-jump filter (P3I §12), so keep implied ground speed sane.
        engine.onGpsFix(fix(0.0, 0.0, speedMps = 1.0f, timestampMs = 0))
        engine.onGpsFix(fix(0.0, 0.001, speedMps = 3.0f, timestampMs = 60_000))
        val state = engine.onGpsFix(fix(0.0, 0.002, speedMps = 1.5f, timestampMs = 120_000))!!

        assertEquals(3.0 * 3.6, state.maxSpeedKmh, 0.01)
    }

    @Test
    fun `stop transitions status and returns final state`() {
        val engine = RecordingEngine()
        engine.start()
        engine.onGpsFix(fix(0.0, 0.0, timestampMs = 0))

        val finalState = engine.stop()

        assertEquals(RecordingStatus.STOPPED, finalState.status)
        // fix after stop must be ignored
        assertNull(engine.onGpsFix(fix(0.0, 0.001, timestampMs = 1000)))
    }

    @Test
    fun `gps jump implying implausible speed is rejected`() {
        val engine = RecordingEngine()
        engine.start()

        engine.onGpsFix(fix(0.0, 0.0, timestampMs = 0))
        // ~20km away one second later — implies ~20,000 m/s, nowhere near plausible
        val jumped = engine.onGpsFix(fix(0.0, 0.2, timestampMs = 1000))
        assertNull(jumped)
        // rejected fix must not have moved lastPoint or bumped pointCount
        assertEquals(1, engine.currentState().pointCount)
    }

    @Test
    fun `gps jump does not corrupt distance for the next legitimate fix`() {
        val engine = RecordingEngine()
        engine.start()

        engine.onGpsFix(fix(0.0, 0.0, timestampMs = 0))
        engine.onGpsFix(fix(0.0, 0.2, timestampMs = 1000)) // rejected jump
        // Next fix is a normal, nearby step from the ORIGINAL point — since
        // the jump never moved lastPoint, this must compute a small,
        // sane distance rather than "back from 20km away".
        val state = engine.onGpsFix(fix(0.0, 0.001, speedMps = 1.0f, timestampMs = 2000))!!
        assertTrue("expected a small sane distance, was ${state.distanceMeters}", state.distanceMeters < 200.0)
    }

    @Test
    fun `fast but plausible hiking speed is not rejected as a jump`() {
        val engine = RecordingEngine()
        engine.start()

        engine.onGpsFix(fix(0.0, 0.0, speedMps = 3.0f, timestampMs = 0))
        // ~111m in 10s = ~11 m/s, fast trail running/downhill but not a
        // teleport — must be accepted, not filtered as a jump.
        val state = engine.onGpsFix(fix(0.0, 0.001, speedMps = 3.0f, timestampMs = 10_000))
        assertNotNull(state)
    }
}
