package app.trailveil.map.fog

import kotlin.math.abs
import kotlin.math.hypot
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

        // The device figure, restated as arithmetic rather than re-measured here: a 240-tile
        // completion, which is 15x16, taken to 17x18 by ONE tile of padding. Section 14b later
        // established WHICH viewport that was - a 1080x2400 screen at 160 dpi, where the steep
        // pose plans 234 - so this is the LARGE-viewport case, not a phone. On a phone-shaped
        // viewport the same pose plans 40 and one ring tile costs 72. The arithmetic below is the
        // reason the arm needs budget work at all, and it applies exactly where the plan is large.
        // Bound to the real constant so that raising the shipped budget shows up here as the arm's
        // premise changing rather than as a comment going quietly stale.
        val deviceRingTiles = (15 + 2) * (16 + 2)
        assertTrue(
            "a single ring tile costs $deviceRingTiles on the large viewport, which must " +
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
        // The planner NARROWS the ring to what the budget can hold rather than refusing the plan.
        // Before this arm it threw, which spent the whole generation - the render failed and the
        // cover stayed up - for the sake of tiles that were only ever a continuity improvement.
        val narrowed = FogViewportCoveragePlanner(paddingTiles = 1).plan(atBudget)
        assertEquals(
            "a ring that does not fit is dropped to the widest one that does, which here is none",
            0,
            narrowed.appliedPaddingTiles,
        )
        assertEquals(
            "and the narrowed plan is exactly the unpadded plan, not some third thing",
            atBudgetSize,
            narrowed.keys.size,
        )

        // Narrowing is not all-or-nothing: given a budget between the p=1 and p=2 costs, the p=2
        // planner comes back with p=1 rather than with none. Calibrated from the planner itself so
        // the case cannot go stale against a fixture change.
        val ringOneCost = FogViewportCoveragePlanner(paddingTiles = 1, maxTiles = RING_ARM_MAX_TILES)
            .plan(atBudget).keys.size
        val ringTwoCost = FogViewportCoveragePlanner(paddingTiles = 2, maxTiles = RING_ARM_MAX_TILES)
            .plan(atBudget).keys.size
        assertTrue(
            "the fixture must separate the two ring widths, p1=$ringOneCost p2=$ringTwoCost",
            ringOneCost < ringTwoCost,
        )
        val steppedDown = FogViewportCoveragePlanner(paddingTiles = 2, maxTiles = ringOneCost)
            .plan(atBudget)
        assertEquals(
            "a budget that holds p=1 but not p=2 yields p=1",
            1,
            steppedDown.appliedPaddingTiles,
        )
        assertEquals("and costs exactly the p=1 plan", ringOneCost, steppedDown.keys.size)

        // The UNPADDED plan is still refused when it genuinely does not fit. That is a real error
        // rather than a ring that was too ambitious, and it is the shipped behaviour of every build
        // that asks for no ring - which is all of them.
        val refused = assertThrows(IllegalArgumentException::class.java) {
            FogViewportCoveragePlanner(paddingTiles = 0, maxTiles = atBudgetSize - 1).plan(atBudget)
        }
        assertTrue(
            "the planner must refuse by budget, not by some other complaint: ${refused.message}",
            refused.message?.contains("maxTiles is") == true,
        )

        // And the ring is not narrowed at all once the budget travels with the padding.
        val afforded = FogViewportCoveragePlanner(paddingTiles = 1, maxTiles = RING_ARM_MAX_TILES)
            .plan(atBudget)
        assertEquals("a budget with room keeps the ring", 1, afforded.appliedPaddingTiles)
    }

    /**
     * The ring is a pan device, and four of the audit's six gesture kinds are not pans.
     *
     * A coverage key carries the zoom it was planned at, and the surround test predicts with the
     * CURRENT camera's `floorZoom`. Cross one integer zoom boundary and every predicted key is at a
     * zoom the published set contains none of, so the cover rises however wide the ring is. A pinch
     * crosses those boundaries by construction; only a movement that stays within one floor zoom
     * can be rescued by padding at all.
     */
    @Test
    fun `no ring of any size survives a change of floor zoom`() {
        val start = viewport(centerLongitude = 0.0)
        val surroundPlanner = FogViewportCoveragePlanner(paddingTiles = 0)
        val absurdRing = FogViewportCoveragePlanner(paddingTiles = 8, maxTiles = RING_ARM_MAX_TILES)
        val published = absurdRing.plan(start).keySet

        assertTrue(
            "the fixture must actually be a large ring, or this proves only that 0 is small",
            published.size > 200,
        )
        assertTrue(
            "every published key is planned at the camera's floor zoom",
            published.all { key -> key.zoom == ZOOM },
        )

        val zoomedIn = start.copy(floorZoom = ZOOM + 1)
        assertFalse(
            "one integer zoom step defeats a ring of 8 tiles: the predicted keys are at " +
                "${ZOOM + 1} and the published set holds none",
            fogViewportCoveredByPublishedTiles(
                viewport = zoomedIn,
                recentActualRequests = surroundPlanner.plan(zoomedIn).keySet,
                publishedKeys = published,
                planner = surroundPlanner,
            ),
        )
    }

    /** A small viewport well away from both poles and the antimeridian. */
    /**
     * `V03-011` section 14d, pinned on the host: the predicate is a RECTANGLE, so a ring is left
     * when an axis runs out and never when a hypotenuse does.
     *
     * Measured first on a device, where a diagonal drag of 2.10 tiles straight-line travel stayed
     * inside a one-tile ring and was briefly read as the ring outperforming its own width. It was
     * not: each axis was under one tile. The trap is that a pan is naturally reported as a distance,
     * and a distance is the one quantity this predicate never looks at.
     *
     * The move below is CHECKED to be diagonal-past-the-ring before it is used, so the case cannot
     * quietly become vacuous if the fixture's geometry is ever retuned.
     */
    @Test
    fun `a diagonal move past the ring in distance is still inside it on both axes`() {
        val start = viewport(centerLongitude = 0.0)
        val ringPlanner = FogViewportCoveragePlanner(paddingTiles = 1)
        val surroundPlanner = FogViewportCoveragePlanner(paddingTiles = 0)
        val published = ringPlanner.plan(start).keySet

        val moved = movedViewport(
            centerLongitude = ONE_TILE_DEGREES * 0.8,
            centerLatitude = DIAGONAL_NORTH_DEGREES,
        )
        val tiles = (1 shl ZOOM).toDouble()
        val deltaX = abs(
            WebMercator.normalizedX(ONE_TILE_DEGREES * 0.8) - WebMercator.normalizedX(0.0),
        ) * tiles
        val deltaY = abs(
            WebMercator.normalizedY(DIAGONAL_NORTH_DEGREES) - WebMercator.normalizedY(0.0),
        ) * tiles
        assertTrue(
            "the fixture must actually be a diagonal that travels FURTHER than one tile: " +
                "dx=$deltaX dy=$deltaY hypot=${hypot(deltaX, deltaY)}",
            hypot(deltaX, deltaY) > 1.0,
        )
        assertTrue(
            "...while staying under one tile on each axis, or this case proves nothing: " +
                "dx=$deltaX dy=$deltaY",
            deltaX < 1.0 && deltaY < 1.0,
        )

        assertTrue(
            "a one-tile ring holds a move of ${hypot(deltaX, deltaY)} tiles, because the surround " +
                "test is rectangle containment and neither axis left it",
            fogViewportCoveredByPublishedTiles(
                viewport = moved,
                recentActualRequests = surroundPlanner.plan(moved).keySet,
                publishedKeys = published,
                planner = surroundPlanner,
            ),
        )
    }

    /**
     * `V03-011` section 14d, second half: a ring of `p` holds MORE than `p` tiles of movement,
     * because the published rectangle is tile-aligned and the viewport does not fill it.
     *
     * The capacity is `p` plus whatever the alignment leaves on the leading side, which is why the
     * device measurements could not be predicted from `p` alone and why the arm's capacity is a
     * property of where the camera sits inside the tile grid as well as of the ring.
     */
    @Test
    fun `a ring of one holds more than one tile of movement, because tiles are aligned`() {
        val start = viewport(centerLongitude = 0.0)
        val published = FogViewportCoveragePlanner(paddingTiles = 1).plan(start).keySet
        val surroundPlanner = FogViewportCoveragePlanner(paddingTiles = 0)

        val beyondTheRing = viewport(centerLongitude = ONE_TILE_DEGREES * 1.4)
        assertTrue(
            "1.4 tiles is past the ring's nominal width and still covered: capacity is p plus the " +
                "tile alignment slack, not p",
            fogViewportCoveredByPublishedTiles(
                viewport = beyondTheRing,
                recentActualRequests = surroundPlanner.plan(beyondTheRing).keySet,
                publishedKeys = published,
                planner = surroundPlanner,
            ),
        )

        // And the slack is finite, so the case is not vacuous: far enough out, the cover rises.
        val wellPast = viewport(centerLongitude = ONE_TILE_DEGREES * 3.0)
        assertFalse(
            "the alignment slack is bounded; three tiles must still raise the cover",
            fogViewportCoveredByPublishedTiles(
                viewport = wellPast,
                recentActualRequests = surroundPlanner.plan(wellPast).keySet,
                publishedKeys = published,
                planner = surroundPlanner,
            ),
        )
    }

    private fun movedViewport(
        centerLongitude: Double,
        centerLatitude: Double,
    ): FogViewportCoverageRequest =
        FogViewportCoverageRequest(
            center = GeoPoint(centerLatitude, centerLongitude),
            floorZoom = ZOOM,
            nearLeft = GeoPoint(centerLatitude - 1.0, centerLongitude - 1.0),
            farLeft = GeoPoint(centerLatitude + 1.0, centerLongitude - 1.0),
            farRight = GeoPoint(centerLatitude + 1.0, centerLongitude + 1.0),
            nearRight = GeoPoint(centerLatitude - 1.0, centerLongitude + 1.0),
        )

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

        /**
         * A northward move that is under one tile in `y` while the eastward leg is 0.8 of one in
         * `x`, so the pair travels further than a tile without either axis leaving the ring. The
         * case asserts both halves of that before it uses it.
         */
        const val DIAGONAL_NORTH_DEGREES = 8.0
    }
}
