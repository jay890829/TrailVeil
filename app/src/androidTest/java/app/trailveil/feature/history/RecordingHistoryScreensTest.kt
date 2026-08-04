package app.trailveil.feature.history

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingHistoryAcceptedPointSegment
import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.data.history.RecordingHistorySegment
import app.trailveil.data.history.RecordingHistorySession
import app.trailveil.data.history.RecordingHistoryStatus
import app.trailveil.ui.theme.TrailVeilTheme
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

@RunWith(AndroidJUnit4::class)
class RecordingHistoryScreensTest {
    @Test
    fun loadingListDoesNotClaimHistoryIsEmpty() {
        composeRule.setContent {
            TrailVeilTheme {
                RecordingHistoryListScreen(
                    sessions = emptyList(),
                    loading = true,
                    onOpenSession = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordingHistoryTestTags.Loading).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Empty).assertDoesNotExist()
    }

    @Test
    fun loadingDetailDoesNotClaimSessionIsMissing() {
        composeRule.setContent {
            TrailVeilTheme {
                RecordingHistoryDetailScreen(
                    detail = null,
                    loading = true,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordingHistoryTestTags.Loading).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.DetailMissing).assertDoesNotExist()
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyListHasAHelpfulExplicitState() {
        composeRule.setContent {
            TrailVeilTheme {
                RecordingHistoryListScreen(sessions = emptyList(), onOpenSession = {})
            }
        }

        composeRule.onNodeWithTag(RecordingHistoryTestTags.Empty).assertIsDisplayed()
        composeRule.onNodeWithText("No saved explorations yet").assertIsDisplayed()
    }

    @Test
    fun listUsesProvidedNewestFirstSessionsAndOpensSelectedId() {
        val openedId = AtomicLong(-1L)
        val newest = session(id = 30, startedAt = 3_000L, status = RecordingHistoryStatus.COMPLETED)
        val older = session(id = 20, startedAt = 2_000L, status = RecordingHistoryStatus.ACTIVE)

        composeRule.setContent {
            TrailVeilTheme {
                RecordingHistoryListScreen(
                    sessions = listOf(newest, older),
                    onOpenSession = openedId::set,
                    nowMillis = 4_000L,
                )
            }
        }

        composeRule.onNodeWithTag(RecordingHistoryTestTags.List).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(newest.id))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(older.id)).assertIsDisplayed()
        assertEquals(newest.id, openedId.get())
    }

    @Test
    fun detailShowsInterruptedOutcomeCountsReasonsAndOrderedSegments() {
        val detail = RecordingHistoryDetail(
            session = session(
                id = 42,
                startedAt = 0L,
                endedAt = 125_000L,
                status = RecordingHistoryStatus.INTERRUPTED,
                stopReason = "INTERRUPT:GPS_DISABLED",
                distanceMeters = 1_250.0,
                acceptedPointCount = 7,
                rejectedPointCount = 3,
            ),
            segments = listOf(
                segment(id = 1, sequence = 0, startedAt = 0L, endedAt = 60_000L),
                segment(id = 2, sequence = 1, startedAt = 60_000L, endedAt = 125_000L),
            ),
            latestOperationOutcome = null,
            latestAcceptedPoint = null,
        )
        val backCalls = AtomicLong()

        composeRule.setContent {
            TrailVeilTheme {
                RecordingHistoryDetailScreen(
                    detail = detail,
                    onBack = { backCalls.incrementAndGet() },
                    nowMillis = 125_000L,
                )
            }
        }

        composeRule.onNodeWithTag(RecordingHistoryTestTags.Detail).assertIsDisplayed()
        composeRule.onNodeWithText("Interrupted").assertIsDisplayed()
        composeRule.onNodeWithText("Recording ended unexpectedly; saved points remain available.")
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "2m 05s · 1.25 km · 7 accepted points · 3 rejected points",
        )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Stop reason: INTERRUPT:GPS_DISABLED").assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.segment(0)).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.segment(1)).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performClick()
        assertEquals(1L, backCalls.get())
    }

    /**
     * The detail page scrolls, so the embedded map has to claim its own drags or panning the track
     * scrolls the page instead. MapLibre asks the host to stop intercepting touches only once while
     * the map initialises, and Compose view interop drops that request when a gesture ends, so the
     * first drag can succeed on its own; the assertion needs a later drag to be meaningful.
     */
    @Test
    fun draggingTheTrackMapPansItWhileTheRestOfTheDetailPageStillScrolls() {
        composeRule.setContent {
            TrailVeilTheme {
                RecordingHistoryDetailScreen(
                    detail = trackedDetail(segmentCount = 8),
                    onBack = {},
                    nowMillis = 600_000L,
                )
            }
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).assertIsDisplayed()
        composeRule.waitForIdle()

        val beforeMapDrags = mapTop()
        repeat(2) {
            composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap)
                .performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        val afterMapDrags = mapTop()

        composeRule.onNodeWithTag(RecordingHistoryTestTags.Detail).performTouchInput {
            // Below the map, so this drag never enters the surface that owns map gestures.
            swipeUp(startY = bottom - 4f, endY = bottom - 304f)
        }
        composeRule.waitForIdle()
        val afterPageDrag = mapTop()

        assertEquals(
            "Dragging the track map scrolled the detail page instead of panning the map",
            beforeMapDrags,
            afterMapDrags,
            SCROLL_TOLERANCE_DP,
        )
        assertTrue(
            "Dragging outside the track map no longer scrolls the detail page",
            abs(afterPageDrag - afterMapDrags) > SCROLL_TOLERANCE_DP,
        )
    }

    /**
     * MapLibre's default drawing surface is its own compositor layer, which keeps presenting
     * regardless of what the composition around it does — it stayed painted over the history list
     * for as long as the popped screen's view remained attached. A windowed render view is drawn
     * and removed with everything else on the screen it belongs to.
     */
    @Test
    fun theTrackMapDrawsIntoTheWindowSoItCannotOutliveItsScreen() {
        composeRule.setContent {
            TrailVeilTheme {
                RecordingHistoryDetailScreen(
                    detail = trackedDetail(segmentCount = 2),
                    onBack = {},
                    nowMillis = 600_000L,
                )
            }
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).assertIsDisplayed()

        val renderView = awaitRenderView()
        assertTrue(
            "Track map render view was ${renderView.javaClass.name}, not a windowed one",
            renderView is TextureView,
        )
    }

    private fun awaitRenderView(): View {
        repeat(RENDER_VIEW_POLLS) {
            // The activity registry refuses to answer off the main thread, which is also the only
            // thread allowed to read the view tree it hands back.
            composeRule.runOnIdle { attachedMapView()?.renderView }?.let { return it }
            Thread.sleep(RENDER_VIEW_POLL_MILLIS)
        }
        error("The track map never attached a render view")
    }

    private fun attachedMapView(): MapView? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstNotNullOfOrNull { activity -> activity.window.decorView.findMapView() }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }

    private fun mapTop(): Double = composeRule
        .onNodeWithTag(RecordingHistoryTestTags.TrackMap)
        .getUnclippedBoundsInRoot()
        .top
        .value
        .toDouble()

    private fun trackedDetail(segmentCount: Int) = RecordingHistoryDetail(
        session = session(
            id = 7,
            startedAt = 0L,
            endedAt = 600_000L,
            status = RecordingHistoryStatus.COMPLETED,
            acceptedPointCount = segmentCount.toLong() * 2L,
        ),
        segments = List(segmentCount) { index ->
            segment(
                id = index + 1L,
                sequence = index.toLong(),
                startedAt = index * 60_000L,
                endedAt = (index + 1) * 60_000L,
            )
        },
        latestOperationOutcome = null,
        latestAcceptedPoint = null,
        acceptedPointSegments = List(segmentCount) { index ->
            RecordingHistoryAcceptedPointSegment(
                segmentId = index + 1L,
                segmentSequence = index.toLong(),
                points = List(2) { offset ->
                    RecordingHistoryAcceptedPoint(
                        id = index * 2L + offset + 1L,
                        timestamp = index * 60_000L + offset * 1_000L,
                        latitude = 25.0330 + index * 0.0004 + offset * 0.0002,
                        longitude = 121.5654 + index * 0.0006 + offset * 0.0003,
                        sequence = index * 2L + offset,
                    )
                },
            )
        },
    )

    private fun session(
        id: Long,
        startedAt: Long,
        endedAt: Long? = startedAt + 60_000L,
        status: RecordingHistoryStatus,
        stopReason: String? = null,
        distanceMeters: Double = 100.0,
        acceptedPointCount: Long = 2L,
        rejectedPointCount: Long = 0L,
    ) = RecordingHistorySession(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        status = status,
        stopReason = stopReason,
        distanceMeters = distanceMeters,
        acceptedPointCount = acceptedPointCount,
        rejectedPointCount = rejectedPointCount,
    )

    private fun segment(
        id: Long,
        sequence: Long,
        startedAt: Long,
        endedAt: Long,
    ) = RecordingHistorySegment(
        id = id,
        sequence = sequence,
        startedAt = startedAt,
        endedAt = endedAt,
        startReason = "SESSION_START",
        endReason = "GPS_DISABLED",
    )

    private companion object {
        const val SCROLL_TOLERANCE_DP = 1.0
        const val RENDER_VIEW_POLLS = 50
        const val RENDER_VIEW_POLL_MILLIS = 100L
    }
}
