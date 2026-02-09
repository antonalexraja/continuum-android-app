/**
 * IMU Sensor Provider
 *
 * Handles accelerometer and gyroscope data from Android SensorManager.
 * Provides nanosecond-accurate timestamps and proper coordinate transformation.
 *
 * ## Coordinate System
 *
 * Android sensors use a device-centric coordinate system:
 * - X: Right (along short edge)
 * - Y: Up (along long edge)
 * - Z: Out of screen (towards user)
 *
 * Navigator expects body-frame coordinates (when device is landscape, screen up):
 * - X: Forward
 * - Y: Right
 * - Z: Down
 *
 * This class transforms from Android to Navigator body frame.
 *
 * ## Timestamps
 *
 * Android sensor timestamps are in nanoseconds since boot (SystemClock.elapsedRealtimeNanos).
 * This matches what the native engine expects.
 *
 * ## Thread Safety
 *
 * Sensor callbacks arrive on a sensor thread. The callback function must be thread-safe.
 */
package com.continuum.navigator.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.continuum.navigator.core.native.ImuInput
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Callback interface for IMU samples.
 */
fun interface ImuCallback {
    /**
     * Called when a new IMU sample is ready.
     *
     * @param input Complete IMU sample with accel and gyro
     */
    fun onImuSample(input: ImuInput)
}

/**
 * Configuration for IMU sensor provider.
 */
data class ImuConfig(
    /**
     * Sensor sampling period in microseconds.
     * Common values:
     * - SensorManager.SENSOR_DELAY_FASTEST (~0) = ~10us
     * - SensorManager.SENSOR_DELAY_GAME = ~20,000us (50Hz)
     * - SensorManager.SENSOR_DELAY_UI = ~60,000us (~17Hz)
     * - SensorManager.SENSOR_DELAY_NORMAL = ~200,000us (5Hz)
     *
     * For navigation, use fastest or ~5000us (200Hz)
     */
    val samplingPeriodUs: Int = 5_000, // 200 Hz

    /**
     * Maximum reporting latency in microseconds.
     * Allows sensor batching for power savings.
     * 0 = no batching (lowest latency, highest power)
     */
    val maxReportLatencyUs: Int = 0,

    /**
     * Device orientation for coordinate transformation.
     */
    val orientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
)

/**
 * Device orientation for coordinate transformation.
 */
enum class DeviceOrientation {
    /** Device held upright in portrait mode */
    PORTRAIT,
    /** Device rotated 90° clockwise (landscape, home button right) */
    LANDSCAPE_RIGHT,
    /** Device rotated 90° counter-clockwise (landscape, home button left) */
    LANDSCAPE_LEFT,
    /** Device flat, screen up */
    FLAT_SCREEN_UP,
}

/**
 * IMU sensor provider using Android SensorManager.
 */
class ImuSensorProvider(
    private val context: Context,
    private val config: ImuConfig = ImuConfig(),
    private val callback: ImuCallback,
) {
    companion object {
        private const val TAG = "ImuSensorProvider"
    }

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Handler thread for sensor callbacks
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // State
    private val isRunning = AtomicBoolean(false)
    private val sampleCount = AtomicLong(0)
    
    // Track last emitted timestamp to prevent duplicates
    @Volatile private var lastEmittedTimestamp: Long = 0

    // Latest sensor values (for synchronization)
    @Volatile private var lastAccelTimestamp: Long = 0
    @Volatile private var lastAccelX: Float = 0f
    @Volatile private var lastAccelY: Float = 0f
    @Volatile private var lastAccelZ: Float = 0f

    @Volatile private var lastGyroTimestamp: Long = 0
    @Volatile private var lastGyroX: Float = 0f
    @Volatile private var lastGyroY: Float = 0f
    @Volatile private var lastGyroZ: Float = 0f

    // Sensor listener
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event)
                Sensor.TYPE_GYROSCOPE -> onGyroscope(event)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "Sensor ${sensor?.name} accuracy changed: $accuracy")
        }
    }

    /**
     * Check if required sensors are available.
     */
    fun areSensorsAvailable(): Boolean =
        accelerometer != null && gyroscope != null

    /**
     * Get sensor information for debugging.
     */
    fun getSensorInfo(): String = buildString {
        appendLine("Accelerometer: ${accelerometer?.name ?: "NOT AVAILABLE"}")
        accelerometer?.let {
            appendLine("  - Vendor: ${it.vendor}")
            appendLine("  - Resolution: ${it.resolution} m/s²")
            appendLine("  - Max Range: ${it.maximumRange} m/s²")
            appendLine("  - Min Delay: ${it.minDelay} µs")
        }
        appendLine("Gyroscope: ${gyroscope?.name ?: "NOT AVAILABLE"}")
        gyroscope?.let {
            appendLine("  - Vendor: ${it.vendor}")
            appendLine("  - Resolution: ${it.resolution} rad/s")
            appendLine("  - Max Range: ${it.maximumRange} rad/s")
            appendLine("  - Min Delay: ${it.minDelay} µs")
        }
    }

    /**
     * Start collecting sensor data.
     *
     * @throws IllegalStateException if sensors not available
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }

        if (!areSensorsAvailable()) {
            isRunning.set(false)
            throw IllegalStateException("Required sensors not available")
        }
        
        // Reset timing state for fresh start
        lastEmittedTimestamp = 0
        lastAccelTimestamp = 0
        lastGyroTimestamp = 0

        // Create handler thread for callbacks
        handlerThread = HandlerThread("ImuSensorThread").apply {
            start()
            handler = Handler(looper)
        }

        // Register accelerometer
        val accelOk = sensorManager.registerListener(
            sensorListener,
            accelerometer,
            config.samplingPeriodUs,
            config.maxReportLatencyUs,
            handler
        )

        // Register gyroscope
        val gyroOk = sensorManager.registerListener(
            sensorListener,
            gyroscope,
            config.samplingPeriodUs,
            config.maxReportLatencyUs,
            handler
        )

        if (!accelOk || !gyroOk) {
            stop()
            throw IllegalStateException(
                "Failed to register sensors: accel=$accelOk, gyro=$gyroOk"
            )
        }

        Log.i(TAG, "IMU sensors started at ${config.samplingPeriodUs}µs period")
    }

    /**
     * Stop collecting sensor data.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        sensorManager.unregisterListener(sensorListener)

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        Log.i(TAG, "IMU sensors stopped. Total samples: ${sampleCount.get()}")
    }

    /**
     * Get total sample count since start.
     */
    fun getSampleCount(): Long = sampleCount.get()

    // ═══════════════════════════════════════════════════════════════════════
    // Sensor Callbacks
    // ═══════════════════════════════════════════════════════════════════════

    private fun onAccelerometer(event: SensorEvent) {
        lastAccelTimestamp = event.timestamp
        lastAccelX = event.values[0]
        lastAccelY = event.values[1]
        lastAccelZ = event.values[2]

        // Try to emit sample if we have both sensors
        tryEmitSample()
    }

    private fun onGyroscope(event: SensorEvent) {
        lastGyroTimestamp = event.timestamp
        lastGyroX = event.values[0]
        lastGyroY = event.values[1]
        lastGyroZ = event.values[2]

        // Try to emit sample if we have both sensors
        tryEmitSample()
    }

    /**
     * Emit a combined IMU sample.
     *
     * We use the gyroscope timestamp as the canonical time since
     * angular rate is more sensitive to timing errors than acceleration.
     */
    private fun tryEmitSample() {
        // Need both sensor readings
        if (lastAccelTimestamp == 0L || lastGyroTimestamp == 0L) {
            return
        }
        
        // Get the timestamp we would emit
        val timestampToEmit = lastGyroTimestamp
        
        // Ensure strictly monotonic timestamps - skip if not newer than last emitted
        if (timestampToEmit <= lastEmittedTimestamp) {
            return
        }

        // Check timestamps are reasonably close (within 50ms)
        val timeDiff = abs(lastAccelTimestamp - lastGyroTimestamp)
        if (timeDiff > 50_000_000) { // 50ms in nanoseconds
            return
        }
        
        // Mark this timestamp as emitted before processing
        lastEmittedTimestamp = timestampToEmit

        // Transform coordinates based on orientation
        val (accelX, accelY, accelZ) = transformAccel(
            lastAccelX, lastAccelY, lastAccelZ
        )
        val (gyroX, gyroY, gyroZ) = transformGyro(
            lastGyroX, lastGyroY, lastGyroZ
        )

        // Use gyro timestamp as canonical
        val input = ImuInput(
            timestampNs = timestampToEmit,
            accelX = accelX.toDouble(),
            accelY = accelY.toDouble(),
            accelZ = accelZ.toDouble(),
            gyroX = gyroX.toDouble(),
            gyroY = gyroY.toDouble(),
            gyroZ = gyroZ.toDouble(),
        )

        sampleCount.incrementAndGet()
        callback.onImuSample(input)
    }

    /**
     * Transform accelerometer from Android to body frame.
     *
     * Android (portrait):  X=right, Y=up, Z=out
     * Body (forward):      X=forward, Y=right, Z=down
     *
     * For portrait mode with device held upright:
     * - Forward = +Y (Android up)
     * - Right = +X (Android right)
     * - Down = -Z (Android out, but inverted)
     */
    private fun transformAccel(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        return when (config.orientation) {
            DeviceOrientation.PORTRAIT -> {
                // Device upright in portrait
                // Forward = +Y, Right = +X, Down = -Z
                Triple(y, x, -z)
            }
            DeviceOrientation.LANDSCAPE_RIGHT -> {
                // Rotated 90° CW (home button on right)
                // Forward = +X, Right = -Y, Down = -Z
                Triple(x, -y, -z)
            }
            DeviceOrientation.LANDSCAPE_LEFT -> {
                // Rotated 90° CCW (home button on left)
                // Forward = -X, Right = +Y, Down = -Z
                Triple(-x, y, -z)
            }
            DeviceOrientation.FLAT_SCREEN_UP -> {
                // Device flat on table, screen up
                // Forward = +Y, Right = +X, Down = +Z
                Triple(y, x, z)
            }
        }
    }

    /**
     * Transform gyroscope from Android to body frame.
     * Same transformation as accelerometer.
     */
    private fun transformGyro(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        return when (config.orientation) {
            DeviceOrientation.PORTRAIT -> Triple(y, x, -z)
            DeviceOrientation.LANDSCAPE_RIGHT -> Triple(x, -y, -z)
            DeviceOrientation.LANDSCAPE_LEFT -> Triple(-x, y, -z)
            DeviceOrientation.FLAT_SCREEN_UP -> Triple(y, x, z)
        }
    }
}
