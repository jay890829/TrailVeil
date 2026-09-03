package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `V02-005` design §2.4/§11: the pure fog camera decision function pinned over its FULL input
 * cross-product (3 x 2 x 2 x 2 x 2 = 48 rows), so any behavioral drift in the decision core is
 * a visible table diff, not an emergent runtime change.
 */
class FogCameraReactionTableTest {

    /**
     * The expected table, stated as the design's rule priority independent of the production
     * cascade: gestures never provoke fog work at move-start; follow eases have the same
     * move-start exemption; the coordinator's per-frame check still covers a real exit; a
     * programmed move outside the surround raises the cover; a programmed move with a rotation
     * due raises the cover (§4(c) belt-and-braces); everything else rebuilds at idle.
     * `programmedFlightActive` never changes the reaction — the ticket exists for follow
     * stand-down and SP10 stale-cancel rejection, not cover policy.
     */
    private fun expectedReaction(
        moveReason: FogCameraMoveReason,
        followStepInFlight: Boolean,
        insidePublishedSurround: Boolean,
        paletteRotationRequired: Boolean,
    ): FogCameraReaction {
        if (moveReason == FogCameraMoveReason.GESTURE) {
            return FogCameraReaction.LEAVE_PUBLISHED_COVERAGE
        }
        if (followStepInFlight) return FogCameraReaction.LEAVE_PUBLISHED_COVERAGE
        if (!insidePublishedSurround) return FogCameraReaction.RAISE_OPAQUE_COVER
        if (paletteRotationRequired) return FogCameraReaction.RAISE_OPAQUE_COVER
        return FogCameraReaction.REBUILD_AT_IDLE
    }

    @Test
    fun `full cross product is pinned`() {
        val booleans = listOf(false, true)
        for (reason in FogCameraMoveReason.entries) {
            for (follow in booleans) {
                for (flight in booleans) {
                    for (inside in booleans) {
                        for (rotation in booleans) {
                            assertEquals(
                                "reason=$reason follow=$follow flight=$flight " +
                                    "inside=$inside rotation=$rotation",
                                expectedReaction(reason, follow, inside, rotation),
                                fogCameraReaction(
                                    moveReason = reason,
                                    followStepInFlight = follow,
                                    programmedFlightActive = flight,
                                    insidePublishedSurround = inside,
                                    paletteRotationRequired = rotation,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `golden rows anchor the table independent of the rule statement`() {
        // A gesture outside the surround with a rotation due still provokes nothing at
        // move-start; the per-frame safety check owns the actual exit.
        assertEquals(
            FogCameraReaction.LEAVE_PUBLISHED_COVERAGE,
            fogCameraReaction(
                moveReason = FogCameraMoveReason.GESTURE,
                followStepInFlight = false,
                programmedFlightActive = false,
                insidePublishedSurround = false,
                paletteRotationRequired = true,
            ),
        )
        // A follow ease stays exempt even outside the surround at move-start; the per-frame
        // check owns mid-flight exits.
        assertEquals(
            FogCameraReaction.LEAVE_PUBLISHED_COVERAGE,
            fogCameraReaction(
                moveReason = FogCameraMoveReason.DEVELOPER,
                followStepInFlight = true,
                programmedFlightActive = true,
                insidePublishedSurround = false,
                paletteRotationRequired = false,
            ),
        )
        // The instant-jump case: programmed move already outside the surround.
        assertEquals(
            FogCameraReaction.RAISE_OPAQUE_COVER,
            fogCameraReaction(
                moveReason = FogCameraMoveReason.DEVELOPER,
                followStepInFlight = false,
                programmedFlightActive = true,
                insidePublishedSurround = false,
                paletteRotationRequired = false,
            ),
        )
        // Rotation belt-and-braces on a programmed move inside the surround.
        assertEquals(
            FogCameraReaction.RAISE_OPAQUE_COVER,
            fogCameraReaction(
                moveReason = FogCameraMoveReason.API_ANIMATION,
                followStepInFlight = false,
                programmedFlightActive = false,
                insidePublishedSurround = true,
                paletteRotationRequired = true,
            ),
        )
        // The quiet inversion the PoC never had: programmed move inside the surround rebuilds
        // at idle without a cover.
        assertEquals(
            FogCameraReaction.REBUILD_AT_IDLE,
            fogCameraReaction(
                moveReason = FogCameraMoveReason.DEVELOPER,
                followStepInFlight = false,
                programmedFlightActive = true,
                insidePublishedSurround = true,
                paletteRotationRequired = false,
            ),
        )
    }

    @Test
    fun `failure classification is the two §9 rows`() {
        assertEquals(
            FogInstallFailureClassification.RETRY_BEHIND_PLACEHOLDERS,
            classifyFogInstallFailure(hasProvenGeneration = true),
        )
        assertEquals(
            FogInstallFailureClassification.TERMINAL_FOR_COMPOSITION,
            classifyFogInstallFailure(hasProvenGeneration = false),
        )
    }
}
