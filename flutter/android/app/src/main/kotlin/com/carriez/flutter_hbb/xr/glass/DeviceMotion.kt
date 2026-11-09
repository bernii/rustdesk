package com.carriez.flutter_hbb.xr.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The accelerometer, as the body force the marble's oils feel in screen axes
 * (m/s², x right, y down): gravity's pull along the screen minus the phone's
 * own acceleration. The sensor reports the reaction to both, device axes with
 * y up, so the force is (−x, y). The same formula as the web playground's
 * devicemotion handler. The app is portrait only, so device and screen axes
 * agree.
 *
 * And the phone's rotation since [start], from the game rotation vector (gyro
 * and accelerometer, no compass), for a light that stays put in the room.
 *
 * For the G-force ([GForce]), the reading into the screen as well, and the
 * gyroscope: how fast the phone turns and how fast that changes. [onMoved]
 * hears, on the sensor thread, when the phone moves enough to wake the oil.
 */
class DeviceMotion(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationSensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyroscope = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotation = FloatArray(9)
    private var reference: FloatArray? = null
    private val calm = FloatArray(3)
    private var calmReady = false
    private var lastSpinAt = 0L

    /**
     * How the phone has turned since [start]: row-major, it takes a direction
     * in device axes then (x right, y up, z out of the screen) to device axes
     * now. Null until the rotation sensor reports.
     */
    @Volatile
    var turned: FloatArray? = null
        private set

    @Volatile
    var forceX = 0f
        private set

    @Volatile
    var forceY = 0f
        private set

    /** Into the screen, m/s²: g for a phone lying face up. */
    @Volatile
    var weight = 0f
        private set

    /** How fast the phone turns, rad/s, about x, y and z of the device's axes. */
    @Volatile
    var spin = FloatArray(3)
        private set

    /** How fast [spin] changes, rad/s², smoothed over SPIN_UP_SMOOTH. */
    @Volatile
    var spinUp = FloatArray(3)
        private set

    /** False until the first reading arrives. */
    @Volatile
    var ready = false
        private set

    /** Called on the sensor thread whenever the phone moves more than a hand's tremor. */
    @Volatile
    var onMoved: (() -> Unit)? = null

    fun start() {
        // About 50 Hz: shakes are a few hertz, and the oils move far slower.
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        reference = null
        turned = null
        rotationSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        calmReady = false
        lastSpinAt = 0L
        spin = FloatArray(3)
        spinUp = FloatArray(3)
        gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> onRotation(event.values)
            Sensor.TYPE_GYROSCOPE -> onSpin(event.values, event.timestamp)
            else -> onForce(event.values)
        }
    }

    private fun onForce(values: FloatArray) {
        forceX = -values[0]
        forceY = values[1]
        weight = values[2]
        ready = true
        // A slow reference the reading wanders from when the phone moves.
        val reading = floatArrayOf(forceX, forceY, weight)
        if (!calmReady) {
            reading.copyInto(calm)
            calmReady = true
        }
        var away = 0f
        for (i in 0 until 3) {
            away = max(away, abs(reading[i] - calm[i]))
            calm[i] += (reading[i] - calm[i]) * CALM_FOLLOW
        }
        if (away > WAKE_FORCE) onMoved?.invoke()
    }

    private fun onSpin(values: FloatArray, at: Long) {
        val now = floatArrayOf(values[0], values[1], values[2])
        val dt = if (lastSpinAt == 0L) 0f else (at - lastSpinAt) / 1e9f
        lastSpinAt = at
        if (dt > 0f && dt < 0.2f) {
            val before = spin
            val smooth = min(1f, dt / SPIN_UP_SMOOTH)
            spinUp = FloatArray(3) { spinUp[it] + ((now[it] - before[it]) / dt - spinUp[it]) * smooth }
        }
        spin = now
        if (sqrt(now[0] * now[0] + now[1] * now[1] + now[2] * now[2]) > WAKE_SPIN) onMoved?.invoke()
    }

    // The rotation matrix takes device axes to the world's. Then to now is
    // the world's back to device axes after the world's from then: Rᵀ R₀.
    private fun onRotation(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotation, values)
        val then = reference ?: rotation.copyOf().also { reference = it }
        val result = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) sum += rotation[k * 3 + row] * then[k * 3 + col]
                result[row * 3 + col] = sum
            }
        }
        turned = result
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        // The web playground's.
        const val SPIN_UP_SMOOTH = 0.03f // s
        // Waking: a reading this far from where it's been lately, or turning
        // this fast, is more than a hand's tremor.
        const val WAKE_FORCE = 0.5f // m/s²
        const val WAKE_SPIN = 0.5f // rad/s
        const val CALM_FOLLOW = 0.02f // per reading, about a second at 50 Hz
    }
}
