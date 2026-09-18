package app.trailveil.harness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSegmentCullingTest {
    @Test fun oppositeOutsideEndpointsRetainTheCrossingCorridor() {
        assertFalse(outside(-30f, 50f, 130f, 50f))
        assertFalse(outside(50f, -30f, 50f, 130f))
        assertFalse(outside(-30f, -30f, 130f, 130f))
    }

    @Test fun onlySharedOutsideHalfPlanesAreRejected() {
        assertTrue(outside(-30f, -100f, -21f, 200f))
        assertTrue(outside(121f, -100f, 140f, 200f))
        assertTrue(outside(-100f, -21f, 200f, -30f))
        assertTrue(outside(-100f, 130f, 200f, 121f))
        assertFalse(outside(-20f, 50f, -20f, 60f))
    }

    private fun outside(x0: Float, y0: Float, x1: Float, y1: Float) =
        screenSegmentOutside(x0, y0, x1, y1, 100f, 100f, 20f)
}
