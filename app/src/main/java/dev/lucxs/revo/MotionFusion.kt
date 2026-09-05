package dev.lucxs.revo

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Turns raw accelerometer/gyroscope samples into a small, bounded "motion
 * cue": (dx, dy) is how far the dot ring should drift, spin is how far it
 * should rotate. Both are meant to feel like the ring is a fixed part of
 * the outside world reacting to the vehicle's braking/accelerating/turning
 * - not a real position, and not real yaw.
 *
 * Why not just integrate acceleration into a position directly? Because
 * accelerometer noise integrates into unbounded drift within seconds. This
 * instead drives a damped spring: acceleration pushes a velocity, velocity
 * pushes a position, and both velocity and position are constantly pulled
 * back toward zero. The result tracks sustained acceleration (braking over
 * ~1-2s) while a one-off jolt or steady cruising settles back to center
 * instead of wandering off.
 *
 * Reference frame: TYPE_GRAVITY reports "up" in the phone's own axes (the
 * same convention as the accelerometer - a phone resting on a table reads
 * +9.8 on the axis pointing away from the table). Projecting linear
 * acceleration onto the plane perpendicular to that gravity vector isolates
 * the horizontal (real-world) push/pull from whatever angle the phone
 * happens to be held at, so the cue stays correct whether the user holds
 * the phone flat or tilts it back to read. The two axes of that plane are
 * rebuilt from gravity on every sample (see rightHat/forwardHat below), so
 * they also self-correct if the user's hand tilt changes.
 *
 * What this can't separate: a genuine, deliberate rotation of the phone in
 * the hand around the axis pointing at the user's face looks the same to
 * the gyroscope as the vehicle actually turning. There's no way around
 * this with only the phone's own sensors (a headset fixed to the head, like
 * the commercial devices this app is inspired by, doesn't have the
 * problem). The low-pass filtering below is tuned to mostly absorb quick
 * hand adjustments, since those are brief compared to how long a real
 * lane change or turn takes - but it's a real limitation, not a bug.
 */
class MotionFusion(
    private val sensorManager: SensorManager,
    private val onMotion: (dx: Float, dy: Float, spin: Float) -> Unit,
) : SensorEventListener {

    /** 0..1, set from the "Sensitivity" slider. */
    var sensitivity: Float = 0.5f

    private val gravity = FloatArray(3)
    private var hasGravity = false

    // Exponential-moving-average low-pass state for the horizontal
    // acceleration cue, ~250ms time constant at typical sensor rates.
    private var lateralFiltered = 0f
    private var frontBackFiltered = 0f
    private val filterAlpha = 0.15f

    // Damped-spring state for the ring's (x, y) drift.
    private var velX = 0f
    private var velY = 0f
    private var posX = 0f
    private var posY = 0f
    private var lastAccelTimestampNs = 0L

    // Damped-spring state for the ring's rotation (turning cue).
    private var spinVel = 0f
    private var spin = 0f
    private var lastGyroTimestampNs = 0L

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
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
            Sensor.TYPE_LINEAR_ACCELERATION -> if (hasGravity) {
                onLinearAcceleration(event)
            }
            Sensor.TYPE_GYROSCOPE -> if (hasGravity) {
                onGyroscope(event)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onLinearAcceleration(event: SensorEvent) {
        val dt = deltaSeconds(lastAccelTimestampNs, event.timestamp)
        lastAccelTimestampNs = event.timestamp
        if (dt <= 0f) return

        val upHat = normalized(gravity) ?: return
        val rightHat = normalized(cross(upHat, DEVICE_FORWARD_AXIS)) ?: return
        val forwardHat = cross(rightHat, upHat)

        val lateral = dot(event.values, rightHat)
        val frontBack = dot(event.values, forwardHat)

        lateralFiltered += (lateral - lateralFiltered) * filterAlpha
        frontBackFiltered += (frontBack - frontBackFiltered) * filterAlpha

        // sensitivity 0..1 -> drive gain; higher sensitivity = the same
        // real-world acceleration pushes the ring further.
        val driveGain = 6f + sensitivity * 18f
        val damping = 4f
        val restoreTau = 0.6f

        velX += (lateralFiltered * driveGain - damping * velX) * dt
        velY += (frontBackFiltered * driveGain - damping * velY) * dt
        posX += velX * dt
        posY += velY * dt
        posX -= posX * (dt / restoreTau)
        posY -= posY * (dt / restoreTau)

        posX = posX.coerceIn(-MAX_OFFSET, MAX_OFFSET)
        posY = posY.coerceIn(-MAX_OFFSET, MAX_OFFSET)

        onMotion(posX, posY, spin)
    }

    private fun onGyroscope(event: SensorEvent) {
        val dt = deltaSeconds(lastGyroTimestampNs, event.timestamp)
        lastGyroTimestampNs = event.timestamp
        if (dt <= 0f) return

        val upHat = normalized(gravity) ?: return
        // Component of angular velocity about the "up" axis = yaw rate in
        // the phone's own horizontal frame, i.e. how fast it's turning flat,
        // which is what a vehicle turning left/right looks like.
        val yawRate = dot(event.values, upHat)

        val driveGain = 0.6f + sensitivity * 1.2f
        val damping = 3f
        val restoreTau = 0.8f

        spinVel += (yawRate * driveGain - damping * spinVel) * dt
        spin += spinVel * dt
        spin -= spin * (dt / restoreTau)
        spin = spin.coerceIn(-MAX_SPIN_RADIANS, MAX_SPIN_RADIANS)

        onMotion(posX, posY, spin)
    }

    private fun deltaSeconds(lastNs: Long, nowNs: Long): Float {
        if (lastNs == 0L) return 0f
        val dt = (nowNs - lastNs) / 1_000_000_000f
        // Clamp so a paused/backgrounded sensor stream (or the very first
        // sample) can't slam the spring with a huge synthetic dt.
        return dt.coerceIn(0f, 0.1f)
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

        // Arbitrary internal spring units (not pixels, not m/s^2) - the
        // overlay view maps the final posX/posY into an actual pixel
        // offset. This just bounds how far the spring itself can wind up.
        private const val MAX_OFFSET = 40f
        private const val MAX_SPIN_RADIANS = 0.35f
    }
}
