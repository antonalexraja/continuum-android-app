/**
 * Trip Logger
 *
 * Captures all sensor inputs required to deterministically replay a navigation session.
 *
 * ## Features
 *
 * - Records IMU, GNSS, and speed sensor data with precise timestamps
 * - Binary append-only format for efficiency
 * - Non-blocking writes via background thread
 * - Versioned file format for forward compatibility
 *
 * ## Usage
 *
 * ```kotlin
 * val logger = TripLogger(context)
 * logger.startTrip()
 *
 * // During navigation:
 * logger.logImu(timestamp, accel, gyro, accuracy)
 * logger.logGnss(timestamp, lat, lon, alt, accuracy)
 *
 * logger.stopTrip()
 * ```
 *
 * ## Important
 *
 * This captures RAW sensor inputs only - not processed/smoothed values.
 * Replay feeds these back through the SAME engine for identical results.
 */
package com.continuum.navigator.core.logging

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Trip Logger for recording navigation sessions.
 */
class TripLogger(private val context: Context) {

    companion object {
        private const val TAG = "TripLogger"

        // File format
        const val MAGIC = "NAVTRIP1"
        const val VERSION = 1

        // Record types
        const val RECORD_IMU: Byte = 0x01
        const val RECORD_GNSS_POSITION: Byte = 0x02
        const val RECORD_GNSS_VELOCITY: Byte = 0x03
        const val RECORD_SPEED: Byte = 0x04
        const val RECORD_ENGINE_EVENT: Byte = 0x05
        const val RECORD_CONFIG: Byte = 0x06

        // Engine events
        const val EVENT_START: Byte = 0x01
        const val EVENT_STOP: Byte = 0x02
        const val EVENT_GNSS_FIRST_FIX: Byte = 0x03
        const val EVENT_RESET: Byte = 0x04

        // Buffer size for batch writes
        private const val WRITE_BUFFER_SIZE = 64 * 1024  // 64KB
    }

    // State
    private val isRecording = AtomicBoolean(false)
    private var outputStream: DataOutputStream? = null
    private var currentTripFile: File? = null
    private var startMonotonicNs: Long = 0

    // Non-blocking write queue
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val writerRunning = AtomicBoolean(false)
    private var writerThread: Thread? = null

    // Statistics
    private var imuCount: Long = 0
    private var gnssCount: Long = 0
    private var speedCount: Long = 0

    /**
     * Check if currently recording.
     */
    fun isRecording(): Boolean = isRecording.get()

    /**
     * Get the current trip file (if recording).
     */
    fun getCurrentTripFile(): File? = currentTripFile

    /**
     * Start recording a new trip.
     *
     * @param configHash Optional hash of engine configuration for verification
     * @return The trip file being recorded to
     */
    fun startTrip(configHash: Int = 0): File {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording, stopping previous trip")
            stopTrip()
        }

        // Create trips directory
        val tripsDir = File(context.filesDir, "trips")
        tripsDir.mkdirs()

        // Generate filename with timestamp
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val tripFile = File(tripsDir, "trip_$timestamp.bin")

        // Open file
        val fos = FileOutputStream(tripFile)
        val bos = BufferedOutputStream(fos, WRITE_BUFFER_SIZE)
        outputStream = DataOutputStream(bos)

        // Record start time
        startMonotonicNs = System.nanoTime()

        // Write header
        writeHeader(configHash)

        // Reset stats
        imuCount = 0
        gnssCount = 0
        speedCount = 0

        // Start writer thread
        startWriterThread()

        currentTripFile = tripFile
        isRecording.set(true)

        // Log start event
        logEngineEvent(EVENT_START)

        Log.i(TAG, "Started recording trip: ${tripFile.absolutePath}")
        return tripFile
    }

    /**
     * Stop recording the current trip.
     */
    fun stopTrip() {
        if (!isRecording.get()) {
            Log.w(TAG, "Not recording")
            return
        }

        // Log stop event
        logEngineEvent(EVENT_STOP)

        isRecording.set(false)

        // Stop writer thread
        stopWriterThread()

        // Flush and close
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing trip file: ${e.message}")
        }
        outputStream = null

        Log.i(TAG, "Stopped recording trip: ${currentTripFile?.absolutePath}")
        Log.i(TAG, "Stats: IMU=$imuCount, GNSS=$gnssCount, Speed=$speedCount")

        currentTripFile = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Header
    // ═══════════════════════════════════════════════════════════════════════

    private fun writeHeader(configHash: Int) {
        val stream = outputStream ?: return

        // Magic (8 bytes)
        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        stream.write(magicBytes)

        // Version (4 bytes)
        stream.writeInt(VERSION)

        // Created timestamp - Unix millis (8 bytes)
        stream.writeLong(System.currentTimeMillis())

        // Engine config hash (4 bytes)
        stream.writeInt(configHash)

        // Start monotonic ns (8 bytes)
        stream.writeLong(startMonotonicNs)

        // Reserved (32 bytes)
        stream.write(ByteArray(32))

        stream.flush()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // IMU Logging
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Log an IMU sample.
     *
     * @param timestampNs Monotonic timestamp in nanoseconds
     * @param accelX Accelerometer X in m/s²
     * @param accelY Accelerometer Y in m/s²
     * @param accelZ Accelerometer Z in m/s²
     * @param gyroX Gyroscope X in rad/s
     * @param gyroY Gyroscope Y in rad/s
     * @param gyroZ Gyroscope Z in rad/s
     * @param accuracy Sensor accuracy (0-3)
     */
    fun logImu(
        timestampNs: Long,
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        accuracy: Int = 3,
    ) {
        if (!isRecording.get()) return

        val record = ByteArray(49)
        val buf = ByteBuffer.wrap(record)

        buf.put(RECORD_IMU)
        buf.putLong(timestampNs)
        buf.putFloat(accelX)
        buf.putFloat(accelY)
        buf.putFloat(accelZ)
        buf.putFloat(gyroX)
        buf.putFloat(gyroY)
        buf.putFloat(gyroZ)
        buf.put(accuracy.toByte())
        // Reserved: 15 bytes (already zeroed)

        writeQueue.offer(record)
        imuCount++
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GNSS Logging
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Log a GNSS position fix.
     *
     * @param timestampNs Monotonic timestamp in nanoseconds
     * @param latitude Latitude in degrees
     * @param longitude Longitude in degrees
     * @param altitude Altitude in meters (WGS84)
     * @param accuracyM Horizontal accuracy in meters
     * @param provider Provider type (0=GPS, 1=Network, 2=Fused)
     */
    fun logGnssPosition(
        timestampNs: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracyM: Float,
        provider: Int = 0,
    ) {
        if (!isRecording.get()) return

        val record = ByteArray(57)
        val buf = ByteBuffer.wrap(record)

        buf.put(RECORD_GNSS_POSITION)
        buf.putLong(timestampNs)
        buf.putDouble(latitude)
        buf.putDouble(longitude)
        buf.putDouble(altitude)
        buf.putFloat(accuracyM)
        buf.put(provider.toByte())
        // Reserved: 19 bytes (already zeroed)

        writeQueue.offer(record)
        gnssCount++
    }

    /**
     * Log GNSS velocity.
     *
     * @param timestampNs Monotonic timestamp in nanoseconds
     * @param velocityNorth Velocity north in m/s
     * @param velocityEast Velocity east in m/s
     * @param velocityDown Velocity down in m/s
     * @param speedAccuracyMps Speed accuracy in m/s
     */
    fun logGnssVelocity(
        timestampNs: Long,
        velocityNorth: Double,
        velocityEast: Double,
        velocityDown: Double,
        speedAccuracyMps: Float,
    ) {
        if (!isRecording.get()) return

        val record = ByteArray(41)
        val buf = ByteBuffer.wrap(record)

        buf.put(RECORD_GNSS_VELOCITY)
        buf.putLong(timestampNs)
        buf.putDouble(velocityNorth)
        buf.putDouble(velocityEast)
        buf.putDouble(velocityDown)
        buf.putFloat(speedAccuracyMps)
        // Reserved: 4 bytes

        writeQueue.offer(record)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Speed Logging
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Log external speed input (e.g., from OBD2 or wheel speed sensor).
     *
     * @param timestampNs Monotonic timestamp in nanoseconds
     * @param speedMps Speed in m/s
     * @param source Source identifier (0=OBD2, 1=CAN, 2=Wheel)
     */
    fun logSpeed(
        timestampNs: Long,
        speedMps: Float,
        source: Int = 0,
    ) {
        if (!isRecording.get()) return

        val record = ByteArray(17)
        val buf = ByteBuffer.wrap(record)

        buf.put(RECORD_SPEED)
        buf.putLong(timestampNs)
        buf.putFloat(speedMps)
        buf.put(source.toByte())
        // Reserved: 3 bytes

        writeQueue.offer(record)
        speedCount++
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Engine Events
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Log an engine event.
     */
    fun logEngineEvent(eventType: Byte) {
        if (!isRecording.get() && eventType != EVENT_START) return

        val record = ByteArray(17)
        val buf = ByteBuffer.wrap(record)

        buf.put(RECORD_ENGINE_EVENT)
        buf.putLong(System.nanoTime())
        buf.put(eventType)
        // Reserved: 7 bytes

        writeQueue.offer(record)
    }

    /**
     * Log first GNSS fix event.
     */
    fun logFirstGnssFix() {
        logEngineEvent(EVENT_GNSS_FIRST_FIX)
    }

    /**
     * Log engine reset event.
     */
    fun logReset() {
        logEngineEvent(EVENT_RESET)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Configuration Logging
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Log engine configuration snapshot.
     *
     * @param configJson JSON string of configuration
     */
    fun logConfig(configJson: String) {
        if (!isRecording.get()) return

        val configBytes = configJson.toByteArray(Charsets.UTF_8)
        val record = ByteArray(13 + configBytes.size)
        val buf = ByteBuffer.wrap(record)

        buf.put(RECORD_CONFIG)
        buf.putLong(System.nanoTime())
        buf.putInt(configBytes.size)
        buf.put(configBytes)

        writeQueue.offer(record)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Background Writer
    // ═══════════════════════════════════════════════════════════════════════

    private fun startWriterThread() {
        writerRunning.set(true)
        writerThread = thread(name = "TripLogWriter", priority = Thread.MIN_PRIORITY) {
            Log.d(TAG, "Writer thread started")

            while (writerRunning.get() || writeQueue.isNotEmpty()) {
                val record = writeQueue.poll()
                if (record != null) {
                    try {
                        outputStream?.write(record)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing record: ${e.message}")
                    }
                } else {
                    // Queue empty, sleep briefly
                    Thread.sleep(1)
                }
            }

            Log.d(TAG, "Writer thread stopped")
        }
    }

    private fun stopWriterThread() {
        writerRunning.set(false)
        writerThread?.join(5000)  // Wait up to 5s for flush
        writerThread = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get list of recorded trip files.
     */
    fun getTrips(): List<File> {
        val tripsDir = File(context.filesDir, "trips")
        return tripsDir.listFiles { file ->
            file.extension == "bin" && file.name.startsWith("trip_")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Delete a trip file.
     */
    fun deleteTrip(file: File): Boolean {
        return file.delete()
    }

    /**
     * Delete all trip files.
     */
    fun deleteAllTrips() {
        getTrips().forEach { it.delete() }
    }
}
