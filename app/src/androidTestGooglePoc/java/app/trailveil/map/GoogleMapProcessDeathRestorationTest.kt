package app.trailveil.map

import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.feature.history.RecordingHistoryTestTags
import app.trailveil.feature.recording.PermissionHistory
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 9: the design's process-death stance, proven where instrumentation CAN prove it.
 *
 * The stance (design §6): camera is RESTORED from the provider-tagged saved-state envelope; fog is
 * deliberately not persisted and is RECONSTRUCTED from canonical Room behind the opaque cover; the
 * back stack, including a detail `sessionId`, comes back and re-enters through
 * `HistoryRouteState.Loading`; a foreign provider's envelope is discarded and the map starts clean.
 *
 * What instrumentation cannot do is the literal `am kill` + relaunch: ActivityManager force-stops
 * the target package whenever an instrumentation run ends or its process dies, and a force-stop
 * removes the task with the framework's saved state, so no in-process test can plant state, die,
 * and find it again. That half is `.github/scripts/verify-process-death-restoration.sh`, driven from
 * the host against the plain app and observed through the screen; it is device-only and excluded
 * from hosted shards under the same regime as `NotificationStartContinuationTest`.
 *
 * Everything else the stance promises is proven here:
 *  - the FIRST launch of an instrumentation run is a genuinely cold process (`am instrument`
 *    force-stops first), so the canonical rebuild, dynamite cold load and fogRuntime reload are all
 *    exercised by the first generation this test waits for;
 *  - `recreate()` on the detail route restores the `sessionId` argument and re-enters through
 *    Loading to a loaded detail map on a NEW MapView;
 *  - a real saved-state envelope, captured from the surface's own `onSaveInstanceState`, restores
 *    the camera when replayed unmodified and is discarded when its provider tag is foreign.
 */
@RunWith(AndroidJUnit4::class)
class GoogleMapProcessDeathRestorationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val createdSessionIds = ArrayList<Long>()
    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: PermissionHistory? = null

    @Before
    fun setUp() {
        GoogleMapSurfaceTestHooks.reset()
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
        val application = application()
        runBlocking {
            createdSessionIds.forEach { id ->
                application.appContainer.databaseForTesting().recordingDao().deleteSession(id)
            }
            originalPermissionHistory?.let { permissionHistory.replaceForTesting(it) }
        }
    }

    @Test
    fun aColdProcessRebuildsFogBehindTheCoverAndRecreationRestoresTheDetailEntryThroughLoading() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Cold process: the first generation is a canonical rebuild, not a restart re-proof.
            // Resolve the launcher's MapView by that generation rather than by first sight, so a
            // view the host replaces during startup is not watched while its tags never move.
            val mapView = awaitLiveGeneration(scenario)
            assertTrue(
                "cold process never lowered the cover after its rebuild: " + describe(mapView),
                awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
            )
            val coverInterval = mapView.getTag(R.id.map_fog_last_cover_interval_ms) as? Long
            assertNotNull("the cold rebuild was not covered while it ran", coverInterval)
            assertTrue("cold cover interval must be positive", requireNotNull(coverInterval) > 0L)

            // Seed the fixture the way the accepted launcher tests do: after the entry map is up,
            // then let the production history route observe the row before navigating to it.
            val fixtureId = createCompletedSession(application())
            awaitLatestPointObserved(scenario)
            dismissDisclosureIfShown()
            openFixtureHistoryList(fixtureId)
            openFixtureDetailFromList()
            val firstDetail = awaitDetailMapView(scenario)
            assertTrue(
                "the detail map never loaded before recreation",
                awaitTag(firstDetail, R.id.map_detail_map_loaded) { it == true },
            )

            scenario.recreate()

            // Re-entry through Loading: nothing may claim the session is missing, and the loaded
            // detail must come back on a fresh MapView with the fixture's track.
            composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(RecordingHistoryTestTags.TrackMap)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertTrue(
                "the restored detail entry reported the session as missing",
                composeRule.onAllNodesWithTag(RecordingHistoryTestTags.DetailMissing)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            composeRule.onNodeWithTag(RecordingHistoryTestTags.Detail).assertIsDisplayed()
            val restoredDetail = awaitDetailMapView(scenario)
            assertNotSame("recreation reused the destroyed detail MapView", firstDetail, restoredDetail)
            assertTrue(
                "the restored detail map never loaded: " + describe(restoredDetail),
                awaitTag(restoredDetail, R.id.map_detail_map_loaded) { it == true },
            )
            composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performScrollTo()
        }
    }

    /**
     * The cold half of the stance as a real user meets it: history already in Room, THEN a launch.
     * Fog must be reconstructed from canonical data behind the cover on the launcher itself. The
     * first device pass failed exactly this shape with the binding reporting no fog runtime for
     * 45 s, so a failure here carries a frames-only thread dump: a starved dispatcher or a held
     * lock names itself instead of hiding behind an innocent timeout.
     */
    @Test
    fun aColdStartWithExistingHistoryRebuildsFogFromCanonicalRoomBehindTheCover() {
        createCompletedSession(application())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val mapView = awaitLiveGeneration(scenario)
            assertTrue(
                "launch with history never lowered the cover after its rebuild: " + describe(mapView),
                awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
            )
            val coverInterval = mapView.getTag(R.id.map_fog_last_cover_interval_ms) as? Long
            assertNotNull("the rebuild with history was not covered while it ran", coverInterval)
            assertTrue("cover interval must be positive", requireNotNull(coverInterval) > 0L)
        }
    }

    @Test
    fun aRealEnvelopeRestoresTheCameraAndAForeignEnvelopeIsDiscarded() {
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))

        // Capture a real envelope from the surface's own onSaveInstanceState.
        val captured = AtomicReference<Bundle>()
        GoogleMapSurfaceTestHooks.onSaveInstanceState.set { captured.set(deepCopy(it)) }
        val planted = CameraPosition.Builder()
            .target(LatLng(25.0330, 121.5654))
            .zoom(13.25f)
            .bearing(41f)
            .tilt(25f)
            .build()
        val firstMap = armTestMap()
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            val map = firstMap.await()
            scenario.onActivity { map.moveCamera(CameraUpdateFactory.newCameraPosition(planted)) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        }
        val envelope = requireNotNull(captured.get()) { "the surface never saved instance state" }
        val provider = findProviderEntry(envelope)
        assertNotNull("no provider-tagged map envelope was saved", provider)
        assertEquals("google", requireNotNull(provider).getString(MAP_SAVED_STATE_PROVIDER_KEY))

        // Positive control: replaying the untouched envelope restores the camera.
        GoogleMapSurfaceTestHooks.plantedSavedInstanceState = deepCopy(envelope)
        val restoredMap = armTestMap()
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            val map = restoredMap.await()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val restored = readCamera(scenario, map)
            assertEquals(planted.target.latitude, restored.target.latitude, 0.0001)
            assertEquals(planted.target.longitude, restored.target.longitude, 0.0001)
            assertEquals(planted.zoom, restored.zoom, 0.01f)
            assertEquals(planted.bearing, restored.bearing, 0.01f)
            assertEquals(planted.tilt, restored.tilt, 0.01f)
        }

        // Foreign tag: the same payload under another provider's name must be ignored entirely.
        // A deep copy, because restoring the positive control CONSUMED the shared nested entry
        // that a shallow Bundle(envelope) would still point at.
        val foreign = deepCopy(envelope)
        requireNotNull(findProviderEntry(foreign)).putString(MAP_SAVED_STATE_PROVIDER_KEY, "maplibre")
        GoogleMapSurfaceTestHooks.plantedSavedInstanceState = foreign
        val cleanMap = armTestMap()
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            val map = cleanMap.await()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val clean = readCamera(scenario, map)
            assertFalse(
                "a foreign provider envelope restored this provider's camera",
                kotlin.math.abs(clean.bearing - planted.bearing) < 0.01f &&
                    kotlin.math.abs(clean.tilt - planted.tilt) < 0.01f &&
                    kotlin.math.abs(clean.zoom - planted.zoom) < 0.01f,
            )
            assertNotEquals(planted.bearing, clean.bearing, 0.01f)
        }
    }

    /** The registry stores one child bundle per saved-state key; the map's carries `provider`. */
    private fun findProviderEntry(bundle: Bundle): Bundle? {
        if (bundle.containsKey(MAP_SAVED_STATE_PROVIDER_KEY)) return bundle
        for (key in bundle.keySet()) {
            val child = runCatching { bundle.getBundle(key) }.getOrNull() ?: continue
            findProviderEntry(child)?.let { return it }
        }
        return null
    }

    /** Armed BEFORE launch: the hook fires once, whenever Play services delivers the map. */
    private fun armTestMap(): ArmedMap {
        val ready = CountDownLatch(1)
        val mapRef = AtomicReference<GoogleMap>()
        GoogleMapSurfaceTestHooks.onMapReady.set { map ->
            mapRef.set(map)
            ready.countDown()
        }
        return ArmedMap(mapRef, ready)
    }

    private class ArmedMap(
        private val mapRef: AtomicReference<GoogleMap>,
        private val ready: CountDownLatch,
    ) {
        fun await(): GoogleMap {
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            return requireNotNull(mapRef.get())
        }
    }

    private fun readCamera(
        scenario: ActivityScenario<GoogleMapSurfaceTestActivity>,
        map: GoogleMap,
    ): CameraPosition {
        val camera = AtomicReference<CameraPosition>()
        scenario.onActivity { camera.set(map.cameraPosition) }
        return requireNotNull(camera.get())
    }

    private fun application(): TrailVeilApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as TrailVeilApplication

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

    private fun openFixtureHistoryList(fixtureId: Long) {
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingHistoryTestTags.item(fixtureId))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(fixtureId)).performScrollTo()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(fixtureId)).performClick()
    }

    private fun openFixtureDetailFromList() {
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingHistoryTestTags.TrackMap)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).assertIsDisplayed()
    }

    private fun createCompletedSession(application: TrailVeilApplication): Long {
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val started = runBlocking {
            dao.startSession(
                session = app.trailveil.data.db.RecordingSessionEntity(
                    startedAt = FIXTURE_STARTED_AT,
                    status = app.trailveil.data.db.RecordingStatus.ACTIVE,
                    createdAppVersion = "google-stage9-test",
                ),
                initialSegment = app.trailveil.data.db.TrackSegmentEntity(
                    sessionId = 0L,
                    sequence = 0L,
                    startedAt = FIXTURE_STARTED_AT,
                    startReason = "SESSION_START",
                ),
            )
        }
        createdSessionIds += started.sessionId
        runBlocking {
            TRACK_POINTS.forEachIndexed { index, point ->
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

    private fun describe(mapView: MapView): String =
        "[runtimePresent=${mapView.getTag(R.id.map_fog_runtime_present)} " +
            "effectEpoch=${mapView.getTag(R.id.map_fog_effect_epoch)} " +
            "restored=${mapView.getTag(R.id.map_saved_state_restored)} " +
            "defaultAtReady=${mapView.getTag(R.id.map_camera_default_at_ready)} " +
            "binding=${mapView.getTag(R.id.map_fog_binding_state)} " +
            "phase=${mapView.getTag(R.id.map_fog_phase)} " +
            "gates=[${mapView.getTag(R.id.map_fog_binding_gates)}] " +
            "live=[${(mapView.getTag(R.id.map_fog_binding_instance) as? GoogleCanonicalFogSurfaceBinding)?.describeForTesting()}] " +
            "lastFogFailure=${mapView.getTag(R.id.map_fog_last_failure)} " +
            "basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
            "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
            "cover=${mapView.getTag(R.id.map_fog_cover_up)} " +
            "detailLoaded=${mapView.getTag(R.id.map_detail_map_loaded)} " +
            "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown}]"

    /** The attached MapView that carries a proven generation, re-resolved on every poll. */
    private fun awaitLiveGeneration(scenario: ActivityScenario<MainActivity>): MapView {
        val live = AtomicReference<List<MapView>>(emptyList())
        // The last poll that saw any MapView at all: a surface that fell back to the unavailable
        // placeholder removes its view, and the failure needs that view's last diagnostics.
        val lastSeen = AtomicReference<List<MapView>>(emptyList())
        val tree = AtomicReference<String>()
        val routeRuntime = AtomicReference<Any?>()
        val presentation = AtomicReference<String>()
        repeat(GENERATION_POLLS) {
            // Idle the rule before every read: under a Compose test rule the frame clock advances
            // only while the rule idles, so a raw main-thread hop would poll a composition that
            // never recomposes. The accepted stage-8 rule-based launcher tests poll the same way.
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                live.set(decor.findMapViews())
                if (live.get().isNotEmpty()) lastSeen.set(live.get())
                tree.set(decor.describeTree())
                routeRuntime.set(decor.getTag(R.id.recording_entry_fog_runtime_loaded))
                presentation.set(
                    "state=${decor.getTag(R.id.recording_presentation_state)} " +
                        "outcome=${decor.getTag(R.id.recording_presentation_latest_outcome)} " +
                        "latestPoint=${decor.getTag(R.id.recording_presentation_latest_point_id) != null} " +
                        "composition=[${decor.getTag(R.id.recording_entry_composition)}]",
                )
            }
            live.get().firstOrNull { it.getTag(R.id.map_fog_canonical_generation) != null }
                ?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        val runtime = application().appContainer.fogRuntime()
        error(
            "canonical fog never installed on the production launcher: mapViews=" +
                live.get().withIndex().joinToString { (index, view) -> "#$index" + describe(view) } +
                " lastSeenMapViews=" +
                lastSeen.get().withIndex().joinToString { (index, view) -> "#$index" + describe(view) } +
                " routeRuntimeLoaded=${routeRuntime.get()}" +
                " presentation=[${presentation.get()}]" +
                " screen=[menu=${nodeCount(RecordingEntryTestTags.Menu)}" +
                " unavailable=${nodeCount(MapSurfaceTestTags.ProviderUnavailable)}" +
                " privacy=${nodeCount(RecordingEntryTestTags.PrivacySheet)}" +
                " detail=${nodeCount(RecordingHistoryTestTags.Detail)}" +
                " trackMap=${nodeCount(RecordingHistoryTestTags.TrackMap)}]" +
                " coordinatorLocked=${runtime.viewportCoordinator.isLockedForTesting}" +
                " synchronizerLocked=${runtime.changeSynchronizer.isLockedForTesting}" +
                " decorView=[${tree.get()}]" +
                " threads=" + describeThreads(),
        )
    }

    /**
     * Frames only, for the threads that are inside this app or its coroutine machinery: names,
     * states and the top of each stack. No message text, no values, nothing positional.
     */
    private fun describeThreads(): String =
        Thread.getAllStackTraces().entries
            .filter { (_, frames) ->
                frames.any { frame ->
                    frame.className.startsWith("app.trailveil") ||
                        frame.className.startsWith("kotlinx.coroutines")
                }
            }
            .take(THREAD_DUMP_MAX_THREADS)
            .joinToString(prefix = "[", postfix = "]") { (thread, frames) ->
                thread.name + ":" + thread.state + "<" +
                    frames.take(THREAD_DUMP_MAX_FRAMES).joinToString("<-") { frame ->
                        frame.className.substringAfterLast('.') + "." + frame.methodName +
                            ":" + frame.lineNumber
                    } + ">"
            }

    /** The production entry route reports the newest persisted point on its decor view. */
    private fun awaitLatestPointObserved(scenario: ActivityScenario<MainActivity>) {
        val observed = AtomicReference<Any?>()
        repeat(LATEST_POINT_POLLS) {
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                observed.set(
                    activity.window.decorView.getTag(R.id.recording_presentation_latest_point_id),
                )
            }
            if (observed.get() != null) return
            Thread.sleep(POLL_MILLIS)
        }
        error("recording entry never observed the seeded history point")
    }

    /**
     * A Parcel round-trip. [Bundle]'s copy constructor is shallow, and restoring a planted bundle
     * CONSUMES the registry's nested entry, so a shallow copy shared with a later replay is emptied
     * by the first. This is also the shape the framework hands an Activity after real process death.
     */
    private fun deepCopy(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private fun nodeCount(tag: String): Int =
        runCatching { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size }
            .getOrDefault(-1)

    /** Class names only: what is on screen, never anything a user typed. */
    private fun View.describeTree(): String {
        val names = linkedSetOf<String>()
        fun walk(view: View) {
            names += view.javaClass.simpleName.ifEmpty { view.javaClass.name.substringAfterLast('.') }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> walk(view.getChildAt(index)) }
            }
        }
        walk(this)
        return names.joinToString(",")
    }

    private fun awaitTag(mapView: MapView, key: Int, predicate: (Any?) -> Boolean): Boolean {
        repeat(TAG_POLLS) {
            if (predicate(mapView.getTag(key))) return true
            composeRule.waitForIdle()
            Thread.sleep(POLL_MILLIS)
        }
        return predicate(mapView.getTag(key))
    }

    /** The attached MapView that has reported its detail map loaded, re-resolved on every poll. */
    private fun awaitDetailMapView(scenario: ActivityScenario<MainActivity>): MapView {
        val found = AtomicReference<MapView>()
        val seen = AtomicReference<MapView>()
        repeat(MAP_VIEW_POLLS) {
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                val attached = activity.window.decorView.findMapViews()
                    .filter(View::isAttachedToWindow)
                seen.set(attached.firstOrNull())
                found.set(attached.firstOrNull { it.getTag(R.id.map_detail_map_loaded) == true })
            }
            found.get()?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error(
            "the detail route never reported a loaded Google MapView: " +
                (seen.get()?.let(::describe) ?: "no attached MapView"),
        )
    }

    private fun View.findMapViews(): List<MapView> {
        if (this is MapView) return listOf(this)
        if (this !is ViewGroup) return emptyList()
        return (0 until childCount).flatMap { index -> getChildAt(index).findMapViews() }
    }

    private companion object {
        const val POLL_MILLIS = 250L
        const val GENERATION_POLLS = 180
        const val TAG_POLLS = 180
        const val MAP_VIEW_POLLS = 120
        const val NAVIGATION_TIMEOUT_MILLIS = 30_000L
        const val LATEST_POINT_POLLS = 40
        const val THREAD_DUMP_MAX_THREADS = 12
        const val THREAD_DUMP_MAX_FRAMES = 8
        const val FIXTURE_STARTED_AT = 9_013_200_000L
        val TRACK_POINTS = listOf(
            GeoPoint(latitude = 25.0330, longitude = 121.5654),
            GeoPoint(latitude = 25.0334, longitude = 121.5660),
            GeoPoint(latitude = 25.0339, longitude = 121.5667),
        )
    }
}
