package app.trailveil.map.fog

/**
 * One tile's outcome, reduced to the two counts the verdict rule needs.
 *
 * The counted unit is the planner BLOCK, never the individual probe pixel — see
 * [reduceFogProofBlocks] for why, and never reintroduce a per-pixel count here.
 */
data class FogProofTileSample(
    val onScreenBlocks: Int,
    val matchingBlocks: Int,
) {
    init {
        require(onScreenBlocks >= 0) { "onScreenBlocks must not be negative" }
        require(matchingBlocks in 0..onScreenBlocks) {
            "matchingBlocks must be within the on-screen block count"
        }
    }
}

/**
 * One block's outcome. A block is a region of a tile the planner drew several interchangeable
 * candidate pixels from, so both fields are existential: the prover may stop at the first match.
 */
data class FogProofBlockSample(
    val anyOnScreen: Boolean,
    val anyMatched: Boolean,
) {
    init {
        require(!anyMatched || anyOnScreen) { "a matched block must have been on screen" }
    }
}

/**
 * Collapses one tile's blocks into the sample the per-tile rule consumes: the block, not the
 * individual pixel, is what the threshold counts.
 *
 * This is the remedy for carry-forward F. The rule below takes `min(minimum, onScreenBlocks)`, so a
 * tile with at most `minimum` usable units needs unanimity; when the unit was a single pixel per
 * block, one Google label over one pixel of a sparse tile made that tile unverifiable on every
 * attempt (the plan is a deterministic function of the camera and the masks, so all ten attempts
 * reproduce it) and the install went terminal. Counting blocks keeps the threshold and the
 * fail-closed requirement exactly as they were — every counted block still had to show real fog —
 * while letting an occluded pixel be answered by another pixel of the same region.
 */
fun reduceFogProofBlocks(blocks: List<FogProofBlockSample>): FogProofTileSample =
    FogProofTileSample(
        onScreenBlocks = blocks.count(FogProofBlockSample::anyOnScreen),
        matchingBlocks = blocks.count(FogProofBlockSample::anyMatched),
    )

/** Coordinate-free proof tally; every field is a count or a boolean. */
data class FogProofTally(
    val requiredTiles: Int,
    val verifiedTiles: Int,
    val offScreenTiles: Int,
    val passed: Boolean,
)

/**
 * The provider-neutral verdict rule for a screen-pixel fog proof.
 *
 * Two failure modes pull in opposite directions and the rule has to close both:
 *
 * A **vacuous pass** would drop the opaque cover having observed fog in not one pixel — the
 * fail-closed invariant's worst violation. So a plan with tiles in it can never pass on zero
 * verified tiles.
 *
 * An **unsatisfiable proof** is the mirror image. A tile whose probes all project off screen has no
 * pixels to prove, and demanding it deadlocks the install forever: the plan is a deterministic
 * function of the camera and the masks, so a stationary camera re-plans to the identical probe set
 * on every attempt and fails identically. SP8 measured exactly this on device
 * (`visualRequiredTiles=15 visualVerifiedTiles=10 visualOffScreenOnlyTiles=5` through all ten
 * attempts), and the engineering PoC's provider has carried the exemption since. The production
 * prover did not, which reintroduced the deadlock — terminal on a first install, and an endless
 * once-a-second rebuild loop afterwards.
 *
 * So: an off-screen-only tile is exempt from the requirement, and the delivery barrier owns its byte
 * truth instead; but at least one tile must have been positively observed for the proof to pass.
 */
fun tallyFogProof(
    tiles: List<FogProofTileSample>,
    minimumMatchingBlocksPerTile: Int,
): FogProofTally {
    require(minimumMatchingBlocksPerTile > 0) {
        "minimumMatchingBlocksPerTile must be positive"
    }
    var offScreenTiles = 0
    var verifiedTiles = 0
    tiles.forEach { tile ->
        if (tile.onScreenBlocks == 0) {
            offScreenTiles += 1
        } else {
            val required = minOf(minimumMatchingBlocksPerTile, tile.onScreenBlocks)
            if (tile.matchingBlocks >= required) verifiedTiles += 1
        }
    }
    return FogProofTally(
        requiredTiles = tiles.size,
        verifiedTiles = verifiedTiles,
        offScreenTiles = offScreenTiles,
        // An empty plan has nothing to hide and passes; a non-empty one must show real fog.
        passed = if (tiles.isEmpty()) {
            true
        } else {
            verifiedTiles > 0 && verifiedTiles + offScreenTiles == tiles.size
        },
    )
}
