package app.trailveil.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.data.history.RecordingHistorySegment
import app.trailveil.data.history.RecordingHistorySession
import app.trailveil.data.history.RecordingHistoryStatus
import app.trailveil.ui.theme.TrailVeilTheme
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
}
