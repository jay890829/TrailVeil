package app.trailveil.map

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
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
import app.trailveil.map.fog.FogBackdropGeometry
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln
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

            // Fetching a real style, rendering its tiles and building fog from them takes longer
            // under a full suite than it does alone, and this is a wait for setup rather than a
            // budget anything is measured against.
            composeRule.waitUntil(
                timeoutMillis = if (requireOnlineStyle) ONLINE_STYLE_SETUP_MILLIS else 30_000L,
            ) { fogRendered.get() }
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

            // How far out this display itself allows, measured once rather than assumed, and
            // checked against what MapLibre's own rule predicts so that a floor added anywhere in
            // this app would show up as the measurement exceeding the prediction.
            val zoomFloor = measureZoomFloor(map, cameraRequest)
            val predictedFloor = predictedZoomFloor()
            assertTrue(
                "The camera stopped at zoom $zoomFloor when this display allows $predictedFloor, " +
                    "so something is refusing to zoom out",
                zoomFloor <= predictedFloor + ZOOM_FLOOR_PREDICTION_TOLERANCE,
            )

            val report = StringBuilder(
                "calibration=${calibration.report()} " +
                    "zoomFloor=${"%.2f".format(java.util.Locale.US, zoomFloor)} " +
                    "predictedFloor=${"%.2f".format(java.util.Locale.US, predictedFloor)} ",
            )
            var worstFraction = 0.0
            var worstLabel = "none"
            var worstOverFogged = 0.0
            var worstOverFoggedLabel = "none"
            var requestId = 100L
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
                    composeRule.waitUntil(timeoutMillis = 45_000L) {
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
                    // covering the viewport, so the floor belongs to the display rather than to
                    // this app — and comparing against that, rather than against a hard-coded 1.0,
                    // both survives a taller screen and still fails if anything of ours adds a
                    // floor of its own.
                    assertEquals(
                        "Camera settled at zoom $settledZoom, not at the zoom this display allows",
                        max(zoom, zoomFloor),
                        settledZoom,
                        ZOOM_TOLERANCE,
                    )
                    if (coverage.uncoveredFraction > worstFraction) {
                        worstFraction = coverage.uncoveredFraction
                        // With the bounds, not just the size. A strip along one edge, a seam
                        // through the middle and a corner are three different defects, and a bare
                        // percentage cannot tell them apart — localising one costs a run each time.
                        worstLabel = "$label@z$zoom ${coverage.report()}"
                    }
                    if (coverage.overFoggedFraction > worstOverFogged) {
                        worstOverFogged = coverage.overFoggedFraction
                        worstOverFoggedLabel = "$label@z$zoom ${coverage.report()}"
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
                            "worstOverFogged=$worstOverFoggedLabel " +
                            "limit=${MAXIMUM_SETTLED_REVEALED_FRACTION * 100.0}% $report\n",
                    )
                },
            )
            assertTrue(
                "Settled camera $worstLabel showed unexplored map as revealed " +
                    "(${"%.4f".format(java.util.Locale.US, worstFraction * 100.0)}%)",
                worstFraction <= MAXIMUM_SETTLED_REVEALED_FRACTION,
            )
            // Coverage can be wrong in the other direction too, and until now nothing here could
            // see it: two coats of fog over the same ground read as covered while looking like a
            // black stripe down the map.
            assertTrue(
                "Settled camera $worstOverFoggedLabel drew " +
                    "${"%.4f".format(java.util.Locale.US, worstOverFogged * 100.0)}% of the map " +
                    "under more than one coat of fog",
                worstOverFogged <= MAXIMUM_OVER_FOGGED_FRACTION,
            )
        } finally {
            database.close()
        }
    }

    /**
     * Zooming out with two fingers, which is the case the pan invariant below cannot see.
     *
     * A gesture deliberately never rebuilds the overlay — that is what keeps a pan smooth — so
     * whatever fog was installed when the fingers landed is all the coverage there is until they
     * lift. A pan cannot outrun that, because it moves the camera by a bounded amount. A zoom-out
     * can: it grows the viewport without moving the camera at all, and when the surround was
     * measured in fractions of the world rather than in screens, one pinch from zoom 4 presented
     * 46% of the screen as bare basemap.
     */
    @Test
    fun aPinchZoomOutNeverExposesUnexploredMap() = sweepGesture(
        provider = MapProviderConfiguration(
            providerName = "fog-pinch-test-provider",
            styleUri = "https://tiles.invalid/styles/fog-pinch",
        ),
        requireOnlineStyle = false,
        savedStateKey = "trailveil.map.fog-pinch-test",
        gesture = ::pinchOutInSteps,
    )

    /** The same pinch against the style that actually ships. */
    @Test
    fun aPinchZoomOutNeverExposesUnexploredMapOnTheProductionStyle() = sweepGesture(
        provider = ProductionMapProvider,
        requireOnlineStyle = true,
        savedStateKey = "trailveil.map.fog-pinch-production-test",
        gesture = ::pinchOutInSteps,
    )

    /**
     * The other way out. MapLibre zooms on a double tap held and dragged, with one finger, through
     * a different detector than the pinch — and one finger is the gesture a person makes without
     * meaning to.
     */
    @Test
    fun aQuickZoomOutNeverExposesUnexploredMap() = sweepGesture(
        provider = MapProviderConfiguration(
            providerName = "fog-quick-zoom-test-provider",
            styleUri = "https://tiles.invalid/styles/fog-quick-zoom",
        ),
        requireOnlineStyle = false,
        savedStateKey = "trailveil.map.fog-quick-zoom-test",
        gesture = ::quickZoomOutInSteps,
    )

    /**
     * Tilt the map, then zoom out. The camera sees ground the viewport's own size does not describe.
     *
     * An adversarial verifier found this and it is the reason `P4-022` failed its first round: a
     * shove to 60 degrees of pitch followed by one pinch put **14.7501%** of the screen on show as
     * explored ground — `(0,0)-(1079,1238)`, `bareAtWorst=216`, the unfogged basemap reference
     * exactly — with the safety cover never raised. The blindness was older than that task, in
     * `covers` computing an axis-aligned box from the viewport's width and height; what `P4-022` did
     * was retire the second guard that had been hiding it.
     *
     * Tilt and rotate stay enabled — they are ordinary map gestures and taking them away to make the
     * arithmetic true was the alternative — so `covers` reads the projection's own visible region
     * instead. Tilt is applied here as a programmed move rather than a two-finger shove because the
     * shove detector rejects a vertically-stacked pointer pair and the injection is fragile; what
     * matters is that the fog is rebuilt for a tilted camera before the measured gesture starts.
     */
    @Test
    fun tiltingThenZoomingOutNeverExposesUnexploredMap() =
        assertObliqueZoomOutIsCovered(tilt = 60.0, bearing = 0.0, label = "tilt")

    /**
     * The same question for bearing, which the fix claims is handled for the same reason.
     *
     * A turned camera covers a larger patch of ground with the same screen: the axis-aligned box the
     * old arithmetic built is the *inscribed* rectangle of the rotated one. Nothing in the suite
     * rotated the camera before this, so the claim that reading the projection handles rotation had
     * no evidence behind it at all — only the argument that it comes from the same call.
     */
    @Test
    fun rotatingThenZoomingOutNeverExposesUnexploredMap() =
        assertObliqueZoomOutIsCovered(tilt = 0.0, bearing = 45.0, label = "bearing")

    /** Both at once, which is the largest patch of ground a screen can be pointed at. */
    @Test
    fun rotatingAndTiltingThenZoomingOutNeverExposesUnexploredMap() =
        assertObliqueZoomOutIsCovered(tilt = 60.0, bearing = 45.0, label = "tilt+bearing")

    private fun assertObliqueZoomOutIsCovered(tilt: Double, bearing: Double, label: String) {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            revealTrack(database, REVEALED_CENTER)
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-tilt-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-tilt",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.oblique-zoom-out-$label",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = MapCameraRequest(
                        requestId = 1L,
                        point = UNEXPLORED_NEAR_REVEALED,
                        zoom = 16.0,
                    ),
                    onFogRendered = { fogRendered.set(true) },
                )
            }
            composeRule.waitUntil(timeoutMillis = 30_000L) { fogRendered.get() }
            val map = checkNotNull(awaitMap())
            composeRule.waitUntil(timeoutMillis = 30_000L) {
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes().isEmpty()
            }
            Thread.sleep(ZOOM_SETTLE_MILLIS)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isCompassEnabled = false
            }

            // Tilt as a programmed move, then let the fog rebuild for the tilted camera. Equivalent
            // to the verifier's two-finger shove and far less fragile to inject.
            composeRule.runOnUiThread {
                map.easeCamera(
                    CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder()
                            .target(map.cameraPosition.target)
                            .zoom(map.cameraPosition.zoom)
                            .tilt(tilt)
                            .bearing(bearing)
                            .build(),
                    ),
                    300,
                )
            }
            composeRule.waitUntil(timeoutMillis = 30_000L) {
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes().isEmpty()
            }
            Thread.sleep(ZOOM_SETTLE_MILLIS)

            val startCameraZoom = map.cameraPosition.zoom
            val report = StringBuilder(
                "[$label] tilt=${map.cameraPosition.tilt} bearing=${map.cameraPosition.bearing}",
            )
            var covered = 0
            // Starts below zero so that "no frame was ever measured" is distinguishable from
            // "every measured frame was clean". A verifier defeated the first version of these
            // gates by blanking the map at every audited state: they counted covered states, printed
            // them, and asserted nothing about them, so all three passed while the user saw black.
            var worst = -1.0
            var holds = 0
            longPinchOutInSteps(map) {
                holds += 1
                val isCovered = composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes().isNotEmpty()
                val zoom = map.cameraPosition.zoom
                if (isCovered) {
                    covered += 1
                    report.append(" z=${"%.2f".format(java.util.Locale.US, zoom)}:covered")
                    return@longPinchOutInSteps
                }
                val audit = map.auditFogCoverage()
                if (audit.uncoveredFraction > worst) worst = audit.uncoveredFraction
                report.append(
                    " z=${"%.2f".format(java.util.Locale.US, zoom)}:" +
                        "${"%.4f".format(java.util.Locale.US, audit.uncoveredFraction * 100)}%",
                )
            }
            val endCameraZoom = map.cameraPosition.zoom
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "TrailVeil oblique zoom-out: coveredFrames=$covered " +
                            "worst=${"%.4f".format(java.util.Locale.US, worst * 100)}% $report",
                    )
                },
            )
            assertTrue(
                "The camera never took the pose this measures, so it measured an upright map: " +
                    report,
                map.cameraPosition.tilt >= tilt - OBLIQUE_POSE_TOLERANCE &&
                    kotlin.math.abs(map.cameraPosition.bearing - bearing) <= OBLIQUE_POSE_TOLERANCE,
            )
            assertTrue("The gesture never reported a held frame: $report", holds > 0)
            assertTrue(
                "The long gesture did not cover four zoom levels " +
                    "(start=$startCameraZoom end=$endCameraZoom): $report",
                startCameraZoom - endCameraZoom >= MINIMUM_LONG_GESTURE_ZOOM_CHANGE,
            )
            assertTrue(
                "Every held frame was covered, so no coverage was measured at all: $report",
                worst >= 0.0,
            )
            // One covered frame is the accepted cost of a tilted camera at the far end of a long
            // zoom-out; a map blanked for the whole gesture is the defect `P4-008` exists to keep
            // away, and this is what tells the two apart.
            assertTrue(
                "The map was blanked for more than the last frame of the gesture: $report",
                covered <= MAXIMUM_OBLIQUE_COVERED_FRAMES,
            )
            assertTrue(
                "An oblique camera showed unexplored map as revealed: $report",
                worst <= MAXIMUM_SETTLED_REVEALED_FRACTION,
            )
        } finally {
            database.close()
        }
    }


    /**
     * The same long pinch where Mercator is most stretched, which is where it is not quite clean.
     *
     * `P4-022` retired the guard that blanked the map past 0.75 zoom levels out, on evidence
     * gathered at one place and one start zoom. Repeating it elsewhere: start zooms 18, 16 and 14
     * over Taipei and a western-hemisphere camera all held `uncovered=0.0000%` at every held frame.
     * At 78 degrees north it does not — a full-width line one pixel tall appears near the end of the
     * measured pinch, `0.0091%` then `0.0203%` at `(0,1491)-(1079,1491)`.
     *
     * It does not grow without bound, and it is not two hundred times under the bound. A verifier
     * corrected both claims: swept over latitude and pinch length it is *non-monotone* — worst
     * `0.0301%` at 78°N and 3.75 levels, back to `0.0000%` at 3.92, and `0.0000%` at 84°N and 85°N
     * where it lands on the over-fog side instead — saturating at about one full-width row. It is a
     * plus-or-minus one pixel seam whose sign flips with sub-pixel alignment. And the bound it is
     * measured against is `MAXIMUM_SETTLED_REVEALED_FRACTION` at 0.1%, so `0.0203%` is **4.9x**
     * under it, not 200x; the 200x came from comparing against a different gate for a different
     * quantity. Whose docstring, moreover, says the allowance is for the revealed track and not for
     * a coverage gap — and at this camera the track is 5,000 km away, so all of it is gap.
     *
     * Kept as a gate because no other gesture test goes near the poles, and because a hairline that
     * is allowed to grow silently is how the black band arrived in the first place.
     */
    @Test
    fun aPinchZoomOutNearThePoleNeverExposesUnexploredMap() = sweepGesture(
        provider = MapProviderConfiguration(
            providerName = "fog-pinch-pole-test-provider",
            styleUri = "https://tiles.invalid/styles/fog-pinch-pole",
        ),
        requireOnlineStyle = false,
        savedStateKey = "trailveil.map.fog-pinch-pole-test",
        gesture = ::pinchOutInSteps,
        startPoint = GeoPoint(78.0, 15.0),
        startZoom = 16.0,
        expectCover = false,
    )

    /**
     * The pinch the three tests above cannot make: from the zoom people actually explore at.
     *
     * Those start at zoom 4, where the surround is the whole world and the pixel budget never
     * binds. The clamp only applies in the clamped regime, so until this test that regime — the one
     * the budget was invented for — had no gesture evidence at all. It starts over unexplored
     * ground rather than over the track, because at this zoom a revealed track fills more of the
     * screen than the leak allowance.
     *
     * It asserts both halves now. Until `P4-022` this test accepted a covered map, because a
     * second guard blanked the screen past 0.75 zoom levels out to hide the drift of an oversized
     * quad; `P4-024` shrank the surround and the drift went with it. This gate now uses the full
     * height and asserts that the camera actually travels at least four levels, rather than treating
     * the earlier 2.86-level measurement as if it met the task. Every injected move after the scale
     * detector engages is followed by a fully rendered frame and a coverage audit while the fingers
     * remain down, rather than auditing only six settled holds. What is pinned here is that a long
     * pinch from close in neither leaks nor blanks — the complaint `P4-008` was opened for, in the
     * last case that still had it.
     */
    @Test
    fun aPinchZoomOutFromExplorationZoomNeverExposesUnexploredMap() = sweepGesture(
        provider = MapProviderConfiguration(
            providerName = "fog-pinch-close-test-provider",
            styleUri = "https://tiles.invalid/styles/fog-pinch-close",
        ),
        requireOnlineStyle = false,
        savedStateKey = "trailveil.map.fog-pinch-close-test",
        gesture = ::frameAuditedLongPinchOutInSteps,
        startPoint = UNEXPLORED_NEAR_REVEALED,
        startZoom = 16.0,
        // Neither bare nor hidden. `expectCover = false` makes the harness assert
        // `coveredFrames == 0`, so a regression that brings the blanking back fails here rather
        // than passing quietly as it did while this read `true`.
        expectCover = false,
        minimumZoomChange = MINIMUM_LONG_GESTURE_ZOOM_CHANGE,
    )

    /**
     * Zooming out across the point where the renderer starts repeating an image source by itself.
     *
     * The world's own copies have to be drawn when it does not and hidden when it does, and neither
     * belongs to the zoom the overlay was built at — a gesture changes the camera and rebuilds
     * nothing. Getting that from the build zoom put a second coat of fog over half the screen past
     * the antimeridian, reported from a device as one side of the map going completely black.
     * Only the production style can show it: the packaged fallback is a flat fill with no world
     * edge and no repetition.
     */
    @Test
    fun zoomingOutPastTheAntimeridianNeverDoubleFogsTheMap() = sweepGesture(
        provider = ProductionMapProvider,
        requireOnlineStyle = true,
        savedStateKey = "trailveil.map.fog-antimeridian-out-test",
        gesture = ::frameAuditedPinchOutInSteps,
        startPoint = ANTIMERIDIAN,
        startZoom = 1.6,
        minimumZoomChange = ANTIMERIDIAN_ZOOM_CHANGE,
    )

    /**
     * The same crossing in the other direction, which fails the other way and is the dangerous one.
     *
     * Built at a zoom where the renderer repeats by itself, the copies are hidden; zoom in past
     * that point and the renderer stops, so without them the neighbouring copy of the world is
     * drawn with no fog on it at all. Reported from a device as bare, readable basemap filling a
     * third of the screen — unexplored ground presented as explored.
     */
    @Test
    fun zoomingInPastTheAntimeridianNeverExposesUnexploredMap() = sweepGesture(
        provider = ProductionMapProvider,
        requireOnlineStyle = true,
        savedStateKey = "trailveil.map.fog-antimeridian-in-test",
        gesture = ::frameAuditedQuickZoomInInSteps,
        startPoint = ANTIMERIDIAN,
        startZoom = 0.0,
        expectZoomOut = false,
        expectZoomIn = true,
        minimumZoomChange = ANTIMERIDIAN_ZOOM_CHANGE,
    )

    /**
     * The same crossing again, started from a zoom where it is the *bands* that carry the world
     * copies rather than the mosaic.
     *
     * Below render zoom 2 the mosaic spans a world and is copied itself; above it, it does not, and
     * a pair of flat band quads carry the fog into the neighbouring copies instead. The test above
     * starts at 1.6 and therefore never installs that pair at all — which is how half of this
     * defect survived a fix that measured clean, and was reported from a device a second time.
     */
    @Test
    fun zoomingOutFromAnExplorationZoomPastTheAntimeridianNeverDoubleFogsTheMap() = sweepGesture(
        provider = ProductionMapProvider,
        requireOnlineStyle = true,
        savedStateKey = "trailveil.map.fog-antimeridian-bands-test",
        gesture = ::pinchOutInSteps,
        startPoint = ANTIMERIDIAN,
        startZoom = 3.0,
        minimumZoomChange = ANTIMERIDIAN_ZOOM_CHANGE,
    )

    /**
     * The same zoom-out with no world edge anywhere near it.
     *
     * Every test above that could see a second coat of fog was placed at the antimeridian, so the
     * defect read as an antimeridian defect for two rounds of fixes. It was not: the far side band
     * lies past the world's edge whenever the mosaic sits far enough east or west, and Taipei is
     * far enough. Measured at 5.38% of the screen here before the fix.
     */
    @Test
    fun zoomingOutFromAnExplorationZoomNeverDoubleFogsTheMap() = sweepGesture(
        provider = ProductionMapProvider,
        requireOnlineStyle = true,
        savedStateKey = "trailveil.map.fog-ordinary-bands-test",
        gesture = ::pinchOutInSteps,
        startPoint = REVEALED_CENTER,
        startZoom = 3.0,
        minimumZoomChange = ANTIMERIDIAN_ZOOM_CHANGE,
    )

    /**
     * The side bands come back after the band that replaced them is removed.
     *
     * The wrapped band exists only where the surround spans a world, and replaces the two side
     * bands only where the renderer repeats by itself. Crossing camera zoom 1 hides the side bands;
     * the rebuild that follows removes the wrapped band, because at that zoom the mosaic spans the
     * world and there is nothing beside it to cover. If nothing turns the side bands back on, every
     * later zoom draws the map with no cover beside the mosaic at all — measured by an independent
     * verifier at 89.3519% of the screen bare with the safety cover down, at every zoom afterwards
     * and until the style is reloaded.
     *
     * Asserted on the layers rather than on pixels because it is a state that persists: a pixel
     * test would have to fling several screen-widths without ever letting the camera idle, while
     * the state itself is wrong the moment the rebuild lands.
     *
     * What this pins is that *an* arrangement is drawn, never *which* — a verifier defeated it by
     * making the wrapped band never chosen, which is internally consistent and is exactly the
     * behaviour P4-024 was opened for. Which arrangement is right is pinned by
     * [noSingleFogQuadIsDrawnMoreThanOnce], which reports 8.2455% against that same change, and by
     * `exactlyOneArrangementOfTheGroundBesideTheMosaicIsEverDrawn` in the JVM suite. Read the three
     * together; none of them is sufficient alone.
     */
    @Test
    fun theSideBandsComeBackWhenTheWrappedBandIsRemoved() {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            val fogFailure = AtomicReference<Throwable?>(null)
            revealTrack(database, REVEALED_CENTER)
            val cameraRequest = mutableStateOf(
                MapCameraRequest(requestId = 1L, point = REVEALED_CENTER, zoom = 3.0),
            )
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-side-band-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-side-band",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.fog-side-band-test",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = cameraRequest.value,
                    onFogRendered = { fogRendered.set(true) },
                    onFogFailure = { failure -> fogFailure.set(failure) },
                )
            }
            composeRule.waitUntil(timeoutMillis = 30_000L) {
                fogRendered.get() || fogFailure.get() != null
            }
            fogFailure.get()?.let { failure ->
                throw AssertionError("The initial side-band fog install failed", failure)
            }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }

            val trace = StringBuilder()
            fun settleAt(zoom: Double, requestId: Long) {
                composeRule.runOnUiThread {
                    cameraRequest.value = MapCameraRequest(
                        requestId = requestId,
                        point = REVEALED_CENTER,
                        zoom = zoom,
                    )
                }
                composeRule.waitUntil(timeoutMillis = 30_000L) {
                    composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                        .fetchSemanticsNodes()
                        .isEmpty()
                }
                Thread.sleep(ZOOM_SETTLE_MILLIS)
            }

            fun assertGroundBesideTheMosaicIsCoveredOnce(label: String) {
                val westDrawn = map.fogLayerIsRendered(FogBackdropIds.WestLayer)
                val eastDrawn = map.fogLayerIsRendered(FogBackdropIds.EastLayer)
                val wrappedDrawn = map.fogLayerIsRendered(FogBackdropIds.WrappedSideLayer)
                trace.append(
                    "\n $label west=$westDrawn east=$eastDrawn wrapped=$wrappedDrawn",
                )
                val sidesDrawn = westDrawn
                assertEquals(
                    "$label: the side bands disagree with each other",
                    westDrawn,
                    eastDrawn,
                )
                assertTrue(
                    "$label: the ground beside the mosaic is covered " +
                        (if (wrappedDrawn) "twice" else "not at all") +
                        " (west=$westDrawn east=$eastDrawn wrapped=$wrappedDrawn)",
                    sidesDrawn != wrappedDrawn,
                )
            }

            settleAt(3.0, 2L)
            assertGroundBesideTheMosaicIsCoveredOnce("at zoom 3, before crossing")
            // Below the zoom where the renderer repeats by itself: the wrapped band takes over, and
            // the rebuild that follows removes it again.
            settleAt(0.8, 3L)
            assertGroundBesideTheMosaicIsCoveredOnce("at zoom 0.8, past the repetition edge")
            settleAt(8.0, 4L)
            assertGroundBesideTheMosaicIsCoveredOnce("back at zoom 8")

            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString("stream", "TrailVeil side bands:$trace\n") },
            )
        } finally {
            database.close()
        }
    }

    /**
     * A settled camera at every zoom a user passes through on the way out, with nothing moving.
     *
     * The defect this exists for needed no gesture at all: at render zooms 12 and 14 the south band
     * was drawn 50 and 73 screen pixels away from where its coordinates put it, over the mosaic,
     * as a full-width black band along the bottom of the map. It is a property of how large the
     * surround quad is, so it appeared at the two zooms where the surround is at its size limit and
     * nowhere else — which is why zoom had to be swept rather than sampled, and why the frames are
     * settled: every gesture test in this file stops measuring at 0.75 levels out, where the safety
     * cover goes up, and the band lives past that.
     */
    @Test
    fun noSettledZoomDrawsASeamOverTheMap() {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            revealTrack(database, REVEALED_CENTER)
            val cameraRequest = mutableStateOf(
                MapCameraRequest(requestId = 1L, point = REVEALED_CENTER, zoom = 16.0),
            )
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = ProductionMapProvider,
                    fallbackTimeoutMillis = 20_000L,
                    savedStateKey = "trailveil.map.fog-seam-probe",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = cameraRequest.value,
                    onFogRendered = { fogRendered.set(true) },
                )
            }
            composeRule.waitUntil(timeoutMillis = ONLINE_STYLE_SETUP_MILLIS) { fogRendered.get() }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }
            val loadState = composeRule.runOnIdle {
                attachedMapView()?.getTag(R.id.map_basemap_load_state)
            }
            Assume.assumeTrue("style=$loadState", loadState == BasemapLoadState.ONLINE.name)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isCompassEnabled = false
            }
            val report = StringBuilder()
            var requestId = 1L
            var worst = 0.0
            var worstReport = "none"
            SETTLED_SEAM_ZOOMS.forEach { zoom ->
                requestId += 1L
                composeRule.runOnUiThread {
                    cameraRequest.value = MapCameraRequest(
                        requestId = requestId,
                        point = REVEALED_CENTER,
                        zoom = zoom,
                    )
                }
                composeRule.waitUntil(timeoutMillis = 45_000L) {
                    composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                        .fetchSemanticsNodes()
                        .isEmpty()
                }
                Thread.sleep(ZOOM_SETTLE_MILLIS)
                val visibility = ALL_FOG_LAYERS.associateWith { map.fogLayerVisibility(it) }
                val renderedLayers = ALL_FOG_LAYERS.filter { map.fogLayerIsRendered(it) }
                val audit = map.auditFogCoverage()
                report.append(
                    "\n z=${"%.2f".format(java.util.Locale.US, map.cameraPosition.zoom)} " +
                        audit.report(),
                )
                if (audit.overFoggedFraction > worst) {
                    worst = audit.overFoggedFraction
                    worstReport = "at zoom $zoom: ${audit.report()}"
                }
                // Only when something is wrong, and only to say what: which quad is the second
                // coat, and where each one really is. Localising this by hand cost a run apiece.
                if (audit.overFoggedFraction > MAXIMUM_SETTLED_SEAM_FRACTION) {
                    map.setFogLayersVisible(false)
                    val bare = map.snapshotStableBarePixels("settled seam diagnostic")
                    renderedLayers.forEach { id ->
                        map.setSingleFogLayerVisible(id, true)
                        val only = compareFogCoverage(map.snapshotPixels(), bare, snapshotWidth())
                        map.setSingleFogLayerVisible(id, false)
                        report.append("\n  only ${id.removePrefix("trailveil-")} -> ")
                            .append(only.report())
                    }
                    map.restoreFogLayerVisibility(visibility)
                    renderedLayers.forEach { id ->
                        map.setSingleFogLayerVisible(id, false)
                        val without =
                            compareFogCoverage(map.snapshotPixels(), bare, snapshotWidth())
                        map.setSingleFogLayerVisible(id, true)
                        report.append("\n  without ${id.removePrefix("trailveil-")} -> ")
                            .append(without.report())
                    }
                    map.restoreFogLayerVisibility(visibility)
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString("stream", "TrailVeil settled seam sweep:$report\n") },
            )
            assertTrue(
                "A settled camera drew part of the map under more than one coat of fog $worstReport",
                worst <= MAXIMUM_SETTLED_SEAM_FRACTION,
            )
        } finally {
            database.close()
        }
    }

    /**
     * No single fog quad may be drawn more than once.
     *
     * This is the cause behind the black block a user saw while zooming out, stated as the rule it
     * broke rather than as the symptom. Below the zoom where the renderer repeats an image source
     * across world copies by itself, a quad lying *entirely* past the world's edge is drawn twice —
     * at its own coordinates and again where the repetition puts it, on the same pixels. Two coats
     * of fog transmit 0.077 of the basemap instead of 0.278, which reads as black.
     *
     * Measured by drawing each quad with every other fog layer hidden, so nothing else can be the
     * second coat. Before the fix the east band alone reported 5.31% of the screen over-fogged at
     * an ordinary place and 8.25% past the antimeridian, while every other quad reported none: the
     * east band was the only one whose centre lay outside the world. A coverage test cannot see
     * this at all — over-fogged map still counts as covered — which is why it survived two rounds
     * of fixes that measured clean.
     */
    @Test
    fun noSingleFogQuadIsDrawnMoreThanOnce() =
        assertNoFogQuadIsDoubled(ANTIMERIDIAN, "antimeridian")

    @Test
    fun noSingleFogQuadIsDrawnMoreThanOnceAtAnOrdinaryPlace() =
        assertNoFogQuadIsDoubled(REVEALED_CENTER, "ordinary")

    private fun assertNoFogQuadIsDoubled(startPoint: GeoPoint, label: String) {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            revealTrack(database, REVEALED_CENTER)
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = ProductionMapProvider,
                    fallbackTimeoutMillis = 20_000L,
                    savedStateKey = "trailveil.map.fog-single-quad-$label",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = MapCameraRequest(
                        requestId = 1L,
                        point = startPoint,
                        zoom = 3.0,
                    ),
                    onFogRendered = { fogRendered.set(true) },
                )
            }
            composeRule.waitUntil(timeoutMillis = ONLINE_STYLE_SETUP_MILLIS) { fogRendered.get() }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }
            val loadState = composeRule.runOnIdle {
                attachedMapView()?.getTag(R.id.map_basemap_load_state)
            }
            Assume.assumeTrue(
                "The production style did not load (state=$loadState)",
                loadState == BasemapLoadState.ONLINE.name,
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isCompassEnabled = false
            }
            composeRule.waitUntil(timeoutMillis = 25_000L) {
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            Thread.sleep(ZOOM_SETTLE_MILLIS)
            composeRule.waitUntil(timeoutMillis = 25_000L) { fogGeneration() != null }

            val view = requireNotNull(composeRule.runOnIdle { attachedMapView() })
            val report = StringBuilder("viewport=${view.width}x${view.height}")
            var doubled: String? = null
            var isolations = 0
            pinchOutInSteps(map) {
                if (doubled != null) return@pinchOutInSteps
                val covered = composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
                val zoom = map.cameraPosition.zoom
                if (covered) {
                    report.append("\n z=${"%.2f".format(java.util.Locale.US, zoom)} covered")
                    return@pinchOutInSteps
                }
                // Read before anything is hidden: which quads are drawn depends on the camera, so
                // a reading taken afterwards would describe the harness rather than the app.
                val visibility = ALL_FOG_LAYERS.associateWith { map.fogLayerVisibility(it) }
                val renderedLayers = ALL_FOG_LAYERS.filter { map.fogLayerIsRendered(it) }
                report.append("\n z=${"%.2f".format(java.util.Locale.US, zoom)}")
                map.setFogLayersVisible(false)
                renderedLayers.forEach { id ->
                    val only = map.measureQuadAlone(id)
                    isolations += 1
                    report.append("\n  only ${id.removePrefix("trailveil-")} -> ")
                        .append(only.report())
                    if (only.overFoggedFraction <= MAXIMUM_SINGLE_QUAD_OVER_FOGGED_FRACTION) {
                        return@forEach
                    }
                    // Looked at twice before it is believed. A production basemap is still
                    // streaming at these zooms, and a tile or label that lands between the two
                    // frames of a pair reads as a change in coverage that no quad caused: the same
                    // dark box at (939,943)-(1054,1037) was attributed to the wrapped side band in
                    // one run and to the south band in another, at the same camera zoom, which is
                    // not something a quad drawn twice can do. A quad really drawn twice is
                    // deterministic and survives a second look; this does not.
                    val again = map.measureQuadAlone(id)
                    report.append(" | again ").append(again.report())
                    if (
                        doubled == null &&
                        again.overFoggedFraction > MAXIMUM_SINGLE_QUAD_OVER_FOGGED_FRACTION
                    ) {
                        doubled = "$id at zoom $zoom: ${only.report()} then ${again.report()}"
                    }
                }
                map.restoreFogLayerVisibility(visibility)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString("stream", "TrailVeil single-quad coats [$label]:$report\n")
                },
            )
            assertTrue(
                "No quad was ever drawn on its own, so this measured nothing",
                isolations > 0,
            )
            assertTrue(
                "A single fog quad was drawn more than once: $doubled",
                doubled == null,
            )
        } finally {
            database.close()
        }
    }

    /**
     * One quad drawn with every other fog layer hidden, against a bare frame taken next to it.
     *
     * Called with every fog layer already hidden, and leaves them that way. The bare reference is
     * re-taken for each quad rather than once for the sweep: a single reference goes stale by ten
     * seconds over seven layers, and a basemap that is still loading changes underneath it.
     */
    private fun MapLibreMap.measureQuadAlone(id: String): FogAudit {
        val bare = snapshotStableBarePixels("single-quad reference for $id")
        setSingleFogLayerVisible(id, true)
        val only = snapshotPixels()
        setSingleFogLayerVisible(id, false)
        return compareFogCoverage(only, bare, snapshotWidth())
    }

    private fun MapLibreMap.fogLayerVisibility(id: String): String? {
        val captured = AtomicReference<String?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            captured.set(style?.getLayer(id)?.visibility?.value)
        }
        return captured.get()
    }

    /** What the renderer selects after visibility and the installed camera-zoom opacity step. */
    private fun MapLibreMap.fogLayerIsRendered(id: String): Boolean {
        val captured = AtomicBoolean(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val layer = style?.getLayer(id)
            val zoom = cameraPosition.zoom
            val hasWrappedBand = style?.getLayer(FogBackdropIds.WrappedSideLayer) != null
            val zoomOpacityIsVisible = when (id) {
                FogOverlayIds.WestRepeatLayer,
                FogOverlayIds.EastRepeatLayer,
                FogBackdropIds.WestWorldLayer,
                FogBackdropIds.EastWorldLayer,
                -> zoom >= WORLD_COPY_RENDER_EDGE_ZOOM

                FogBackdropIds.WrappedSideLayer -> zoom < WORLD_COPY_RENDER_EDGE_ZOOM
                FogBackdropIds.WestLayer,
                FogBackdropIds.EastLayer,
                -> !hasWrappedBand || zoom >= WORLD_COPY_RENDER_EDGE_ZOOM

                else -> true
            }
            captured.set(
                layer != null &&
                    layer.visibility.value != Property.NONE &&
                    zoomOpacityIsVisible,
            )
        }
        return captured.get()
    }

    private fun MapLibreMap.restoreFogLayerVisibility(visibility: Map<String, String?>) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val style = requireNotNull(style) { "The style is not ready" }
            visibility.forEach { (id, value) ->
                if (value != null) {
                    style.getLayer(id)?.setProperties(PropertyFactory.visibility(value))
                }
            }
        }
        Thread.sleep(FOG_VISIBILITY_SETTLE_MILLIS)
    }

    private fun MapLibreMap.setSingleFogLayerVisible(id: String, visible: Boolean) {
        val value = if (visible) Property.VISIBLE else Property.NONE
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            style?.getLayer(id)?.setProperties(PropertyFactory.visibility(value))
        }
        Thread.sleep(FOG_VISIBILITY_SETTLE_MILLIS)
    }

    /**
     * The two settled cameras either side of the zoom where the renderer starts repeating an image
     * source by itself, which is the edge the world copies are switched on at.
     *
     * Both sides fail totally when the switch is in the wrong place, in opposite directions, so
     * both sides are asserted. Swept in steps of 0.02 to find it: 0.98 with the copies on is
     * 50.000% black, 1.00 with them off leaks 49.722%, and the edge is exactly the integer. A user
     * saw the black band twice, once for each of two values that were inferred rather than
     * measured.
     */
    @Test
    fun theWorldCopyEdgeIsCorrectOnBothSides() {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            revealTrack(database, REVEALED_CENTER)
            val cameraRequest = mutableStateOf(
                MapCameraRequest(requestId = 1L, point = ANTIMERIDIAN, zoom = 2.0),
            )

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = ProductionMapProvider,
                    fallbackTimeoutMillis = 20_000L,
                    savedStateKey = "trailveil.map.fog-world-copy-edge",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = cameraRequest.value,
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = ONLINE_STYLE_SETUP_MILLIS) { fogRendered.get() }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }
            val loadState = composeRule.runOnIdle {
                attachedMapView()?.getTag(R.id.map_basemap_load_state)
            }
            Assume.assumeTrue(
                "The production style did not load (state=$loadState); skipping rather than " +
                    "reporting a fallback-style result as production",
                loadState == BasemapLoadState.ONLINE.name,
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isCompassEnabled = false
            }

            val report = StringBuilder()
            var requestId = 100L
            WORLD_COPY_EDGE_ZOOMS.forEach { zoom ->
                requestId += 1L
                composeRule.runOnUiThread {
                    cameraRequest.value = MapCameraRequest(
                        requestId = requestId,
                        point = ANTIMERIDIAN,
                        zoom = zoom,
                    )
                }
                composeRule.waitUntil(timeoutMillis = 45_000L) {
                    composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                        .fetchSemanticsNodes()
                        .isEmpty()
                }
                Thread.sleep(ZOOM_SETTLE_MILLIS)
                val settled = map.cameraPosition.zoom
                val audit = map.auditFogCoverage()
                report.append(
                    " z=${"%.2f".format(java.util.Locale.US, settled)}=${audit.report()}",
                )
                assertTrue(
                    "At zoom $settled the map was left bare past the world edge: ${audit.report()}",
                    audit.uncoveredFraction <= MAXIMUM_SETTLED_REVEALED_FRACTION,
                )
                assertTrue(
                    "At zoom $settled part of the map was under more than one coat of fog: " +
                        audit.report(),
                    audit.overFoggedFraction <= MAXIMUM_OVER_FOGGED_FRACTION,
                )
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString("stream", "TrailVeil world-copy edge:$report\n")
                },
            )
        } finally {
            database.close()
        }
    }

    /**
     * A pan at the zoom where the whole world is nearly on screen, which is where an adversarial
     * verifier found the defect every test here had missed.
     *
     * The surround was a square centred on the mosaic and *trimmed* by the world edge, so a mosaic
     * off the equator lost a quarter of its height with nothing over that strip. Reproduced here
     * before the fix at 22.04% of the screen shown as bare basemap, growing 0 → 2.71 → 12.42 →
     * 22.04% across one drag, with the camera zoom never changing — a pan, not a zoom.
     */
    @Test
    fun aPanAtWorldZoomNeverExposesUnexploredMap() = sweepGesture(
        provider = MapProviderConfiguration(
            providerName = "fog-world-pan-test-provider",
            styleUri = "https://tiles.invalid/styles/fog-world-pan",
        ),
        requireOnlineStyle = false,
        savedStateKey = "trailveil.map.fog-world-pan-test",
        gesture = ::panInSteps,
        startPoint = GeoPoint(0.0, 121.5654),
        startZoom = 2.0,
        expectZoomOut = false,
    )

    /**
     * The other defect from the same verification, which fails in the opposite direction: the live
     * camera check measured the viewport against one world when the installed coverage is three
     * worlds wide, so an ordinary drag at zoom 1 from anywhere far from the prime meridian raised
     * the safety cover over a map that was correctly and completely fogged. Taipei is 121° out,
     * which was enough.
     */
    @Test
    fun aPanAtWorldZoomNeverHidesAFullyFoggedMap() = sweepGesture(
        provider = MapProviderConfiguration(
            providerName = "fog-world-pan-far-test-provider",
            styleUri = "https://tiles.invalid/styles/fog-world-pan-far",
        ),
        requireOnlineStyle = false,
        savedStateKey = "trailveil.map.fog-world-pan-far-test",
        gesture = ::panInSteps,
        startPoint = GeoPoint(25.0330, 121.5654),
        startZoom = 1.0,
        expectZoomOut = false,
    )

    /**
     * Measures coverage *during* a zoom-out, with the fingers still down.
     *
     * The settled sweep compares each pixel with the same pixel unfogged, which needs two frames of
     * one view and so cannot be taken from a moving camera. A gesture does not have to be moving to
     * be a gesture, though: the zoom is driven in steps and the fingers are held between them, so
     * the same comparison works while the overlay is still exactly the one installed before the
     * gesture began. That the overlay really is that one is asserted rather than assumed — the
     * published fog generation must not change from the first touch to the last.
     */
    private fun sweepGesture(
        provider: MapProviderConfiguration,
        requireOnlineStyle: Boolean,
        savedStateKey: String,
        gesture: (map: MapLibreMap, onHold: () -> Unit) -> Unit,
        startPoint: GeoPoint = REVEALED_CENTER,
        startZoom: Double = GESTURE_START_ZOOM,
        expectZoomOut: Boolean = true,
        expectCover: Boolean = false,
        expectZoomIn: Boolean = false,
        minimumZoomChange: Double = MINIMUM_GESTURE_ZOOM_CHANGE,
    ) {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            revealTrack(database, REVEALED_CENTER)

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = provider,
                    fallbackTimeoutMillis = if (requireOnlineStyle) 20_000L else 100L,
                    savedStateKey = savedStateKey,
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = MapCameraRequest(
                        requestId = 1L,
                        point = startPoint,
                        zoom = startZoom,
                    ),
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            // Fetching a real style, rendering its tiles and building fog from them takes longer
            // under a full suite than it does alone, and this is a wait for setup rather than a
            // budget anything is measured against.
            composeRule.waitUntil(
                timeoutMillis = if (requireOnlineStyle) ONLINE_STYLE_SETUP_MILLIS else 30_000L,
            ) { fogRendered.get() }
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
            composeRule.waitUntil(timeoutMillis = 25_000L) {
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            Thread.sleep(ZOOM_SETTLE_MILLIS)

            // Same calibration the settled sweep runs, for the same reason: a detector that cannot
            // see a leak here would report every audit of the gesture as covered.
            val calibration = map.auditWithFogRemoved()
            assertTrue(
                "The map drew almost nothing, so this would pass vacuously: " +
                    calibration.report(),
                calibration.drawnFraction >= MINIMUM_DRAWN_FRACTION,
            )
            assertTrue(
                "With the fog layers hidden the audit still reported the map as covered: " +
                    calibration.report(),
                calibration.uncoveredFraction >= MINIMUM_CALIBRATION_UNCOVERED_FRACTION,
            )

            // Canonical fog has to be installed before the fingers land, or the gesture would be
            // measured against a placeholder and the generation check would compare with nothing.
            composeRule.waitUntil(timeoutMillis = 25_000L) { fogGeneration() != null }
            val startCameraZoom = map.cameraPosition.zoom
            val startTarget = map.cameraPosition.target
            val generationAtTouchDown = fogGeneration()
            // What MapLibre made of the injected touches. Without this the test could report a
            // clean gesture that the map never actually received as one.
            val moveReasons = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val reasonListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                moveReasons += reason
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                map.addOnCameraMoveStartedListener(reasonListener)
            }
            var coveredFrames = 0
            var worstFraction = -1.0
            var worstReport = "none"
            var worstOverFogged = 0.0
            var worstOverFoggedReport = "none"
            var worstZoom = startCameraZoom
            var holds = 0
            val generations = mutableListOf<Any?>()
            val trace = StringBuilder()

            gesture(map) {
                holds += 1
                generations += fogGeneration()
                val covered = composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
                if (covered) coveredFrames += 1
                val zoom = map.cameraPosition.zoom
                // A covered frame shows the user nothing, and the snapshot is of the map surface
                // underneath the cover, so auditing it would measure something nobody can see.
                if (covered) {
                    trace.append("z=${"%.2f".format(java.util.Locale.US, zoom)}:covered ")
                    return@gesture
                }
                val audit = map.auditFogCoverage()
                trace.append("z=${"%.2f".format(java.util.Locale.US, zoom)}:")
                    .append("${"%.4f".format(java.util.Locale.US, audit.uncoveredFraction * 100.0)}%")
                    .append("/${"%.4f".format(java.util.Locale.US, audit.overFoggedFraction * 100.0)}% ")
                if (audit.overFoggedFraction > worstOverFogged) {
                    worstOverFogged = audit.overFoggedFraction
                    worstOverFoggedReport = audit.report()
                }
                if (audit.uncoveredFraction > worstFraction) {
                    worstFraction = audit.uncoveredFraction
                    worstReport = audit.report()
                    worstZoom = zoom
                }
            }
            val endZoom = map.cameraPosition.zoom
            val endTarget = map.cameraPosition.target
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                map.removeOnCameraMoveStartedListener(reasonListener)
            }
            assertTrue("The gesture never reported a held frame", holds > 0)
            assertTrue(
                "Every held frame was covered, so no coverage was measured at all",
                expectCover || worstFraction >= 0.0,
            )

            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "TrailVeil zoom-out gesture invariant [${provider.providerName}]: " +
                            "startZoom=${"%.2f".format(java.util.Locale.US, startCameraZoom)} " +
                            "endZoom=${"%.2f".format(java.util.Locale.US, endZoom)} holds=$holds " +
                            "worst=$worstReport worstOverFogged=$worstOverFoggedReport " +
                            "atZoom=${"%.2f".format(java.util.Locale.US, worstZoom)} " +
                            "calibration=${calibration.report()} " +
                            "generationBeforeAttempts=$generationAtTouchDown " +
                            "measuredGeneration=${generations.firstOrNull()} held=$generations " +
                            "moveReasons=$moveReasons " +
                            "coveredFrames=$coveredFrames trace=[$trace]\n",
                    )
                },
            )
            assertTrue(
                "MapLibre never saw the injected touches as a gesture (reasons=$moveReasons)",
                moveReasons.contains(
                    MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE,
                ),
            )
            if (expectZoomIn) {
                assertTrue(
                    "The gesture did not zoom in, so this measured nothing " +
                        "(start=$startCameraZoom end=$endZoom)",
                    endZoom - startCameraZoom >= minimumZoomChange,
                )
            } else if (expectZoomOut) {
                assertTrue(
                    "The gesture did not zoom out, so this measured nothing " +
                        "(start=$startCameraZoom end=$endZoom)",
                    startCameraZoom - endZoom >= minimumZoomChange,
                )
            } else {
                val moved = endTarget != null && startTarget != null &&
                    (
                        kotlin.math.abs(endTarget.latitude - startTarget.latitude) +
                            kotlin.math.abs(endTarget.longitude - startTarget.longitude)
                        ) > MINIMUM_PAN_DEGREES
                assertTrue(
                    "The gesture did not move the camera, so this measured nothing " +
                        "($startTarget -> $endTarget)",
                    moved,
                )
            }
            // Without this the measurement could be of an overlay rebuilt mid-gesture, which is
            // not the thing under test — the whole point is that a gesture gets no rebuild.
            // Compared against the first *held* frame rather than against touch-down: a pinch that
            // fails to engage is lifted and made again, and the abandoned attempt ends in a camera
            // idle that legitimately rebuilds the fog before the measured attempt starts.
            assertNotNull(
                "Fog was not loaded when the measured frames were taken",
                generations.firstOrNull(),
            )
            assertTrue(
                "The fog was rebuilt during the gesture, so nothing measured here is about what a " +
                    "gesture is given: $generations (touch-down was $generationAtTouchDown)",
                generations.all { it == generations.first() },
            )
            assertTrue(
                "A zoom-out gesture presented unexplored map as revealed at zoom $worstZoom: " +
                    worstReport,
                worstFraction <= MAXIMUM_SETTLED_REVEALED_FRACTION,
            )
            // A gesture can get coverage wrong in the other direction too, and until this was
            // measured it did: crossing the zoom where the renderer starts repeating an image
            // source by itself put a second coat of fog over half the screen.
            assertTrue(
                "A gesture drew part of the map under more than one coat of fog: " +
                    worstOverFoggedReport,
                worstOverFogged <= MAXIMUM_OVER_FOGGED_FRACTION,
            )
            if (expectCover) {
                // The other half of the contract in the regime where the surround is clamped: the
                // map is hidden rather than allowed to leak, and the guard really does fire.
                assertTrue(
                    "The gesture left the surround behind and nothing covered the map",
                    coveredFrames > 0,
                )
            } else {
                assertEquals(
                    "The safety cover was raised during a gesture",
                    0,
                    coveredFrames,
                )
            }
        } finally {
            database.close()
        }
    }

    /**
     * Two fingers drawn together at the centre of the screen, held still after every step so the
     * camera can be read against itself without ever letting go of the map.
     */
    private fun pinchOutInSteps(map: MapLibreMap, onHold: () -> Unit) =
        pinchInSteps(map, onHold, zoomIn = false)

    /** Zoom-1 transition gate: one fully rendered coverage audit after every pointer move. */
    private fun frameAuditedPinchOutInSteps(map: MapLibreMap, onHold: () -> Unit) =
        pinchInSteps(map, onHold, zoomIn = false, auditEveryMove = true)

    /**
     * A pinch that uses the whole screen rather than its shorter edge.
     *
     * The ordinary driver measures its span against `min(width, height)`, which on a portrait phone
     * is the *width* — so it under-travels a real vertical pinch by about a zoom level. A verifier
     * found the tilt leak at 2.9 and 3.9 levels out, past where the ordinary driver stops, so a gate
     * for it has to reach at least that far.
     */
    private fun longPinchOutInSteps(map: MapLibreMap, onHold: () -> Unit) =
        pinchInSteps(map, onHold, zoomIn = false, spanEdge = PinchSpanEdge.TALLEST)

    /** The acceptance pinch: the same single stream, audited after every move-induced frame. */
    private fun frameAuditedLongPinchOutInSteps(map: MapLibreMap, onHold: () -> Unit) =
        pinchInSteps(
            map,
            onHold,
            zoomIn = false,
            spanEdge = PinchSpanEdge.TALLEST,
            auditEveryMove = true,
        )

    private enum class PinchSpanEdge { SHORTEST, TALLEST }

    private fun pinchInSteps(
        map: MapLibreMap,
        onHold: () -> Unit,
        zoomIn: Boolean,
        spanEdge: PinchSpanEdge = PinchSpanEdge.SHORTEST,
        auditEveryMove: Boolean = false,
    ) {
        repeat(PINCH_ATTEMPTS) { attempt ->
            if (pinchOnce(map, onHold, zoomIn, spanEdge, auditEveryMove)) return
        }
        // Every assertion downstream would still be sound, but reporting nothing measured is more
        // useful than reporting a clean gesture that never happened.
        throw AssertionError("The pinch never engaged MapLibre's scale detector in $PINCH_ATTEMPTS attempts")
    }

    /** One pinch. Returns whether it actually zoomed, having run [onHold] at each held step. */
    private fun pinchOnce(
        map: MapLibreMap,
        onHold: () -> Unit,
        zoomIn: Boolean,
        spanEdge: PinchSpanEdge = PinchSpanEdge.SHORTEST,
        auditEveryMove: Boolean = false,
    ): Boolean {
        val view = requireNotNull(composeRule.runOnIdle { attachedMapView() })
        val centerX = view.width / 2f
        val centerY = view.height / 2f
        val downTime = SystemClock.uptimeMillis()
        val shorterEdge = when (spanEdge) {
            PinchSpanEdge.SHORTEST -> minOf(view.width, view.height)
            // The pointers separate along Y, so a vertical pinch is bounded by the height. Kept
            // just inside it so both pointers stay on screen for the whole travel.
            PinchSpanEdge.TALLEST -> (view.height * TALL_PINCH_EDGE_FRACTION).toInt()
        }
        // Fingers apart then together zooms out; together then apart zooms in.
        val openSpanFraction = when (spanEdge) {
            PinchSpanEdge.SHORTEST -> PINCH_START_SPAN_FRACTION
            PinchSpanEdge.TALLEST -> LONG_PINCH_START_SPAN_FRACTION
        }
        val closeSpanFraction = when (spanEdge) {
            PinchSpanEdge.SHORTEST -> PINCH_END_SPAN_FRACTION
            PinchSpanEdge.TALLEST -> LONG_PINCH_END_SPAN_FRACTION
        }
        val startSpan = shorterEdge *
            if (zoomIn) closeSpanFraction else openSpanFraction
        val endSpan = shorterEdge *
            if (zoomIn) openSpanFraction else closeSpanFraction
        val zoomAtTouchDown = map.cameraPosition.zoom

        // Every event in the stream is built the same way, including the first and the last. A
        // pointer whose tool type or precision changes part way through is a different pointer as
        // far as the input pipeline is concerned, and the gesture never begins.
        fun send(action: Int, pointerCount: Int, span: Float, eventTime: Long) {
            val properties = Array(pointerCount) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coordinates = Array(pointerCount) { index ->
                MotionEvent.PointerCoords().apply {
                    x = centerX
                    y = if (index == 0) centerY - span / 2f else centerY + span / 2f
                    pressure = 1f
                    size = 1f
                }
            }
            injectTouch(
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    pointerCount,
                    properties,
                    coordinates,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                ),
            )
        }

        send(MotionEvent.ACTION_DOWN, 1, startSpan, downTime)
        send(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            startSpan,
            SystemClock.uptimeMillis(),
        )
        fun lift(span: Float) {
            send(
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
                span,
                SystemClock.uptimeMillis(),
            )
            send(MotionEvent.ACTION_UP, 1, span, SystemClock.uptimeMillis())
        }

        // Engagement first, with nothing measured. An attempt that never reaches MapLibre's scale
        // detector is abandoned here, so a restarted pinch cannot contribute a frame — and the
        // frames that are measured all belong to one gesture over one installed overlay.
        // Engage with the inward travel already proven by the ordinary pinch. Expanding the tall
        // stream first let the move detector own all four attempts on API 36, while a five-percent
        // inward probe remained below its effective touch slop. The >=4.0 zoom assertion remains
        // the authority on whether the tall stream leaves enough measured travel afterwards.
        val engageSpan = startSpan + (endSpan - startSpan) * PINCH_ENGAGE_TRAVEL
        repeat(PINCH_ENGAGE_MOVES) { move ->
            val span = startSpan + (engageSpan - startSpan) * (move + 1) / PINCH_ENGAGE_MOVES
            send(MotionEvent.ACTION_MOVE, 2, span, SystemClock.uptimeMillis())
            SystemClock.sleep(GESTURE_MICRO_STEP_MILLIS)
        }
        // Input injection is synchronous, but the camera value is published with the renderer's
        // work. Reading it immediately can call an engaged zoom-in attempt a miss under full-suite
        // load. This is setup only; the acceptance path below still audits each of its own moves.
        map.awaitFullyRenderedFrame(view)
        val engagement = if (spanEdge == PinchSpanEdge.TALLEST) {
            kotlin.math.abs(map.cameraPosition.zoom - zoomAtTouchDown)
        } else if (zoomIn) {
            map.cameraPosition.zoom - zoomAtTouchDown
        } else {
            zoomAtTouchDown - map.cameraPosition.zoom
        }
        if (engagement < MINIMUM_PINCH_ENGAGEMENT) {
            lift(engageSpan)
            // Lifting ends in a camera idle, which rebuilds the fog. Let that finish, so the next
            // attempt starts from a settled overlay rather than racing one.
            Thread.sleep(PINCH_RETRY_SETTLE_MILLIS)
            return false
        }

        // The tall stream spends enough inward travel to engage reliably that only about 3.7
        // levels remain. Once the scale detector owns the uninterrupted stream, reopen to the
        // original span and use the whole inward path for the per-move audit. These setup moves
        // stay near zoom 16, inside the already-proven surround; every move of the acceptance path
        // from the reopened span to [endSpan] is still audited below.
        var currentSpan = engageSpan
        var streamEnded = false
        try {
            val measuredStartSpan = if (spanEdge == PinchSpanEdge.TALLEST) {
                repeat(PINCH_REOPEN_MOVES) { move ->
                    currentSpan = engageSpan +
                        (startSpan - engageSpan) * (move + 1) / PINCH_REOPEN_MOVES
                    send(MotionEvent.ACTION_MOVE, 2, currentSpan, SystemClock.uptimeMillis())
                    SystemClock.sleep(GESTURE_MICRO_STEP_MILLIS)
                }
                startSpan
            } else {
                engageSpan
            }

            val moves = GESTURE_STEPS * GESTURE_MICRO_STEPS
            repeat(moves) { move ->
                currentSpan = measuredStartSpan +
                    (endSpan - measuredStartSpan) * (move + 1) / moves
                send(MotionEvent.ACTION_MOVE, 2, currentSpan, SystemClock.uptimeMillis())
                SystemClock.sleep(GESTURE_MICRO_STEP_MILLIS)
                if (auditEveryMove) {
                    map.awaitFullyRenderedFrame(view)
                    onHold()
                } else if ((move + 1) % GESTURE_MICRO_STEPS == 0) {
                    Thread.sleep(GESTURE_HOLD_SETTLE_MILLIS)
                    onHold()
                }
            }
            lift(endSpan)
            streamEnded = true
            return true
        } finally {
            if (!streamEnded) {
                // An assertion in a per-move audit must not leave two pointers down and poison
                // every test that follows it. Preserve the original failure if the window itself
                // has already gone away and the best-effort cancellation is rejected.
                runCatching {
                    send(
                        MotionEvent.ACTION_CANCEL,
                        2,
                        currentSpan,
                        SystemClock.uptimeMillis(),
                    )
                }
            }
        }
    }

    /** Requests and waits for a fully rendered frame at the current camera and style state. */
    private fun MapLibreMap.awaitFullyRenderedFrame(view: MapView) {
        val ready = CountDownLatch(1)
        lateinit var listener: MapView.OnDidFinishRenderingFrameListener
        listener = MapView.OnDidFinishRenderingFrameListener { fullyRendered, _, _ ->
            if (fullyRendered) {
                view.removeOnDidFinishRenderingFrameListener(listener)
                ready.countDown()
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.addOnDidFinishRenderingFrameListener(listener)
            triggerRepaint()
        }
        try {
            assertTrue(
                "MapLibre did not fully render the requested camera and style state",
                ready.await(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.removeOnDidFinishRenderingFrameListener(listener)
            }
        }
    }

    /**
     * One finger dragged diagonally, held between steps. Diagonal so that a single gesture crosses
     * both the edge a trimmed surround loses and the edge a wrongly-measured one thinks it has.
     */
    private fun panInSteps(@Suppress("UNUSED_PARAMETER") map: MapLibreMap, onHold: () -> Unit) {
        val view = requireNotNull(composeRule.runOnIdle { attachedMapView() })
        val fromX = view.width * 0.72f
        val toX = view.width * 0.22f
        val fromY = view.height * 0.24f
        val toY = view.height * 0.82f
        val downTime = SystemClock.uptimeMillis()

        fun send(action: Int, x: Float, y: Float) {
            injectTouch(
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN },
            )
        }

        send(MotionEvent.ACTION_DOWN, fromX, fromY)
        val moves = GESTURE_STEPS * GESTURE_MICRO_STEPS
        repeat(moves) { move ->
            val progress = (move + 1).toFloat() / moves
            send(
                MotionEvent.ACTION_MOVE,
                fromX + (toX - fromX) * progress,
                fromY + (toY - fromY) * progress,
            )
            SystemClock.sleep(GESTURE_MICRO_STEP_MILLIS)
            if ((move + 1) % GESTURE_MICRO_STEPS == 0) {
                Thread.sleep(GESTURE_HOLD_SETTLE_MILLIS)
                onHold()
            }
        }
        send(MotionEvent.ACTION_UP, toX, toY)
    }

    /**
     * `sendPointerSync` refuses nothing and reports nothing, so a malformed multi-touch stream is
     * indistinguishable from a map that ignored it. Injecting through the automation interface
     * instead returns whether the event was actually dispatched.
     */
    private fun injectTouch(event: MotionEvent) {
        val injected = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .injectInputEvent(event, true)
        event.recycle()
        assertTrue("The input event was rejected: $event", injected)
    }

    /**
     * One finger: tap, then press and drag. MapLibre reads that as a zoom through a detector the
     * pinch never touches; dragging up zooms out and dragging down zooms in.
     */
    private fun quickZoomOutInSteps(map: MapLibreMap, onHold: () -> Unit) =
        quickZoomInSteps(map, onHold, zoomIn = false, auditEveryMove = false)

    /** The reverse zoom-1 transition, audited after every move without lifting the held tap. */
    private fun frameAuditedQuickZoomInInSteps(map: MapLibreMap, onHold: () -> Unit) =
        quickZoomInSteps(map, onHold, zoomIn = true, auditEveryMove = true)

    private fun quickZoomInSteps(
        map: MapLibreMap,
        onHold: () -> Unit,
        zoomIn: Boolean,
        auditEveryMove: Boolean,
    ) {
        val view = requireNotNull(composeRule.runOnIdle { attachedMapView() })
        val centerX = view.width / 2f
        val centerY = view.height / 2f

        fun send(downTime: Long, action: Int, y: Float) {
            injectTouch(
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    centerX,
                    y,
                    0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN },
            )
        }

        // A tap that lifts instantly, or a second tap that lands instantly, is not a double tap:
        // the platform requires a minimum time on each side of the gap before it will call it one,
        // and without that this degenerates into an ordinary drag that pans instead of zooming.
        val tapDown = SystemClock.uptimeMillis()
        send(tapDown, MotionEvent.ACTION_DOWN, centerY)
        SystemClock.sleep(TAP_DURATION_MILLIS)
        send(tapDown, MotionEvent.ACTION_UP, centerY)
        SystemClock.sleep(DOUBLE_TAP_GAP_MILLIS)
        val holdDown = SystemClock.uptimeMillis()
        send(holdDown, MotionEvent.ACTION_DOWN, centerY)
        SystemClock.sleep(TAP_DURATION_MILLIS)
        val travel = view.height * QUICK_ZOOM_TRAVEL_FRACTION
        val moves = GESTURE_STEPS * GESTURE_MICRO_STEPS
        val direction = if (zoomIn) 1f else -1f
        var currentY = centerY
        var streamEnded = false
        try {
            repeat(moves) { move ->
                currentY = centerY + direction * travel * (move + 1) / moves
                send(holdDown, MotionEvent.ACTION_MOVE, currentY)
                SystemClock.sleep(GESTURE_MICRO_STEP_MILLIS)
                if (auditEveryMove) {
                    map.awaitFullyRenderedFrame(view)
                    onHold()
                } else if ((move + 1) % GESTURE_MICRO_STEPS == 0) {
                    Thread.sleep(GESTURE_HOLD_SETTLE_MILLIS)
                    onHold()
                }
            }
            send(holdDown, MotionEvent.ACTION_UP, currentY)
            streamEnded = true
        } finally {
            if (!streamEnded) {
                runCatching { send(holdDown, MotionEvent.ACTION_CANCEL, currentY) }
            }
        }
    }

    private fun fogGeneration(): Any? = composeRule.runOnIdle {
        attachedMapView()?.getTag(R.id.map_fog_canonical_generation)
    }

    /**
     * Where this display's own zoom floor is, taken from the camera rather than from a constant.
     */
    private fun measureZoomFloor(
        map: MapLibreMap,
        cameraRequest: MutableState<MapCameraRequest>,
    ): Double {
        composeRule.runOnUiThread {
            cameraRequest.value = MapCameraRequest(
                requestId = 2L,
                point = UNEXPLORED_VIEWPOINTS.first().second,
                zoom = 0.0,
            )
        }
        composeRule.waitUntil(timeoutMillis = 25_000L) {
            composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        Thread.sleep(ZOOM_SETTLE_MILLIS)
        return map.cameraPosition.zoom
    }

    /**
     * MapLibre keeps the world covering the viewport, and its world is 512 logical pixels across at
     * zoom 0, so how far out a device can go is a property of how tall its map view is.
     */
    private fun predictedZoomFloor(): Double {
        val view = requireNotNull(composeRule.runOnIdle { attachedMapView() })
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .resources
            .displayMetrics
            .density
        val logicalHeight = view.height / density
        return max(0.0, ln(logicalHeight / MAPLIBRE_WORLD_SIZE_DP) / ln(2.0))
    }

    /**
     * Following a walking user has to move the map without blanking it.
     *
     * Every programmed camera move used to hide the overlay until its rebuild landed, which is the
     * right answer for a jump across the world and the wrong one for a person walking: they would
     * have watched the map go black every time they crossed the dead zone. A follow step is bounded
     * by the ground crossed between two fixes, and the surround is now the whole world, so there is
     * nothing left for such a step to outrun.
     */
    @Test
    fun followingALocationMovesTheMapWithoutBlankingIt() {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            val revealed = GeoPoint(25.0330, 121.5654)
            revealTrack(database, revealed)
            val followLocation = mutableStateOf<GeoPoint?>(null)

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-follow-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-follow",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.fog-follow-test",
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
                    followLocation = followLocation.value,
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 20_000L) { fogRendered.get() }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }
            composeRule.waitUntil(timeoutMillis = 20_000L) {
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            Thread.sleep(ZOOM_SETTLE_MILLIS)

            // A step that stays inside the viewport but well outside the dead zone, which is what
            // a few minutes of walking looks like at this zoom.
            val walked = GeoPoint(revealed.latitude + FOLLOW_STEP_DEGREES, revealed.longitude)
            composeRule.runOnUiThread { followLocation.value = walked }

            var coveredFrames = 0
            var arrived = false
            val deadline = SystemClock.uptimeMillis() + FOLLOW_ARRIVAL_TIMEOUT_MILLIS
            while (SystemClock.uptimeMillis() < deadline) {
                if (
                    composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                ) {
                    coveredFrames += 1
                }
                val target = map.cameraPosition.target
                if (
                    target != null &&
                    kotlin.math.abs(target.latitude - walked.latitude) < FOLLOW_ARRIVAL_DEGREES
                ) {
                    arrived = true
                    break
                }
            }
            val settled = map.cameraPosition.target

            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "TrailVeil follow step: from=${revealed.latitude} to=${walked.latitude} " +
                            "settled=${settled?.latitude} arrived=$arrived " +
                            "coveredFrames=$coveredFrames\n",
                    )
                },
            )
            assertTrue(
                "The map never followed the location it was given (settled at $settled)",
                arrived,
            )
            assertEquals(
                "The safety cover was raised while following a walking user",
                0,
                coveredFrames,
            )
        } finally {
            database.close()
        }
    }

    /**
     * Turning following on and moving the camera happen together, and the move carries the zoom.
     *
     * A follow step is a latitude and longitude with no zoom in it, so one made in the same frame
     * as a programmed move replaces that move and the zoom goes with it. From the user's side the
     * recentre button centred the map but never took them back in, and it took a second press to
     * get the zoom — which is how this was reported.
     */
    @Test
    fun turningFollowingOnDoesNotSwallowTheZoomItWasAskedFor() {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            val revealed = GeoPoint(25.0330, 121.5654)
            revealTrack(database, revealed)
            val followLocation = mutableStateOf<GeoPoint?>(null)
            // Started somewhere else, which is the whole point: the user looked around, so the
            // location is well off centre when the button is pressed and the follow step is a real
            // one. Starting centred makes `followCameraMove` return HOLD, no competing animation is
            // ever issued, and the test passes whether the fix is present or not.
            val lookedAround = GeoPoint(
                revealed.latitude + RECENTRE_LOOK_AWAY_DEGREES,
                revealed.longitude + RECENTRE_LOOK_AWAY_DEGREES,
            )
            val cameraRequest = mutableStateOf(
                MapCameraRequest(requestId = 1L, point = lookedAround, zoom = RECENTRE_FROM_ZOOM),
            )

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-recentre-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-recentre",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.fog-recentre-test",
                    fogRuntime = fogRuntime(
                        database,
                        RoomPersistedTrackPointChangeFeed(database.recordingDao()),
                    ),
                    fogRequired = true,
                    cameraRequest = cameraRequest.value,
                    followLocation = followLocation.value,
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 20_000L) { fogRendered.get() }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }
            composeRule.waitUntil(timeoutMillis = 20_000L) {
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            Thread.sleep(ZOOM_SETTLE_MILLIS)

            // One press of the recentre button: following turns on and a zoom is asked for, in the
            // same recomposition, with the map somewhere else entirely.
            composeRule.runOnUiThread {
                followLocation.value = revealed
                cameraRequest.value = MapCameraRequest(
                    requestId = 2L,
                    point = revealed,
                    zoom = RECENTRE_TO_ZOOM,
                )
            }
            // Waited *for* rather than waited out, because `waitUntil` is also what pumps the
            // compose test clock: a plain sleep here leaves the state change unrecomposed, so the
            // camera never receives the request and the test fails for a reason of its own making.
            // The timeout is swallowed so the assertion below can say where the camera actually is,
            // which a bare timeout cannot.
            runCatching {
                composeRule.waitUntil(timeoutMillis = FOLLOW_ARRIVAL_TIMEOUT_MILLIS) {
                    kotlin.math.abs(map.cameraPosition.zoom - RECENTRE_TO_ZOOM) < ZOOM_TOLERANCE
                }
            }
            Thread.sleep(ZOOM_SETTLE_MILLIS)

            assertEquals(
                "The zoom the recentre asked for was replaced by a follow step",
                RECENTRE_TO_ZOOM,
                map.cameraPosition.zoom,
                ZOOM_TOLERANCE,
            )
        } finally {
            database.close()
        }
    }

    /**
     * The only thing that stops the map following is the user's own hand. The follow step itself is
     * a programmed move, so it must not be mistaken for one and switch itself off after one step.
     */
    @Test
    fun onlyAGestureReportsThatTheUserMovedTheCamera() {
        val database = inMemoryDatabase()
        try {
            val fogRendered = AtomicBoolean(false)
            val revealed = GeoPoint(25.0330, 121.5654)
            revealTrack(database, revealed)
            val followLocation = mutableStateOf<GeoPoint?>(null)
            val userMoves = AtomicInteger()

            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "fog-follow-cancel-test-provider",
                        styleUri = "https://tiles.invalid/styles/fog-follow-cancel",
                    ),
                    fallbackTimeoutMillis = 100L,
                    savedStateKey = "trailveil.map.fog-follow-cancel-test",
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
                    followLocation = followLocation.value,
                    onUserMovedCamera = { userMoves.incrementAndGet() },
                    onFogRendered = { fogRendered.set(true) },
                )
            }

            composeRule.waitUntil(timeoutMillis = 20_000L) { fogRendered.get() }
            val map = checkNotNull(awaitMap()) { "The map never became ready" }
            composeRule.waitUntil(timeoutMillis = 20_000L) {
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            Thread.sleep(ZOOM_SETTLE_MILLIS)

            composeRule.runOnUiThread {
                followLocation.value =
                    GeoPoint(revealed.latitude + FOLLOW_STEP_DEGREES, revealed.longitude)
            }
            Thread.sleep(FOLLOW_ARRIVAL_TIMEOUT_MILLIS)
            assertEquals(
                "A follow step reported itself as the user moving the camera, so following " +
                    "would switch itself off after one step",
                0,
                userMoves.get(),
            )

            val view = requireNotNull(composeRule.runOnIdle { attachedMapView() })
            dragVertically(
                x = view.width / 2f,
                fromY = view.height * 0.7f,
                toY = view.height * 0.3f,
                steps = 12,
                stepMillis = 12L,
                lift = true,
            )
            composeRule.waitUntil(timeoutMillis = 10_000L) { userMoves.get() > 0 }

            assertTrue(
                "A drag on the map never reported that the user moved the camera",
                userMoves.get() > 0,
            )
            assertNotNull("The map lost its camera", map.cameraPosition.target)
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
            val gestureFailure = AtomicReference<Throwable?>(null)
            val gestures = Thread {
                try {
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
                } catch (failure: Throwable) {
                    // An uncaught exception on this target-process thread kills instrumentation
                    // before the manifest gate can report which cases were lost.
                    gestureFailure.set(failure)
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
            gestureFailure.get()?.let { failure ->
                throw AssertionError("The sustained input stream failed", failure)
            }
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
     * The production comparator must reject a reference that changes underneath it without adding
     * a brightness floor that would hide a real leak over dark ocean tiles.
     */
    @Test
    fun bareReferenceMustBeStableAndTheComparatorStillDetectsADarkLeak() {
        val incomplete = intArrayOf(gray(1), gray(1), gray(1), gray(1))
        val painted = intArrayOf(gray(18), gray(22), gray(26), gray(28))

        assertTrue(
            "An incomplete reference was accepted after the basemap changed",
            !referenceFramesAreStable(incomplete, painted),
        )
        assertTrue(
            "Two identical dark-ocean references were rejected",
            referenceFramesAreStable(painted, painted.copyOf()),
        )

        val fogged = intArrayOf(gray(5), gray(6), painted[2], gray(8))
        val audit = compareFogCoverage(fogged, painted, width = 2)
        assertEquals(
            "The stable-reference rule blinded the detector to an actual missing-fog pixel",
            0.25,
            audit.uncoveredFraction,
            0.0,
        )
        assertEquals(1.0, audit.drawnFraction, 0.0)
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
        // Put back what the app chose, not everything. Which fog quads are drawn depends on the
        // camera, and leaving them all visible would hand the next measurement an arrangement the
        // app never produces — two coats where it draws one.
        val visibility = ALL_FOG_LAYERS.associateWith { fogLayerVisibility(it) }
        setFogLayersVisible(false)
        val bare = snapshotStableBarePixels("fog coverage reference")
        restoreFogLayerVisibility(visibility)
        return compareFogCoverage(fogged, bare, snapshotWidth())
    }

    /**
     * What the audit reports when there is definitely no fog: the same bare frame compared against
     * itself. Everything the map actually draws must come back uncovered. A detector that stays
     * quiet here would stay quiet on a real leak too, and every clean sweep it produced would mean
     * nothing — which is exactly how the previous fixed-threshold measurement went wrong.
     */
    private fun MapLibreMap.auditWithFogRemoved(): FogAudit {
        val visibility = ALL_FOG_LAYERS.associateWith { fogLayerVisibility(it) }
        setFogLayersVisible(false)
        val first = snapshotStableBarePixels("fog-removed calibration first reference")
        val second = snapshotStableBarePixels("fog-removed calibration second reference")
        restoreFogLayerVisibility(visibility)
        return compareFogCoverage(first, second, snapshotWidth())
    }

    private fun compareFogCoverage(fogged: IntArray, bare: IntArray, width: Int): FogAudit {
        assertEquals("Snapshot sizes differ", bare.size, fogged.size)
        var uncovered = 0L
        var drawn = 0L
        var worstRatio = 0.0
        var worstBare = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var overFogged = 0L
        var judgeable = 0L
        var darkMinX = Int.MAX_VALUE
        var darkMinY = Int.MAX_VALUE
        var darkMaxX = Int.MIN_VALUE
        var darkMaxY = Int.MIN_VALUE
        bare.indices.forEach { index ->
            val bareLuminance = luminance(bare[index])
            val fogLuminance = luminance(fogged[index])
            // Nothing to reveal where the map itself draws nothing.
            if (bareLuminance <= 0) return@forEach
            drawn += 1L
            // The other way coverage goes wrong. One coat of fog transmits 0.278 of what is under
            // it; two coats transmit 0.077 and read as black on a dark basemap. That is not a leak,
            // so nothing here could see it — and a map with a black stripe down it is still a
            // broken map. Judged only where the basemap is bright enough for the ratio to mean
            // something.
            if (bareLuminance >= MINIMUM_BARE_FOR_OVER_FOG) {
                judgeable += 1L
                if (fogLuminance < FOG_TRANSMISSION * bareLuminance * OVER_FOG_RATIO) {
                    overFogged += 1L
                    val x = index % width
                    val y = index / width
                    if (x < darkMinX) darkMinX = x
                    if (x > darkMaxX) darkMaxX = x
                    if (y < darkMinY) darkMinY = y
                    if (y > darkMaxY) darkMaxY = y
                }
            }
            if (fogLuminance > FOG_TRANSMISSION_CEILING * bareLuminance + FOG_LUMINANCE_TOLERANCE) {
                uncovered += 1L
                val x = index % width
                val y = index / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
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
            overFoggedFraction = if (judgeable == 0L) {
                0.0
            } else {
                overFogged.toDouble() / judgeable.toDouble()
            },
            overFoggedBounds = if (overFogged == 0L) null else IntArray(4).also {
                it[0] = darkMinX
                it[1] = darkMinY
                it[2] = darkMaxX
                it[3] = darkMaxY
            },
            // Where the leak is, not just how big it is. A strip along one edge, a seam through the
            // middle and a corner are three different bugs that the fraction alone cannot tell
            // apart, and localising one by bisecting the geometry costs a run each time.
            uncoveredBounds = if (uncovered == 0L) null else IntArray(4).also {
                it[0] = minX
                it[1] = minY
                it[2] = maxX
                it[3] = maxY
            },
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

    private fun snapshotWidth(): Int =
        requireNotNull(composeRule.runOnIdle { attachedMapView() }).width

    /**
     * A fog comparison is meaningful only after the no-fog reference has stopped changing. A
     * `fullyRendered` callback prevents accepting an in-progress renderer frame; requiring two
     * bit-identical snapshots then prevents a tile that lands between the paired captures from
     * being reported as missing fog. This deliberately has no luminance floor: OpenFreeMap ocean
     * tiles are dark, and a real leak there must remain judgeable.
     */
    private fun MapLibreMap.snapshotStableBarePixels(label: String): IntArray {
        val view = requireNotNull(composeRule.runOnIdle { attachedMapView() })
        var previous: IntArray? = null
        var changedPixels = -1L
        repeat(BARE_REFERENCE_STABILITY_ATTEMPTS) { attempt ->
            if (attempt > 0) SystemClock.sleep(BARE_REFERENCE_STABILITY_RETRY_MILLIS)
            awaitFullyRenderedFrame(view)
            val current = snapshotPixels()
            val prior = previous
            if (prior != null) {
                changedPixels = changedPixelCount(prior, current)
                if (changedPixels == 0L) return current
            }
            previous = current
        }
        assertTrue(
            "$label never produced two identical fully rendered snapshots; " +
                "lastChangedPixels=$changedPixels",
            false,
        )
        return requireNotNull(previous)
    }

    private fun referenceFramesAreStable(first: IntArray, second: IntArray): Boolean =
        changedPixelCount(first, second) == 0L

    private fun changedPixelCount(first: IntArray, second: IntArray): Long {
        assertEquals("Snapshot sizes differ", first.size, second.size)
        var changed = 0L
        first.indices.forEach { index ->
            if (first[index] != second[index]) changed += 1L
        }
        return changed
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

    private fun gray(value: Int): Int =
        (0xff shl 24) or (value shl 16) or (value shl 8) or value

    private data class FogAudit(
        val uncoveredFraction: Double,
        val drawnFraction: Double,
        val worstRatio: Double,
        val worstBareLuminance: Int,
        val sampledPixels: Long,
        val uncoveredBounds: IntArray? = null,
        val overFoggedFraction: Double = 0.0,
        val overFoggedBounds: IntArray? = null,
    ) {
        fun report(): String = "[uncovered=" +
            "${"%.4f".format(java.util.Locale.US, uncoveredFraction * 100.0)}% " +
            "drawn=${"%.2f".format(java.util.Locale.US, drawnFraction * 100.0)}% " +
            "worstRatio=${"%.2f".format(java.util.Locale.US, worstRatio)} " +
            "bareAtWorst=$worstBareLuminance pixels=$sampledPixels" +
            (uncoveredBounds?.let { " at=(${it[0]},${it[1]})-(${it[2]},${it[3]})" } ?: "") +
            " overFogged=${"%.4f".format(java.util.Locale.US, overFoggedFraction * 100.0)}%" +
            (overFoggedBounds?.let { " dark=(${it[0]},${it[1]})-(${it[2]},${it[3]})" } ?: "") +
            "]"

        // The array field makes the generated equals/hashCode wrong by identity; nothing here
        // compares audits, so they are simply not offered.
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
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
        const val BARE_REFERENCE_STABILITY_ATTEMPTS = 12
        const val BARE_REFERENCE_STABILITY_RETRY_MILLIS = 50L
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

        /**
         * What one coat of fog leaves: `(255 - fogAlpha) / 255`. A second coat squares it, to
         * 0.077, which is black on anything but a bright basemap. Half of one coat is comfortably
         * between the two and well clear of rounding.
         */
        const val FOG_TRANSMISSION = 0.278
        const val OVER_FOG_RATIO = 0.5

        /**
         * Below this the basemap is so dark that one coat and two are not separable. At bare 30 one
         * coat lands at 8 and two at 2, six levels apart; at bare 12 they are 3 and 1, and a single
         * step of rounding decides the answer. Ocean in the production style sits at 18-28, which
         * is exactly the range that produced scattered false positives before this bound.
         */
        const val MINIMUM_BARE_FOR_OVER_FOG = 30

        /**
         * What the seams between fog quads are allowed to cost, and no more.
         *
         * The bands deliberately overlap the mosaic by half of one *mosaic mask* pixel, which is
         * sub-pixel at exploration zooms and about five screen pixels at render zoom 0, where one
         * mask pixel is a two-hundred-and-fifty-sixth of the world. That is the whole of the
         * residue this tolerates: measured 0.4167% at a camera with the world's top and bottom
         * edges on screen — two five-pixel strips across 1080 — and 1.2465% at the antimeridian,
         * where the mosaic's east and west edges are on screen as well. `P4-023` is the task to
         * make that overlap a screen-pixel quantity instead.
         *
         * A gesture adds to that. Where two fog quads abut — the mosaic and its own world copy —
         * the seam between them widens as the camera zooms away from the zoom the overlay was
         * built for, the same drift `P4-017` measured in the opposite direction. Measured crossing
         * the antimeridian: a 24-pixel dark line at 2.3148% of the screen, still a line and not a
         * region. `P4-024` is the task to shrink those seams.
         *
         * What this must never stop catching is the defect it was built for: a user reported half
         * the map going black past the antimeridian, which measures 50.39% settled and 50.08%
         * during a gesture. This bound sits twenty times under that.
         */
        const val MAXIMUM_OVER_FOGGED_FRACTION = 0.05
        const val FOG_VISIBILITY_SETTLE_MILLIS = 600L

        /** Every quad the fog installs, in the order they are drawn. */
        val ALL_FOG_LAYERS: List<String> = listOf(
            FogOverlayIds.Layer,
            FogOverlayIds.WestRepeatLayer,
            FogOverlayIds.EastRepeatLayer,
        ) + FogBackdropIds.Layers

        /**
         * One quad drawn on its own is one coat of fog, everywhere it reaches, or it is being
         * drawn twice. This is not a seam budget — there is no second quad to seam against — so it
         * allows only what antialiasing at the quad's own edge can account for. The defect it
         * bounds measured 5.31% and 8.25%, two orders of magnitude above it.
         */
        const val MAXIMUM_SINGLE_QUAD_OVER_FOGGED_FRACTION = 0.001

        /** The map must actually be drawing, or "no leak found" is a statement about a blank screen. */
        const val MINIMUM_DRAWN_FRACTION = 0.5

        /** With the fog hidden, nearly everything the map draws must read as uncovered. */
        const val MINIMUM_CALIBRATION_UNCOVERED_FRACTION = 0.5
        /**
         * How long a production-style test may take to fetch a style, its tiles and a first fog
         * frame before it is called a failure.
         *
         * A setup budget, not something a result is measured against — every measurement here is
         * taken afterwards. It was 90 seconds, which is ample alone and occasionally not enough
         * under a full suite where every production-style test fetches again: three of four full
         * runs lost one or two tests to it, each on this wait, each passing in isolation
         * immediately afterwards, on an emulator with 505 ms round-trip to the tile host. A gate
         * that fails a test per run cannot tell anyone whether the tree is green.
         */
        const val ONLINE_STYLE_SETUP_MILLIS = 180_000L

        const val ZOOM_SETTLE_MILLIS = 1_200L
        const val ZOOM_TOLERANCE = 0.05

        /**
         * Every zoom the surround changes character at, and the ordinary ones between them: it is
         * the whole world below render zoom six and clamped above, and the band appeared only where
         * the clamp binds.
         */
        val SETTLED_SEAM_ZOOMS = listOf(16.0, 14.0, 12.0, 10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0)

        /**
         * A settled camera has no gesture to blame, so the seams between quads are the designed
         * half a mosaic pixel and nothing else — a few screen pixels at most. The band this bounds
         * measured 2.08% and 3.04%; every zoom now measures 0.0000%.
         */
        const val MAXIMUM_SETTLED_SEAM_FRACTION = 0.002

        /** Looking around from far out, then pressing the button that takes you back in. */
        const val RECENTRE_FROM_ZOOM = 8.0
        const val RECENTRE_TO_ZOOM = 16.0

        /**
         * Far enough at zoom 8 to put the user's own location off screen, so the follow step the
         * guard has to stand aside for is the off-screen one rather than no step at all.
         */
        const val RECENTRE_LOOK_AWAY_DEGREES = 0.35

        /** The camera must actually reach the pose, or the measurement is of an upright map. */
        const val OBLIQUE_POSE_TOLERANCE = 1.0

        /**
         * A tilted camera sees ground running to the horizon, so the far end of a long zoom-out
         * legitimately leaves the installed surround and is covered. Measured at one frame of six;
         * more than that is the blanking this task exists to remove, coming back.
         */
        const val MAXIMUM_OBLIQUE_COVERED_FRAMES = 2

        /**
         * MapLibre's world is 512 logical pixels across at zoom 0 and it keeps that world covering
         * the viewport, so the lowest reachable zoom belongs to the display, not to this app. The
         * sweep predicts it from the map view's own height and then checks the camera against the
         * prediction, which is what makes "nothing here refuses to zoom out" a real assertion on
         * any screen rather than a constant that happens to hold on one.
         */
        const val MAPLIBRE_WORLD_SIZE_DP = 512.0
        const val ZOOM_FLOOR_PREDICTION_TOLERANCE = 0.3
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

        /**
         * The zoom the reported leak started from, and enough steps out of it to cross the range
         * where the old surround ran out. The fingers stay down throughout; each step is held long
         * enough for the camera to settle so the frame can be compared with its own unfogged twin.
         */
        const val GESTURE_START_ZOOM = 4.0
        const val GESTURE_STEPS = 6
        const val GESTURE_MICRO_STEPS = 5
        const val GESTURE_MICRO_STEP_MILLIS = 16L
        const val GESTURE_HOLD_SETTLE_MILLIS = 500L
        const val MINIMUM_GESTURE_ZOOM_CHANGE = 1.5
        const val MINIMUM_LONG_GESTURE_ZOOM_CHANGE = 4.0
        /** How much of the height a full-screen vertical pinch may use. */
        const val TALL_PINCH_EDGE_FRACTION = 0.95f

        const val PINCH_START_SPAN_FRACTION = 0.75f
        const val PINCH_END_SPAN_FRACTION = 0.06f
        const val LONG_PINCH_START_SPAN_FRACTION = 0.82f
        const val LONG_PINCH_END_SPAN_FRACTION = 0.019f
        /**
         * How many times a pinch may be started over before the test gives up. Injected two-finger
         * streams do not always reach MapLibre's scale detector — about one attempt in three ends
         * with the move detector holding the gesture and the camera never zooming — and neither a
         * coarser first step nor finer steps made that reliable. So engagement is checked instead
         * of assumed: an attempt that has not moved the camera is lifted and made again, which
         * costs a second and makes the suite deterministic.
         */
        const val PINCH_ATTEMPTS = 4
        const val PINCH_ENGAGE_MOVES = 8
        const val PINCH_REOPEN_MOVES = 8
        const val PINCH_ENGAGE_TRAVEL = 0.30f
        const val MINIMUM_PINCH_ENGAGEMENT = 0.03
        const val PINCH_RETRY_SETTLE_MILLIS = 2_500L
        const val QUICK_ZOOM_TRAVEL_FRACTION = 0.45f
        const val TAP_DURATION_MILLIS = 60L
        const val DOUBLE_TAP_GAP_MILLIS = 80L

        /**
         * About 450 m north — inside the viewport at zoom 16, so it is a follow step rather than a
         * move, and far outside the dead zone, so it is a step the map has to take.
         */
        const val FOLLOW_STEP_DEGREES = 0.004
        const val FOLLOW_ARRIVAL_DEGREES = 0.0005
        const val FOLLOW_ARRIVAL_TIMEOUT_MILLIS = 4_000L

        val REVEALED_CENTER = GeoPoint(25.0330, 121.5654)

        /** Where the world's edge is at the middle of the screen, so both copies are in view. */
        val ANTIMERIDIAN = GeoPoint(0.0, 179.5)

        /**
         * Less than a pinch usually travels, because these two start at 1.6 and at MapLibre's own
         * floor and only have to cross 0.95 between them.
         */
        const val ANTIMERIDIAN_ZOOM_CHANGE = 0.4

        /** Either side of the measured edge, and one well clear of it. */
        val WORLD_COPY_EDGE_ZOOMS = listOf(0.98, 1.0, 1.6)
        const val WORLD_COPY_RENDER_EDGE_ZOOM = 1.0

        /** Far enough east that the revealed track stays off screen all the way out of zoom 16. */
        val UNEXPLORED_NEAR_REVEALED = GeoPoint(25.0330, 121.9000)
        const val MINIMUM_PAN_DEGREES = 0.5
    }
}
