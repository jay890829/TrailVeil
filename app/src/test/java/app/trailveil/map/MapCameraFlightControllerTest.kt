package app.trailveil.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraFlightControllerTest {
    @Test
    fun hostClaimSurvivesProviderBindingReplacementUntilTheOriginalCallback() {
        val host = MapCameraFlightController()

        // The camera request starts while fogRuntime is null, so only the composition host can
        // own the claim. A later provider binding must still see the host flight and stand down a
        // follow fix rather than replacing the request's zoom.
        val nullOwnerFlight = host.claim()
        assertTrue(host.isActive)
        assertTrue("follow must stand down while the old host flight is airborne", host.isActive)

        // A replacement/newer claim models a new programmed flight. The old terminal callback is
        // posted late and must not clear the replacement claim.
        val newerFlight = host.claim()
        assertTrue(host.isActive)
        assertFalse(host.release(nullOwnerFlight))
        assertTrue(host.isActive)
        assertTrue(host.release(newerFlight))
        assertFalse(host.isActive)
    }

    @Test
    fun staleTerminalCannotClearAnUnrelatedNewHostClaim() {
        val host = MapCameraFlightController()
        val first = host.claim()
        assertTrue(host.release(first))
        val second = host.claim()

        assertFalse(host.release(first))
        assertTrue(host.isActive)
        assertTrue(host.release(second))
        assertFalse(host.isActive)
    }
}
