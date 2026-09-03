package app.trailveil.map

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.feature.history.RecordingHistoryTestTags
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.googlepoc.FlingGestureInjector
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity history-return twin: Google MapView re-attach plus two injected drags/cycle. */
@RunWith(AndroidJUnit4::class)
class GoogleMapReturnInteractivityTest {
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
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        runBlocking {
            createdSessionIds.forEach { sessionId ->
                application.appContainer.databaseForTesting().recordingDao().deleteSession(sessionId)
            }
            val original = originalPermissionHistory
            if (original != null) permissionHistory.replaceForTesting(original)
        }
    }

    @Test
    fun twentyRealHistoryReturnsAcceptTheFirstAndSecondGoogleDrags() {
        dismissDisclosureIfShown()
        val mapView = awaitMapView()
        val map = awaitGoogleMap(mapView)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val started = createCompletedSession(
            application,
            GeoPoint(25.0330, 121.5654),
        )

        // Let the production history route observe the seeded row before the first navigation.
        assertTrue(
            "recording entry never observed the seeded history point",
            awaitUntil(10_000L) {
                var pointObserved = false
                composeRule.runOnIdle {
                    pointObserved = composeRule.activity.window.decorView
                        .getTag(app.trailveil.R.id.recording_presentation_latest_point_id) != null
                }
                pointObserved
            },
        )
        awaitCameraReady(map)
        val readyLatencies = ArrayList<Long>()
        val firstDragLatencies = ArrayList<Long>()
        val secondDragLatencies = ArrayList<Long>()

        repeat(RETURN_CYCLES) { cycle ->
            composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
            composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
            assertTrue(
                "history list did not appear on cycle ${cycle + 1}",
                awaitUntil(20_000L) { historyIsShowing() },
            )

            val returnStartedAt = SystemClock.elapsedRealtime()
            Espresso.pressBack()
            val historyGoneAt = AtomicLong(-1L)
            val returnedMapView = AtomicReference<MapView>()
            assertTrue(
                "Google map did not re-attach on history return ${cycle + 1}",
                awaitUntil(20_000L) {
                    if (!historyIsShowing()) {
                        historyGoneAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    }
                    composeRule.runOnIdle {
                        if (returnedMapView.get() == null) {
                            returnedMapView.set(findMapView(composeRule.activity.window.decorView))
                        }
                    }
                    returnedMapView.get() != null && historyGoneAt.get() >= 0L
                },
            )
            val returned = requireNotNull(returnedMapView.get())
            val returnedMap = awaitGoogleMap(returned)
            val readyAt = SystemClock.elapsedRealtime()
            readyLatencies += (readyAt - historyGoneAt.get()).coerceAtLeast(0L)
            assertTrue(
                "history return map was not a real GestureOwningGoogleMapView",
                returned is GestureOwningGoogleMapView,
            )

            val beforeFirst = cameraTarget(returnedMap)
            val firstStartedAt = SystemClock.elapsedRealtime()
            injectFling(returned)
            assertTrue(
                "first drag was not accepted/moving on return cycle ${cycle + 1}",
                awaitUntil(DRAG_TIMEOUT_MILLIS) {
                    distanceDegrees(cameraTarget(returnedMap), beforeFirst) > MOVEMENT_TOLERANCE_DEGREES
                },
            )
            firstDragLatencies += SystemClock.elapsedRealtime() - firstStartedAt
            awaitCameraSettled(returnedMap)

            val beforeSecond = cameraTarget(returnedMap)
            val secondStartedAt = SystemClock.elapsedRealtime()
            injectFling(returned)
            assertTrue(
                "second drag was not accepted/moving on return cycle ${cycle + 1}",
                awaitUntil(DRAG_TIMEOUT_MILLIS) {
                    distanceDegrees(cameraTarget(returnedMap), beforeSecond) > MOVEMENT_TOLERANCE_DEGREES
                },
            )
            secondDragLatencies += SystemClock.elapsedRealtime() - secondStartedAt
            awaitCameraSettled(returnedMap)
        }

        val readyP50 = percentile(readyLatencies, 50)
        val readyP95 = percentile(readyLatencies, 95)
        val firstP50 = percentile(firstDragLatencies, 50)
        val firstP95 = percentile(firstDragLatencies, 95)
        val secondP50 = percentile(secondDragLatencies, 50)
        val secondP95 = percentile(secondDragLatencies, 95)
        val report = "TrailVeil SP4 google-return api=${android.os.Build.VERSION.SDK_INT} " +
            "image=${android.os.Build.PRODUCT} cycles=${readyLatencies.size} " +
            "readyP50Ms=$readyP50 readyP95Ms=$readyP95 " +
            "firstDragP50Ms=$firstP50 firstDragP95Ms=$firstP95 " +
            "secondDragP50Ms=$secondP50 secondDragP95Ms=$secondP95 " +
            "firstAccepted=${firstDragLatencies.size} secondAccepted=${secondDragLatencies.size}"
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", report) },
        )
        assertEquals(RETURN_CYCLES, readyLatencies.size)
        assertEquals(RETURN_CYCLES, firstDragLatencies.size)
        assertEquals(RETURN_CYCLES, secondDragLatencies.size)
        assertTrue("SP4 ready p95 exceeded 250 ms: $report", readyP95 <= READY_P95_BUDGET_MILLIS)
        // Keep the local reference live through the final assertion; it also documents that this
        // suite exercises the production map rather than a detached test-only map.
        assertTrue("seeded session was not created", started > 0L)
    }

    private fun createCompletedSession(
        application: TrailVeilApplication,
        point: GeoPoint,
    ): Long {
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val started = runBlocking {
            dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = 1_000L,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "google-return-test",
                ),
                initialSegment = TrackSegmentEntity(
                    sessionId = 0L,
                    sequence = 0L,
                    startedAt = 1_000L,
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
                    timestamp = 1_000L,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    horizontalAccuracy = 5.0,
                ),
                distanceDeltaMeters = 10.0,
            )
            dao.closeRecording(
                sessionId = started.sessionId,
                segmentId = started.segmentId,
                endedAt = 2_000L,
                status = RecordingStatus.COMPLETED,
                stopReason = "TEST_COMPLETE",
                segmentEndReason = "TEST_COMPLETE",
            )
        }
        return started.sessionId
    }

    private fun dismissDisclosureIfShown() {
        composeRule.waitForIdle()
        if (
            composeRule.onAllNodesWithTag(RecordingEntryTestTags.PrivacySheet)
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacyDismiss).performClick()
            composeRule.waitForIdle()
        }
    }

    private fun historyIsShowing(): Boolean = listOf(
        RecordingHistoryTestTags.Loading,
        RecordingHistoryTestTags.Empty,
        RecordingHistoryTestTags.List,
    ).any { tag -> composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitMapView(): MapView {
        val found = AtomicReference<MapView?>()
        assertTrue(
            "MainActivity did not attach a Google MapView",
            awaitUntil(30_000L) {
                composeRule.runOnIdle {
                    found.set(findMapView(composeRule.activity.window.decorView))
                }
                found.get() != null
            },
        )
        return requireNotNull(found.get())
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
        assertTrue("Google map never became ready", ready.await(30, TimeUnit.SECONDS))
        return requireNotNull(map.get())
    }

    private fun awaitCameraReady(map: GoogleMap) {
        assertTrue(
            "Google camera never produced a finite target",
            awaitUntil(10_000L) {
                cameraTarget(map).latitude.isFinite() && cameraTarget(map).longitude.isFinite()
            },
        )
    }

    private fun injectFling(mapView: MapView) {
        val origin = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getLocationOnScreen(origin)
        }
        FlingGestureInjector.flingCameraWest(
            centerX = origin[0] + mapView.width / 2,
            centerY = origin[1] + mapView.height / 2,
            screenWidth = mapView.resources.displayMetrics.widthPixels,
        )
    }

    private fun cameraTarget(map: GoogleMap): LatLng {
        val target = AtomicReference<LatLng>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            target.set(map.cameraPosition.target)
        }
        return requireNotNull(target.get())
    }

    private fun awaitCameraSettled(map: GoogleMap) {
        var last: LatLng? = null
        var stable = 0
        awaitUntil(DRAG_SETTLE_TIMEOUT_MILLIS) {
            val current = cameraTarget(map)
            val previous = last
            stable = if (
                previous != null &&
                    distanceDegrees(current, previous) <= MOVEMENT_TOLERANCE_DEGREES
            ) {
                stable + 1
            } else {
                0
            }
            last = current
            stable >= 2
        }
    }

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(25L)
        }
        return condition()
    }

    private fun findMapView(view: View): MapView? {
        if (view is MapView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findMapView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun distanceDegrees(a: LatLng, b: LatLng): Double =
        kotlin.math.hypot(a.latitude - b.latitude, a.longitude - b.longitude)

    private fun distanceDegrees(a: LatLng, b: GeoPoint): Double =
        kotlin.math.hypot(a.latitude - b.latitude, a.longitude - b.longitude)

    private fun percentile(values: List<Long>, percent: Int): Long {
        if (values.isEmpty()) return -1L
        val sorted = values.sorted()
        val index = (sorted.size * percent / 100).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private companion object {
        const val RETURN_CYCLES = 20
        const val READY_P95_BUDGET_MILLIS = 250L
        const val DRAG_TIMEOUT_MILLIS = 2_000L
        const val DRAG_SETTLE_TIMEOUT_MILLIS = 2_000L
        const val MOVEMENT_TOLERANCE_DEGREES = 0.00001
    }
}
