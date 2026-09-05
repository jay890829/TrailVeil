package app.trailveil.feature.history

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.get
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.MainActivity
import app.trailveil.TrailVeilApplication
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.googlepoc.SpikeEvidence
import app.trailveil.googlepoc.GoogleRendererPin
import app.trailveil.map.GoogleMapOverlayTestHooks
import app.trailveil.map.GoogleMapOverlayObservation
import app.trailveil.map.GoogleMapMarkerObservation
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Permanent Stage-8 detail-map acceptance plus the retained SP3 pixel measurement. */
@RunWith(AndroidJUnit4::class)
class GoogleHistoryDetailPopTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val createdFixtureIds = ArrayList<Long>()
    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: app.trailveil.feature.recording.PermissionHistory? = null

    @Before
    fun setUp() {
        assertTrue(
            "SP3 needs the keyed googlePoc runtime; keyless hosted jobs compile this harness only",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        permissionHistory = PermissionHistoryStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        originalPermissionHistory = runBlocking { permissionHistory.current() }
        runBlocking {
            permissionHistory.replaceForTesting(
                requireNotNull(originalPermissionHistory).copy(hasSeenIntroduction = true),
            )
        }
    }

    @After
    fun tearDown() {
        GoogleMapOverlayTestHooks.onObservation = null
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        runBlocking {
            createdFixtureIds.forEach { id ->
                application.appContainer.databaseForTesting().recordingDao().deleteSession(id)
            }
            val original = originalPermissionHistory
            if (original != null) permissionHistory.replaceForTesting(original)
        }
    }

    @Test
    fun detailSurfaceProtocolReportsRenderClipPopScrollAndAttribution() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val rendererPin = GoogleRendererPin.initialize(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            requested = "latest",
        )
        val observations = CopyOnWriteArrayList<GoogleMapOverlayObservation>()
        GoogleMapOverlayTestHooks.onObservation = { observations += it }
        val fixtureId = createCompletedSession(application)
        dismissDisclosureIfShown()
        openFixtureHistoryList(fixtureId)

        val listReference = takeWholeWindowScreenshot()
        val listConfirmation = takeWholeWindowScreenshot()
        val listStability = difference(
            fingerprint(listReference, Rect(0, 0, listReference.width, listReference.height)),
            listConfirmation,
        )

        openFixtureDetailFromList(fixtureId)
        val mapView = awaitDetailMapView()
        val map = awaitGoogleMap(mapView)
        awaitCameraSettled(map)
        assertTrue(
            "detail fit did not observe the real OnMapLoadedCallback",
            awaitDetailMapLoaded(mapView),
        )
        assertTwoPointCameraFit(map)
        assertPolylineObservation(observations)
        val detailBoundsBeforeScroll = mapView.boundsOnScreen()
        val detailReference = takeWholeWindowScreenshot()
        val renderSurface = findRenderSurface(mapView)
        val renderClass = renderSurface?.javaClass?.name ?: "NONE"
        val clip = measureRoundedClip(
            screenshot = detailReference,
            bounds = detailBoundsBeforeScroll,
        )
        val logo = locateAttributionCandidates(mapView, detailBoundsBeforeScroll)
        val attributionPixels = assertAttributionPixels(
            mapView = mapView,
            observation = logo,
            screenshot = detailReference,
        )

        val beforeMapDrag = cameraTarget(map)
        val beforeMapDragBounds = mapView.boundsOnScreen()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap)
            .performTouchInput { swipeUp() }
        val mapDragMoved = awaitCameraMoved(map, beforeMapDrag)
        val afterMapDragBounds = mapView.boundsOnScreen()
        assertTrue("drag inside detail map did not move the real Google camera", mapDragMoved)
        assertEquals(
            "dragging the detail map moved the vertical-scroll container",
            beforeMapDragBounds.top,
            afterMapDragBounds.top,
        )

        val beforeScroll = mapView.boundsOnScreen()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Detail)
            .performTouchInput {
                // The map is in the middle of the detail column. Starting at the column's
                // bottom edge keeps this stream outside the MapView and exercises verticalScroll
                // rather than map panning.
                swipeUp(startY = bottom - 4f, endY = bottom - 304f)
            }
        composeRule.waitForIdle()
        SystemClock.sleep(SCROLL_SETTLE_MILLIS)
        val afterScroll = mapView.boundsOnScreen()
        val scrollDeltaY = afterScroll.top - beforeScroll.top
        assertTrue(
            "dragging below the detail map did not scroll the vertical-scroll container",
            kotlin.math.abs(scrollDeltaY) > DETAIL_SCROLL_TOLERANCE_PX,
        )

        // The gesture-ownership step above deliberately PANNED the detail camera, which moves most
        // of the two-point track off screen. Calibrating the pop oracle on that frame is what made
        // it unstable: the run before this correction found only 4 track pixels against a required
        // 3, so any small change in fling distance or grid alignment dropped it below the floor.
        // Return to the list and re-enter detail, so the oracle calibrates on a freshly fitted,
        // freshly rendered surface whose load is confirmed by the real OnMapLoadedCallback rather
        // than on one this test just dragged and scrolled off-screen and back.
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performScrollTo()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyListIsShowing() }
        openFixtureDetailFromList(fixtureId)
        val popMapView = awaitDetailMapView()
        val popMap = awaitGoogleMap(popMapView)
        assertTrue(
            "re-entered detail did not observe the real OnMapLoadedCallback",
            awaitDetailMapLoaded(popMapView),
        )
        awaitCameraSettled(popMap)
        assertTwoPointCameraFit(popMap)
        // Prove the real SDK polyline exists on the re-entered map before any pixel is trusted.
        assertPolylineObservation(observations)
        // Read the camera while the map is still ALIVE. The old code asserted on a MapView the pop
        // had already destroyed, which only happened to work.
        val popCameraTarget = AtomicReference<LatLng>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            popCameraTarget.set(popMap.cameraPosition.target)
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).performScrollTo()
        composeRule.waitForIdle()
        val popBounds = popMapView.boundsOnScreen()
        val popDetail = takeWholeWindowScreenshot()
        val popDetailFingerprint = fingerprint(popDetail, popBounds)
        // Locate the signature at the rendered track itself instead of hoping the coarse
        // classification grid happens to land on a 5 dp line. This does not relax the ghost
        // oracle — `mapLike` still requires MINIMUM_TRACK_SIGNATURE_SAMPLES matching track pixels;
        // it gives that requirement far more places to be satisfied, so a real ghost frame is
        // MORE likely to be caught, not less.
        val trackSignature = locateTrackSignature(popDetail, popBounds)
        assertTrue(
            "pop reference frame contained no rendered track pixels to calibrate on " +
                "(found ${trackSignature.size}); the oracle cannot detect a ghost it cannot " +
                "recognise, so this is a calibration failure and not a passing pop",
            trackSignature.size >= MINIMUM_TRACK_SIGNATURE_SAMPLES,
        )
        val capture = startTransitionCapture(
            mapBounds = popBounds,
            mapReference = popDetailFingerprint,
            listReference = fingerprint(listReference, popBounds),
            trackSignature = trackSignature,
        )
        val popStartedAt = SystemClock.uptimeMillis()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performScrollTo()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyListIsShowing() }
        val listVisibleAt = SystemClock.uptimeMillis()
        val mapDestroyedAt = awaitMapDestroyed(popMapView, listVisibleAt)
        SystemClock.sleep(POST_EXIT_CAPTURE_MILLIS)
        capture.stop()
        val popSummary = capture.summarize(
            windowStartMillis = popStartedAt,
            listVisibleAtMillis = listVisibleAt,
            mapDestroyedAtMillis = mapDestroyedAt,
        )

        // Measure the actual main-map -> history forward exit. Warm the route once to obtain a
        // settled history-list negative reference, return to the main map, then arm the pixel
        // window immediately before the real menu/history navigation.
        androidx.test.espresso.Espresso.pressBack()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingEntryTestTags.Menu)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val warmMainMapView = awaitMainMapView()
        awaitGoogleMap(warmMainMapView)
        SystemClock.sleep(MAIN_REFERENCE_SETTLE_MILLIS)
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyListIsShowing() }
        SystemClock.sleep(MAIN_REFERENCE_SETTLE_MILLIS)
        val forwardHistoryReference = takeWholeWindowScreenshot()
        val mainMapView = run {
            androidx.test.espresso.Espresso.pressBack()
            composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(RecordingEntryTestTags.Menu)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            awaitMainMapView()
        }
        awaitGoogleMap(mainMapView)
        SystemClock.sleep(MAIN_REFERENCE_SETTLE_MILLIS)
        val mainBounds = mainMapView.boundsOnScreen()
        val mainReference = takeWholeWindowScreenshot()
        val forwardCapture = startTransitionCapture(
            mapBounds = mainBounds,
            mapReference = fingerprint(mainReference, mainBounds),
            listReference = fingerprint(forwardHistoryReference, mainBounds),
            requireTrackSignature = false,
        )
        val forwardStartedAt = SystemClock.uptimeMillis()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyListIsShowing() }
        val forwardHistoryVisibleAt = SystemClock.uptimeMillis()
        SystemClock.sleep(POST_EXIT_CAPTURE_MILLIS)
        forwardCapture.stop()
        val forwardSummary = forwardCapture.summarize(
            windowStartMillis = forwardStartedAt,
            listVisibleAtMillis = forwardHistoryVisibleAt,
            mapDestroyedAtMillis = Long.MAX_VALUE,
        )

        val summary = buildString {
            append("SP3 detail-render")
            append(" api=").append(android.os.Build.VERSION.SDK_INT)
            append(" product=").append(android.os.Build.PRODUCT)
            append(" rendererRequested=LATEST")
            append(" rendererGranted=").append(rendererPin.granted)
            append(" rendererCollapsed=").append(rendererPin.collapsed)
            append(" rendererView=").append(renderClass)
            append(" rendererKind=").append(
                when (renderSurface) {
                    is SurfaceView -> "SurfaceView"
                    is TextureView -> "TextureView"
                    null -> "NONE"
                    else -> "OTHER"
                },
            )
            append(" mapSize=").append(detailBoundsBeforeScroll.width())
                .append('x').append(detailBoundsBeforeScroll.height())
            append(" clipEffective=").append(clip.effective)
            append(" clipCornerOutsideDistance=")
                .append(format(clip.cornerOutsideDistance))
            append(" clipCornerInteriorDistance=")
                .append(format(clip.cornerInteriorDistance))
            append(" attributionCandidates=").append(logo.count)
            append(" attributionVisible=").append(logo.visible)
            append(" attributionInsideMap=").append(logo.insideMap)
            append(" attributionSize=").append(logo.width).append('x').append(logo.height)
            append(" attributionPixelSamples=").append(attributionPixels.samples)
            append(" attributionPixelMatches=").append(attributionPixels.matches)
            append(" attributionPixelMatchRatio=").append(format(attributionPixels.matchRatio))
            append(" mapGestureMoved=").append(mapDragMoved)
            append(" mapGestureScrollDeltaY=").append(
                afterMapDragBounds.top - beforeMapDragBounds.top,
            )
            append(" scrollDeltaY=").append(scrollDeltaY)
            append(" listStableDifference=").append(format(listStability))
            append(" popFrames=").append(popSummary.frames)
            append(" popListVisibleFrames=").append(popSummary.listVisibleFrames)
            append(" popMapLikeAfterList=").append(popSummary.mapLikeAfterList)
            append(" popMapLikeAfterDestroy=").append(popSummary.mapLikeAfterDestroy)
            append(" popBlankFramesAfterClick=").append(popSummary.blankFramesAfterClick)
            append(" popTrackSignatureSamples=").append(capture.trackSignatureSamples)
            append(" popDestroyedMs=").append(mapDestroyedAt - listVisibleAt)
            append(" forwardFrames=").append(forwardSummary.frames)
            append(" forwardMapLike=").append(forwardSummary.mapLikeAfterList)
            append(" forwardBlankFrames=").append(forwardSummary.blankFramesAfterClick)
            append(" forwardAmbiguousFrames=").append(forwardSummary.ambiguousFramesAfterClick)
            append(" sp3MeasurementValid=")
                .append(renderSurface != null && detailBoundsBeforeScroll.width() > 0)
            append("\n")
        }
        SpikeEvidence.emit(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            fileName = SP3_FILE,
            line = summary,
        )

        // Keep the permanent acceptance assertions next to the measurement so a passing status
        // line cannot hide a broken live detail surface.
        assertTrue("detail MapView had no measured layout", detailBoundsBeforeScroll.width() > 0)
        assertTrue(
            "detail map never produced a finite camera target",
            requireNotNull(popCameraTarget.get()).isFinite(),
        )
        assertEquals(0, popSummary.mapLikeAfterDestroy)
        assertEquals(0, popSummary.blankFramesAfterClick)
        assertEquals(0, forwardSummary.mapLikeAfterList)
        assertEquals(0, forwardSummary.blankFramesAfterList)
        assertTrue(
            "detail pixel oracle found no stable track signature",
            capture.trackSignatureSamples >= MINIMUM_TRACK_SIGNATURE_SAMPLES,
        )

        listReference.recycle()
        listConfirmation.recycle()
        detailReference.recycle()
        popDetail.recycle()
        mainReference.recycle()
        forwardHistoryReference.recycle()
        GoogleMapOverlayTestHooks.onObservation = null
    }

    @Test
    fun datelineDetailFitsTheShortArcOnTheRealGoogleMap() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val mapViewAndMap = openDetailWithPoints(
            application,
            listOf(
                GeoPoint(latitude = 10.0, longitude = 179.0),
                GeoPoint(latitude = 11.0, longitude = -179.0),
            ),
        )
        val map = mapViewAndMap.second
        val target = awaitTarget(map) { point ->
            point.latitude in 10.0..11.0 &&
                minOf(
                    kotlin.math.abs(point.longitude - 180.0),
                    kotlin.math.abs(point.longitude + 180.0),
                ) < 2.0
        }
        assertTrue("dateline fit escaped the short arc: $target", target.latitude in 10.0..11.0)
        assertTrue(
            "dateline fit selected the long way around: $target",
            minOf(
                kotlin.math.abs(target.longitude - 180.0),
                kotlin.math.abs(target.longitude + 180.0),
            ) < 2.0,
        )
        assertTrue(
            "dateline detail MapView did not have a measured layout",
            mapViewAndMap.first.width > 0 && mapViewAndMap.first.height > 0,
        )
        val visibleBounds = visibleBounds(map)
        assertTrue(
            "dateline southwest endpoint was outside the fitted visible region",
            visibleBounds.contains(LatLng(10.0, 179.0)),
        )
        assertTrue(
            "dateline northeast endpoint was outside the fitted visible region",
            visibleBounds.contains(LatLng(11.0, -179.0)),
        )
        assertTrue(
            "dateline bounds fit selected an effectively world-wide zoom",
            mapCameraZoom(map) > DATELINE_MIN_ZOOM,
        )
    }

    @Test
    fun singletonDetailUsesZoomSixteenOnTheRealGoogleMap() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val observations = CopyOnWriteArrayList<GoogleMapOverlayObservation>()
        GoogleMapOverlayTestHooks.onObservation = { observations += it }
        val mapViewAndMap = openDetailWithPoints(
            application,
            listOf(GeoPoint(latitude = 35.0, longitude = -120.0)),
        )
        val mapView = mapViewAndMap.first
        val map = mapViewAndMap.second
        assertTrue("singleton detail callback tag was not true", awaitDetailMapLoaded(mapView))
        val target = awaitTarget(map) { point ->
            kotlin.math.abs(point.latitude - 35.0) < 0.0001 &&
                kotlin.math.abs(point.longitude + 120.0) < 0.0001
        }
        assertEquals(
            "singleton target=$target laidOut=${mapView.isLaidOut} " +
                "size=${mapView.width}x${mapView.height}",
            35.0,
            target.latitude,
            0.0001,
        )
        assertEquals(-120.0, target.longitude, 0.0001)
        assertEquals(16.0f, mapCameraZoom(map), 0.1f)
        val marker = awaitSingletonMarkerObservation(observations)
        assertEquals(35.0, marker.position.latitude, 0.0001)
        assertEquals(-120.0, marker.position.longitude, 0.0001)
        assertTrue(marker.visible)
        assertEquals(null, marker.title)
        assertEquals(null, marker.snippet)
    }

    @Test
    fun duplicateSingletonDetailUsesZoomSixteenRatherThanDegenerateBounds() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val map = openDetailWithPoints(
            application,
            listOf(
                GeoPoint(latitude = 35.0, longitude = -120.0),
                GeoPoint(latitude = 35.0, longitude = -120.0),
            ),
        ).second
        val target = awaitTarget(map) { point ->
            kotlin.math.abs(point.latitude - 35.0) < 0.0001 &&
                kotlin.math.abs(point.longitude + 120.0) < 0.0001
        }
        assertEquals(35.0, target.latitude, 0.0001)
        assertEquals(-120.0, target.longitude, 0.0001)
        assertEquals(16.0f, mapCameraZoom(map), 0.1f)
    }

    @Test
    fun sameLongitudeDifferentLatitudesUseBoundsFitOnTheRealGoogleMap() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val map = openDetailWithPoints(
            application,
            listOf(
                GeoPoint(latitude = 25.0, longitude = 121.0),
                GeoPoint(latitude = 26.0, longitude = 121.0),
            ),
        ).second
        val target = awaitTarget(map) { point -> point.latitude in 25.0..26.0 }
        assertTrue(target.latitude in 25.0..26.0)
        assertTrue(
            "same-longitude bounds fit unexpectedly retained singleton zoom",
            mapCameraZoom(map) < 16.0f,
        )
    }

    @Test
    fun ordinaryMultiPointFitLeavesTheRequiredPaddingAroundEveryEndpoint() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val points = listOf(
            GeoPoint(latitude = 25.0, longitude = 121.0),
            GeoPoint(latitude = 26.0, longitude = 123.0),
        )
        val mapViewAndMap = openDetailWithPoints(application, points)
        val mapView = mapViewAndMap.first
        val map = mapViewAndMap.second
        awaitTarget(map) { point -> point.latitude in 25.0..26.0 && point.longitude in 121.0..123.0 }
        assertTrackEndpointsInsetByPadding(mapView, map, points)
        assertTrue(
            "ordinary bounds fit unexpectedly retained singleton zoom",
            mapCameraZoom(map) < 16.0f,
        )
    }

    private fun assertTrackEndpointsInsetByPadding(
        mapView: MapView,
        map: GoogleMap,
        points: List<GeoPoint>,
    ) {
        val projected = ArrayList<android.graphics.Point>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val projection = map.projection
            points.forEach { point ->
                projected += projection.toScreenLocation(
                    LatLng(point.latitude, point.longitude),
                )
            }
        }
        val width = mapView.width
        val height = mapView.height
        assertTrue("ordinary detail map was not laid out", width > 0 && height > 0)
        projected.forEachIndexed { index, screenPoint ->
            assertTrue(
                "endpoint $index is too close to the left edge: $screenPoint",
                screenPoint.x >= DETAIL_BOUNDS_PADDING_PX - SCREEN_ROUNDING_TOLERANCE_PX,
            )
            assertTrue(
                "endpoint $index is too close to the right edge: $screenPoint",
                screenPoint.x <= width - DETAIL_BOUNDS_PADDING_PX + SCREEN_ROUNDING_TOLERANCE_PX,
            )
            assertTrue(
                "endpoint $index is too close to the top edge: $screenPoint",
                screenPoint.y >= DETAIL_BOUNDS_PADDING_PX - SCREEN_ROUNDING_TOLERANCE_PX,
            )
            assertTrue(
                "endpoint $index is too close to the bottom edge: $screenPoint",
                screenPoint.y <= height - DETAIL_BOUNDS_PADDING_PX + SCREEN_ROUNDING_TOLERANCE_PX,
            )
        }
    }

    private fun dismissDisclosureIfShown() {
        repeat(DISCLOSURE_POLLS) {
            if (
                composeRule.onAllNodesWithTag(RecordingEntryTestTags.PrivacySheet)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacyDismiss).performClick()
                composeRule.waitForIdle()
                return
            }
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun openFixtureHistoryList(fixtureId: Long) {
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingHistoryTestTags.item(fixtureId))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.List).assertIsDisplayed()
        composeRule.waitForIdle()
    }

    private fun openFixtureDetailFromList(fixtureId: Long) {
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(fixtureId)).performScrollTo()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(fixtureId)).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingHistoryTestTags.TrackMap)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).assertIsDisplayed()
    }

    private fun historyListIsShowing(): Boolean =
        composeRule.onAllNodesWithTag(RecordingHistoryTestTags.List)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun openDetailWithPoints(
        application: TrailVeilApplication,
        points: List<GeoPoint>,
    ): Pair<MapView, GoogleMap> {
        val fixtureId = createCompletedSession(application, points)
        dismissDisclosureIfShown()
        openFixtureHistoryList(fixtureId)
        openFixtureDetailFromList(fixtureId)
        val mapView = awaitDetailMapView()
        val map = awaitGoogleMap(mapView)
        assertTrue(
            "detail fit helper did not observe the real OnMapLoadedCallback",
            awaitDetailMapLoaded(mapView),
        )
        awaitCameraSettled(map)
        return mapView to map
    }

    private fun awaitDetailMapView(): MapView {
        val found = AtomicReference<MapView?>()
        val diagnostics = AtomicReference("")
        val deadline = SystemClock.uptimeMillis() + MAP_READY_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline && found.get() == null) {
            composeRule.runOnIdle {
                val maps = composeRule.activity.window.decorView.findMapViews()
                    .filter { it.isAttachedToWindow }
                diagnostics.set(
                    maps.joinToString { map ->
                        "${map.javaClass.name}:${map.width}x${map.height}:" +
                            "${map.left},${map.top}:parent=${map.parent?.javaClass?.name}"
                    },
                )
                found.set(
                    maps.filter { it.height in 160..600 }.maxByOrNull { it.height }
                        ?: maps.filter { it.width > 0 && it.height > 0 }.minByOrNull { it.height },
                )
            }
            if (found.get() == null) SystemClock.sleep(POLL_MILLIS)
        }
        return requireNotNull(found.get()) {
            "No attached Google detail MapView; candidates=${diagnostics.get()}"
        }
    }

    private fun awaitMainMapView(): MapView {
        val found = AtomicReference<MapView?>()
        val deadline = SystemClock.uptimeMillis() + MAP_READY_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline && found.get() == null) {
            composeRule.runOnIdle {
                found.set(
                    composeRule.activity.window.decorView.findMapViews()
                        .filter { it.width > 0 && it.height > 0 }
                        .maxByOrNull { it.height },
                )
            }
            if (found.get() == null) SystemClock.sleep(POLL_MILLIS)
        }
        return requireNotNull(found.get()) { "No attached Google main MapView" }
    }

    private fun awaitGoogleMap(mapView: MapView): GoogleMap {
        val map = AtomicReference<GoogleMap?>()
        val ready = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getMapAsync {
                map.set(it)
                ready.countDown()
            }
        }
        assertTrue("Google detail map never became ready", ready.await(30, TimeUnit.SECONDS))
        return requireNotNull(map.get())
    }

    private fun awaitCameraSettled(map: GoogleMap) {
        val deadline = SystemClock.uptimeMillis() + CAMERA_SETTLE_TIMEOUT_MILLIS
        var previous: LatLng? = null
        var stable = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val current = AtomicReference<LatLng>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                current.set(map.cameraPosition.target)
            }
            val target = requireNotNull(current.get())
            if (target.latitude.isFinite() && target.longitude.isFinite()) {
                stable = if (
                    previous != null &&
                        kotlin.math.abs(previous!!.latitude - target.latitude) +
                        kotlin.math.abs(previous!!.longitude - target.longitude) <=
                        CAMERA_STABLE_EPSILON_DEGREES
                ) {
                    stable + 1
                } else {
                    0
                }
                previous = target
                if (stable >= CAMERA_STABLE_POLLS) return
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        assertTrue("Google detail map camera did not settle", stable >= CAMERA_STABLE_POLLS)
    }

    private fun awaitDetailMapLoaded(mapView: MapView): Boolean {
        val deadline = SystemClock.uptimeMillis() + MAP_READY_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val loaded = AtomicReference<Any?>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                loaded.set(mapView.getTag(app.trailveil.R.id.map_detail_map_loaded))
            }
            if (loaded.get() == true) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun cameraTarget(map: GoogleMap): LatLng {
        val target = AtomicReference<LatLng>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            target.set(map.cameraPosition.target)
        }
        return requireNotNull(target.get())
    }

    private fun assertTwoPointCameraFit(map: GoogleMap) {
        val target = awaitTarget(map) { point ->
            point.latitude in 25.0330..25.0340 &&
                point.longitude in 121.5654..121.5664
        }
        assertTrue(
            "detail camera latitude escaped the persisted track: $target",
            target.latitude in 25.0330..25.0340,
        )
        assertTrue(
            "detail camera longitude escaped the persisted track: $target",
            target.longitude in 121.5654..121.5664,
        )
        assertTrue(
            "detail camera zoom was not finite: ${mapCameraZoom(map)}",
            mapCameraZoom(map).isFinite(),
        )
    }

    private fun assertPolylineObservation(
        observations: List<GoogleMapOverlayObservation>,
    ) {
        val deadline = SystemClock.uptimeMillis() + OVERLAY_OBSERVATION_TIMEOUT_MILLIS
        var match: GoogleMapOverlayObservation? = null
        while (SystemClock.uptimeMillis() < deadline && match == null) {
            match = observations.lastOrNull { observation ->
                observation.polylines.size == 1 && observation.polylines.single().visible
            }
            if (match == null) SystemClock.sleep(POLL_MILLIS)
        }
        val line = requireNotNull(match) {
            "detail track polyline was not observed on the real GoogleMap"
        }.polylines.single()
        assertEquals(2, line.points.size)
        assertEquals(android.graphics.Color.argb(229, 0x6A, 0x1B, 0x9A), line.color)
        assertEquals(5.0f, line.width, 0.0f)
        assertEquals(229, line.alpha)
        assertEquals(Float.MAX_VALUE, line.zIndex, 0.0f)
        assertFalse(line.geodesic)
    }

    private fun mapCameraZoom(map: GoogleMap): Float {
        val zoom = AtomicReference<Float>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            zoom.set(map.cameraPosition.zoom)
        }
        return requireNotNull(zoom.get())
    }

    private fun awaitSingletonMarkerObservation(
        observations: List<GoogleMapOverlayObservation>,
    ): GoogleMapMarkerObservation {
        val deadline = SystemClock.uptimeMillis() + OVERLAY_OBSERVATION_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            observations.lastOrNull { observation ->
                observation.trackMarkers.size == 1 && observation.trackMarkers.single().visible
            }?.let { return it.trackMarkers.single() }
            SystemClock.sleep(POLL_MILLIS)
        }
        return requireNotNull(
            observations.lastOrNull { observation -> observation.trackMarkers.size == 1 }
                ?.trackMarkers
                ?.singleOrNull(),
        ) { "detail singleton marker was not observed on the real GoogleMap" }
    }

    private fun visibleBounds(map: GoogleMap): com.google.android.gms.maps.model.LatLngBounds {
        val bounds = AtomicReference<com.google.android.gms.maps.model.LatLngBounds>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bounds.set(map.projection.visibleRegion.latLngBounds)
        }
        return requireNotNull(bounds.get())
    }

    private fun awaitCameraMoved(map: GoogleMap, before: LatLng): Boolean {
        val deadline = SystemClock.uptimeMillis() + CAMERA_MOVE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (cameraDistance(before, cameraTarget(map)) > CAMERA_MOVE_EPSILON_DEGREES) {
                return true
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        return cameraDistance(before, cameraTarget(map)) > CAMERA_MOVE_EPSILON_DEGREES
    }

    private fun cameraDistance(first: LatLng, second: LatLng): Double =
        kotlin.math.abs(first.latitude - second.latitude) +
            kotlin.math.abs(first.longitude - second.longitude)

    private fun awaitTarget(map: GoogleMap, predicate: (LatLng) -> Boolean): LatLng {
        val deadline = SystemClock.uptimeMillis() + CAMERA_FIT_TIMEOUT_MILLIS
        var latest = cameraTarget(map)
        while (SystemClock.uptimeMillis() < deadline) {
            latest = cameraTarget(map)
            if (predicate(latest)) return latest
            SystemClock.sleep(POLL_MILLIS)
        }
        return latest
    }

    private fun awaitMapDestroyed(mapView: MapView, listVisibleAtMillis: Long): Long {
        var destroyed = false
        val deadline = listVisibleAtMillis + DETAIL_TEARDOWN_BUDGET_MILLIS
        while (!destroyed && SystemClock.uptimeMillis() < deadline) {
            composeRule.runOnIdle {
                destroyed = !mapView.isAttachedToWindow && mapView.parent == null
            }
            if (!destroyed) SystemClock.sleep(POLL_MILLIS)
        }
        val destroyedAt = SystemClock.uptimeMillis()
        assertTrue(
            "detail MapView was not detached/destroyed within " +
                "$DETAIL_TEARDOWN_BUDGET_MILLIS ms of list visibility",
            destroyed && destroyedAt - listVisibleAtMillis <= DETAIL_TEARDOWN_BUDGET_MILLIS,
        )
        return destroyedAt
    }

    private fun createCompletedSession(
        application: TrailVeilApplication,
        points: List<GeoPoint> = DEFAULT_TRACK_POINTS,
    ): Long {
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val started = runBlocking {
            dao.startSession(
                session = app.trailveil.data.db.RecordingSessionEntity(
                    startedAt = FIXTURE_STARTED_AT,
                    status = app.trailveil.data.db.RecordingStatus.ACTIVE,
                    createdAppVersion = "google-sp3-test",
                ),
                initialSegment = app.trailveil.data.db.TrackSegmentEntity(
                    sessionId = 0L,
                    sequence = 0L,
                    startedAt = FIXTURE_STARTED_AT,
                    startReason = "SESSION_START",
                ),
            )
        }
        createdFixtureIds += started.sessionId
        runBlocking {
            points.forEachIndexed { index, point ->
                dao.appendAcceptedPoint(
                    point = app.trailveil.data.db.TrackPointEntity(
                        sessionId = started.sessionId,
                        segmentId = started.segmentId,
                        sequence = index.toLong(),
                        timestamp = FIXTURE_STARTED_AT + index * 5_000L,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        horizontalAccuracy = 5.0,
                    ),
                    distanceDeltaMeters = 10.0,
                )
            }
            dao.closeRecording(
                sessionId = started.sessionId,
                segmentId = started.segmentId,
                endedAt = FIXTURE_STARTED_AT + 60_000L,
                status = app.trailveil.data.db.RecordingStatus.COMPLETED,
                stopReason = "TEST_COMPLETE",
                segmentEndReason = "TEST_COMPLETE",
            )
        }
        return started.sessionId
    }

    private fun takeWholeWindowScreenshot(): Bitmap =
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "UiAutomation produced no screenshot"
        }

    private fun measureRoundedClip(screenshot: Bitmap, bounds: Rect): ClipObservation {
        val outside = listOf(
            pixelAt(screenshot, bounds.left - 2, bounds.top + CORNER_SAMPLE_INSET_PX),
            pixelAt(screenshot, bounds.right + 1, bounds.top + CORNER_SAMPLE_INSET_PX),
            pixelAt(screenshot, bounds.left + CORNER_SAMPLE_INSET_PX, bounds.top - 2),
            pixelAt(screenshot, bounds.left + CORNER_SAMPLE_INSET_PX, bounds.bottom + 1),
        ).filterNotNull()
        val corners = listOf(
            pixelAt(screenshot, bounds.left + CORNER_SAMPLE_INSET_PX, bounds.top + CORNER_SAMPLE_INSET_PX),
            pixelAt(screenshot, bounds.right - CORNER_SAMPLE_INSET_PX - 1, bounds.top + CORNER_SAMPLE_INSET_PX),
            pixelAt(screenshot, bounds.left + CORNER_SAMPLE_INSET_PX, bounds.bottom - CORNER_SAMPLE_INSET_PX - 1),
            pixelAt(screenshot, bounds.right - CORNER_SAMPLE_INSET_PX - 1, bounds.bottom - CORNER_SAMPLE_INSET_PX - 1),
        ).filterNotNull()
        val interior = pixelAt(screenshot, bounds.centerX(), bounds.centerY())
        if (outside.isEmpty() || corners.isEmpty() || interior == null) {
            return ClipObservation(false, Double.NaN, Double.NaN)
        }
        val cornerOutsideDistance = corners.zip(outside).map { (corner, outsideCorner) ->
            colorDistance(corner, outsideCorner)
        }.average()
        val cornerInteriorDistance = corners.map { corner -> colorDistance(corner, interior) }.average()
        return ClipObservation(
            effective = cornerOutsideDistance <= CLIP_BACKGROUND_DISTANCE &&
                cornerInteriorDistance >= CLIP_INTERIOR_DISTANCE,
            cornerOutsideDistance = cornerOutsideDistance,
            cornerInteriorDistance = cornerInteriorDistance,
        )
    }

    private fun locateAttributionCandidates(mapView: MapView, mapBounds: Rect): AttributionObservation {
        val candidates = ArrayList<ImageView>()
        fun walk(view: View) {
            if (view is ImageView && view.isShown) {
                val bounds = view.boundsOnScreen()
                val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                val description = view.contentDescription?.toString().orEmpty()
                val looksLikeAttribution =
                    bounds.bottom >= mapBounds.bottom - ATTRIBUTION_BOTTOM_TOLERANCE_PX &&
                        bounds.left <= mapBounds.left + ATTRIBUTION_START_TOLERANCE_PX &&
                        (idName.orEmpty().contains("logo", true) ||
                            idName.orEmpty().contains("watermark", true) ||
                            description.contains("google", true) ||
                            description.contains("logo", true) ||
                            (bounds.width() in 10..300 && bounds.height() in 8..120))
                if (looksLikeAttribution) candidates += view
            }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> walk(view.getChildAt(index)) }
            }
        }
        walk(mapView)
        val visible = candidates.any { it.isShown && it.isVisible }
        val attributionBounds = candidates.firstOrNull()?.boundsOnScreen()
        val inside = candidates.any { candidate ->
            val bounds = candidate.boundsOnScreen()
            bounds.left >= mapBounds.left &&
                bounds.top >= mapBounds.top &&
                bounds.right <= mapBounds.right &&
                bounds.bottom <= mapBounds.bottom
        }
        return AttributionObservation(
            count = candidates.size,
            visible = visible,
            insideMap = inside,
            candidate = candidates.firstOrNull(),
            width = attributionBounds?.width() ?: 0,
            height = attributionBounds?.height() ?: 0,
        )
    }

    /** Compares pixels drawn by the real SDK ImageView with the composited whole-window capture. */
    private fun assertAttributionPixels(
        mapView: MapView,
        observation: AttributionObservation,
        screenshot: Bitmap,
    ): AttributionPixelObservation {
        val candidate = requireNotNull(observation.candidate) {
            "Google attribution ImageView was not found"
        }
        val mapBounds = mapView.boundsOnScreen()
        val reference = androidx.core.graphics.createBitmap(
            candidate.width.coerceAtLeast(1),
            candidate.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val candidateBounds = AtomicReference<Rect>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            candidateBounds.set(candidate.boundsOnScreen())
            candidate.draw(Canvas(reference))
        }
        val bounds = requireNotNull(candidateBounds.get())
        assertTrue("attribution ImageView escaped the map bounds", mapBounds.contains(bounds))
        var samples = 0
        var matches = 0
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                val expected = reference[x, y]
                if (Color.alpha(expected) < ATTRIBUTION_MIN_ALPHA) continue
                samples += 1
                val screenX = bounds.left + x
                val screenY = bounds.top + y
                if (
                    screenX in 0 until screenshot.width &&
                        screenY in 0 until screenshot.height &&
                        hasMatchingPixel(
                            expected = expected,
                            screenshot = screenshot,
                            centerX = screenX,
                            centerY = screenY,
                        )
                ) {
                    matches += 1
                }
            }
        }
        reference.recycle()
        val ratio = if (samples == 0) 0.0 else matches.toDouble() / samples
        assertTrue(
            "Google attribution drawable produced too few opaque pixels: samples=$samples",
            samples >= ATTRIBUTION_MIN_SAMPLES,
        )
        assertTrue(
            "Google attribution pixels were clipped or mismatched: matches=$matches/$samples",
            ratio >= ATTRIBUTION_MIN_MATCH_RATIO,
        )
        return AttributionPixelObservation(samples, matches, ratio)
    }

    private fun hasMatchingPixel(
        expected: Int,
        screenshot: Bitmap,
        centerX: Int,
        centerY: Int,
    ): Boolean {
        for (dy in -ATTRIBUTION_SEARCH_RADIUS_PX..ATTRIBUTION_SEARCH_RADIUS_PX) {
            for (dx in -ATTRIBUTION_SEARCH_RADIUS_PX..ATTRIBUTION_SEARCH_RADIUS_PX) {
                val x = centerX + dx
                val y = centerY + dy
                if (
                    x in 0 until screenshot.width &&
                        y in 0 until screenshot.height &&
                        colorDistance(expected, screenshot[x, y]) <= ATTRIBUTION_RGB_TOLERANCE
                ) return true
            }
        }
        return false
    }

    private fun findRenderSurface(view: View): View? {
        if (view is SurfaceView || view is TextureView) return view
        if (view !is ViewGroup) return null
        repeat(view.childCount) { index ->
            findRenderSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /**
     * Every pixel of the reference frame, inside the map, that the real SDK rendered in the track's
     * colour. These are absolute screen coordinates, sampled at [TRACK_SIGNATURE_STRIDE_PX] rather
     * than at the classification grid's [FINGERPRINT_STRIDE_PX], because the polyline is 5 dp wide
     * and whether a coarse grid intersects it is luck rather than evidence.
     */
    private fun locateTrackSignature(bitmap: Bitmap, bounds: Rect): List<Point> {
        val clamped = Rect(
            bounds.left.coerceAtLeast(0),
            bounds.top.coerceAtLeast(0),
            bounds.right.coerceAtMost(bitmap.width),
            bounds.bottom.coerceAtMost(bitmap.height),
        )
        if (clamped.width() <= 0 || clamped.height() <= 0) return emptyList()
        val found = ArrayList<Point>()
        var y = clamped.top + FINGERPRINT_INSET_PX
        while (y < clamped.bottom - FINGERPRINT_INSET_PX) {
            var x = clamped.left + FINGERPRINT_INSET_PX
            while (x < clamped.right - FINGERPRINT_INSET_PX) {
                if (colorDistance(bitmap[x, y], TRACK_PURPLE_COLOR) <= TRACK_PIXEL_TOLERANCE) {
                    found += Point(x, y)
                }
                x += TRACK_SIGNATURE_STRIDE_PX
            }
            y += TRACK_SIGNATURE_STRIDE_PX
        }
        return found
    }

    private fun startTransitionCapture(
        mapBounds: Rect,
        mapReference: Fingerprint,
        listReference: Fingerprint,
        trackSignature: List<Point> = emptyList(),
        requireTrackSignature: Boolean = true,
    ): TransitionCapture {
        val running = AtomicBoolean(true)
        val samples = CopyOnWriteArrayList<TransitionSample>()
        val thread = Thread(
            {
                while (running.get()) {
                    val started = SystemClock.uptimeMillis()
                    runCatching { takeWholeWindowScreenshot() }.onSuccess { screenshot ->
                        val actual = runCatching { fingerprint(screenshot, mapBounds) }.getOrNull()
                        if (actual != null) {
                            val mapDistance = difference(mapReference, actual)
                            val listDistance = difference(listReference, actual)
                            val mapLike = mapDistance + TRANSITION_CLASSIFICATION_MARGIN < listDistance &&
                                (!requireTrackSignature ||
                                    trackSignaturePresent(screenshot, trackSignature))
                            val listLike =
                                listDistance + TRANSITION_CLASSIFICATION_MARGIN < mapDistance
                            val blank = !mapLike && !listLike && isUniform(actual)
                            samples += TransitionSample(
                                started,
                                mapLike = mapLike,
                                listLike = listLike,
                                blank = blank,
                                ambiguous = !mapLike && !listLike && !blank,
                            )
                        }
                        screenshot.recycle()
                    }
                    SystemClock.sleep(FRAME_CAPTURE_GAP_MILLIS)
                }
            },
            "trailveil-sp3-pop-capture",
        ).apply { start() }
        return TransitionCapture(running, thread, samples, trackSignature.size)
    }

    /**
     * Reads the candidate coordinates out of the live frame. The threshold is deliberately the
     * unchanged [MINIMUM_TRACK_SIGNATURE_SAMPLES]: this correction adds candidates, it does not
     * lower the bar a frame must clear to count as showing the map.
     */
    private fun trackSignaturePresent(screenshot: Bitmap, points: List<Point>): Boolean {
        if (points.isEmpty()) return false
        var matching = 0
        for (point in points) {
            val color = pixelAt(screenshot, point.x, point.y) ?: continue
            if (colorDistance(color, TRACK_PURPLE_COLOR) <= TRACK_PIXEL_TOLERANCE) {
                matching += 1
                if (matching >= MINIMUM_TRACK_SIGNATURE_SAMPLES) return true
            }
        }
        return false
    }

    private fun isUniform(fingerprint: Fingerprint): Boolean {
        val first = fingerprint.colors.firstOrNull() ?: return true
        return fingerprint.colors.all { color ->
            colorDistance(first, color) <= BLANK_COLOR_DISTANCE
        }
    }

    private fun format(value: Double): String =
        if (value.isFinite()) "%.4f".format(Locale.US, value) else "NaN"

    private fun pixelAt(bitmap: Bitmap, x: Int, y: Int): Int? =
        if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap[x, y] else null

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
            kotlin.math.abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
            kotlin.math.abs((first and 0xff) - (second and 0xff))

    private fun fingerprint(bitmap: Bitmap, bounds: Rect): Fingerprint {
        val clamped = Rect(
            bounds.left.coerceAtLeast(0),
            bounds.top.coerceAtLeast(0),
            bounds.right.coerceAtMost(bitmap.width),
            bounds.bottom.coerceAtMost(bitmap.height),
        )
        require(clamped.width() > 0 && clamped.height() > 0) { "empty screenshot bounds: $bounds" }
        val colors = ArrayList<Int>()
        var y = clamped.top + FINGERPRINT_INSET_PX
        while (y < clamped.bottom - FINGERPRINT_INSET_PX) {
            var x = clamped.left + FINGERPRINT_INSET_PX
            while (x < clamped.right - FINGERPRINT_INSET_PX) {
                colors += bitmap[x, y]
                x += FINGERPRINT_STRIDE_PX
            }
            y += FINGERPRINT_STRIDE_PX
        }
        check(colors.isNotEmpty()) { "no fingerprint samples for $clamped" }
        return Fingerprint(clamped, colors.toIntArray())
    }

    private fun difference(reference: Fingerprint, bitmap: Bitmap): Double =
        difference(reference, fingerprint(bitmap, reference.bounds))

    private fun difference(first: Fingerprint, second: Fingerprint): Double {
        if (first.colors.size != second.colors.size) return 1.0
        var total = 0L
        first.colors.indices.forEach { index -> total += colorDistance(first.colors[index], second.colors[index]) }
        return total.toDouble() / (first.colors.size * MAXIMUM_COLOR_DISTANCE)
    }

    private fun View.boundsOnScreen(): Rect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun View.findMapViews(): List<MapView> = buildList {
        if (this@findMapViews is MapView) add(this@findMapViews)
        if (this@findMapViews is ViewGroup) {
            repeat(childCount) { index -> addAll(getChildAt(index).findMapViews()) }
        }
    }

    private data class Fingerprint(val bounds: Rect, val colors: IntArray)

    private data class ClipObservation(
        val effective: Boolean,
        val cornerOutsideDistance: Double,
        val cornerInteriorDistance: Double,
    )

    private data class AttributionObservation(
        val count: Int,
        val visible: Boolean,
        val insideMap: Boolean,
        val candidate: ImageView?,
        val width: Int,
        val height: Int,
    )

    private data class AttributionPixelObservation(
        val samples: Int,
        val matches: Int,
        val matchRatio: Double,
    )

    private data class TransitionSample(
        val startedAtMillis: Long,
        val mapLike: Boolean,
        val listLike: Boolean,
        val blank: Boolean,
        val ambiguous: Boolean,
    )

    private data class TransitionSummary(
        val frames: Int,
        val listVisibleFrames: Int,
        val mapLikeAfterList: Int,
        val mapLikeAfterDestroy: Int,
        val blankFramesAfterClick: Int,
        val blankFramesAfterList: Int,
        val ambiguousFramesAfterClick: Int,
    )

    private class TransitionCapture(
        private val running: AtomicBoolean,
        private val thread: Thread,
        private val samples: List<TransitionSample>,
        val trackSignatureSamples: Int,
    ) {
        fun stop() {
            running.set(false)
            thread.join(CAPTURE_JOIN_TIMEOUT_MILLIS)
            check(!thread.isAlive) { "SP3 pop capture did not stop" }
        }

        fun summarize(
            windowStartMillis: Long,
            listVisibleAtMillis: Long,
            mapDestroyedAtMillis: Long,
        ): TransitionSummary {
            val afterClick = samples.filter { it.startedAtMillis >= windowStartMillis }
            val afterList = afterClick.filter { it.startedAtMillis >= listVisibleAtMillis }
            val afterDestroy = afterClick.filter { it.startedAtMillis >= mapDestroyedAtMillis }
            return TransitionSummary(
                frames = afterClick.size,
                listVisibleFrames = afterList.size,
                mapLikeAfterList = afterList.count { it.mapLike },
                mapLikeAfterDestroy = afterDestroy.count { it.mapLike },
                blankFramesAfterClick = afterClick.count { it.blank },
                blankFramesAfterList = afterList.count { it.blank },
                ambiguousFramesAfterClick = afterClick.count { it.ambiguous },
            )
        }
    }

    private companion object {
        const val SP3_FILE = "sp3-summary.txt"
        const val FIXTURE_STARTED_AT = 9_013_100_000L
        val DEFAULT_TRACK_POINTS = listOf(
            GeoPoint(latitude = 25.0330, longitude = 121.5654),
            GeoPoint(latitude = 25.0340, longitude = 121.5664),
        )
        const val NAVIGATION_TIMEOUT_MILLIS = 20_000L
        const val MAP_READY_TIMEOUT_MILLIS = 30_000L
        const val DETAIL_TEARDOWN_BUDGET_MILLIS = 750L
        const val CAMERA_SETTLE_TIMEOUT_MILLIS = 5_000L
        const val CAMERA_MOVE_TIMEOUT_MILLIS = 5_000L
        const val CAMERA_FIT_TIMEOUT_MILLIS = 10_000L
        const val OVERLAY_OBSERVATION_TIMEOUT_MILLIS = 5_000L
        const val CAMERA_STABLE_POLLS = 3
        const val CAMERA_STABLE_EPSILON_DEGREES = 0.000_000_01
        const val CAMERA_MOVE_EPSILON_DEGREES = 0.000_001
        const val DETAIL_SCROLL_TOLERANCE_PX = 8
        const val DETAIL_BOUNDS_PADDING_PX = 72
        const val SCREEN_ROUNDING_TOLERANCE_PX = 2
        const val DATELINE_MIN_ZOOM = 5.0f
        const val TRACK_PURPLE_COLOR = 0xff6a1b9a.toInt()
        const val TRACK_PIXEL_TOLERANCE = 100

        /**
         * Unchanged by the 2026-09-01 stability correction, deliberately. The instability was that
         * only four candidate pixels existed to clear this bar, not that the bar was wrong.
         */
        const val MINIMUM_TRACK_SIGNATURE_SAMPLES = 3

        /** Finer than [FINGERPRINT_STRIDE_PX]: a 5 dp polyline is thinner than the coarse grid. */
        const val TRACK_SIGNATURE_STRIDE_PX = 2
        const val TRANSITION_CLASSIFICATION_MARGIN = 0.002
        const val BLANK_COLOR_DISTANCE = 3
        const val DISCLOSURE_POLLS = 40
        const val POLL_MILLIS = 100L
        const val SCROLL_SETTLE_MILLIS = 300L
        const val POST_EXIT_CAPTURE_MILLIS = 1_000L
        const val MAIN_REFERENCE_SETTLE_MILLIS = 500L
        const val FRAME_CAPTURE_GAP_MILLIS = 16L
        const val CAPTURE_JOIN_TIMEOUT_MILLIS = 5_000L
        const val FINGERPRINT_INSET_PX = 12
        const val FINGERPRINT_STRIDE_PX = 8
        const val CORNER_SAMPLE_INSET_PX = 4
        const val ATTRIBUTION_BOTTOM_TOLERANCE_PX = 200
        const val ATTRIBUTION_START_TOLERANCE_PX = 180
        const val ATTRIBUTION_MIN_ALPHA = 200
        const val ATTRIBUTION_RGB_TOLERANCE = 40
        const val ATTRIBUTION_SEARCH_RADIUS_PX = 2
        const val ATTRIBUTION_MIN_SAMPLES = 16
        const val ATTRIBUTION_MIN_MATCH_RATIO = 0.90
        const val CLIP_BACKGROUND_DISTANCE = 24.0
        const val CLIP_INTERIOR_DISTANCE = 24.0
        const val MAXIMUM_COLOR_DISTANCE = 255.0 * 3.0
    }
}

private fun LatLng.isFinite(): Boolean = latitude.isFinite() && longitude.isFinite()
