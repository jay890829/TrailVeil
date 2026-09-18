package app.trailveil.feature.settings

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.map.GoogleFogCoverageArm
import app.trailveil.map.GoogleFogArm
import app.trailveil.map.GoogleFogArmInitializer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HarnessFogArmPickerTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun fixedSchemeIgnoresLegacyPreferencesAndHasNoSelector() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        if (stageLegacyFogPreference(
            save = { id -> GoogleFogArm.store(targetContext, GoogleFogArm.entries.single { it.id == id }) },
            readId = { GoogleFogArm.stored(targetContext).id },
        )) return

        val oldActive = checkNotNull(GoogleFogArm.activeArm)
        val profile = GoogleFogCoverageArm.profile
        val mosaic = GoogleFogCoverageArm.mosaicOverlay
        val stencil = GoogleFogCoverageArm.screenStencil
        val vector = GoogleFogCoverageArm.vectorPolygon
        val track = GoogleFogCoverageArm.trackVector
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            verifyFixedFogScheme(compose, listOf("trackVectorRing", "ring2", "screenStencil", "unknown"),
                expectedLabel = GoogleFogArm.TRACK_VECTOR.label,
                initialize = {
                    GoogleFogArmInitializer().create(context)
                    assertEquals(0, GoogleFogCoverageArm.profile.paddingTiles)
                    assertTrue(GoogleFogCoverageArm.trackVector)
                    checkNotNull(GoogleFogArm.activeArm).label
                },
                activeLabel = {
                    assertEquals(0, GoogleFogCoverageArm.profile.paddingTiles)
                    assertTrue(GoogleFogCoverageArm.trackVector)
                    checkNotNull(GoogleFogArm.activeArm).label
                })
        } finally {
            oldActive.apply()
            GoogleFogCoverageArm.profile = profile
            GoogleFogCoverageArm.mosaicOverlay = mosaic
            GoogleFogCoverageArm.screenStencil = stencil
            GoogleFogCoverageArm.vectorPolygon = vector
            GoogleFogCoverageArm.trackVector = track
        }
    }
}
