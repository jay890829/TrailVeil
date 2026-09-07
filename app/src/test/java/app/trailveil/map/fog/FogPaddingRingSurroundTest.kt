package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V03-011` arm 1, settled on the host before any device measurement.
 *
 * The spike's first arm is "pre-render a ring of tiles beyond the viewport so the camera can move
 * during a gesture without the safety cover rising". [FogViewportCoveragePlanner] already has the
 * `paddingTiles` knob that looks like the whole implementation. It is not, and these tests are why:
 * turning that knob alone moves the cover NOT AT ALL.
 *
 * The surround predicate is `published ⊇ predicted`, and today
 * [GoogleCanonicalFogSurfaceBinding][app.trailveil.map] hands the SAME planner to both roles - the
 * one that renders the published set and the one [fogViewportCoveredByPublishedTiles] uses to
 * predict the moved camera's needs. Both sides are then dilated by the same `p`, and for
 * axis-aligned rectangles that dilation cancels exactly: `rect0 ⊕ p ⊇ rect1 ⊕ p` reduces term by
 * term to `rect0 ⊇ rect1`, which is the p = 0 answer. The ring gets rendered, paid for in tiles and
 * memory, and bought nothing.
 *
 * The mechanism the arm actually needs is an ASYMMETRY: publish padded, predict unpadded. That is a
 * second planner in the binding, not a constant change, and it is what `first test` and
 * `second test` below separate.
 *
 * Two limits on the cancellation claim, neither of which rescues the symmetric wiring. Within `p`
 * tiles of a pole the y clamp breaks the cancellation, so padding does buy movement there; and a
 * viewport already at full world width is unaffected by either. Neither describes a walking user.
 */
class FogPaddingRingSurroundTest {

    @Test
    fun `symmetric padding buys no camera movement whatsoever`() {
        val start = viewport(centerLongitude = 0.0)
        val movedOneTileEast = viewport(centerLongitude = ONE_TILE_DEGREES)

        // The unpadded answer: one tile east is outside the published rectangle, cover rises.
        val unpaddedPlanner = FogViewportCoveragePlanner(paddingTiles = 0)
        assertFalse(
            "the baseline arm must fail this pan - otherwise the ring has nothing to fix",
            fogViewportCoveredByPublishedTiles(
                viewport = movedOneTileEast,
                recentActualRequests = emptySet(),
                publishedKeys = unpaddedPlanner.plan(start).keySet,
                planner = unpaddedPlanner,
            ),
        )

        // The ring, wired the way the binding wires it today: same planner for both roles.
        val ringPlanner = FogViewportCoveragePlanner(paddingTiles = RING_TILES)
        val ringPublished = ringPlanner.plan(start).keySet
        assertTrue(
            "the ring must actually be larger, or this test proves nothing",
            ringPublished.size > unpaddedPlanner.plan(start).keySet.size,
        )
        assertFalse(
            "SYMMETRIC padding cancels: a ring of $RING_TILES tiles bought zero movement, because " +
                "the surround predicate dilated the prediction by the same $RING_TILES tiles. " +
                "published=${ringPublished.size} tiles rendered for no change in the answer",
            fogViewportCoveredByPublishedTiles(
                viewport = movedOneTileEast,
                recentActualRequests = emptySet(),
                publishedKeys = ringPublished,
                planner = ringPlanner,
            ),
        )
    }

    @Test
    fun `asymmetric padding is what keeps the cover down, and only out to the ring`() {
        val start = viewport(centerLongitude = 0.0)
        val ringPlanner = FogViewportCoveragePlanner(paddingTiles = RING_TILES)
        val surroundPlanner = FogViewportCoveragePlanner(paddingTiles = 0)
        val published = ringPlanner.plan(start).keySet

        val movedOneTileEast = viewport(centerLongitude = ONE_TILE_DEGREES)
        assertTrue(
            "publish padded, predict unpadded: one tile of movement is inside the ring",
            fogViewportCoveredByPublishedTiles(
                viewport = movedOneTileEast,
                // What the SDK would really ask for at the new camera. The published ring has to
                // contain these too, or the second clause of the predicate fails and the arm is
                // measuring a cover that rose for a different reason than the prediction.
                recentActualRequests = surroundPlanner.plan(movedOneTileEast).keySet,
                publishedKeys = published,
                planner = surroundPlanner,
            ),
        )

        val movedPastTheRing = viewport(
            centerLongitude = ONE_TILE_DEGREES * (RING_TILES + 2),
        )
        assertFalse(
            "the ring is finite: past $RING_TILES tiles the cover must still rise",
            fogViewportCoveredByPublishedTiles(
                viewport = movedPastTheRing,
                recentActualRequests = surroundPlanner.plan(movedPastTheRing).keySet,
                publishedKeys = published,
                planner = surroundPlanner,
            ),
        )
    }

    /**
     * The ring is quadratic, and the budget it has to fit under is the one the device is already
     * near. `V03-011` measured the rectangular completion at 240 tiles against
     * [FogViewportCoveragePlanner.DEFAULT_MAX_TILES] of 256, so the arm cannot be enabled by
     * changing `paddingTiles` alone: the planner refuses the plan and the binding's coverage call
     * throws before anything is measured.
     */
    @Test
    fun `the ring is quadratic and does not fit under the shipped tile budget`() {
        val start = viewport(centerLongitude = 0.0)
        val base = FogViewportCoveragePlanner(paddingTiles = 0).plan(start).keys
        val columns = base.map(FogTileKey::x).distinct().size
        val rows = base.map(FogTileKey::y).distinct().size

        val ring = FogViewportCoveragePlanner(paddingTiles = RING_TILES).plan(start).keys
        assertEquals(
            "a ring of p adds 2p columns and 2p rows, so the cost is (C+2p)(R+2p), not C*R+ring",
            (columns + 2 * RING_TILES) * (rows + 2 * RING_TILES),
            ring.size,
        )

        // The device figure, restated as arithmetic rather than re-measured here: `V03-011`
        // section 9 recorded a 240-tile completion, which is 15x16, and ONE tile of padding takes
        // it to 17x18. Bound to the real constant so that raising the shipped budget shows up here
        // as the arm's premise changing rather than as a comment going quietly stale.
        val deviceRingTiles = (15 + 2) * (16 + 2)
        assertTrue(
            "a single ring tile costs $deviceRingTiles on the measured viewport, which must " +
                "exceed the shipped budget of " +
                "${FogViewportCoveragePlanner.DEFAULT_MAX_TILES} or this arm needs no budget work",
            deviceRingTiles > FogViewportCoveragePlanner.DEFAULT_MAX_TILES,
        )

        val atBudget = wideViewport()
        val atBudgetSize = FogViewportCoveragePlanner(paddingTiles = 0).plan(atBudget).keys.size
        assertTrue(
            "fixture must sit under the budget unpadded, actual=$atBudgetSize",
            atBudgetSize <= FogViewportCoveragePlanner.DEFAULT_MAX_TILES,
        )
        val refused = assertThrows(IllegalArgumentException::class.java) {
            FogViewportCoveragePlanner(paddingTiles = 1).plan(atBudget)
        }
        assertTrue(
            "the planner must refuse the padded plan by budget, not by some other complaint: " +
                "${refused.message}",
            refused.message?.contains("maxTiles is") == true,
        )
        // And it is not refused once the budget travels with the padding - which is the change the
        // arm has to make, in the binding's adapter cache budget as well as here.
        FogViewportCoveragePlanner(paddingTiles = 1, maxTiles = RING_ARM_MAX_TILES).plan(atBudget)
    }

    /** A small viewport well away from both poles and the antimeridian. */
    private fun viewport(centerLongitude: Double): FogViewportCoverageRequest =
        FogViewportCoverageRequest(
            center = GeoPoint(0.0, centerLongitude),
            floorZoom = ZOOM,
            nearLeft = GeoPoint(-1.0, centerLongitude - 1.0),
            farLeft = GeoPoint(1.0, centerLongitude - 1.0),
            farRight = GeoPoint(1.0, centerLongitude + 1.0),
            nearRight = GeoPoint(-1.0, centerLongitude + 1.0),
        )

    /** Wide enough that one tile of padding crosses [FogViewportCoveragePlanner.DEFAULT_MAX_TILES]. */
    private fun wideViewport(): FogViewportCoverageRequest =
        FogViewportCoverageRequest(
            center = GeoPoint(0.0, 0.0),
            floorZoom = ZOOM,
            nearLeft = GeoPoint(-55.0, -80.0),
            farLeft = GeoPoint(62.0, -80.0),
            farRight = GeoPoint(62.0, 80.0),
            nearRight = GeoPoint(-55.0, 80.0),
        )

    private companion object {
        const val ZOOM = 5
        /** 360 degrees over 2^5 tiles. */
        const val ONE_TILE_DEGREES = 360.0 / 32.0
        const val RING_TILES = 2
        /** Only large enough for the arm's padded plans; not a proposal for the shipped default. */
        const val RING_ARM_MAX_TILES = 512
    }
}
