package app.trailveil.feature.history

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.feature.recording.PermissionHistory
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.googlepoc.FlingGestureInjector
import app.trailveil.map.GestureOwningGoogleMapView
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two history-detail claims `GoogleHistoryDetailPopTest` leaves half-covered.
 *
 * Its pop oracle proves the popped detail MapView is detached and leaves no stale content, and it
 * drags the detail map exactly once. Neither is the whole property:
 *
 *  * a detached View says nothing about the popped *route* - the MapLibre twin asserts the popped
 *    `NavBackStackEntry` lifecycle owner reaches `DESTROYED`, which is what releases the entry's
 *    saved state and ViewModel store, and
 *  * one successful drag can be an accident. `GestureOwningGoogleMapView` re-claims the gesture on
 *    every `ACTION_DOWN` precisely because Compose interop drops a `requestDisallowIntercept`
 *    request when a gesture ends, so only *repeated* drags test the re-claim rather than the
 *    initial one.
 *
 * Both cases run on the real `MainActivity` NavHost against the live hosted Google map.
 */
@RunWith(AndroidJUnit4::class)
class GoogleHistoryDetailInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val createdFixtureIds = ArrayList<Long>()
    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: PermissionHistory? = null

    @Before
    fun setUp() {
        assumeTrue(
            "Google PoC runtime key is not configured; host builds remain compile-only",
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
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        runBlocking {
            createdFixtureIds.forEach { id ->
                application.appContainer.databaseForTesting().recordingDao().deleteSession(id)
            }
            // Non-null only once the store above was constructed, so an assumption failure in
            // setUp leaves this branch unentered rather than touching an uninitialised field.
            val original = originalPermissionHistory
            if (original != null) permissionHistory.replaceForTesting(original)
        }
    }

    /**
     * Closes the lifecycle-owner and replacement-gesture halves of
     * `HistoryDetailPopLifecycleTest`'s
     * `theDetailMapLeavesWithTheTransitionAndIsDestroyedWhenTheListSettles`.
     */
    @Test
    fun poppingTheDetailRouteDestroysItsLifecycleOwnerAndItsReplacementTakesARealDrag() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val fixtureId = createCompletedSession(application)
        dismissDisclosureIfShown()
        openFixtureHistoryList(fixtureId)
        openFixtureDetailFromList(fixtureId)

        val firstMapView = awaitDetailMapView()
        assertTrue(
            "the first detail map never observed the real OnMapLoadedCallback",
            awaitDetailMapLoaded(firstMapView),
        )
        // The AndroidView interop holder carries the composition's LifecycleOwner, which inside a
        // NavHost destination is that destination's NavBackStackEntry. If it resolved to the
        // Activity instead, the DESTROYED wait below would time out rather than pass.
        val detailRouteOwner: LifecycleOwner = composeRule.runOnIdle {
            checkNotNull(firstMapView.findViewTreeLifecycleOwner()) {
                "the Google detail MapView has no NavBackStackEntry lifecycle owner"
            }
        }
        val stateBeforePop = composeRule.runOnIdle { detailRouteOwner.lifecycle.currentState }
        assertTrue(
            "the detail route's lifecycle owner was not active before the pop: $stateBeforePop",
            stateBeforePop.isAtLeast(Lifecycle.State.STARTED),
        )

        popToHistoryList()
        awaitDetailRouteDestroyed(firstMapView, detailRouteOwner)
        composeRule.runOnIdle {
            assertFalse(
                "the popped detail MapView is still attached",
                firstMapView.isAttachedToWindow,
            )
            assertNull("the popped detail MapView still has a parent", firstMapView.parent)
            assertEquals(
                "the popped detail route's NavBackStackEntry lifecycle never reached DESTROYED, " +
                    "so the route's saved state and ViewModel store were never released",
                Lifecycle.State.DESTROYED,
                detailRouteOwner.lifecycle.currentState,
            )
        }

        openFixtureDetailFromList(fixtureId)
        val replacementMapView = awaitDetailMapView(excluding = firstMapView)
        assertNotSame(
            "the popped MapView was reused after destruction",
            firstMapView,
            replacementMapView,
        )
        assertTrue(
            "the replacement detail map is not the production gesture-owning view",
            replacementMapView is GestureOwningGoogleMapView,
        )
        val replacementMap = awaitGoogleMap(replacementMapView)
        assertTrue(
            "the replacement detail map never observed the real OnMapLoadedCallback",
            awaitDetailMapLoaded(replacementMapView),
        )
        awaitCameraSettled(replacementMap)
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).performScrollTo()
        composeRule.waitForIdle()
        SystemClock.sleep(SCROLL_SETTLE_MILLIS)

        val beforeDrag = cameraTarget(replacementMap)
        val touchDownsBeforeDrag = touchDownCount(replacementMapView)
        injectHorizontalDrag(replacementMapView)
        assertEquals(
            "the injected DOWN never reached the replacement GestureOwningGoogleMapView",
            touchDownsBeforeDrag + 1,
            touchDownCount(replacementMapView),
        )
        assertTrue(
            "the replacement detail map did not accept a real injected drag: " +
                "camera stayed within $CAMERA_MOVE_EPSILON_DEGREES deg of its post-pop fit",
            awaitCameraMoved(replacementMap, beforeDrag),
        )
        val afterDrag = awaitCameraSettled(replacementMap)

        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "TrailVeil google-detail-pop-lifecycle " +
                        "api=${android.os.Build.VERSION.SDK_INT} " +
                        "product=${android.os.Build.PRODUCT} " +
                        "poppedOwnerState=${Lifecycle.State.DESTROYED} " +
                        "replacementReused=false " +
                        "replacementDragDeltaDeg=" +
                        format(cameraDistance(beforeDrag, afterDrag)) + "\n",
                )
            },
        )
    }

    /**
     * Closes the repetition half of `RecordingHistoryScreensTest`'s
     * `draggingTheTrackMapPansItWhileTheRestOfTheDetailPageStillScrolls`.
     *
     * Two drag forms, three consecutive drags each. The Compose swipes run along the detail page's
     * own `verticalScroll` axis, which is the axis a lost intercept claim is stolen on; the
     * injected flings are real `InputDispatcher` streams whose DOWN is counted at
     * `GestureOwningGoogleMapView`, so a drag that silently went somewhere else cannot read as a
     * pan. The closing control drags below the map, so "the page did not scroll" is a measurement
     * on a page that demonstrably still scrolls.
     */
    @Test
    fun threeConsecutiveDetailMapDragsEachPanTheCameraWhileThePageNeverScrolls() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val fixtureId = createCompletedSession(application)
        dismissDisclosureIfShown()
        openFixtureHistoryList(fixtureId)
        openFixtureDetailFromList(fixtureId)

        val mapView = awaitDetailMapView()
        assertTrue(
            "the detail map never observed the real OnMapLoadedCallback",
            awaitDetailMapLoaded(mapView),
        )
        val map = awaitGoogleMap(mapView)
        awaitCameraSettled(map)
        // Deliberately NOT scrolled to first. Every drag below runs at the scroll offset the route
        // opens at, which is the offset the closing control's `bottom - 4f` start coordinate is
        // calibrated for; the six no-scroll assertions are what keep it that way.
        composeRule.waitForIdle()
        val settledPageTop = mapTopOnScreen(mapView)

        val composeDragDeltas = ArrayList<Double>()
        repeat(CONSECUTIVE_DRAGS) { index ->
            val before = awaitCameraSettled(map)
            composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap)
                .performTouchInput { swipeUp() }
            assertTrue(
                "vertical detail-map drag ${index + 1} of $CONSECUTIVE_DRAGS did not pan the " +
                    "real Google camera; the detail page's verticalScroll took it instead",
                awaitCameraMoved(map, before),
            )
            val after = awaitCameraSettled(map)
            composeDragDeltas += cameraDistance(before, after)
            assertEquals(
                "vertical detail-map drag ${index + 1} of $CONSECUTIVE_DRAGS scrolled the detail " +
                    "page instead of panning the map",
                settledPageTop,
                mapTopOnScreen(mapView),
            )
        }

        val injectedDragDeltas = ArrayList<Double>()
        repeat(CONSECUTIVE_DRAGS) { index ->
            val before = awaitCameraSettled(map)
            val touchDownsBefore = touchDownCount(mapView)
            injectHorizontalDrag(mapView)
            assertEquals(
                "injected detail-map drag ${index + 1} of $CONSECUTIVE_DRAGS never reached " +
                    "GestureOwningGoogleMapView",
                touchDownsBefore + 1,
                touchDownCount(mapView),
            )
            assertTrue(
                "injected detail-map drag ${index + 1} of $CONSECUTIVE_DRAGS did not pan the " +
                    "real Google camera",
                awaitCameraMoved(map, before),
            )
            val after = awaitCameraSettled(map)
            injectedDragDeltas += cameraDistance(before, after)
            assertEquals(
                "injected detail-map drag ${index + 1} of $CONSECUTIVE_DRAGS scrolled the detail " +
                    "page instead of panning the map",
                settledPageTop,
                mapTopOnScreen(mapView),
            )
        }

        // Anti-vacuity control: a page that cannot scroll would satisfy every assertion above.
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Detail).performTouchInput {
            // Below the map, so this stream never enters the surface that owns map gestures.
            swipeUp(startY = bottom - 4f, endY = bottom - 304f)
        }
        composeRule.waitForIdle()
        SystemClock.sleep(SCROLL_SETTLE_MILLIS)
        val afterPageDrag = mapTopOnScreen(mapView)
        assertTrue(
            "dragging below the detail map no longer scrolls the detail page, so the six " +
                "no-scroll assertions above prove nothing",
            abs(afterPageDrag - settledPageTop) > DETAIL_SCROLL_TOLERANCE_PX,
        )

        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "TrailVeil google-detail-drag-repetition " +
                        "api=${android.os.Build.VERSION.SDK_INT} " +
                        "product=${android.os.Build.PRODUCT} " +
                        "composeDrags=${composeDragDeltas.size} " +
                        "injectedDrags=${injectedDragDeltas.size} " +
                        "minComposeDeltaDeg=" +
                        format(composeDragDeltas.minOrNull()) + " " +
                        "minInjectedDeltaDeg=" +
                        format(injectedDragDeltas.minOrNull()) + " " +
                        "pageScrollDeltaPx=${afterPageDrag - settledPageTop}\n",
                )
            },
        )
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
        // Scrolled into view first: the list is a plain verticalScroll Column, so every card is
        // composed at any scroll position and a click below the fold dispatches outside the window.
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(fixtureId)).performScrollTo()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(fixtureId)).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingHistoryTestTags.TrackMap)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).assertIsDisplayed()
    }

    private fun popToHistoryList() {
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performScrollTo()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyListIsShowing() }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.List).assertIsDisplayed()
    }

    private fun historyListIsShowing(): Boolean =
        composeRule.onAllNodesWithTag(RecordingHistoryTestTags.List)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /**
     * The attached detail MapView. `map_detail_map_loaded` is the discriminator rather than a size
     * guess: the hosted surface writes it on every composition of a fog-free map and nulls it on
     * the fog-required entry map, so a main map still attached mid-transition can never be picked.
     */
    private fun awaitDetailMapView(excluding: MapView? = null): MapView {
        val found = AtomicReference<MapView?>()
        val diagnostics = AtomicReference("")
        val deadline = SystemClock.uptimeMillis() + MAP_READY_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline && found.get() == null) {
            composeRule.runOnIdle {
                val candidates = composeRule.activity.window.decorView.findMapViews()
                    .filter { view ->
                        view !== excluding &&
                            view.isAttachedToWindow &&
                            view.width > 0 &&
                            view.height > 0 &&
                            view.getTag(R.id.map_detail_map_loaded) != null
                    }
                diagnostics.set(
                    composeRule.activity.window.decorView.findMapViews().joinToString { view ->
                        "${view.javaClass.simpleName}:${view.width}x${view.height}:" +
                            "attached=${view.isAttachedToWindow}:" +
                            "detailLoaded=${view.getTag(R.id.map_detail_map_loaded)}"
                    },
                )
                found.set(candidates.minByOrNull { view -> view.height })
            }
            if (found.get() == null) SystemClock.sleep(POLL_MILLIS)
        }
        return requireNotNull(found.get()) {
            "No attached Google detail MapView; candidates=${diagnostics.get()}"
        }
    }

    private fun awaitDetailMapLoaded(mapView: MapView): Boolean {
        val deadline = SystemClock.uptimeMillis() + MAP_READY_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val loaded = AtomicReference<Any?>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                loaded.set(mapView.getTag(R.id.map_detail_map_loaded))
            }
            if (loaded.get() == true) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
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
        assertTrue(
            "the Google detail map never became ready",
            ready.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return requireNotNull(map.get())
    }

    private fun awaitDetailRouteDestroyed(mapView: MapView, owner: LifecycleOwner) {
        val deadline = SystemClock.uptimeMillis() + NAVIGATION_TIMEOUT_MILLIS
        var destroyed = false
        while (!destroyed && SystemClock.uptimeMillis() < deadline) {
            destroyed = composeRule.runOnIdle {
                !mapView.isAttachedToWindow &&
                    mapView.parent == null &&
                    owner.lifecycle.currentState == Lifecycle.State.DESTROYED
            }
            if (!destroyed) SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun cameraTarget(map: GoogleMap): LatLng {
        val target = AtomicReference<LatLng>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            target.set(map.cameraPosition.target)
        }
        return requireNotNull(target.get())
    }

    private fun awaitCameraSettled(map: GoogleMap): LatLng {
        val deadline = SystemClock.uptimeMillis() + CAMERA_SETTLE_TIMEOUT_MILLIS
        var previous: LatLng? = null
        var stable = 0
        var latest = cameraTarget(map)
        while (SystemClock.uptimeMillis() < deadline) {
            latest = cameraTarget(map)
            if (latest.latitude.isFinite() && latest.longitude.isFinite()) {
                val last = previous
                stable = if (
                    last != null && cameraDistance(last, latest) <= CAMERA_STABLE_EPSILON_DEGREES
                ) {
                    stable + 1
                } else {
                    0
                }
                previous = latest
                if (stable >= CAMERA_STABLE_POLLS) return latest
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        assertTrue("the Google detail map camera did not settle", stable >= CAMERA_STABLE_POLLS)
        return latest
    }

    private fun awaitCameraMoved(map: GoogleMap, before: LatLng): Boolean {
        val deadline = SystemClock.uptimeMillis() + CAMERA_MOVE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (cameraDistance(before, cameraTarget(map)) > CAMERA_MOVE_EPSILON_DEGREES) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return cameraDistance(before, cameraTarget(map)) > CAMERA_MOVE_EPSILON_DEGREES
    }

    private fun cameraDistance(first: LatLng, second: LatLng): Double =
        abs(first.latitude - second.latitude) + abs(first.longitude - second.longitude)

    private fun format(value: Double?): String =
        if (value != null && value.isFinite()) "%.6f".format(Locale.US, value) else "NaN"

    private fun touchDownCount(mapView: MapView): Int {
        val count = AtomicReference(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            count.set((mapView.getTag(R.id.map_touch_down_count) as? Int) ?: 0)
        }
        return count.get()
    }

    /** The detail map's screen top, which moves exactly when the detail page scrolls. */
    private fun mapTopOnScreen(mapView: MapView): Int {
        val location = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getLocationOnScreen(location)
        }
        return location[1]
    }

    /**
     * One real single-pointer drag from [FlingGestureInjector], aimed at the visible middle of the
     * detail map. The injector is used unchanged: its DOWN lands inside the map, which is all that
     * decides gesture ownership, and its horizontal path stays clear of the back-gesture edges.
     */
    private fun injectHorizontalDrag(mapView: MapView) {
        val origin = IntArray(2)
        val size = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getLocationOnScreen(origin)
            size[0] = mapView.width
            size[1] = mapView.height
        }
        val metrics = mapView.resources.displayMetrics
        val visibleTop = origin[1].coerceAtLeast(0)
        val visibleBottom = (origin[1] + size[1]).coerceAtMost(metrics.heightPixels)
        assertTrue(
            "the detail map is not on screen, so an injected drag would land elsewhere: " +
                "top=${origin[1]} size=${size[0]}x${size[1]} screen=" +
                "${metrics.widthPixels}x${metrics.heightPixels}",
            visibleBottom - visibleTop >= MINIMUM_VISIBLE_MAP_PX,
        )
        // Keep the stream clear of the system's top/bottom gesture zones, the same margin
        // FlingGestureInjector already keeps from the left and right edges.
        val centerY = ((visibleTop + visibleBottom) / 2)
            .coerceIn(EDGE_GESTURE_MARGIN_PX, metrics.heightPixels - EDGE_GESTURE_MARGIN_PX)
        assertTrue(
            "the detail map's visible band leaves no drag row clear of the system gesture " +
                "zones: band=$visibleTop..$visibleBottom screen=${metrics.heightPixels}",
            centerY in visibleTop until visibleBottom,
        )
        FlingGestureInjector.flingCameraWest(
            centerX = origin[0] + size[0] / 2,
            centerY = centerY,
            screenWidth = metrics.widthPixels,
        )
    }

    private fun createCompletedSession(
        application: TrailVeilApplication,
        points: List<GeoPoint> = DEFAULT_TRACK_POINTS,
    ): Long {
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val started = runBlocking {
            dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = FIXTURE_STARTED_AT,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "google-detail-interaction-test",
                ),
                initialSegment = TrackSegmentEntity(
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
                    point = TrackPointEntity(
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
                status = RecordingStatus.COMPLETED,
                stopReason = "TEST_COMPLETE",
                segmentEndReason = "TEST_COMPLETE",
            )
        }
        return started.sessionId
    }

    private fun View.findMapViews(): List<MapView> = buildList {
        if (this@findMapViews is MapView) add(this@findMapViews)
        if (this@findMapViews is ViewGroup) {
            repeat(childCount) { index -> addAll(getChildAt(index).findMapViews()) }
        }
    }

    private companion object {
        const val FIXTURE_STARTED_AT = 9_013_200_000L
        val DEFAULT_TRACK_POINTS = listOf(
            GeoPoint(latitude = 25.0330, longitude = 121.5654),
            GeoPoint(latitude = 25.0340, longitude = 121.5664),
        )

        /** Three, because the MapLibre defect only appears from the second gesture onwards. */
        const val CONSECUTIVE_DRAGS = 3

        const val NAVIGATION_TIMEOUT_MILLIS = 20_000L
        const val MAP_READY_TIMEOUT_MILLIS = 30_000L
        const val MAP_READY_TIMEOUT_SECONDS = 30L
        const val CAMERA_SETTLE_TIMEOUT_MILLIS = 5_000L
        const val CAMERA_MOVE_TIMEOUT_MILLIS = 5_000L
        const val CAMERA_STABLE_POLLS = 3
        const val CAMERA_STABLE_EPSILON_DEGREES = 0.000_000_01
        const val CAMERA_MOVE_EPSILON_DEGREES = 0.000_001
        const val DETAIL_SCROLL_TOLERANCE_PX = 8

        /**
         * A harness sanity floor on the on-screen band the injected DOWN aims at, not a product
         * threshold. What actually proves the drag reached the map is `map_touch_down_count`.
         */
        const val MINIMUM_VISIBLE_MAP_PX = 96

        /** Matches the left/right margin `FlingGestureInjector` already keeps from screen edges. */
        const val EDGE_GESTURE_MARGIN_PX = 120

        const val DISCLOSURE_POLLS = 40
        const val POLL_MILLIS = 100L
        const val SCROLL_SETTLE_MILLIS = 300L
    }
}
