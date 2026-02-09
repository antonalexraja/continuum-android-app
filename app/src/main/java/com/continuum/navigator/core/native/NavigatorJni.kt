/**
 * Navigator Native JNI Bindings
 *
 * Low-level JNI interface to the native navigation library.
 * This class should NOT be used directly - use NavigatorNative instead.
 *
 * ## Thread Safety
 *
 * The native library is NOT thread-safe. All calls to a single handle
 * must be serialized by the caller. NavigatorNative provides this synchronization.
 *
 * ## Memory Management
 *
 * - `nativeCreate()` allocates native memory
 * - `nativeDestroy()` frees native memory
 * - Caller MUST call destroy when done
 * - Using a handle after destroy is undefined behavior
 */
package com.continuum.navigator.core.native

import androidx.annotation.Keep

/**
 * JNI bindings to the native navigator library.
 *
 * All methods are internal - external code should use [NavigatorNative].
 */
@Keep
internal object NavigatorJni {

    /**
     * Flag indicating if the native library was loaded successfully.
     */
    @Volatile
    var isLoaded: Boolean = false
        private set

    /**
     * Error message if library loading failed.
     */
    var loadError: String? = null
        private set

    init {
        loadNativeLibrary()
    }

    private fun loadNativeLibrary() {
        try {
            System.loadLibrary("navigator_ffi")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            loadError = "Failed to load navigator_ffi: ${e.message}"
            isLoaded = false
        } catch (e: SecurityException) {
            loadError = "Security error loading navigator_ffi: ${e.message}"
            isLoaded = false
        }
    }

    /**
     * Ensure library is loaded, throwing if not.
     */
    fun ensureLoaded() {
        if (!isLoaded) {
            throw IllegalStateException(loadError ?: "Native library not loaded")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create a new navigator instance with default configuration.
     * @return Native handle pointer (0 if failed)
     */
    @JvmStatic
    external fun nativeCreate(): Long

    /**
     * Create a new navigator instance with configuration.
     * @return Native handle pointer (0 if failed)
     */
    @JvmStatic
    external fun nativeCreateWithConfig(
        initialLatitudeDeg: Double,
        initialLongitudeDeg: Double,
        initialAltitudeM: Double,
        initialHeadingRad: Double,
        accelNoiseDensity: Double,
        gyroNoiseDensity: Double,
        accelBiasRw: Double,
        gyroBiasRw: Double,
        positionNoiseStdM: Double,
        velocityNoiseStdMps: Double,
        attitudeNoiseStdRad: Double,
    ): Long

    /**
     * Destroy a navigator instance and free memory.
     * @param handle Native handle pointer
     */
    @JvmStatic
    external fun nativeDestroy(handle: Long)

    /**
     * Reset the navigator to uninitialized state.
     * @param handle Native handle pointer
     * @return Error code (0 = success)
     */
    @JvmStatic
    external fun nativeReset(handle: Long): Int

    /**
     * Check if navigator is initialized.
     * @param handle Native handle pointer
     * @return 1 if initialized, 0 if not, negative on error
     */
    @JvmStatic
    external fun nativeIsInitialized(handle: Long): Int

    // ═══════════════════════════════════════════════════════════════════════
    // Sensor Processing
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Process an IMU sample.
     * @return Error code (0 = success)
     */
    @JvmStatic
    external fun nativeProcessImu(
        handle: Long,
        timestampNs: Long,
        accelX: Double,
        accelY: Double,
        accelZ: Double,
        gyroX: Double,
        gyroY: Double,
        gyroZ: Double,
    ): Int

    /**
     * Process a GNSS position measurement.
     * @return Error code (0 = success)
     */
    @JvmStatic
    external fun nativeProcessGnssPosition(
        handle: Long,
        timestampNs: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        horizontalAccuracyM: Double,
        verticalAccuracyM: Double,
    ): Int

    /**
     * Process a GNSS velocity measurement.
     * @return Error code (0 = success)
     */
    @JvmStatic
    external fun nativeProcessGnssVelocity(
        handle: Long,
        timestampNs: Long,
        velocityNorthMps: Double,
        velocityEastMps: Double,
        velocityDownMps: Double,
        velocityAccuracyMps: Double,
    ): Int

    /**
     * Process an external speed measurement.
     * @return Error code (0 = success)
     */
    @JvmStatic
    external fun nativeProcessSpeed(
        handle: Long,
        timestampNs: Long,
        speedMps: Double,
        accuracyMps: Double,
    ): Int

    // ═══════════════════════════════════════════════════════════════════════
    // State Retrieval
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get navigation output.
     * @param output Array to receive output values (must be size 24)
     *   [0] = timestampNs (as double)
     *   [1-3] = position NED
     *   [4-6] = position LLA
     *   [7-9] = velocity NED
     *   [10-13] = quaternion (w,x,y,z)
     *   [14-16] = euler angles
     *   [17-19] = uncertainties
     *   [20] = status
     *   [21] = validity flags
     * @return Error code (0 = success)
     */
    @JvmStatic
    external fun nativeGetState(handle: Long, output: DoubleArray): Int

    /**
     * Get covariance output.
     * @param output Array to receive output values (must be size 15)
     * @return Error code (0 = success)
     */
    @JvmStatic
    external fun nativeGetCovariance(handle: Long, output: DoubleArray): Int

    /**
     * Get IMU bias estimates.
     * @param output Array to receive output values (must be size 6)
     * @return Error code (0 = success)
     */
    @JvmStatic
    external fun nativeGetImuBias(handle: Long, output: DoubleArray): Int

    // ═══════════════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get version string.
     * @return Version string (e.g., "0.1.0")
     */
    @JvmStatic
    external fun nativeVersion(): String

    /**
     * Get error message for an error code.
     * @return Human-readable error message
     */
    @JvmStatic
    external fun nativeErrorMessage(code: Int): String
}
