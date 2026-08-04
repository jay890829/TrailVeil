package app.trailveil.map

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedPointRevision
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.data.map.PersistedTrackPointChangeFeed
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
            composeRule.onNodeWithText(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getString(R.string.map_unavailable),
            ).assertIsDisplayed()
        } finally {
            database.close()
        }
    }

    /**
     * Fog is the product's core feature, so its failure must outrank the basemap fallback that is
     * expected offline. Ranking the fallback first hides every fog failure taken while offline.
     */
    @Test
    fun fogFailureStatusOutranksTheLocalBasemapFallback() {
        val fogUnavailableText = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.map_fog_unavailable)
        val database = inMemoryDatabase()
        try {
            val dao = database.recordingDao()
            val style = FogRenderStyle()
            val runtime = FogRuntime(
                viewportCoordinator = FogViewportCoordinator(
                    trackDataSource = ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
                    pipeline = FogTilePipeline(
                        memoryCache = FogMemoryTileCache(8L * 1024L * 1024L),
                        diskCache = null,
                        renderMask = { _, _ -> error("Fog tile rendering is unavailable") },
                    ),
                    style = style,
                ),
                pointChanges = RoomPersistedTrackPointChangeFeed(dao),
            )
            val fogFailed = AtomicBoolean(false)

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-failure-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-failure",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.fog-failure-test",
                    fogRuntime = runtime,
                    fogRequired = true,
                    onFogFailure = { fogFailed.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText(fogUnavailableText)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val basemapState = composeRule.runOnIdle {
                attachedMapView()?.getTag(R.id.map_basemap_load_state)
            }

            assertTrue("The failing fog runtime never reported a failure", fogFailed.get())
            assertEquals(BasemapLoadState.LOCAL_FALLBACK.name, basemapState)
            composeRule.onNodeWithText(fogUnavailableText).assertIsDisplayed()
        } finally {
            database.close()
        }
    }

    /**
     * A canonical change feed failure stops fog from tracking new points at all. The placeholder
     * reinstall that follows each failure clears the per-viewport render flag, so the feed failure
     * needs its own latched state or the offline surface reports nothing but a basemap fallback.
     *
     * The status is re-asserted across more than one synchronization retry because the unlatched
     * render flag is true only between a retry and the placeholder reinstall that follows it, so a
     * single sample cannot tell a latched status apart from that transient one.
     */
    @Test
    fun changeFeedFailureKeepsTheFogStatusVisibleDuringTheLocalFallback() {
        val fogUnavailableText = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.map_fog_unavailable)
        val database = inMemoryDatabase()
        try {
            val fogFailed = AtomicBoolean(false)

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "feed-failure-test-provider",
                        styleUri = "https://tiles.invalid/styles/feed-failure",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.feed-failure-test",
                    fogRuntime = fogRuntime(database, RecoverableChangeFeed()),
                    fogRequired = true,
                    onFogFailure = { fogFailed.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText(fogUnavailableText)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val basemapState = composeRule.runOnIdle {
                attachedMapView()?.getTag(R.id.map_basemap_load_state)
            }

            assertTrue("The unavailable change feed never reported a failure", fogFailed.get())
            assertEquals(BasemapLoadState.LOCAL_FALLBACK.name, basemapState)
            val settleDeadline = SystemClock.uptimeMillis() + FOG_STATUS_SETTLE_MILLIS
            while (SystemClock.uptimeMillis() < settleDeadline) {
                composeRule.onNodeWithText(fogUnavailableText).assertIsDisplayed()
            }
        } finally {
            database.close()
        }
    }

    /** The latched feed status must be visibility only: a healed feed has to clear it again. */
    @Test
    fun fogStatusClearsAfterTheChangeFeedRecovers() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fogUnavailableText = targetContext.getString(R.string.map_fog_unavailable)
        val fallbackText = targetContext.getString(R.string.map_unavailable)
        val database = inMemoryDatabase()
        try {
            val feed = RecoverableChangeFeed()
            val fogRendered = AtomicBoolean(false)

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "feed-recovery-test-provider",
                        styleUri = "https://tiles.invalid/styles/feed-recovery",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.feed-recovery-test",
                    fogRuntime = fogRuntime(database, feed),
                    fogRequired = true,
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText(fogUnavailableText)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            feed.restore()
            composeRule.waitUntil(timeoutMillis = 15_000L) { fogRendered.get() }
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText(fallbackText).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText(fallbackText).assertIsDisplayed()
        } finally {
            database.close()
        }
    }

    /**
     * A rendered mosaic may keep covering the map only where it actually reaches. Panning far
     * beyond it must raise the safety cover rather than expose unknown area as explored.
     */
    @Test
    fun panningBeyondTheRenderedFogRaisesTheSafetyCover() {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            val cameraRequest = mutableStateOf(
                MapCameraRequest(
                    requestId = 1L,
                    point = GeoPoint(25.0330, 121.5654),
                    zoom = 16.0,
                ),
            )

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-cover-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-cover",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.fog-cover-test",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = cameraRequest.value,
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000L) { fogRendered.get() }
            composeRule.onNodeWithTag(MapSurfaceTestTags.FogSafetyCover).assertDoesNotExist()

            composeRule.runOnUiThread {
                cameraRequest.value = MapCameraRequest(
                    requestId = 2L,
                    point = GeoPoint(35.0330, 131.5654),
                    zoom = 16.0,
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } finally {
            database.close()
        }
    }

    private fun inMemoryDatabase(): TrailVeilDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()

    private fun fogRuntime(
        database: TrailVeilDatabase,
        pointChanges: PersistedTrackPointChangeFeed,
    ): FogRuntime {
        val dao = database.recordingDao()
        val style = FogRenderStyle()
        return FogRuntime(
            viewportCoordinator = FogViewportCoordinator(
                trackDataSource = ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
                pipeline = FogTilePipeline(
                    memoryCache = FogMemoryTileCache(8L * 1024L * 1024L),
                    diskCache = null,
                    renderMask = FogTileRenderer(style)::render,
                ),
                style = style,
            ),
            pointChanges = pointChanges,
        )
    }

    /**
     * Fails like an unavailable canonical feed until [restore]. The restored revision flow stays
     * open like the Room feed; a completing flow would make the surface re-synchronize in a loop.
     */
    private class RecoverableChangeFeed : PersistedTrackPointChangeFeed {
        private val restored = AtomicBoolean(false)

        fun restore() {
            restored.set(true)
        }

        override suspend fun latestCursor(): PersistedPointCursor {
            requireAvailable()
            return PersistedPointCursor(0L)
        }

        override fun revisionsAfter(cursor: PersistedPointCursor): Flow<PersistedPointRevision> =
            flow {
                requireAvailable()
                awaitCancellation()
            }

        override suspend fun readChangesAfter(
            cursor: PersistedPointCursor,
            limit: Int,
        ): List<PersistedTrackPointChange> {
            requireAvailable()
            return emptyList()
        }

        private fun requireAvailable() {
            check(restored.get()) { "Canonical change feed is unavailable" }
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

    private companion object {
        const val FOG_STATUS_SETTLE_MILLIS = 2_000L
    }
}
