/**
 * Trip Replay Controller
 *
 * Replays recorded trip logs through the navigation engine.
 * The engine receives the same inputs as during live navigation,
 * ensuring Dead Reckoning works identically.
 *
 * ## Key Features
 *
 * - Deterministic replay of sensor data
 * - Original timing preserved (with optional speed-up)
 * - Engine is unaware it's receiving replayed data
 * - Supports pause/resume/seek
 *
 * ## Usage
 *
 * ```kotlin
 * val controller = ReplayController(tripFile)
 * controller.setNavigator(navigator)
 * controller.setOutputCallback { output -> updateMap(output) }
 *
 * controller.start()
 * // ...
 * controller.pause()
 * controller.resume()
 * controller.stop()
 * ```
 *
 * ## Important
 *
 * Live sensor providers MUST be stopped during replay to avoid
 * mixing live and recorded data.
 */
package com.continuum.navigator.core.logging

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.continuum.navigator.core.native.GnssPositionInput
import com.continuum.navigator.core.native.GnssVelocityInput
import com.continuum.navigator.core.native.ImuInput
import com.continuum.navigator.core.native.NavigationOutput
import com.continuum.navigator.core.native.NavigatorError
import com.continuum.navigator.core.native.NavigatorException
import com.continuum.navigator.core.native.NavigatorNative
import com.continuum.navigator.core.native.SpeedInput
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Replay state.
 */
enum class ReplayState {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    PAUSED,
    FINISHED,
    ERROR,
}

/**
 * Replay progress information.
 */
data class ReplayProgress(
    val currentTimestampNs: Long,
    val totalDurationNs: Long,
    val recordsPlayed: Long,
    val totalRecords: Long,
    val progressPercent: Float,
)

/**
 * Listener for replay events.
 */
interface ReplayListener {
    fun onStateChanged(state: ReplayState)
    fun onProgress(progress: ReplayProgress)
    fun onNavigationOutput(output: NavigationOutput)
    fun onError(message: String)
    fun onReplayFinished()
}

/**
 * Controller for replaying trip logs.
 */
class ReplayController(
    private val tripFile: File,
) {
    companion object {
        private const val TAG = "ReplayController"
    }

    // State
    private val _state = AtomicBoolean(false)
    private var state: ReplayState = ReplayState.IDLE
    private val isPaused = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    // Trip data
    private lateinit var reader: TripReader
    private lateinit var header: TripHeader
    private var records: List<TripRecord> = emptyList()
    private var recordIndex = 0

    // Timing
    private var playbackSpeed: Float = 1.0f
    private var startTimeNs: Long = 0
    private var baseRecordTimestampNs: Long = 0
    private val currentTimestampNs = AtomicLong(0)

    // Navigator (engine is unaware of replay)
    private var navigator: NavigatorNative? = null

    // Threading
    private var replayThread: HandlerThread? = null
    private var replayHandler: Handler? = null

    // Listener
    private var listener: ReplayListener? = null

    // Statistics
    private var recordsPlayed: Long = 0

    // ═══════════════════════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Set the navigator instance to receive replayed data.
     * This should be a fresh navigator instance for deterministic replay.
     */
    fun setNavigator(navigator: NavigatorNative) {
        this.navigator = navigator
    }

    /**
     * Set replay listener.
     */
    fun setListener(listener: ReplayListener?) {
        this.listener = listener
    }

    /**
     * Set playback speed multiplier (1.0 = real-time, 2.0 = 2x speed).
     */
    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.1f, 100f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Loading
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Load the trip file.
     * Call this before start() to prepare the replay.
     *
     * @return True if loaded successfully
     */
    fun load(): Boolean {
        if (state != ReplayState.IDLE && state != ReplayState.ERROR) {
            Log.w(TAG, "Cannot load in state: $state")
            return false
        }

        setState(ReplayState.LOADING)

        return try {
            reader = TripReader(tripFile)
            header = reader.open()

            // Read all records into memory for random access
            records = reader.readAllRecords()
            recordIndex = 0

            reader.close()

            if (records.isEmpty()) {
                throw IllegalStateException("Trip file has no records")
            }

            baseRecordTimestampNs = records.first().timestampNs
            currentTimestampNs.set(baseRecordTimestampNs)

            Log.i(TAG, "Loaded trip: ${records.size} records, " +
                "duration=${(records.last().timestampNs - baseRecordTimestampNs) / 1_000_000_000}s")

            setState(ReplayState.READY)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load trip: ${e.message}", e)
            listener?.onError("Failed to load trip: ${e.message}")
            setState(ReplayState.ERROR)
            false
        }
    }

    /**
     * Get trip header information.
     */
    fun getHeader(): TripHeader? = if (::header.isInitialized) header else null

    /**
     * Get total duration in nanoseconds.
     */
    fun getTotalDurationNs(): Long {
        if (records.isEmpty()) return 0
        return records.last().timestampNs - records.first().timestampNs
    }

    /**
     * Get total record count.
     */
    fun getTotalRecords(): Int = records.size

    // ═══════════════════════════════════════════════════════════════════════
    // Playback Control
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start replay.
     */
    fun start() {
        if (navigator == null) {
            Log.e(TAG, "Navigator not set, cannot start replay")
            listener?.onError("Navigator not set")
            return
        }

        if (state == ReplayState.IDLE) {
            if (!load()) return
        }

        if (state != ReplayState.READY && state != ReplayState.PAUSED) {
            Log.w(TAG, "Cannot start in state: $state")
            return
        }

        // Start replay thread
        replayThread = HandlerThread("TripReplay").apply { start() }
        replayHandler = Handler(replayThread!!.looper)

        isPaused.set(false)
        isStopping.set(false)
        startTimeNs = System.nanoTime()

        if (state == ReplayState.READY) {
            // Starting from beginning
            recordIndex = 0
            recordsPlayed = 0
        }

        setState(ReplayState.PLAYING)
        Log.i(TAG, "Started replay at ${playbackSpeed}x speed")

        // Schedule first record
        scheduleNextRecord()
    }

    /**
     * Pause replay.
     */
    fun pause() {
        if (state != ReplayState.PLAYING) return

        isPaused.set(true)
        setState(ReplayState.PAUSED)
        Log.i(TAG, "Paused replay at record $recordIndex")
    }

    /**
     * Resume replay.
     */
    fun resume() {
        if (state != ReplayState.PAUSED) return

        isPaused.set(false)
        startTimeNs = System.nanoTime()
        baseRecordTimestampNs = records[recordIndex].timestampNs

        setState(ReplayState.PLAYING)
        Log.i(TAG, "Resumed replay at record $recordIndex")

        scheduleNextRecord()
    }

    /**
     * Stop replay.
     */
    fun stop() {
        if (state == ReplayState.IDLE || state == ReplayState.FINISHED) return

        isStopping.set(true)
        replayHandler?.removeCallbacksAndMessages(null)
        replayThread?.quitSafely()
        replayThread = null
        replayHandler = null

        recordIndex = 0
        recordsPlayed = 0

        setState(ReplayState.READY)
        Log.i(TAG, "Stopped replay")
    }

    /**
     * Seek to a specific timestamp.
     *
     * @param timestampNs Target timestamp in nanoseconds (relative to trip start)
     */
    fun seekTo(timestampNs: Long) {
        if (records.isEmpty()) return

        val targetNs = baseRecordTimestampNs + timestampNs

        // Binary search for closest record
        var low = 0
        var high = records.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (records[mid].timestampNs < targetNs) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        recordIndex = low
        currentTimestampNs.set(records[recordIndex].timestampNs)

        // Reset timing base
        startTimeNs = System.nanoTime()
        baseRecordTimestampNs = records[recordIndex].timestampNs

        Log.d(TAG, "Seeked to record $recordIndex")

        // Report progress
        reportProgress()

        // If playing, continue from new position
        if (state == ReplayState.PLAYING) {
            replayHandler?.removeCallbacksAndMessages(null)
            scheduleNextRecord()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Replay Engine
    // ═══════════════════════════════════════════════════════════════════════

    private fun scheduleNextRecord() {
        if (isStopping.get() || isPaused.get()) return
        if (recordIndex >= records.size) {
            onReplayComplete()
            return
        }

        val record = records[recordIndex]
        val recordOffsetNs = record.timestampNs - baseRecordTimestampNs

        // Calculate wall-clock delay based on playback speed
        val targetWallTimeNs = startTimeNs + (recordOffsetNs / playbackSpeed).toLong()
        val nowNs = System.nanoTime()
        val delayNs = targetWallTimeNs - nowNs

        val delayMs = (delayNs / 1_000_000L).coerceAtLeast(0L)

        replayHandler?.postDelayed({
            if (!isStopping.get() && !isPaused.get()) {
                processRecord(record)
                recordIndex++
                recordsPlayed++
                scheduleNextRecord()
            }
        }, delayMs)
    }

    private fun processRecord(record: TripRecord) {
        currentTimestampNs.set(record.timestampNs)

        when (record) {
            is TripRecord.Imu -> processImu(record)
            is TripRecord.GnssPosition -> processGnssPosition(record)
            is TripRecord.GnssVelocity -> processGnssVelocity(record)
            is TripRecord.Speed -> processSpeed(record)
            is TripRecord.EngineEvent -> processEngineEvent(record)
            is TripRecord.Config -> processConfig(record)
        }

        // Report progress periodically (every 100 records)
        if (recordsPlayed % 100 == 0L) {
            reportProgress()
        }
    }

    private fun processImu(record: TripRecord.Imu) {
        val input = ImuInput(
            timestampNs = record.timestampNs,
            accelX = record.accelX.toDouble(),
            accelY = record.accelY.toDouble(),
            accelZ = record.accelZ.toDouble(),
            gyroX = record.gyroX.toDouble(),
            gyroY = record.gyroY.toDouble(),
            gyroZ = record.gyroZ.toDouble(),
        )

        try {
            navigator?.processImu(input)

            // Get output after IMU processing (not every sample)
            if (recordsPlayed % 10 == 0L) {
                navigator?.getState()?.let { output ->
                    listener?.onNavigationOutput(output)
                }
            }
        } catch (e: NavigatorException) {
            // Expected for uninitialized state
            if (e.errorCode != NavigatorError.NOT_INITIALIZED) {
                Log.w(TAG, "IMU replay error: ${e.message}")
            }
        }
    }

    private fun processGnssPosition(record: TripRecord.GnssPosition) {
        val input = GnssPositionInput(
            timestampNs = record.timestampNs,
            latitudeDeg = record.latitude,
            longitudeDeg = record.longitude,
            altitudeM = record.altitude,
            horizontalAccuracyM = record.accuracyM.toDouble(),
            verticalAccuracyM = record.accuracyM.toDouble() * 1.5, // Approximate
        )

        try {
            navigator?.processGnssPosition(input)

            // Always report output after GNSS
            navigator?.getState()?.let { output ->
                listener?.onNavigationOutput(output)
            }
        } catch (e: NavigatorException) {
            Log.w(TAG, "GNSS replay error: ${e.message}")
        }
    }

    private fun processGnssVelocity(record: TripRecord.GnssVelocity) {
        val input = GnssVelocityInput(
            timestampNs = record.timestampNs,
            velocityNorthMps = record.velocityNorth,
            velocityEastMps = record.velocityEast,
            velocityDownMps = record.velocityDown,
            velocityAccuracyMps = record.speedAccuracyMps.toDouble(),
        )

        try {
            navigator?.processGnssVelocity(input)
        } catch (e: NavigatorException) {
            // Velocity errors less critical
        }
    }

    private fun processSpeed(record: TripRecord.Speed) {
        val input = SpeedInput(
            timestampNs = record.timestampNs,
            speedMps = record.speedMps.toDouble(),
            accuracyMps = 1.0,  // Default accuracy for replayed speed
        )

        try {
            navigator?.processSpeed(input)
        } catch (e: NavigatorException) {
            Log.d(TAG, "Speed replay error: ${e.message}")
        }
    }

    private fun processEngineEvent(record: TripRecord.EngineEvent) {
        Log.d(TAG, "Engine event: ${record.eventName}")
        // Events are informational during replay
    }

    private fun processConfig(record: TripRecord.Config) {
        Log.d(TAG, "Config: ${record.configJson.take(100)}...")
        // Could be used to verify engine configuration matches
    }

    private fun onReplayComplete() {
        setState(ReplayState.FINISHED)
        Log.i(TAG, "Replay complete: $recordsPlayed records played")
        listener?.onReplayFinished()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // State & Progress
    // ═══════════════════════════════════════════════════════════════════════

    private fun setState(newState: ReplayState) {
        state = newState
        listener?.onStateChanged(newState)
    }

    fun getState(): ReplayState = state

    private fun reportProgress() {
        val totalDuration = getTotalDurationNs()
        val current = currentTimestampNs.get() - records.firstOrNull()?.timestampNs.let { it ?: 0L }

        val progress = ReplayProgress(
            currentTimestampNs = current,
            totalDurationNs = totalDuration,
            recordsPlayed = recordsPlayed,
            totalRecords = records.size.toLong(),
            progressPercent = if (totalDuration > 0) (current.toFloat() / totalDuration * 100f) else 0f,
        )

        listener?.onProgress(progress)
    }

    fun getCurrentProgress(): ReplayProgress {
        val totalDuration = getTotalDurationNs()
        val current = currentTimestampNs.get() - records.firstOrNull()?.timestampNs.let { it ?: 0L }

        return ReplayProgress(
            currentTimestampNs = current,
            totalDurationNs = totalDuration,
            recordsPlayed = recordsPlayed,
            totalRecords = records.size.toLong(),
            progressPercent = if (totalDuration > 0) (current.toFloat() / totalDuration * 100f) else 0f,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        records = emptyList()
        navigator = null
        listener = null
        setState(ReplayState.IDLE)
    }
}
