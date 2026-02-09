/**
 * GNSS Location Provider
 *
 * Handles location data from Android FusedLocationProviderClient.
 * Provides position and velocity updates with proper timestamp conversion.
 *
 * ## Timestamps
 *
 * Android Location uses getElapsedRealtimeNanos() which is compatible with
 * sensor timestamps (SystemClock.elapsedRealtimeNanos).
 *
 * ## Velocity
 *
 * Location.getSpeed() provides ground speed (horizontal only).
 * Location.getBearing() provides course over ground.
 *
 * We convert to NED velocity components.
 *
 * ## Thread Safety
 *
 * Location callbacks arrive on the main looper by default.
 * The callback function must be thread-safe.
 */
package com.continuum.navigator.core.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.continuum.navigator.core.native.GnssPositionInput
import com.continuum.navigator.core.native.GnssVelocityInput
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin

/**
 * Callback interface for GNSS updates.
 */
interface GnssCallback {
    /**
     * Called when a new position is available.
     */
    fun onGnssPosition(input: GnssPositionInput)

    /**
     * Called when velocity is available.
     * May not be called if Location.hasSpeed() is false.
     */
    fun onGnssVelocity(input: GnssVelocityInput)
}

/**
 * Configuration for GNSS location provider.
 */
data class GnssConfig(
    /**
     * Desired update interval in milliseconds.
     * Actual interval depends on system and battery optimization.
     */
    val intervalMs: Long = 1_000, // 1 Hz

    /**
     * Minimum update interval in milliseconds.
     * Updates may arrive faster if available.
     */
    val minIntervalMs: Long = 500,

    /**
     * Location priority.
     * HIGH_ACCURACY uses GPS + network for best accuracy.
     * BALANCED_POWER uses WiFi/Cell for moderate accuracy with lower power.
     */
    val priority: Int = Priority.PRIORITY_HIGH_ACCURACY,

    /**
     * Minimum speed (m/s) to include velocity updates.
     * Below this, speed measurements are unreliable.
     */
    val minSpeedForVelocity: Float = 0.5f,
)

/**
 * GNSS location provider using FusedLocationProviderClient.
 */
class GnssLocationProvider(
    private val context: Context,
    private val config: GnssConfig = GnssConfig(),
    private val callback: GnssCallback,
) {
    companion object {
        private const val TAG = "GnssLocationProvider"

        /** Default vertical accuracy when not provided by GPS */
        private const val DEFAULT_VERTICAL_ACCURACY = 10.0

        /** Default speed accuracy when not provided */
        private const val DEFAULT_SPEED_ACCURACY = 0.5
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val isRunning = AtomicBoolean(false)
    private val positionCount = AtomicLong(0)
    private val velocityCount = AtomicLong(0)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { processLocation(it) }
        }
    }

    /**
     * Check if location permission is granted.
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if location services are enabled.
     */
    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE)
            as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Start location updates.
     *
     * @throws SecurityException if permission not granted
     * @throws IllegalStateException if location not available
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }

        if (!hasLocationPermission()) {
            isRunning.set(false)
            throw SecurityException("Location permission not granted")
        }

        val request = LocationRequest.Builder(config.intervalMs)
            .setMinUpdateIntervalMillis(config.minIntervalMs)
            .setPriority(config.priority)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.i(TAG, "GNSS location updates started at ${config.intervalMs}ms interval")
        } catch (e: SecurityException) {
            isRunning.set(false)
            throw e
        }
    }

    /**
     * Stop location updates.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.i(TAG, "GNSS stopped. Positions: ${positionCount.get()}, Velocities: ${velocityCount.get()}")
    }

    /**
     * Request a single location update.
     * Useful for getting initial position.
     *
     * @param callback Invoked with location or null if unavailable
     */
    fun requestSingleUpdate(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                callback(location)
            }.addOnFailureListener {
                Log.w(TAG, "Failed to get last location: ${it.message}")
                callback(null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting location", e)
            callback(null)
        }
    }

    /**
     * Get position/velocity counts for debugging.
     */
    fun getCounts(): Pair<Long, Long> = Pair(positionCount.get(), velocityCount.get())

    // ═══════════════════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════════════════

    private fun processLocation(location: Location) {
        // Convert to monotonic nanoseconds
        val timestampNs = location.elapsedRealtimeNanos

        // Extract position
        val positionInput = GnssPositionInput(
            timestampNs = timestampNs,
            latitudeDeg = location.latitude,
            longitudeDeg = location.longitude,
            altitudeM = if (location.hasAltitude()) location.altitude else 0.0,
            horizontalAccuracyM = if (location.hasAccuracy()) {
                location.accuracy.toDouble()
            } else {
                10.0 // Default accuracy
            },
            verticalAccuracyM = if (Build.VERSION.SDK_INT >= 26 &&
                location.hasVerticalAccuracy()
            ) {
                location.verticalAccuracyMeters.toDouble()
            } else {
                DEFAULT_VERTICAL_ACCURACY
            }
        )

        positionCount.incrementAndGet()
        callback.onGnssPosition(positionInput)

        // Extract velocity if available and above threshold
        if (location.hasSpeed() && location.speed >= config.minSpeedForVelocity) {
            val speed = location.speed.toDouble()
            val bearing = if (location.hasBearing()) {
                Math.toRadians(location.bearing.toDouble())
            } else {
                0.0
            }

            // Convert speed + bearing to NED velocity
            // Bearing is degrees clockwise from North
            val velocityNorth = speed * cos(bearing)
            val velocityEast = speed * sin(bearing)
            val velocityDown = 0.0 // No vertical velocity from GPS speed

            val speedAccuracy = if (Build.VERSION.SDK_INT >= 26 &&
                                     location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond.toDouble()
            } else {
                DEFAULT_SPEED_ACCURACY
            }

            val velocityInput = GnssVelocityInput(
                timestampNs = timestampNs,
                velocityNorthMps = velocityNorth,
                velocityEastMps = velocityEast,
                velocityDownMps = velocityDown,
                velocityAccuracyMps = speedAccuracy,
            )

            velocityCount.incrementAndGet()
            callback.onGnssVelocity(velocityInput)
        }
    }
}
