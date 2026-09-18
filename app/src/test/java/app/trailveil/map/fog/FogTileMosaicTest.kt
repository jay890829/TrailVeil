package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FogTileMosaicTest {
    @Test fun tileLayoutRetainsInvalidBatchRejection() {
        val a = tile(1, 1, 1)
        val cases = listOf(
            emptyList<FogMosaicTile>() to "tiles must not be empty",
            listOf(a, a.copy(mask = FogPixelMask(1, 2, ByteArray(2)))) to "all tile masks must have the same dimensions",
            listOf(a.copy(mask = FogPixelMask(0, 2, ByteArray(0)))) to "tile masks must not be empty",
            listOf(a, a.copy(key = a.key.copy(zoom = 3))) to "all tiles must use the same zoom",
            listOf(a, a.copy(key = a.key.copy(renderVersion = 1))) to "all tiles must use the same render version",
            listOf(a, tile(2, 1, 1), tile(1, 2, 1)) to "tiles must form a complete row-major rectangle",
            listOf(a, tile(1, 3, 1)) to "tile rows must be vertically consecutive",
            listOf(a, tile(3, 1, 1)) to "tile columns must be horizontally consecutive with dateline wrapping",
            listOf(a, tile(2, 2, 1)) to "every tile row must use the same horizontal order",
        )
        for ((tiles, message) in cases) {
            assertEquals(message, assertThrows(IllegalArgumentException::class.java) { FogPocMosaic.layout(tiles) }.message)
            assertEquals(message, assertThrows(IllegalArgumentException::class.java) { FogPocMosaic.compose(tiles) }.message)
        }
        // A zero-cost dimension-only overflow check: no giant mask allocation is needed.
        assertThrows(ArithmeticException::class.java) { FogPocMosaic.layout(listOf(a.key), Int.MAX_VALUE, 2) }
        val short = listOf(a.copy(mask = FogPixelMask(2, 2, ByteArray(3))))
        assertThrows(IndexOutOfBoundsException::class.java) { FogPocMosaic.layout(short) }
        assertThrows(IndexOutOfBoundsException::class.java) { FogPocMosaic.compose(short) }
        val long = listOf(a.copy(mask = FogPixelMask(2, 2, ByteArray(5))))
        assertEquals(FogPocMosaic.compose(long).bounds, FogPocMosaic.layout(long).bounds)
    }

    @Test
    fun composeCopiesRowMajorPixelsWithoutInternalGapsOrOverlap() {
        val tiles = listOf(
            tile(x = 1, y = 1, alpha = 1),
            tile(x = 2, y = 1, alpha = 2),
            tile(x = 1, y = 2, alpha = 3),
            tile(x = 2, y = 2, alpha = 4),
        )

        val mosaic = FogPocMosaic.compose(tiles)

        assertEquals(4, mosaic.mask.width)
        assertEquals(4, mosaic.mask.height)
        assertEquals(4, mosaic.tileCount)
        assertEquals(
            listOf(
                1, 1, 2, 2,
                1, 1, 2, 2,
                3, 3, 4, 4,
                3, 3, 4, 4,
            ),
            mosaic.mask.copyAlpha().map { it.toInt() and 0xff },
        )
    }

    @Test
    fun ordinaryMosaicBoundsMatchOuterTileEdges() {
        val center = GeoPoint(latitude = 20.0, longitude = 0.0)
        val keys = FogPocTileGrid.around(center, zoom = 3, renderVersion = 0)
        val tiles = keys.map { key -> FogMosaicTile(key, solidMask(alpha = key.x + key.y)) }

        val mosaic = FogPocMosaic.compose(tiles)
        val firstBounds = FogPocTileGrid.bounds(keys.first())
        val lastBounds = FogPocTileGrid.bounds(keys.last())

        assertEquals(firstBounds.westLongitude, mosaic.bounds.westLongitude, 0.0)
        assertEquals(firstBounds.northLatitude, mosaic.bounds.northLatitude, 0.0)
        assertEquals(lastBounds.eastLongitude, mosaic.bounds.eastLongitude, 0.0)
        assertEquals(lastBounds.southLatitude, mosaic.bounds.southLatitude, 0.0)
    }

    @Test
    fun datelineMosaicUsesContinuousUnwrappedLongitudeBounds() {
        val center = GeoPoint(latitude = 20.0, longitude = 179.0)
        val keys = FogPocTileGrid.around(center, zoom = 2, renderVersion = 0)
        assertEquals(listOf(2, 3, 0), keys.take(3).map(FogTileKey::x))

        val mosaic = FogPocMosaic.compose(
            keys.map { key -> FogMosaicTile(key, solidMask(alpha = key.x)) },
        )

        assertEquals(0.0, mosaic.bounds.westLongitude, 0.0)
        assertEquals(270.0, mosaic.bounds.eastLongitude, 0.0)
    }

    private fun tile(x: Int, y: Int, alpha: Int): FogMosaicTile =
        FogMosaicTile(
            key = FogTileKey(zoom = 2, x = x, y = y, renderVersion = 0),
            mask = solidMask(alpha),
        )

    private fun solidMask(alpha: Int): FogPixelMask =
        FogPixelMask(
            width = 2,
            height = 2,
            alpha = ByteArray(4) { alpha.toByte() },
        )
}
