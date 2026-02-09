/**
 * Trip Reader
 *
 * Parses trip log files for replay or analysis.
 *
 * ## Usage
 *
 * ```kotlin
 * val reader = TripReader(tripFile)
 * val header = reader.readHeader()
 *
 * reader.forEachRecord { record ->
 *     when (record) {
 *         is TripRecord.Imu -> handleImu(record)
 *         is TripRecord.GnssPosition -> handleGnss(record)
 *         // ...
 *     }
 * }
 * ```
 */
package com.continuum.navigator.core.logging

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

/**
 * Header information from a trip log file.
 */
data class TripHeader(
    val version: Int,
    val createdTimestamp: Long,
    val configHash: Int,
    val startMonotonicNs: Long,
)

/**
 * Sealed class for trip log records.
 */
sealed class TripRecord {
    abstract val timestampNs: Long

    data class Imu(
        override val timestampNs: Long,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float,
        val accuracy: Int,
    ) : TripRecord()

    data class GnssPosition(
        override val timestampNs: Long,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracyM: Float,
        val provider: Int,
    ) : TripRecord()

    data class GnssVelocity(
        override val timestampNs: Long,
        val velocityNorth: Double,
        val velocityEast: Double,
        val velocityDown: Double,
        val speedAccuracyMps: Float,
    ) : TripRecord()

    data class Speed(
        override val timestampNs: Long,
        val speedMps: Float,
        val source: Int,
    ) : TripRecord()

    data class EngineEvent(
        override val timestampNs: Long,
        val eventType: Byte,
    ) : TripRecord() {
        val eventName: String
            get() = when (eventType) {
                TripLogger.EVENT_START -> "START"
                TripLogger.EVENT_STOP -> "STOP"
                TripLogger.EVENT_GNSS_FIRST_FIX -> "FIRST_FIX"
                TripLogger.EVENT_RESET -> "RESET"
                else -> "UNKNOWN($eventType)"
            }
    }

    data class Config(
        override val timestampNs: Long,
        val configJson: String,
    ) : TripRecord()
}

/**
 * Reader for trip log files.
 */
class TripReader(private val file: File) : AutoCloseable {

    companion object {
        private const val TAG = "TripReader"
        private const val HEADER_SIZE = 64
    }

    private var inputStream: DataInputStream? = null
    private var header: TripHeader? = null
    private var recordCount: Long = 0

    /**
     * Open the trip file and read header.
     *
     * @return The trip header
     * @throws IllegalArgumentException if file is invalid
     */
    fun open(): TripHeader {
        val fis = FileInputStream(file)
        val bis = BufferedInputStream(fis, 64 * 1024)
        inputStream = DataInputStream(bis)

        header = readHeader()
        return header!!
    }

    /**
     * Get the header (must call open() first).
     */
    fun getHeader(): TripHeader = header ?: throw IllegalStateException("Call open() first")

    private fun readHeader(): TripHeader {
        val stream = inputStream ?: throw IllegalStateException("Stream not open")

        // Read magic
        val magicBytes = ByteArray(8)
        stream.readFully(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)

        if (magic != TripLogger.MAGIC) {
            throw IllegalArgumentException("Invalid trip file magic: $magic")
        }

        // Read version
        val version = stream.readInt()
        if (version > TripLogger.VERSION) {
            Log.w(TAG, "Trip file version $version is newer than supported ${TripLogger.VERSION}")
        }

        // Read created timestamp
        val createdTimestamp = stream.readLong()

        // Read config hash
        val configHash = stream.readInt()

        // Read start monotonic ns
        val startMonotonicNs = stream.readLong()

        // Skip reserved
        stream.skipBytes(32)

        Log.d(TAG, "Trip header: version=$version, created=$createdTimestamp, startNs=$startMonotonicNs")

        return TripHeader(version, createdTimestamp, configHash, startMonotonicNs)
    }

    /**
     * Read the next record from the file.
     *
     * @return The next record, or null if EOF
     */
    fun readRecord(): TripRecord? {
        val stream = inputStream ?: return null

        val recordType: Byte = try {
            stream.readByte()
        } catch (e: EOFException) {
            return null
        }

        recordCount++

        return when (recordType) {
            TripLogger.RECORD_IMU -> readImuRecord(stream)
            TripLogger.RECORD_GNSS_POSITION -> readGnssPositionRecord(stream)
            TripLogger.RECORD_GNSS_VELOCITY -> readGnssVelocityRecord(stream)
            TripLogger.RECORD_SPEED -> readSpeedRecord(stream)
            TripLogger.RECORD_ENGINE_EVENT -> readEngineEventRecord(stream)
            TripLogger.RECORD_CONFIG -> readConfigRecord(stream)
            else -> {
                Log.w(TAG, "Unknown record type: $recordType at record $recordCount")
                null
            }
        }
    }

    private fun readImuRecord(stream: DataInputStream): TripRecord.Imu {
        val timestampNs = stream.readLong()
        val accelX = stream.readFloat()
        val accelY = stream.readFloat()
        val accelZ = stream.readFloat()
        val gyroX = stream.readFloat()
        val gyroY = stream.readFloat()
        val gyroZ = stream.readFloat()
        val accuracy = stream.readByte().toInt()
        stream.skipBytes(15) // Reserved

        return TripRecord.Imu(timestampNs, accelX, accelY, accelZ, gyroX, gyroY, gyroZ, accuracy)
    }

    private fun readGnssPositionRecord(stream: DataInputStream): TripRecord.GnssPosition {
        val timestampNs = stream.readLong()
        val latitude = stream.readDouble()
        val longitude = stream.readDouble()
        val altitude = stream.readDouble()
        val accuracyM = stream.readFloat()
        val provider = stream.readByte().toInt()
        stream.skipBytes(19) // Reserved

        return TripRecord.GnssPosition(timestampNs, latitude, longitude, altitude, accuracyM, provider)
    }

    private fun readGnssVelocityRecord(stream: DataInputStream): TripRecord.GnssVelocity {
        val timestampNs = stream.readLong()
        val velocityNorth = stream.readDouble()
        val velocityEast = stream.readDouble()
        val velocityDown = stream.readDouble()
        val speedAccuracyMps = stream.readFloat()
        stream.skipBytes(4) // Reserved

        return TripRecord.GnssVelocity(timestampNs, velocityNorth, velocityEast, velocityDown, speedAccuracyMps)
    }

    private fun readSpeedRecord(stream: DataInputStream): TripRecord.Speed {
        val timestampNs = stream.readLong()
        val speedMps = stream.readFloat()
        val source = stream.readByte().toInt()
        stream.skipBytes(3) // Reserved

        return TripRecord.Speed(timestampNs, speedMps, source)
    }

    private fun readEngineEventRecord(stream: DataInputStream): TripRecord.EngineEvent {
        val timestampNs = stream.readLong()
        val eventType = stream.readByte()
        stream.skipBytes(7) // Reserved

        return TripRecord.EngineEvent(timestampNs, eventType)
    }

    private fun readConfigRecord(stream: DataInputStream): TripRecord.Config {
        val timestampNs = stream.readLong()
        val length = stream.readInt()
        val configBytes = ByteArray(length)
        stream.readFully(configBytes)
        val configJson = String(configBytes, Charsets.UTF_8)

        return TripRecord.Config(timestampNs, configJson)
    }

    /**
     * Iterate over all records.
     */
    inline fun forEachRecord(action: (TripRecord) -> Unit) {
        var record = readRecord()
        while (record != null) {
            action(record)
            record = readRecord()
        }
    }

    /**
     * Read all records into a list.
     * Note: May use significant memory for large trips.
     */
    fun readAllRecords(): List<TripRecord> {
        val records = mutableListOf<TripRecord>()
        forEachRecord { records.add(it) }
        return records
    }

    /**
     * Get record count (after reading all records).
     */
    fun getRecordCount(): Long = recordCount

    override fun close() {
        inputStream?.close()
        inputStream = null
    }
}

/**
 * Summary of a trip file.
 */
data class TripSummary(
    val file: File,
    val header: TripHeader,
    val duration: Long,  // Duration in nanoseconds
    val imuCount: Int,
    val gnssCount: Int,
    val speedCount: Int,
    val firstGnssPosition: TripRecord.GnssPosition?,
    val lastGnssPosition: TripRecord.GnssPosition?,
)

/**
 * Analyze a trip file without loading all records into memory.
 */
fun analyzeTripFile(file: File): TripSummary {
    val reader = TripReader(file)
    reader.open()

    var firstTimestamp: Long? = null
    var lastTimestamp: Long = 0
    var imuCount = 0
    var gnssCount = 0
    var speedCount = 0
    var firstGnss: TripRecord.GnssPosition? = null
    var lastGnss: TripRecord.GnssPosition? = null

    reader.forEachRecord { record ->
        if (firstTimestamp == null) firstTimestamp = record.timestampNs
        lastTimestamp = record.timestampNs

        when (record) {
            is TripRecord.Imu -> imuCount++
            is TripRecord.GnssPosition -> {
                gnssCount++
                if (firstGnss == null) firstGnss = record
                lastGnss = record
            }
            is TripRecord.Speed -> speedCount++
            else -> {}
        }
    }

    reader.close()

    return TripSummary(
        file = file,
        header = reader.getHeader(),
        duration = lastTimestamp - (firstTimestamp ?: 0),
        imuCount = imuCount,
        gnssCount = gnssCount,
        speedCount = speedCount,
        firstGnssPosition = firstGnss,
        lastGnssPosition = lastGnss,
    )
}
