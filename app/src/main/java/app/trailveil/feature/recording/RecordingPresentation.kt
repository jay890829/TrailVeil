package app.trailveil.feature.recording

import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.data.history.RecordingHistoryStatus

internal data class RecordingPresentation(
    val state: RecordingDisplayState,
    val activeSessionId: Long?,
    val latestSessionId: Long?,
    val latestEndedAt: Long?,
    val latestAcceptedPoint: RecordingHistoryAcceptedPoint?,
)

internal val TerminalRecordingStates = setOf(
    RecordingDisplayState.COMPLETED,
    RecordingDisplayState.INTERRUPTED,
    RecordingDisplayState.FAILED_TO_START,
)

/**
 * How long a courtesy message stays on screen, measured from the moment the thing it reports
 * actually happened rather than from the moment a card appeared. The anchor matters: this screen is
 * rebuilt from scratch every time the user comes back from history, and a window measured from
 * composition would start over each time.
 *
 * A completion is also announced by a notification, so the card is only the in-app echo of it and
 * does not have to survive long enough to be caught.
 */
internal const val TRANSIENT_NOTICE_WINDOW_MILLIS = 5_000L

/**
 * Acknowledgements of something the user just did. They are courtesies, so they expire; every other
 * start notice reports a failure and waits to be read.
 */
internal val ExpiringStartNotices = setOf(
    RecordingStartNotice.STARTED,
    RecordingStartNotice.STOP_REQUESTED,
)

internal fun RecordingHistoryDetail?.toRecordingPresentation(
    stoppingSessionId: Long?,
): RecordingPresentation {
    if (this == null) {
        return RecordingPresentation(
            state = RecordingDisplayState.IDLE,
            activeSessionId = null,
            latestSessionId = null,
            latestEndedAt = null,
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
        // Unlike `activeSessionId`, this identifies the newest session whatever its status, which
        // is what lets an acknowledgement be bound to the one outcome it was made for.
        latestSessionId = session.id,
        latestEndedAt = session.endedAt,
        latestAcceptedPoint = latestAcceptedPoint,
    )
}

/**
 * Whether a terminal outcome is still news.
 *
 * The underlying source is the newest persisted session, so a terminal status stays true until an
 * entirely new exploration exists — days, if the user does not record again. That makes a terminal
 * card a claim about time as much as about state, and this is where that claim is made.
 */
internal fun terminalNoticeVisible(
    state: RecordingDisplayState,
    sessionId: Long?,
    endedAt: Long?,
    nowMillis: Long,
    acknowledgedSessionId: Long?,
): Boolean {
    if (state !in TerminalRecordingStates) return false
    // Nothing announces an outcome it cannot name. The route publishes an unidentified state while
    // the newest session is being read, and an acknowledgement is bound to an identity, so an
    // outcome without one could neither be trusted nor dismissed.
    if (sessionId == null) return false
    if (sessionId == acknowledgedSessionId) return false
    return when (state) {
        RecordingDisplayState.COMPLETED ->
            endedAt != null && nowMillis - endedAt < TRANSIENT_NOTICE_WINDOW_MILLIS
        // A failed or interrupted exploration is the outcome a user may still need to act on, so it
        // waits to be read instead of expiring on its own.
        else -> true
    }
}

/**
 * Whether a start notice is still worth showing.
 *
 * `raisedAt` is when the user's action produced the notice, not when it was last drawn, so leaving
 * this screen and coming back does not buy the notice another window.
 */
internal fun startNoticeVisible(
    notice: RecordingStartNotice?,
    raisedAt: Long?,
    nowMillis: Long,
    dismissedNotice: RecordingStartNotice?,
): Boolean {
    if (notice == null) return false
    if (notice == dismissedNotice) return false
    if (notice !in ExpiringStartNotices) return true
    // An acknowledgement with no timestamp cannot be timed, and a courtesy that cannot expire is
    // the thing being removed here, so it does not get shown at all.
    return raisedAt != null && nowMillis - raisedAt < TRANSIENT_NOTICE_WINDOW_MILLIS
}

private const val LOCATION_REJECTED_PREFIX = "LOCATION_REJECTED_"
