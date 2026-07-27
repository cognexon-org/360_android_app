package com.propertytour360.capture.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.propertytour360.capture.util.AngleMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Orientation source for guided panorama capture.
 *
 * This app aims the BACK CAMERA at the scene while the phone is held upright, so it
 * must NOT use SensorManager.getOrientation(): that convention is defined for a phone
 * lying flat and becomes degenerate (gimbal lock, swapped/inverted axes) when the
 * phone is vertical — which is why the old tilt guidance felt scrambled.
 *
 * Instead we compute the direction the back camera actually points and derive yaw
 * (azimuth) and pitch (elevation) from it. TYPE_GAME_ROTATION_VECTOR is preferred:
 * it has no magnetometer yaw jumps, and because it is gravity-referenced the up/down
 * (pitch) reading is accurate. Yaw is relative, which is fine — capture measures yaw
 * from the first shot.
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _sample = MutableStateFlow(OrientationSample())
    val sample: StateFlow<OrientationSample> = _sample

    private val rotationMatrix = FloatArray(9)

    private var lastTimestampNs: Long = 0L
    private var filteredYaw = 0f
    private var filteredPitch = 0f
    private var filteredRoll = 0f
    private var initialized = false

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        lastTimestampNs = 0L
        initialized = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // The rotation matrix maps device coords -> world coords (X=East, Y=North, Z=Up).
        // The back camera looks along the device -Z axis; its world components are the
        // negated third column of the matrix.
        val camEast = -rotationMatrix[2]
        val camNorth = -rotationMatrix[5]
        val camUp = -rotationMatrix[8]

        // Yaw / azimuth: clockwise positive from an arbitrary reference (relative is fine).
        val yaw = AngleMath.normalizeDegrees(
            Math.toDegrees(atan2(camEast.toDouble(), camNorth.toDouble())).toFloat()
        )

        // Pitch in the capture convention: 0 at the horizon, NEGATIVE looking up (ceiling
        // target -78), POSITIVE looking down (floor target +78).
        val elevation = Math.toDegrees(asin(camUp.coerceIn(-1f, 1f).toDouble())).toFloat()
        val pitch = (-elevation).coerceIn(-90f, 90f)

        // Roll about the camera axis (informational only; not used for alignment gating).
        val roll = Math.toDegrees(asin(rotationMatrix[6].coerceIn(-1f, 1f).toDouble())).toFloat()

        if (!initialized) {
            filteredYaw = yaw
            filteredPitch = pitch
            filteredRoll = roll
            initialized = true
        } else {
            // Circular interpolation for yaw; ordinary low-pass filtering for pitch and roll.
            filteredYaw = AngleMath.interpolateDegrees(filteredYaw, yaw, 0.18f)
            filteredPitch += (pitch - filteredPitch) * 0.18f
            filteredRoll += (roll - filteredRoll) * 0.18f
        }

        val previous = _sample.value
        val timestampNs = if (event.timestamp > 0L) event.timestamp else SystemClock.elapsedRealtimeNanos()
        val dtSeconds = if (lastTimestampNs == 0L) 0f
        else ((timestampNs - lastTimestampNs) / 1_000_000_000f).coerceIn(0.001f, 0.25f)
        lastTimestampNs = timestampNs

        val speed = if (dtSeconds > 0f) {
            val yawRate = AngleMath.angularDistance(previous.yawDegrees, filteredYaw) / dtSeconds
            val pitchRate = abs(previous.pitchDegrees - filteredPitch) / dtSeconds
            maxOf(yawRate, pitchRate)
        } else 0f

        _sample.value = OrientationSample(
            yawDegrees = filteredYaw,
            pitchDegrees = filteredPitch,
            rollDegrees = filteredRoll,
            angularSpeedDegreesPerSecond = speed,
            sensorAvailable = rotationSensor != null,
            timestampNs = timestampNs
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

data class OrientationSample(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val angularSpeedDegreesPerSecond: Float = 0f,
    val sensorAvailable: Boolean = false,
    val timestampNs: Long = 0L
)