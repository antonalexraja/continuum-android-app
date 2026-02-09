/**
 * Navigator Native Wrapper
 *
 * High-level Kotlin wrapper for the native navigation engine.
 * Provides thread-safe access and Kotlin-friendly API.
 *
 * ## Thread Safety
 *
 * This class is thread-safe. All methods synchronize on the instance.
 * However, for optimal performance, sensor data should be processed
 * from a dedicated thread to avoid blocking the UI.
 *
 * ## Usage
 *
 * ```kotlin
 * val navigator = NavigatorNative.create()
 * try {
 *     navigator.processGnssPosition(gnssInput)
 *     navigator.processImu(imuInput)
 *     val output = navigator.getState()
 *     // use output
 * } finally {
 *     navigator.destroy()
 * }
 * ```
 *
 * ## Lifecycle
 *
 * - Create with [create] or [createWithConfig]
 * - Process sensor data with processXxx methods
 * - Get state with [getState], [getCovariance], [getImuBias]
 * - Call [destroy] when done (or use [use] extension)
 *
 * After [destroy], all methods will throw [IllegalStateException].
 */
package com.continuum.navigator.core.native

import android.util.Log
import androidx.annotation.GuardedBy
import java.io.Closeable

/**
 * Thread-safe wrapper for the native navigation engine.
 */
class NavigatorNative private constructor(
    private var handle: Long,
) : Closeable {

    /** Lock for thread safety */
    private val lock = Any()

    /** Flag to track if destroyed */
    @GuardedBy("lock")
    private var isDestroyed = false

    // Reusable arrays to avoid allocation on hot path
    private val stateBuffer = DoubleArray(24)
    private val covarianceBuffer = DoubleArray(15)
    private val biasBuffer = DoubleArray(6)

    companion object {
        /**
         * Create a new navigator with default configuration.
         *
         * @throws IllegalStateException if native library not loaded
         * @throws NavigatorException if creation failed
         */
        fun create(): NavigatorNative {
            NavigatorJni.ensureLoaded()
            val handle = NavigatorJni.nativeCreate()
            if (handle == 0L) {
                throw NavigatorException(
                    NavigatorError.NULL_POINTER,
                    "Failed to create navigator"
                )
            }
            return NavigatorNative(handle)
        }

        /**
         * Create a new navigator with custom configuration.
         *
         * @param config Configuration options
         * @throws IllegalStateException if native library not loaded
         * @throws NavigatorException if creation failed
         */
        fun createWithConfig(config: NavigatorConfig): NavigatorNative {
            NavigatorJni.ensureLoaded()
            val handle = NavigatorJni.nativeCreateWithConfig(
                config.initialLatitudeDeg,
                config.initialLongitudeDeg,
                config.initialAltitudeM,
                config.initialHeadingRad,
                config.accelNoiseDensity,
                config.gyroNoiseDensity,
                config.accelBiasRw,
                config.gyroBiasRw,
                config.positionNoiseStdM,
                config.velocityNoiseStdMps,
                config.attitudeNoiseStdRad,
            )
            if (handle == 0L) {
                throw NavigatorException(
                    NavigatorError.NULL_POINTER,
                    "Failed to create navigator with config"
                )
            }
            return NavigatorNative(handle)
        }

        /**
         * Get native library version.
         */
        fun version(): String {
            NavigatorJni.ensureLoaded()
            return NavigatorJni.nativeVersion()
        }

        /**
         * Check if native library is loaded.
         */
        fun isLibraryLoaded(): Boolean = NavigatorJni.isLoaded
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Check if navigator is initialized with position.
     */
    fun isInitialized(): Boolean {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeIsInitialized(handle)
            return result == 1
        }
    }

    /**
     * Reset navigator to uninitialized state.
     *
     * @throws NavigatorException on error
     */
    fun reset() {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeReset(handle)
            checkResult(result, "reset")
        }
    }

    /**
     * Destroy the navigator and free native memory.
     *
     * After this call, all other methods will throw [IllegalStateException].
     * Safe to call multiple times.
     */
    fun destroy() {
        synchronized(lock) {
            if (!isDestroyed) {
                NavigatorJni.nativeDestroy(handle)
                handle = 0
                isDestroyed = true
            }
        }
    }

    /**
     * Implement Closeable for use with `use {}` block.
     */
    override fun close() = destroy()

    // ═══════════════════════════════════════════════════════════════════════
    // Sensor Processing
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Process an IMU sample.
     *
     * @param input IMU sample data
     * @throws NavigatorException on error
     */
    fun processImu(input: ImuInput) {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeProcessImu(
                handle,
                input.timestampNs,
                input.accelX,
                input.accelY,
                input.accelZ,
                input.gyroX,
                input.gyroY,
                input.gyroZ,
            )
            checkResult(result, "processImu")
        }
    }

    /**
     * Process IMU sample with individual values.
     *
     * This overload avoids object allocation on hot path.
     */
    fun processImu(
        timestampNs: Long,
        accelX: Double,
        accelY: Double,
        accelZ: Double,
        gyroX: Double,
        gyroY: Double,
        gyroZ: Double,
    ) {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeProcessImu(
                handle,
                timestampNs,
                accelX, accelY, accelZ,
                gyroX, gyroY, gyroZ,
            )
            checkResult(result, "processImu")
        }
    }

    /**
     * Process a GNSS position measurement.
     *
     * @param input GNSS position data
     * @throws NavigatorException on error
     */
    fun processGnssPosition(input: GnssPositionInput) {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeProcessGnssPosition(
                handle,
                input.timestampNs,
                input.latitudeDeg,
                input.longitudeDeg,
                input.altitudeM,
                input.horizontalAccuracyM,
                input.verticalAccuracyM,
            )
            checkResult(result, "processGnssPosition")
        }
    }

    /**
     * Process a GNSS velocity measurement.
     *
     * @param input GNSS velocity data
     * @throws NavigatorException on error
     */
    fun processGnssVelocity(input: GnssVelocityInput) {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeProcessGnssVelocity(
                handle,
                input.timestampNs,
                input.velocityNorthMps,
                input.velocityEastMps,
                input.velocityDownMps,
                input.velocityAccuracyMps,
            )
            checkResult(result, "processGnssVelocity")
        }
    }

    /**
     * Process an external speed measurement.
     *
     * @param input Speed data
     * @throws NavigatorException on error
     */
    fun processSpeed(input: SpeedInput) {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeProcessSpeed(
                handle,
                input.timestampNs,
                input.speedMps,
                input.accuracyMps,
            )
            checkResult(result, "processSpeed")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // State Retrieval
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get current navigation state.
     *
     * @return Navigation output with position, velocity, attitude
     * @throws NavigatorException on error
     */
    fun getState(): NavigationOutput {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeGetState(handle, stateBuffer)
            checkResult(result, "getState")

            return NavigationOutput(
                timestampNs = stateBuffer[0].toLong(),
                positionNorthM = stateBuffer[1],
                positionEastM = stateBuffer[2],
                positionDownM = stateBuffer[3],
                latitudeDeg = stateBuffer[4],
                longitudeDeg = stateBuffer[5],
                altitudeM = stateBuffer[6],
                velocityNorthMps = stateBuffer[7],
                velocityEastMps = stateBuffer[8],
                velocityDownMps = stateBuffer[9],
                attitudeW = stateBuffer[10],
                attitudeX = stateBuffer[11],
                attitudeY = stateBuffer[12],
                attitudeZ = stateBuffer[13],
                rollRad = stateBuffer[14],
                pitchRad = stateBuffer[15],
                yawRad = stateBuffer[16],
                positionStdM = stateBuffer[17],
                velocityStdMps = stateBuffer[18],
                attitudeStdRad = stateBuffer[19],
                status = stateBuffer[20].toInt(),
                validityFlags = stateBuffer[21].toInt(),
            )
        }
    }

    /**
     * Get current covariance/uncertainties.
     *
     * @return Covariance output
     * @throws NavigatorException on error
     */
    fun getCovariance(): CovarianceOutput {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeGetCovariance(handle, covarianceBuffer)
            checkResult(result, "getCovariance")

            return CovarianceOutput(
                positionNorthStdM = covarianceBuffer[0],
                positionEastStdM = covarianceBuffer[1],
                positionDownStdM = covarianceBuffer[2],
                velocityNorthStdMps = covarianceBuffer[3],
                velocityEastStdMps = covarianceBuffer[4],
                velocityDownStdMps = covarianceBuffer[5],
                rollStdRad = covarianceBuffer[6],
                pitchStdRad = covarianceBuffer[7],
                yawStdRad = covarianceBuffer[8],
                gyroBiasXStd = covarianceBuffer[9],
                gyroBiasYStd = covarianceBuffer[10],
                gyroBiasZStd = covarianceBuffer[11],
                accelBiasXStd = covarianceBuffer[12],
                accelBiasYStd = covarianceBuffer[13],
                accelBiasZStd = covarianceBuffer[14],
            )
        }
    }

    /**
     * Get current IMU bias estimates.
     *
     * @return IMU bias output
     * @throws NavigatorException on error
     */
    fun getImuBias(): ImuBiasOutput {
        synchronized(lock) {
            ensureNotDestroyed()
            val result = NavigatorJni.nativeGetImuBias(handle, biasBuffer)
            checkResult(result, "getImuBias")

            return ImuBiasOutput(
                gyroBiasX = biasBuffer[0],
                gyroBiasY = biasBuffer[1],
                gyroBiasZ = biasBuffer[2],
                accelBiasX = biasBuffer[3],
                accelBiasY = biasBuffer[4],
                accelBiasZ = biasBuffer[5],
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════════════════

    private fun ensureNotDestroyed() {
        if (isDestroyed) {
            throw IllegalStateException("Navigator has been destroyed")
        }
    }

    private fun checkResult(result: Int, operation: String) {
        if (result < 0) {
            val message = try {
                NavigatorJni.nativeErrorMessage(result)
            } catch (e: Exception) {
                NavigatorError.toString(result)
            }
            throw NavigatorException(result, "$operation failed: $message")
        }
    }

    protected fun finalize() {
        // Safety net - prefer explicit destroy()
        if (!isDestroyed && handle != 0L) {
            Log.w("NavigatorNative", "Navigator not explicitly destroyed")
            destroy()
        }
    }
}

/**
 * Exception thrown by navigator operations.
 */
class NavigatorException(
    /** Native error code */
    val errorCode: Int,
    message: String,
) : RuntimeException(message) {

    /** Check if this is a recoverable error */
    val isRecoverable: Boolean
        get() = errorCode != NavigatorError.PANIC &&
                errorCode != NavigatorError.NULL_POINTER

    override fun toString(): String =
        "NavigatorException(code=$errorCode, message=$message)"
}
