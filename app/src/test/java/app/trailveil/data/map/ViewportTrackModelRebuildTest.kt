package app.trailveil.data.map

import app.trailveil.map.fog.GeoPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ViewportTrackModelRebuildTest {
    private val whole = ViewportBounds(-1.0, 1.0, -180.0, 180.0)
    private fun reader(points: List<ViewportTrackPoint>) = ViewportTrackPointReader { south, north, interval ->
        points.filter { it.latitude in south..north && it.longitude in interval.west..interval.east }
    }
    private fun point(id: Long, sequence: Long, longitude: Double = 0.0,
        session: Long = 1L, segment: Long = 1L, segmentSequence: Long = 0L) =
        ViewportTrackPoint(id, session, segment, segmentSequence, sequence, 0.0, longitude)

    @Test fun repeatedSequencesGapsAndLongMaximumKeepExactlyTheOriginalRuns() = runTest {
        val points = listOf(point(1, 0), point(2, 0), point(3, 1), point(4, 3),
            point(5, Long.MAX_VALUE - 1), point(6, Long.MAX_VALUE), point(7, Long.MAX_VALUE))
        val actual = ViewportTrackDataSource(reader(points)).read(whole)
        assertEquals(listOf(1, 2, 1, 2, 1), actual.segments.map { it.points.size })
        assertEquals(ViewportTrackDataSourceB44Reference(reader(points)).read(whole), actual)
    }

    @Test fun shuffledInterleavedIdentitiesAndFirstDuplicateIdWinMatchB44() = runTest {
        val random = Random(20260912)
        repeat(100) { iteration ->
            val points = List(120) { index ->
                point(index.toLong() + 1, listOf(0L, 1L, 2L, 4L, Long.MAX_VALUE - 1, Long.MAX_VALUE).random(random),
                    random.nextDouble(-0.9, 0.9), session = 3_000_000_000L + random.nextInt(3),
                    segment = 4_000_000_000L + random.nextInt(4), segmentSequence = random.nextLong(3))
            }.shuffled(random)
            val input = points + points[0].copy(longitude = 0.95) + points[1]
            val expected = ViewportTrackDataSourceB44Reference(reader(input)).read(whole)
            val actual = ViewportTrackDataSource(reader(input)).read(whole)
            assertEquals("model iteration $iteration", expected, actual)
            assertEquals("render-local ids iteration $iteration", expected.toFogTrackSegments(), actual.toFogTrackSegments())
        }
    }

    @Test fun perWindowFilteringStillBreaksSequencesBeforeRebuildingModels() = runTest {
        val points = listOf(point(1, 0, 0.2), point(2, 1, 1.5), point(3, 2, 0.8),
            point(4, 3, 179.5), point(5, 4, -179.5), point(6, 5, 180.0), point(7, 6, -180.0))
        val windows = listOf(whole.copy(west = 0.0, east = 1.0), whole.copy(west = 0.5, east = 2.0),
            whole.copy(west = 179.0, east = -179.0), whole.copy(west = 178.0, east = -178.0))
        val expected = ViewportTrackDataSourceB44Reference(reader(points.reversed())).readBatch(windows)
        val actual = ViewportTrackDataSource(reader(points.reversed())).readBatch(windows)
        assertEquals(expected, actual)
        assertEquals(listOf(1, 1), actual[0].segments.map { it.points.size })
        assertEquals(expected.map { it.toFogTrackSegments() }, actual.map { it.toFogTrackSegments() })
    }

    @Test fun manySingletonsAndOneDenseRunKeepOrderedModels() = runTest {
        for (points in listOf(
            List(4_000) { point(it + 1L, 0L, segment = it + 1L) },
            List(20_000) { point(it + 1L, it.toLong(), longitude = it / 100_000.0) },
            emptyList(),
        )) {
            assertEquals(ViewportTrackDataSourceB44Reference(reader(points)).read(whole),
                ViewportTrackDataSource(reader(points)).read(whole))
        }
    }

    @Test fun coarseAndUnavailableCoarseRoutesRemainEquivalent() = runTest {
        val fallback = reader(listOf(point(1, 0), point(2, 2)))
        assertEquals(ViewportTrackDataSourceB44Reference(fallback).read(whole, coarse = true),
            ViewportTrackDataSource(fallback).read(whole, coarse = true))
        val coarse = object : ViewportTrackPointReader by fallback {
            override suspend fun readCoarseCells(south: Double, north: Double, interval: LongitudeInterval) =
                listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.5), GeoPoint(0.0, 0.0))
        }
        assertEquals(ViewportTrackDataSourceB44Reference(coarse).read(whole, coarse = true),
            ViewportTrackDataSource(coarse).read(whole, coarse = true))
    }
}
