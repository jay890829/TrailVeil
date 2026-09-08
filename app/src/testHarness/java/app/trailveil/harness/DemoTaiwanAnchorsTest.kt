package app.trailveil.harness

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V03-013`: the load fixture's anchors, and the denser walk they carry.
 *
 * The owner asked for arbitrary locations inside Taiwan. What is asserted here is the part of
 * "arbitrary" that has to hold for the fixture to be usable: the same locations every run, spread
 * across the box rather than clustered in a corner, and inside it.
 */
class DemoTaiwanAnchorsTest {

    @Test
    fun theAnchorsAreTheSameEveryRun() {
        assertEquals(DemoTaiwanAnchors.anchors(200), DemoTaiwanAnchors.anchors(200))
    }

    /** A short run must be a genuine prefix of a long one, or a quick check is a different test. */
    @Test
    fun aShorterRunIsAPrefixOfALongerOne() {
        assertEquals(DemoTaiwanAnchors.anchors(200).take(50), DemoTaiwanAnchors.anchors(50))
    }

    /** Every anchor is inside Taiwan's box - the request was a place, not merely a spread. */
    @Test
    fun everyAnchorIsInsideTaiwansBoundingBox() {
        DemoTaiwanAnchors.anchors().forEach { anchor ->
            assertTrue(
                "latitude outside Taiwan: ${anchor.latitude}",
                anchor.latitude in DemoTaiwanAnchors.SOUTH_LATITUDE..DemoTaiwanAnchors.NORTH_LATITUDE,
            )
            assertTrue(
                "longitude outside Taiwan: ${anchor.longitude}",
                anchor.longitude in
                    DemoTaiwanAnchors.WEST_LONGITUDE..DemoTaiwanAnchors.EAST_LONGITUDE,
            )
            assertTrue("an anchor claims to come from the device", !anchor.fromDevice)
        }
    }

    /**
     * They are actually spread across the box, not huddled in a corner.
     *
     * A seeded generator that had been mis-wired - the same draw 200 times, or a collapsed range -
     * would still produce 200 anchors and a fixture that looked like it worked. Quartering the box
     * and requiring every quarter to be occupied is the cheapest test that fails on that.
     */
    @Test
    fun theAnchorsCoverTheBoxRatherThanOneCorner() {
        val midLatitude =
            (DemoTaiwanAnchors.SOUTH_LATITUDE + DemoTaiwanAnchors.NORTH_LATITUDE) / 2.0
        val midLongitude =
            (DemoTaiwanAnchors.WEST_LONGITUDE + DemoTaiwanAnchors.EAST_LONGITUDE) / 2.0
        val quadrants = DemoTaiwanAnchors.anchors()
            .map { anchor -> (anchor.latitude >= midLatitude) to (anchor.longitude >= midLongitude) }
            .toSet()
        assertEquals("the anchors do not reach all four quarters of the box", 4, quadrants.size)
        assertEquals(
            "anchors are not distinct",
            DemoTaiwanAnchors.DEFAULT_SESSIONS,
            DemoTaiwanAnchors.anchors().toSet().size,
        )
    }

    /** The dense session is still the same walk, only sampled finer. */
    @Test
    fun theDenseWalkKeepsTheShapeAndCountOfTheSparseOne() {
        val dense = DemoExplorationTrack.around(25.0, 121.0, DemoTaiwanAnchors.DEFAULT_POINTS_PER_SESSION)
        assertEquals(DemoTaiwanAnchors.DEFAULT_POINTS_PER_SESSION, dense.size)
        assertTrue(
            "the dense walk should still be at least a thousand points",
            dense.size >= 1_000,
        )
        assertTrue(
            "the island must stay detached at the load fixture's density",
            DemoExplorationTrack.separationFromIsland(
                DemoTaiwanAnchors.DEFAULT_POINTS_PER_SESSION,
            ) > DemoExplorationTrack.MIN_ISLAND_SEPARATION_METRES,
        )
    }

    /** Denser sampling walks the same ground, so the reported distance must not grow with it. */
    @Test
    fun theWalkedDistanceDoesNotGrowWithDensity() {
        val sparse = DemoExplorationTrack.lengthMetres()
        val dense = DemoExplorationTrack.lengthMetres(
            DemoTaiwanAnchors.DEFAULT_POINTS_PER_SESSION,
        )
        assertTrue(
            "distance moved from $sparse m to $dense m purely by sampling finer",
            abs(dense - sparse) / sparse < 0.05,
        )
    }
}
