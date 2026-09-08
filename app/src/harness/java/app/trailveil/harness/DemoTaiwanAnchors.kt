package app.trailveil.harness

import kotlin.random.Random

/**
 * `V03-013`: scattered anchors for the load fixture, inside Taiwan's bounding box.
 *
 * The local walk answers "what does this arm's boundary look like". This answers a different
 * question: what the fog does when the database is not nearly empty and the explored ground is not
 * all in one place. Two hundred sessions of a thousand-odd points each is about 205,000 rows, which
 * is the scale the schema's own comments were written about - `P4-036` replaced the fog viewport
 * index because "a populated database was slow at zooms whose window was already small", and
 * `P4-037` added the cell table because a world settle visited every row.
 *
 * **Pseudo-random, not random, and the distinction is the whole point.** What makes arbitrary
 * locations useful is that they are the SAME arbitrary locations every time: an arm is judged by
 * switching arm and looking again, and a fixture that reseeded itself would move the subject under
 * the comparison. A fixed seed gives locations with no pattern and no designed clustering, and
 * reproducible in the one way that matters here.
 *
 * **A plain bounding box, so a good share of these land in the sea, and that is by explicit owner
 * decision (2026-09-08).** Taiwan's box is well over half water - the Strait to the west, the
 * Pacific to the east - and the alternative was rejection-sampling a coarse island outline. For a
 * fixture whose job is row count and read cost, a track offshore loads the database exactly as well
 * as one on land, so the outline would have bought nothing but code to maintain.
 */
internal object DemoTaiwanAnchors {

    /**
     * Any fixed value works; this one is written down so a future reader knows the locations are a
     * choice rather than a leftover.
     */
    const val SEED = 20260908L

    const val DEFAULT_SESSIONS = 200

    const val DEFAULT_POINTS_PER_SESSION = 1_024

    /**
     * Taiwan's main island, bounding box.
     *
     * North to Fugui Cape, south to Eluanbi, west to the Chiayi coast, east to Sandiao Cape, each
     * rounded outward to a round number. Deliberately the box and not the island; see the class
     * KDoc. Comfortably inside the latitudes [DemoExplorationTrack] can draw at, so nothing seeded
     * here is ever clamped.
     */
    const val SOUTH_LATITUDE = 21.90
    const val NORTH_LATITUDE = 25.30
    const val WEST_LONGITUDE = 120.00
    const val EAST_LONGITUDE = 122.00

    /**
     * The first [count] anchors, always the same ones in the same order.
     *
     * Prefix-stable by construction: taking 200 gives the same first 50 as taking 50, because each
     * anchor is drawn from one stream in order. A shortened run is therefore a genuine subset of a
     * full one rather than a different fixture, which is what makes a quick check comparable with
     * the real thing.
     */
    fun anchors(count: Int = DEFAULT_SESSIONS): List<DemoExplorationSeeder.Anchor> {
        require(count > 0) { "count must be positive" }
        val random = Random(SEED)
        return List(count) {
            DemoExplorationSeeder.Anchor(
                latitude = random.nextDouble(SOUTH_LATITUDE, NORTH_LATITUDE),
                longitude = random.nextDouble(WEST_LONGITUDE, EAST_LONGITUDE),
                fromDevice = false,
            )
        }
    }
}
