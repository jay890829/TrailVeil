package app.trailveil.map

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.R
import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.LongitudeInterval
import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedPointRevision
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.data.map.PersistedTrackPointChangeFeed
import app.trailveil.data.map.PersistedTrackPoint
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.ViewportTrackPointReader
import app.trailveil.map.fog.FogNativeGeometry
import app.trailveil.map.fog.FogNativeGeometryEngine
import app.trailveil.map.fog.FogNativePolygon
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.FogPocMosaic
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileKey
import app.trailveil.map.fog.FogViewportTileGrid
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRevealUpdate
import app.trailveil.map.fog.TrackSegment
import app.trailveil.map.fog.anchoredNear
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

/**
 * Device checks for the MapLibre completed-idle certificate.
 *
 * This class deliberately uses only the b47 surface seams. The candidate adds a new idle trace
 * name, but b47's enum does not have that value, so the test treats the trace as an opaque name and
 * never links the new enum constant. The natural-pan rows are coordinate-free; the synthetic
 * fixture is only a bounded correctness harness and is not a production-track or frame-latency
 * claim.
 */
@RunWith(AndroidJUnit4::class)
class MapLibreIdleReuseTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The same test APK is run against b47 (`false`) and the candidate (`true`). */
    @Test
    fun naturalWithinCenterTilePanUsesExpectedIdlePath() {
        val previousNative = MapLibreVectorFogState.trackEnabled
        val previousVector = MapLibreVectorFogState.enabled
        val nativePositive = nativePositiveArgument()
        MapLibreVectorFogState.trackEnabled = nativePositive
        MapLibreVectorFogState.enabled = false
        try {
            val harness = createHarness(RectangleNativeEngine())
            val map = awaitInitialCanonical(harness)
            val measurement = measureNaturalPans(
                harness = harness,
                map = map,
                gestureCount = NATURAL_PAN_COUNT,
                expectedReuse = expectedReuseArgument(),
                label = if (nativePositive) "native-success" else "raster-success",
            )
            assertTrue(
                "The injected stream never reached MapLibre's gesture camera path: " +
                    measurement.moveReasons,
                measurement.moveReasons.count { reason ->
                    reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                } >= NATURAL_PAN_COUNT,
            )
            sendMeasurementStatus(measurement)
            if (expectedReuseArgument()) {
                assertTrue(
                    "Every same-footprint idle should reuse the complete native generation: " +
                        measurement.rows,
                    measurement.reuseCount >= NATURAL_PAN_COUNT,
                )
                assertEquals(
                    "A reused idle must not invoke a canonical install",
                    0,
                    measurement.installCountDelta,
                )
                assertEquals(
                    "A reused idle must not pulse the composed safety cover",
                    0,
                    measurement.coverPulseCount,
                )
            } else {
                assertEquals(
                    "b47 has no completed-idle reuse path",
                    0,
                    measurement.reuseCount,
                )
                assertTrue(
                    "The baseline must rebuild each natural idle: " + measurement.rows,
                    measurement.installCountDelta >= NATURAL_PAN_COUNT,
                )
            }
        } finally {
            MapLibreVectorFogState.trackEnabled = previousNative
            MapLibreVectorFogState.enabled = previousVector
        }
    }

    /** Native was requested, but a null geometry result must remain retryable and never be reused. */
    @Test
    fun nativeRequestedRasterFallbackNeverReusesSameFootprintIdle() {
        val previousNative = MapLibreVectorFogState.trackEnabled
        val previousVector = MapLibreVectorFogState.enabled
        MapLibreVectorFogState.trackEnabled = true
        MapLibreVectorFogState.enabled = false
        try {
            val engine = NullNativeFallbackEngine()
            val harness = createHarness(engine)
            val map = awaitInitialCanonical(harness)
            val renderCallsBefore = engine.renderCalls.get()
            val measurement = measureNaturalPans(
                harness = harness,
                map = map,
                gestureCount = FALLBACK_PAN_COUNT,
                expectedReuse = false,
                label = "native-requested-raster-fallback",
            )
            sendMeasurementStatus(measurement)
            assertTrue(
                "The native-requested fixture was never asked for geometry",
                engine.renderCalls.get() - renderCallsBefore >= FALLBACK_PAN_COUNT,
            )
            assertEquals(
                "A native request that fell back to raster must never receive an idle certificate",
                0,
                measurement.reuseCount,
            )
            assertTrue(
                "Every fallback idle must rebuild instead of reusing its raster footprint: " +
                    measurement.rows,
                measurement.installCountDelta >= FALLBACK_PAN_COUNT,
            )
        } finally {
            MapLibreVectorFogState.trackEnabled = previousNative
            MapLibreVectorFogState.enabled = previousVector
        }
    }

    @Test
    fun reusedIdleStillObservesAppendReplacementAndChangedFootprints() {
        val oldNative = MapLibreVectorFogState.trackEnabled
        val oldVector = MapLibreVectorFogState.enabled
        MapLibreVectorFogState.trackEnabled = true
        MapLibreVectorFogState.enabled = false
        try {
            val engine = RectangleNativeEngine()
            val harness = createHarness(engine)
            val map = awaitInitialCanonical(harness)
            measureNaturalPans(harness, map, 1, expectedReuseArgument(), "before-append")
            val generationBeforeAppend = currentGeneration(harness)
            val installsBeforeAppend = harness.observation.installs.size
            harness.source.append(ViewportTrackPoint(2L, 1L, 1L, 0L, 1L, CENTER.latitude + 0.00001, CENTER.longitude))
            harness.feed.append(PersistedTrackPoint(2L, 1L, 1L, 0L, 1L, 2_000L, CENTER.latitude + 0.00001, CENTER.longitude))
            awaitAdditionalInstall(harness, map, installsBeforeAppend)
            assertEquals("An append must wake the same viewport's revision collector", generationBeforeAppend, currentGeneration(harness))
            assertEquals("The follow-up must read the appended canonical point", 2, engine.lastReadPointCount.get())

            measureNaturalPans(harness, map, 1, expectedReuseArgument(), "before-replacement")
            val generationBeforeReplacement = checkNotNull(currentGeneration(harness))
            val installsBeforeReplacement = harness.observation.installs.size
            runBlocking {
                harness.runtime.replaceCanonicalData {
                    harness.source.clear()
                    harness.feed.reset()
                }
            }
            awaitAdditionalInstall(harness, map, installsBeforeReplacement)
            assertTrue("Replacement epoch must invalidate the old viewport generation", checkNotNull(currentGeneration(harness)) > generationBeforeReplacement)
            assertEquals("Replacement must be rendered from the empty canonical source", 0, engine.lastReadPointCount.get())

            val beforeTileMove = footprint(harness, currentViewportRequest(map))
            var installCount = harness.observation.installs.size
            composeRule.runOnUiThread { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(CENTER.latitude, CENTER.longitude + 0.02), CAMERA_ZOOM)) }
            awaitAdditionalInstall(harness, map, installCount)
            val afterTileMove = footprint(harness, currentViewportRequest(map))
            assertTrue("The tile-crossing control must change the footprint", beforeTileMove != afterTileMove)
            installCount = harness.observation.installs.size
            composeRule.runOnUiThread { map.moveCamera(CameraUpdateFactory.zoomTo(CAMERA_ZOOM - 1.0)) }
            awaitAdditionalInstall(harness, map, installCount)
            assertTrue("Integer zoom must change the footprint and render", afterTileMove != footprint(harness, currentViewportRequest(map)))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "U1_RECOVERY append=PASS replacement=PASS tile=PASS integerZoom=PASS\n")
            })
        } finally {
            MapLibreVectorFogState.trackEnabled = oldNative
            MapLibreVectorFogState.enabled = oldVector
        }
    }

    @Test
    fun pauseResumeInvalidatesThePreviouslyReusedGeneration() {
        val oldNative = MapLibreVectorFogState.trackEnabled
        val oldVector = MapLibreVectorFogState.enabled
        MapLibreVectorFogState.trackEnabled = true
        MapLibreVectorFogState.enabled = false
        try {
            val harness = createHarness(RectangleNativeEngine())
            val map = awaitInitialCanonical(harness)
            measureNaturalPans(harness, map, 1, expectedReuseArgument(), "before-pause")
            val view = harness.mapView.get()
            val tracesBefore = harness.observation.traces.size
            val installsBefore = harness.observation.installs.size
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.waitForIdle()
            // Required injector cleanup must not provide the invalidation being tested.
            bestEffortClearStuckInjectedPointers()
            composeRule.waitForIdle()
            injectGentlePan(harness, map, forward = false)
            composeRule.waitUntil(INITIAL_TIMEOUT_MILLIS) {
                harness.observation.traces.drop(tracesBefore).any { it.trigger == CAMERA_IDLE_TRIGGER || it.trigger == IDLE_REUSED_TRIGGER } &&
                    harness.observation.installs.size > installsBefore
            }
            awaitCanonicalQuiescence(harness, map)
            val newTraces = harness.observation.traces.drop(tracesBefore)
            assertTrue("The same MapView must survive pause/resume", harness.mapView.get() === view)
            assertTrue("Injected cleanup/programmed motion must not supply the invalidation", newTraces.none { it.trigger == "CAMERA_MOVE_CANCELLED" || it.trigger == "PROGRAMMED_MOVE_STARTED" })
            assertEquals("The first idle after pause/resume cannot reuse the old certificate", CAMERA_IDLE_TRIGGER,
                newTraces.first { it.trigger == CAMERA_IDLE_TRIGGER || it.trigger == IDLE_REUSED_TRIGGER }.trigger)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "U1_LIFECYCLE pauseResume=PASS canonicalInstalls=${harness.observation.installs.size - installsBefore}\n")
            })
        } finally {
            MapLibreVectorFogState.trackEnabled = oldNative
            MapLibreVectorFogState.enabled = oldVector
        }
    }

    private fun awaitAdditionalInstall(harness: Harness, map: MapLibreMap, before: Int) {
        composeRule.waitUntil(INITIAL_TIMEOUT_MILLIS) { harness.observation.installs.size > before }
        awaitCanonicalQuiescence(harness, map)
    }

    private fun expectedReuseArgument(): Boolean {
        val value = InstrumentationRegistry.getArguments().getString(EXPECT_REUSE_ARGUMENT)
        check(value == "true" || value == "false") {
            "Pass -e $EXPECT_REUSE_ARGUMENT true for the candidate or false for b47"
        }
        return value == "true"
    }

    private fun nativePositiveArgument(): Boolean {
        val value = InstrumentationRegistry.getArguments()
            .getString(EXPECT_NATIVE_ARGUMENT, "true")
        check(value == "true" || value == "false") {
            "Pass -e $EXPECT_NATIVE_ARGUMENT true or false for the positive fixture mode"
        }
        return value == "true"
    }

    private fun createHarness(engine: FogNativeGeometryEngine): Harness {
        val source = SyntheticTrackSource()
        val feed = SyntheticChangeFeed()
        val style = FogRenderStyle()
        val runtime = FogRuntime(
            viewportCoordinator = FogViewportCoordinator(
                trackDataSource = ViewportTrackDataSource(
                    ViewportTrackPointReader { south, north, interval ->
                        source.read(south, north, interval)
                    },
                ),
                pipeline = FogTilePipeline(
                    memoryCache = app.trailveil.map.fog.FogMemoryTileCache(8L * 1024L * 1024L),
                    diskCache = null,
                    renderMask = FogTileRenderer(style)::render,
                ),
                style = style,
                // z16's 5x5 extent leaves this small gesture inside the center tile while also
                // keeping the visible corners within the installed surround.
                mosaicPaddingTiles = CENTER_TILE_PADDING,
                nativeGeometryEngine = engine,
            ),
            pointChanges = feed,
        )
        val observation = Observation()
        val mapView = AtomicReference<MapView?>()
        composeRule.setContent {
            TrailVeilMapSurface(
                modifier = Modifier.fillMaxSize(),
                provider = MapProviderConfiguration(
                    providerName = "u1-idle-reuse",
                    styleUri = "https://tiles.invalid/u1-idle-reuse",
                ),
                fallbackTimeoutMillis = 100L,
                savedStateKey = "trailveil.map.u1.idle-reuse",
                fogRuntime = runtime,
                fogRequired = true,
                cameraRequest = MapCameraRequest(
                    requestId = 1L,
                    point = CENTER,
                    zoom = CAMERA_ZOOM,
                ),
                // Do not install an onFogRendered assertion seam here. The reuse certificate is
                // issued after that callback; a callback that mutates test state can accidentally
                // invalidate the very certificate this test is measuring.
                onMapViewCreatedForTesting = mapView::set,
                onFogCoverageInstalledForTesting = { installed ->
                    observation.installs += InstallStamp(
                        generation = installed.generation,
                        slot = installed.slot.name,
                        halfWorlds = installed.extent.halfWorlds,
                        wrapsWorld = installed.extent.wrapsWorld,
                        uptimeMillis = SystemClock.uptimeMillis(),
                    )
                },
                onFogCoverageStateComposedForTesting = { composed ->
                    observation.compositions += CompositionStamp(
                        generation = composed.generation,
                        coverageInstalled = composed.coverageInstalled,
                        uptimeMillis = SystemClock.uptimeMillis(),
                    )
                },
                onFogViewportRequestedForTesting = { trace ->
                    // Use only the enum's runtime name so this class can run with b47's older enum.
                    observation.traces += TraceStamp(
                        trigger = trace.trigger.name,
                        generation = trace.generation,
                        mapZoom = trace.mapZoom,
                        lastMoveWasGesture = trace.lastMoveWasGesture,
                        uptimeMillis = trace.uptimeMillis,
                    )
                },
            )
        }
        return Harness(
            runtime = runtime,
            source = source,
            feed = feed,
            observation = observation,
            mapView = mapView,
            style = style,
        )
    }

    private fun awaitInitialCanonical(harness: Harness): MapLibreMap {
        composeRule.waitUntil(timeoutMillis = INITIAL_TIMEOUT_MILLIS) {
            harness.mapView.get()?.isAttachedToWindow == true &&
                harness.observation.installs.isNotEmpty()
        }
        val view = requireNotNull(harness.mapView.get())
        val ready = CountDownLatch(1)
        val map = AtomicReference<MapLibreMap?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.getMapAsync {
                map.set(it)
                ready.countDown()
            }
        }
        assertTrue(
            "MapLibre did not become ready",
            ready.await(INITIAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )
        val readyMap = requireNotNull(map.get())
        composeRule.waitUntil(timeoutMillis = INITIAL_TIMEOUT_MILLIS) {
            val camera = composeRule.runOnIdle {
                val target = readyMap.cameraPosition.target
                CameraSnapshot(
                    latitude = target?.latitude,
                    longitude = target?.longitude,
                    zoom = readyMap.cameraPosition.zoom,
                    styleLoaded = readyMap.style?.isFullyLoaded == true,
                )
            }
            val latitude = camera.latitude
            val longitude = camera.longitude
            camera.styleLoaded &&
                latitude != null &&
                longitude != null &&
                kotlin.math.abs(latitude - CENTER.latitude) <= CAMERA_TOLERANCE &&
                kotlin.math.abs(
                    app.trailveil.map.fog.WebMercator.wrapLongitude(
                        longitude - CENTER.longitude,
                    ),
                ) <= CAMERA_TOLERANCE &&
                kotlin.math.abs(camera.zoom - CAMERA_ZOOM) <= ZOOM_TOLERANCE &&
                currentGeneration(harness) != null &&
                composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        // The install callback precedes certificate creation. Let the Compose/main-thread work
        // which follows that callback drain before the first natural idle is measured.
        composeRule.waitForIdle()
        return readyMap
    }

    private fun measureNaturalPans(
        harness: Harness,
        map: MapLibreMap,
        gestureCount: Int,
        expectedReuse: Boolean,
        label: String,
    ): Measurement {
        val reasons = CopyOnWriteArrayList<Int>()
        val reasonListener = MapLibreMap.OnCameraMoveStartedListener { reason -> reasons += reason }
        composeRule.runOnUiThread { map.addOnCameraMoveStartedListener(reasonListener) }
        val outcomes = ArrayList<GestureOutcome>(gestureCount)
        try {
            repeat(gestureCount) { index ->
                // Pointer-state recovery belongs to setup, outside this gesture's trace/install
                // window. It is deliberately unasserted because a clean device rejects CANCEL.
                bestEffortClearStuckInjectedPointers()
                awaitCanonicalQuiescence(harness, map)
                val beforeGeneration = currentGeneration(harness)
                val beforeSlot = harness.observation.installs.lastOrNull()?.slot
                val beforeCamera = currentViewportRequest(map)
                val beforeFootprint = footprint(harness, beforeCamera)
                val beforeInstallCount = harness.observation.installs.size
                val beforeTraceCount = harness.observation.traces.size
                val beforeCompositionCount = harness.observation.compositions.size
                val reasonCountBefore = reasons.size
                val gestureWindow = injectGentlePan(
                    harness = harness,
                    map = map,
                    forward = index % 2 == 0,
                )
                val outcome = awaitSettledGesture(
                    harness = harness,
                    beforeGeneration = beforeGeneration,
                    beforeInstallCount = beforeInstallCount,
                    beforeTraceCount = beforeTraceCount,
                    beforeCompositionCount = beforeCompositionCount,
                    beforeSlot = beforeSlot,
                    pointerDownAt = gestureWindow.pointerDownAt,
                    pointerUpAt = gestureWindow.pointerUpAt,
                    expectedReuse = expectedReuse,
                    label = "$label pan ${index + 1}/$gestureCount",
                )
                val afterCamera = currentViewportRequest(map)
                assertEquals(
                    "A gentle center-tile pan changed the canonical footprint",
                    beforeFootprint,
                    footprint(harness, afterCamera),
                )
                assertTrue(
                    "Pan ${index + 1} did not reach the real API gesture camera path",
                    reasons.drop(reasonCountBefore).any { reason ->
                        reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                    },
                )
                outcomes += outcome
            }
        } finally {
            composeRule.runOnUiThread { map.removeOnCameraMoveStartedListener(reasonListener) }
        }
        val measurement = Measurement(
            label = label,
            expectedReuse = expectedReuse,
            outcomes = outcomes,
            moveReasons = reasons.toList(),
        )
        assertEquals(
            "Every injected gesture must have an observed settled outcome: ${measurement.rows}",
            gestureCount,
            outcomes.size,
        )
        return measurement
    }

    private fun awaitCanonicalQuiescence(harness: Harness, map: MapLibreMap) {
        composeRule.waitForIdle()
        composeRule.waitUntil(INITIAL_TIMEOUT_MILLIS) {
            composeRule.runOnIdle {
                val view = harness.mapView.get()
                val generation = view?.getTag(R.id.map_fog_canonical_generation) as? Long
                val lastInstall = harness.observation.installs.lastOrNull()
                generation != null && generation >= (harness.observation.traces.lastOrNull()?.generation ?: 0L) &&
                    generation == harness.observation.compositions.lastOrNull()?.generation &&
                    generation == lastInstall?.generation &&
                    view.getTag(R.id.map_fog_active_slot) == lastInstall.slot &&
                    map.style?.isFullyLoaded == true &&
                    harness.observation.compositions.lastOrNull()?.coverageInstalled == true
            }
        }
        composeRule.waitForIdle()
    }

    private fun injectGentlePan(
        harness: Harness,
        map: MapLibreMap,
        forward: Boolean,
    ): GestureWindow {
        val view = requireNotNull(composeRule.runOnIdle { harness.mapView.get() })
        val geometry = composeRule.runOnIdle {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            ScreenGeometry(
                centerX = location[0] + view.width / 2f,
                centerY = location[1] + view.height / 2f,
            )
        }
        val centerX = geometry.centerX
        val centerY = geometry.centerY
        val direction = if (forward) 1f else -1f
        val fromX = centerX - direction * PAN_TRAVEL_PX / 2f
        val toX = centerX + direction * PAN_TRAVEL_PX / 2f
        val downTime = SystemClock.uptimeMillis()

        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                x,
                y,
                0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val accepted = try {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .injectInputEvent(event, true)
            } finally {
                event.recycle()
            }
            assertTrue("The injected pan event was rejected", accepted)
        }

        var streamEnded = false
        try {
            send(MotionEvent.ACTION_DOWN, fromX, centerY)
            repeat(PAN_MOVE_COUNT) { move ->
                val fraction = (move + 1).toFloat() / PAN_MOVE_COUNT
                send(
                    MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * fraction,
                    centerY,
                )
                SystemClock.sleep(PAN_MOVE_INTERVAL_MILLIS)
            }
            val pointerUpAt = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_UP, toX, centerY)
            streamEnded = true
            return GestureWindow(pointerDownAt = downTime, pointerUpAt = pointerUpAt)
        } finally {
            // If DOWN/MOVE/UP was rejected or an assertion interrupted the stream, this sends the
            // unasserted one- and two-pointer ACTION_CANCEL probes required by device-testing.md.
            if (!streamEnded) bestEffortClearStuckInjectedPointers()
        }
    }

    private fun awaitSettledGesture(
        harness: Harness,
        beforeGeneration: Long?,
        beforeInstallCount: Int,
        beforeTraceCount: Int,
        beforeCompositionCount: Int,
        beforeSlot: String?,
        pointerDownAt: Long,
        pointerUpAt: Long,
        expectedReuse: Boolean,
        label: String,
    ): GestureOutcome {
        var coverSamples = 0
        var lastEventCount = -1
        var lastChangeAt = SystemClock.uptimeMillis()
        composeRule.waitUntil(timeoutMillis = GESTURE_SETTLE_TIMEOUT_MILLIS) {
            val newTraces = harness.observation.traces.drop(beforeTraceCount)
            val newInstalls = harness.observation.installs.drop(beforeInstallCount)
            val reuse = newTraces.firstOrNull { trace -> trace.trigger == IDLE_REUSED_TRIGGER }
            val idle = newTraces.firstOrNull { trace -> trace.trigger == CAMERA_IDLE_TRIGGER }
            val install = newInstalls.firstOrNull { candidate ->
                beforeGeneration == null || candidate.generation > beforeGeneration
            }
            val coverVisible = composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (coverVisible) coverSamples++
            if (reuse == null && (idle == null || install == null)) {
                lastChangeAt = SystemClock.uptimeMillis()
                lastEventCount = newTraces.size + newInstalls.size
                return@waitUntil false
            }
            val eventCount = newTraces.size + newInstalls.size
            if (eventCount != lastEventCount) lastChangeAt = SystemClock.uptimeMillis()
            lastEventCount = eventCount
            // A held-gesture idle is a documented MapLibre possibility. Let the stream reach its
            // ACTION_UP and then require a quiet main-thread window before taking its final counts.
            if (SystemClock.uptimeMillis() - maxOf(pointerUpAt, lastChangeAt) < QUIET_MILLIS) {
                return@waitUntil false
            }
            val lastGeneration = harness.observation.traces.lastOrNull()?.generation
            currentGeneration(harness) == lastGeneration &&
                harness.observation.installs.lastOrNull()?.generation == lastGeneration
        }
        composeRule.waitForIdle()
        val terminalTraces = harness.observation.traces.drop(beforeTraceCount)
        val terminalInstalls = harness.observation.installs.drop(beforeInstallCount)
        val reuse = terminalTraces.firstOrNull { trace -> trace.trigger == IDLE_REUSED_TRIGGER }
        val idle = terminalTraces.firstOrNull { trace -> trace.trigger == CAMERA_IDLE_TRIGGER }
        val install = terminalInstalls.firstOrNull { candidate ->
            beforeGeneration == null || candidate.generation > beforeGeneration
        }
        val kind = when {
            reuse != null -> IDLE_REUSED_TRIGGER
            idle != null && install != null -> CAMERA_IDLE_TRIGGER
            else -> error(
                "$label did not produce a settled idle callback; " +
                    "traces=$terminalTraces installs=$terminalInstalls",
            )
        }
        val trace = reuse ?: checkNotNull(idle)
        val outcome = GestureOutcome(
            kind = kind,
            trace = trace,
            install = if (kind == CAMERA_IDLE_TRIGGER) install else null,
            beforeGeneration = beforeGeneration,
            afterGeneration = currentGeneration(harness),
            beforeSlot = beforeSlot,
            afterSlot = harness.observation.installs.lastOrNull()?.slot,
            prematureTraceCount = terminalTraces.count { event ->
                event.uptimeMillis >= pointerDownAt && event.uptimeMillis < pointerUpAt
            },
            prematureInstallCount = terminalInstalls.count { event ->
                event.uptimeMillis >= pointerDownAt && event.uptimeMillis < pointerUpAt
            },
            newTraces = terminalTraces,
            newInstalls = terminalInstalls,
            pointerUpAt = pointerUpAt,
            // The callback can happen while the finger is held; retaining its actual timestamp
            // makes that fact visible as a negative pointer-up delta in the status row.
            firstOutcomeAt = if (reuse != null) reuse.uptimeMillis else checkNotNull(install).uptimeMillis,
            lastEventAt = (terminalTraces.map { it.uptimeMillis } + terminalInstalls.map { it.uptimeMillis }).max(),
            observationEndAt = SystemClock.uptimeMillis(),
            coverSamples = coverSamples,
            compositionPulses = compositionPulseCount(
                harness = harness,
                beforeCompositionCount = beforeCompositionCount,
                pointerDownAt = pointerDownAt,
            ),
        )
        // Emit every event before asserting so failed hypotheses remain diagnosable.
        sendMeasurementStatus(Measurement(label, expectedReuse, listOf(outcome), emptyList()))
        if (expectedReuse) {
            assertEquals("$label unexpectedly rebuilt", IDLE_REUSED_TRIGGER, outcome.kind)
            assertEquals("$label changed generation while reusing", outcome.beforeGeneration, outcome.afterGeneration)
            assertEquals("$label changed slot while reusing", outcome.beforeSlot, outcome.afterSlot)
            assertTrue("$label raised a cover during reuse", outcome.coverSamples == 0)
            assertTrue("$label pulsed the cover composition during reuse", outcome.compositionPulses == 0)
            assertTrue(
                "$label emitted a non-reuse trace in the reuse window: ${outcome.newTraces}",
                outcome.newTraces.all { trace -> trace.trigger == IDLE_REUSED_TRIGGER },
            )
            assertEquals(
                "$label installed a late generation after reuse",
                0,
                outcome.newInstalls.size,
            )
        } else {
            assertTrue("$label reused on the old/fallback path", outcome.newTraces.none { it.trigger == IDLE_REUSED_TRIGGER })
            assertTrue("$label never requested an idle rebuild", outcome.newTraces.any { it.trigger == CAMERA_IDLE_TRIGGER })
            assertTrue("$label did not install a generation", outcome.newInstalls.isNotEmpty())
        }
        return outcome
    }

    private fun currentGeneration(harness: Harness): Long? = composeRule.runOnIdle {
        harness.mapView.get()?.getTag(R.id.map_fog_canonical_generation) as? Long
    }

    private fun currentViewportRequest(map: MapLibreMap): FogViewportRequest = composeRule.runOnIdle {
        val target = requireNotNull(map.cameraPosition.target) { "The map has no camera target" }
        FogViewportRequest(
            center = GeoPoint(target.latitude, target.longitude),
            mapZoom = map.cameraPosition.zoom,
        )
    }

    /** A pure signature made from the same stable grid/layout helpers as the production path. */
    private fun footprint(harness: Harness, request: FogViewportRequest): FootprintSignature {
        val zoom = floor(request.mapZoom).toInt().coerceIn(0, 22)
        val keys = FogViewportTileGrid.around(
            center = request.center,
            zoom = zoom,
            renderVersion = app.trailveil.map.fog.FogRenderVersions.CURRENT,
            paddingTiles = CENTER_TILE_PADDING,
        )
        val layout = FogPocMosaic.layout(keys, harness.style.tileSize).anchoredNear(request.center.longitude)
        return FootprintSignature(
            keys = keys,
            bounds = layout.bounds,
            tileCount = layout.tileCount,
            samplingGridWidth = layout.samplingGridWidth,
            samplingGridHeight = layout.samplingGridHeight,
        )
    }

    private fun compositionPulseCount(
        harness: Harness,
        beforeCompositionCount: Int,
        pointerDownAt: Long,
    ): Int = harness.observation.compositions
        .drop(beforeCompositionCount)
        .count { stamp ->
            stamp.uptimeMillis >= pointerDownAt && !stamp.coverageInstalled
        }

    private fun android.view.View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is android.view.ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }

    private fun sendMeasurementStatus(measurement: Measurement) {
        val rows = JSONArray()
        measurement.outcomes.forEachIndexed { index, outcome ->
            rows.put(
                JSONObject()
                    .put("pan", index + 1)
                    .put("expectedReuse", measurement.expectedReuse)
                    .put("actualIdleTrigger", outcome.kind)
                    .put("idleCount", outcome.newTraces.count { it.trigger == CAMERA_IDLE_TRIGGER })
                    .put("reuseCount", outcome.newTraces.count { it.trigger == IDLE_REUSED_TRIGGER })
                    .put("generation", outcome.install?.generation ?: outcome.trace.generation)
                    .put("completedCanonicalInstallCount", outcome.newInstalls.size)
                    .put("traces", JSONArray(outcome.newTraces.map { "${it.trigger}:${it.generation}:${it.uptimeMillis}" }))
                    .put("installs", JSONArray(outcome.newInstalls.map { "${it.generation}:${it.slot}:${it.uptimeMillis}" }))
                    .put("preUpTraceCount", outcome.prematureTraceCount)
                    .put("preUpInstallCount", outcome.prematureInstallCount)
                    .put("beforeGeneration", outcome.beforeGeneration)
                    .put("afterGeneration", outcome.afterGeneration)
                    .put("beforeSlot", outcome.beforeSlot)
                    .put("afterSlot", outcome.afterSlot)
                    .put("pointerUpUptime", outcome.pointerUpAt)
                    .put("firstOutcomeUptime", outcome.firstOutcomeAt)
                    .put("pointerUpToFirstOutcomeMillis", outcome.firstOutcomeAt - outcome.pointerUpAt)
                    .put("lastEventUptime", outcome.lastEventAt)
                    .put("pointerUpToLastEventMillis", outcome.lastEventAt - outcome.pointerUpAt)
                    .put("observedQuietUptime", outcome.observationEndAt)
                    .put("coverSamples", outcome.coverSamples)
                    .put("compositionPulses", outcome.compositionPulses)
            )
        }
        val payload = JSONObject()
            .put("label", measurement.label)
            .put("expectedReuse", measurement.expectedReuse)
            .put("idleCount", measurement.idleCount)
            .put("reuseCount", measurement.reuseCount)
            .put("installCountDelta", measurement.installCountDelta)
            .put("coverPulseCount", measurement.coverPulseCount)
            .put("digest", measurement.digest)
            .put("rows", rows)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", "U1_IDLE_REUSE $payload\n") },
        )
    }

    private data class Harness(
        val runtime: FogRuntime,
        val source: SyntheticTrackSource,
        val feed: SyntheticChangeFeed,
        val observation: Observation,
        val mapView: AtomicReference<MapView?>,
        val style: FogRenderStyle,
    )

    private data class CameraSnapshot(
        val latitude: Double?,
        val longitude: Double?,
        val zoom: Double,
        val styleLoaded: Boolean,
    )

    private data class ScreenGeometry(val centerX: Float, val centerY: Float)

    private data class FootprintSignature(
        val keys: List<FogTileKey>,
        val bounds: FogTileBounds,
        val tileCount: Int,
        val samplingGridWidth: Int,
        val samplingGridHeight: Int,
    )

    private data class TraceStamp(
        val trigger: String,
        val generation: Long,
        val mapZoom: Double?,
        val lastMoveWasGesture: Boolean,
        val uptimeMillis: Long,
    )

    private data class InstallStamp(
        val generation: Long,
        val slot: String,
        val halfWorlds: Double,
        val wrapsWorld: Boolean,
        val uptimeMillis: Long,
    )

    private data class CompositionStamp(
        val generation: Long,
        val coverageInstalled: Boolean,
        val uptimeMillis: Long,
    )

    private class Observation {
        val traces = CopyOnWriteArrayList<TraceStamp>()
        val installs = CopyOnWriteArrayList<InstallStamp>()
        val compositions = CopyOnWriteArrayList<CompositionStamp>()
    }

    private data class GestureOutcome(
        val kind: String,
        val trace: TraceStamp,
        val install: InstallStamp?,
        val beforeGeneration: Long?,
        val afterGeneration: Long?,
        val beforeSlot: String?,
        val afterSlot: String?,
        val prematureTraceCount: Int,
        val prematureInstallCount: Int,
        val newTraces: List<TraceStamp>,
        val newInstalls: List<InstallStamp>,
        val pointerUpAt: Long,
        val firstOutcomeAt: Long,
        val lastEventAt: Long,
        val observationEndAt: Long,
        val coverSamples: Int,
        val compositionPulses: Int,
    )

    private data class GestureWindow(val pointerDownAt: Long, val pointerUpAt: Long)

    private data class Measurement(
        val label: String,
        val expectedReuse: Boolean,
        val outcomes: List<GestureOutcome>,
        val moveReasons: List<Int>,
    ) {
        val idleCount: Int get() = outcomes.sumOf { outcome -> outcome.newTraces.count { it.trigger == CAMERA_IDLE_TRIGGER } }
        val reuseCount: Int get() = outcomes.sumOf { outcome -> outcome.newTraces.count { it.trigger == IDLE_REUSED_TRIGGER } }
        val installCountDelta: Int get() = outcomes.sumOf { it.newInstalls.size }
        val coverPulseCount: Int get() = outcomes.sumOf { it.coverSamples + it.compositionPulses }
        val rows: String get() = outcomes.joinToString(prefix = "[", postfix = "]") { outcome ->
            "${outcome.kind}:g=${outcome.install?.generation ?: outcome.trace.generation}:" +
                "firstCallbackDt=${outcome.firstOutcomeAt - outcome.pointerUpAt}:cover=${outcome.coverSamples}:" +
                "pulses=${outcome.compositionPulses}"
        }
        val digest: String
            get() {
                val digest = MessageDigest.getInstance("SHA-256")
                outcomes.forEach { outcome ->
                    digest.update(
                        (
                            "${outcome.kind}|${outcome.trace.generation}|" +
                                "${outcome.install?.slot ?: "reuse"}|" +
                                "${outcome.install?.wrapsWorld ?: false}|" +
                                "${outcome.trace.mapZoom?.let { "%.3f".format(java.util.Locale.US, it) }}|" +
                                "${outcome.trace.lastMoveWasGesture}|"
                        ).toByteArray(Charsets.UTF_8),
                    )
                }
                return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
    }

    private class SyntheticTrackSource {
        @Volatile
        private var points: List<ViewportTrackPoint> = listOf(
            ViewportTrackPoint(
                pointId = 1L,
                sessionId = 1L,
                segmentId = 1L,
                segmentSequence = 0L,
                pointSequence = 0L,
                latitude = CENTER.latitude,
                longitude = CENTER.longitude,
            ),
        )

        fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> =
            points.filter { point ->
                point.latitude in south..north && point.longitude in interval.west..interval.east
            }

        fun append(point: ViewportTrackPoint) { points = points + point }
        fun clear() { points = emptyList() }
    }

    private class SyntheticChangeFeed : PersistedTrackPointChangeFeed {
        private val revisions = MutableSharedFlow<PersistedPointRevision>(replay = 1, extraBufferCapacity = 1)
        private val changes = CopyOnWriteArrayList<PersistedTrackPointChange>()

        override suspend fun latestCursor(): PersistedPointCursor =
            PersistedPointCursor(changes.lastOrNull()?.point?.pointId ?: initialCursor)

        private var initialCursor = 1L

        override fun revisionsAfter(cursor: PersistedPointCursor): Flow<PersistedPointRevision> = revisions

        override suspend fun readChangesAfter(
            cursor: PersistedPointCursor,
            limit: Int,
        ): List<PersistedTrackPointChange> = changes
            .filter { change -> change.point.pointId > cursor.pointId }
            .take(limit)

        fun append(point: PersistedTrackPoint, previous: PersistedTrackPoint? = null) {
            changes += PersistedTrackPointChange(point, previous)
            revisions.tryEmit(PersistedPointRevision(PersistedPointCursor(point.pointId)))
        }

        fun reset() {
            changes.clear()
            initialCursor = 0L
            revisions.tryEmit(PersistedPointRevision(PersistedPointCursor(0L)))
        }
    }

    private class RectangleNativeEngine : FogNativeGeometryEngine {
        val lastReadPointCount = AtomicInteger()
        override val description: String = "u1[native-rectangle]"

        override suspend fun render(
            bounds: app.trailveil.map.fog.FogTileBounds,
            style: FogRenderStyle,
            read: suspend (app.trailveil.data.map.ViewportBounds) -> List<TrackSegment>,
        ): FogNativeGeometry {
            lastReadPointCount.set(read(ViewportBounds(bounds.southLatitude, bounds.northLatitude, bounds.westLongitude, bounds.eastLongitude)).sumOf { it.points.size })
            return FogNativeGeometry(
            bounds = bounds,
            polygons = listOf(
                FogNativePolygon(
                    shell = listOf(
                        GeoPoint(bounds.southLatitude, bounds.westLongitude),
                        GeoPoint(bounds.southLatitude, bounds.eastLongitude),
                        GeoPoint(bounds.northLatitude, bounds.eastLongitude),
                        GeoPoint(bounds.northLatitude, bounds.westLongitude),
                        GeoPoint(bounds.southLatitude, bounds.westLongitude),
                    ),
                ),
            ),
            diagnostics = "u1[native-rectangle]",
        )
        }

        override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) = Unit

        override fun clear() = Unit
    }

    private class NullNativeFallbackEngine : FogNativeGeometryEngine {
        val renderCalls = AtomicInteger()
        override val description: String = "u1[native-null-fallback]"

        override suspend fun render(
            bounds: app.trailveil.map.fog.FogTileBounds,
            style: FogRenderStyle,
            read: suspend (app.trailveil.data.map.ViewportBounds) -> List<TrackSegment>,
        ): FogNativeGeometry? {
            renderCalls.incrementAndGet()
            return null
        }

        override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) = Unit

        override fun clear() = Unit
    }

    private companion object {
        const val EXPECT_REUSE_ARGUMENT = "u1ExpectReuse"
        const val EXPECT_NATIVE_ARGUMENT = "u1Native"
        const val QUIET_MILLIS = 300L
        const val IDLE_REUSED_TRIGGER = "CAMERA_IDLE_REUSED"
        const val CAMERA_IDLE_TRIGGER = "CAMERA_IDLE"
        const val INITIAL_TIMEOUT_MILLIS = 60_000L
        const val GESTURE_SETTLE_TIMEOUT_MILLIS = 30_000L
        const val CENTER_TILE_PADDING = 2
        const val CAMERA_ZOOM = 16.25
        const val CAMERA_TOLERANCE = 0.001
        const val ZOOM_TOLERANCE = 0.08
        const val PAN_TRAVEL_PX = 48f
        const val PAN_MOVE_COUNT = 8
        const val PAN_MOVE_INTERVAL_MILLIS = 24L
        const val NATURAL_PAN_COUNT = 7
        const val FALLBACK_PAN_COUNT = 2
        val CENTER = GeoPoint(25.0330, 121.5654)
    }
}
