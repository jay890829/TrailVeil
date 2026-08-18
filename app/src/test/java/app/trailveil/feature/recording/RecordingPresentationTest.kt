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
        val presentation = null.toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)

        assertEquals(RecordingDisplayState.IDLE, presentation.state)
        assertNull(presentation.activeSessionId)
        assertNull(presentation.latestSessionId)
        assertNull(presentation.latestEndedAt)
        assertNull(presentation.latestAcceptedPoint)
    }

    @Test
    fun terminalOutcomesStillCarryTheirSessionIdentity() {
        val presentation = detail(RecordingHistoryStatus.COMPLETED)
            .toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)

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
        ).toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)
        val accepted = detail(
            status = RecordingHistoryStatus.ACTIVE,
            outcome = "LOCATION_ACCEPTED_CONTINUOUS_NONE",
        ).toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)

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
            detail.toRecordingPresentation(stoppingSessionId = 7L, runtimeToken = THIS_RUNTIME).state,
        )
        assertEquals(
            RecordingDisplayState.RECORDING,
            detail.toRecordingPresentation(stoppingSessionId = 8L, runtimeToken = THIS_RUNTIME).state,
        )
    }

    @Test
    fun durableTerminalStatesMapWithoutServiceMemory() {
        assertEquals(
            RecordingDisplayState.COMPLETED,
            detail(RecordingHistoryStatus.COMPLETED)
                .toRecordingPresentation(stoppingSessionId = 7L, runtimeToken = THIS_RUNTIME)
                .state,
        )
        assertEquals(
            RecordingDisplayState.INTERRUPTED,
            detail(RecordingHistoryStatus.INTERRUPTED)
                .toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)
                .state,
        )
        assertEquals(
            RecordingDisplayState.FAILED_TO_START,
            detail(RecordingHistoryStatus.FAILED_TO_START)
                .toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)
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

    @Test
    fun anActiveRowOwnedByARuntimeThatIsGoneIsNotReportedAsRecording() {
        // Measured on a POCO F7 Ultra: kill the process mid-session and this OEM never restarts the
        // foreground service, so the ACTIVE row outlives the runtime that claimed it. The screen used
        // to read that row and say "recording" while nothing was subscribed to location at all.
        val orphaned = detail(
            status = RecordingHistoryStatus.ACTIVE,
            ownerToken = "runtime-of-a-process-that-died",
        ).toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)

        assertEquals(RecordingDisplayState.ABANDONED, orphaned.state)
        // The same row owned by this process is the ordinary live case and must be unaffected.
        assertEquals(
            RecordingDisplayState.RECORDING,
            detail(status = RecordingHistoryStatus.ACTIVE)
                .toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)
                .state,
        )
    }

    @Test
    fun ownershipIsDecidedBeforeAnythingThatDescribesALiveRuntime() {
        val orphaned = detail(
            status = RecordingHistoryStatus.ACTIVE,
            outcome = "LOCATION_REJECTED_ACCURACY",
            ownerToken = "runtime-of-a-process-that-died",
        )

        // Poor signal and stopping both describe a runtime that is still there. Neither may outrank
        // the fact that no runtime owns this row.
        assertEquals(
            RecordingDisplayState.ABANDONED,
            orphaned.toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME).state,
        )
        assertEquals(
            RecordingDisplayState.ABANDONED,
            orphaned.toRecordingPresentation(stoppingSessionId = 7L, runtimeToken = THIS_RUNTIME).state,
        )
    }

    @Test
    fun anActiveRowWithNoOwnerAtAllIsAbandonedRatherThanTrusted() {
        // The database invariants keep an ACTIVE row's token non-null, and a migrated row carries a
        // sentinel. Whatever produced it, an unowned row is not a live recording, and the mapping
        // must fail closed rather than treat "no token" as "mine".
        assertEquals(
            RecordingDisplayState.ABANDONED,
            detail(status = RecordingHistoryStatus.ACTIVE, ownerToken = null)
                .toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)
                .state,
        )
    }

    @Test
    fun anAbandonedExplorationWaitsToBeReadInsteadOfExpiring() {
        // It is not a persisted terminal status, so it has no ended-at to expire against; if it were
        // treated as transient the one message telling the user they stopped being recorded could
        // disappear before they saw it.
        assertTrue(
            terminalNoticeVisible(
                state = RecordingDisplayState.ABANDONED,
                sessionId = 7L,
                endedAt = null,
                nowMillis = TRANSIENT_NOTICE_WINDOW_MILLIS * 100L,
                acknowledgedSessionId = null,
            ),
        )
    }

    @Test
    fun anAbandonedExplorationIsOfferedToRecoveryExactlyOncePerProcess() {
        assertEquals(
            7L,
            abandonedSessionToResume(
                state = RecordingDisplayState.ABANDONED,
                activeSessionId = 7L,
                attemptedSessionId = null,
                startupReconciled = true,
                activityResumed = true,
            ),
        )
        // Already offered. A blocked attempt leaves the state abandoned, and retrying on every
        // recomposition would turn one refusal into a loop of them.
        assertNull(
            abandonedSessionToResume(
                state = RecordingDisplayState.ABANDONED,
                activeSessionId = 7L,
                attemptedSessionId = 7L,
                startupReconciled = true,
                activityResumed = true,
            ),
        )
        // A different exploration is a different question.
        assertEquals(
            8L,
            abandonedSessionToResume(
                state = RecordingDisplayState.ABANDONED,
                activeSessionId = 8L,
                attemptedSessionId = 7L,
                startupReconciled = true,
                activityResumed = true,
            ),
        )
    }

    @Test
    fun nothingIsResumedWhileTheScreenCannotStartOrHasNotRepairedStartup() {
        assertNull(
            abandonedSessionToResume(
                state = RecordingDisplayState.ABANDONED,
                activeSessionId = 7L,
                attemptedSessionId = null,
                startupReconciled = false,
                activityResumed = true,
            ),
        )
        assertNull(
            abandonedSessionToResume(
                state = RecordingDisplayState.ABANDONED,
                activeSessionId = 7L,
                attemptedSessionId = null,
                startupReconciled = true,
                activityResumed = false,
            ),
        )
    }

    @Test
    fun onlyAnAbandonedExplorationIsEverResumed() {
        RecordingDisplayState.entries
            .filter { it != RecordingDisplayState.ABANDONED }
            .forEach { state ->
                assertNull(
                    "$state must not trigger a recovery attempt",
                    abandonedSessionToResume(
                        state = state,
                        activeSessionId = 7L,
                        attemptedSessionId = null,
                        startupReconciled = true,
                        activityResumed = true,
                    ),
                )
            }
    }

    @Test
    fun anAbandonedExplorationOffersStartRatherThanStopSoTheUserCanAskAgain() {
        // The row is still ACTIVE, so identity alone would offer Stop for a runtime that no longer
        // exists — and since the automatic re-arm is offered once per process, a user whose attempt
        // was blocked, who then fixes the permission and returns, would have had no way to retry.
        assertFalse(
            stopControlOffered(
                state = RecordingDisplayState.ABANDONED,
                activeSessionId = 7L,
            ),
        )
        assertTrue(
            stopControlOffered(
                state = RecordingDisplayState.RECORDING,
                activeSessionId = 7L,
            ),
        )
        assertFalse(
            stopControlOffered(
                state = RecordingDisplayState.IDLE,
                activeSessionId = null,
            ),
        )
    }

    private fun detail(
        status: RecordingHistoryStatus,
        outcome: String = "START_ACTIVATED",
        ownerToken: String? = THIS_RUNTIME,
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
        locationOwnerToken = ownerToken,
    )

    private companion object {
        const val THIS_RUNTIME = "runtime-of-the-process-under-test"
    }
}
