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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import app.trailveil.googlepoc.FlingGestureInjector
import app.trailveil.map.GestureOwningGoogleMapView
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.CameraUpdateFactory
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

    /**
     * Pressing recentre from a camera that is somewhere else at another zoom lands the zoom the
     * press asked for, and a real follow step afterwards does not take that zoom away again.
     *
     * Pressed from where the opening one-shot left the camera, this can only pass: the camera is
     * already on the point at the exploration zoom, so a swallowed zoom would be invisible. The
     * press is therefore made from a camera driven away in both target and zoom, and BOTH halves
     * of that away camera are asserted before the press - a case that drove the zoom away but
     * silently failed to move the target would still be measuring a recentre to where the camera
     * already was.
     *
     * What the away camera does NOT do, contrary to what this KDoc used to claim, is let the
     * follow step compete with the request. `RecordingEntryRoute.onRecenter` writes
     * `requestedFollowing` and `cameraRequest` from the same `currentLocation`, so the surface's
     * follow effect meets `cameraRequest?.point == target` and returns before it can move
     * anything; no press from any camera can get past that. The only state on this route where
     * the guard does not short-circuit is a follow fix that is a DIFFERENT point from the standing
     * request, so the competition is driven at the end, by appending a second accepted point once
     * the recentre has settled: from there a real EASE follow step runs, and the assertion that
     * matters is that it lands the new fix while the exploration zoom the press asked for is still
     * standing. A follow move that carried a zoom of its own would fail it.
     *
     * Nothing here waits on canonical fog. The programmed exit raises the opaque cover by design,
     * and an earlier version of this case waited for the cover to come back down before pressing.
     * That wait is not a precondition of the property - the cover is a fog-coloured overlay above
     * the map and neither the recentre control nor the camera is behind it - and it made the case
     * depend on a full render-and-snapshot-prove cycle at a throwaway viewport. On the recorded
     * device run it failed there with `reason=FIRST_COMPOSITION`, which is the coordinator's
     * cover reason from before any generation was ever proven: the surface had already terminated
     * with its first cover still up, and the tags this case reads had simply stopped being
     * rewritten. Waiting longer would not have helped, and the property is measured without it.
     */
    @Test
    fun recenterFromAnotherZoomLandsTheExplorationZoomItAsksFor() {
        // Loud, not skipped. This case is what inventory row 93 is recorded closed on, and a
        // keyless run that silently skipped it would be indistinguishable from a run that proved
        // it. The class's older cases have no gate at all and simply fail when no hosted map
        // appears, so a keyless run is already red here; only the abstention is new.
        assertTrue(
            "the hosted Google map needs the keyed googlePoc runtime",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        dismissDisclosureIfShown()
        val mapView = awaitMapView()
        val map = awaitGoogleMap(mapView)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val point = GeoPoint(25.0330, 121.5654)

        val pointId = appendCompletedPoint(dao, point, timestamp = 11_000L)
        assertTrue(
            "RecordingEntryRoute did not observe the accepted point: expected=$pointId actual=" +
                composeRule.runOnIdle {
                    composeRule.activity.window.decorView
                        .getTag(R.id.recording_presentation_latest_point_id)
                },
            awaitUntil(10_000L) {
                composeRule.activity.window.decorView
                    .getTag(R.id.recording_presentation_latest_point_id) == pointId
            },
        )
        awaitRecenterEnabled()
        // The opening one-shot is the only other thing that moves this camera, and it aims at
        // whichever point was latest when the screen opened. Waiting for the camera to go quiet is
        // what makes the press the sole cause of the move this case measures.
        assertTrue(
            "the entry surface never stopped moving the camera on its own",
            awaitCameraQuiescent(map),
        )
        val away = GeoPoint(
            latitude = point.latitude + AWAY_OFFSET_DEGREES,
            longitude = point.longitude + AWAY_OFFSET_DEGREES,
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(away.latitude, away.longitude),
                    AWAY_ZOOM,
                ),
            )
        }
        // The away camera has to be settled and it has to be BOTH things it claims to be. A second
        // quiescence check is what makes the press the sole cause of the move measured below: the
        // move above is instantaneous, so anything still moving this camera afterwards is the
        // surface, and it would be measured as if the press had done it.
        assertTrue(
            "the entry surface kept moving the camera after the programmed exit",
            awaitCameraQuiescent(map),
        )
        val beforePress = cameraOnMain(map)
        assertEquals(
            "the programmed exit did not land the away latitude: $beforePress",
            away.latitude,
            beforePress.target.latitude,
            TARGET_TOLERANCE_DEGREES,
        )
        assertEquals(
            "the programmed exit did not land the away longitude: $beforePress",
            away.longitude,
            beforePress.target.longitude,
            TARGET_TOLERANCE_DEGREES,
        )
        assertTrue(
            "the press was about to be made from the exploration zoom it asks for, so a " +
                "swallowed zoom could not be seen: zoom=${beforePress.zoom}",
            kotlin.math.abs(beforePress.zoom - EXPLORATION_ZOOM) > ZOOM_TOLERANCE,
        )

        composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter).performClick()
        assertTrue(
            "recording-entry recenter did not enter following mode",
            awaitUntil(5_000L) {
                nodesDescribed(R.string.map_following_latest_location) == 1
            },
        )
        assertTrue(
            "recenter from another zoom did not land the point at the exploration zoom: " +
                "from=${cameraOnMain(map)}",
            awaitCamera(map, point, zoom = EXPLORATION_ZOOM, timeoutMillis = 30_000L),
        )
        // Settled, not merely passed through: a flight that reached the zoom and then let a follow
        // step pull it back out would satisfy a poll but not the user.
        SystemClock.sleep(RECENTER_SETTLE_MILLIS)
        val settled = cameraOnMain(map)
        assertEquals(point.latitude, settled.target.latitude, TARGET_TOLERANCE_DEGREES)
        assertEquals(point.longitude, settled.target.longitude, TARGET_TOLERANCE_DEGREES)
        assertEquals(EXPLORATION_ZOOM, settled.zoom, ZOOM_TOLERANCE)

        // The follow step, finally able to compete. Following is still on, and this second
        // accepted point is a DIFFERENT point from the standing camera request - the only state on
        // this route where `cameraRequest?.point == target` does not return before the follow
        // effect can move anything. The offset clears the follow dead zone and stays inside the
        // screen, so the exempt EASE branch runs, and that branch carries no zoom of its own.
        val followPoint = GeoPoint(
            latitude = point.latitude + FOLLOW_STEP_OFFSET_DEGREES,
            longitude = point.longitude,
        )
        val followPointId = appendCompletedPoint(dao, followPoint, timestamp = 12_000L)
        assertTrue(
            "RecordingEntryRoute did not observe the follow fix: expected=$followPointId actual=" +
                composeRule.runOnIdle {
                    composeRule.activity.window.decorView
                        .getTag(R.id.recording_presentation_latest_point_id)
                },
            awaitUntil(10_000L) {
                composeRule.activity.window.decorView
                    .getTag(R.id.recording_presentation_latest_point_id) == followPointId
            },
        )
        assertTrue(
            "the follow step never moved to the new fix, so nothing competed for the camera: " +
                "from=${cameraOnMain(map)}",
            awaitCamera(map, followPoint, zoom = null, timeoutMillis = FOLLOW_STEP_TIMEOUT_MILLIS),
        )
        SystemClock.sleep(RECENTER_SETTLE_MILLIS)
        val afterFollow = cameraOnMain(map)
        assertEquals(followPoint.latitude, afterFollow.target.latitude, TARGET_TOLERANCE_DEGREES)
        assertEquals(followPoint.longitude, afterFollow.target.longitude, TARGET_TOLERANCE_DEGREES)
        assertEquals(
            "the competing follow step ate the exploration zoom the press asked for: $afterFollow",
            EXPLORATION_ZOOM,
            afterFollow.zoom,
            ZOOM_TOLERANCE,
        )
    }

    /**
     * The recentre control announces centring before the first press, following after it, and
     * centring again once a real gesture has ended following.
     *
     * Only the middle state was asserted, so a button stuck on the following label passed. Both
     * ends are the discoverable half: before the press it has to say what a press would do, and
     * after a hand has taken the map it has to stop claiming a mode the map is no longer in.
     */
    @Test
    fun theRecenterButtonAnnouncesCentringBeforeFollowingAndAgainAfterAGesture() {
        // Loud for the same reason: inventory row 108 is recorded closed on this case.
        assertTrue(
            "the hosted Google map needs the keyed googlePoc runtime",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dismissDisclosureIfShown()
        val mapView = awaitMapView()
        val map = awaitGoogleMap(mapView)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val point = GeoPoint(25.0330, 121.5654)

        val pointId = appendCompletedPoint(dao, point, timestamp = 21_000L)
        assertTrue(
            "RecordingEntryRoute did not observe the accepted point: expected=$pointId actual=" +
                composeRule.runOnIdle {
                    composeRule.activity.window.decorView
                        .getTag(R.id.recording_presentation_latest_point_id)
                },
            awaitUntil(10_000L) {
                composeRule.activity.window.decorView
                    .getTag(R.id.recording_presentation_latest_point_id) == pointId
            },
        )
        awaitRecenterEnabled()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.map_center_latest_location))
            .assertIsDisplayed()
        assertEquals(
            "the control announced following before any press",
            0,
            nodesDescribed(R.string.map_following_latest_location),
        )

        composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter).performClick()
        assertTrue(
            "the control did not switch to the following announcement after the press",
            awaitUntil(5_000L) {
                nodesDescribed(R.string.map_following_latest_location) == 1 &&
                    nodesDescribed(R.string.map_center_latest_location) == 0
            },
        )
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.map_following_latest_location))
            .assertIsDisplayed()
        // No camera assertion belongs here. The opening one-shot has already flown this camera to
        // the same point at the same EXPLORATION_ZOOM the press asks for, so a wait for that
        // camera is satisfied by its first poll whatever the press did, and it read as proof that
        // "following was a real mode" while proving nothing at all. The property this case owns is
        // the announcement; the camera consequence of a press from a DIFFERENT camera is owned by
        // `recenterFromAnotherZoomLandsTheExplorationZoomItAsksFor`, and the follow consequence by
        // `productionEntryUsesGoogleMarkerFollowRecenterAndGestureOwnership`.

        // A real hand on the real map is what ends following; the injected fling is the same
        // single-pointer stream the gesture-ownership case uses.
        val beforeGesture = cameraTarget(map)
        injectFling(mapView)
        assertTrue(
            "the injected gesture did not move the Google camera, so nothing ended following",
            awaitUntil(5_000L) {
                distanceDegrees(cameraTarget(map), beforeGesture) > MOVEMENT_TOLERANCE_DEGREES
            },
        )
        assertTrue(
            "the control did not return to the centring announcement once the gesture ended " +
                "following",
            awaitUntil(5_000L) {
                nodesDescribed(R.string.map_center_latest_location) == 1 &&
                    nodesDescribed(R.string.map_following_latest_location) == 0
            },
        )
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.map_center_latest_location))
            .assertIsDisplayed()
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

    /** How many nodes in the live window announce [stringId]; zero is an assertable absence. */
    private fun nodesDescribed(stringId: Int): Int =
        composeRule.onAllNodesWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(stringId),
        ).fetchSemanticsNodes().size

    /**
     * True once the surface has stopped moving the camera by itself.
     *
     * Asking only "is the camera where the opening one-shot aimed it" cannot see that one-shot
     * still in flight, so a case that drives the camera somewhere else next would race it.
     */
    private fun awaitCameraQuiescent(
        map: GoogleMap,
        timeoutMillis: Long = QUIESCENT_TIMEOUT_MILLIS,
        stableSamples: Int = QUIESCENT_STABLE_SAMPLES,
    ): Boolean {
        var previous: com.google.android.gms.maps.model.CameraPosition? = null
        var stable = 0
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = cameraOnMain(map)
            val last = previous
            stable = if (
                last != null &&
                kotlin.math.abs(now.target.latitude - last.target.latitude) <=
                    TARGET_TOLERANCE_DEGREES &&
                kotlin.math.abs(now.target.longitude - last.target.longitude) <=
                    TARGET_TOLERANCE_DEGREES &&
                kotlin.math.abs(now.zoom - last.zoom) <= ZOOM_TOLERANCE
            ) {
                stable + 1
            } else {
                0
            }
            previous = now
            if (stable >= stableSamples) return true
            SystemClock.sleep(QUIESCENT_SAMPLE_INTERVAL_MILLIS)
        }
        return false
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

        /** The zoom the entry route's recentre asks for; the same value `awaitCamera` is given. */
        const val EXPLORATION_ZOOM = 16.0f
        const val ZOOM_TOLERANCE = 0.1f
        const val TARGET_TOLERANCE_DEGREES = 0.001

        /**
         * Far enough that the recentre target leaves the away viewport (so a zoom-less follow move
         * would be a visible JUMP), and four levels out, so a swallowed zoom cannot hide.
         */
        const val AWAY_OFFSET_DEGREES = 0.5
        const val AWAY_ZOOM = 12.0f

        /**
         * Roughly a third of a kilometre: past the 0.12 follow dead zone at the exploration zoom
         * on any plausible viewport, comfortably inside half the shorter edge so the EASE branch
         * rather than JUMP runs, and three times [TARGET_TOLERANCE_DEGREES] so arriving at the new
         * fix cannot be confused with staying on the old one.
         */
        const val FOLLOW_STEP_OFFSET_DEGREES = 0.003

        /** One 450 ms ease plus room for the recomposition that dispatches it. */
        const val FOLLOW_STEP_TIMEOUT_MILLIS = 15_000L

        const val RECENTER_SETTLE_MILLIS = 1_500L
        const val QUIESCENT_TIMEOUT_MILLIS = 20_000L
        const val QUIESCENT_SAMPLE_INTERVAL_MILLIS = 250L
        const val QUIESCENT_STABLE_SAMPLES = 4
    }
}
