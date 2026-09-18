package app.trailveil.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapLibreFogColorTest {
    @Test fun rasterKeepsEveryAlphaValueAndUsesTheExistingNativeRgb() {
        assertEquals(0xff1f262b.toInt(), MAPLIBRE_FOG_COLOR)
        for (alpha in 0..255) {
            val pixel = mapLibreFogPixel(alpha)
            assertEquals(alpha, pixel ushr 24)
            assertEquals(if (alpha == 0) 0 else 0x1f262b, pixel and 0xffffff)
            assertEquals(pixel, mapLibreFogPixel(alpha.toByte().toInt()))
        }
        assertEquals(0xb81f262b.toInt(), mapLibreFogPixel(184))
    }
}
