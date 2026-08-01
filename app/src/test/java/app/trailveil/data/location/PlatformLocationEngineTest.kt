package app.trailveil.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformLocationEngineTest {

    @Test
    fun `request mapping preserves cadence and displacement`() {
        val mapped = LocationUpdateRequest(
            intervalMillis = 8_000,
            minIntervalMillis = 3_000,
            minDisplacementMeters = 12.5,
        ).toPlatformLocationRequestSpec()

        assertEquals(8_000, mapped.intervalMillis)
        assertEquals(3_000, mapped.minIntervalMillis)
        assertEquals(12.5f, mapped.minDistanceMeters)
    }

    @Test
    fun `unavailable provider fails before registration`() {
        val failure = runCatching {
            DefaultLocationProviderSelector.select(
                preferredProvider = "fused",
                state = LocationProviderState(exists = false, enabled = false),
            )
        }.exceptionOrNull()

        assertTrue(failure is LocationProviderUnavailableException)
        assertTrue(requireNotNull(failure).message!!.contains("fused"))
    }

    @Test
    fun `disabled provider reports actionable terminal failure`() {
        val failure = runCatching {
            DefaultLocationProviderSelector.select(
                preferredProvider = "fused",
                state = LocationProviderState(exists = true, enabled = false),
            )
        }.exceptionOrNull()

        assertTrue(failure is LocationProviderDisabledException)
        assertTrue(requireNotNull(failure).message!!.contains("disabled"))
    }
    @Test
    fun `raw conversion preserves optional values when present`() {
        val raw = PlatformLocationSample(
            latitude = 25.033,
            longitude = 121.5654,
            accuracyMeters = 4.5,
            elapsedRealtimeNanos = 123_456_789,
            epochMillis = 1_725_000_000_000,
            altitudeMeters = 32.25,
            speedMetersPerSecond = 1.5,
            bearingDegrees = 90.0,
            isMock = true,
        ).toRawLocationFix()

        assertEquals(25.033, raw.latitude, 0.0)
        assertEquals(121.5654, raw.longitude, 0.0)
        assertEquals(4.5, raw.horizontalAccuracyMeters, 0.0)
        assertEquals(123_456_789, raw.capturedAtElapsedRealtimeNanos)
        assertEquals(1_725_000_000_000, raw.epochMillis)
        assertEquals(32.25, raw.altitudeMeters!!, 0.0)
        assertEquals(1.5, raw.speedMetersPerSecond!!, 0.0)
        assertEquals(90.0, raw.bearingDegrees!!, 0.0)
        assertEquals(true, raw.isMock)
    }

    @Test
    fun `raw conversion uses nan accuracy and absent optional fields`() {
        val raw = PlatformLocationSample(
            latitude = 0.0,
            longitude = 0.0,
            accuracyMeters = null,
            elapsedRealtimeNanos = 1,
            epochMillis = 2,
            altitudeMeters = null,
            speedMetersPerSecond = null,
            bearingDegrees = null,
            isMock = false,
        ).toRawLocationFix()

        assertTrue(raw.horizontalAccuracyMeters.isNaN())
        assertNull(raw.altitudeMeters)
        assertNull(raw.speedMetersPerSecond)
        assertNull(raw.bearingDegrees)
        assertEquals(false, raw.isMock)
    }
}
