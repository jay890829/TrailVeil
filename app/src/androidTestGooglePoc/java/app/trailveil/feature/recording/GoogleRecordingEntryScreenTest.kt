package app.trailveil.feature.recording

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.googlepoc.FlingGestureInjector
import app.trailveil.map.GestureOwningGoogleMapView
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity recording-entry checks against the Google hosted map actual. */
@RunWith(AndroidJUnit4::class)
class GoogleRecordingEntryScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val createdSessionIds = ArrayList<Long>()
    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: PermissionHistory? = null

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
    fun productionEntryUsesGoogleMarkerFollowRecenterAndGestureOwnership() {
        dismissDisclosureIfShown()
        val mapView = awaitMapView()
        val map = awaitGoogleMap(mapView)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val first = GeoPoint(25.0330, 121.5654)
        val second = GeoPoint(25.0430, 121.5754)
        val third = GeoPoint(24.0000, 120.0000)

        val firstPointId = appendCompletedPoint(dao, first, timestamp = 1_000L)
        assertTrue(
            "RecordingEntryRoute did not observe the first accepted point: " +
                "expected=$firstPointId actual=" +
                composeRule.runOnIdle {
                    composeRule.activity.window.decorView
                        .getTag(R.id.recording_presentation_latest_point_id)
                },
            awaitUntil(10_000L) {
                composeRule.activity.window.decorView.getTag(R.id.recording_presentation_latest_point_id) ==
                    firstPointId
            },
        )
        awaitRecenterEnabled()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter).performClick()
        assertTrue(
            "recording-entry recenter did not enter following mode",
            awaitUntil(5_000L) {
                composeRule.onAllNodesWithContentDescription(
                    InstrumentationRegistry.getInstrumentation()
                        .targetContext
                        .getString(R.string.map_following_latest_location),
                ).fetchSemanticsNodes().isNotEmpty()
            },
        )
        assertTrue(
            "Google recording-entry camera never recentred to the accepted point",
            awaitCamera(map = map, target = first, zoom = 16.0f, timeoutMillis = 30_000L),
        )
        assertTrue(
            "recording-entry recenter did not preserve the exploration zoom",
            awaitCamera(map, first, zoom = 16.0f, timeoutMillis = 8_000L),
        )

        // Recenter enters following mode. A later accepted point must move the same real Google
        // map; this is not a Compose-only callback assertion.
        val secondPointId = appendCompletedPoint(dao, second, timestamp = 2_000L)
        assertTrue(
            "RecordingEntryRoute did not observe the second accepted point: " +
                "expected=$secondPointId actual=" +
                composeRule.runOnIdle {
                    composeRule.activity.window.decorView
                        .getTag(R.id.recording_presentation_latest_point_id)
                } +
                " db=" + runBlocking {
                    application.appContainer.recordingHistory.latestSessionSummary().first()
                        ?.latestAcceptedPoint?.id
                },
            awaitUntil(10_000L) {
                composeRule.activity.window.decorView.getTag(R.id.recording_presentation_latest_point_id) ==
                    secondPointId
            },
        )
        assertTrue(
            "recording-entry follow did not move to the next accepted point; " +
                "camera=${cameraTarget(map)}",
            awaitCamera(map, second, zoom = null, timeoutMillis = 30_000L),
        )

        // The real injected gesture owns the map and stops following. A later point must not pull
        // the camera to `third`; FlingGestureInjector always cleans rejected pointer streams.
        val beforeGesture = cameraTarget(map)
        injectFling(mapView)
        assertTrue(
            "recording-entry injected gesture did not move the Google camera",
            awaitUntil(5_000L) {
                val after = cameraTarget(map)
                distanceDegrees(after, beforeGesture) > MOVEMENT_TOLERANCE_DEGREES
            },
        )
        appendCompletedPoint(dao, third, timestamp = 3_000L)
        SystemClock.sleep(2_000L)
        assertTrue(
            "a post-gesture accepted point incorrectly resumed following",
            distanceDegrees(cameraTarget(map), third) > FOLLOW_STOP_ASSERTION_DISTANCE_DEGREES,
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            android.os.Bundle().apply {
                putString(
                    "stream",
                    "Google recording entry: accepted marker/recenter/follow/gesture path passed",
                )
            },
        )
    }

    private fun appendCompletedPoint(
        dao: app.trailveil.data.db.RecordingDao,
        point: GeoPoint,
        timestamp: Long,
    ): Long {
        val started = runBlocking {
            dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = timestamp,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "google-recording-entry-test",
                ),
                initialSegment = TrackSegmentEntity(
                    sessionId = 0L,
                    sequence = 0L,
                    startedAt = timestamp,
                    startReason = "SESSION_START",
                ),
            )
        }
        createdSessionIds += started.sessionId
        val pointId = runBlocking {
            val insertedPointId = dao.appendAcceptedPoint(
                point = TrackPointEntity(
                    sessionId = started.sessionId,
                    segmentId = started.segmentId,
                    sequence = 0L,
                    timestamp = timestamp,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    horizontalAccuracy = 5.0,
                ),
                distanceDeltaMeters = 10.0,
            )
            dao.closeRecording(
                sessionId = started.sessionId,
                segmentId = started.segmentId,
                endedAt = timestamp + 1_000L,
                status = RecordingStatus.COMPLETED,
                stopReason = "TEST_COMPLETE",
                segmentEndReason = "TEST_COMPLETE",
            )
            insertedPointId
        }
        return pointId
    }

    @Test
    fun theDisclosureNamesGoogleMapsAndItsTerms() {
        // `V02-006`: on the Google build the privacy sheet must name Google as the basemap
        // provider and carry the two documents Google Maps Platform Terms 3.2.2(a) require the
        // application to name: the Google Maps Additional Terms and the Google Privacy Policy.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val providerName = context.getString(R.string.map_provider_disclosure_name)
        val providerPrivacy = context.getString(R.string.map_provider_privacy_body)
        val providerTerms = context.getString(R.string.map_provider_terms_body)
        assertTrue(providerName.contains("Google"))
        assertTrue(providerPrivacy.contains("policies.google.com/privacy"))
        assertTrue(providerTerms.contains("maps.google.com/help/terms_maps"))

        composeRule.waitForIdle()
        dismissDisclosureIfShown()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Privacy).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacySheet).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(R.string.recording_entry_privacy_provider_label, providerName),
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(providerPrivacy).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(providerTerms).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacyDismiss).performClick()
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithTag(RecordingEntryTestTags.PrivacySheet)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
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

    private fun awaitRecenterEnabled() {
        assertTrue(
            "recording-entry Recenter never became enabled for the accepted point",
            awaitUntil(30_000L) {
                runCatching {
                    composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter).assertIsEnabled()
                }.isSuccess
            },
        )
    }

    private fun awaitMapView(): MapView {
        val found = AtomicReference<MapView?>()
        assertTrue(
            "MainActivity never attached a Google map view",
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
        assertTrue("Google recording-entry map never became ready", ready.await(30, TimeUnit.SECONDS))
        return requireNotNull(map.get())
    }

    private fun awaitCamera(
        map: GoogleMap,
        target: GeoPoint,
        zoom: Float?,
        timeoutMillis: Long,
    ): Boolean = awaitUntil(timeoutMillis) {
        val camera = cameraOnMain(map)
        kotlin.math.abs(camera.target.latitude - target.latitude) <= 0.001 &&
            kotlin.math.abs(camera.target.longitude - target.longitude) <= 0.001 &&
            (zoom == null || kotlin.math.abs(camera.zoom - zoom) <= 0.1f)
    }

    private fun cameraTarget(map: GoogleMap): LatLng = cameraOnMain(map).target

    private fun cameraOnMain(map: GoogleMap): com.google.android.gms.maps.model.CameraPosition {
        val camera = AtomicReference<com.google.android.gms.maps.model.CameraPosition>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            camera.set(map.cameraPosition)
        }
        return requireNotNull(camera.get())
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

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50L)
        }
        return condition()
    }

    private fun findMapView(view: View): MapView? {
        if (view is GestureOwningGoogleMapView) return view
        if (view is MapView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findMapView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun distanceDegrees(a: LatLng, b: GeoPoint): Double =
        kotlin.math.hypot(a.latitude - b.latitude, a.longitude - b.longitude)

    private fun distanceDegrees(a: LatLng, b: LatLng): Double =
        kotlin.math.hypot(a.latitude - b.latitude, a.longitude - b.longitude)

    private companion object {
        const val MOVEMENT_TOLERANCE_DEGREES = 1e-5
        const val FOLLOW_STOP_ASSERTION_DISTANCE_DEGREES = 0.01
    }
}
