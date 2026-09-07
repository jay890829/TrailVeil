package app.trailveil.harness

import kotlin.random.Random

/**
 * `V03-013`: scattered anchors for the world-scale fixture.
 *
 * The local walk answers "what does this arm's boundary look like". This answers a different
 * question the owner asked for next: what the fog does when the database is not nearly empty and the
 * explored ground is not all in one place. Three hundred sessions of a thousand-odd points each is
 * about 307,000 rows, which is the scale at which the schema's own comments say things went wrong
 * before - `P4-036` replaced the fog viewport index because "a populated database was slow at zooms
 * whose window was already small", and `P4-037` added the cell table because a world settle visited
 * every row.
 *
 * **Pseudo-random, not random, and the distinction is the whole point.** The owner asked for random
 * world locations; what makes them useful is that they are the SAME random locations every time. An
 * arm is judged by switching arm and looking again, and a fixture that reseeded itself differently
 * would move the subject under the comparison. A fixed seed gives locations that are arbitrary in
 * every way that matters - no pattern, no clustering by design, spread over every continent and
 * ocean - and reproducible in the one way that matters here.
 *
 * Latitude is uniform in [-70, 70] rather than over the whole sphere. Uniform latitude already
 * over-samples the poles relative to area, and the poles are where Mercator distortion, not the
 * fog, would dominate what you see; clamping keeps the fixture measuring the thing it is for.
 * **Most of these will land in the ocean** - about 70% of the Earth is - which is correct for a load
 * fixture and is why this is not the button to use when comparing boundaries.
 */
internal object DemoWorldAnchors {

    /**
     * Any fixed value works; this one is written down so a future reader knows the locations are a
     * choice rather than a leftover.
     */
    const val SEED = 20260908L

    const val DEFAULT_SESSIONS = 300

    const val DEFAULT_POINTS_PER_SESSION = 1_024

    private const val MAX_LATITUDE = 70.0

    /**
     * The first [count] anchors, always the same ones in the same order.
     *
     * Prefix-stable by construction: taking 300 gives the same first 50 as taking 50, because each
     * anchor is drawn from one stream in order. That means a shortened run is a genuine subset of a
     * full one rather than a different fixture, which is what makes a quick check comparable with
     * the real thing.
     */
    fun anchors(count: Int = DEFAULT_SESSIONS): List<DemoExplorationSeeder.Anchor> {
        require(count > 0) { "count must be positive" }
        val random = Random(SEED)
        return List(count) {
            DemoExplorationSeeder.Anchor(
                latitude = random.nextDouble(-MAX_LATITUDE, MAX_LATITUDE),
                longitude = random.nextDouble(-180.0, 180.0),
                fromDevice = false,
            )
        }
    }
}
