/**
 * Instrumented tests for sensor providers.
 *
 * These tests verify sensor provider lifecycle and configuration.
 * Actual sensor data tests require physical device.
 */
package com.navigator.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.continuum.navigator.core.native.GnssPositionInput
import com.continuum.navigator.core.native.GnssVelocityInput
import com.continuum.navigator.core.sensors.GnssCallback
import com.continuum.navigator.core.sensors.GnssConfig
import com.continuum.navigator.core.sensors.GnssLocationProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.continuum.navigator.core.native.ImuInput
import com.continuum.navigator.core.sensors.DeviceOrientation
import com.continuum.navigator.core.sensors.ImuConfig
import com.continuum.navigator.core.sensors.ImuSensorProvider
import com.navigator.core.sensors.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for ImuSensorProvider.
 */
@RunWith(AndroidJUnit4::class)
class ImuSensorProviderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun testSensorsAvailable() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Most devices have accelerometer, gyroscope may be missing on some
        assertNotNull("Accelerometer should be available", accel)
        // gyro may be null on some devices - just log it
        if (gyro == null) {
            println("Warning: Gyroscope not available on this device")
        }
    }

    @Test
    fun testConfigDefault() {
        val config = ImuConfig()

        assertEquals("Default sampling period should be 10ms", 10_000, config.samplingPeriodUs)
        assertEquals("Default orientation should be PORTRAIT", DeviceOrientation.PORTRAIT, config.orientation)
    }

    @Test
    fun testConfigCustom() {
        val config = ImuConfig(
            samplingPeriodUs = 5_000,
            orientation = DeviceOrientation.LANDSCAPE_RIGHT,
        )

        assertEquals("Custom sampling period", 5_000, config.samplingPeriodUs)
        assertEquals("Custom orientation", DeviceOrientation.LANDSCAPE_RIGHT, config.orientation)
    }

    @Test
    fun testCreateProvider() {
        var callbackCount = 0
        val provider = ImuSensorProvider(
            context = context,
            config = ImuConfig(),
            callback = { callbackCount++ }
        )

        assertNotNull("Provider should be created", provider)
        // Don't start - just verify creation
    }

    @Test
    fun testStartStop() {
        var callbackCount = 0
        val latch = CountDownLatch(1)

        val provider = ImuSensorProvider(
            context = context,
            config = ImuConfig(samplingPeriodUs = 10_000),
            callback = {
                callbackCount++
                if (callbackCount >= 1) {
                    latch.countDown()
                }
            }
        )

        val started = provider.start()
        if (!started) {
            println("Warning: Sensors not available for start test")
            return
        }

        // Wait for at least one callback (or timeout)
        val received = latch.await(2, TimeUnit.SECONDS)

        provider.stop()

        if (received) {
            assertTrue("Should have received callbacks", callbackCount > 0)
        } else {
            println("Warning: No sensor callbacks received (may need physical device)")
        }
    }

    @Test
    fun testMultipleStartStop() {
        val provider = ImuSensorProvider(
            context = context,
            config = ImuConfig(),
            callback = { }
        )

        // Multiple starts should be safe
        provider.start()
        provider.start()

        // Multiple stops should be safe
        provider.stop()
        provider.stop()
    }

    @Test
    fun testDeviceOrientations() {
        // Test that all orientations can be used
        for (orientation in DeviceOrientation.values()) {
            val config = ImuConfig(orientation = orientation)
            val provider = ImuSensorProvider(
                context = context,
                config = config,
                callback = { }
            )
            assertNotNull("Provider with $orientation should be created", provider)
        }
    }

    @Test
    fun testImuInputStructure() {
        val input = ImuInput(
            timestampNs = 123456789L,
            accelX = 1.0,
            accelY = 2.0,
            accelZ = 9.81,
            gyroX = 0.1,
            gyroY = 0.2,
            gyroZ = 0.3,
        )

        assertEquals(123456789L, input.timestampNs)
        assertEquals(1.0, input.accelX, 0.001)
        assertEquals(2.0, input.accelY, 0.001)
        assertEquals(9.81, input.accelZ, 0.001)
        assertEquals(0.1, input.gyroX, 0.001)
        assertEquals(0.2, input.gyroY, 0.001)
        assertEquals(0.3, input.gyroZ, 0.001)
    }
}

/**
 * Tests for GnssLocationProvider.
 *
 * Note: Location tests require location permissions and may need a physical device.
 */
@RunWith(AndroidJUnit4::class)
class GnssLocationProviderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun testLocationServicesAvailable() {
        val client = LocationServices.getFusedLocationProviderClient(context)
        assertNotNull("FusedLocationProviderClient should be available", client)
    }

    @Test
    fun testConfigDefault() {
        val config = GnssConfig()

        assertEquals("Default interval should be 1000ms", 1000L, config.intervalMs)
        assertEquals("Default min interval should be 500ms", 500L, config.minIntervalMs)
    }

    @Test
    fun testConfigCustom() {
        val config = GnssConfig(
            intervalMs = 2000L,
            minIntervalMs = 1000L,
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        )

        assertEquals("Custom interval", 2000L, config.intervalMs)
        assertEquals("Custom min interval", 1000L, config.minIntervalMs)
    }

    @Test
    fun testCreateProvider() {
        val callback = object : GnssCallback {
            override fun onGnssPosition(input: GnssPositionInput) {}
            override fun onGnssVelocity(input: GnssVelocityInput) {}
        }

        val provider = GnssLocationProvider(
            context = context,
            config = GnssConfig(),
            callback = callback
        )

        assertNotNull("Provider should be created", provider)
    }

    @Test
    fun testHasLocationPermission() {
        val callback = object : GnssCallback {
            override fun onGnssPosition(input: GnssPositionInput) {}
            override fun onGnssVelocity(input: GnssVelocityInput) {}
        }

        val provider = GnssLocationProvider(
            context = context,
            config = GnssConfig(),
            callback = callback
        )

        // Permission may or may not be granted in test environment
        val hasPermission = provider.hasLocationPermission()
        println("Location permission granted: $hasPermission")
    }

    @Test
    fun testMultipleStartStop() {
        val callback = object : GnssCallback {
            override fun onGnssPosition(input: GnssPositionInput) {}
            override fun onGnssVelocity(input: GnssVelocityInput) {}
        }

        val provider = GnssLocationProvider(
            context = context,
            config = GnssConfig(),
            callback = callback
        )

        // Multiple starts/stops should be safe (even without permission)
        provider.start()
        provider.start()
        provider.stop()
        provider.stop()
    }

    @Test
    fun testGnssPositionInputStructure() {
        val input = GnssPositionInput(
            timestampNs = 987654321L,
            latitudeDeg = 37.7749,
            longitudeDeg = -122.4194,
            altitudeM = 10.0,
            horizontalAccuracyM = 5.0,
            verticalAccuracyM = 15.0,
        )

        assertEquals(987654321L, input.timestampNs)
        assertEquals(37.7749, input.latitudeDeg, 0.0001)
        assertEquals(-122.4194, input.longitudeDeg, 0.0001)
        assertEquals(10.0, input.altitudeM, 0.001)
        assertEquals(5.0, input.horizontalAccuracyM, 0.001)
        assertEquals(15.0, input.verticalAccuracyM, 0.001)
    }

    @Test
    fun testGnssVelocityInputStructure() {
        val input = GnssVelocityInput(
            timestampNs = 123456789L,
            velocityNorthMps = 10.0,
            velocityEastMps = 5.0,
            velocityDownMps = -0.5,
            velocityAccuracyMps = 0.5,
        )

        assertEquals(123456789L, input.timestampNs)
        assertEquals(10.0, input.velocityNorthMps, 0.001)
        assertEquals(5.0, input.velocityEastMps, 0.001)
        assertEquals(-0.5, input.velocityDownMps, 0.001)
        assertEquals(0.5, input.velocityAccuracyMps, 0.001)
    }
}
