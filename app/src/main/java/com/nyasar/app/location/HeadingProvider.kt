package com.nyasar.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

/**
 * Spec P3 §37/§38: heading for Heading-Up mode must come from device
 * orientation (rotation vector sensor), not raw GPS bearing — GPS bearing
 * is only reliable while actually moving and is noisy/absent when
 * stationary, which was causing (or would cause) the map to "muter liar"
 * that the spec explicitly forbids.
 *
 * Priority order per §37:
 *  1. TYPE_ROTATION_VECTOR (fused, most stable) if the device has it.
 *  2. Fallback: TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD via
 *     getRotationMatrix, for devices without a rotation vector sensor.
 *  3. If neither sensor is present at all, this flow simply emits nothing
 *     — callers (NavigationViewModel/RecordingViewModel) already fall back
 *     to GPS bearing when device heading is null, only while the GPS
 *     speed indicates actual movement (see HeadingSource in each ViewModel).
 *
 * Smoothing: low-pass filtered with circular-mean handling of the 0/360
 * wraparound, so heading near due north doesn't jitter between 359° and
 * 1°. This is the "filter/smoothing" §11/§38 requires.
 *
 * Lifecycle-safe: sensors are registered only while the returned Flow has
 * an active collector (callbackFlow + awaitClose unregisters), so nothing
 * drains battery reading orientation while no screen using it is visible.
 */
class HeadingProvider(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** True if at least one usable orientation sensor exists on this device. */
    fun hasOrientationSensor(): Boolean {
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        return rotationVector != null || (accel != null && mag != null)
    }

    /**
     * Emits smoothed device heading in degrees clockwise from true north,
     * corrected for current screen rotation (so heading is correct whether
     * the phone is held in the orientation the app is locked to). Emits
     * nothing if no orientation sensor exists on this device — see
     * [hasOrientationSensor].
     */
    fun observeHeading(smoothingFactor: Float = 0.15f): Flow<Float> = callbackFlow {
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (rotationVectorSensor == null && (accelSensor == null || magSensor == null)) {
            close()
            return@callbackFlow
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotationMatrix = FloatArray(9)
        val orientationValues = FloatArray(3)
        var lastAccel: FloatArray? = null
        var lastMag: FloatArray? = null

        // Circular smoothing state, tracked as a unit vector average rather
        // than a plain float average, so the 359°→1° wraparound never
        // produces a spurious swing through 180°.
        var smoothedSin = 0f
        var smoothedCos = 1f
        var initialized = false

        fun screenRotationDeg(): Int = when (windowManager?.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        fun emitHeading(rawDegrees: Float) {
            val corrected = (rawDegrees + screenRotationDeg() + 360f) % 360f
            val rad = corrected * PI.toFloat() / 180f
            if (!initialized) {
                smoothedSin = sin(rad)
                smoothedCos = cos(rad)
                initialized = true
            } else {
                smoothedSin += smoothingFactor * (sin(rad) - smoothedSin)
                smoothedCos += smoothingFactor * (cos(rad) - smoothedCos)
            }
            val smoothedDeg = (atan2(smoothedSin, smoothedCos) * 180f / PI.toFloat() + 360f) % 360f
            trySend(smoothedDeg)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientationValues)
                        val azimuthDeg = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                        emitHeading((azimuthDeg + 360f) % 360f)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccel = event.values.clone()
                        maybeEmitFromFallback(lastAccel, lastMag, rotationMatrix, orientationValues, ::emitHeading)
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        lastMag = event.values.clone()
                        maybeEmitFromFallback(lastAccel, lastMag, rotationMatrix, orientationValues, ::emitHeading)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { /* not needed */ }
        }

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
            magSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        }

        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private fun maybeEmitFromFallback(
        accel: FloatArray?,
        mag: FloatArray?,
        rotationMatrix: FloatArray,
        orientationValues: FloatArray,
        emit: (Float) -> Unit
    ) {
        if (accel == null || mag == null) return
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accel, mag)
        if (!success) return
        SensorManager.getOrientation(rotationMatrix, orientationValues)
        val azimuthDeg = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
        emit((azimuthDeg + 360f) % 360f)
    }
}
