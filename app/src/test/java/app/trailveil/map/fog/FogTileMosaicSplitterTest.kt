package app.trailveil.map.fog

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FogTileMosaicSplitterTest {
    @Test
    fun splitPreservesRowMajorOrderDimensionsAndDatelineColumns() {
        val keys = listOf(
            FogTileKey(zoom = 2, x = 3, y = 1, renderVersion = FogRenderVersions.CURRENT),
            FogTileKey(zoom = 2, x = 0, y = 1, renderVersion = FogRenderVersions.CURRENT),
            FogTileKey(zoom = 2, x = 3, y = 2, renderVersion = FogRenderVersions.CURRENT),
            FogTileKey(zoom = 2, x = 0, y = 2, renderVersion = FogRenderVersions.CURRENT),
        )
        val alpha = ByteArray(512 * 512) { FogRenderStyle().fogAlpha.toByte() }
        alpha[0] = 0
        alpha[256] = 10
        alpha[512 * 256] = 20
        alpha[512 * 256 + 256] = 30
        val render = FogViewportRender(
            request = FogViewportRequest(GeoPoint(0.0, 180.0), mapZoom = 2.0),
            keys = keys,
            queryBounds = null,
            mosaic = FogTileMosaic(
                mask = FogPixelMask(512, 512, alpha),
                bounds = FogTileBounds(-180.0, -66.5, 180.0, 66.5),
                tileCount = keys.size,
            ),
        )

        val split = FogTileMosaicSplitter.split(render)

        assertEquals(keys.toSet(), split.keys)
        assertEquals(256, split.getValue(keys[0]).width)
        assertEquals(256, split.getValue(keys[0]).height)
        assertEquals(0, split.getValue(keys[0]).alphaAt(0, 0))
        assertEquals(10, split.getValue(keys[1]).alphaAt(0, 0))
        assertEquals(20, split.getValue(keys[2]).alphaAt(0, 0))
        assertEquals(30, split.getValue(keys[3]).alphaAt(0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitRejectsNonDivisibleMosaicDimensions() {
        val key = FogTileKey(zoom = 2, x = 1, y = 1, renderVersion = FogRenderVersions.CURRENT)
        FogTileMosaicSplitter.split(
            FogViewportRender(
                request = FogViewportRequest(GeoPoint(0.0, 0.0), 2.0),
                keys = listOf(key),
                queryBounds = null,
                mosaic = FogTileMosaic(
                    mask = FogPixelMask(255, 256, ByteArray(255 * 256)),
                    bounds = FogTileBounds(-90.0, -45.0, 0.0, 45.0),
                    tileCount = 1,
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitRejectsASecondRowWithShiftedHorizontalSequence() {
        val keys = listOf(
            FogTileKey(zoom = 2, x = 3, y = 1, renderVersion = FogRenderVersions.CURRENT),
            FogTileKey(zoom = 2, x = 0, y = 1, renderVersion = FogRenderVersions.CURRENT),
            // The second row is individually contiguous across the dateline, but shifted.
            FogTileKey(zoom = 2, x = 0, y = 2, renderVersion = FogRenderVersions.CURRENT),
            FogTileKey(zoom = 2, x = 1, y = 2, renderVersion = FogRenderVersions.CURRENT),
        )
        FogTileMosaicSplitter.split(
            FogViewportRender(
                request = FogViewportRequest(GeoPoint(0.0, 180.0), 2.0),
                keys = keys,
                queryBounds = null,
                mosaic = FogTileMosaic(
                    mask = FogPixelMask(512, 512, ByteArray(512 * 512)),
                    bounds = FogTileBounds(-180.0, -66.5, 180.0, 66.5),
                    tileCount = keys.size,
                ),
            ),
        )
    }

    @Test
    fun separateCanonicalSegmentsDoNotBecomeAConnectedClearCapsuleThroughAdapter() {
        val zoom = 12
        val first = GeoPoint(latitude = 0.0, longitude = 0.0)
        val second = GeoPoint(latitude = 0.0, longitude = 0.04)
        val key = FogTileKey(
            zoom = zoom,
            x = WebMercator.tile(first, zoom).x,
            y = WebMercator.tile(first, zoom).y,
            renderVersion = FogRenderVersions.CURRENT,
        )
        val style = FogRenderStyle()
        val renderer = FogTileRenderer(style)
        val separateSegments = listOf(
            TrackSegment(id = 1, points = listOf(first)),
            TrackSegment(id = 2, points = listOf(second)),
        )
        val joinedSegment = listOf(TrackSegment(id = 1, points = listOf(first, second)))
        val separateMask = renderer.render(key, separateSegments)
        val joinedMask = renderer.render(key, joinedSegment)
        val firstPixel = WebMercator.worldPixel(first, zoom, FogTilePngCodec.TILE_SIZE)
        val secondPixel = WebMercator.worldPixel(second, zoom, FogTilePngCodec.TILE_SIZE)
        val midpointX = (((firstPixel.x + secondPixel.x) / 2.0) - key.x * 256.0).toInt()
        val midpointY = (firstPixel.y - key.y * 256.0).toInt()

        assertTrue(separateMask.alphaAt(midpointX, midpointY) > 0)
        assertEquals(0, joinedMask.alphaAt(midpointX, midpointY))

        val adapter = FogTileProviderAdapter(CanonicalFogTileSource { separateMask })
        val generation = adapter.beginGeneration()
        assertTrue(adapter.publishMasks(generation, mapOf(key to separateMask)))
        val image = requireNotNull(
            ImageIO.read(ByteArrayInputStream(adapter.tileBytes(key.x, key.y, key.zoom))),
        )
        assertEquals(255, image.getRGB(midpointX, midpointY).ushr(24) and 0xff)
    }
}
