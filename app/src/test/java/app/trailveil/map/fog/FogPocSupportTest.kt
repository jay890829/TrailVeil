package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogPocSupportTest {
    @Test
    fun ordinaryCenterProducesOrderedThreeByThreeGrid() {
        val center = GeoPoint(latitude = 0.0, longitude = 0.0)
        val centerTile = WebMercator.tile(center, zoom = 3)

        val tiles = FogPocTileGrid.around(center, zoom = 3, renderVersion = 7)

        assertEquals(9, tiles.size)
        assertEquals(listOf(3, 4, 5), tiles.map(FogTileKey::x).distinct())
        assertEquals(listOf(3, 4, 5), tiles.map(FogTileKey::y).distinct())
        assertTrue(tiles.all { it.renderVersion == 7 })
        assertTrue(tiles.any { it.x == centerTile.x && it.y == centerTile.y })
    }

    @Test
    fun horizontalNeighborsWrapAcrossDateline() {
        val east = FogPocTileGrid.around(
            center = GeoPoint(latitude = 0.0, longitude = 179.999),
            zoom = 2,
            renderVersion = 0,
        )
        val west = FogPocTileGrid.around(
            center = GeoPoint(latitude = 0.0, longitude = -179.999),
            zoom = 2,
            renderVersion = 0,
        )

        assertEquals(listOf(2, 3, 0), east.take(3).map(FogTileKey::x))
        assertEquals(listOf(3, 0, 1), west.take(3).map(FogTileKey::x))
    }

    @Test
    fun verticalNeighborsStopAtWebMercatorEdge() {
        val tiles = FogPocTileGrid.around(
            center = GeoPoint(latitude = WebMercator.MAX_LATITUDE, longitude = 0.0),
            zoom = 3,
            renderVersion = 0,
        )

        assertEquals(6, tiles.size)
        assertEquals(listOf(0, 1), tiles.map(FogTileKey::y).distinct())
    }

    @Test
    fun tileBoundsUseExactWebMercatorEdges() {
        val northWest = FogPocTileGrid.bounds(FogTileKey(zoom = 1, x = 0, y = 0, renderVersion = 0))
        val southEast = FogPocTileGrid.bounds(FogTileKey(zoom = 1, x = 1, y = 1, renderVersion = 0))

        assertEquals(-180.0, northWest.westLongitude, 0.0)
        assertEquals(0.0, northWest.eastLongitude, 0.0)
        assertEquals(0.0, northWest.southLatitude, 1e-12)
        assertEquals(WebMercator.MAX_LATITUDE, northWest.northLatitude, 1e-12)
        assertEquals(0.0, southEast.westLongitude, 0.0)
        assertEquals(180.0, southEast.eastLongitude, 0.0)
        assertEquals(-WebMercator.MAX_LATITUDE, southEast.southLatitude, 1e-12)
        assertEquals(0.0, southEast.northLatitude, 1e-12)
    }

    @Test
    fun timingLogIsStableAndNeverContainsCoordinates() {
        val log = FogPocTiming(
            stage = FogPocTimingStage.UPDATE_RENDER,
            durationMillis = 42,
            pointCount = 100_000,
            tileCount = 9,
        ).asStructuredLog()

        assertEquals(
            "event=trailveil_maplibre_poc_timing stage=update_render " +
                "duration_ms=42 point_count=100000 tile_count=9",
            log,
        )
        assertFalse(log.contains("latitude"))
        assertFalse(log.contains("longitude"))
        assertFalse(log.contains("coordinate"))
    }
}
