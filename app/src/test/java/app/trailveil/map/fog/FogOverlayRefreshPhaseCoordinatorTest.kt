package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogOverlayRefreshPhaseCoordinatorTest {
    @Test
    fun eachGenerationRecordsStartAndClearsOnlyAfterCanonicalPublish() {
        val cleared = mutableListOf<Int>()
        val coordinator = FogOverlayRefreshPhaseCoordinator {
            cleared += cleared.size
        }

        assertTrue(coordinator.onGenerationStarted(1L))
        assertFalse(coordinator.onGenerationStarted(1L))
        assertFalse(coordinator.onCanonicalPublished(0L))
        assertTrue(coordinator.onCanonicalPublished(1L))
        assertFalse(coordinator.onCanonicalPublished(1L))
        assertEquals(1, cleared.size)

        assertTrue(coordinator.onGenerationStarted(2L))
        assertFalse(coordinator.onCanonicalPublished(1L))
        assertTrue(coordinator.onCanonicalPublished(2L))
        assertEquals(2, cleared.size)
        assertEquals(
            FogOverlayRefreshSnapshot(2L, generationStarted = true, canonicalPublished = true),
            coordinator.snapshot(),
        )
    }

    @Test
    fun clearFailureKeepsPhaseBoundedAndReturnsFailure() {
        var attempts = 0
        val coordinator = FogOverlayRefreshPhaseCoordinator {
            attempts += 1
            error("overlay unavailable")
        }

        assertTrue(coordinator.onGenerationStarted(4L))
        assertFalse(coordinator.onGenerationStarted(4L))
        assertFalse(coordinator.onCanonicalPublished(4L))
        assertEquals(1, attempts)
    }
}
