package app.trailveil.feature.settings

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.map.MapLibreVectorFogState
import app.trailveil.map.MapLibreFogArm
import app.trailveil.map.fogMosaicPaddingTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import app.trailveil.TrailVeilApplication
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HarnessFogArmPickerTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun fixedSchemeIgnoresLegacyPreferencesAndHasNoSelector() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        if (stageLegacyFogPreference(
            save = { id -> MapLibreFogArm.store(targetContext, MapLibreFogArm.entries.single { it.id == id }) },
            readId = { MapLibreFogArm.stored(targetContext).id },
        )) return

        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TrailVeilApplication
        runBlocking(Dispatchers.IO) { application.appContainer.fogRuntime() }
        val oldActive = MapLibreVectorFogState.activeArm
        val vector = MapLibreVectorFogState.enabled
        val track = MapLibreVectorFogState.trackEnabled
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            verifyFixedFogScheme(compose, listOf("trackVectorFloor", "mosaic5", "mosaic7", "unknown"),
                expectedLabel = MapLibreFogArm.TRACK_VECTOR_RING.label,
                initialize = {
                    assertEquals(2, fogMosaicPaddingTiles(context))
                    assertTrue(MapLibreVectorFogState.trackEnabled)
                    checkNotNull(MapLibreVectorFogState.activeArm).label
                },
                activeLabel = {
                    assertTrue(MapLibreVectorFogState.trackEnabled)
                    checkNotNull(MapLibreVectorFogState.activeArm).label
                })
        } finally {
            MapLibreVectorFogState.activeArm = oldActive
            MapLibreVectorFogState.enabled = vector
            MapLibreVectorFogState.trackEnabled = track
        }
    }
}
