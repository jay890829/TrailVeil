package app.trailveil.map

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.MapView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-008` criterion 3, the device half: what a granted legacy renderer does to the real app.
 *
 * `V02-005` stage 3 measured that the image class decides the renderer and the requested value is
 * advisory - `google_apis` grants `LEGACY` whatever is asked, `google_apis_playstore` grants
 * `LATEST` - and stage 9 then measured that renderer on six images without a single green run:
 * out-of-memory inside the renderer, a null bitmap from the SDK, timing failures. So the gate
 * refuses it, and this is the case that watches the refusal happen for real, with nothing stubbed:
 * the production `GoogleMapWarmup` observes the grant at process start, the production
 * `TrailVeilMapSurface` consults it, and the production `MainActivity` is what gets launched.
 *
 * It is an `assumeTrue` on the grant rather than a pin, deliberately. Pinning the renderer through
 * `GoogleRendererPin` would prove that the gate reacts to a value the test itself supplied; only an
 * image that really grants `LEGACY` proves the whole path. On a `google_apis_playstore` image both
 * cases skip and say so, which is the honest answer there rather than a fabricated one.
 *
 * Run it as: an emulator on a `google_apis` system image, `googlePoc` with a key configured.
 * A keyless build stops at `MISSING_KEY` before the renderer is ever consulted, so that arm skips
 * too - the ordering under test in `ProviderRuntimeGateTest` is the same ordering being respected
 * here.
 */
@RunWith(AndroidJUnit4::class)
class GoogleLegacyRendererGateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val createdSessionIds = ArrayList<Long>()

    @After
    fun tearDown() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        runBlocking {
            createdSessionIds.forEach { sessionId ->
                application.appContainer.databaseForTesting().recordingDao().deleteSession(sessionId)
            }
        }
        createdSessionIds.clear()
    }

    @Test
    fun theRendererGrantIsObservedAtProcessStartRatherThanAssumed() {
        val granted = awaitRendererGrant()
        assertTrue(
            "the warmup never observed a renderer grant; the three-argument " +
                "MapsInitializer.initialize callback did not run",
            granted != ProviderRenderer.UNREPORTED,
        )
        assumeTrue(
            "this image granted $granted; the legacy arm needs a google_apis image",
            granted == ProviderRenderer.LEGACY,
        )
        assertEquals(ProviderRenderer.LEGACY, grantedGoogleRenderer)
    }

    @Test
    fun aLegacyGrantClosesTheMapAndLeavesRecordingHonest() {
        assumeTrue(
            "this image granted ${awaitRendererGrant()}; the legacy arm needs a google_apis image",
            awaitRendererGrant() == ProviderRenderer.LEGACY,
        )
        assumeTrue(
            "a keyless build stops at MISSING_KEY before the renderer is consulted",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        dismissDisclosureIfShown()

        // Nothing unproven is exposed because nothing is composed that could expose it: the
        // terminal surface is styleless and builds no provider view at all, so this is a
        // structural claim rather than a pixel one.
        composeRule.onNodeWithTag(MapSurfaceTestTags.ProviderUnavailable).assertIsDisplayed()
        assertNull(
            "a legacy grant still constructed a Google MapView",
            composeRule.runOnIdle { findMapView(composeRule.activity.window.decorView) },
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithText(context.getString(R.string.map_provider_unavailable_legacy_renderer))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.map_provider_unavailable_keyless_build))
            .assertIsDisplayed()

        // Recording is the half that must keep working while the map cannot. A completed point
        // written to Room has to reach the recording presentation exactly as it does when the map
        // is up; the map slot showing a terminal surface must not be able to stall that path.
        val pointId = appendCompletedPoint(GeoPoint(25.0330, 121.5654), timestamp = 1_000L)
        assertTrue(
            "the recording route never observed the accepted point while the map was refused: " +
                "expected=$pointId actual=" +
                composeRule.runOnIdle {
                    composeRule.activity.window.decorView
                        .getTag(R.id.recording_presentation_latest_point_id)
                },
            awaitUntil(15_000L) {
                composeRule.activity.window.decorView
                    .getTag(R.id.recording_presentation_latest_point_id) == pointId
            },
        )
        composeRule.onNodeWithTag(RecordingEntryTestTags.MapExploration).assertIsDisplayed()
    }

    /**
     * The grant is a process fact latched on the first `MapsInitializer` call, which the warmup
     * Initializer makes from `ContentProvider.onCreate`. Its callback lands on the main looper,
     * possibly after this test's first instruction, so it is waited for rather than read once.
     */
    private fun awaitRendererGrant(): ProviderRenderer {
        awaitUntil(30_000L) { grantedGoogleRenderer != ProviderRenderer.UNREPORTED }
        return grantedGoogleRenderer
    }

    private fun appendCompletedPoint(point: GeoPoint, timestamp: Long): Long {
        val dao = (
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .applicationContext as TrailVeilApplication
            ).appContainer.databaseForTesting().recordingDao()
        val started = runBlocking {
            dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = timestamp,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "google-legacy-renderer-test",
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
        return runBlocking {
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

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50L)
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
}
