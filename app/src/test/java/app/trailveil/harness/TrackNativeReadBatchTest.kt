package app.trailveil.harness

import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.ViewportTrackPointReader
import app.trailveil.map.fog.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TrackNativeReadBatchTest {
    private val style = FogRenderStyle()

    @Test fun coldRowsAreBoundedBatchesAndGeometryMatchesTheLegacyReads() = runBlocking {
        val target = rectangle(2048, 2056, 2046, 2048)
        val legacy = TrackRegionGeometryEngine()
        val batch = TrackRegionGeometryEngine()
        var singles = 0
        val groups = mutableListOf<List<ViewportBounds>>()
        val expected = checkNotNull(legacy.render(target, style) { tracks(it) })
        val actual = checkNotNull(batch.renderBatched(target, style,
            read = { singles++; tracks(it) },
            readBatch = { windows -> groups += windows; windows.map(::tracks) },
        ))
        assertEquals(expected.polygons, actual.polygons)
        assertEquals(4, groups.size)
        assertTrue(groups.all { it.size == 4 && it.map { window -> window.south to window.north }.distinct().size == 1 })
        assertEquals(legacy.cacheMisses, batch.cacheMisses)
        val warm = checkNotNull(batch.renderBatched(target, style,
            read = { error("warm single") }, readBatch = { error("warm batch") }))
        assertEquals(actual.polygons, warm.polygons)
        assertEquals("complete cold rows need no single reads", 0, singles)
    }

    @Test fun coordinatorMarginAndBatchModelsProduceTheSameGeometryAsIndependentReads() = runBlocking {
        val points = listOf(
            ViewportTrackPoint(1, 1, 1, 0, 0, 0.04, 0.02),
            ViewportTrackPoint(2, 1, 1, 0, 1, 0.04, 0.20),
            ViewportTrackPoint(3, 1, 1, 0, 2, 0.04, 0.08),
            ViewportTrackPoint(4, 1, 2, 1, 0, 0.04, -0.03),
            ViewportTrackPoint(5, 1, 2, 1, 1, 0.04, 0.01),
        )
        val legacyReads = mutableListOf<ViewportBounds>()
        val batchReads = mutableListOf<ViewportBounds>()
        fun source(log: MutableList<ViewportBounds>) = ViewportTrackDataSource(ViewportTrackPointReader { south, north, interval ->
            log += ViewportBounds(south, north, interval.west, interval.east)
            points.filter { it.latitude in south..north && it.longitude in interval.west..interval.east }
        })
        val target = rectangle(2048, 2052, 2047, 2048)
        val legacySource = source(legacyReads)
        val expected = checkNotNull(TrackRegionGeometryEngine().render(target, style) { partition ->
            legacySource.read(FogViewportTileGrid.expandBounds(partition, 6_100.0)).toFogTrackSegments()
        })
        val coordinator = FogViewportCoordinator(source(batchReads),
            FogTilePipeline(FogMemoryTileCache(100), null) { _, _ -> error("native-only read invoked raster") },
            nativeGeometryEngine = TrackRegionGeometryEngine())
        val actual = checkNotNull(coordinator.renderNativeGeometry(target))
        assertEquals(expected.polygons, actual.polygons)
        assertEquals(4, legacyReads.size)
        assertEquals(1, batchReads.size)
        assertEquals(legacyReads.first().south, batchReads.single().south, 0.0)
        assertEquals(legacyReads.first().north, batchReads.single().north, 0.0)
        assertEquals(legacyReads.minOf { it.west }, batchReads.single().west, 0.0)
        assertEquals(legacyReads.maxOf { it.east }, batchReads.single().east, 0.0)
    }

    @Test fun aWarmHoleIsNotPrefetchedAndLookingAheadDoesNotTouchTheLru() = runBlocking {
        suspend fun run(evictFutureWarmEntry: Boolean) {
            val legacy = TrackRegionGeometryEngine()
            val batch = TrackRegionGeometryEngine()
            val future = rectangle(2050, 2051, 2047, 2048)
            for (engine in listOf(legacy, batch)) {
                engine.render(future, style) { emptyList() }
                if (evictFutureWarmEntry) repeat(63) { index ->
                    engine.render(rectangle(2100 + index, 2101 + index, 2047, 2048), style) { emptyList() }
                }
            }
            val target = rectangle(2048, 2052, 2047, 2048)
            val queried = mutableListOf<ViewportBounds>()
            val beforeHits = batch.cacheHits
            val beforeMisses = batch.cacheMisses
            val expected = checkNotNull(legacy.render(target, style) { emptyList() })
            val actual = checkNotNull(batch.renderBatched(target, style,
                read = { queried += it; emptyList() },
                readBatch = { queried += it; it.map { emptyList<TrackSegment>() } }))
            assertEquals(expected.polygons, actual.polygons)
            assertEquals(legacy.cacheHits, batch.cacheHits)
            assertEquals(legacy.cacheMisses, batch.cacheMisses)
            assertEquals(if (evictFutureWarmEntry) 0L else 1L, batch.cacheHits - beforeHits)
            assertEquals(if (evictFutureWarmEntry) 4L else 3L, batch.cacheMisses - beforeMisses)
            assertEquals(if (evictFutureWarmEntry) 4 else 3, queried.size)
        }
        run(false)
        run(true)
    }

    @Test fun inputBudgetIsPerPartitionNotTheSumOfTheReadBatch() = runBlocking {
        // Outside these partitions, so no expensive buffer is needed. Each input is within the
        // original 100k budget, but four together exceed it; they must not become one input.
        val points = List(30_001) { GeoPoint(70.0, 100.0) }
        val input = listOf(TrackSegment(0, points))
        val target = rectangle(2048, 2052, 2047, 2048)
        val expected = checkNotNull(TrackRegionGeometryEngine().render(target, style) { input })
        var batchSize = 0
        val actual = checkNotNull(TrackRegionGeometryEngine().renderBatched(target, style,
            read = { input }, readBatch = { batchSize = it.size; it.map { input } }))
        assertEquals(4, batchSize)
        assertEquals(expected.polygons, actual.polygons)
    }

    @Test fun aFirstPartitionBudgetFailureHasTheSameOutcomeWithAtMostThreeUnusedReads() = runBlocking {
        val input = listOf(TrackSegment(0, List(100_001) { GeoPoint(0.0, 0.0) }))
        val target = rectangle(2048, 2056, 2047, 2048)
        val legacy = TrackRegionGeometryEngine(); val batch = TrackRegionGeometryEngine()
        var oldReads = 0; var newReads = 0
        assertNull(legacy.render(target, style) { oldReads++; input })
        assertNull(batch.renderBatched(target, style,
            read = { newReads++; input }, readBatch = { newReads += it.size; it.map { input } }))
        assertEquals("geometry-budget", legacy.lastFallback)
        assertEquals(legacy.lastFallback, batch.lastFallback)
        assertEquals(1, oldReads)
        assertEquals(4, newReads)
        assertEquals(legacy.cacheMisses, batch.cacheMisses)
    }

    @Test fun readFailureCancellationAndMalformedCountCannotPublishAPartialPartitionCache() = runBlocking {
        val target = rectangle(2048, 2052, 2047, 2048)
        for (failure in listOf("throw", "cancel", "count")) {
            val engine = TrackRegionGeometryEngine()
            val failureResult = runCatching {
                engine.renderBatched(target, style, read = { emptyList() }, readBatch = {
                    when (failure) {
                        "throw" -> error("injected read failure")
                        "cancel" -> throw CancellationException("injected cancellation")
                        else -> emptyList()
                    }
                })
            }.exceptionOrNull()
            assertNotNull(failureResult)
            var queried = 0
            assertNotNull(engine.renderBatched(target, style,
                read = { queried++; emptyList() }, readBatch = { queried += it.size; it.map { emptyList<TrackSegment>() } }))
            assertEquals(4, queried)
        }
    }

    private fun rectangle(x0: Int, x1: Int, y0: Int, y1: Int): FogTileBounds = FogTileBounds(
        (x0 + 1e-6) / 4096.0 * 360.0 - 180.0, WebMercator.latitudeAtNormalizedY((y1 - 1e-6) / 4096.0),
        (x1 - 1e-6) / 4096.0 * 360.0 - 180.0, WebMercator.latitudeAtNormalizedY((y0 + 1e-6) / 4096.0))
    private fun tracks(window: ViewportBounds): List<TrackSegment> = listOf(TrackSegment(0, listOf(
        GeoPoint((window.south + window.north) / 2, window.west + (window.east - window.west) * 0.3),
        GeoPoint((window.south + window.north) / 2, window.west + (window.east - window.west) * 0.7),
    )))
}
