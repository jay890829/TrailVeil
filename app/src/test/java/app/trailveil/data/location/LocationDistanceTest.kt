package app.trailveil.data.location

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationDistanceTest {
    @Test
    fun identicalCoordinatesHaveZeroDistance() {
        assertEquals(0.0, LocationDistance.haversineMeters(25.0, 121.0, 25.0, 121.0), 0.0)
    }

    @Test
    fun oneEquatorialDegreeMatchesTheMeanEarthReference() {
        assertEquals(
            111_195.080,
            LocationDistance.haversineMeters(0.0, 0.0, 0.0, 1.0),
            0.01,
        )
    }

    @Test
    fun datelineCrossingTakesTheShortPathInBothDirections() {
        val eastward = LocationDistance.haversineMeters(0.0, 179.999, 0.0, -179.999)
        val westward = LocationDistance.haversineMeters(0.0, -179.999, 0.0, 179.999)

        assertEquals(222.390, eastward, 0.01)
        assertEquals(eastward, westward, 1e-8)
    }
}
