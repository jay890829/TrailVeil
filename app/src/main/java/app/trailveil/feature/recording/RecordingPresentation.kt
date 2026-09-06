package app.trailveil.feature.recording

import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingLatestSessionSummary
import app.trailveil.data.history.RecordingHistoryStatus
import app.trailveil.recording.RecordingResumeOutcome

internal data class RecordingPresentation(
    val state: RecordingDisplayState,
    val activeSessionId: Long?,
    /**
     * When the open exploration began, in wall-clock millis, so a terminal row can be dated from
     * it when the exploration recorded no point at all. Null exactly when [activeSessionId] is
     * null. `V02-015` removed its other use: it no longer decides anything, because a start time
     * compared against a clock-derived boot instant is a clock test, not a reboot test.
     */
    val activeSessionStartedAt: Long?,
    /**
     * When the open exploration itself last recorded a point, so a terminal row can be dated from
     * when recording actually stopped. Null when it recorded none — and deliberately not
     * [latestAcceptedPoint], which is the newest point across every session.
     */
    val activeSessionLastPointAt: Long?,
    /**
     * Which boot the open exploration was started in, so a reboot can be told from a process death
     * without asking what time it is. Null when unknown, which is not the same as "this boot".
     */
    val activeSessionBootId: Long?,
    val latestSessionId: Long?,
    val latestEndedAt: Long?,
    val latestAcceptedPoint: RecordingHistoryAcceptedPoint?,
)

internal val TerminalRecordingStates = setOf(
    RecordingDisplayState.COMPLETED,
    RecordingDisplayState.INTERRUPTED,
    RecordingDisplayState.FAILED_TO_START,
    // Not durably terminal — the row is still ACTIVE — but terminal to the user, who is no longer
    // being recorded and needs to be told so rather than have it expire unread. `V02-014` adds the
    // second row of that kind: the user has been told, and the terminal write is what is missing.
    RecordingDisplayState.ABANDONED,
    RecordingDisplayState.INTERRUPTED_UNSAVED,
)

/**
 * The two states whose durable row is still `ACTIVE` while nothing is recording it.
 *
 * Both are contradictions between the database and reality, and both are ended by writing the
 * terminal row, so [abandonedExplorationAction] treats them alike. They differ only in which half
 * is missing: [RecordingDisplayState.ABANDONED] lost the runtime, and
 * [RecordingDisplayState.INTERRUPTED_UNSAVED] lost the write.
 */
private val RepairableActiveStates = setOf(
    RecordingDisplayState.ABANDONED,
    RecordingDisplayState.INTERRUPTED_UNSAVED,
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
 * @param announcedInterruption whether this runtime has already told the user that the given
 *   exploration was interrupted. Required for the same reason, and it is the only thing that can
 *   answer the question: after a failed terminal write the row still says `ACTIVE` and the token is
 *   still this process's, so every durable signal agrees that recording is live while the user has
 *   a notification in hand saying it ended (`V02-014`).
 */
internal fun RecordingLatestSessionSummary?.toRecordingPresentation(
    stoppingSessionId: Long?,
    runtimeToken: String,
    announcedInterruption: (Long) -> Boolean,
): RecordingPresentation {
    if (this == null) {
        return RecordingPresentation(
            state = RecordingDisplayState.IDLE,
            activeSessionId = null,
            activeSessionStartedAt = null,
            activeSessionLastPointAt = null,
            activeSessionBootId = null,
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
        // `V02-014`. The same contradiction reaches this arm: `handleStart`'s catch-all interrupts a
        // session whose start could not be completed, and if that write fails too the row is left
        // `STARTING` while the user has been told the exploration ended. Startup reconciliation runs
        // once per process and will not come back for it, so without this the screen says
        // "Preparing a durable exploration record…" for the life of the process. The terminal
        // transaction closes a `STARTING` row perfectly well - it writes `INTERRUPT_DURING_START:` -
        // so the repair below needs nothing new.
        RecordingHistoryStatus.STARTING -> when {
            announcedInterruption(session.id) -> RecordingDisplayState.INTERRUPTED_UNSAVED
            else -> RecordingDisplayState.STARTING
        }
        RecordingHistoryStatus.ACTIVE -> when {
            // Ownership is asked first because it decides whether anything is recording at all. A
            // row this process does not own is not stopping and has no signal quality to report —
            // both of those would describe a runtime that no longer exists.
            locationOwnerToken != runtimeToken -> RecordingDisplayState.ABANDONED
            // `V02-014`. Asked before both of the branches below, and the reason is not that they
            // describe a live collector - `stoppingSessionId` is set after a stop request commits,
            // so it describes one that is on its way out. It is that this runtime has already told
            // the user the exploration ENDED. Nothing after that may describe it as still going,
            // and every durable signal here says it is: the row is `ACTIVE` and the token is ours,
            // and they are the ones that are wrong.
            announcedInterruption(session.id) -> RecordingDisplayState.INTERRUPTED_UNSAVED
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
        activeSessionBootId = activeSessionId?.let { sessionBootId },
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
        /**
         * Why recording stopped, or null when nothing in this process knows.
         *
         * `V02-014`. Null is the reboot case and keeps the reason the repair already wrote; a value
         * is what the runtime remembered when it announced the interruption, and it reaches the
         * history screen, so a wrong one here is a wrong one there. Not defaulted, for the reason
         * the parameter above is not: a construction that omitted it would compile and quietly
         * relabel a storage failure as a reboot, which is the defect rather than a shortcut.
         */
        val reason: String?,
    ) : AbandonedExplorationAction
}

/**
 * When an unfinished exploration stopped recording, for a terminal row dated after the fact.
 *
 * Its own last accepted point, or its start when it accepted none, and null only when even the start
 * is unknown. Written once and shared, because it is used from two places now - the automatic repair
 * and the Stop control - and `V02-007`'s ninth check found this exact rule unbound when it lived
 * inline at a single call site: deleting the fallback compiled and left every test green while a
 * zero-point exploration went back to being dated from its discovery.
 */
internal fun stoppedRecordingInstant(
    activeSessionLastPointAt: Long?,
    activeSessionStartedAt: Long?,
): Long? = activeSessionLastPointAt ?: activeSessionStartedAt

/** Whether the device restarted under an exploration, when that can be established at all. */
internal enum class BootContinuity {
    /** Same boot: the runtime died and the row outlived it, so continuing it is honest. */
    SAME_BOOT,

    /** Different boot: the device restarted under it, and `PLAN.md` forbids resuming across that. */
    RESTARTED,

    /** Neither is established. The app must ask rather than pick one. */
    UNKNOWN,
}

/**
 * Did the device restart under this exploration?
 *
 * `V02-015` replaced a clock comparison with this. The old test computed the boot instant as wall
 * clock minus uptime and asked whether the session started before it, within a five-second
 * tolerance. Both halves were wrong in the same way: the computed instant moves whenever the wall
 * clock is corrected - a time sync after a reboot does exactly that, and so does the user - and a
 * correction larger than the tolerance makes a pre-reboot session look like a post-boot one. The
 * consequence was not cosmetic: the app then re-armed location collection on an exploration nobody
 * asked to continue, which `PLAN.md` forbids in as many words.
 *
 * A boot counter is not a clock. It increases by one per boot, it cannot be set, and equality
 * answers the question directly rather than approximately, so no tolerance is needed and no
 * correction can move it.
 *
 * **[UNKNOWN] is a real answer and is not folded into either other one.** A row from before the
 * column existed has no identity, and a platform may refuse to report one. Treating that as
 * [SAME_BOOT] resumes someone who did not ask; treating it as [RESTARTED] ends explorations that a
 * process death should have continued, silently, for everyone on such a device. So it is returned
 * as itself, and the caller declines to act rather than guessing.
 */
internal fun bootContinuity(
    sessionBootId: Long?,
    currentBootId: Long?,
): BootContinuity = when {
    sessionBootId == null || currentBootId == null -> BootContinuity.UNKNOWN
    sessionBootId == currentBootId -> BootContinuity.SAME_BOOT
    else -> BootContinuity.RESTARTED
}

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
 *
 * **`V02-014`: an announced interruption whose write failed is repaired here too.** That row is
 * still `ACTIVE` and still owned by this process, so it never presents as abandoned; what it needs
 * is exactly what an abandoned row needs, the terminal write, and the retry contract is already
 * right - [runClaimedAbandonedAction] releases the claim when the write returns false, so the next
 * time this decision is reached the attempt is available again. It is reached again when the
 * activity resumes or the screen is rebuilt, which is when a storage failure has plausibly cleared.
 *
 * **`P4-048`: an announcement outranks the boot comparison.** If this runtime has already told the
 * user that this exploration was interrupted, it ends - resuming it would make the notification a
 * lie, which is what the product owner decided on 2026-08-21 after `P5-001` row 6. This is NOT
 * "stop resuming": a process killed and restarted by the system announces nothing, so
 * [announcedInThisRuntime] is false there and the `P4-041` recovery is untouched. The announcement
 * is the whole difference, and it is asked of this decision rather than checked beside it for the
 * same reason [claim] is.
 */
internal fun abandonedExplorationAction(
    state: RecordingDisplayState,
    activeSessionId: Long?,
    activeSessionStartedAt: Long?,
    activeSessionLastPointAt: Long?,
    activeSessionBootId: Long?,
    currentBootId: Long?,
    startupReconciled: Boolean,
    activityResumed: Boolean,
    claim: (Long) -> Boolean,
    announcedInterruptionReason: (Long) -> String?,
): AbandonedExplorationAction? {
    if (state !in RepairableActiveStates) return null
    val sessionId = activeSessionId ?: return null
    // Startup repair owns any still-STARTING row; acting across it would race that decision.
    if (!startupReconciled) return null
    // A start is only permitted from a visible activity, so asking earlier would spend the one
    // attempt on a refusal that says nothing about whether recovery was possible.
    if (!activityResumed) return null
    // P4-048. Answered before the boot question rather than folded into it, because the two say
    // different things: the boot question asks whether resuming COULD be right, and this asks
    // whether the user has already been told it will not happen. An announcement wins either way.
    // One lookup answers both halves: a reason exists exactly when this runtime announced, so the
    // fact and the reason cannot drift apart the way two parameters could. What matters is which
    // answer WINS - [bootContinuity] is a pure comparison, so evaluating it below asks the platform
    // nothing and observes nothing.
    val announcedReason = announcedInterruptionReason(sessionId)
    val interrupt = AbandonedExplorationAction.Interrupt(
        sessionId = sessionId,
        stoppedRecordingAt = stoppedRecordingInstant(
            activeSessionLastPointAt = activeSessionLastPointAt,
            activeSessionStartedAt = activeSessionStartedAt,
        ),
        reason = announcedReason,
    )
    val continuity = bootContinuity(
        sessionBootId = activeSessionBootId,
        currentBootId = currentBootId,
    )
    val action: AbandonedExplorationAction? = if (announcedReason != null) {
        interrupt
    } else {
        when (continuity) {
            BootContinuity.RESTARTED -> interrupt
            BootContinuity.SAME_BOOT -> AbandonedExplorationAction.Resume(sessionId)
            // `V02-015`: neither established, so neither is done. Nothing is resumed and nothing
            // is ended behind the user's back; the row stays as it is and the screen goes on
            // offering both controls, which is what asking looks like here.
            BootContinuity.UNKNOWN -> null
        }
    }
    // The claim is spent only once there is an action to spend it on, and deliberately not before.
    // It bounds ATTEMPTS - one per process - and the unknown branch makes none, so burning it there
    // would leave nothing to retry with when this runtime later announces an interruption for the
    // same row and the branch above becomes reachable. That is the `V02-014` repair, and an
    // exploration with no recorded boot is exactly the row most likely to need it.
    if (action == null || !claim(sessionId)) return null
    return action
}

/**
 * Whether this device has earned the background-start guidance.
 *
 * Earned by one event only: the app itself re-armed an exploration the platform had left abandoned
 * (`Resume`, and the service start was actually requested). On a platform that restarts the sticky
 * service, an ordinary kill never reaches this state - recovery happens in seconds under the live
 * token and the row never presents as abandoned. Stock Android can still reach it three ways: a
 * force-stop from app info cancels the sticky restart on every build, replacing the APK mid-recording
 * leaves no restart (this app declares no receiver, so no MY_PACKAGE_REPLACED path), and opening the
 * app in the seconds between a kill and the platform's restart wins a race. The card's wording
 * carries those cases - it hedges with 部分裝置 and offers an exit for devices without the option -
 * so the trigger is strong evidence, not proof, about this device. `Interrupt` must never earn it: no platform restarts a service across a reboot, so naming
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
