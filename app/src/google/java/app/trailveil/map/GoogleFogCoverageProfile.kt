package app.trailveil.map

import app.trailveil.map.fog.FogTileCacheBudget
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.FogViewportCoveragePlanner

/**
 * How much fog the Google surface renders beyond what it can see, and what that costs it.
 *
 * [DEFAULT] reproduces the shipped constants exactly - no ring, and the four separate 256s the
 * binding grew independently - so a build using it behaves identically to one that never had this
 * type. It is what `googleRelease` gets, as a constant; see `GoogleFogCoverageProfileSeam`.
 *
 * The type exists for `V03-011` arm 1, and encodes what `FogPaddingRingSurroundTest` established
 * about that arm. Two things, neither of which a single `paddingTiles` constant can express:
 *
 * 1. **The ring is asymmetric or it is nothing.** The safety cover rises when the camera leaves
 *    `published >= predicted`. Padding BOTH sides of that comparison cancels exactly, so a ring
 *    wired that way is rendered, paid for, and buys no camera movement at all. Hence [renderPlanner]
 *    and [surroundPlanner] rather than one planner: render padded, reason unpadded.
 * 2. **The ring is quadratic and does not fit.** `(C + 2p)(R + 2p)`, against a rectangular
 *    completion the survey measured at 240 tiles under a hard 256. So the budgets travel with the
 *    padding, in one object, instead of being raised one forgotten constant at a time.
 */
internal data class GoogleFogCoverageProfile(
    val label: String,
    val paddingTiles: Int,
    val maxTiles: Int,
    val maxRequestedKeys: Int,
    val cacheMaxEntries: Int,
    val cacheMaxBytes: Long,
) {
    init {
        require(paddingTiles >= 0) { "paddingTiles must be non-negative" }
        require(maxTiles > 0) { "maxTiles must be positive" }
        require(maxRequestedKeys > 0) { "maxRequestedKeys must be positive" }
        require(cacheMaxBytes > 0L) { "cacheMaxBytes must be positive" }
        require(cacheMaxEntries >= maxTiles) {
            // Otherwise the ring is rendered and immediately evicted, and the arm measures cache
            // churn while reporting it as the cost of a ring.
            "cacheMaxEntries ($cacheMaxEntries) must hold a whole plan ($maxTiles)"
        }
        // The publish cap is the one that decides a generation, and it is NOT the plan.
        //
        // `FogTileProviderAdapter.publish` REJECTS a key once `candidate.size >= maxEntries` - it
        // never evicts - and what it is handed is the UNION of the plan with the SDK's observed
        // requests, rectangularly completed, bounded by [maxRequestedKeys]. A budget that holds a
        // whole plan but not a whole union fails the generation at publish time with the cover up,
        // which is the failure this arm exists to remove. Strictly greater because the cap is
        // `>=`: room for exactly [maxRequestedKeys] entries rejects the last key of a full union.
        //
        // **Asserted for ring profiles only, deliberately.** The shipped [DEFAULT] sits exactly on
        // that boundary (256 entries against a 256-key union) and has since long before this arm.
        // Whether the shipped profile should have the extra entry is a real question and a
        // separate change with its own verification; asserting it here would fail construction in
        // every Google build, which is not a thing an arm-1 measurement is allowed to do.
        if (paddingTiles > 0) {
            require(cacheMaxEntries > maxRequestedKeys) {
                "cacheMaxEntries ($cacheMaxEntries) must hold a whole publish " +
                    "($maxRequestedKeys) with room for the key that reaches it"
            }
        }
        require(maxRequestedKeys >= maxTiles) {
            "maxRequestedKeys ($maxRequestedKeys) must hold the plan it is the union with " +
                "($maxTiles)"
        }
        require(maxRequestedKeys <= FogViewportCoordinator.MAX_PROVIDER_VIEWPORT_TILES) {
            // The union travels to FogViewportCoordinator.renderTiles, whose own `require` is a
            // hard ceiling on ONE render. A profile allowed to plan a wider union than that only
            // moves the failure to a later call, which is what the first version of this arm did.
            "maxRequestedKeys ($maxRequestedKeys) exceeds one render's ceiling " +
                "(${FogViewportCoordinator.MAX_PROVIDER_VIEWPORT_TILES})"
        }
    }

    /** What gets rendered and published: the viewport plus the ring. */
    fun renderPlanner(): FogViewportCoveragePlanner =
        FogViewportCoveragePlanner(paddingTiles = paddingTiles, maxTiles = maxTiles)

    /**
     * What the surround test and the proof predict: the visible viewport, never the ring.
     *
     * Equal to [renderPlanner] when [paddingTiles] is zero, which is why the default profile leaves
     * behaviour untouched.
     */
    fun surroundPlanner(): FogViewportCoveragePlanner =
        FogViewportCoveragePlanner(paddingTiles = 0, maxTiles = maxTiles)

    fun cacheBudget(): FogTileCacheBudget =
        FogTileCacheBudget(maxEntries = cacheMaxEntries, maxBytes = cacheMaxBytes)

    companion object {
        /** The shipped behaviour, constant by constant. */
        val DEFAULT = GoogleFogCoverageProfile(
            label = "default",
            paddingTiles = FogViewportCoveragePlanner.DEFAULT_PADDING_TILES,
            maxTiles = FogViewportCoveragePlanner.DEFAULT_MAX_TILES,
            maxRequestedKeys = DEFAULT_MAX_REQUESTED_KEYS,
            cacheMaxEntries = FogTileCacheBudget().maxEntries,
            cacheMaxBytes = FogTileCacheBudget().maxBytes,
        )

        /**
         * `V03-011` arm 1. The budgets are sized for the plan this padding actually produces, and
         * are a measurement fixture rather than a proposal for the shipped default: whether a ring
         * this size is affordable is the question the arm exists to answer.
         */
        fun ring(paddingTiles: Int): GoogleFogCoverageProfile {
            require(paddingTiles > 0) { "a ring profile needs padding; use DEFAULT for none" }
            // The coordinator's own ceiling, and the reason this is no longer 512.
            //
            // `FogViewportCoordinator.MAX_PROVIDER_VIEWPORT_TILES` is a hard `require` on one
            // render, so a profile that let the planner build 512 keys only moved the failure
            // one call later - section 14c, where a padded plan of about 300 keys failed the
            // generation and left the cover up. Now that the planner narrows a ring that will not
            // fit instead of refusing it, asking for exactly what a render may cost is the whole
            // fix: a viewport with room gets the ring it asked for, and one without gets the
            // widest ring it can afford rather than a failed generation.
            val maxTiles = FogViewportCoordinator.MAX_PROVIDER_VIEWPORT_TILES
            return GoogleFogCoverageProfile(
                label = "ring$paddingTiles",
                paddingTiles = paddingTiles,
                maxTiles = maxTiles,
                // The union of the padded plan with the SDK's observed requests. Capped at one
                // render's ceiling rather than added to it: the union is what reaches
                // `renderTiles`, so a larger bound here does not buy a larger ring, it buys a
                // later crash. The first version of this arm asked for 512 and got exactly that.
                maxRequestedKeys = maxTiles,
                // One more than the union, because the publish cap rejects at `>=`.
                cacheMaxEntries = maxTiles + 1,
                // Scaled by entries, in Long arithmetic and in this order: the previous form
                // divided first and would have silently produced a zero budget for any maxTiles
                // below the default entry count.
                cacheMaxBytes = FogTileCacheBudget().maxBytes *
                    (maxTiles + 1).toLong() / FogTileCacheBudget().maxEntries.toLong(),
            )
        }

        /** The binding's own union bound, which is a distinct 256 from the planner's. */
        const val DEFAULT_MAX_REQUESTED_KEYS = 256
    }
}
