package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogOverlayStatePolicyTest {
    @Test
    fun provenOverlayStaysVisibleAcrossRepeatedHealthyLocationPublications() {
        val gate = FogOverlayVisibilityGate()
        val generation = 1L
        assertTrue(gate.revealForProvenGeneration(generation))

        // Live GPS fixes update marker geometry without changing canonical fog. Healthy camera
        // publications must therefore preserve the already-proven visible state and must not
        // manufacture a reproof/hide transition for every fix.
        repeat(5) {
            assertFalse(
                reconcileFogOverlayCoordinatorState(
                    installedGeneration = generation,
                    pendingGeneration = null,
                    coverUp = false,
                    retryScheduled = false,
                    terminal = false,
                    hideOverlays = gate::hide,
                ),
            )
            assertEquals(generation, gate.visibleGeneration)
        }
    }

    @Test
    fun healthyPublicationCannotReopenAnOverlayHiddenForAnInstalledGenerationReproof() {
        val gate = FogOverlayVisibilityGate()
        val generation = 1L

        // G1 has already passed its proof and is visible.
        assertTrue(gate.revealForProvenGeneration(generation))
        assertEquals(generation, gate.visibleGeneration)

        // A marker/track update makes the current proof stale. The installed generation identity
        // remains G1 while its replacement proof is in flight, so no pending-generation signal is
        // available to distinguish the next healthy camera publication.
        gate.hide()
        assertEquals(null, gate.visibleGeneration)

        fun publishHealthyCoordinatorState() = reconcileFogOverlayCoordinatorState(
            installedGeneration = generation,
            pendingGeneration = null,
            coverUp = false,
            retryScheduled = false,
            terminal = false,
            hideOverlays = gate::hide,
        )

        // Camera move/frame/idle publications can all repeat the same healthy state. They may not
        // manufacture a positive visibility transition while the proof callback is outstanding.
        assertFalse(publishHealthyCoordinatorState())
        assertFalse(publishHealthyCoordinatorState())
        assertFalse(publishHealthyCoordinatorState())
        assertEquals(null, gate.visibleGeneration)

        // A stale proof for an older generation is rejected, while the matching G1 proof is the
        // sole positive transition back to visible.
        assertFalse(gate.revealForProvenGeneration(generation - 1L))
        assertEquals(null, gate.visibleGeneration)
        assertTrue(gate.revealForProvenGeneration(generation))
        assertEquals(generation, gate.visibleGeneration)
    }

    @Test
    fun pendingCoverAndRetryPublicationsPreserveTheProvenOverlayUntilMatchingProof() {
        val gate = FogOverlayVisibilityGate()
        assertTrue(gate.revealForProvenGeneration(4L))

        // G2 is pending while G1 remains installed; cover/retry publications protect the view but
        // do not make the already-proven marker disappear from the map-owned gate.
        listOf(
            Triple(5L, true, false),
            Triple(5L, false, true),
            Triple(null, true, false),
        ).forEach { (pending, cover, retry) ->
            assertFalse(
                reconcileFogOverlayCoordinatorState(
                    installedGeneration = 4L,
                    pendingGeneration = pending,
                    coverUp = cover,
                    retryScheduled = retry,
                    terminal = false,
                    hideOverlays = gate::hide,
                ),
            )
            assertEquals("pending=$pending cover=$cover retry=$retry", 4L, gate.visibleGeneration)
        }

        // The pending generation's accepted proof is the only positive handover transition; a
        // stale G3 callback is still rejected by the generation floor.
        assertFalse(gate.revealForProvenGeneration(3L))
        assertTrue(gate.revealForProvenGeneration(5L))
        assertEquals(5L, gate.visibleGeneration)
    }

    @Test
    fun noInstalledGenerationOrTerminalStateHidesWithoutAllowingAStaleReveal() {
        val gate = FogOverlayVisibilityGate()
        assertTrue(gate.revealForProvenGeneration(4L))

        assertTrue(
            reconcileFogOverlayCoordinatorState(
                installedGeneration = null,
                pendingGeneration = 5L,
                coverUp = true,
                retryScheduled = true,
                terminal = false,
                hideOverlays = gate::hide,
            ),
        )
        assertEquals(null, gate.visibleGeneration)
        assertFalse(gate.revealForProvenGeneration(3L))

        assertTrue(
            reconcileFogOverlayCoordinatorState(
                installedGeneration = 4L,
                pendingGeneration = null,
                coverUp = false,
                retryScheduled = false,
                terminal = true,
                hideOverlays = gate::hide,
            ),
        )
        assertEquals(null, gate.visibleGeneration)
    }
}
