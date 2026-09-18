package app.trailveil.harness

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

class TrackRegionClippingTest {
    private val factory = GeometryFactory()
    private val clip = factory.createPolygon(arrayOf(
        Coordinate(0.0, 0.0), Coordinate(100.0, 0.0), Coordinate(100.0, 100.0),
        Coordinate(0.0, 100.0), Coordinate(0.0, 0.0),
    ))

    @Test fun buffersInsideOnAcrossAndOutsideEdgesMatchActualIntersection() {
        for (x in listOf(-10.0, 0.0, 1.0, 10.0, 50.0, 99.0, 100.0, 110.0)) {
            for (radius in listOf(1.0, 10.0, 50.0)) {
                val area = factory.createLineString(arrayOf(
                    Coordinate(x, 25.0), Coordinate(x + 5.0, 75.0),
                )).buffer(radius)
                val expected = area.intersection(clip)
                val actual = clipTrackAreaToRectangle(area, clip)
                assertTrue("clipping differs at x=$x radius=$radius",
                    if (expected.isEmpty) actual.isEmpty else actual.equalsTopo(expected))
                assertTrue("area escaped its partition", actual.difference(clip).isEmpty)
            }
        }
    }

    @Test fun aContainedHoledAreaAndAnEmptyAreaNeedNoOverlayOperation() {
        val area = factory.createPoint(Coordinate(50.0, 50.0)).buffer(20.0)
            .difference(factory.createPoint(Coordinate(50.0, 50.0)).buffer(5.0))
        assertSame(area, clipTrackAreaToRectangle(area, clip))
        assertTrue(area.equalsTopo(area.intersection(clip)))
        val empty = factory.createPolygon()
        assertSame(empty, clipTrackAreaToRectangle(empty, clip))
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelopeContainmentCannotSubstituteForANonRectangularClip() {
        val triangle = factory.createPolygon(arrayOf(
            Coordinate(0.0, 0.0), Coordinate(100.0, 0.0), Coordinate(0.0, 100.0), Coordinate(0.0, 0.0),
        ))
        clipTrackAreaToRectangle(factory.createPoint(Coordinate(75.0, 75.0)).buffer(1.0), triangle)
    }
}
