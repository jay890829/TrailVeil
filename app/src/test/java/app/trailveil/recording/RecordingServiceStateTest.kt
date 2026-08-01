package app.trailveil.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingServiceStateTest {
    @Test
    fun exposesOnlyServiceAcceptedStoppingSessionAndClearsMatchingSession() {
        val state = RecordingServiceState()

        assertEquals(null, state.stoppingSessionId.value)
        state.markStopping(7L)
        assertEquals(7L, state.stoppingSessionId.value)

        state.clearStopping(8L)
        assertEquals(7L, state.stoppingSessionId.value)
        state.clearStopping(7L)
        assertEquals(null, state.stoppingSessionId.value)
    }

    @Test
    fun exposesAcceptedServiceLocationAndOnlyClearsMatchingSession() {
        val state = RecordingServiceState()
        val location = RecordingServiceLocation(
            sessionId = 7L,
            latitude = 25.033,
            longitude = 121.565,
        )

        state.publishAcceptedLocation(location)
        assertEquals(location, state.latestAcceptedLocation.value)
        state.clearLocation(8L)
        assertEquals(location, state.latestAcceptedLocation.value)
        state.clearLocation(7L)
        assertEquals(null, state.latestAcceptedLocation.value)
    }
}
