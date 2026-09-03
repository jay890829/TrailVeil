package app.trailveil.map

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

/**
 * `V02-005` stage 9: the MapLibre accessibility baseline, asserted live rather than recorded.
 *
 * This is the shipped provider's reality as measured on device — one localized map description,
 * the attribution control beneath it and nothing else, no SDK class exposing a name — stated
 * through [MapAccessibilityBaseline] so the Google twin
 * (the accessibility parity test in the googlePoc instrumentation tree; its name is not spelled here
 * because this tree must stay free of Google provider markers) asserts the identical shape on the
 * identical screens. If the shipped provider ever changes shape, this test moves first and the twin
 * must follow.
 */
@RunWith(AndroidJUnit4::class)
class MapAccessibilityBaselineTest {
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
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = targetContext.getString(R.string.map_content_description)
        // The one SDK control the shipped surface exposes beneath its map node, by the SDK's own
        // localized string: the attribution icon that opens the attribution dialog.
        val attribution = targetContext.getString(
            org.maplibre.android.R.string.maplibre_attributionsIconContentDescription,
        )
        val shippedControls = listOf<Pair<String, (MapAccessibilityBaseline.NodeSummary) -> Boolean>>(
            "attribution control" to { node -> node.description == attribution },
        )
        dismissDisclosureIfShown()
        // Steady fogged state on the shipped surface: a fog slot installed and the opaque cover
        // gone. The canonical generation stays unpublished until Room holds points, so it is not
        // the precondition for an empty-history entry screen.
        awaitLiveMapView("entry map never installed a fog slot") { view ->
            view.getTag(R.id.map_fog_active_slot) != null
        }
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.waitForIdle()
        MapAccessibilityBaseline.assertMapContributesExactlyOneTarget(
            nodes = MapAccessibilityBaseline.dumpActiveWindow(),
            expectedDescription = expected,
            screen = "maplibre entry",
            expectedDescendants = shippedControls,
        )

        val fixtureId = createCompletedSession(composeRule.activity.application as TrailVeilApplication)
        openFixtureDetail(fixtureId)
        awaitLiveMapView("detail map never laid out") { view -> view.isLaidOut && view.width > 0 }
        composeRule.waitForIdle()
        MapAccessibilityBaseline.assertMapContributesExactlyOneTarget(
            nodes = MapAccessibilityBaseline.dumpActiveWindow(),
            expectedDescription = expected,
            screen = "maplibre detail",
            expectedDescendants = shippedControls,
        )
    }

    @Test
    fun attributionAndLogoStayAboveTheNavigationBar() {
        // `V02-006`: the OpenStreetMap credit sits behind the SDK's attribution control, so the
        // control must be tappable. Before this task the full-bleed MapView drew the logo and the
        // control under the system navigation bar, and a tap on the control landed on Back.
        // Measured from the views' screen bounds against the window's navigation-bar inset.
        dismissDisclosureIfShown()
        val mapView = awaitLiveMapView("entry map never laid out") { view ->
            view.isLaidOut && view.height > 0
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val navigationBottom = requireNotNull(mapView.rootWindowInsets)
                .getInsets(WindowInsets.Type.navigationBars())
                .bottom
            val decor = composeRule.activity.window.decorView
            val decorLocation = IntArray(2).also(decor::getLocationOnScreen)
            val limit = decorLocation[1] + decor.height - navigationBottom
            // MapLibre 13 builds these two ImageViews programmatically, without ids: the
            // attribution control carries the SDK's localized content description, the logo
            // is the only other ImageView child that is not the compass and has none.
            val attributionDescription = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getString(org.maplibre.android.R.string.maplibre_attributionsIconContentDescription)
            val imageChildren = (0 until mapView.childCount)
                .map(mapView::getChildAt)
                .filterIsInstance<android.widget.ImageView>()
                .filter { child -> child !is org.maplibre.android.maps.widgets.CompassView }
            val attributionControl = imageChildren.firstOrNull { child ->
                child.contentDescription == attributionDescription
            }
            val logo = imageChildren.firstOrNull { child ->
                child !== attributionControl && child.contentDescription.isNullOrEmpty()
            }
            for ((name, candidate) in listOf(
                "attribution control" to attributionControl,
                "logo" to logo,
            )) {
                val control = requireNotNull(candidate) {
                    "$name view missing among ${imageChildren.size} image children"
                }
                val location = IntArray(2).also(control::getLocationOnScreen)
                val bottom = location[1] + control.height
                assertTrue(
                    "$name bottom $bottom is not above the navigation bar (limit $limit," +
                        " inset $navigationBottom, height ${control.height})",
                    control.height > 0 && bottom <= limit,
                )
            }
        }
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
                    createdAppVersion = "maplibre-stage9-a11y",
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

    /**
     * Resolves the live MapView on every poll rather than holding the first one seen: a host can
     * replace its MapView during startup and a stale reference never moves (stage-9 fixture
     * lesson). Polls through runOnIdle so the rule's frame clock advances between reads.
     */
    private fun awaitLiveMapView(failure: String, ready: (MapView) -> Boolean): MapView {
        var found: MapView? = null
        var seen: List<MapView> = emptyList()
        val satisfied = awaitUntil(45_000L) {
            composeRule.runOnIdle {
                seen = composeRule.activity.window.decorView.attachedMapViews()
                found = seen.firstOrNull(ready)
            }
            found != null
        }
        assertTrue(
            "$failure: mapViews=" + seen.withIndex().joinToString { (index, view) ->
                "#$index" + describe(view)
            },
            satisfied,
        )
        return requireNotNull(found)
    }

    /** Tags, flags and sizes only; nothing positional. */
    private fun describe(view: MapView): String =
        "[generation=${view.getTag(R.id.map_fog_canonical_generation)}" +
            " load=${view.getTag(R.id.map_basemap_load_state)}" +
            " slot=${view.getTag(R.id.map_fog_active_slot)}" +
            " attached=${view.isAttachedToWindow} laidOut=${view.isLaidOut}" +
            " size=${view.width}x${view.height}]"

    private fun View.attachedMapViews(): List<MapView> {
        if (this is MapView) return if (isAttachedToWindow) listOf(this) else emptyList()
        if (this !is ViewGroup) return emptyList()
        return (0 until childCount).flatMap { index -> getChildAt(index).attachedMapViews() }
    }

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(200L)
        }
        return condition()
    }

    private companion object {
        const val NAVIGATION_TIMEOUT_MILLIS = 30_000L
        const val FIXTURE_STARTED_AT = 9_013_400_000L
        val TRACK_POINTS = listOf(
            GeoPoint(latitude = 25.0330, longitude = 121.5654),
            GeoPoint(latitude = 25.0334, longitude = 121.5660),
        )
    }
}
