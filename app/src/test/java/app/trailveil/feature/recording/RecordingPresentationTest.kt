package app.trailveil.feature.recording

import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.data.history.RecordingHistoryOperationOutcome
import app.trailveil.data.history.RecordingHistorySession
import app.trailveil.data.history.RecordingHistoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingPresentationTest {
    @Test
    fun missingHistoryIsIdleWithoutAnActiveSession() {
        val presentation = null.toRecordingPresentation(stoppingSessionId = null)

        assertEquals(RecordingDisplayState.IDLE, presentation.state)
        assertNull(presentation.activeSessionId)
        assertNull(presentation.latestAcceptedPoint)
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

    private fun detail(
        status: RecordingHistoryStatus,
        outcome: String = "START_ACTIVATED",
    ) = RecordingHistoryDetail(
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
        segments = emptyList(),
        latestOperationOutcome = RecordingHistoryOperationOutcome(outcome),
        latestAcceptedPoint = RecordingHistoryAcceptedPoint(
            id = 11L,
            timestamp = 1_500L,
            latitude = 25.0330,
            longitude = 121.5654,
        ),
    )
}
