package app.trailveil.map

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
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
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

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
     * The backdrop bands around a mosaic are finite, so a programmed camera move — which can
     * cross any distance in one step — still has to raise the safety cover rather than expose
     * unknown map as explored. Gestures are covered by the band surround instead and are asserted
     * against the renderer's own frames in [sustainedGesturesNeverExposeUnexploredMap].
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

    /**
     * The mosaic is a fixed number of tiles, so how much of the world it covers shrinks as the map
     * zooms in and grows as it zooms out — but the tile grid stops widening at three columns, so
     * below zoom 2 the mosaic covers less than the whole world while the viewport covers more of
     * it. This sweeps the settled camera across zoom levels and edge cases and reads back what
     * MapLibre actually drew, because the geometry alone does not say whether the shortfall is
     * ever inside the viewport.
     */
    @Test
    fun noSettledCameraPresentsUnexploredMapAsRevealed() = sweepSettledCameras(
        provider = MapProviderConfiguration(
            providerName = "fog-zoom-test-provider",
            styleUri = "https://tiles.invalid/styles/fog-zoom",
        ),
        requireOnlineStyle = false,
    )

    /**
     * The same sweep against the style that actually ships.
     *
     * The packaged fallback is a flat light fill, and the fixed-threshold measurement this replaced
     * was calibrated to its brightness — production vector tiles render the same unfogged ocean at
     * luminance 18-28, far below that threshold, so a real leak read as "fogged". Geometry can also
     * differ: the fallback paints the whole viewport, real tiles stop at the edge of the world.
     * Skipped rather than failed when the style cannot be fetched, so an offline run stays green
     * without silently claiming production was checked.
     */
    @Test
    fun noSettledCameraPresentsUnexploredMapAsRevealedOnTheProductionStyle() = sweepSettledCameras(
        provider = ProductionMapProvider,
        requireOnlineStyle = true,
    )

    private fun sweepSettledCameras(
        provider: MapProviderConfiguration,
        requireOnlineStyle: Boolean,
    ) {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            val revealed = GeoPoint(25.0330, 121.5654)
            revealTrack(database, revealed)
            val cameraRequest = mutableStateOf(
                MapCameraRequest(requestId = 1L, point = revealed, zoom = 16.0),
            )

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = provider,
                    fallbackTimeoutMillis = if (requireOnlineStyle) 20_000L else 100L,
                    savedStateKey = "trailveil.map.fog-zoom-test",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = cameraRequest.value,
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 30_000L) { fogRendered.get() }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }
            if (requireOnlineStyle) {
                val loadState = composeRule.runOnIdle {
                    attachedMapView()?.getTag(R.id.map_basemap_load_state)
                }
                Assume.assumeTrue(
                    "The production style did not load (state=$loadState); skipping rather than " +
                        "reporting a fallback-style result as production",
                    loadState == BasemapLoadState.ONLINE.name,
                )
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isCompassEnabled = false
            }

            // Calibrate before measuring: prove the instrument can see a leak on this device and
            // this style, so that a clean sweep below means the fog is there rather than that the
            // detector is blind.
            val calibration = map.auditWithFogRemoved()
            assertTrue(
                "The map drew almost nothing, so the sweep would pass vacuously: " +
                    calibration.report(),
                calibration.drawnFraction >= MINIMUM_DRAWN_FRACTION,
            )
            assertTrue(
                "With the fog layers hidden the audit still reported the map as covered, so it " +
                    "cannot detect a leak: " + calibration.report(),
                calibration.uncoveredFraction >= MINIMUM_CALIBRATION_UNCOVERED_FRACTION,
            )

            val report = StringBuilder("calibration=${calibration.report()} ")
            var worstFraction = 0.0
            var worstLabel = "none"
            var requestId = 1L
            UNEXPLORED_VIEWPOINTS.forEach { (label, point) ->
                ZOOM_SWEEP.forEach { zoom ->
                    requestId += 1L
                    composeRule.runOnUiThread {
                        cameraRequest.value = MapCameraRequest(
                            requestId = requestId,
                            point = point,
                            zoom = zoom,
                        )
                    }
                    composeRule.waitUntil(timeoutMillis = 25_000L) {
                        composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                            .fetchSemanticsNodes()
                            .isEmpty()
                    }
                    Thread.sleep(ZOOM_SETTLE_MILLIS)
                    val coverage = map.auditFogCoverage()
                    val settledZoom = map.cameraPosition.zoom
                    report.append(
                        "$label@z$zoom(actual=${"%.2f".format(java.util.Locale.US, settledZoom)})=" +
                            "${coverage.report()} ",
                    )
                    // Every zoom must actually be reachable, or the sweep could pass because the
                    // camera quietly refused to go where it was told. MapLibre keeps the world
                    // covering the viewport, so the true floor is device-dependent and sits below
                    // 1 — there, all that can be asserted is that nothing of ours adds a floor.
                    if (zoom >= LOWEST_EXACTLY_REACHABLE_ZOOM) {
                        assertEquals(
                            "Camera did not reach the requested zoom",
                            zoom,
                            settledZoom,
                            ZOOM_TOLERANCE,
                        )
                    } else {
                        assertTrue(
                            "Camera stopped at zoom $settledZoom, so something is refusing to " +
                                "zoom out",
                            settledZoom < LOWEST_EXACTLY_REACHABLE_ZOOM,
                        )
                    }
                    if (coverage.uncoveredFraction > worstFraction) {
                        worstFraction = coverage.uncoveredFraction
                        worstLabel = "$label@z$zoom"
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "TrailVeil settled fog coverage sweep [${provider.providerName}]: " +
                            "worst=$worstLabel " +
                            "worstFraction=${"%.4f".format(java.util.Locale.US, worstFraction * 100.0)}% " +
                            "limit=${MAXIMUM_SETTLED_REVEALED_FRACTION * 100.0}% $report\n",
                    )
                },
            )
            assertTrue(
                "Settled camera $worstLabel showed unexplored map as revealed " +
                    "(${"%.4f".format(java.util.Locale.US, worstFraction * 100.0)}%)",
                worstFraction <= MAXIMUM_SETTLED_REVEALED_FRACTION,
            )
        } finally {
            database.close()
        }
    }

    /**
     * The same invariant under real touch input rather than a camera call: sustained drags and
     * flings run on their own thread while this one keeps reading back what MapLibre drew.
     */
    @Test
    fun sustainedGesturesNeverExposeUnexploredMap() {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            val revealed = GeoPoint(25.0330, 121.5654)
            revealTrack(database, revealed)

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-gesture-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-gesture",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.fog-gesture-test",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = MapCameraRequest(
                        requestId = 1L,
                        point = revealed,
                        zoom = 16.0,
                    ),
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000L) { fogRendered.get() }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isCompassEnabled = false
            }
            var explored = map.renderedFogCoverage()
            composeRule.waitUntil(timeoutMillis = 20_000L) {
                explored = map.renderedFogCoverage()
                explored.revealedFraction > MINIMUM_REVEALED_FRACTION
            }

            val metrics = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
            val centerX = metrics.widthPixels / 2f
            val gestures = Thread {
                repeat(SUSTAINED_DRAG_COUNT) {
                    dragVertically(
                        x = centerX,
                        fromY = metrics.heightPixels * 0.78f,
                        toY = metrics.heightPixels * 0.22f,
                        steps = 24,
                        stepMillis = 12L,
                        lift = false,
                    )
                }
                repeat(FLING_COUNT) {
                    dragVertically(
                        x = centerX,
                        fromY = metrics.heightPixels * 0.80f,
                        toY = metrics.heightPixels * 0.20f,
                        steps = 6,
                        stepMillis = 3L,
                        lift = true,
                    )
                    Thread.sleep(FLING_SETTLE_MILLIS)
                }
            }
            gestures.start()

            var samples = 0
            var postExitFrames = 0
            var postExitMaxLuminance = 0
            var postExitMaxRevealed = 0.0
            var exited = false
            var coveredFrames = 0
            val leaks = mutableListOf<FogCoverage>()
            while (gestures.isAlive) {
                val coverage = map.renderedFogCoverage()
                samples += 1
                if (
                    composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                ) {
                    coveredFrames += 1
                }
                // The revealed track leaves the viewport within the first drag; every frame from
                // then on is over map the user has not explored.
                if (!exited && coverage.revealedFraction == 0.0) exited = true
                if (exited) {
                    postExitFrames += 1
                    postExitMaxLuminance = max(postExitMaxLuminance, coverage.maxLuminance)
                    postExitMaxRevealed = max(postExitMaxRevealed, coverage.revealedFraction)
                    if (coverage.revealedFraction > 0.0) leaks += coverage
                }
            }
            gestures.join()
            val settled = map.renderedFogCoverage()
            val diagnostics = AtomicReference("")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val style = map.style
                diagnostics.set(
                    "camera=${map.cameraPosition.target}/${map.cameraPosition.zoom} " +
                        "mosaicSource=${style?.getSource(FogOverlayIds.Source) != null} " +
                        "mosaicLayer=${style?.getLayer(FogOverlayIds.Layer) != null} " +
                        "bands=" + FogBackdropIds.Layers.count { style?.getLayer(it) != null } +
                        " layers=" + style?.layers?.map { it.id },
                )
            }

            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "TrailVeil fog gesture invariant: frames=$samples " +
                            "postExitFrames=$postExitFrames " +
                            "revealedReference=${explored.report()} " +
                            "postExitWorst=[maxLuminance=$postExitMaxLuminance " +
                            "aboveThreshold=" +
                            "${"%.4f".format(java.util.Locale.US, postExitMaxRevealed * 100.0)}%] " +
                            "settled=${settled.report()} leaks=${leaks.size} " +
                            "coveredFrames=$coveredFrames ${diagnostics.get()}\n",
                    )
                },
            )
            assertTrue("The gesture sequence never left the revealed area", exited)
            assertTrue(
                "Too few rendered frames were sampled after leaving the revealed area: " +
                    "$postExitFrames",
                postExitFrames >= MINIMUM_POST_EXIT_FRAMES,
            )
            assertTrue(
                "Unexplored map was drawn unfogged during gestures: $leaks",
                leaks.isEmpty(),
            )
            // The other half of the contract: coverage came from the map itself, so the surface
            // never had to blank out the map the user was panning.
            assertEquals(
                "The safety cover was raised during a gesture",
                0,
                coveredFrames,
            )
        } finally {
            database.close()
        }
    }

    private fun dragVertically(
        x: Float,
        fromY: Float,
        toY: Float,
        steps: Int,
        stepMillis: Long,
        lift: Boolean,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, fromY, 0),
        )
        repeat(steps) { step ->
            SystemClock.sleep(stepMillis)
            val y = fromY + (toY - fromY) * (step + 1) / steps
            instrumentation.sendPointerSync(
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    0,
                ),
            )
        }
        if (lift) {
            instrumentation.sendPointerSync(
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    x,
                    toY,
                    0,
                ),
            )
        } else {
            instrumentation.sendPointerSync(
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL,
                    x,
                    toY,
                    0,
                ),
            )
        }
    }

    private fun revealTrack(database: TrailVeilDatabase, center: GeoPoint) {
        val dao = database.recordingDao()
        runBlocking {
            val recording = dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = 1_000L,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "fog-cover-test",
                ),
                initialSegment = TrackSegmentEntity(
                    sessionId = 0,
                    sequence = 0,
                    startedAt = 1_000L,
                    startReason = "SESSION_START",
                ),
            )
            repeat(REVEALED_POINT_COUNT) { index ->
                dao.appendAcceptedPoint(
                    point = TrackPointEntity(
                        sessionId = recording.sessionId,
                        segmentId = recording.segmentId,
                        sequence = index.toLong(),
                        timestamp = 1_000L + index * 5_000L,
                        latitude = center.latitude,
                        longitude = center.longitude + index * 0.0002,
                        horizontalAccuracy = 5.0,
                    ),
                    distanceDeltaMeters = 20.0,
                )
            }
        }
    }

    /**
     * Fraction of the map's own rendered frame that is bright enough to be unfogged basemap.
     * The local fallback basemap is a flat light colour, so fog at the renderer's own alpha and
     * bare basemap are far apart in luminance.
     */
    private fun MapLibreMap.renderedFogCoverage(): FogCoverage {
        val ready = CountDownLatch(1)
        val captured = AtomicReference<Bitmap?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            snapshot { bitmap ->
                captured.set(bitmap)
                ready.countDown()
            }
        }
        assertTrue(
            "MapLibre did not produce a frame snapshot",
            ready.await(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        val bitmap = requireNotNull(captured.get())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        var revealed = 0L
        var maxLuminance = 0
        pixels.forEach { pixel ->
            val luminance = (
                77 * ((pixel shr 16) and 0xff) +
                    150 * ((pixel shr 8) and 0xff) +
                    29 * (pixel and 0xff)
                ) shr 8
            if (luminance > maxLuminance) maxLuminance = luminance
            if (luminance >= UNFOGGED_LUMINANCE) revealed += 1L
        }
        return FogCoverage(
            revealedFraction = revealed.toDouble() / pixels.size.toDouble(),
            maxLuminance = maxLuminance,
            sampledPixels = pixels.size.toLong(),
        )
    }

    /**
     * Whether each pixel of the rendered map actually has fog over it, measured against the same
     * frame with the fog layers hidden.
     *
     * The absolute-luminance test this sits beside can only work on the packaged fallback style,
     * which is a flat light fill: it calls a pixel revealed at luminance 150 against a fogged
     * reference of 60. Production vector tiles are far darker — the same unfogged ocean measures
     * 18-28 — so every real leak would read as "fogged" to a fixed threshold. Comparing a pixel to
     * its own unfogged value instead makes the measurement independent of how bright the basemap
     * happens to be, and catches a one-pixel seam as readily as half a screen.
     *
     * Only valid for a settled camera: it needs two frames of the same view.
     */
    private fun MapLibreMap.auditFogCoverage(): FogAudit {
        val fogged = snapshotPixels()
        setFogLayersVisible(false)
        val bare = snapshotPixels()
        setFogLayersVisible(true)
        return compareFogCoverage(fogged, bare)
    }

    /**
     * What the audit reports when there is definitely no fog: the same bare frame compared against
     * itself. Everything the map actually draws must come back uncovered. A detector that stays
     * quiet here would stay quiet on a real leak too, and every clean sweep it produced would mean
     * nothing — which is exactly how the previous fixed-threshold measurement went wrong.
     */
    private fun MapLibreMap.auditWithFogRemoved(): FogAudit {
        setFogLayersVisible(false)
        val first = snapshotPixels()
        val second = snapshotPixels()
        setFogLayersVisible(true)
        return compareFogCoverage(first, second)
    }

    private fun compareFogCoverage(fogged: IntArray, bare: IntArray): FogAudit {
        assertEquals("Snapshot sizes differ", bare.size, fogged.size)
        var uncovered = 0L
        var drawn = 0L
        var worstRatio = 0.0
        var worstBare = 0
        bare.indices.forEach { index ->
            val bareLuminance = luminance(bare[index])
            val fogLuminance = luminance(fogged[index])
            // Nothing to reveal where the map itself draws nothing.
            if (bareLuminance <= 0) return@forEach
            drawn += 1L
            if (fogLuminance > FOG_TRANSMISSION_CEILING * bareLuminance + FOG_LUMINANCE_TOLERANCE) {
                uncovered += 1L
                val ratio = fogLuminance.toDouble() / bareLuminance.toDouble()
                if (ratio > worstRatio) {
                    worstRatio = ratio
                    worstBare = bareLuminance
                }
            }
        }
        return FogAudit(
            uncoveredFraction = uncovered.toDouble() / bare.size.toDouble(),
            drawnFraction = drawn.toDouble() / bare.size.toDouble(),
            worstRatio = worstRatio,
            worstBareLuminance = worstBare,
            sampledPixels = bare.size.toLong(),
        )
    }

    private fun MapLibreMap.setFogLayersVisible(visible: Boolean) {
        val value = if (visible) Property.VISIBLE else Property.NONE
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val style = requireNotNull(style) { "The style is not ready" }
            (
                listOf(
                    FogOverlayIds.Layer,
                    FogOverlayIds.WestRepeatLayer,
                    FogOverlayIds.EastRepeatLayer,
                ) + FogBackdropIds.Layers
                ).forEach { id ->
                style.getLayer(id)?.setProperties(PropertyFactory.visibility(value))
            }
        }
        Thread.sleep(FOG_VISIBILITY_SETTLE_MILLIS)
    }

    private fun MapLibreMap.snapshotPixels(): IntArray {
        val ready = CountDownLatch(1)
        val captured = AtomicReference<Bitmap?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            snapshot { bitmap ->
                captured.set(bitmap)
                ready.countDown()
            }
        }
        assertTrue(
            "MapLibre did not produce a frame snapshot",
            ready.await(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        val bitmap = requireNotNull(captured.get())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return pixels
    }

    private fun luminance(pixel: Int): Int = (
        77 * ((pixel shr 16) and 0xff) +
            150 * ((pixel shr 8) and 0xff) +
            29 * (pixel and 0xff)
        ) shr 8

    private data class FogAudit(
        val uncoveredFraction: Double,
        val drawnFraction: Double,
        val worstRatio: Double,
        val worstBareLuminance: Int,
        val sampledPixels: Long,
    ) {
        fun report(): String = "[uncovered=" +
            "${"%.4f".format(java.util.Locale.US, uncoveredFraction * 100.0)}% " +
            "drawn=${"%.2f".format(java.util.Locale.US, drawnFraction * 100.0)}% " +
            "worstRatio=${"%.2f".format(java.util.Locale.US, worstRatio)} " +
            "bareAtWorst=$worstBareLuminance pixels=$sampledPixels]"
    }

    private data class FogCoverage(
        val revealedFraction: Double,
        val maxLuminance: Int,
        val sampledPixels: Long,
    ) {
        fun report(): String = "[maxLuminance=$maxLuminance " +
            "aboveThreshold=${"%.4f".format(java.util.Locale.US, revealedFraction * 100.0)}% " +
            "pixels=$sampledPixels]"
    }

    private fun awaitMap(): MapLibreMap? {
        val ready = CountDownLatch(1)
        val found = AtomicReference<MapLibreMap?>(null)
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.runOnIdle { attachedMapView() } != null
        }
        val mapView = requireNotNull(composeRule.runOnIdle { attachedMapView() })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getMapAsync { map ->
                found.set(map)
                ready.countDown()
            }
        }
        ready.await(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return found.get()
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
        const val REVEALED_POINT_COUNT = 40
        const val SNAPSHOT_TIMEOUT_SECONDS = 10L
        const val UNFOGGED_LUMINANCE = 150
        const val MINIMUM_REVEALED_FRACTION = 0.01

        /**
         * A settled camera over unexplored ground should be entirely fogged. The allowance is for
         * the revealed track itself, which is sub-pixel at these zooms but can still land on one,
         * not for any part of a coverage gap — the narrowest gap the tile grid could open is a
         * quarter of the world's width.
         */
        const val MAXIMUM_SETTLED_REVEALED_FRACTION = 0.001

        /**
         * Fog transmits `(255 - fogAlpha) / 255` = 0.278 of what is under it, so a covered pixel
         * lands near 0.28 of its bare value and an uncovered one at 1.0. Half-way between is a
         * wide margin either side; the flat tolerance absorbs rounding where the map is nearly
         * black and the ratio stops being meaningful.
         */
        const val FOG_TRANSMISSION_CEILING = 0.5
        const val FOG_LUMINANCE_TOLERANCE = 4
        const val FOG_VISIBILITY_SETTLE_MILLIS = 600L

        /** The map must actually be drawing, or "no leak found" is a statement about a blank screen. */
        const val MINIMUM_DRAWN_FRACTION = 0.5

        /** With the fog hidden, nearly everything the map draws must read as uncovered. */
        const val MINIMUM_CALIBRATION_UNCOVERED_FRACTION = 0.5
        const val ZOOM_SETTLE_MILLIS = 1_200L
        const val ZOOM_TOLERANCE = 0.01
        const val LOWEST_EXACTLY_REACHABLE_ZOOM = 1.0
        val ZOOM_SWEEP = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 6.0)
        val UNEXPLORED_VIEWPOINTS = listOf(
            "atlantic" to GeoPoint(0.0, 0.0),
            // An image quad is drawn once, in one world copy, while the basemap repeats. Both
            // sides of the seam are sampled because which side leaks depends on where the tile
            // window's western edge lands.
            "antimeridian-east" to GeoPoint(0.0, 179.5),
            "antimeridian-west" to GeoPoint(0.0, -179.5),
            // Rows are clamped at the poles, so the surround has fewer tiles to work with here.
            "high-latitude" to GeoPoint(80.0, 0.0),
        )
        const val SUSTAINED_DRAG_COUNT = 6
        const val FLING_COUNT = 4
        const val FLING_SETTLE_MILLIS = 400L
        const val MINIMUM_POST_EXIT_FRAMES = 20
    }
}
