package app.trailveil.map

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.trailveil.R
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.map.fog.FogMemoryTileCache
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.GeoPoint
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

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

    /**
     * The local fallback badge short-circuits every later status branch, so the surface has no
     * other composition read of the canonical-fog flag. Publication must therefore observe that
     * state itself, or the offline surface keeps reporting "no canonical fog" after it rendered.
     */
    @Test
    fun canonicalFogGenerationIsPublishedWhileTheLocalFallbackIsActive() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()
        try {
            val dao = database.recordingDao()
            val style = FogRenderStyle()
            val runtime = FogRuntime(
                viewportCoordinator = FogViewportCoordinator(
                    trackDataSource = ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
                    pipeline = FogTilePipeline(
                        memoryCache = FogMemoryTileCache(8L * 1024L * 1024L),
                        diskCache = null,
                        renderMask = FogTileRenderer(style)::render,
                    ),
                    style = style,
                ),
                pointChanges = RoomPersistedTrackPointChangeFeed(dao),
            )
            val fogRendered = AtomicBoolean(false)
            val fogFailed = AtomicBoolean(false)

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-publication-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-publication",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.fog-publication-test",
                    fogRuntime = runtime,
                    fogRequired = true,
                    onFogRendered = { fogRendered.set(true) },
                    onFogFailure = { fogFailed.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000L) {
                fogRendered.get() || fogFailed.get()
            }
            val published = composeRule.runOnIdle {
                attachedMapView()?.getTag(R.id.map_fog_canonical_generation)
            }
            val basemapState = composeRule.runOnIdle {
                attachedMapView()?.getTag(R.id.map_basemap_load_state)
            }

            assertEquals(BasemapLoadState.LOCAL_FALLBACK.name, basemapState)
            assertNotNull(
                "The canonical fog generation was never published after the fog rendered",
                published,
            )
        } finally {
            database.close()
        }
    }

    private fun attachedMapView(): MapView? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstNotNullOfOrNull { activity -> activity.window.decorView.findMapView() }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }
}
