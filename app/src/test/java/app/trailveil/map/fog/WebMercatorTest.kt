package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMercatorTest {
    @Test
    fun tileBoundariesChooseEastAndSouthTiles() {
        assertEquals(TileCoordinate(1, 1), WebMercator.tile(GeoPoint(0.0, 0.0), zoom = 1))
        assertEquals(
            TileCoordinate(0, 1),
            WebMercator.tile(GeoPoint(0.0, -0.000_001), zoom = 1),
        )
        assertEquals(
            TileCoordinate(1, 0),
            WebMercator.tile(GeoPoint(0.000_001, 0.0), zoom = 1),
        )
    }

    @Test
    fun latitudeIsClampedToWebMercatorLimit() {
        assertEquals(WebMercator.normalizedY(90.0), WebMercator.normalizedY(WebMercator.MAX_LATITUDE), 0.0)
        assertEquals(WebMercator.normalizedY(-90.0), WebMercator.normalizedY(-WebMercator.MAX_LATITUDE), 0.0)
        assertEquals(0, WebMercator.tile(GeoPoint(90.0, 0.0), zoom = 8).y)
        assertEquals(255, WebMercator.tile(GeoPoint(-90.0, 0.0), zoom = 8).y)
    }

    @Test
    fun longitudeWrapsAcrossDateline() {
        assertEquals(-180.0, WebMercator.wrapLongitude(180.0), 0.0)
        assertEquals(-179.0, WebMercator.wrapLongitude(181.0), 0.0)
        assertEquals(179.0, WebMercator.wrapLongitude(-181.0), 0.0)
        assertEquals(
            WebMercator.worldPixel(GeoPoint(0.0, -179.0), zoom = 3).x,
            WebMercator.worldPixel(GeoPoint(0.0, 181.0), zoom = 3).x,
            0.0,
        )
    }

    @Test
    fun projectionMatchesKnownZoomZeroWorldPixels() {
        assertEquals(WorldPixel(128.0, 128.0), WebMercator.worldPixel(GeoPoint(0.0, 0.0), zoom = 0))
        assertEquals(WorldPixel(0.0, 128.0), WebMercator.worldPixel(GeoPoint(0.0, 180.0), zoom = 0))
        assertEquals(WorldPixel(0.0, 128.0), WebMercator.worldPixel(GeoPoint(0.0, 540.0), zoom = 0))
        assertEquals(0.0, WebMercator.worldPixel(GeoPoint(WebMercator.MAX_LATITUDE, 0.0), 0).y, 0.0)
        assertEquals(256.0, WebMercator.worldPixel(GeoPoint(-WebMercator.MAX_LATITUDE, 0.0), 0).y, 1e-12)
    }

    @Test
    fun projectionRoundTripIsStable() {
        val source = GeoPoint(25.033, 121.5654)
        val pixel = WebMercator.worldPixel(source, zoom = 17)
        val restored = WebMercator.pointAtWorldPixel(pixel, zoom = 17)

        assertEquals(source.latitude, restored.latitude, 1e-10)
        assertEquals(source.longitude, restored.longitude, 1e-10)
        assertTrue(WebMercator.metersPerPixel(source.latitude, zoom = 17) > 0.0)
    }
}
