package dev.lucxs.revo

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Turns raw accelerometer samples into one signal: (dx, dy), the phone's
 * own estimated displacement since the leak last let it settle. Everything
 * downstream (MotionOverlayView) treats this as "how far the camera has
 * moved" and draws each dot's on-screen position as the *negative* of
 * this, scaled by 1/depth - the standard parallax relationship for a
 * point that's actually fixed in space while the camera carrying it moves.
 *
 * Reference frame: TYPE_GRAVITY reports "up" in the phone's own axes (the
 * same convention as the accelerometer - a phone resting on a table reads
 * +9.8 on the axis pointing away from the table). Projecting linear
 * acceleration onto the plane perpendicular to that gravity vector isolates
 * the horizontal (real-world) push/pull from whatever angle the phone
 * happens to be held at. The two axes of that plane (rightHat/forwardHat)
 * are rebuilt from gravity on every sample, so they self-correct if the
 * user's hand tilt changes.
 *
 * On integrating acceleration twice: naive double integration of a real
 * accelerometer drifts into nonsense within seconds, because there's no
 * such thing as unbiased-forever acceleration data. Instead this leaks at
 * two timescales: velocity leaks back toward zero with a ~4s time
 * constant (so it tracks a real 1-3s brake/turn almost like true
 * integration, since that's short next to 4s), and position leaks back
 * with a slower ~12s constant on top of that (so a stopped vehicle, or a
 * phone just sitting on a table, settles the dots back toward center
 * within the following 10-20s instead of the field staying shifted
 * forever). Both constants are first-pass estimates meant to be retuned
 * after actually riding with this.
 *
 * Rotation is deliberately NOT compensated here. A phone's gyroscope can't
 * tell "the user tilted the phone in their hand" apart from "the vehicle
 * actually turned" - both look identical to the sensor. Folding that in
 * would make the field visibly (and wrongly) swing every time someone
 * just adjusts their grip. Left as a scoped future extension for once
 * there's a way to disambiguate the two (e.g. only trusting gyro signal
 * that's sustained and correlated with lateral accelerometer signal).
 */
class MotionFusion(
    private val sensorManager: SensorManager,
    private val onMotion: (dx: Float, dy: Float) -> Unit,
) : SensorEventListener {

    /** 0..1, set from the "Sensitivity" slider. */
    var sensitivity: Float = 0.5f

    private val gravity = FloatArray(3)
    private var hasGravity = false

    // Exponential-moving-average low-pass on the horizontal acceleration,
    // ~250ms time constant at typical sensor rates - smooths sensor noise
    // and quick hand jitter before it ever reaches the integrator, so the
    // result reads as one smooth push rather than a buzz.
    private var lateralFiltered = 0f
    private var frontBackFiltered = 0f
    private val filterAlpha = 0.15f

    private var velX = 0f
    private var velY = 0f
    private var posX = 0f
    private var posY = 0f
    private var lastTimestampNs = 0L

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                hasGravity = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> if (hasGravity) onLinearAcceleration(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onLinearAcceleration(event: SensorEvent) {
        val dt = deltaSeconds(lastTimestampNs, event.timestamp)
        lastTimestampNs = event.timestamp
        if (dt <= 0f) return

        val upHat = normalized(gravity) ?: return
        val rightHat = normalized(cross(upHat, DEVICE_FORWARD_AXIS)) ?: return
        val forwardHat = cross(rightHat, upHat)

        val lateral = dot(event.values, rightHat)
        val frontBack = dot(event.values, forwardHat)

        lateralFiltered += (lateral - lateralFiltered) * filterAlpha
        frontBackFiltered += (frontBack - frontBackFiltered) * filterAlpha

        // sensitivity 0..1 -> drive gain; higher sensitivity = the same
        // real-world acceleration builds up more estimated displacement.
        val driveGain = 0.4f + sensitivity * 1.2f

        velX += (lateralFiltered * driveGain - velX / VELOCITY_LEAK_TAU_S) * dt
        velY += (frontBackFiltered * driveGain - velY / VELOCITY_LEAK_TAU_S) * dt
        posX += velX * dt - posX * (dt / POSITION_LEAK_TAU_S)
        posY += velY * dt - posY * (dt / POSITION_LEAK_TAU_S)

        // Not the mechanism that returns the field to center (that's
        // POSITION_LEAK_TAU_S) - just a backstop against a pathological
        // sensor spike (phone dropped, violent shake) blowing this up.
        posX = posX.coerceIn(-SAFETY_CLAMP_UNITS, SAFETY_CLAMP_UNITS)
        posY = posY.coerceIn(-SAFETY_CLAMP_UNITS, SAFETY_CLAMP_UNITS)

        onMotion(posX, posY)
    }

    private fun deltaSeconds(lastNs: Long, nowNs: Long): Float {
        if (lastNs == 0L) return 0f
        // Clamp so a paused/backgrounded sensor stream (or the very first
        // sample) can't slam the integrator with a huge synthetic dt.
        return ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
    }

    private fun normalized(v: FloatArray): FloatArray? {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 0.001f) return null
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: FloatArray, b: FloatArray) =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    companion object {
        // Device's own "out of the screen, toward the user's face" axis,
        // used only as a fixed helper to build a horizontal basis from
        // gravity - see the class doc above.
        private val DEVICE_FORWARD_AXIS = floatArrayOf(0f, 0f, 1f)

        private const val VELOCITY_LEAK_TAU_S = 4f
        private const val POSITION_LEAK_TAU_S = 12f
        private const val SAFETY_CLAMP_UNITS = 30f
    }
}
