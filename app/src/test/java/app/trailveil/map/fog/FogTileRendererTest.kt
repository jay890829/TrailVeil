package app.trailveil.map.fog

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FogTileRendererTest {
    private val style = FogRenderStyle(tileSize = 64, fogAlpha = 180, revealRadiusMeters = 25.0)
    private val renderer = FogTileRenderer(style)

    @Test
    fun separateSegmentsNeverRevealTheGapBetweenThem() {
        val key = FogTileKey(zoom = 16, x = 32_768, y = 32_768, renderVersion = 1)
        val left = pointAtTilePixel(key, 16.0, 32.0)
        val right = pointAtTilePixel(key, 48.0, 32.0)

        val separated = renderer.render(
            key,
            listOf(TrackSegment(1, listOf(left)), TrackSegment(2, listOf(right))),
        )
        val connected = renderer.render(key, listOf(TrackSegment(1, listOf(left, right))))

        assertEquals(style.fogAlpha, separated.alphaAt(32, 32))
        assertEquals(0, connected.alphaAt(32, 32))
    }

    @Test
    fun circlesAndRoundedLinesRevealExpectedPixels() {
        val key = FogTileKey(zoom = 16, x = 32_768, y = 32_768, renderVersion = 1)
        val start = pointAtTilePixel(key, 20.0, 30.0)
        val end = pointAtTilePixel(key, 44.0, 30.0)
        val mask = renderer.render(key, listOf(TrackSegment(7, listOf(start, end))))

        assertEquals(0, mask.alphaAt(20, 30))
        assertEquals(0, mask.alphaAt(32, 30))
        assertEquals(0, mask.alphaAt(44, 30))
        assertEquals(style.fogAlpha, mask.alphaAt(32, 50))
    }

    @Test
    fun circleCrossingTileBoundaryAppearsOnBothTiles() {
        val westKey = FogTileKey(zoom = 16, x = 32_767, y = 32_768, renderVersion = 1)
        val eastKey = FogTileKey(zoom = 16, x = 32_768, y = 32_768, renderVersion = 1)
        val boundaryPoint = GeoPoint(0.0, 0.0)
        val segment = listOf(TrackSegment(1, listOf(boundaryPoint)))

        assertEquals(0, renderer.render(westKey, segment).alphaAt(63, 0))
        assertEquals(0, renderer.render(eastKey, segment).alphaAt(0, 0))
    }

    @Test
    fun datelineSegmentUsesShortestWrappedPath() {
        val westEdge = FogTileKey(zoom = 2, x = 0, y = 2, renderVersion = 1)
        val eastEdge = FogTileKey(zoom = 2, x = 3, y = 2, renderVersion = 1)
        val middle = FogTileKey(zoom = 2, x = 1, y = 2, renderVersion = 1)
        val segment = listOf(
            TrackSegment(
                1,
                listOf(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5)),
            ),
        )

        val datelineRenderer = FogTileRenderer(
            style.copy(revealRadiusMeters = 100_000.0),
        )
        assertEquals(0, datelineRenderer.render(westEdge, segment).alphaAt(0, 0))
        assertEquals(0, datelineRenderer.render(eastEdge, segment).alphaAt(63, 0))
        assertEquals(style.fogAlpha, datelineRenderer.render(middle, segment).alphaAt(32, 0))
    }

    @Test
    fun exactHalfWorldTransitionRevealsEndpointsWithoutChoosingAHemisphere() {
        val key = FogTileKey(zoom = 2, x = 2, y = 2, renderVersion = 1)
        val halfWorldRenderer = FogTileRenderer(
            style.copy(revealRadiusMeters = 100_000.0),
        )
        val west = GeoPoint(0.0, -90.0)
        val east = GeoPoint(0.0, 90.0)
        val forward = halfWorldRenderer.render(
            key,
            listOf(TrackSegment(1, listOf(west, east))),
        )
        val reverse = halfWorldRenderer.render(
            key,
            listOf(TrackSegment(1, listOf(east, west))),
        )

        assertEquals(forward, reverse)
        assertEquals(style.fogAlpha, forward.alphaAt(32, 0))
    }

    @Test
    fun zeroLengthLineIsTheSameAsOnePointCircle() {
        val key = FogTileKey(zoom = 16, x = 32_768, y = 32_768, renderVersion = 1)
        val point = pointAtTilePixel(key, 32.0, 32.0)

        assertEquals(
            renderer.render(key, listOf(TrackSegment(1, listOf(point)))),
            renderer.render(key, listOf(TrackSegment(1, listOf(point, point)))),
        )
    }

    @Test
    fun lineWithBothEndpointsOutsideStillRevealsTileInterior() {
        val key = FogTileKey(zoom = 16, x = 32_768, y = 32_768, renderVersion = 1)
        val segment = TrackSegment(
            1,
            listOf(pointAtTilePixel(key, -12.0, 32.0), pointAtTilePixel(key, 76.0, 32.0)),
        )

        assertEquals(0, renderer.render(key, listOf(segment)).alphaAt(32, 32))
    }

    @Test
    fun segmentEnumerationOrderDoesNotChangeTheUnionMask() {
        val key = FogTileKey(zoom = 16, x = 32_768, y = 32_768, renderVersion = 1)
        val left = TrackSegment(1, listOf(pointAtTilePixel(key, 16.0, 16.0)))
        val right = TrackSegment(2, listOf(pointAtTilePixel(key, 48.0, 48.0)))

        assertEquals(renderer.render(key, listOf(left, right)), renderer.render(key, listOf(right, left)))
    }

    @Test
    fun representativeMaskIsDeterministicAndMatchesGoldenDigest() {
        val key = FogTileKey(zoom = 16, x = 54_897, y = 28_071, renderVersion = 3)
        val segment = TrackSegment(
            4,
            listOf(
                pointAtTilePixel(key, 8.0, 12.0),
                pointAtTilePixel(key, 31.0, 39.0),
                pointAtTilePixel(key, 56.0, 20.0),
            ),
        )

        val first = renderer.render(key, listOf(segment)).copyAlpha()
        val second = renderer.render(key, listOf(segment)).copyAlpha()

        assertArrayEquals(first, second)
        assertNotEquals(0L, fnv1a64(first))
        assertEquals(-5279054705270811299L, fnv1a64(first))
    }

    private fun pointAtTilePixel(key: FogTileKey, x: Double, y: Double): GeoPoint =
        WebMercator.pointAtWorldPixel(
            WorldPixel(
                x = key.x * style.tileSize.toDouble() + x,
                y = key.y * style.tileSize.toDouble() + y,
            ),
            zoom = key.zoom,
            tileSize = style.tileSize,
        )

    private fun fnv1a64(bytes: ByteArray): Long {
        var hash = -3750763034362895579L
        bytes.forEach { byte ->
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 1099511628211L
        }
        return hash
    }
}
