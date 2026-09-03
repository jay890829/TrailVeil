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
    fun conservativeCandidatesContainAnIndependentRendererOracle() {
        val oracleStyle = FogRenderStyle(tileSize = 16, revealRadiusMeters = 100_000.0)
        val zoomLevels = 0..5
        val boundary = WebMercator.pointAtWorldPixel(
            pixel = WorldPixel(x = 8.0 * oracleStyle.tileSize, y = 10.0 * oracleStyle.tileSize),
            zoom = zoomLevels.last,
            tileSize = oracleStyle.tileSize,
        )
        val updates = listOf(
            FogRevealUpdate(current = boundary),
            FogRevealUpdate(
                previousInSegment = GeoPoint(latitude = -35.0, longitude = -70.0),
                current = GeoPoint(latitude = 55.0, longitude = 40.0),
            ),
            FogRevealUpdate(
                previousInSegment = GeoPoint(latitude = 12.345, longitude = 179.0),
                current = GeoPoint(latitude = 12.345, longitude = -179.0),
            ),
            FogRevealUpdate(
                previousInSegment = GeoPoint(latitude = 12.345, longitude = -89.0),
                current = GeoPoint(latitude = 12.345, longitude = 91.0),
            ),
            FogRevealUpdate(
                previousInSegment = GeoPoint(latitude = 84.0, longitude = -10.0),
                current = GeoPoint(latitude = 85.0, longitude = 10.0),
            ),
            FogRevealUpdate(
                previousInSegment = GeoPoint(latitude = 89.999, longitude = 0.0),
                current = GeoPoint(latitude = 89.999, longitude = 179.0),
            ),
        )
        val invalidator = FogTileInvalidator(zoomLevels, oracleStyle)

        updates.forEach { update ->
            val candidates = invalidator.candidateKeys(update, renderVersion = 4)
            val oracle = rendererAffectedKeys(update, zoomLevels, oracleStyle, renderVersion = 4)
            val missing = oracle - candidates
            assertTrue("Conservative candidates omitted renderer changes: $missing", missing.isEmpty())
            assertTrue("Oracle fixture changed no rendered tile: $update", oracle.isNotEmpty())
        }
    }

    @Test
    fun clampedPolarSpanCanBeCountedAndIntersectedWithoutMaterializingIt() {
        val update = FogRevealUpdate(
            previousInSegment = GeoPoint(latitude = 89.999, longitude = 0.0),
            current = GeoPoint(latitude = 89.999, longitude = 179.0),
        )
        val invalidator = FogTileInvalidator(0..22, style)
        val activeKeys = FogViewportTileGrid.around(update.current, zoom = 22, renderVersion = 7)

        val candidateCount = invalidator.candidateKeyCount(update)
        val activeCandidates = invalidator.candidateKeysAmong(update, 7, activeKeys)

        assertTrue(candidateCount > 60_000_000L)
        assertTrue(activeCandidates.isNotEmpty())
        assertTrue(activeCandidates.size <= activeKeys.size)
        assertTrue(activeKeys.containsAll(activeCandidates))
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

    private fun rendererAffectedKeys(
        update: FogRevealUpdate,
        zoomLevels: IntRange,
        style: FogRenderStyle,
        renderVersion: Int,
    ): Set<FogTileKey> {
        val renderer = FogTileRenderer(style)
        val before = update.previousInSegment
            ?.let { previous -> listOf(TrackSegment(id = 0, points = listOf(previous))) }
            .orEmpty()
        val after = listOf(
            TrackSegment(
                id = 0,
                points = update.previousInSegment
                    ?.let { previous -> listOf(previous, update.current) }
                    ?: listOf(update.current),
            ),
        )
        return buildSet {
            zoomLevels.forEach { zoom ->
                val tileCount = 1 shl zoom
                repeat(tileCount) { y ->
                    repeat(tileCount) { x ->
                        val key = FogTileKey(zoom, x, y, renderVersion)
                        if (renderer.render(key, before) != renderer.render(key, after)) add(key)
                    }
                }
            }
        }
    }
}
