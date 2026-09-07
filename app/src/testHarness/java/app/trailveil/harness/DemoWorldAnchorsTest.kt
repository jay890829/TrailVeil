package app.trailveil.harness

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V03-013`: the world fixture's anchors, and the denser walk they carry.
 *
 * The owner asked for random world locations. What is asserted here is the part of "random" that has
 * to hold for the fixture to be usable: the same locations every run, spread rather than clustered,
 * and inside the range the walk generator can render without smearing.
 */
class DemoWorldAnchorsTest {

    @Test
    fun theAnchorsAreTheSameEveryRun() {
        assertEquals(DemoWorldAnchors.anchors(300), DemoWorldAnchors.anchors(300))
    }

    /** A short run must be a genuine prefix of a long one, or a quick check is a different test. */
    @Test
    fun aShorterRunIsAPrefixOfALongerOne() {
        assertEquals(DemoWorldAnchors.anchors(300).take(50), DemoWorldAnchors.anchors(50))
    }

    @Test
    fun everyAnchorIsSomewhereTheWalkCanBeDrawn() {
        DemoWorldAnchors.anchors().forEach { anchor ->
            assertTrue("latitude out of range: ${anchor.latitude}", abs(anchor.latitude) <= 70.0)
            assertTrue("longitude out of range: ${anchor.longitude}", abs(anchor.longitude) <= 180.0)
            assertTrue("an anchor claims to come from the device", !anchor.fromDevice)
        }
    }

    /**
     * They are actually spread, not huddled.
     *
     * A seeded generator that had been mis-wired - the same draw 300 times, or a tiny range - would
     * still produce 300 anchors and a fixture that looked like it worked. Quartering the globe and
     * requiring every quarter to be occupied is the cheapest test that fails on that.
     */
    @Test
    fun theAnchorsCoverTheGlobeRatherThanOneCorner() {
        val quadrants = DemoWorldAnchors.anchors()
            .map { anchor -> (anchor.latitude >= 0.0) to (anchor.longitude >= 0.0) }
            .toSet()
        assertEquals("the anchors do not reach all four quadrants", 4, quadrants.size)
        assertEquals(
            "anchors are not distinct",
            DemoWorldAnchors.DEFAULT_SESSIONS,
            DemoWorldAnchors.anchors().toSet().size,
        )
    }

    /** The dense session is still the same walk, only sampled finer. */
    @Test
    fun theDenseWalkKeepsTheShapeAndCountOfTheSparseOne() {
        val dense = DemoExplorationTrack.around(25.0, 121.0, DemoWorldAnchors.DEFAULT_POINTS_PER_SESSION)
        assertEquals(DemoWorldAnchors.DEFAULT_POINTS_PER_SESSION, dense.size)
        assertTrue(
            "the dense walk should still be at least a thousand points",
            dense.size >= 1_000,
        )
        assertTrue(
            "the island must stay detached at the load fixture's density",
            DemoExplorationTrack.separationFromIsland(
                DemoWorldAnchors.DEFAULT_POINTS_PER_SESSION,
            ) > DemoExplorationTrack.MIN_ISLAND_SEPARATION_METRES,
        )
    }

    /** Denser sampling walks the same ground, so the reported distance must not grow with it. */
    @Test
    fun theWalkedDistanceDoesNotGrowWithDensity() {
        val sparse = DemoExplorationTrack.lengthMetres()
        val dense = DemoExplorationTrack.lengthMetres(
            DemoWorldAnchors.DEFAULT_POINTS_PER_SESSION,
        )
        assertTrue(
            "distance moved from $sparse m to $dense m purely by sampling finer",
            abs(dense - sparse) / sparse < 0.05,
        )
    }
}
