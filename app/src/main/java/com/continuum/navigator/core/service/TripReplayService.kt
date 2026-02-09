/**
 * Trip Replay Service
 *
 * Replays recorded trip logs through the navigation engine.
 * This runs as a separate mode from live navigation.
 *
 * ## Architecture
 *
 * - Creates a fresh NavigatorNative instance for replay
 * - ReplayController feeds recorded data at original timing
 * - Engine is UNAWARE it's receiving replayed data
 * - Same rendering pipeline as live navigation
 *
 * ## Determinism Guarantee
 *
 * Same inputs + same engine = same outputs.
 * Dead Reckoning works identically because:
 * 1. IMU data is replayed with original timestamps
 * 2. GNSS data is replayed with original timestamps
 * 3. Engine state is fresh (not contaminated by previous sessions)
 *
 * ## Usage
 *
 * ```kotlin
 * // Start replay
 * TripReplayService.start(context, tripFile)
 *
 * // Bind to get updates
 * bindService(intent, connection, BIND_AUTO_CREATE)
 *
 * // Control playback
 * binder.pause()
 * binder.resume()
 * binder.setSpeed(2.0f)
 * binder.seekToPercent(0.5f)
 *
 * // Stop
 * TripReplayService.stop(context)
 * ```
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
import com.continuum.navigator.core.logging.ReplayController
import com.continuum.navigator.core.logging.ReplayListener
import com.continuum.navigator.core.logging.ReplayProgress
import com.continuum.navigator.core.logging.ReplayState
import com.continuum.navigator.core.logging.TripHeader
import com.continuum.navigator.core.native.NavigationOutput
import com.continuum.navigator.core.native.NavigatorNative
import com.continuum.navigator.core.R
import java.io.File

/**
 * Replay service state.
 */
enum class ReplayServiceState {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    PAUSED,
    FINISHED,
    ERROR,
}

/**
 * Service for replaying recorded trips.
 */
class TripReplayService : Service(), ReplayListener {

    companion object {
        private const val TAG = "TripReplayService"
        private const val CHANNEL_ID = "replay_channel"
        private const val NOTIFICATION_ID = 2

        /** Intent extra for trip file path */
        const val EXTRA_TRIP_FILE = "trip_file_path"

        /** Intent action to stop the service */
        const val ACTION_STOP = "com.navigator.core.STOP_REPLAY"

        /** Start replay of a trip file */
        fun start(context: Context, tripFile: File) {
            val intent = Intent(context, TripReplayService::class.java).apply {
                putExtra(EXTRA_TRIP_FILE, tripFile.absolutePath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the replay service */
        fun stop(context: Context) {
            context.stopService(Intent(context, TripReplayService::class.java))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════

    private var navigator: NavigatorNative? = null
    private var replayController: ReplayController? = null
    private var tripFile: File? = null

    private val _serviceState = MutableLiveData(ReplayServiceState.IDLE)
    private val _navigationOutput = MutableLiveData<NavigationOutput>()
    private val _replayProgress = MutableLiveData<ReplayProgress>()
    private val _errorMessage = MutableLiveData<String?>()

    // ═══════════════════════════════════════════════════════════════════════
    // Binder for clients
    // ═══════════════════════════════════════════════════════════════════════

    inner class ReplayBinder : Binder() {
        fun getService(): TripReplayService = this@TripReplayService

        /** Service state observable */
        val serviceState: LiveData<ReplayServiceState> = _serviceState

        /** Navigation output observable */
        val navigationOutput: LiveData<NavigationOutput> = _navigationOutput

        /** Replay progress observable */
        val replayProgress: LiveData<ReplayProgress> = _replayProgress

        /** Error message observable */
        val errorMessage: LiveData<String?> = _errorMessage

        /** Get trip header */
        fun getTripHeader(): TripHeader? = replayController?.getHeader()

        /** Get total duration in nanoseconds */
        fun getTotalDurationNs(): Long = replayController?.getTotalDurationNs() ?: 0

        /** Get total record count */
        fun getTotalRecords(): Int = replayController?.getTotalRecords() ?: 0

        /** Start/resume playback */
        fun play() {
            val state = replayController?.getState()
            when (state) {
                ReplayState.READY -> replayController?.start()
                ReplayState.PAUSED -> replayController?.resume()
                else -> Log.w(TAG, "Cannot play in state: $state")
            }
        }

        /** Pause playback */
        fun pause() {
            replayController?.pause()
        }

        /** Stop and reset to beginning */
        fun reset() {
            replayController?.stop()
        }

        /** Set playback speed (1.0 = real-time) */
        fun setSpeed(speed: Float) {
            replayController?.setPlaybackSpeed(speed)
        }

        /** Seek to percentage (0.0 to 1.0) */
        fun seekToPercent(percent: Float) {
            val totalDuration = replayController?.getTotalDurationNs() ?: 0
            val targetNs = (totalDuration * percent.coerceIn(0f, 1f)).toLong()
            replayController?.seekTo(targetNs)
        }

        /** Seek to timestamp in nanoseconds */
        fun seekTo(timestampNs: Long) {
            replayController?.seekTo(timestampNs)
        }

        /** Check if currently playing */
        fun isPlaying(): Boolean = replayController?.getState() == ReplayState.PLAYING

        /** Check if paused */
        fun isPaused(): Boolean = replayController?.getState() == ReplayState.PAUSED

        /** Check if finished */
        fun isFinished(): Boolean = replayController?.getState() == ReplayState.FINISHED
    }

    private val binder = ReplayBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // ═══════════════════════════════════════════════════════════════════════
    // Service Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TripReplayService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Get trip file from intent
        val filePath = intent?.getStringExtra(EXTRA_TRIP_FILE)
        if (filePath == null) {
            Log.e(TAG, "No trip file specified")
            _errorMessage.postValue("No trip file specified")
            _serviceState.postValue(ReplayServiceState.ERROR)
            return START_NOT_STICKY
        }

        tripFile = File(filePath)
        if (!tripFile!!.exists()) {
            Log.e(TAG, "Trip file not found: $filePath")
            _errorMessage.postValue("Trip file not found")
            _serviceState.postValue(ReplayServiceState.ERROR)
            return START_NOT_STICKY
        }

        loadAndPrepare()

        return START_STICKY
    }

    override fun onDestroy() {
        stopReplay()
        super.onDestroy()
        Log.i(TAG, "TripReplayService destroyed")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Replay Control
    // ═══════════════════════════════════════════════════════════════════════

    private fun loadAndPrepare() {
        _serviceState.postValue(ReplayServiceState.LOADING)

        try {
            // Create fresh navigator for replay
            // This ensures deterministic behavior (no state from previous sessions)
            navigator = NavigatorNative.Companion.create()
            Log.i(TAG, "Created fresh navigator for replay")

            // Create replay controller
            replayController = ReplayController(tripFile!!).apply {
                setNavigator(navigator!!)
                setListener(this@TripReplayService)
            }

            // Load trip file
            if (!replayController!!.load()) {
                throw IllegalStateException("Failed to load trip file")
            }

            val header = replayController!!.getHeader()
            val totalRecords = replayController!!.getTotalRecords()
            val durationSec = replayController!!.getTotalDurationNs() / 1_000_000_000.0

            Log.i(TAG, "Loaded trip: $totalRecords records, ${durationSec}s duration")
            Log.i(TAG, "Trip version: ${header?.version}, created: ${header?.createdTimestamp}")

            _serviceState.postValue(ReplayServiceState.READY)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load trip", e)
            _errorMessage.postValue("Failed to load trip: ${e.message}")
            _serviceState.postValue(ReplayServiceState.ERROR)
        }
    }

    private fun stopReplay() {
        Log.i(TAG, "Stopping replay...")

        replayController?.release()
        replayController = null

        navigator?.destroy()
        navigator = null

        _serviceState.postValue(ReplayServiceState.IDLE)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ReplayListener
    // ═══════════════════════════════════════════════════════════════════════

    override fun onStateChanged(state: ReplayState) {
        val serviceState = when (state) {
            ReplayState.IDLE -> ReplayServiceState.IDLE
            ReplayState.LOADING -> ReplayServiceState.LOADING
            ReplayState.READY -> ReplayServiceState.READY
            ReplayState.PLAYING -> ReplayServiceState.PLAYING
            ReplayState.PAUSED -> ReplayServiceState.PAUSED
            ReplayState.FINISHED -> ReplayServiceState.FINISHED
            ReplayState.ERROR -> ReplayServiceState.ERROR
        }
        _serviceState.postValue(serviceState)
    }

    override fun onProgress(progress: ReplayProgress) {
        _replayProgress.postValue(progress)
    }

    override fun onNavigationOutput(output: NavigationOutput) {
        _navigationOutput.postValue(output)
    }

    override fun onError(message: String) {
        Log.e(TAG, "Replay error: $message")
        _errorMessage.postValue(message)
    }

    override fun onReplayFinished() {
        Log.i(TAG, "Replay finished")
        updateNotification("Replay complete")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Notification
    // ═══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.replay_service_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Trip replay status"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String = "Replaying trip..."): Notification {
        val stopIntent = Intent(this, TripReplayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.replay_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }
}
