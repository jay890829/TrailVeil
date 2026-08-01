package app.trailveil.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRouteTest {
    @Test
    fun recordingRouteIsStable() {
        assertEquals("recording", RecordingRoute)
        assertTrue(RecordingRoute.matches(Regex("[a-z][a-z0-9_-]*")))
    }
}
