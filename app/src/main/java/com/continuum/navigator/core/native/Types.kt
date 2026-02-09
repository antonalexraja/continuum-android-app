/**
 * Navigator Native FFI Types
 *
 * Kotlin data classes that mirror the C FFI structures.
 * These are used for communication with the native navigation engine.
 *
 * IMPORTANT: Field order and types must match the Rust #[repr(C)] structs exactly.
 */
package com.continuum.navigator.core.native

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Configuration for the navigator engine.
 *
 * All fields have sensible defaults. Only set non-zero values for fields
 * you want to override.
 */
data class NavigatorConfig(
    /** Initial latitude in degrees (-90 to 90). 0 = not set */
    val initialLatitudeDeg: Double = 0.0,
    /** Initial longitude in degrees (-180 to 180). 0 = not set */
    val initialLongitudeDeg: Double = 0.0,
    /** Initial altitude in meters (WGS84) */
    val initialAltitudeM: Double = 0.0,
    /** Initial heading in radians (0 = North, π/2 = East) */
    val initialHeadingRad: Double = 0.0,
    /** Accelerometer noise density (m/s²/√Hz). 0 = use default */
    val accelNoiseDensity: Double = 0.0,
    /** Gyroscope noise density (rad/s/√Hz). 0 = use default */
    val gyroNoiseDensity: Double = 0.0,
    /** Accelerometer bias random walk (m/s³/√Hz). 0 = use default */
    val accelBiasRw: Double = 0.0,
    /** Gyroscope bias random walk (rad/s²/√Hz). 0 = use default */
    val gyroBiasRw: Double = 0.0,
    /** Position process noise std (m). 0 = use default */
    val positionNoiseStdM: Double = 0.0,
    /** Velocity process noise std (m/s). 0 = use default */
    val velocityNoiseStdMps: Double = 0.0,
    /** Attitude process noise std (rad). 0 = use default */
    val attitudeNoiseStdRad: Double = 0.0,
)

/**
 * IMU sensor input sample.
 *
 * Coordinate system: Right-handed body frame
 * - X: Forward (front of device)
 * - Y: Right (right side of device)
 * - Z: Down (through screen)
 *
 * Note: Android sensor coordinates are different and must be transformed!
 */
data class ImuInput(
    /** Monotonic timestamp in nanoseconds */
    val timestampNs: Long,
    /** X-axis acceleration (m/s²) */
    val accelX: Double,
    /** Y-axis acceleration (m/s²) */
    val accelY: Double,
    /** Z-axis acceleration (m/s²) */
    val accelZ: Double,
    /** X-axis angular rate (rad/s) */
    val gyroX: Double,
    /** Y-axis angular rate (rad/s) */
    val gyroY: Double,
    /** Z-axis angular rate (rad/s) */
    val gyroZ: Double,
)

/**
 * GNSS position measurement input.
 */
data class GnssPositionInput(
    /** Monotonic timestamp in nanoseconds */
    val timestampNs: Long,
    /** Latitude in degrees (-90 to 90) */
    val latitudeDeg: Double,
    /** Longitude in degrees (-180 to 180) */
    val longitudeDeg: Double,
    /** Altitude in meters (WGS84 ellipsoid) */
    val altitudeM: Double,
    /** Horizontal accuracy (1-sigma) in meters */
    val horizontalAccuracyM: Double,
    /** Vertical accuracy (1-sigma) in meters */
    val verticalAccuracyM: Double,
)

/**
 * GNSS velocity measurement input.
 */
data class GnssVelocityInput(
    /** Monotonic timestamp in nanoseconds */
    val timestampNs: Long,
    /** North velocity in m/s */
    val velocityNorthMps: Double,
    /** East velocity in m/s */
    val velocityEastMps: Double,
    /** Down velocity in m/s */
    val velocityDownMps: Double,
    /** Velocity accuracy (1-sigma) in m/s */
    val velocityAccuracyMps: Double,
)

/**
 * External speed measurement input (e.g., from vehicle OBD).
 */
data class SpeedInput(
    /** Monotonic timestamp in nanoseconds */
    val timestampNs: Long,
    /** Speed magnitude in m/s (always positive) */
    val speedMps: Double,
    /** Speed accuracy (1-sigma) in m/s */
    val accuracyMps: Double,
)

/**
 * Navigation state output from the filter.
 */
data class NavigationOutput(
    /** Timestamp in nanoseconds */
    val timestampNs: Long = 0L,

    // Position in local NED frame (meters)
    val positionNorthM: Double = 0.0,
    val positionEastM: Double = 0.0,
    val positionDownM: Double = 0.0,

    // Position in geodetic coordinates
    val latitudeDeg: Double = 0.0,
    val longitudeDeg: Double = 0.0,
    val altitudeM: Double = 0.0,

    // Velocity in NED frame (m/s)
    val velocityNorthMps: Double = 0.0,
    val velocityEastMps: Double = 0.0,
    val velocityDownMps: Double = 0.0,

    // Attitude quaternion (w, x, y, z) - body to NED
    val attitudeW: Double = 1.0,
    val attitudeX: Double = 0.0,
    val attitudeY: Double = 0.0,
    val attitudeZ: Double = 0.0,

    // Euler angles (radians)
    val rollRad: Double = 0.0,
    val pitchRad: Double = 0.0,
    val yawRad: Double = 0.0,

    // Uncertainties (1-sigma)
    val positionStdM: Double = 0.0,
    val velocityStdMps: Double = 0.0,
    val attitudeStdRad: Double = 0.0,

    // Filter status
    val status: Int = FilterStatus.UNINITIALIZED,
    val validityFlags: Int = 0,
) {
    /** Speed over ground in m/s */
    val speed: Double
        get() = sqrt(
            velocityNorthMps * velocityNorthMps + velocityEastMps * velocityEastMps
        )

    /** Ground track in radians (0 = North, π/2 = East) */
    val groundTrackRad: Double
        get() = atan2(velocityEastMps, velocityNorthMps)

    /** Ground track in degrees (0 = North, 90 = East) */
    val groundTrackDeg: Double
        get() = Math.toDegrees(groundTrackRad)

    /** Heading in degrees (0 = North, 90 = East) */
    val headingDeg: Double
        get() = Math.toDegrees(yawRad).let { if (it < 0) it + 360 else it }

    /** Check if position (NED) is valid */
    val hasPositionNed: Boolean
        get() = (validityFlags and ValidityFlags.POSITION_NED) != 0

    /** Check if position (LLA) is valid */
    val hasPositionLla: Boolean
        get() = (validityFlags and ValidityFlags.POSITION_LLA) != 0

    /** Check if velocity is valid */
    val hasVelocity: Boolean
        get() = (validityFlags and ValidityFlags.VELOCITY) != 0

    /** Check if attitude is valid */
    val hasAttitude: Boolean
        get() = (validityFlags and ValidityFlags.ATTITUDE) != 0

    /** Check if filter is running */
    val isRunning: Boolean
        get() = status == FilterStatus.RUNNING
}

/**
 * Covariance/uncertainty output.
 */
data class CovarianceOutput(
    // Position uncertainties (m)
    val positionNorthStdM: Double = 0.0,
    val positionEastStdM: Double = 0.0,
    val positionDownStdM: Double = 0.0,

    // Velocity uncertainties (m/s)
    val velocityNorthStdMps: Double = 0.0,
    val velocityEastStdMps: Double = 0.0,
    val velocityDownStdMps: Double = 0.0,

    // Attitude uncertainties (rad)
    val rollStdRad: Double = 0.0,
    val pitchStdRad: Double = 0.0,
    val yawStdRad: Double = 0.0,

    // Gyro bias uncertainties (rad/s)
    val gyroBiasXStd: Double = 0.0,
    val gyroBiasYStd: Double = 0.0,
    val gyroBiasZStd: Double = 0.0,

    // Accel bias uncertainties (m/s²)
    val accelBiasXStd: Double = 0.0,
    val accelBiasYStd: Double = 0.0,
    val accelBiasZStd: Double = 0.0,
)

/**
 * IMU bias estimates output.
 */
data class ImuBiasOutput(
    // Gyroscope biases (rad/s)
    val gyroBiasX: Double = 0.0,
    val gyroBiasY: Double = 0.0,
    val gyroBiasZ: Double = 0.0,

    // Accelerometer biases (m/s²)
    val accelBiasX: Double = 0.0,
    val accelBiasY: Double = 0.0,
    val accelBiasZ: Double = 0.0,
)

/**
 * Filter status codes.
 * Must match the native FILTER_STATUS_* constants.
 */
object FilterStatus {
    /** Not yet initialized with position */
    const val UNINITIALIZED = 0
    /** Collecting initial data for alignment */
    const val ALIGNING = 1
    /** Normal operation */
    const val RUNNING = 2
    /** No recent updates, coasting on IMU */
    const val COASTING = 3
    /** Filter has diverged, needs reset */
    const val DIVERGED = 4

    fun toString(status: Int): String = when (status) {
        UNINITIALIZED -> "Uninitialized"
        ALIGNING -> "Aligning"
        RUNNING -> "Running"
        COASTING -> "Coasting"
        DIVERGED -> "Diverged"
        else -> "Unknown($status)"
    }
}

/**
 * Validity flags for navigation output.
 * Must match the native VALIDITY_* constants.
 */
object ValidityFlags {
    const val POSITION_NED = 1 shl 0
    const val POSITION_LLA = 1 shl 1
    const val VELOCITY = 1 shl 2
    const val ATTITUDE = 1 shl 3
    const val POSITION_STD = 1 shl 4
    const val VELOCITY_STD = 1 shl 5
    const val ATTITUDE_STD = 1 shl 6
    const val BIASES_CALIBRATED = 1 shl 7
}

/**
 * Navigator error codes.
 * Must match the native NAVIGATOR_* constants.
 */
object NavigatorError {
    const val SUCCESS = 0

    // Warnings (positive)
    const val WARN_STALE_DATA = 1
    const val WARN_DEGRADED = 2

    // Validation errors (-1 to -9)
    const val INVALID_LATITUDE = -1
    const val INVALID_LONGITUDE = -2
    const val INVALID_ALTITUDE = -3
    const val QUATERNION_INVALID = -4
    const val COVARIANCE_INVALID = -5
    const val INVALID_PARAMETER = -10

    // Numerical errors (-50 to -59)
    const val MATRIX_SINGULAR = -50
    const val NUMERICAL = -51
    const val DIVERGENCE = -52
    const val CONVERGENCE = -53

    // Sensor errors (-100 to -149)
    const val SENSOR_DROPOUT = -100
    const val SENSOR_INVALID = -101
    const val SENSOR_FUTURE = -102
    const val SENSOR_STALE = -103

    // State errors (-150 to -199)
    const val NOT_INITIALIZED = -150
    const val INVALID_STATE = -151

    // Time errors (-200 to -249)
    const val TIME_NOT_MONOTONIC = -200
    const val TIME_DELTA_LARGE = -201

    // System errors (-250 to -299)
    const val NULL_POINTER = -250
    const val PANIC = -251

    fun isSuccess(code: Int): Boolean = code >= 0
    fun isWarning(code: Int): Boolean = code > 0
    fun isError(code: Int): Boolean = code < 0

    fun toString(code: Int): String = when (code) {
        SUCCESS -> "Success"
        WARN_STALE_DATA -> "Warning: Stale data"
        WARN_DEGRADED -> "Warning: Degraded"
        INVALID_LATITUDE -> "Invalid latitude"
        INVALID_LONGITUDE -> "Invalid longitude"
        INVALID_ALTITUDE -> "Invalid altitude"
        QUATERNION_INVALID -> "Invalid quaternion"
        COVARIANCE_INVALID -> "Invalid covariance"
        INVALID_PARAMETER -> "Invalid parameter"
        MATRIX_SINGULAR -> "Singular matrix"
        NUMERICAL -> "Numerical error"
        DIVERGENCE -> "Filter divergence"
        CONVERGENCE -> "Convergence failure"
        SENSOR_DROPOUT -> "Sensor dropout"
        SENSOR_INVALID -> "Invalid sensor data"
        SENSOR_FUTURE -> "Future timestamp"
        SENSOR_STALE -> "Stale sensor data"
        NOT_INITIALIZED -> "Not initialized"
        INVALID_STATE -> "Invalid state"
        TIME_NOT_MONOTONIC -> "Time not monotonic"
        TIME_DELTA_LARGE -> "Time delta too large"
        NULL_POINTER -> "Null pointer"
        PANIC -> "Native panic"
        else -> "Unknown error ($code)"
    }
}
