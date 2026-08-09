package app.trailveil.feature.recording

import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingLatestSessionSummary
import app.trailveil.data.history.RecordingHistoryOperationOutcome
import app.trailveil.data.history.RecordingHistorySession
import app.trailveil.data.history.RecordingHistoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPresentationTest {
    @Test
    fun missingHistoryIsIdleWithoutAnActiveSession() {
        val presentation = null.toRecordingPresentation(stoppingSessionId = null)

        assertEquals(RecordingDisplayState.IDLE, presentation.state)
        assertNull(presentation.activeSessionId)
        assertNull(presentation.latestSessionId)
        assertNull(presentation.latestEndedAt)
        assertNull(presentation.latestAcceptedPoint)
    }

    @Test
    fun terminalOutcomesStillCarryTheirSessionIdentity() {
        val presentation = detail(RecordingHistoryStatus.COMPLETED)
            .toRecordingPresentation(stoppingSessionId = null)

        // `activeSessionId` is deliberately null once a session ends, so identity for an
        // acknowledgement has to come from somewhere that survives the ending.
        assertNull(presentation.activeSessionId)
        assertEquals(7L, presentation.latestSessionId)
        assertEquals(2_000L, presentation.latestEndedAt)
    }

    @Test
    fun activeLatestRejectedOutcomeIsPoorSignalUntilAnAcceptedOutcomeArrives() {
        val rejected = detail(
            status = RecordingHistoryStatus.ACTIVE,
            outcome = "LOCATION_REJECTED_ACCURACY",
        ).toRecordingPresentation(stoppingSessionId = null)
        val accepted = detail(
            status = RecordingHistoryStatus.ACTIVE,
            outcome = "LOCATION_ACCEPTED_CONTINUOUS_NONE",
        ).toRecordingPresentation(stoppingSessionId = null)

        assertEquals(RecordingDisplayState.POOR_SIGNAL, rejected.state)
        assertEquals(RecordingDisplayState.RECORDING, accepted.state)
        assertEquals(7L, accepted.activeSessionId)
        assertEquals(11L, accepted.latestAcceptedPoint?.id)
    }

    @Test
    fun stoppingRequestOnlyAppliesToTheMatchingPersistedActiveSession() {
        val detail = detail(status = RecordingHistoryStatus.ACTIVE)

        assertEquals(
            RecordingDisplayState.STOPPING,
            detail.toRecordingPresentation(stoppingSessionId = 7L).state,
        )
        assertEquals(
            RecordingDisplayState.RECORDING,
            detail.toRecordingPresentation(stoppingSessionId = 8L).state,
        )
    }

    @Test
    fun durableTerminalStatesMapWithoutServiceMemory() {
        assertEquals(
            RecordingDisplayState.COMPLETED,
            detail(RecordingHistoryStatus.COMPLETED)
                .toRecordingPresentation(stoppingSessionId = 7L)
                .state,
        )
        assertEquals(
            RecordingDisplayState.INTERRUPTED,
            detail(RecordingHistoryStatus.INTERRUPTED)
                .toRecordingPresentation(stoppingSessionId = null)
                .state,
        )
        assertEquals(
            RecordingDisplayState.FAILED_TO_START,
            detail(RecordingHistoryStatus.FAILED_TO_START)
                .toRecordingPresentation(stoppingSessionId = null)
                .state,
        )
    }

    @Test
    fun anOutcomeWithoutIdentityIsNeverAnnounced() {
        // The route publishes IDLE with no identity for as long as the newest session is being
        // read, which happens on every return from the history screen. IDLE is not terminal, so
        // that frame is already silent — but nothing may announce an outcome it cannot name, and
        // the two states that otherwise never expire are where that would actually show.
        listOf(
            RecordingDisplayState.INTERRUPTED,
            RecordingDisplayState.FAILED_TO_START,
            RecordingDisplayState.COMPLETED,
            RecordingDisplayState.IDLE,
        ).forEach { state ->
            assertFalse(
                terminalNoticeVisible(
                    state = state,
                    sessionId = null,
                    endedAt = 2_000L,
                    nowMillis = 2_100L,
                    acknowledgedSessionId = 7L,
                ),
            )
        }
    }

    @Test
    fun anAcknowledgementIsBoundToOneExploration() {
        assertFalse(
            terminalNoticeVisible(
                state = RecordingDisplayState.COMPLETED,
                sessionId = 7L,
                endedAt = 2_000L,
                nowMillis = 2_100L,
                acknowledgedSessionId = 7L,
            ),
        )
        // The same outcome for a later exploration is new information, not a repeat.
        assertTrue(
            terminalNoticeVisible(
                state = RecordingDisplayState.COMPLETED,
                sessionId = 8L,
                endedAt = 2_000L,
                nowMillis = 2_100L,
                acknowledgedSessionId = 7L,
            ),
        )
    }

    @Test
    fun aCompletionExpiresOnItsPersistedEndTime() {
        val endedAt = 2_000L
        assertTrue(
            terminalNoticeVisible(
                state = RecordingDisplayState.COMPLETED,
                sessionId = 7L,
                endedAt = endedAt,
                nowMillis = endedAt + TRANSIENT_NOTICE_WINDOW_MILLIS - 1L,
                acknowledgedSessionId = null,
            ),
        )
        assertFalse(
            terminalNoticeVisible(
                state = RecordingDisplayState.COMPLETED,
                sessionId = 7L,
                endedAt = endedAt,
                nowMillis = endedAt + TRANSIENT_NOTICE_WINDOW_MILLIS,
                acknowledgedSessionId = null,
            ),
        )
    }

    @Test
    fun failedOutcomesWaitToBeReadInsteadOfExpiring() {
        val endedAt = 2_000L
        val longAfter = endedAt + TRANSIENT_NOTICE_WINDOW_MILLIS * 10L
        listOf(
            RecordingDisplayState.INTERRUPTED,
            RecordingDisplayState.FAILED_TO_START,
        ).forEach { state ->
            assertTrue(
                terminalNoticeVisible(
                    state = state,
                    sessionId = 7L,
                    endedAt = endedAt,
                    nowMillis = longAfter,
                    acknowledgedSessionId = null,
                ),
            )
        }
    }

    @Test
    fun liveStatesAreNotGovernedByTheTerminalRule() {
        listOf(
            RecordingDisplayState.STARTING,
            RecordingDisplayState.RECORDING,
            RecordingDisplayState.POOR_SIGNAL,
            RecordingDisplayState.STOPPING,
        ).forEach { state ->
            assertFalse(
                terminalNoticeVisible(
                    state = state,
                    sessionId = 7L,
                    endedAt = null,
                    nowMillis = 2_000L,
                    acknowledgedSessionId = null,
                ),
            )
        }
    }

    @Test
    fun anAcknowledgementOfAUserActionExpiresOnItsOwnAge() {
        val raisedAt = 2_000L
        listOf(
            RecordingStartNotice.STARTED,
            RecordingStartNotice.STOP_REQUESTED,
        ).forEach { notice ->
            assertTrue(
                startNoticeVisible(
                    notice = notice,
                    raisedAt = raisedAt,
                    nowMillis = raisedAt + TRANSIENT_NOTICE_WINDOW_MILLIS - 1L,
                    dismissedNotice = null,
                ),
            )
            assertFalse(
                startNoticeVisible(
                    notice = notice,
                    raisedAt = raisedAt,
                    nowMillis = raisedAt + TRANSIENT_NOTICE_WINDOW_MILLIS,
                    dismissedNotice = null,
                ),
            )
        }
    }

    @Test
    fun startNoticesThatReportAFailureWaitToBeRead() {
        val raisedAt = 2_000L
        val longAfter = raisedAt + TRANSIENT_NOTICE_WINDOW_MILLIS * 100L
        listOf(
            RecordingStartNotice.PERSISTENCE_FAILURE,
            RecordingStartNotice.LAUNCH_FAILURE,
            RecordingStartNotice.ACTIVITY_NOT_VISIBLE,
        ).forEach { notice ->
            assertTrue(
                startNoticeVisible(
                    notice = notice,
                    raisedAt = raisedAt,
                    nowMillis = longAfter,
                    dismissedNotice = null,
                ),
            )
            assertFalse(
                startNoticeVisible(
                    notice = notice,
                    raisedAt = raisedAt,
                    nowMillis = longAfter,
                    dismissedNotice = notice,
                ),
            )
        }
    }

    @Test
    fun anUntimedAcknowledgementIsNotShownAtAll() {
        // Nothing should be able to reintroduce a courtesy message that never goes away.
        assertFalse(
            startNoticeVisible(
                notice = RecordingStartNotice.STARTED,
                raisedAt = null,
                nowMillis = 2_000L,
                dismissedNotice = null,
            ),
        )
    }

    private fun detail(
        status: RecordingHistoryStatus,
        outcome: String = "START_ACTIVATED",
    ) = RecordingLatestSessionSummary(
        session = RecordingHistorySession(
            id = 7L,
            startedAt = 1_000L,
            endedAt = if (
                status == RecordingHistoryStatus.STARTING ||
                status == RecordingHistoryStatus.ACTIVE
            ) {
                null
            } else {
                2_000L
            },
            status = status,
            stopReason = null,
            distanceMeters = 12.5,
            acceptedPointCount = 1L,
            rejectedPointCount = 0L,
        ),
        latestOperationOutcome = RecordingHistoryOperationOutcome(outcome),
        latestAcceptedPoint = RecordingHistoryAcceptedPoint(
            id = 11L,
            timestamp = 1_500L,
            latitude = 25.0330,
            longitude = 121.5654,
        ),
    )
}
