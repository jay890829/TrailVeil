package app.trailveil.feature.recording

import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.data.history.RecordingHistoryStatus

internal data class RecordingPresentation(
    val state: RecordingDisplayState,
    val activeSessionId: Long?,
    val latestAcceptedPoint: RecordingHistoryAcceptedPoint?,
)

internal fun RecordingHistoryDetail?.toRecordingPresentation(
    stoppingSessionId: Long?,
): RecordingPresentation {
    if (this == null) {
        return RecordingPresentation(
            state = RecordingDisplayState.IDLE,
            activeSessionId = null,
            latestAcceptedPoint = null,
        )
    }
    val session = session
    val activeSessionId = session.id.takeIf {
        session.status == RecordingHistoryStatus.STARTING ||
            session.status == RecordingHistoryStatus.ACTIVE
    }
    val state = when (session.status) {
        RecordingHistoryStatus.STARTING -> RecordingDisplayState.STARTING
        RecordingHistoryStatus.ACTIVE -> when {
            stoppingSessionId == session.id -> RecordingDisplayState.STOPPING
            latestOperationOutcome?.value?.startsWith(LOCATION_REJECTED_PREFIX) == true ->
                RecordingDisplayState.POOR_SIGNAL
            else -> RecordingDisplayState.RECORDING
        }
        RecordingHistoryStatus.COMPLETED -> RecordingDisplayState.COMPLETED
        RecordingHistoryStatus.INTERRUPTED -> RecordingDisplayState.INTERRUPTED
        RecordingHistoryStatus.FAILED_TO_START -> RecordingDisplayState.FAILED_TO_START
    }
    return RecordingPresentation(
        state = state,
        activeSessionId = activeSessionId,
        latestAcceptedPoint = latestAcceptedPoint,
    )
}

private const val LOCATION_REJECTED_PREFIX = "LOCATION_REJECTED_"
