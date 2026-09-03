package app.trailveil.map

import app.trailveil.map.fog.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCameraBoundsPlannerTest {
    @Test
    fun emptyAndInvalidPointsProduceNoCameraBounds() {
        assertNull(planTrackCameraBounds(emptyList()))
        assertNull(
            planTrackCameraBounds(
                listOf(
                    GeoPoint(latitude = 91.0, longitude = 0.0),
                    GeoPoint(latitude = -91.0, longitude = 0.0),
                ),
            ),
        )
    }

    @Test
    fun singletonAndDuplicatesStayFiniteAndDoNotSpanTheWorld() {
        val singleton = requireNotNull(
            planTrackCameraBounds(listOf(GeoPoint(latitude = 89.9, longitude = 540.0))),
        )
        assertEquals(89.9, singleton.southLatitude, 0.0)
        assertEquals(89.9, singleton.northLatitude, 0.0)
        assertEquals(-180.0, singleton.westLongitude, 0.0)
        assertEquals(-180.0, singleton.eastLongitude, 0.0)
        assertFalse(singleton.crossesAntimeridian)
        assertEquals(0.0, singleton.longitudeSpanDegrees, 0.0)

        val duplicate = requireNotNull(
            planTrackCameraBounds(
                listOf(
                    GeoPoint(latitude = 10.0, longitude = 179.5),
                    GeoPoint(latitude = 10.0, longitude = 179.5),
                ),
            ),
        )
        assertEquals(0.0, duplicate.longitudeSpanDegrees, 0.0)
        assertEquals(179.5, duplicate.westLongitude, 0.0)
        assertEquals(179.5, duplicate.eastLongitude, 0.0)
    }

    @Test
    fun datelinePointsUseTheShortCrossingArc() {
        val bounds = requireNotNull(
            planTrackCameraBounds(
                listOf(
                    GeoPoint(latitude = -2.0, longitude = 179.0),
                    GeoPoint(latitude = 3.0, longitude = -179.0),
                    GeoPoint(latitude = 1.0, longitude = 178.5),
                ),
            ),
        )
        assertEquals(-2.0, bounds.southLatitude, 0.0)
        assertEquals(3.0, bounds.northLatitude, 0.0)
        assertEquals(178.5, bounds.westLongitude, 0.0)
        assertEquals(-179.0, bounds.eastLongitude, 0.0)
        assertTrue(bounds.crossesAntimeridian)
        assertEquals(2.5, bounds.longitudeSpanDegrees, 0.0)
    }

    @Test
    fun ordinaryAndWideWorldInputsRemainOrderedOnTheSelectedArc() {
        val ordinary = requireNotNull(
            planTrackCameraBounds(
                listOf(
                    GeoPoint(latitude = 25.0, longitude = 121.0),
                    GeoPoint(latitude = 26.0, longitude = 123.0),
                ),
            ),
        )
        assertFalse(ordinary.crossesAntimeridian)
        assertEquals(121.0, ordinary.westLongitude, 0.0)
        assertEquals(123.0, ordinary.eastLongitude, 0.0)
        assertEquals(2.0, ordinary.longitudeSpanDegrees, 0.0)

        val wide = requireNotNull(
            planTrackCameraBounds(
                listOf(
                    GeoPoint(latitude = 0.0, longitude = -170.0),
                    GeoPoint(latitude = 0.0, longitude = -10.0),
                    GeoPoint(latitude = 0.0, longitude = 170.0),
                ),
            ),
        )
        // The sorted longitudes have a 180 degree largest gap (-10 -> 170), so the selected
        // complement is exactly the 180 degree shortest arc crossing the dateline. This catches
        // an implementation that simply uses min/max and accidentally selects 340 degrees.
        assertTrue(wide.crossesAntimeridian)
        assertEquals(170.0, wide.westLongitude, 0.0)
        assertEquals(-10.0, wide.eastLongitude, 0.0)
        assertEquals(180.0, wide.longitudeSpanDegrees, 0.0)
    }

    @Test
    fun polarEndpointsRemainFiniteAndPreserveLatitudeOrder() {
        val bounds = requireNotNull(
            planTrackCameraBounds(
                listOf(
                    GeoPoint(latitude = -89.9, longitude = 10.0),
                    GeoPoint(latitude = 89.9, longitude = 10.0),
                ),
            ),
        )
        assertEquals(-89.9, bounds.southLatitude, 0.0)
        assertEquals(89.9, bounds.northLatitude, 0.0)
        assertTrue(bounds.southLatitude.isFinite())
        assertTrue(bounds.northLatitude.isFinite())
        assertEquals(0.0, bounds.longitudeSpanDegrees, 0.0)
    }
}
