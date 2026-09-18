package app.trailveil.harness

import app.trailveil.map.fog.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TrackPartitionRejectionTest {
    private val style = FogRenderStyle()
    private fun window(x: Int, y: Int) = FogTileBounds(
        x / 4096.0 * 360.0 - 180.0, WebMercator.latitudeAtNormalizedY((y + 1.0) / 4096),
        (x + 1.0) / 4096 * 360.0 - 180.0, WebMercator.latitudeAtNormalizedY(y / 4096.0))

    @Test fun outsideDenseSegmentsAreRejectedButCrossingAndTouchingTracksRemain() = runBlocking {
        val bounds = window(2048, 2047)
        val tracks = listOf(
            TrackSegment(0, List(1024) { GeoPoint(0.15, it / 100_000.0) }),
            TrackSegment(1, listOf(GeoPoint(0.04, -0.1), GeoPoint(0.04, 0.2))),
            TrackSegment(2, listOf(GeoPoint(0.06, -0.0001))),
        )
        val expected = checkNotNull(TrackRegionGeometryB42Reference().render(bounds, style) { tracks })
        val actual = checkNotNull(TrackRegionGeometryEngine().render(bounds, style) { tracks })
        assertEquals(expected.polygons, actual.polygons)
        assertTrue(actual.diagnostics.contains("rejectedSegments=1 rejectedPoints=1024"))
    }

    @Test fun seededOrdinaryPolarAndDatelineCasesMatchFrozenB42IncludingFallbacks() = runBlocking {
        val random = Random(20260912)
        for (bounds in listOf(window(2048, 2047), window(4095, 2047), window(0, 2047), window(2048, 480))) {
            repeat(16) {
                val midLat = (bounds.southLatitude + bounds.northLatitude) / 2
                val midLon = (bounds.westLongitude + bounds.eastLongitude) / 2
                val tracks = List(7) { segment ->
                    val latitude = midLat + random.nextDouble(-0.2, 0.2)
                    val longitude = midLon + random.nextDouble(-0.2, 0.2)
                    TrackSegment(segment, List(12) {
                        GeoPoint(latitude + random.nextDouble(-0.03, 0.03),
                            WebMercator.wrapLongitude(longitude + random.nextDouble(-0.03, 0.03)))
                    })
                }
                val reference = TrackRegionGeometryB42Reference()
                val candidate = TrackRegionGeometryEngine()
                val expected = reference.render(bounds, style) { tracks }
                val actual = candidate.render(bounds, style) { tracks }
                assertEquals(reference.lastFallback, candidate.lastFallback)
                assertEquals(expected?.polygons, actual?.polygons)
            }
        }
    }

    @Test fun inputAndConnectorBudgetsStillFailBeforeAnOutsideSegmentCanBeSkipped() = runBlocking {
        val bounds = window(2048, 2047)
        for (tracks in listOf(
            listOf(TrackSegment(0, List(100_001) { GeoPoint(0.2, 0.2) })),
            listOf(TrackSegment(0, listOf(GeoPoint(10.0, 0.0), GeoPoint(79.0, 0.0)))),
            listOf(TrackSegment(0, listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 180.0)))),
        )) {
            val reference = TrackRegionGeometryB42Reference()
            val candidate = TrackRegionGeometryEngine()
            val expected = reference.render(bounds, style) { tracks }
            val actual = candidate.render(bounds, style) { tracks }
            assertEquals(reference.lastFallback, candidate.lastFallback)
            assertEquals(expected?.polygons, actual?.polygons)
        }
    }

    @Test fun additionClearAndRadiusChangeStillRebuildTheRejectedPartition() = runBlocking {
        val bounds = window(2048, 2047)
        val candidate = TrackRegionGeometryEngine()
        val outside = listOf(TrackSegment(0, listOf(GeoPoint(0.2, 0.2))))
        val inside = outside + TrackSegment(1, listOf(GeoPoint(0.04, 0.04)))
        candidate.render(bounds, style) { outside }
        candidate.invalidate(listOf(FogRevealUpdate(GeoPoint(0.04, 0.04))), style)
        val actual = candidate.render(bounds, style) { inside }
        val expected = TrackRegionGeometryB42Reference().render(bounds, style) { inside }
        assertEquals(expected?.polygons, actual?.polygons)
        val large = style.copy(revealRadiusMeters = 30_000.0)
        assertEquals(TrackRegionGeometryB42Reference().render(bounds, large) { outside }?.polygons,
            candidate.render(bounds, large) { outside }?.polygons)
        candidate.clear()
        assertEquals(TrackRegionGeometryB42Reference().render(bounds, style) { outside }?.polygons,
            candidate.render(bounds, style) { outside }?.polygons)
    }

    @Test fun preCancelledRejectionCannotProceed() = runBlocking {
        val job = Job().apply { cancel() }
        try {
            withContext(job) { trackOutsidePartition(listOf(GeoPoint(0.2, 0.2)), 25.0, 1.001, 0.0, 0.0, 1.0, 1.0) }
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { /* expected */ }
    }

    @Test fun cancellationAtTheLastRejectedPointDoesNotPublishAnEmptyPartition() = runBlocking {
        val candidate = TrackRegionGeometryEngine()
        val bounds = window(2048, 2047)
        val job = Job()
        val cancellingPoints = object : AbstractList<GeoPoint>() {
            override val size: Int = 10
            override fun get(index: Int): GeoPoint {
                if (index == size - 1) job.cancel()
                return GeoPoint(0.2, 0.2)
            }
        }
        try {
            withContext(job) { candidate.render(bounds, style) { listOf(TrackSegment(0, cancellingPoints)) } }
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { /* expected */ }
        var freshReads = 0
        candidate.render(bounds, style) { freshReads++; emptyList() }
        assertEquals("cancelled rejected partition must not have become a warm empty cache entry", 1, freshReads)
        assertEquals(0L, candidate.cacheHits)
    }
}
