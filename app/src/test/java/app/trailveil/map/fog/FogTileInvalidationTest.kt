package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FogTileInvalidationTest {
    private val style = FogRenderStyle(tileSize = 64, revealRadiusMeters = 25.0)

    @Test
    fun centeredNewPointChangesOnlyItsTileAtEachConfiguredZoom() {
        val point = WebMercator.pointAtWorldPixel(
            pixel = WorldPixel(x = 1_000.5 * style.tileSize, y = 12_000.5 * style.tileSize),
            zoom = 16,
            tileSize = style.tileSize,
        )
        val invalidator = FogTileInvalidator(15..16, style)

        val affected = invalidator.affectedKeys(FogRevealUpdate(point), renderVersion = 7)

        assertEquals(
            setOf(
                WebMercator.tile(point, 15).toKey(zoom = 15, renderVersion = 7),
                WebMercator.tile(point, 16).toKey(zoom = 16, renderVersion = 7),
            ),
            affected,
        )
    }

    @Test
    fun boundaryPointChangesEveryTileContainingNewRevealPixels() {
        val zoom = 15
        val boundary = WebMercator.pointAtWorldPixel(
            pixel = WorldPixel(x = 2.0 * style.tileSize, y = 3.0 * style.tileSize),
            zoom = zoom,
            tileSize = style.tileSize,
        )

        val affected = FogTileInvalidator(zoom..zoom, style)
            .affectedKeys(FogRevealUpdate(boundary), renderVersion = 0)

        assertEquals(setOf(1, 2), affected.map(FogTileKey::x).toSet())
        assertEquals(setOf(2, 3), affected.map(FogTileKey::y).toSet())
        assertEquals(4, affected.size)
    }

    @Test
    fun subPixelCornerRevealDoesNotInvalidateUnchangedMasks() {
        val zoom = 14
        val boundary = WebMercator.pointAtWorldPixel(
            pixel = WorldPixel(x = 2_000.0 * style.tileSize, y = 8_192.0 * style.tileSize),
            zoom = zoom,
            tileSize = style.tileSize,
        )

        val affected = FogTileInvalidator(zoom..zoom, style)
            .affectedKeys(FogRevealUpdate(boundary), renderVersion = 0)

        assertTrue(affected.isEmpty())
    }

    @Test
    fun identicalContinuousPointDoesNotInvalidateUnchangedPixels() {
        val point = GeoPoint(latitude = 25.0, longitude = 121.0)
        val affected = FogTileInvalidator(4..6, style).affectedKeys(
            update = FogRevealUpdate(current = point, previousInSegment = point),
            renderVersion = 2,
        )

        assertTrue(affected.isEmpty())
    }

    @Test
    fun datelineCapsuleUsesAdjacentWrappedEdgeTiles() {
        val zoom = 15
        val affected = FogTileInvalidator(zoom..zoom, style).affectedKeys(
            update = FogRevealUpdate(
                previousInSegment = GeoPoint(latitude = 12.345, longitude = 179.999),
                current = GeoPoint(latitude = 12.345, longitude = -179.999),
            ),
            renderVersion = 3,
        )

        assertTrue(affected.any { key -> key.x == 0 })
        assertTrue(affected.any { key -> key.x == (1 shl zoom) - 1 })
        assertFalse(affected.any { key -> key.x in 2 until (1 shl zoom) - 2 })
        assertTrue(affected.all { key -> key.renderVersion == 3 })
    }

    @Test
    fun ambiguousHalfWorldUpdateInvalidatesOnlyNewEndpointPixels() {
        val zoom = 15
        val previous = GeoPoint(latitude = 12.345, longitude = -89.0)
        val current = GeoPoint(latitude = 12.345, longitude = 91.0)

        val affected = FogTileInvalidator(zoom..zoom, style).affectedKeys(
            update = FogRevealUpdate(current, previousInSegment = previous),
            renderVersion = 0,
        )

        assertEquals(setOf(WebMercator.tile(current, zoom).x), affected.map(FogTileKey::x).toSet())
    }

    @Test
    fun invalidZoomAndRenderVersionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FogTileInvalidator(IntRange.EMPTY, style)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FogTileInvalidator(23..23, style)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FogTileInvalidator(Int.MIN_VALUE..Int.MAX_VALUE, style)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FogTileInvalidator(1..1, style).affectedKeys(
                FogRevealUpdate(GeoPoint(0.0, 0.0)),
                renderVersion = -1,
            )
        }
    }

    private fun TileCoordinate.toKey(zoom: Int, renderVersion: Int) =
        FogTileKey(zoom = zoom, x = x, y = y, renderVersion = renderVersion)
}
