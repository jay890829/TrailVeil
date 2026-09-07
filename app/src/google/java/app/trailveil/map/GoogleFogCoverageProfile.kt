package app.trailveil.map

import app.trailveil.map.fog.FogTileCacheBudget
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
        require(cacheMaxEntries >= maxTiles) {
            // Otherwise the ring is rendered and immediately evicted, and the arm measures cache
            // churn while reporting it as the cost of a ring.
            "cacheMaxEntries ($cacheMaxEntries) must hold a whole plan ($maxTiles)"
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
            // The measured 15x16 completion, padded, rounded up to a power of two. p=1 needs 306
            // and p=2 needs 380.
            val maxTiles = 512
            return GoogleFogCoverageProfile(
                label = "ring$paddingTiles",
                paddingTiles = paddingTiles,
                maxTiles = maxTiles,
                // The union of the padded plan with the SDK's own observed requests.
                maxRequestedKeys = maxTiles + DEFAULT_MAX_REQUESTED_KEYS,
                cacheMaxEntries = maxTiles,
                // Scaled with the entry count, so a fog tile's byte allowance is unchanged.
                cacheMaxBytes = FogTileCacheBudget().maxBytes *
                    (maxTiles / FogTileCacheBudget().maxEntries),
            )
        }

        /** The binding's own union bound, which is a distinct 256 from the planner's. */
        const val DEFAULT_MAX_REQUESTED_KEYS = 256
    }
}
