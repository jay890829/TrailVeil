package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural coverage for the rule that decides every fog install.
 *
 * Round 7 found the production evaluator had none: `GoogleFogSnapshotProver` was referenced by one
 * test file that only matched source text, no `src/testGooglePoc` exists, and every production
 * device case runs on an empty database where every one of a tile's 256 blocks is usable and the
 * min-3 rule is trivially met — so the defects below were invisible to CI. The rule now lives in
 * `src/main` precisely so it can be exercised here.
 */
class FogSnapshotProofVerdictTest {

    private fun sample(onScreen: Int, matching: Int) = FogProofTileSample(onScreen, matching)

    @Test
    fun anEmptyPlanPasses() {
        val tally = tallyFogProof(emptyList(), MINIMUM)
        assertTrue("nothing was planned, so nothing is hidden", tally.passed)
        assertEquals(0, tally.requiredTiles)
    }

    @Test
    fun aFullyVerifiedPlanPasses() {
        val tally = tallyFogProof(List(4) { sample(onScreen = 8, matching = 8) }, MINIMUM)
        assertTrue(tally.passed)
        assertEquals(4, tally.verifiedTiles)
        assertEquals(0, tally.offScreenTiles)
    }

    /**
     * The F2 deadlock, measured on device by SP8 and reintroduced in the production prover: a tile
     * whose probes all project off screen was counted as required-but-unverifiable, so a stationary
     * camera re-planned to the identical set and failed all ten attempts — terminal on a first
     * install.
     */
    @Test
    fun offScreenOnlyTilesAreExemptRatherThanBlocking() {
        val tally = tallyFogProof(
            listOf(
                sample(onScreen = 8, matching = 8),
                sample(onScreen = 0, matching = 0),
                sample(onScreen = 5, matching = 5),
            ),
            MINIMUM,
        )
        assertTrue("an off-screen-only tile must not be able to deadlock the install", tally.passed)
        assertEquals(2, tally.verifiedTiles)
        assertEquals(1, tally.offScreenTiles)
    }

    /**
     * The opposite failure, and the reason the exemption cannot simply count off-screen tiles as
     * verified: dropping the opaque cover having observed fog in not one pixel is the worst
     * violation of the fail-closed invariant.
     */
    @Test
    fun aPlanWhoseTilesAreAllOffScreenNeverPassesVacuously() {
        val tally = tallyFogProof(List(6) { sample(onScreen = 0, matching = 0) }, MINIMUM)
        assertFalse("a proof that observed nothing must not pass", tally.passed)
        assertEquals(0, tally.verifiedTiles)
        assertEquals(6, tally.offScreenTiles)
    }

    @Test
    fun oneUnverifiedOnScreenTileFailsTheWholePlan() {
        val tally = tallyFogProof(
            listOf(
                sample(onScreen = 8, matching = 8),
                sample(onScreen = 8, matching = MINIMUM - 1),
            ),
            MINIMUM,
        )
        assertFalse("a tile that is on screen and unproven must block the reveal", tally.passed)
    }

    @Test
    fun aTileMeetingExactlyTheMinimumIsVerified() {
        val tally = tallyFogProof(listOf(sample(onScreen = 9, matching = MINIMUM)), MINIMUM)
        assertTrue(tally.passed)
    }

    /**
     * The unanimity edge the threshold still has, kept explicit: `minOf(minimum, onScreenBlocks)`
     * collapses to "all of them" once a tile has at most `minimum` usable units. That is deliberate
     * and fail-closed — what carry-forward F fixed is the UNIT, not the threshold.
     */
    @Test
    fun aSparseTileStillNeedsEveryUnitItHas() {
        assertTrue(tallyFogProof(listOf(sample(onScreen = 3, matching = 3)), MINIMUM).passed)
        assertFalse(tallyFogProof(listOf(sample(onScreen = 3, matching = 2)), MINIMUM).passed)
    }

    // ---- carry-forward F: the counted unit is the block, not the pixel ------------------------

    /**
     * The remedy. Before it, the planner emitted one probe per 16x16 block and the owner decision
     * keeps Google's labels and POI icons compositing above the fog, so one glyph over one pixel of
     * a sparse tile made that tile unverifiable on EVERY attempt — the plan is a deterministic
     * function of the camera and the masks, so all ten retries reproduced it and the install went
     * terminal. A block now carries several separated interchangeable candidates and is proven by
     * any one of them.
     */
    @Test
    fun anOccludedCandidateIsAnsweredByAnotherCandidateOfTheSameBlock() {
        // Three visible regions, the first one's leading pixel under a label.
        val occluded = listOf(
            block(onScreen = true, matched = false),
            block(onScreen = true, matched = true),
            block(onScreen = true, matched = true),
        )
        assertFalse(
            "a region with no matching candidate at all must still block the reveal",
            tallyFogProof(listOf(reduceFogProofBlocks(occluded)), MINIMUM).passed,
        )

        // The same three regions, with a separated candidate of the first landing beside the glyph.
        val healed = occluded.toMutableList().also { it[0] = block(onScreen = true, matched = true) }
        assertTrue(tallyFogProof(listOf(reduceFogProofBlocks(healed)), MINIMUM).passed)
    }

    @Test
    fun aBlockCountsOnceHoweverManyCandidatesItHad() {
        val sampled = reduceFogProofBlocks(
            listOf(
                block(onScreen = true, matched = true),
                block(onScreen = true, matched = false),
                block(onScreen = false, matched = false),
            ),
        )
        assertEquals("only the on-screen blocks count", 2, sampled.onScreenBlocks)
        assertEquals(1, sampled.matchingBlocks)
    }

    /**
     * The fallbacks must not become a vacuous pass of their own: a block nobody could observe is
     * off-screen, never matched, so a tile made entirely of them still reaches the off-screen
     * exemption rather than being counted as proven.
     */
    @Test
    fun blocksThatWereNeverOnScreenProveNothing() {
        val sampled = reduceFogProofBlocks(List(5) { block(onScreen = false, matched = false) })
        assertEquals(0, sampled.onScreenBlocks)
        assertEquals(0, sampled.matchingBlocks)
        assertFalse(tallyFogProof(listOf(sampled), MINIMUM).passed)
    }

    @Test
    fun aBlockCannotClaimAMatchItNeverSaw() {
        val failure = runCatching { FogProofBlockSample(anyOnScreen = false, anyMatched = true) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun aSampleCannotClaimMoreMatchesThanOnScreenProbes() {
        val failure = runCatching { FogProofTileSample(onScreenBlocks = 2, matchingBlocks = 3) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    private fun block(onScreen: Boolean, matched: Boolean) =
        FogProofBlockSample(anyOnScreen = onScreen, anyMatched = matched)

    private companion object {
        const val MINIMUM = 3
    }
}
