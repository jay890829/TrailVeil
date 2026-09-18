package app.trailveil.data.map

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ViewportTrackReadBatchTest {
    @Test fun overlappingWindowsShareOneReadButDoNotJoinAcrossTheNarrowerWindowsGap() = runTest {
        val points = listOf(point(1, 0, 0.2), point(2, 1, 1.5), point(3, 2, 0.8))
        val windows = listOf(box(0.0, 1.0), box(0.5, 2.0))
        val reader = Reader(points.reversed())
        val batched = ViewportTrackDataSource(reader).readBatch(windows)
        val expected = windows.map { ViewportTrackDataSource(Reader(points)).read(it) }
        assertEquals(expected, batched)
        assertEquals(listOf(1, 1), batched[0].segments.map { it.points.size })
        assertEquals(listOf(2), batched[1].segments.map { it.points.size })
        assertEquals(listOf(box(0.0, 2.0)), reader.requests)
    }

    @Test fun datelineWindowsUseTwoShortUnionsAndRetainExactEdgeMembership() = runTest {
        val points = listOf(point(1, 0, 180.0), point(2, 1, -180.0), point(3, 2, -178.5),
            point(4, 3, 178.5), point(5, 4, 0.0))
        val windows = listOf(box(179.0, -179.0), box(178.0, -178.0))
        val reader = Reader(points + points.first()) // duplicate id is still a single canonical point
        val actual = ViewportTrackDataSource(reader).readBatch(windows)
        assertEquals(windows.map { ViewportTrackDataSource(Reader(points)).read(it) }, actual)
        assertEquals(listOf(box(-180.0, -178.0), box(178.0, 180.0)), reader.requests)
        assertFalse(actual.flatMap { it.segments }.flatMap { it.points }.any { it.longitude == 0.0 })
    }

    @Test fun disjointIntervalsNeverQueryTheGapAndDifferentLatitudeBandsStayIndependent() = runTest {
        val reader = Reader(listOf(point(1, 0, 1.0), point(2, 1, 3.0), point(3, 2, 5.0)))
        val windows = listOf(box(0.0, 2.0), box(4.0, 6.0))
        val actual = ViewportTrackDataSource(reader).readBatch(windows)
        assertEquals(windows, reader.requests)
        assertEquals(listOf(1, 1), actual.map { it.segments.sumOf { segment -> segment.points.size } })
        reader.requests.clear()
        val different = listOf(windows[0], windows[1].copy(south = -2.0, north = 2.0))
        ViewportTrackDataSource(reader).readBatch(different)
        assertEquals(different, reader.requests)
    }

    @Test fun manyInputsAreProcessedInBoundedGroupsAndAllModelsMatchIndependentReads() = runTest {
        val points = (0..120).map { index -> point(index + 1L, index.toLong(), index / 100.0) }
        val windows = (0..9).map { index -> box(index / 10.0, index / 10.0 + 0.3) }
        val reader = Reader(points.shuffled(kotlin.random.Random(41)))
        val actual = ViewportTrackDataSource(reader).readBatch(windows)
        assertEquals(windows.map { ViewportTrackDataSource(Reader(points)).read(it) }, actual)
        assertEquals(3, reader.requests.size)
        assertTrue(reader.requests.all { it.east - it.west <= 0.6000000000000001 })
        assertEquals(emptyList<ViewportTrackReadModel>(), ViewportTrackDataSource(reader).readBatch(emptyList()))
    }

    @Test fun cancellationAfterStorageReturnCannotProduceAPartialBatchAnswer() = runTest {
        var published = false
        val reader = ViewportTrackPointReader { _, _, _ ->
            currentCoroutineContext().cancel()
            listOf(point(1, 0, 0.5))
        }
        val job = launch {
            ViewportTrackDataSource(reader).readBatch(listOf(box(0.0, 1.0), box(0.5, 2.0)))
            published = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
    }

    private class Reader(private val points: List<ViewportTrackPoint>) : ViewportTrackPointReader {
        val requests = mutableListOf<ViewportBounds>()
        override suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> {
            requests += ViewportBounds(south, north, interval.west, interval.east)
            return points.filter { it.latitude in south..north && it.longitude in interval.west..interval.east }
        }
    }
    private fun box(west: Double, east: Double) = ViewportBounds(-1.0, 1.0, west, east)
    private fun point(id: Long, sequence: Long, longitude: Double) = ViewportTrackPoint(id, 1L, 1L, 0L, sequence, 0.0, longitude)
}
