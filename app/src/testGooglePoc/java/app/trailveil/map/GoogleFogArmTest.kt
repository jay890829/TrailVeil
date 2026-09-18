package app.trailveil.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GoogleFogArmTest {
    @Test fun freshAndEveryRetiredChoiceResolveToFloorOnly() {
        val ids = listOf(null, "trackVector", "trackVectorFloor", "trackVectorRing", "vector", "ring2",
            "baseline", "ring1", "mosaic", "ring2mosaic", "screenStencil", "unknown")
        ids.forEach { assertEquals(GoogleFogArm.TRACK_VECTOR, GoogleFogArm.fromId(it)) }
        assertEquals(0, GoogleFogArm.DEFAULT.paddingTiles)
        assertEquals(true, GoogleFogArm.DEFAULT.trackVector)
    }

    @Test fun applyingAnyRetainedChoiceClearsEveryLegacySwitch() {
        try {
            GoogleFogArm.entries.forEach { arm ->
                GoogleFogCoverageArm.mosaicOverlay = true
                GoogleFogCoverageArm.screenStencil = true
                GoogleFogCoverageArm.vectorPolygon = true
                arm.apply()
                assertEquals(arm, GoogleFogArm.activeArm)
                assertFalse(GoogleFogCoverageArm.mosaicOverlay)
                assertFalse(GoogleFogCoverageArm.screenStencil)
                assertFalse(GoogleFogCoverageArm.vectorPolygon)
                assertEquals(arm.trackVector, GoogleFogCoverageArm.trackVector)
                assertEquals(arm.paddingTiles, GoogleFogCoverageArm.profile.paddingTiles)
            }
        } finally { GoogleFogCoverageArm.reset() }
    }
}
