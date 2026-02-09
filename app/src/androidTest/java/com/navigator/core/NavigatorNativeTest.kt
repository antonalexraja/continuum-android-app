/**
 * Instrumented tests for Navigator native library and Android bindings.
 *
 * These tests run on an Android device or emulator.
 */
package com.navigator.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.continuum.navigator.core.native.GnssPositionInput
import com.continuum.navigator.core.native.GnssVelocityInput
import com.continuum.navigator.core.native.ImuInput
import com.continuum.navigator.core.native.NavigatorConfig
import com.continuum.navigator.core.native.NavigatorError
import com.continuum.navigator.core.native.NavigatorException
import com.continuum.navigator.core.native.NavigatorJni
import com.continuum.navigator.core.native.NavigatorNative
import com.continuum.navigator.core.native.SpeedInput
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for NavigatorNative wrapper class.
 */
@RunWith(AndroidJUnit4::class)
class NavigatorNativeTest {

    private var navigator: NavigatorNative? = null

    @After
    fun tearDown() {
        navigator?.destroy()
        navigator = null
    }

    @Test
    fun testLibraryLoaded() {
        // Should not throw
        val loaded = NavigatorJni.isLoaded
        assertTrue("Native library should be loaded", loaded)
    }

    @Test
    fun testVersion() {
        // Version should return non-empty string
        val version = NavigatorNative.version()
        assertTrue("Version should not be empty", version.isNotEmpty())
        // Version should match semver pattern
        assertTrue("Version should match semver", version.matches(Regex("\\d+\\.\\d+\\.\\d+.*")))
    }

    @Test
    fun testCreateDefault() {
        navigator = NavigatorNative.create()
        assertNotNull("Navigator should be created", navigator)
        assertTrue("Navigator should be initialized", navigator!!.isInitialized())
    }

    @Test
    fun testCreateWithConfig() {
        val config = NavigatorConfig(
            initialLatitudeDeg = 37.7749,
            initialLongitudeDeg = -122.4194,
            initialAltitudeM = 10.0,
        )
        navigator = NavigatorNative.createWithConfig(config)
        assertNotNull("Navigator should be created", navigator)
        assertTrue("Navigator should be initialized", navigator!!.isInitialized())
    }

    @Test
    fun testDestroy() {
        navigator = NavigatorNative.create()
        assertTrue("Navigator should be initialized", navigator!!.isInitialized())

        navigator!!.destroy()
        assertFalse("Navigator should not be initialized after destroy", navigator!!.isInitialized())
    }

    @Test
    fun testReset() {
        navigator = NavigatorNative.create()

        // Should not throw
        navigator!!.reset()
        assertTrue("Navigator should still be initialized after reset", navigator!!.isInitialized())
    }

    @Test
    fun testProcessImu() {
        navigator = NavigatorNative.create()

        val imu = ImuInput(
            timestampNs = System.nanoTime(),
            accelX = 0.0,
            accelY = 0.0,
            accelZ = 9.81,
            gyroX = 0.0,
            gyroY = 0.0,
            gyroZ = 0.0,
        )

        // Should not throw
        navigator!!.processImu(imu)
    }

    @Test
    fun testProcessGnssPosition() {
        navigator = NavigatorNative.create()

        val position = GnssPositionInput(
            timestampNs = System.nanoTime(),
            latitudeDeg = 37.7749,
            longitudeDeg = -122.4194,
            altitudeM = 10.0,
            horizontalAccuracyM = 5.0,
            verticalAccuracyM = 10.0,
        )

        // Should not throw
        navigator!!.processGnssPosition(position)
    }

    @Test
    fun testProcessGnssVelocity() {
        navigator = NavigatorNative.create()

        val velocity = GnssVelocityInput(
            timestampNs = System.nanoTime(),
            velocityNorthMps = 1.0,
            velocityEastMps = 0.5,
            velocityDownMps = 0.0,
            velocityAccuracyMps = 0.5,
        )

        // Should not throw
        navigator!!.processGnssVelocity(velocity)
    }

    @Test
    fun testProcessSpeed() {
        navigator = NavigatorNative.create()

        val speed = SpeedInput(
            timestampNs = System.nanoTime(),
            speedMps = 5.0,
            accuracyMps = 0.2,
        )

        // Should not throw
        navigator!!.processSpeed(speed)
    }

    @Test
    fun testGetState() {
        navigator = NavigatorNative.create()

        // Process some data first
        val imu = ImuInput(
            timestampNs = System.nanoTime(),
            accelX = 0.0,
            accelY = 0.0,
            accelZ = 9.81,
            gyroX = 0.0,
            gyroY = 0.0,
            gyroZ = 0.0,
        )
        navigator!!.processImu(imu)

        val output = navigator!!.getState()
        assertNotNull("Output should not be null", output)
        assertTrue("Timestamp should be positive", output.timestampNs > 0)
    }

    @Test
    fun testGetCovariance() {
        navigator = NavigatorNative.create()

        val covariance = navigator!!.getCovariance()
        assertNotNull("Covariance should not be null", covariance)
        assertTrue("Position std should be non-negative", covariance.positionNorthStdM >= 0.0)
        assertTrue("Velocity std should be non-negative", covariance.velocityNorthStdMps >= 0.0)
        assertTrue("Roll std should be non-negative", covariance.rollStdRad >= 0.0)
    }

    @Test
    fun testGetImuBias() {
        navigator = NavigatorNative.create()

        val bias = navigator!!.getImuBias()
        assertNotNull("IMU bias should not be null", bias)
        // Bias values can be any real number
    }

    @Test
    fun testCloseable() {
        val nav = NavigatorNative.create()
        assertTrue("Navigator should be initialized", nav.isInitialized())

        nav.use {
            assertTrue("Navigator should be initialized in use block", it.isInitialized())
        }

        assertFalse("Navigator should not be initialized after use block", nav.isInitialized())
    }

    @Test
    fun testOperationsAfterDestroyThrow() {
        navigator = NavigatorNative.create()
        navigator!!.destroy()

        // Operations after destroy should throw
        try {
            navigator!!.processImu(
                ImuInput(
                    timestampNs = System.nanoTime(),
                    accelX = 0.0,
                    accelY = 0.0,
                    accelZ = 9.81,
                    gyroX = 0.0,
                    gyroY = 0.0,
                    gyroZ = 0.0,
                )
            )
            fail("Should throw NavigatorException after destroy")
        } catch (e: NavigatorException) {
            assertEquals("Error should be NOT_INITIALIZED", NavigatorError.NOT_INITIALIZED, e.errorCode)
        }
    }

    @Test
    fun testThreadSafety() {
        navigator = NavigatorNative.create()

        // Run operations from multiple threads
        val threads = (1..10).map { threadId ->
            Thread {
                for (i in 1..100) {
                    try {
                        navigator!!.processImu(
                            ImuInput(
                                timestampNs = System.nanoTime(),
                                accelX = 0.0,
                                accelY = 0.0,
                                accelZ = 9.81,
                                gyroX = 0.0,
                                gyroY = 0.0,
                                gyroZ = 0.0,
                            )
                        )
                        navigator!!.getState()
                    } catch (e: NavigatorException) {
                        // Expected if navigator was destroyed
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should not crash
        assertTrue("Navigator should still be valid", navigator!!.isInitialized())
    }
}
