package app.trailveil.harness

import app.trailveil.map.fog.FogSurroundExtent
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeFogBackdropGeometryTest {
    @Test fun worldComplementNeverDoubleCoatsEitherSideOfADatelineImage() {
        for (west in listOf(170.0, -190.0, -10.0)) {
            val image = FogTileBounds(west, -10.0, west + 20.0, 10.0)
            val surround = FogSurroundExtent(west + 10.0, 0.5,
                WebMercator.normalizedY(60.0), WebMercator.normalizedY(-60.0), true)
            val fragments = NativeFogBackdropGeometry.googleRectangles(surround, image)
            fragments.forEach { rectangle ->
                val center = (rectangle.westLongitude + rectangle.eastLongitude) / 2.0
                org.junit.Assert.assertTrue(center >= -180.0 && center < 180.0)
                org.junit.Assert.assertTrue(rectangle.eastLongitude - rectangle.westLongitude <= 90.0)
            }
            val surfaces = fragments + image
            for (latitude in listOf(-30.0, 0.0, 30.0)) for (longitude in -179..179) {
                val x = longitude + 0.125
                val coats = surfaces.sumOf { rect ->
                    (-2..2).count { world ->
                        val shifted = x + world * 360.0
                        shifted > rect.westLongitude && shifted < rect.eastLongitude &&
                            latitude > rect.southLatitude && latitude < rect.northLatitude
                    }
                }
                assertEquals("each wrapped ground point needs exactly one coat", 1, coats)
            }
        }
    }

    @Test fun finiteComplementStopsAtTheDeclaredSurround() {
        val image = FogTileBounds(-10.0, -10.0, 10.0, 10.0)
        val surround = FogSurroundExtent(0.0, 0.1,
            WebMercator.normalizedY(30.0), WebMercator.normalizedY(-30.0), false)
        val strips = NativeFogBackdropGeometry.complement(surround, image)
        assertEquals(4, strips.size)
        assertEquals(-36.0, strips.minOf { it.westLongitude }, 0.0)
        assertEquals(36.0, strips.maxOf { it.eastLongitude }, 0.0)
    }
}
