package app.trailveil.data.map

import app.trailveil.map.fog.GeoPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTrackDataSourceTest {
    @Test
    fun normalViewportUsesOneIntervalAndOrdersPointsWithinItsPersistedSegment() = runTest {
        val reader = RecordingReader(
            responses = mapOf(
                LongitudeInterval(120.0, 122.0) to listOf(
                    point(pointId = 3, pointSequence = 2, latitude = 25.2, longitude = 121.2),
                    point(pointId = 1, pointSequence = 0, latitude = 25.0, longitude = 121.0),
                    point(pointId = 2, pointSequence = 1, latitude = 25.1, longitude = 121.1),
                ),
            ),
        )

        val result = ViewportTrackDataSource(reader).read(
            ViewportBounds(south = 24.0, north = 26.0, west = 120.0, east = 122.0),
        )

        assertEquals(listOf(ReadRequest(24.0, 26.0, LongitudeInterval(120.0, 122.0))), reader.requested)
        assertEquals(1, result.segments.size)
        assertEquals(7L, result.segments.single().sessionId)
        assertEquals(10L, result.segments.single().segmentId)
        assertEquals(listOf(121.0, 121.1, 121.2), result.segments.single().points.map { it.longitude })
    }

    @Test
    fun datelineViewportSplitsQueriesDedupesAndDoesNotBridgeSegments() = runTest {
        val east = LongitudeInterval(170.0, 180.0)
        val west = LongitudeInterval(-180.0, -170.0)
        val duplicate = point(pointId = 2, pointSequence = 1, longitude = 179.0)
        val reader = RecordingReader(
            responses = mapOf(
                east to listOf(point(pointId = 1, pointSequence = 0, longitude = 178.0), duplicate),
                west to listOf(duplicate, point(pointId = 3, segmentId = 11, segmentSequence = 1, longitude = -179.0)),
            ),
        )

        val result = ViewportTrackDataSource(reader).read(
            ViewportBounds(south = -10.0, north = 10.0, west = 170.0, east = -170.0),
        )

        assertEquals(listOf(ReadRequest(-10.0, 10.0, east), ReadRequest(-10.0, 10.0, west)), reader.requested)
        assertEquals(listOf(10L, 11L), result.segments.map(ViewportTrackSegment::segmentId))
        assertEquals(listOf(178.0, 179.0), result.segments[0].points.map { it.longitude })
        assertEquals(listOf(-179.0), result.segments[1].points.map { it.longitude })
    }

    @Test
    fun groupingUsesSessionAndSegmentIdentityAndSortsGroupsStably() = runTest {
        val interval = LongitudeInterval(-20.0, 20.0)
        val reader = RecordingReader(
            responses = mapOf(
                interval to listOf(
                    point(pointId = 4, sessionId = 2, segmentId = 10, segmentSequence = 0, pointSequence = 0),
                    point(pointId = 3, sessionId = 1, segmentId = 12, segmentSequence = 1, pointSequence = 0),
                    point(pointId = 2, sessionId = 1, segmentId = 11, segmentSequence = 0, pointSequence = 1),
                    point(pointId = 1, sessionId = 1, segmentId = 11, segmentSequence = 0, pointSequence = 0),
                ),
            ),
        )

        val result = ViewportTrackDataSource(reader).read(
            ViewportBounds(south = -20.0, north = 20.0, west = -20.0, east = 20.0),
        )

        assertEquals(
            listOf(1L to 11L, 1L to 12L, 2L to 10L),
            result.segments.map { it.sessionId to it.segmentId },
        )
        assertEquals(listOf(0.0, 1.0), result.segments.first().points.map { it.latitude })
        assertEquals(listOf(0, 1, 2), result.toFogTrackSegments().map { it.id })
    }

    @Test
    fun duplicatePointIdsAreKeptOnceBeforeOrdering() = runTest {
        val interval = LongitudeInterval(0.0, 10.0)
        val reader = RecordingReader(
            responses = mapOf(
                interval to listOf(
                    point(pointId = 8, pointSequence = 1),
                    point(pointId = 8, pointSequence = 0),
                    point(pointId = 9, pointSequence = 2),
                ),
            ),
        )

        val result = ViewportTrackDataSource(reader).read(
            ViewportBounds(south = 0.0, north = 10.0, west = 0.0, east = 10.0),
        )

        assertEquals(listOf(1.0, 2.0), result.segments.single().points.map { it.latitude })
    }

    @Test
    fun nonContiguousViewportPointsFromOnePersistedSegmentAreSplit() = runTest {
        val interval = LongitudeInterval(0.0, 10.0)
        val reader = RecordingReader(
            responses = mapOf(
                interval to listOf(
                    point(pointId = 6, pointSequence = 6),
                    point(pointId = 2, pointSequence = 2),
                    point(pointId = 5, pointSequence = 5),
                    point(pointId = 1, pointSequence = 1),
                ),
            ),
        )

        val result = ViewportTrackDataSource(reader).read(
            ViewportBounds(south = 0.0, north = 10.0, west = 0.0, east = 10.0),
        )

        assertEquals(listOf(10L, 10L), result.segments.map(ViewportTrackSegment::segmentId))
        assertEquals(
            listOf(listOf(1.0, 2.0), listOf(5.0, 6.0)),
            result.segments.map { segment -> segment.points.map { it.latitude } },
        )
    }

    @Test
    fun invalidBoundsIntervalsAndProjectionPointsAreRejected() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            ViewportBounds(south = 1.0, north = 0.0, west = 0.0, east = 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LongitudeInterval(west = 1.0, east = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            point(latitude = 91.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            point(longitude = Double.NaN)
        }
    }

    /**
     * `P4-037`: many cells become many single-point segments, never one segment holding them all.
     *
     * Found by closure verification, which noticed that every path reaching `coarseReadModel` today
     * hands it exactly ONE cell — so the property its KDoc leans on was true and unobserved. Put all
     * the cells into a single segment instead and `FogTileRenderer` walks that segment's points in
     * order drawing a capsule between each consecutive pair, which at world zoom joins ground on
     * opposite sides of the planet and reveals everything between. That is the worst failure this
     * change could produce, and it is the direction no leak audit catches, because a leak audit
     * accepts extra fog and this reveals extra GROUND.
     *
     * Three cells far enough apart that a joining stroke would be unmistakable.
     */
    @Test
    fun eachCoarseCellBecomesItsOwnSinglePointSegment() = runTest {
        val cells = listOf(
            GeoPoint(latitude = 25.0330, longitude = 121.5654),
            GeoPoint(latitude = -33.8688, longitude = 151.2093),
            GeoPoint(latitude = 51.5074, longitude = -0.1278),
        )
        val source = ViewportTrackDataSource(CoarseReader(cells))

        val model = source.read(
            bounds = ViewportBounds(south = -85.0, north = 85.0, west = -180.0, east = 180.0),
            coarse = true,
        )

        assertEquals("cells were merged into fewer segments", cells.size, model.segments.size)
        assertTrue(
            "a coarse segment holds more than one point, so the renderer will stroke between them",
            model.segments.all { segment -> segment.points.size == 1 },
        )
        assertEquals(
            "the cells the reader supplied are not the points the renderer will draw",
            cells.toSet(),
            model.segments.flatMap { it.points }.toSet(),
        )
    }

    /**
     * `P4-037`: a reader with no coarse route is served POINTS at world zoom, not nothing.
     *
     * The contract is that `readCoarseCells` answers null for "ask me the other way" and an empty
     * list for "that box holds nothing" — opposite meanings, one of which is the difference between
     * falling back and drawing no fog at all. Nothing bound it. Change the interface default from
     * `null` to `emptyList()` and every reader that does not override it silently reports the world
     * as unexplored, and the one existing world-zoom test cannot catch it because its assertion
     * compares a count against itself and both sides become zero.
     *
     * This reader deliberately does NOT override `readCoarseCells`, so it inherits exactly the
     * default under test.
     */
    @Test
    fun aReaderWithNoCoarseRouteStillGetsPointsAtWorldZoom() = runTest {
        val stored = point(pointId = 1, latitude = 0.0, longitude = 0.0)
        val reader = RecordingReader(mapOf(LongitudeInterval(-180.0, 180.0) to listOf(stored)))
        val source = ViewportTrackDataSource(reader)

        val model = source.read(
            bounds = ViewportBounds(south = -85.0, north = 85.0, west = -180.0, east = 180.0),
            coarse = true,
        )

        assertTrue("the reader without a coarse route was never asked for points", reader.requested.isNotEmpty())
        assertEquals(
            "asking for cells from a reader that has none produced no fog instead of falling back",
            listOf(listOf(GeoPoint(stored.latitude, stored.longitude))),
            model.segments.map { segment -> segment.points },
        )
    }

    /** Answers the coarse route, so the cell path is actually exercised. */
    private class CoarseReader(private val cells: List<GeoPoint>) : ViewportTrackPointReader {
        override suspend fun read(
            south: Double,
            north: Double,
            interval: LongitudeInterval,
        ): List<ViewportTrackPoint> = emptyList()

        override suspend fun readCoarseCells(
            south: Double,
            north: Double,
            interval: LongitudeInterval,
        ): List<GeoPoint> = cells
    }

    private fun point(
        pointId: Long = 1,
        sessionId: Long = 7,
        segmentId: Long = 10,
        segmentSequence: Long = 0,
        pointSequence: Long = 0,
        latitude: Double = pointSequence.toDouble(),
        longitude: Double = pointSequence.toDouble(),
    ) = ViewportTrackPoint(
        pointId = pointId,
        sessionId = sessionId,
        segmentId = segmentId,
        segmentSequence = segmentSequence,
        pointSequence = pointSequence,
        latitude = latitude,
        longitude = longitude,
    )

    private data class ReadRequest(
        val south: Double,
        val north: Double,
        val interval: LongitudeInterval,
    )

    private class RecordingReader(
        private val responses: Map<LongitudeInterval, List<ViewportTrackPoint>>,
    ) : ViewportTrackPointReader {
        val requested = mutableListOf<ReadRequest>()

        override suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> {
            requested += ReadRequest(south, north, interval)
            return responses[interval].orEmpty()
        }
    }
}
