package app.trailveil.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLibreFogArmTest {
    @Test fun regressionControlsRetainTheirOriginalExtents() {
        assertEquals(listOf("trackVectorFloor", "trackVector", "mosaic5"), MapLibreFogArm.entries.map { it.id })
        assertEquals(1, MapLibreFogArm.TRACK_VECTOR_FLOOR.mosaicPaddingTiles)
        assertEquals(2, MapLibreFogArm.TRACK_VECTOR_RING.mosaicPaddingTiles)
        assertEquals(2, MapLibreFogArm.RING_2.mosaicPaddingTiles)
        assertEquals(listOf(true, true, false), MapLibreFogArm.entries.map { it.trackVector })
    }

    @Test fun freshAndEveryRetiredChoiceResolveToNativeRing() {
        val ids = listOf(null, "trackVectorFloor", "trackVector", "trackVectorRing", "vector",
            "mosaic5", "baseline", "mosaic7", "unknown", "ring2")
        ids.forEach { assertEquals(MapLibreFogArm.TRACK_VECTOR_RING, MapLibreFogArm.fromId(it)) }
        assertTrue(MapLibreFogArm.DEFAULT.trackVector)
        assertEquals(2, MapLibreFogArm.DEFAULT.mosaicPaddingTiles)
    }
}
