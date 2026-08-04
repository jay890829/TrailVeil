package app.trailveil.map.fog

import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.ViewportTrackPointReader
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
}
