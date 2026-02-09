/**
 * Navigation Foreground Service
 *
 * Runs the navigation engine as a foreground service.
 * This allows sensor and GPS processing even when the app is in background.
 *
 * ## Lifecycle
 *
 * 1. Start service with startForegroundService()
 * 2. Service starts sensors and GPS, creates native engine
 * 3. Sensor callbacks feed data to native engine
 * 4. Clients can bind to get navigation updates
 * 5. Stop with stopService() or ACTION_STOP intent
 *
 * ## Thread Model
 *
 * - IMU sensors: Callback on dedicated sensor thread
 * - GPS: Callback on main thread
 * - Native engine: Protected by NavigatorNative synchronization
 * - State publishing: On main thread via LiveData
 *
 * ## Notifications
 *
 * Android requires foreground services to show a notification.
 * This service shows a persistent notification while running.
 */
package com.continuum.navigator.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.continuum.navigator.core.logging.TripLogger
import com.continuum.navigator.core.native.GnssPositionInput
import com.continuum.navigator.core.native.GnssVelocityInput
import com.continuum.navigator.core.native.ImuInput
import com.continuum.navigator.core.native.NavigationOutput
import com.continuum.navigator.core.native.NavigatorConfig
import com.continuum.navigator.core.native.NavigatorError
import com.continuum.navigator.core.native.NavigatorException
import com.continuum.navigator.core.native.NavigatorNative
import com.continuum.navigator.core.sensors.DeviceOrientation
import com.continuum.navigator.core.sensors.GnssCallback
import com.continuum.navigator.core.sensors.GnssConfig
import com.continuum.navigator.core.sensors.GnssLocationProvider
import com.continuum.navigator.core.sensors.ImuConfig
import com.continuum.navigator.core.sensors.ImuSensorProvider
import com.google.android.gms.location.Priority
import com.continuum.navigator.core.R
import java.io.File

/**
 * Navigation service state.
 */
enum class NavigationServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

/**
 * Navigation service for continuous sensor/GPS processing.
 */
class NavigationService : Service() {

    companion object {
        private const val TAG = "NavigationService"
        private const val CHANNEL_ID = "navigation_channel"
        private const val NOTIFICATION_ID = 1

        /** Intent action to stop the service */
        const val ACTION_STOP = "com.navigator.core.STOP"

        /** Start the navigation service */
        fun start(context: Context, config: NavigatorConfig? = null) {
            val intent = Intent(context, NavigationService::class.java)
            if (config != null) {
                intent.putExtra("config_lat", config.initialLatitudeDeg)
                intent.putExtra("config_lon", config.initialLongitudeDeg)
                intent.putExtra("config_alt", config.initialAltitudeM)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the navigation service */
        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════

    private var navigator: NavigatorNative? = null
    private var imuProvider: ImuSensorProvider? = null
    private var gnssProvider: GnssLocationProvider? = null

    // Trip logging
    private var tripLogger: TripLogger? = null
    private var isLoggingTrip = false
    private var hadFirstGnssFix = false

    private val _serviceState = MutableLiveData(NavigationServiceState.STOPPED)
    private val _navigationOutput = MutableLiveData<NavigationOutput>()
    private val _errorMessage = MutableLiveData<String?>()
    private val _rawGnssPosition = MutableLiveData<GnssPositionInput>()

    // ═══════════════════════════════════════════════════════════════════════
    // Binder for clients
    // ═══════════════════════════════════════════════════════════════════════

    inner class LocalBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService

        /** Service state observable */
        val serviceState: LiveData<NavigationServiceState> = _serviceState

        /** Navigation output observable (updated at ~10Hz) */
        val navigationOutput: LiveData<NavigationOutput> = _navigationOutput

        /** Raw GNSS position observable (unfiltered, ~1Hz) */
        val rawGnssPosition: LiveData<GnssPositionInput> = _rawGnssPosition

        /** Error message observable */
        val errorMessage: LiveData<String?> = _errorMessage

        /** Get current state synchronously */
        fun getCurrentState(): NavigationOutput? = navigator?.getState()

        /** Check if navigator is initialized */
        fun isInitialized(): Boolean = navigator?.isInitialized() == true

        // Trip Logging API
        /** Check if trip logging is active */
        fun isTripLogging(): Boolean = isLoggingTrip

        /** Start trip logging */
        fun startTripLogging(): File? = this@NavigationService.startTripLogging()

        /** Stop trip logging */
        fun stopTripLogging() = this@NavigationService.stopTripLogging()

        /** Get list of recorded trips */
        fun getRecordedTrips(): List<File> {
            // Create TripLogger on demand to list files even if not recording
            if (tripLogger == null) {
                tripLogger = TripLogger(this@NavigationService)
            }
            return tripLogger?.getTrips() ?: emptyList()
        }
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // ═══════════════════════════════════════════════════════════════════════
    // Service Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NavigationService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Extract config from intent
        val config = if (intent?.hasExtra("config_lat") == true) {
            NavigatorConfig(
                initialLatitudeDeg = intent.getDoubleExtra("config_lat", 0.0),
                initialLongitudeDeg = intent.getDoubleExtra("config_lon", 0.0),
                initialAltitudeM = intent.getDoubleExtra("config_alt", 0.0),
            )
        } else {
            null
        }

        startNavigation(config)

        return START_STICKY
    }

    override fun onDestroy() {
        stopNavigation()
        super.onDestroy()
        Log.i(TAG, "NavigationService destroyed")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Navigation Control
    // ═══════════════════════════════════════════════════════════════════════

    private fun startNavigation(config: NavigatorConfig?) {
        if (_serviceState.value == NavigationServiceState.RUNNING) {
            Log.w(TAG, "Navigation already running")
            return
        }

        _serviceState.postValue(NavigationServiceState.STARTING)

        try {
            // Create native navigator
            navigator = if (config != null) {
                NavigatorNative.Companion.createWithConfig(config)
            } else {
                NavigatorNative.Companion.create()
            }
            Log.i(TAG, "Native navigator created. Version: ${NavigatorNative.Companion.version()}")

            // Create IMU provider
            imuProvider = ImuSensorProvider(
                context = this,
                config = ImuConfig(
                    samplingPeriodUs = 5_000, // 200 Hz
                    orientation = DeviceOrientation.PORTRAIT,
                ),
                callback = { input -> onImuSample(input) }
            )

            // Create GNSS provider
            gnssProvider = GnssLocationProvider(
                context = this,
                config = GnssConfig(
                    intervalMs = 1_000,
                    priority = Priority.PRIORITY_HIGH_ACCURACY,
                ),
                callback = object : GnssCallback {
                    override fun onGnssPosition(input: GnssPositionInput) {
                        this@NavigationService.handleGnssPosition(input)
                    }

                    override fun onGnssVelocity(input: GnssVelocityInput) {
                        this@NavigationService.handleGnssVelocity(input)
                    }
                }
            )

            // Start sensors
            imuProvider?.start()
            gnssProvider?.start()

            _serviceState.postValue(NavigationServiceState.RUNNING)
            Log.i(TAG, "Navigation started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start navigation", e)
            _errorMessage.postValue("Failed to start: ${e.message}")
            _serviceState.postValue(NavigationServiceState.ERROR)
            stopNavigation()
        }
    }

    private fun stopNavigation() {
        Log.i(TAG, "Stopping navigation...")

        // Stop trip logging first
        stopTripLogging()

        imuProvider?.stop()
        imuProvider = null

        gnssProvider?.stop()
        gnssProvider = null

        navigator?.destroy()
        navigator = null

        _serviceState.postValue(NavigationServiceState.STOPPED)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sensor Callbacks
    // ═══════════════════════════════════════════════════════════════════════

    private var lastOutputUpdateNs: Long = 0
    private val outputUpdateIntervalNs = 100_000_000L // 100ms = 10Hz updates

    private fun onImuSample(input: ImuInput) {
        // Log IMU sample for trip recording
        if (isLoggingTrip) {
            tripLogger?.logImu(
                timestampNs = input.timestampNs,
                accelX = input.accelX.toFloat(),
                accelY = input.accelY.toFloat(),
                accelZ = input.accelZ.toFloat(),
                gyroX = input.gyroX.toFloat(),
                gyroY = input.gyroY.toFloat(),
                gyroZ = input.gyroZ.toFloat(),
                accuracy = 3
            )
        }

        try {
            navigator?.processImu(input)

            // Periodically update output (not every IMU sample)
            val now = System.nanoTime()
            if (now - lastOutputUpdateNs > outputUpdateIntervalNs) {
                lastOutputUpdateNs = now
                navigator?.getState()?.let { output ->
                    _navigationOutput.postValue(output)
                }
            }
        } catch (e: NavigatorException) {
            // Expected errors (e.g., not initialized before first GNSS) - don't spam logs
            if (e.errorCode != NavigatorError.NOT_INITIALIZED) {
                Log.w(TAG, "IMU processing error: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected IMU error", e)
        }
    }

    private fun handleGnssPosition(input: GnssPositionInput) {
        // Publish raw GNSS position for trajectory visualization
        _rawGnssPosition.postValue(input)

        // Log GNSS position for trip recording
        if (isLoggingTrip) {
            tripLogger?.logGnssPosition(
                timestampNs = input.timestampNs,
                latitude = input.latitudeDeg,
                longitude = input.longitudeDeg,
                altitude = input.altitudeM,
                accuracyM = input.horizontalAccuracyM.toFloat(),
                provider = 0
            )

            // Log first fix event
            if (!hadFirstGnssFix) {
                hadFirstGnssFix = true
                tripLogger?.logFirstGnssFix()
            }
        }

        try {
            navigator?.processGnssPosition(input)
            Log.d(TAG, "GNSS position: ${input.latitudeDeg}, ${input.longitudeDeg}")

            // Update output after position update
            navigator?.getState()?.let { output ->
                _navigationOutput.postValue(output)
            }
        } catch (e: NavigatorException) {
            Log.w(TAG, "GNSS position error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected GNSS error", e)
        }
    }

    private fun handleGnssVelocity(input: GnssVelocityInput) {
        // Log GNSS velocity for trip recording
        if (isLoggingTrip) {
            tripLogger?.logGnssVelocity(
                timestampNs = input.timestampNs,
                velocityNorth = input.velocityNorthMps,
                velocityEast = input.velocityEastMps,
                velocityDown = input.velocityDownMps,
                speedAccuracyMps = input.velocityAccuracyMps.toFloat()
            )
        }

        try {
            navigator?.processGnssVelocity(input)
        } catch (e: NavigatorException) {
            // Velocity errors are less critical
            if (e.errorCode != NavigatorError.NOT_INITIALIZED) {
                Log.d(TAG, "GNSS velocity error: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected GNSS velocity error", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Trip Logging
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start trip logging.
     * All sensor inputs will be recorded for later replay.
     *
     * @return The trip file being recorded to, or null if failed
     */
    private fun startTripLogging(): File? {
        if (isLoggingTrip) {
            Log.w(TAG, "Trip logging already active")
            return tripLogger?.getCurrentTripFile()
        }

        // Create logger if needed
        if (tripLogger == null) {
            tripLogger = TripLogger(this)
        }

        return try {
            val file = tripLogger?.startTrip()
            isLoggingTrip = true
            hadFirstGnssFix = false
            Log.i(TAG, "Started trip logging: ${file?.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start trip logging", e)
            null
        }
    }

    /**
     * Stop trip logging.
     */
    private fun stopTripLogging() {
        if (!isLoggingTrip) return

        isLoggingTrip = false
        tripLogger?.stopTrip()
        Log.i(TAG, "Stopped trip logging")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Notification
    // ═══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.navigation_service_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Navigation service status"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, NavigationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.navigation_notification_title))
            .setContentText(getString(R.string.navigation_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
}
