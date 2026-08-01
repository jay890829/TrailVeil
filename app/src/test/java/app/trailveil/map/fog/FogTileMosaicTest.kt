package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Test

class FogTileMosaicTest {
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
