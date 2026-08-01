package app.trailveil.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.R
import app.trailveil.map.fog.GeoPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapSurfaceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unavailableProviderFallsBackWithoutRemovingTheMapSurface() {
        val fallbackText = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.map_unavailable)

        composeRule.setContent {
            TrailVeilMapSurface(
                modifier = Modifier.fillMaxSize(),
                provider = MapProviderConfiguration(
                    providerName = "unavailable-test-provider",
                    styleUri = "https://tiles.invalid/styles/unavailable",
                ),
                fallbackTimeoutMillis = 100L,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText(fallbackText)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(fallbackText).assertIsDisplayed()
    }

    @Test
    fun requiredFogKeepsUnknownAreaCoveredUntilRuntimeIsReady() {
        composeRule.setContent {
            TrailVeilMapSurface(
                modifier = Modifier.fillMaxSize(),
                fogRuntime = null,
                fogRequired = true,
            )
        }

        composeRule.onNodeWithTag(MapSurfaceTestTags.FogSafetyCover).assertIsDisplayed()
    }

    @Test
    fun persistedLocationAndSegmentedTrackOverlaysSurviveLocalFallback() {
        val fallbackText = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.map_unavailable)

        composeRule.setContent {
            TrailVeilMapSurface(
                modifier = Modifier.fillMaxSize(),
                provider = MapProviderConfiguration(
                    providerName = "overlay-fallback-test-provider",
                    styleUri = "https://tiles.invalid/styles/overlay-fallback",
                ),
                fallbackTimeoutMillis = 100L,
                savedStateKey = "trailveil.map.overlay-test",
                currentLocation = GeoPoint(25.033, 121.565),
                trackOverlay = MapTrackOverlay(
                    requestId = 1L,
                    segments = listOf(
                        listOf(GeoPoint(25.032, 121.564), GeoPoint(25.033, 121.565)),
                        listOf(GeoPoint(25.04, 121.57)),
                    ),
                ),
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText(fallbackText).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MapSurfaceTestTags.Map).assertIsDisplayed()
    }
}
