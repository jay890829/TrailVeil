package app.trailveil.feature.recording

import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingLatestSessionSummary
import app.trailveil.data.history.RecordingHistoryStatus
import app.trailveil.recording.RecordingResumeOutcome

internal data class RecordingPresentation(
    val state: RecordingDisplayState,
    val activeSessionId: Long?,
    /**
     * When the open exploration began, in wall-clock millis, so a caller can tell whether it
     * predates the running boot. Null exactly when [activeSessionId] is null.
     */
    val activeSessionStartedAt: Long?,
    /**
     * When the open exploration itself last recorded a point, so a terminal row can be dated from
     * when recording actually stopped. Null when it recorded none — and deliberately not
     * [latestAcceptedPoint], which is the newest point across every session.
     */
    val activeSessionLastPointAt: Long?,
    val latestSessionId: Long?,
    val latestEndedAt: Long?,
    val latestAcceptedPoint: RecordingHistoryAcceptedPoint?,
)

internal val TerminalRecordingStates = setOf(
    RecordingDisplayState.COMPLETED,
    RecordingDisplayState.INTERRUPTED,
    RecordingDisplayState.FAILED_TO_START,
    // Not durably terminal — the row is still ACTIVE — but terminal to the user, who is no longer
    // being recorded and needs to be told so rather than have it expire unread.
    RecordingDisplayState.ABANDONED,
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
internal const val TRANSIENT_NOTICE_WINDOW_MILLIS = 3_000L

/**
 * Acknowledgements of something the user just did. They are courtesies, so they expire; every other
 * start notice reports a failure and waits to be read.
 */
internal val ExpiringStartNotices = setOf(
    RecordingStartNotice.STARTED,
    RecordingStartNotice.STOP_REQUESTED,
)

/**
 * @param runtimeToken this process's durable ownership token. It is required rather than defaulted
 *   because a caller that omitted it would be claiming a recording is live without checking, which
 *   is the exact defect this parameter exists to prevent.
 */
internal fun RecordingLatestSessionSummary?.toRecordingPresentation(
    stoppingSessionId: Long?,
    runtimeToken: String,
): RecordingPresentation {
    if (this == null) {
        return RecordingPresentation(
            state = RecordingDisplayState.IDLE,
            activeSessionId = null,
            activeSessionStartedAt = null,
            activeSessionLastPointAt = null,
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
            // Ownership is asked first because it decides whether anything is recording at all. A
            // row this process does not own is not stopping and has no signal quality to report —
            // both of those would describe a runtime that no longer exists.
            locationOwnerToken != runtimeToken -> RecordingDisplayState.ABANDONED
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
        activeSessionStartedAt = activeSessionId?.let { session.startedAt },
        activeSessionLastPointAt = activeSessionId?.let { sessionLastAcceptedPointAt },
        // Unlike `activeSessionId`, this identifies the newest session whatever its status, which
        // is what lets an acknowledgement be bound to the one outcome it was made for.
        latestSessionId = session.id,
        latestEndedAt = session.endedAt,
        latestAcceptedPoint = latestAcceptedPoint,
    )
}

/**
 * Whether the screen offers to end the open exploration.
 *
 * An abandoned row is still `ACTIVE` and still stoppable — the in-app Stop starts the service purely
 * to terminalize it — so it must stay reachable. It is the only way to end that row once the
 * foreground notification has died with the process that posted it.
 */
internal fun stopControlOffered(
    state: RecordingDisplayState,
    activeSessionId: Long?,
): Boolean = activeSessionId != null

/**
 * Whether the screen offers to begin an exploration, or to continue an abandoned one.
 *
 * Abandoned is the one state where both controls belong. Nothing is recording, so Start is what
 * continues it — against a row that is still `ACTIVE` it reacquires ownership through the same
 * recovery transaction — and the automatic re-arm is offered only once per process, so without this
 * a user whose re-arm was blocked, who then grants the permission and comes back, would have no way
 * to ask again. Offering only one of the two controls strands them either way: first this screen
 * offered Stop for a runtime that did not exist, then it offered no way to end the row at all.
 */
internal fun startControlOffered(
    state: RecordingDisplayState,
    activeSessionId: Long?,
): Boolean = activeSessionId == null || state == RecordingDisplayState.ABANDONED

/** What this process should do about an exploration it found abandoned. */
internal sealed interface AbandonedExplorationAction {
    val sessionId: Long

    /** Re-arm it: the row outlived a process death inside one boot, so continuing it is honest. */
    data class Resume(override val sessionId: Long) : AbandonedExplorationAction

    /**
     * End it as interrupted: the device restarted under it, and PLAN forbids resuming across that.
     *
     * Carries its own terminal instant so the route forwards a decision instead of computing one —
     * the session's last recorded point, or the session's start when it recorded none, and null only
     * when even the start is unknown. A ninth check found the previous shape (an inline `?:` in the
     * route's effect) bound by nothing once the device fixture began seeding a point: deleting the
     * fallback compiled and left every test green, while a zero-point abandoned session — start
     * pressed, no fix accepted, reboot — went back to being dated from its discovery.
     */
    data class Interrupt(
        override val sessionId: Long,
        val stoppedRecordingAt: Long?,
    ) : AbandonedExplorationAction
}

/**
 * When the running boot began, in wall-clock millis.
 *
 * Wall clock minus uptime. Both reads come from the same clock source a moment apart, so the result
 * is stable to within the cost of the two calls; it moves when the wall clock is corrected, which is
 * why callers compare against it with [BOOT_BOUNDARY_TOLERANCE_MILLIS] rather than exactly.
 */
internal fun bootInstantEpochMillis(
    epochMillis: Long,
    elapsedRealtimeNanos: Long,
): Long = epochMillis - elapsedRealtimeNanos / 1_000_000L

/**
 * How far either side of the computed boot instant a session's start time is treated as ambiguous.
 *
 * The comparison decides whether to collect location without being asked, so the tolerance is spent
 * on the side that does not: a session inside this window of the boot instant is interrupted rather
 * than resumed. The cost of being wrong that way is that the user starts a new exploration instead
 * of continuing one that is at most this old; the cost of being wrong the other way is silently
 * recording someone who did not ask, which is what `PLAN.md` forbids.
 */
internal const val BOOT_BOUNDARY_TOLERANCE_MILLIS = 5_000L

/**
 * What to do about an abandoned exploration, or null for "leave it alone".
 *
 * The platform normally restarts a killed foreground service, and the service recovers the session
 * itself; measured on a POCO F7 Ultra, some OEM builds never do that unless the user has granted a
 * background-start permission that is off by default. Re-arming is a second trigger for the recovery
 * that already exists — the ordinary start path reaches it, because a start against a row that is
 * already `ACTIVE` reacquires ownership through the durable recovery transaction rather than
 * creating a session — and not a second way of recovering.
 *
 * **A restart is not a process death, and only one of them may be resumed.** `PLAN.md` requires that
 * the app not silently resume location after the device reboots, and that an improperly ended session
 * be marked interrupted on the next open. Nothing else in the tree enforces that: the durable row
 * survives a reboot untouched, startup reconciliation only reaches a still-`STARTING` row, and the
 * runtime token is regenerated per process, so without this branch a reboot is indistinguishable from
 * a process death and the first open after one would re-arm collection on a session of any age.
 *
 * [claim] is taken rather than consulted by the caller so that "once per session per process" is part
 * of this decision instead of a line beside it — the guard has twice been correct in isolation while
 * the wiring that reaches it was bound by nothing.
 */
internal fun abandonedExplorationAction(
    state: RecordingDisplayState,
    activeSessionId: Long?,
    activeSessionStartedAt: Long?,
    activeSessionLastPointAt: Long?,
    bootedAtEpochMillis: Long,
    startupReconciled: Boolean,
    activityResumed: Boolean,
    claim: (Long) -> Boolean,
): AbandonedExplorationAction? {
    if (state != RecordingDisplayState.ABANDONED) return null
    val sessionId = activeSessionId ?: return null
    // Startup repair owns any still-STARTING row; acting across it would race that decision.
    if (!startupReconciled) return null
    // A start is only permitted from a visible activity, so asking earlier would spend the one
    // attempt on a refusal that says nothing about whether recovery was possible.
    if (!activityResumed) return null
    if (!claim(sessionId)) return null
    // An unknown start time cannot be shown to postdate the boot, so it takes the safe branch.
    val predatesThisBoot = activeSessionStartedAt == null ||
        activeSessionStartedAt < bootedAtEpochMillis + BOOT_BOUNDARY_TOLERANCE_MILLIS
    return if (predatesThisBoot) {
        AbandonedExplorationAction.Interrupt(
            sessionId = sessionId,
            stoppedRecordingAt = activeSessionLastPointAt ?: activeSessionStartedAt,
        )
    } else {
        AbandonedExplorationAction.Resume(sessionId)
    }
}

/**
 * Whether this device has earned the background-start guidance.
 *
 * Earned by one event only: the app itself re-armed an exploration the platform had left abandoned
 * (`Resume`, and the service start was actually requested). On a platform that restarts the sticky
 * service this state is unreachable - recovery happens in seconds under the live token and the row
 * never presents as abandoned - so reaching it is evidence about THIS device, not a guess about
 * vendors. `Interrupt` must never earn it: no platform restarts a service across a reboot, so naming
 * a background-start setting there would be a lie about the device. A `Blocked` resume is explained
 * by its blocker, which raises its own notice the user must act on first.
 */
internal fun backgroundStartNoticeEarned(
    action: AbandonedExplorationAction?,
    resumeOutcome: RecordingResumeOutcome?,
): Boolean = action is AbandonedExplorationAction.Resume &&
    resumeOutcome is RecordingResumeOutcome.ServiceRequested

/**
 * Whether an earned card may be on screen right now.
 *
 * A location notice can be raised after the card was earned - a permission revoked while it is up -
 * and both point at the same settings button, so only the actionable one may show.
 */
internal fun backgroundStartNoticeVisible(
    earned: Boolean,
    locationNotice: LocationNotice?,
): Boolean = earned && locationNotice == null

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
