/**
 * Unit tests for Navigator data types (non-instrumented).
 *
 * These tests run on the local JVM without Android.
 */
package com.navigator.core

import com.continuum.navigator.core.native.CovarianceOutput
import com.continuum.navigator.core.native.FilterStatus
import com.continuum.navigator.core.native.GnssPositionInput
import com.continuum.navigator.core.native.GnssVelocityInput
import com.continuum.navigator.core.native.ImuBiasOutput
import com.continuum.navigator.core.native.ImuInput
import com.continuum.navigator.core.native.NavigationOutput
import com.continuum.navigator.core.native.NavigatorConfig
import com.continuum.navigator.core.native.NavigatorError
import com.continuum.navigator.core.native.NavigatorException
import com.continuum.navigator.core.native.SpeedInput
import com.continuum.navigator.core.native.ValidityFlags
import com.navigator.core.native.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for FFI data classes.
 */
class TypesTest {

    @Test
    fun testNavigatorConfig() {
        val config = NavigatorConfig(
            initialLatitudeDeg = 37.7749,
            initialLongitudeDeg = -122.4194,
            initialAltitudeM = 50.0,
        )

        assertEquals(37.7749, config.initialLatitudeDeg, 0.0001)
        assertEquals(-122.4194, config.initialLongitudeDeg, 0.0001)
        assertEquals(50.0, config.initialAltitudeM, 0.01)
    }

    @Test
    fun testNavigatorConfigDefault() {
        val config = NavigatorConfig()

        assertEquals(0.0, config.initialLatitudeDeg, 0.0001)
        assertEquals(0.0, config.initialLongitudeDeg, 0.0001)
        assertEquals(0.0, config.initialAltitudeM, 0.01)
    }

    @Test
    fun testImuInput() {
        val input = ImuInput(
            timestampNs = 123456789L,
            accelX = 0.1,
            accelY = 0.2,
            accelZ = 9.81,
            gyroX = 0.01,
            gyroY = 0.02,
            gyroZ = 0.03,
        )

        assertEquals(123456789L, input.timestampNs)
        assertEquals(0.1, input.accelX, 0.001)
        assertEquals(0.2, input.accelY, 0.001)
        assertEquals(9.81, input.accelZ, 0.001)
        assertEquals(0.01, input.gyroX, 0.001)
        assertEquals(0.02, input.gyroY, 0.001)
        assertEquals(0.03, input.gyroZ, 0.001)
    }

    @Test
    fun testGnssPositionInput() {
        val input = GnssPositionInput(
            timestampNs = 987654321L,
            latitudeDeg = 48.8584,
            longitudeDeg = 2.2945,
            altitudeM = 35.0,
            horizontalAccuracyM = 3.0,
            verticalAccuracyM = 5.0,
        )

        assertEquals(987654321L, input.timestampNs)
        assertEquals(48.8584, input.latitudeDeg, 0.0001)
        assertEquals(2.2945, input.longitudeDeg, 0.0001)
        assertEquals(35.0, input.altitudeM, 0.01)
        assertEquals(3.0, input.horizontalAccuracyM, 0.01)
        assertEquals(5.0, input.verticalAccuracyM, 0.01)
    }

    @Test
    fun testGnssVelocityInput() {
        val input = GnssVelocityInput(
            timestampNs = 111222333L,
            velocityNorthMps = 5.0,
            velocityEastMps = 3.0,
            velocityDownMps = -0.1,
            velocityAccuracyMps = 0.5,
        )

        assertEquals(111222333L, input.timestampNs)
        assertEquals(5.0, input.velocityNorthMps, 0.001)
        assertEquals(3.0, input.velocityEastMps, 0.001)
        assertEquals(-0.1, input.velocityDownMps, 0.001)
        assertEquals(0.5, input.velocityAccuracyMps, 0.001)
    }

    @Test
    fun testSpeedInput() {
        val input = SpeedInput(
            timestampNs = 444555666L,
            speedMps = 25.0,
            accuracyMps = 0.2,
        )

        assertEquals(444555666L, input.timestampNs)
        assertEquals(25.0, input.speedMps, 0.001)
        assertEquals(0.2, input.accuracyMps, 0.001)
    }

    @Test
    fun testNavigationOutput() {
        val output = NavigationOutput(
            timestampNs = 777888999L,
            latitudeDeg = 51.5074,
            longitudeDeg = -0.1278,
            altitudeM = 20.0,
            velocityNorthMps = 10.0,
            velocityEastMps = 5.0,
            velocityDownMps = 0.0,
            rollRad = 0.1,
            pitchRad = 0.05,
            yawRad = 1.57,
            status = FilterStatus.RUNNING,
            validityFlags = ValidityFlags.POSITION_LLA or ValidityFlags.VELOCITY,
        )

        assertEquals(777888999L, output.timestampNs)
        assertEquals(51.5074, output.latitudeDeg, 0.0001)
        assertEquals(-0.1278, output.longitudeDeg, 0.0001)
        assertEquals(20.0, output.altitudeM, 0.01)
        assertEquals(10.0, output.velocityNorthMps, 0.001)
        assertEquals(5.0, output.velocityEastMps, 0.001)
        assertEquals(0.0, output.velocityDownMps, 0.001)
        assertEquals(0.1, output.rollRad, 0.001)
        assertEquals(0.05, output.pitchRad, 0.001)
        assertEquals(1.57, output.yawRad, 0.001)
        assertEquals(FilterStatus.RUNNING, output.status)
    }

    @Test
    fun testCovarianceOutput() {
        val cov = CovarianceOutput(
            positionNorthStdM = 5.0,
            velocityNorthStdMps = 0.5,
            rollStdRad = 0.1,
        )

        assertEquals(5.0, cov.positionNorthStdM, 0.001)
        assertEquals(0.5, cov.velocityNorthStdMps, 0.001)
        assertEquals(0.1, cov.rollStdRad, 0.001)
    }

    @Test
    fun testImuBiasOutput() {
        val bias = ImuBiasOutput(
            accelBiasX = 0.01,
            accelBiasY = 0.02,
            accelBiasZ = 0.03,
            gyroBiasX = 0.001,
            gyroBiasY = 0.002,
            gyroBiasZ = 0.003,
        )

        assertEquals(0.01, bias.accelBiasX, 0.0001)
        assertEquals(0.02, bias.accelBiasY, 0.0001)
        assertEquals(0.03, bias.accelBiasZ, 0.0001)
        assertEquals(0.001, bias.gyroBiasX, 0.0001)
        assertEquals(0.002, bias.gyroBiasY, 0.0001)
        assertEquals(0.003, bias.gyroBiasZ, 0.0001)
    }

    @Test
    fun testFilterStatusConstants() {
        assertEquals(0, FilterStatus.UNINITIALIZED)
        assertEquals(1, FilterStatus.ALIGNING)
        assertEquals(2, FilterStatus.RUNNING)
        assertEquals(3, FilterStatus.COASTING)
        assertEquals(4, FilterStatus.DIVERGED)
    }

    @Test
    fun testValidityFlagsConstants() {
        assertEquals(0x01, ValidityFlags.POSITION_NED)
        assertEquals(0x02, ValidityFlags.POSITION_LLA)
        assertEquals(0x04, ValidityFlags.VELOCITY)
        assertEquals(0x08, ValidityFlags.ATTITUDE)
        assertEquals(0x10, ValidityFlags.POSITION_STD)
    }

    @Test
    fun testValidityFlagsCombination() {
        val flags = ValidityFlags.POSITION_NED or ValidityFlags.ATTITUDE
        assertEquals(0x09, flags)

        assertTrue((flags and ValidityFlags.POSITION_NED) != 0)
        assertFalse((flags and ValidityFlags.VELOCITY) != 0)
        assertTrue((flags and ValidityFlags.ATTITUDE) != 0)
    }

    @Test
    fun testNavigatorErrorConstants() {
        assertEquals(0, NavigatorError.SUCCESS)
        assertEquals(-150, NavigatorError.NOT_INITIALIZED)
        assertEquals(-1, NavigatorError.INVALID_LATITUDE)
        assertEquals(-10, NavigatorError.INVALID_PARAMETER)
        assertEquals(-100, NavigatorError.SENSOR_DROPOUT)
    }

    @Test
    fun testNavigatorExceptionWithCode() {
        val exception = NavigatorException("Test error", NavigatorError.INVALID_PARAMETER)

        assertEquals("Test error", exception.message)
        assertEquals(NavigatorError.INVALID_PARAMETER, exception.errorCode)
    }

    @Test
    fun testNavigatorExceptionWithoutCode() {
        val exception = NavigatorException("Generic error")

        assertEquals("Generic error", exception.message)
        assertEquals(NavigatorError.PROCESSING_ERROR, exception.errorCode)
    }

    @Test
    fun testDataClassEquality() {
        val config1 = NavigatorConfig(37.0, -122.0, 10.0)
        val config2 = NavigatorConfig(37.0, -122.0, 10.0)
        val config3 = NavigatorConfig(38.0, -122.0, 10.0)

        assertEquals(config1, config2)
        assertNotEquals(config1, config3)
    }

    @Test
    fun testDataClassCopy() {
        val original = NavigatorConfig(37.0, -122.0, 10.0)
        val copy = original.copy(initialLatitudeDeg = 38.0)

        assertEquals(37.0, original.initialLatitudeDeg, 0.001)
        assertEquals(38.0, copy.initialLatitudeDeg, 0.001)
        assertEquals(original.initialLongitudeDeg, copy.initialLongitudeDeg, 0.001)
    }

    @Test
    fun testDataClassHashCode() {
        val config1 = NavigatorConfig(37.0, -122.0, 10.0)
        val config2 = NavigatorConfig(37.0, -122.0, 10.0)

        assertEquals(config1.hashCode(), config2.hashCode())
    }
}
