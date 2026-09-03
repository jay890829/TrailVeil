package app.trailveil.map

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.MapAccessibilityBaseline
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.feature.history.RecordingHistoryTestTags
import app.trailveil.feature.recording.PermissionHistory
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.MapView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 9: Google twin of `MapAccessibilityBaselineTest`. Same three properties, same
 * helper, same screens — entry map and history detail map — on the real launcher. See
 * [MapAccessibilityBaseline] for what parity means and why it is asserted rather than recorded.
 */
@RunWith(AndroidJUnit4::class)
class GoogleMapAccessibilityParityTest {
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
        val application = composeRule.activity.application as TrailVeilApplication
        runBlocking {
            createdSessionIds.forEach { id ->
                application.appContainer.databaseForTesting().recordingDao().deleteSession(id)
            }
            originalPermissionHistory?.let { permissionHistory.replaceForTesting(it) }
        }
    }

    @Test
    fun entryAndDetailMapsAnnounceOneDescriptionAndLeakNoSdkNodes() {
        val expected = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.map_content_description)
        dismissDisclosureIfShown()
        // Resolve the entry map by its lowered cover rather than by first sight, so a view the
        // host replaces during startup is not watched while its tags never move.
        awaitLiveMapView("google entry: cover never lowered") { view ->
            view.getTag(R.id.map_fog_cover_up) == false
        }
        composeRule.waitForIdle()
        MapAccessibilityBaseline.assertMapContributesExactlyOneTarget(
            nodes = MapAccessibilityBaseline.dumpActiveWindow(),
            expectedDescription = expected,
            screen = "google entry",
        )

        val fixtureId = createCompletedSession(composeRule.activity.application as TrailVeilApplication)
        openFixtureDetail(fixtureId)
        awaitLiveMapView("google detail: map never loaded") { view ->
            view.getTag(R.id.map_detail_map_loaded) == true
        }
        composeRule.waitForIdle()
        MapAccessibilityBaseline.assertMapContributesExactlyOneTarget(
            nodes = MapAccessibilityBaseline.dumpActiveWindow(),
            expectedDescription = expected,
            screen = "google detail",
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

    private fun openFixtureDetail(fixtureId: Long) {
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingHistoryTestTags.item(fixtureId))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(fixtureId)).performScrollTo()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(fixtureId)).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingHistoryTestTags.TrackMap)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).performScrollTo()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).assertIsDisplayed()
    }

    private fun createCompletedSession(application: TrailVeilApplication): Long {
        val dao = application.appContainer.databaseForTesting().recordingDao()
        val started = runBlocking {
            dao.startSession(
                session = app.trailveil.data.db.RecordingSessionEntity(
                    startedAt = FIXTURE_STARTED_AT,
                    status = app.trailveil.data.db.RecordingStatus.ACTIVE,
                    createdAppVersion = "google-stage9-a11y",
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

    /** The attached MapView that satisfies [ready], re-resolved on every poll for 45 s. */
    private fun awaitLiveMapView(failure: String, ready: (MapView) -> Boolean): MapView {
        var live: MapView? = null
        var all: List<MapView> = emptyList()
        var tree = ""
        var routeRuntime: Any? = null
        val settled = awaitUntil(45_000L) {
            // runOnIdle advances the rule's frame clock; runOnUiThread does not, and a poll that
            // never idles the rule watches a composition that never recomposes.
            composeRule.runOnIdle {
                val decor = composeRule.activity.window.decorView
                all = decor.findMapViews().filter(View::isAttachedToWindow)
                live = all.firstOrNull(ready)
                tree = decor.describeTree()
                routeRuntime = decor.getTag(R.id.recording_entry_fog_runtime_loaded)
            }
            live != null
        }
        if (!settled) {
            val runtime = (composeRule.activity.application as TrailVeilApplication)
                .appContainer
                .fogRuntime()
            assertTrue(
                failure + ": mapViews=" +
                    all.withIndex().joinToString { (index, view) -> "#$index" + describe(view) } +
                    " routeRuntimeLoaded=$routeRuntime" +
                    " coordinatorLocked=${runtime.viewportCoordinator.isLockedForTesting}" +
                    " synchronizerLocked=${runtime.changeSynchronizer.isLockedForTesting}" +
                    " decorView=[$tree] threads=" + describeThreads(),
                false,
            )
        }
        return requireNotNull(live)
    }

    /** Frames only, for threads inside this app or its coroutine machinery. Nothing positional. */
    private fun describeThreads(): String =
        Thread.getAllStackTraces().entries
            .filter { (_, frames) ->
                frames.any { frame ->
                    frame.className.startsWith("app.trailveil") ||
                        frame.className.startsWith("kotlinx.coroutines")
                }
            }
            .take(12)
            .joinToString(prefix = "[", postfix = "]") { (thread, frames) ->
                thread.name + ":" + thread.state + "<" +
                    frames.take(8).joinToString("<-") { frame ->
                        frame.className.substringAfterLast('.') + "." + frame.methodName +
                            ":" + frame.lineNumber
                    } + ">"
            }

    private fun describe(mapView: MapView): String =
        "[runtimePresent=${mapView.getTag(R.id.map_fog_runtime_present)} " +
            "effectEpoch=${mapView.getTag(R.id.map_fog_effect_epoch)} " +
            "binding=${mapView.getTag(R.id.map_fog_binding_state)} " +
            "phase=${mapView.getTag(R.id.map_fog_phase)} " +
            "gates=[${mapView.getTag(R.id.map_fog_binding_gates)}] " +
            "lastFogFailure=${mapView.getTag(R.id.map_fog_last_failure)} " +
            "basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
            "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
            "cover=${mapView.getTag(R.id.map_fog_cover_up)} " +
            "detailLoaded=${mapView.getTag(R.id.map_detail_map_loaded)} " +
            "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown}]"

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

    private fun View.findMapViews(): List<MapView> {
        if (this is MapView) return listOf(this)
        if (this !is ViewGroup) return emptyList()
        return (0 until childCount).flatMap { index -> getChildAt(index).findMapViews() }
    }

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            android.os.SystemClock.sleep(200L)
        }
        return condition()
    }

    private companion object {
        const val NAVIGATION_TIMEOUT_MILLIS = 30_000L
        const val FIXTURE_STARTED_AT = 9_013_300_000L
        val TRACK_POINTS = listOf(
            GeoPoint(latitude = 25.0330, longitude = 121.5654),
            GeoPoint(latitude = 25.0334, longitude = 121.5660),
        )
    }
}
