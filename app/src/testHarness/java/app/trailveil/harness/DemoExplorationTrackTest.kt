package app.trailveil.harness

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V03-013`: the demo walk's shape, pinned where it is cheap to pin.
 *
 * What is asserted here is not that the track looks nice - it is the three properties the arm
 * comparison actually rests on. A demo that violated any of them would still draw something, and
 * the wrong conclusion drawn from it would be about the FOG rather than about the fixture.
 */
class DemoExplorationTrackTest {

    /**
     * The same anchor gives the same walk, every time.
     *
     * The comparison method is "switch arm, look again". If the track moved between looks, the
     * difference on screen would be the track's, and every reading taken this way would be void.
     */
    @Test
    fun theWalkIsDeterministic() {
        val first = DemoExplorationTrack.around(25.03, 121.54)
        val second = DemoExplorationTrack.around(25.03, 121.54)
        assertEquals(first, second)
        assertEquals(DemoExplorationTrack.TOTAL_POINTS, first.size)
    }

    /**
     * Consecutive points are closer together than the reveal radius, so the corridor is a corridor.
     *
     * At 25 m of reveal, a stride longer than about 50 m leaves fog between two circles and the
     * "walk" becomes a dotted line - which reads as a fog defect rather than as a fixture defect.
     * The two jumps between components are excluded deliberately; they are what makes the island an
     * island, and they are bounded separately below.
     */
    @Test
    fun stridesStayWellInsideTheRevealRadius() {
        val offsets = DemoExplorationTrack.offsets()
        var walked = 0
        for (index in 1 until offsets.size) {
            val (east, north) = offsets[index]
            val (previousEast, previousNorth) = offsets[index - 1]
            val step = sqrt(
                (east - previousEast) * (east - previousEast) +
                    (north - previousNorth) * (north - previousNorth),
            )
            if (step <= DemoExplorationTrack.MAX_WALKED_STEP_METRES) {
                walked += 1
                assertTrue(
                    "a $step m stride leaves fog between two 25 m reveals",
                    step < MAX_CONTINUOUS_STRIDE_METRES,
                )
            }
        }
        assertTrue("almost every step should be a walked one", walked > offsets.size - 5)
    }

    /**
     * The island is far enough away to stay a separate revealed component.
     *
     * Ring count is the vector arms' whole cost question, and a count of one answers nothing. If the
     * island's reveal touched the corridor's they would merge into a single component and the demo
     * would silently stop testing that.
     */
    @Test
    fun theIslandStaysDetached() {
        assertTrue(
            "the island merged into the rest of the walk",
            DemoExplorationTrack.separationFromIsland() >
                DemoExplorationTrack.MIN_ISLAND_SEPARATION_METRES,
        )
    }

    /** The loop closes, which is what makes reveal circles overlap themselves. */
    @Test
    fun theLoopClosesOnItself() {
        val offsets = DemoExplorationTrack.offsets()
        val loop = offsets.drop(DemoExplorationTrack.CORRIDOR_POINTS)
            .take(DemoExplorationTrack.LOOP_POINTS)
        val (firstEast, firstNorth) = loop.first()
        val (lastEast, lastNorth) = loop.last()
        val gap = sqrt(
            (lastEast - firstEast) * (lastEast - firstEast) +
                (lastNorth - firstNorth) * (lastNorth - firstNorth),
        )
        assertTrue("the loop does not close: $gap m apart", gap < MAX_LOOP_CLOSING_GAP_METRES)
    }

    /**
     * The walk lands where it was anchored, and spans about what it claims to.
     *
     * A sign error in the longitude conversion would put the demo on the other side of the anchor,
     * which at a phone's zoom is off screen - and "seeding did nothing" is what that looks like.
     */
    @Test
    fun theWalkSurroundsItsAnchor() {
        val anchorLatitude = 25.03
        val anchorLongitude = 121.54
        val points = DemoExplorationTrack.around(anchorLatitude, anchorLongitude)
        val north = points.maxOf { it.latitude }
        val south = points.minOf { it.latitude }
        val east = points.maxOf { it.longitude }
        val west = points.minOf { it.longitude }

        assertTrue("the anchor is not inside the walk's latitudes", anchorLatitude in south..north)
        assertTrue("the anchor is not inside the walk's longitudes", anchorLongitude in west..east)

        val heightMetres = (north - south) * METRES_PER_DEGREE_LATITUDE
        assertTrue("the walk is implausibly tall: $heightMetres m", heightMetres < 2_000.0)
        assertTrue("the walk is implausibly short: $heightMetres m", heightMetres > 500.0)
    }

    /**
     * A polar anchor is clamped rather than allowed to smear the walk around the world.
     *
     * Metres per degree of longitude collapses towards the pole, so an unclamped conversion turns a
     * 1.4 km corridor into one that wraps the planet. Nobody explores at 89 degrees, which is
     * exactly why an unclamped bug there would never be found by looking.
     */
    @Test
    fun aPolarAnchorStaysABoundedWalk() {
        val points = DemoExplorationTrack.around(89.5, 0.0)
        val span = points.maxOf { it.longitude } - points.minOf { it.longitude }
        assertTrue("a polar anchor smeared the walk across $span degrees", abs(span) < 30.0)
    }

    /** The reported distance is the walked length, not the sum including the two teleports. */
    @Test
    fun theReportedDistanceExcludesTheJumpsBetweenComponents() {
        val naive = DemoExplorationTrack.offsets().let { offsets ->
            var total = 0.0
            for (index in 1 until offsets.size) {
                val (east, north) = offsets[index]
                val (previousEast, previousNorth) = offsets[index - 1]
                total += sqrt(
                    (east - previousEast) * (east - previousEast) +
                        (north - previousNorth) * (north - previousNorth),
                )
            }
            total
        }
        val reported = DemoExplorationTrack.lengthMetres()
        assertTrue("the reported distance should be shorter than the naive sum", reported < naive)
        assertTrue("the reported distance is implausible: $reported m", reported > 1_000.0)
    }

    private companion object {
        const val MAX_CONTINUOUS_STRIDE_METRES = 25.0
        const val MAX_LOOP_CLOSING_GAP_METRES = 15.0
        const val METRES_PER_DEGREE_LATITUDE = 111_320.0
    }
}
