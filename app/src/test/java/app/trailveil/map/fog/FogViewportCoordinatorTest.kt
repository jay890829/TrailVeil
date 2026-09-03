package app.trailveil.map.fog

import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.ViewportTrackPointReader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FogViewportCoordinatorTest {
    @Test
    fun zoomZeroUsesOneUniqueWorldTile() {
        val keys = FogViewportTileGrid.around(
            center = GeoPoint(latitude = 0.0, longitude = 0.0),
            zoom = 0,
            renderVersion = 1,
        )

        assertEquals(listOf(FogTileKey(zoom = 0, x = 0, y = 0, renderVersion = 1)), keys)
        assertEquals(
            listOf(-180.0 to 180.0),
            FogViewportTileGrid.queryBounds(keys, marginMeters = 6_100.0)
                .longitudeIntervals()
                .map { interval -> interval.west to interval.east },
        )
    }

    @Test
    fun datelineGridProducesTwoNonWrappingStorageIntervals() {
        val keys = FogViewportTileGrid.around(
            center = GeoPoint(latitude = 0.0, longitude = 179.9),
            zoom = 4,
            renderVersion = 1,
        )

        assertEquals(listOf(14, 15, 0), keys.take(3).map(FogTileKey::x))
        val bounds = FogViewportTileGrid.queryBounds(keys, marginMeters = 6_100.0)
        assertTrue(bounds.west > bounds.east)
        assertEquals(2, bounds.longitudeIntervals().size)
    }

    /**
     * A camera just west of the antimeridian gets a tile window that wraps, and tile longitudes are
     * canonical, so the composed mosaic names ground a whole world to its east. The renderer draws
     * an image once, where it is told, so that mosaic lands where the camera cannot see it — 100% of
     * the viewport rendered as unfogged basemap at every zoom, measured on device before this.
     */
    @Test
    fun aWrappedMosaicIsNamedInTheCameraSOwnCopyOfTheWorld() {
        val westOfSeam = -179.5
        val wrapped = mosaic(west = 157.5, east = 225.0)

        val anchored = wrapped.anchoredNear(westOfSeam)

        assertEquals(-202.5, anchored.bounds.westLongitude, 1e-9)
        assertEquals(-135.0, anchored.bounds.eastLongitude, 1e-9)
        assertTrue(
            "The camera must fall inside the mosaic it is given",
            westOfSeam > anchored.bounds.westLongitude && westOfSeam < anchored.bounds.eastLongitude,
        )
        // The shift is whole worlds only, so it renames the same ground rather than moving it.
        assertEquals(
            wrapped.bounds.eastLongitude - wrapped.bounds.westLongitude,
            anchored.bounds.eastLongitude - anchored.bounds.westLongitude,
            1e-9,
        )
        assertEquals(wrapped.bounds.northLatitude, anchored.bounds.northLatitude, 1e-9)
        assertEquals(wrapped.bounds.southLatitude, anchored.bounds.southLatitude, 1e-9)
    }

    @Test
    fun aMosaicAlreadyAroundTheCameraIsLeftAlone() {
        listOf(
            0.0 to mosaic(west = -22.5, east = 45.0),
            // East of the seam the window already runs past 180, which is why that side never broke.
            179.5 to mosaic(west = 135.0, east = 202.5),
        ).forEach { (centerLongitude, untouched) ->
            assertEquals(untouched, untouched.anchoredNear(centerLongitude))
        }
    }

    private fun mosaic(west: Double, east: Double) = FogTileMosaic(
        mask = FogPixelMask(width = 2, height = 2, alpha = ByteArray(4)),
        bounds = FogTileBounds(
            westLongitude = west,
            southLatitude = -10.0,
            eastLongitude = east,
            northLatitude = 10.0,
        ),
        tileCount = 1,
    )

    @Test
    fun cacheLossRebuildsSameViewportFromCanonicalPoints() = runTest {
        var reads = 0
        val point = ViewportTrackPoint(
            pointId = 1,
            sessionId = 1,
            segmentId = 1,
            segmentSequence = 0,
            pointSequence = 0,
            latitude = 25.0330,
            longitude = 121.5654,
        )
        val coordinator = coordinator { _, _, _ ->
            reads += 1
            listOf(point)
        }
        val request = FogViewportRequest(
            center = GeoPoint(point.latitude, point.longitude),
            mapZoom = 14.2,
        )

        val first = coordinator.render(request)
        coordinator.clearDerivedCache()
        val rebuilt = coordinator.render(request)

        assertEquals(first.mosaic.mask, rebuilt.mosaic.mask)
        assertEquals(first.keys, rebuilt.keys)
        assertEquals(2, reads)
    }

    @Test
    fun persistedRevealUpdatesOnlyRendererAffectedCachedTiles() = runTest {
        val coordinator = coordinator { _, _, _ -> emptyList() }
        val current = GeoPoint(latitude = 25.0330, longitude = 121.5654)
        val request = FogViewportRequest(center = current, mapZoom = 14.0)
        val initial = coordinator.render(request)

        val merge = coordinator.mergePersistedReveals(
            listOf(
                FogRevealUpdate(
                    previousInSegment = GeoPoint(25.0329, 121.5653),
                    current = current,
                ),
            ),
        )

        assertTrue(merge.updatedKeys.isNotEmpty())
        assertTrue(merge.updatedKeys.all { key -> key in initial.keys })
        assertTrue(merge.updatedKeys.size < initial.keys.size)
        assertTrue(merge.missingKeys.none { key -> key in initial.keys })
    }

    @Test
    fun highSpeedRevealRenderingIsBoundedByTheActiveViewport() = runTest {
        val renderCount = AtomicInteger()
        val style = FogRenderStyle(tileSize = 8)
        val pipeline = FogTilePipeline(
            memoryCache = FogMemoryTileCache(maxBytes = 4L * 1024L * 1024L),
            diskCache = null,
            renderMask = { _, _ ->
                renderCount.incrementAndGet()
                FogPixelMask(width = 8, height = 8, alpha = ByteArray(64) { 184.toByte() })
            },
        )
        val coordinator = FogViewportCoordinator(
            trackDataSource = emptyDataSource(),
            pipeline = pipeline,
            style = style,
        )
        val origin = GeoPoint(latitude = 25.0, longitude = 121.0)
        val initial = coordinator.render(FogViewportRequest(origin, mapZoom = 14.0))
        renderCount.set(0)

        coordinator.mergePersistedReveals(
            listOf(
                FogRevealUpdate(
                    previousInSegment = origin,
                    current = GeoPoint(latitude = 25.0, longitude = 121.004),
                ),
            ),
        )

        assertTrue(renderCount.get() > 0)
        assertTrue(renderCount.get() <= initial.keys.size)
    }

    @Test
    fun clampedPolarRevealDoesNotExpandTheProjectedRegionIntoTileObjects() = runTest {
        val renderCount = AtomicInteger()
        val style = FogRenderStyle(tileSize = 8)
        val pipeline = FogTilePipeline(
            memoryCache = FogMemoryTileCache(maxBytes = 4L * 1024L * 1024L),
            diskCache = null,
            renderMask = { _, _ ->
                renderCount.incrementAndGet()
                FogPixelMask(width = 8, height = 8, alpha = ByteArray(64) { 184.toByte() })
            },
        )
        val coordinator = FogViewportCoordinator(emptyDataSource(), pipeline, style)
        val previous = GeoPoint(latitude = 89.999, longitude = 0.0)
        val current = GeoPoint(latitude = 89.999, longitude = 179.0)
        val initial = coordinator.render(FogViewportRequest(current, mapZoom = 22.0))
        renderCount.set(0)

        val merge = coordinator.mergePersistedReveals(
            listOf(FogRevealUpdate(current, previousInSegment = previous)),
        )

        assertTrue(merge.updatedKeys.isNotEmpty())
        assertTrue(renderCount.get() <= initial.keys.size)
    }

    @Test
    fun affectedOffscreenCacheIsInvalidatedAndRebuiltFromCanonicalStorage() = runTest {
        var canonicalPoints: List<ViewportTrackPoint> = emptyList()
        val style = FogRenderStyle()
        val pipeline = FogTilePipeline(
            memoryCache = FogMemoryTileCache(maxBytes = 4L * 1024L * 1024L),
            diskCache = null,
            renderMask = FogTileRenderer(style)::render,
        )
        val coordinator = FogViewportCoordinator(
            trackDataSource = ViewportTrackDataSource(
                ViewportTrackPointReader { _, _, _ -> canonicalPoints },
            ),
            pipeline = pipeline,
            style = style,
        )
        val origin = GeoPoint(latitude = 25.0, longitude = 121.0)
        val originRender = coordinator.render(FogViewportRequest(origin, mapZoom = 14.0))
        val originKey = originRender.keys.single { key ->
            val coordinate = WebMercator.tile(origin, key.zoom)
            key.x == coordinate.x && key.y == coordinate.y
        }
        coordinator.render(
            FogViewportRequest(
                center = GeoPoint(latitude = 25.0, longitude = 122.0),
                mapZoom = 14.0,
            ),
        )
        val persisted = ViewportTrackPoint(
            pointId = 1,
            sessionId = 1,
            segmentId = 1,
            segmentSequence = 0,
            pointSequence = 0,
            latitude = origin.latitude,
            longitude = origin.longitude,
        )
        canonicalPoints = listOf(persisted)

        val merge = coordinator.mergePersistedReveals(listOf(FogRevealUpdate(origin)))

        assertTrue(originKey in merge.missingKeys)
        assertEquals(null, pipeline.loadCached(originKey))
        val rebuilt = coordinator.render(FogViewportRequest(origin, mapZoom = 14.0))
        assertTrue(rebuilt.mosaic.mask.copyAlpha().any { alpha -> (alpha.toInt() and 0xff) == 0 })
    }

    /**
     * The provider batch seam renders one window per LOD for the same camera. All of them are on
     * screen, so a reveal must be merged into every one in place; treating only the last-rendered
     * window as active would invalidate the others and pay a canonical read per generation.
     */
    @Test
    fun aProviderBatchOfSeveralZoomWindowsStaysOneActiveViewportForRevealMerges() = runTest {
        val style = FogRenderStyle()
        val pipeline = FogTilePipeline(
            memoryCache = FogMemoryTileCache(maxBytes = 4L * 1024L * 1024L),
            diskCache = null,
            renderMask = FogTileRenderer(style)::render,
        )
        val coordinator = FogViewportCoordinator(
            trackDataSource = emptyDataSource(),
            pipeline = pipeline,
            style = style,
        )
        val center = GeoPoint(latitude = 25.0, longitude = 121.0)
        val far = FogViewportTileGrid.around(center, zoom = 13, renderVersion = FogRenderVersions.CURRENT)
        val near = FogViewportTileGrid.around(center, zoom = 14, renderVersion = FogRenderVersions.CURRENT)
        coordinator.renderTiles(FogViewportRequest(center, mapZoom = 13.0), far)
        coordinator.renderTiles(FogViewportRequest(center, mapZoom = 14.0), near)
        val centerKeys = listOf(13, 14).map { zoom ->
            val tile = WebMercator.tile(center, zoom)
            FogTileKey(zoom, tile.x, tile.y, FogRenderVersions.CURRENT)
        }

        val merge = coordinator.mergePersistedReveals(listOf(FogRevealUpdate(center)))

        assertTrue(
            "a window of the same camera must not be read as off-screen: $merge",
            merge.missingKeys.none { key -> key in far || key in near },
        )
        assertTrue("the near window's centre tile must be merged in place", centerKeys[1] in merge.updatedKeys)
        assertTrue(
            "every LOD window of the camera stays cached after the merge",
            centerKeys.all { key -> pipeline.loadCached(key) != null },
        )

        // A window at a different centre is a different camera and replaces the set.
        val elsewhere = GeoPoint(latitude = 25.0, longitude = 122.0)
        coordinator.render(FogViewportRequest(elsewhere, mapZoom = 14.0))
        val later = coordinator.mergePersistedReveals(listOf(FogRevealUpdate(center)))
        assertTrue("the previous camera's tiles are off-screen now", centerKeys[1] in later.missingKeys)
    }

    @Test
    fun failedRenderDoesNotReplaceTheActiveViewportForLaterRevealMerges() = runTest {
        var failReads = false
        val style = FogRenderStyle()
        val pipeline = FogTilePipeline(
            memoryCache = FogMemoryTileCache(maxBytes = 4L * 1024L * 1024L),
            diskCache = null,
            renderMask = FogTileRenderer(style)::render,
        )
        val coordinator = FogViewportCoordinator(
            trackDataSource = ViewportTrackDataSource(
                ViewportTrackPointReader { _, _, _ ->
                    check(!failReads) { "forced canonical read failure" }
                    emptyList()
                },
            ),
            pipeline = pipeline,
            style = style,
        )
        val origin = GeoPoint(latitude = 25.0, longitude = 121.0)
        val initial = coordinator.render(FogViewportRequest(origin, mapZoom = 14.0))
        failReads = true

        val failure = runCatching {
            coordinator.render(
                FogViewportRequest(
                    center = GeoPoint(latitude = 25.0, longitude = 122.0),
                    mapZoom = 14.0,
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)

        val merge = coordinator.mergePersistedReveals(listOf(FogRevealUpdate(origin)))
        assertTrue(merge.updatedKeys.isNotEmpty())
        assertTrue(merge.updatedKeys.all { key -> key in initial.keys })
    }

    /**
     * `P4-037`: the coordinator asks for cells at world zoom and for points everywhere else.
     *
     * This exists because of a gap the A/B round found rather than by plan. The read-cost gate calls
     * the DAO query directly, and [TrackPointCells.coarseReadIsSubPixel] is covered on its own, so
     * deleting the `coarse =` argument from the coordinator's `read` call left NOTHING red: the
     * parameter defaults to false, the point route runs, the fog is identical, and the entire saving
     * quietly disappears. A failure whose only symptom is being slow again is exactly the kind that
     * survives a green suite.
     *
     * Both directions are asserted. Only the world case would catch that deletion, but only the
     * exploration case catches the opposite mistake — a widened ceiling substituting cell centres at
     * a zoom where the displacement can actually be drawn, which is a wrong map rather than a slow
     * one.
     */
    @Test
    fun theWorldZoomReadTakesTheCellRouteAndTheExplorationZoomReadDoesNot() = runTest {
        val reader = RecordingReader()
        val coordinator = coordinatorReading(reader)
        val centre = GeoPoint(latitude = 25.0330, longitude = 121.5654)

        coordinator.render(FogViewportRequest(center = centre, mapZoom = WORLD_MAP_ZOOM))
        assertEquals("the world-zoom settle did not ask for cells", 1, reader.coarseReads)
        assertEquals("the world-zoom settle read points as well", 0, reader.pointReads)

        coordinator.render(FogViewportRequest(center = centre, mapZoom = EXPLORATION_MAP_ZOOM))
        assertEquals("the exploration-zoom settle asked for cells", 1, reader.coarseReads)
        assertTrue("the exploration-zoom settle read no points at all", reader.pointReads > 0)
    }

    /**
     * Counts which route was taken, and answers both.
     *
     * `readCoarseCells` returns a non-empty list rather than null on purpose: null means "ask me the
     * other way", so a null-returning reader would fall back to points and the two counters could
     * not tell a coarse request from a point one.
     */
    private class RecordingReader : ViewportTrackPointReader {
        var pointReads = 0
        var coarseReads = 0

        override suspend fun read(
            south: Double,
            north: Double,
            interval: app.trailveil.data.map.LongitudeInterval,
        ): List<ViewportTrackPoint> {
            pointReads += 1
            return emptyList()
        }

        override suspend fun readCoarseCells(
            south: Double,
            north: Double,
            interval: app.trailveil.data.map.LongitudeInterval,
        ): List<GeoPoint> {
            coarseReads += 1
            return listOf(GeoPoint(latitude = 25.0330, longitude = 121.5654))
        }
    }

    private fun coordinatorReading(reader: ViewportTrackPointReader): FogViewportCoordinator {
        val style = FogRenderStyle()
        return FogViewportCoordinator(
            trackDataSource = ViewportTrackDataSource(reader),
            pipeline = FogTilePipeline(
                memoryCache = FogMemoryTileCache(maxBytes = 4L * 1024L * 1024L),
                diskCache = null,
                renderMask = FogTileRenderer(style)::render,
            ),
            style = style,
        )
    }

    private fun coordinator(
        read: suspend (Double, Double, app.trailveil.data.map.LongitudeInterval) ->
            List<ViewportTrackPoint>,
    ): FogViewportCoordinator {
        val dataSource = ViewportTrackDataSource(
            ViewportTrackPointReader { south, north, interval ->
                read(south, north, interval)
            },
        )
        val style = FogRenderStyle()
        val pipeline = FogTilePipeline(
            memoryCache = FogMemoryTileCache(maxBytes = 4L * 1024L * 1024L),
            diskCache = null,
            renderMask = FogTileRenderer(style)::render,
        )
        return FogViewportCoordinator(
            trackDataSource = dataSource,
            pipeline = pipeline,
            style = style,
        )
    }

    private fun emptyDataSource() = ViewportTrackDataSource(
        ViewportTrackPointReader { _, _, _ -> emptyList() },
    )

    private companion object {
        /** `renderZoom` floors, so anything under 1.0 is render zoom 0 -- the whole planet. */
        const val WORLD_MAP_ZOOM = 0.5

        /** The zoom a user actually explores at, where a cell centre could be drawn. */
        const val EXPLORATION_MAP_ZOOM = 14.2
    }
}
