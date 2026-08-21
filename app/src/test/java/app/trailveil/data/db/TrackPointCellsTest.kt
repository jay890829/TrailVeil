package app.trailveil.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `P4-037`. The cell arithmetic, and the two tethers its KDoc promises but cannot enforce.
 */
class TrackPointCellsTest {

    @Test
    fun theSqlGranularityIsTheKotlinGranularity() {
        // The tether the KDoc claims. Room needs a compile-time constant in `@Query`, so the
        // granularity is spelled a second time as a string, and a second spelling of a number is
        // exactly what P4-036 found insufficient when it was held together by a comment. A drift
        // here would write cells at one size and read them at another, which drops regions from the
        // world read and so draws MORE fog and passes every leak audit.
        assertEquals(
            TrackPointCells.CELLS_PER_DEGREE,
            TrackPointCells.CELLS_PER_DEGREE_SQL.toDouble(),
            0.0,
        )
    }

    @Test
    fun theCoarseZoomCeilingIsDerivedFromTheSubPixelMarginRatherThanChosen() {
        // MAX_RENDER_ZOOM claims to be derived from MINIMUM_CELLS_PER_MASK_PIXEL. Both sides of the
        // boundary are asserted, so raising the margin without the ceiling following it fails here
        // instead of silently letting cells stand in where they could be drawn.
        assertTrue(
            "the ceiling itself must satisfy the margin",
            TrackPointCells.cellsPerMaskPixel(TrackPointCells.MAX_RENDER_ZOOM) >=
                TrackPointCells.MINIMUM_CELLS_PER_MASK_PIXEL,
        )
        assertFalse(
            "one zoom finer must NOT satisfy it, or the ceiling is not the boundary",
            TrackPointCells.cellsPerMaskPixel(TrackPointCells.MAX_RENDER_ZOOM + 1) >=
                TrackPointCells.MINIMUM_CELLS_PER_MASK_PIXEL,
        )
    }

    @Test
    fun theCoarseRouteIsOfferedOnlyWhereACellCannotBeDrawn() {
        assertTrue(TrackPointCells.coarseReadIsSubPixel(0))
        assertTrue(TrackPointCells.coarseReadIsSubPixel(TrackPointCells.MAX_RENDER_ZOOM))
        assertFalse(TrackPointCells.coarseReadIsSubPixel(TrackPointCells.MAX_RENDER_ZOOM + 1))
        // The zoom the exploration gates use, named so a widened ceiling is caught by a case whose
        // failure says which zoom it broke rather than only that a boundary moved.
        assertFalse("exploration zoom must read points", TrackPointCells.coarseReadIsSubPixel(14))
    }

    @Test
    fun aCellCentreLandsBackInsideItsOwnCell() {
        // The substitution's whole safety argument is that a centre stands for its cell. A centre
        // that rounded into the neighbouring cell would move a dot by a whole cell rather than half.
        listOf(-89.9, -33.8688, -0.001, 0.0, 25.0330, 89.9).forEach { latitude ->
            val cell = TrackPointCells.latitudeCellOf(latitude)
            assertEquals(
                "latitude $latitude",
                cell,
                TrackPointCells.latitudeCellOf(TrackPointCells.latitudeAtCentreOf(cell)),
            )
        }
        listOf(-179.9, -121.5, -0.001, 0.0, 121.5654, 179.9).forEach { longitude ->
            val cell = TrackPointCells.longitudeCellOf(longitude)
            assertEquals(
                "longitude $longitude",
                cell,
                TrackPointCells.longitudeCellOf(TrackPointCells.longitudeAtCentreOf(cell)),
            )
        }
    }

    @Test
    fun aCentreIsNeverMoreThanHalfACellFromAnyPointItStandsFor() {
        // The bound the sub-pixel argument actually rests on. Stated as an assertion because the
        // KDoc's "worst displacement is half a cell" is the input to every zoom claim above it.
        val cellDegrees = 1.0 / TrackPointCells.CELLS_PER_DEGREE
        listOf(25.0330, -33.8688, 0.0).forEach { latitude ->
            val centre = TrackPointCells.latitudeAtCentreOf(TrackPointCells.latitudeCellOf(latitude))
            assertTrue(
                "latitude $latitude moved ${Math.abs(centre - latitude)} degrees",
                Math.abs(centre - latitude) <= cellDegrees / 2.0 + TOLERANCE,
            )
        }
        listOf(121.5654, -0.5, 179.5).forEach { longitude ->
            val centre =
                TrackPointCells.longitudeAtCentreOf(TrackPointCells.longitudeCellOf(longitude))
            assertTrue(
                "longitude $longitude moved ${Math.abs(centre - longitude)} degrees",
                Math.abs(centre - longitude) <= cellDegrees / 2.0 + TOLERANCE,
            )
        }
    }

    @Test
    fun theLatitudeCellIsTheLatitudeBucketRatherThanASecondScheme() {
        // Reused, not reimplemented: the cell table's latitude axis IS P4-036's bucket, so the two
        // cannot disagree about which band a point belongs to.
        listOf(-89.9, 0.0, 25.0330, 89.9).forEach { latitude ->
            assertEquals(
                LatitudeBuckets.of(latitude),
                TrackPointCells.latitudeCellOf(latitude),
            )
        }
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
