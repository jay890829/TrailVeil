package io.github.jay890829.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogPocSpatialSelectionTest {
    @Test
    fun ordinarySelectionKeepsNearbySegmentAndRejectsFarSegment() {
        val point = GeoPoint(latitude = 20.0, longitude = 10.0)
        val near = TrackSegment(id = 1, points = listOf(point))
        val far = TrackSegment(
            id = 2,
            points = listOf(GeoPoint(latitude = -60.0, longitude = -120.0)),
        )
        val keys = FogPocTileGrid.around(point, zoom = 4, renderVersion = 0)
        val centerTile = WebMercator.tile(point, zoom = 4)
        val centerKey = keys.single { it.x == centerTile.x && it.y == centerTile.y }

        val selected = FogPocSpatialSelection.select(keys, listOf(near, far))

        assertEquals(listOf(near), selected.getValue(centerKey))
        assertFalse(selected.values.flatten().contains(far))
    }

    @Test
    fun revealRadiusAndCapsuleSelectBothSidesOfTileBoundary() {
        val center = GeoPoint(latitude = 20.0, longitude = -0.00001)
        val reveal = TrackSegment(id = 1, points = listOf(center))
        val crossing = TrackSegment(
            id = 2,
            points = listOf(
                GeoPoint(latitude = 20.0, longitude = -1.0),
                GeoPoint(latitude = 20.0, longitude = 1.0),
            ),
        )
        val keys = FogPocTileGrid.around(center, zoom = 2, renderVersion = 0)
        val centerY = WebMercator.tile(center, zoom = 2).y
        val west = keys.single { it.x == 1 && it.y == centerY }
        val east = keys.single { it.x == 2 && it.y == centerY }

        val selected = FogPocSpatialSelection.select(
            keys = keys,
            segments = listOf(reveal, crossing),
            style = FogRenderStyle(revealRadiusMeters = 1_000.0),
        )

        assertTrue(reveal in selected.getValue(west))
        assertTrue(reveal in selected.getValue(east))
        assertTrue(crossing in selected.getValue(west))
        assertTrue(crossing in selected.getValue(east))
    }

    @Test
    fun datelineCapsuleSelectsTilesOnBothWrappedSides() {
        val segment = TrackSegment(
            id = 1,
            points = listOf(
                GeoPoint(latitude = 20.0, longitude = 179.0),
                GeoPoint(latitude = 20.0, longitude = -179.0),
            ),
        )
        val keys = FogPocTileGrid.around(segment.points.first(), zoom = 2, renderVersion = 0)
        val centerY = WebMercator.tile(segment.points.first(), zoom = 2).y
        val east = keys.single { it.x == 3 && it.y == centerY }
        val west = keys.single { it.x == 0 && it.y == centerY }

        val selected = FogPocSpatialSelection.select(keys, listOf(segment))

        assertEquals(listOf(segment), selected.getValue(east))
        assertEquals(listOf(segment), selected.getValue(west))
    }
}
