package app.trailveil.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.get
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.MainActivity
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.feature.history.RecordingHistoryTestTags
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Google attribution checks for the full-screen and rounded history/detail surfaces. */
@RunWith(AndroidJUnit4::class)
class GoogleAttributionVisibleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val createdSessionIds = ArrayList<Long>()
    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: app.trailveil.feature.recording.PermissionHistory? = null

    @Before
    fun setUp() {
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
        GoogleMapSurfaceTestHooks.reset()
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        runBlocking {
            createdSessionIds.forEach { id ->
                application.appContainer.databaseForTesting().recordingDao().deleteSession(id)
            }
            val original = originalPermissionHistory
            if (original != null) permissionHistory.replaceForTesting(original)
        }
    }

    @Test
    fun mainAndDetailGoogleLogosAreVisibleAndInsideTheirLiveMapBounds() {
        assertTrue(
            "Google attribution requires the keyed googlePoc runtime",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        val mainMapView = awaitMapView(minHeight = 1)
        val mainMap = awaitGoogleMap(mainMapView)
        assertTrue("main map did not observe the real OnMapLoadedCallback", awaitMapLoaded(mainMapView))
        val mainPixelCapture = awaitAttributionPixels(mainMapView)
        val mainAttribution = mainPixelCapture.first
        val mainScreenshot = mainPixelCapture.second
        val mainPixels = mainPixelCapture.third
        assertTrue("main map was not a live Google map", readCameraTarget(mainMap).latitude.isFinite())

        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val fixtureId = createCompletedSession(application)
        dismissDisclosureIfShown()
        openFixtureHistoryList(fixtureId)
        openFixtureDetailFromList(fixtureId)
        val detailMapView = awaitMapView(minHeight = 160, maxHeight = 600)
        val detailMap = awaitGoogleMap(detailMapView)
        assertTrue("detail map did not observe the real OnMapLoadedCallback", awaitMapLoaded(detailMapView))
        awaitCameraSettled(detailMap)
        val detailBounds = detailMapView.boundsOnScreen()
        val detailPixelCapture = awaitAttributionPixels(detailMapView)
        val detailAttribution = detailPixelCapture.first
        val detailScreenshot = detailPixelCapture.second
        val clip = measureRoundedClip(detailScreenshot, detailBounds)
        val detailPixels = detailPixelCapture.third
        assertTrue(
            "Google logo was clipped by the rounded detail container: $clip",
            clip.effective,
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stream",
                    "Stage8 attribution mainSamples=${mainPixels.samples} " +
                        "mainMatches=${mainPixels.matches} " +
                        "mainRatio=${format(mainPixels.matchRatio)} " +
                        "detailSamples=${detailPixels.samples} " +
                        "detailMatches=${detailPixels.matches} " +
                        "detailRatio=${format(detailPixels.matchRatio)}",
                )
            },
        )
        mainScreenshot.recycle()
        detailScreenshot.recycle()
    }

    /**
     * `V02-007`: the measurement half of MapLibre's
     * `MapAccessibilityBaselineTest#attributionAndLogoStayAboveTheNavigationBar`.
     *
     * That case measures two views against the window's navigation-bar inset. Only one half ports.
     * There is no Google analogue of the tappable attribution CONTROL - the "i" affordance is a
     * MapLibre widget; the Maps SDK exposes no such view, and TrailVeil carries the Google terms
     * in its own disclosure sheet instead
     * (`GoogleRecordingEntryScreenTest#theDisclosureNamesGoogleMapsAndItsTerms`),
     * so there is nothing here whose tappability could be measured and nothing is invented in its
     * place. The logo half does port, and it is the half the sibling pixel case above cannot see:
     * that case proves the logo is DRAWN by matching its own reference pixels, which a logo lifted
     * too little still satisfies wherever the navigation bar is translucent. This measures the
     * lift itself, against the same inset arithmetic the MapLibre case uses.
     */
    @Test
    fun theEntryGoogleLogoStaysAboveTheNavigationBarInset() {
        // Loud, not skipped, and for the same reason its sibling above is: this case is what an
        // inventory row is recorded closed on, and a keyless run that skipped it would look
        // exactly like a run that proved it. The class's other keyed case already fails on this
        // predicate, so nothing new goes red - only the abstention that hid inside a green class.
        assertTrue(
            "measuring the real entry logo requires the keyed googlePoc runtime",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        dismissDisclosureIfShown()
        val mapView = awaitMapView(minHeight = 1)
        assertTrue("entry map did not observe the real OnMapLoadedCallback", awaitMapLoaded(mapView))
        // The lift is applied by `GoogleFogSafetyOverlay.positionAttributionAboveSystemBars`, from
        // the ONLINE transition and from every MapView layout change, so the settled position is
        // the claim; a bounded wait reaches it without granting a stuck logo a pass.
        val settled = awaitLogoAboveNavigationBar(mapView)
        // Two preconditions this measurement is worthless without, asserted rather than merely
        // printed in the failure message. The locator is a bounded size/position heuristic over
        // every ImageView under the map, so a second match would mean the view being measured is
        // whichever one the walk reached first rather than the logo. And the map itself has to
        // reach at least as far down as the limit: an entry map that stopped short of the
        // navigation bar would clear it with no lift applied at all, and this case would report
        // the absent lift as a pass.
        assertTrue(
            "the attribution locator matched ${settled.candidateCount} ImageViews, so the " +
                "measured view is not identified: $settled",
            settled.candidateCount == 1,
        )
        assertTrue(
            "the entry map bottom ${settled.mapBottom} does not reach the navigation-bar limit " +
                "${settled.limit}, so nothing here required a lift: $settled",
            settled.mapBottom >= settled.limit,
        )
        assertTrue(
            "Google logo bottom ${settled.logoBottom} is not above the navigation bar " +
                "(limit ${settled.limit}, inset ${settled.navigationInset}, " +
                "logo height ${settled.logoHeight}, map bottom ${settled.mapBottom})",
            settled.logoHeight > 0 && settled.logoBottom <= settled.limit,
        )
    }

    private fun awaitLogoAboveNavigationBar(mapView: MapView): LogoInsetObservation {
        val deadline = SystemClock.uptimeMillis() + ATTRIBUTION_TIMEOUT_MILLIS
        var last = measureLogoAgainstNavigationBar(mapView)
        while (SystemClock.uptimeMillis() < deadline) {
            // The unambiguous settled state is what this returns early on, so a second candidate
            // that exists only while the SDK is still laying itself out cannot decide the verdict;
            // one that is still there at the deadline is returned and fails the identity check.
            if (
                last != null &&
                last.candidateCount == 1 &&
                last.logoHeight > 0 &&
                last.logoBottom <= last.limit
            ) {
                return last
            }
            SystemClock.sleep(POLL_MILLIS)
            last = measureLogoAgainstNavigationBar(mapView)
        }
        return last ?: throw AssertionError(
            "no Google logo view was ever found on the entry map: " +
                "load=${mapView.getTag(app.trailveil.R.id.map_basemap_load_state)} " +
                "cover=${mapView.getTag(app.trailveil.R.id.map_fog_synchronous_cover_up)}",
        )
    }

    /** Same arithmetic as the MapLibre baseline: the decor's bottom, less the navigation inset. */
    private fun measureLogoAgainstNavigationBar(mapView: MapView): LogoInsetObservation? {
        val observation = AtomicReference<LogoInsetObservation?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val attribution = readAttribution(mapView)
            val logo = attribution.candidate
            val insets = mapView.rootWindowInsets
            if (logo != null && insets != null) {
                val navigationInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                val decor = composeRule.activity.window.decorView
                val decorLocation = IntArray(2).also(decor::getLocationOnScreen)
                val logoBounds = logo.boundsOnScreen()
                observation.set(
                    LogoInsetObservation(
                        logoBottom = logoBounds.bottom,
                        logoHeight = logoBounds.height(),
                        limit = decorLocation[1] + decor.height - navigationInset,
                        navigationInset = navigationInset,
                        mapBottom = mapView.boundsOnScreen().bottom,
                        candidateCount = attribution.candidateCount,
                    ),
                )
            }
        }
        return observation.get()
    }

    @Test
    fun terminalSurfaceContainsNoGoogleMapView() {
        GoogleMapSurfaceTestHooks.decision.set(
            ProviderStartupDecision(false, ProviderFallbackReason.MISSING_KEY),
        )
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "terminal provider surface retained a Google MapView",
                    !activity.window.decorView.containsGoogleMapView(),
                )
                assertTrue(
                    "terminal provider surface retained a Google Maps child view",
                    !activity.window.decorView.containsGoogleMapsPackageView(),
                )
            }
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

    private fun awaitMapView(minHeight: Int, maxHeight: Int = Int.MAX_VALUE): MapView {
        val found = AtomicReference<MapView?>()
        val diagnostics = AtomicReference("")
        val deadline = SystemClock.uptimeMillis() + MAP_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline && found.get() == null) {
            composeRule.runOnIdle {
                val maps = composeRule.activity.window.decorView.findMapViews()
                diagnostics.set(
                    maps.joinToString { map ->
                        "${map.javaClass.name}:${map.width}x${map.height}:" +
                            "${map.left},${map.top}:parent=${map.parent?.javaClass?.name}"
                    },
                )
                found.set(
                    maps.filter { it.height in minHeight..maxHeight }
                        .maxByOrNull { it.height }
                        ?: maps.filter { it.width > 0 && it.height > 0 }
                            .minByOrNull { it.height },
                )
            }
            if (found.get() == null) SystemClock.sleep(POLL_MILLIS)
        }
        return requireNotNull(found.get()) {
            "No attached Google map in requested size range; candidates=${diagnostics.get()}"
        }
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
        assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
        return requireNotNull(map.get())
    }

    private fun awaitCameraSettled(map: GoogleMap) {
        var previous: com.google.android.gms.maps.model.LatLng? = null
        var stable = 0
        val deadline = SystemClock.uptimeMillis() + CAMERA_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val current = readCameraTarget(map)
            stable = if (
                previous != null &&
                    kotlin.math.abs(previous!!.latitude - current.latitude) +
                    kotlin.math.abs(previous!!.longitude - current.longitude) <=
                    CAMERA_EPSILON_DEGREES
            ) stable + 1 else 0
            previous = current
            if (stable >= CAMERA_STABLE_POLLS) return
            SystemClock.sleep(POLL_MILLIS)
        }
        assertTrue("Google detail camera never settled", stable >= CAMERA_STABLE_POLLS)
    }

    private fun readCameraTarget(map: GoogleMap): com.google.android.gms.maps.model.LatLng {
        val target = AtomicReference<com.google.android.gms.maps.model.LatLng>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            target.set(map.cameraPosition.target)
        }
        return requireNotNull(target.get())
    }

    private fun awaitAttribution(mapView: MapView): AttributionObservation {
        val deadline = SystemClock.uptimeMillis() + ATTRIBUTION_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val observation = readAttribution(mapView)
            if (observation.visible && observation.insideMap) return observation
            SystemClock.sleep(POLL_MILLIS)
        }
        val observation = readAttribution(mapView)
        assertTrue("Google attribution was not visible/in bounds: $observation", observation.visible)
        assertTrue("Google attribution escaped map bounds: $observation", observation.insideMap)
        return observation
    }

    private fun awaitAttributionPixels(
        mapView: MapView,
    ): Triple<AttributionObservation, Bitmap, AttributionPixelObservation> {
        val deadline = SystemClock.uptimeMillis() + MAP_TIMEOUT_MILLIS
        var last: AttributionPixelObservation? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val observation = readAttribution(mapView)
            if (!observation.visible || !observation.insideMap) {
                SystemClock.sleep(POLL_MILLIS)
                continue
            }
            val screenshot = takeWholeWindowScreenshot()
            val pixels = measureAttributionPixels(mapView, observation, screenshot)
            last = pixels
            if (
                pixels.samples >= ATTRIBUTION_MIN_SAMPLES &&
                    pixels.matchRatio >= ATTRIBUTION_MIN_MATCH_RATIO
            ) {
                return Triple(observation, screenshot, pixels)
            }
            screenshot.recycle()
            SystemClock.sleep(POLL_MILLIS)
        }
        // The logo can only be hidden by the fail-closed cover, and the cover can only be down once
        // a generation is proven, so the diagnosis needs the whole fog state and not just the
        // installed generation. `activeSlot` is the decisive one: null means fog never STARTED,
        // non-null means it started and never PROVED, and those have different causes.
        throw AssertionError(
            "Google attribution pixels never became visible in the bounded wait: $last " +
                "load=${mapView.getTag(app.trailveil.R.id.map_basemap_load_state)} " +
                "cover=${mapView.getTag(app.trailveil.R.id.map_fog_synchronous_cover_up)} " +
                "generation=${mapView.getTag(app.trailveil.R.id.map_fog_canonical_generation)} " +
                "activeSlot=${mapView.getTag(app.trailveil.R.id.map_fog_active_slot)} " +
                "composeCover=${mapView.getTag(app.trailveil.R.id.map_fog_cover_up)} " +
                "cameraFlight=${mapView.getTag(app.trailveil.R.id.map_camera_flight_active)} " +
                "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown} " +
                "binding=[${mapView.getTag(app.trailveil.R.id.map_fog_binding_state)}] " +
                "lastFogFailure=${mapView.getTag(app.trailveil.R.id.map_fog_last_failure)}",
        )
    }

    private fun awaitMapLoaded(mapView: MapView): Boolean {
        val deadline = SystemClock.uptimeMillis() + MAP_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val state = AtomicReference<Any?>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                state.set(mapView.getTag(app.trailveil.R.id.map_basemap_load_state))
            }
            if (state.get()?.toString() == "ONLINE") return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun assertAttributionWithinMap(mapView: MapView) {
        val observation = readAttribution(mapView)
        assertTrue("Google attribution candidate was not visible: $observation", observation.visible)
        assertTrue("Google attribution candidate was clipped/outside: $observation", observation.insideMap)
    }

    /** Draws the actual SDK ImageView and compares every sufficiently opaque pixel on screen. */
    private fun assertAttributionPixels(
        mapView: MapView,
        observation: AttributionObservation,
        screenshot: Bitmap,
    ): AttributionPixelObservation {
        val result = measureAttributionPixels(mapView, observation, screenshot)
        assertTrue(
            "Google attribution drawable produced too few opaque pixels: ${result.samples}",
            result.samples >= ATTRIBUTION_MIN_SAMPLES,
        )
        assertTrue(
            "Google attribution pixels were clipped/mismatched: " +
                "${result.matches}/${result.samples}",
            result.matchRatio >= ATTRIBUTION_MIN_MATCH_RATIO,
        )
        return result
    }

    private fun measureAttributionPixels(
        mapView: MapView,
        observation: AttributionObservation,
        screenshot: Bitmap,
    ): AttributionPixelObservation {
        val candidate = requireNotNull(observation.candidate) {
            "Google attribution ImageView was not found"
        }
        val mapBounds = mapView.boundsOnScreen()
        val reference = Bitmap.createBitmap(
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
        assertTrue("attribution ImageView escaped map bounds", mapBounds.contains(bounds))
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

    private fun readAttribution(mapView: MapView): AttributionObservation {
        val mapBounds = mapView.boundsOnScreen()
        val candidates = ArrayList<ImageView>()
        fun walk(view: View) {
            if (view is ImageView && view.isShown) {
                val bounds = view.boundsOnScreen()
                // Maps SDK attribution is an ImageView at the bottom-start of the map. Its
                // resource id/content description is renderer-obfuscated, so the bounded
                // size/position check is intentionally the stable locator.
                val looksLikeAttribution =
                    bounds.bottom >= mapBounds.bottom - ATTRIBUTION_BOTTOM_TOLERANCE_PX &&
                        bounds.left <= mapBounds.left + ATTRIBUTION_START_TOLERANCE_PX &&
                        bounds.width() in 10..300 && bounds.height() in 8..120
                if (looksLikeAttribution) candidates += view
            }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> walk(view.getChildAt(index)) }
            }
        }
        walk(mapView)
        val firstBounds = candidates.firstOrNull()?.boundsOnScreen()
        return AttributionObservation(
            candidateCount = candidates.size,
            visible = candidates.isNotEmpty(),
            insideMap = candidates.any { candidate ->
                val bounds = candidate.boundsOnScreen()
                bounds.left >= mapBounds.left &&
                    bounds.top >= mapBounds.top &&
                    bounds.right <= mapBounds.right &&
                    bounds.bottom <= mapBounds.bottom
            },
            candidate = candidates.firstOrNull(),
            width = firstBounds?.width() ?: 0,
            height = firstBounds?.height() ?: 0,
        )
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
        val outsideDistance = corners.zip(outside).map { (corner, edge) ->
            colorDistance(corner, edge)
        }.average()
        val interiorDistance = corners.map { corner -> colorDistance(corner, interior) }.average()
        return ClipObservation(
            effective = outsideDistance <= CLIP_BACKGROUND_DISTANCE &&
                interiorDistance >= CLIP_INTERIOR_DISTANCE,
            cornerOutsideDistance = outsideDistance,
            cornerInteriorDistance = interiorDistance,
        )
    }

    private fun createCompletedSession(application: TrailVeilApplication): Long {
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val started = runBlocking {
            dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = FIXTURE_STARTED_AT,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "google-attribution-test",
                ),
                initialSegment = TrackSegmentEntity(
                    sessionId = 0L,
                    sequence = 0L,
                    startedAt = FIXTURE_STARTED_AT,
                    startReason = "SESSION_START",
                ),
            )
        }
        createdSessionIds += started.sessionId
        runBlocking {
            dao.appendAcceptedPoint(
                point = TrackPointEntity(
                    sessionId = started.sessionId,
                    segmentId = started.segmentId,
                    sequence = 0L,
                    timestamp = FIXTURE_STARTED_AT,
                    latitude = 25.0330,
                    longitude = 121.5654,
                    horizontalAccuracy = 5.0,
                ),
                distanceDeltaMeters = 10.0,
            )
            dao.closeRecording(
                sessionId = started.sessionId,
                segmentId = started.segmentId,
                endedAt = FIXTURE_STARTED_AT + 60_000L,
                status = RecordingStatus.COMPLETED,
                stopReason = "TEST_COMPLETE",
                segmentEndReason = "TEST_COMPLETE",
            )
        }
        return started.sessionId
    }

    private fun takeWholeWindowScreenshot(): Bitmap =
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())

    private fun pixelAt(bitmap: Bitmap, x: Int, y: Int): Int? =
        if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap[x, y] else null

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
            kotlin.math.abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
            kotlin.math.abs((first and 0xff) - (second and 0xff))

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

    private fun View.containsGoogleMapView(): Boolean =
        this is MapView || (this is ViewGroup && (0 until childCount).any { getChildAt(it).containsGoogleMapView() })

    private fun View.containsGoogleMapsPackageView(): Boolean =
        javaClass.name.startsWith("com.google.android.gms.maps") ||
            (this is ViewGroup && (0 until childCount).any {
                getChildAt(it).containsGoogleMapsPackageView()
            })

    private data class AttributionObservation(
        val candidateCount: Int,
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

    private data class LogoInsetObservation(
        val logoBottom: Int,
        val logoHeight: Int,
        val limit: Int,
        val navigationInset: Int,
        val mapBottom: Int,
        val candidateCount: Int,
    )

    private data class ClipObservation(
        val effective: Boolean,
        val cornerOutsideDistance: Double,
        val cornerInteriorDistance: Double,
    )

    private companion object {
        const val FIXTURE_STARTED_AT = 9_013_200_000L
        const val NAVIGATION_TIMEOUT_MILLIS = 20_000L
        const val MAP_TIMEOUT_MILLIS = 30_000L
        const val CAMERA_TIMEOUT_MILLIS = 10_000L
        const val ATTRIBUTION_TIMEOUT_MILLIS = 10_000L
        const val CAMERA_STABLE_POLLS = 3
        const val CAMERA_EPSILON_DEGREES = 0.000_000_01
        const val DISCLOSURE_POLLS = 40
        const val POLL_MILLIS = 100L
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
    }

    private fun format(value: Double): String = "%.4f".format(java.util.Locale.US, value)
}
