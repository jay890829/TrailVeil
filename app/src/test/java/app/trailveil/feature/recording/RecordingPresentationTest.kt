package app.trailveil.feature.recording

import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingLatestSessionSummary
import app.trailveil.data.history.RecordingHistoryOperationOutcome
import app.trailveil.data.history.RecordingHistorySession
import app.trailveil.data.history.RecordingHistoryStatus
import app.trailveil.recording.AbandonedResumeClaims
import app.trailveil.recording.RecordingResumeOutcome
import app.trailveil.recording.RecordingStartBlocker
import app.trailveil.recording.RecordingStartFailureKind
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
    fun anExplorationAbandonedInsideThisBootIsResumed() {
        assertEquals(
            AbandonedExplorationAction.Resume(7L),
            abandonedAction(startedAt = BOOTED_AT + AN_HOUR),
        )
    }

    @Test
    fun anExplorationTheDeviceRestartedUnderIsEndedRatherThanResumed() {
        // PLAN.md: 「裝置重開機後不靜默恢復定位；下次開啟時將未正常結束的 session 標示為中斷。」
        // Both clauses live here. Nothing else in the tree can enforce either: the durable row
        // survives a reboot untouched, startup reconciliation reaches only a still-STARTING row, and
        // the runtime token is regenerated per process — so a reboot is indistinguishable from a
        // process death by the time this decision is made, and without the start-time comparison the
        // first open after a restart re-arms location collection on a session of any age.
        assertEquals(
            AbandonedExplorationAction.Interrupt(7L, stoppedRecordingAt = BOOTED_AT - AN_HOUR),
            abandonedAction(startedAt = BOOTED_AT - AN_HOUR),
        )
    }

    @Test
    fun theBootBoundaryIsSpentOnNotRecordingSomeoneWhoDidNotAsk() {
        // Wall clock minus uptime moves when the clock is corrected, so the boundary is approximate.
        // Which way it errs is not: inside the tolerance the exploration is ended, because the cost
        // is that the user starts a new one, while the cost of erring the other way is collecting
        // location without being asked.
        assertEquals(
            AbandonedExplorationAction.Interrupt(
                7L,
                stoppedRecordingAt = BOOTED_AT + BOOT_BOUNDARY_TOLERANCE_MILLIS - 1L,
            ),
            abandonedAction(startedAt = BOOTED_AT + BOOT_BOUNDARY_TOLERANCE_MILLIS - 1L),
        )
        assertEquals(
            AbandonedExplorationAction.Resume(7L),
            abandonedAction(startedAt = BOOTED_AT + BOOT_BOUNDARY_TOLERANCE_MILLIS),
        )
    }

    @Test
    fun anExplorationWithNoKnownStartTimeIsNeverSilentlyResumed() {
        assertEquals(
            AbandonedExplorationAction.Interrupt(7L, stoppedRecordingAt = null),
            abandonedAction(startedAt = null),
        )
    }

    @Test
    fun theEndingIsDatedFromTheSessionsLastPointWhenItRecordedOne() {
        // The instant rides the action, so the route forwards a decision rather than computing one.
        // A ninth check found the previous inline `?:` in the route bound by nothing once the device
        // fixture began seeding a point: deleting the fallback left every test green while a
        // zero-point abandoned session went back to being dated from its discovery.
        assertEquals(
            AbandonedExplorationAction.Interrupt(
                7L,
                stoppedRecordingAt = BOOTED_AT - AN_HOUR + 900_000L,
            ),
            abandonedAction(
                startedAt = BOOTED_AT - AN_HOUR,
                lastPointAt = BOOTED_AT - AN_HOUR + 900_000L,
            ),
        )
    }

    @Test
    fun anEndingWithNoRecordedPointIsDatedFromTheSessionsOwnStart() {
        // Start pressed, no fix ever accepted, reboot: dating from anything later than the start
        // publishes time the exploration never covered.
        assertEquals(
            AbandonedExplorationAction.Interrupt(7L, stoppedRecordingAt = BOOTED_AT - AN_HOUR),
            abandonedAction(startedAt = BOOTED_AT - AN_HOUR, lastPointAt = null),
        )
    }

    @Test
    fun nothingHappensWhileTheScreenCannotStartOrHasNotRepairedStartup() {
        // Acting before startup repair races the decision it owns; acting before the activity is
        // resumed spends the offer on a refusal that says nothing about whether recovery was
        // possible.
        assertNull(abandonedAction(startupReconciled = false))
        assertNull(abandonedAction(activityResumed = false))
    }

    @Test
    fun onlyAnAbandonedExplorationIsEverActedOn() {
        RecordingDisplayState.entries
            .filter { it != RecordingDisplayState.ABANDONED }
            .forEach { state ->
                assertNull(
                    "$state must not trigger a recovery attempt",
                    abandonedAction(state = state),
                )
            }
    }

    @Test
    fun theDecisionIsMadeOncePerSessionPerProcessAndTheClaimIsPartOfIt() {
        // The claim is taken inside the decision rather than beside it at the call site. That seam
        // is the one this task has broken twice: the guard was correct in isolation both times,
        // while the wiring that reached it was bound by nothing, so deleting or relocating it broke
        // no test at all. Passing the real claims object through the real function is what makes
        // that impossible to do quietly.
        val claims = AbandonedResumeClaims()

        assertEquals(
            AbandonedExplorationAction.Resume(7L),
            abandonedAction(startedAt = BOOTED_AT + AN_HOUR, claim = claims::claim),
        )
        assertNull(abandonedAction(startedAt = BOOTED_AT + AN_HOUR, claim = claims::claim))
    }

    @Test
    fun aRefusedClaimStopsTheEndingTooAndNotOnlyTheResuming() {
        // Otherwise a failing interrupt retries on every recomposition for as long as the screen is
        // open, which is the loop the claim exists to prevent, just on the other branch.
        assertNull(abandonedAction(startedAt = BOOTED_AT - AN_HOUR, claim = { false }))
    }

    @Test
    fun theBootInstantIsWallClockMinusUptime() {
        assertEquals(
            1_000L,
            bootInstantEpochMillis(epochMillis = 61_000L, elapsedRealtimeNanos = 60_000_000_000L),
        )
    }

    @Test
    fun aResumedExplorationThePlatformDidNotRestartEarnsTheBackgroundStartGuidance() {
        // The one input combination that is the whole feature: the app itself re-armed an abandoned
        // exploration and the service start was actually requested. After an ordinary kill on a
        // restarting platform this pair does not occur; force-stop, APK replacement, and the
        // open-before-restart race can still produce it anywhere, which the card's wording carries.
        assertTrue(
            backgroundStartNoticeEarned(
                action = AbandonedExplorationAction.Resume(7L),
                resumeOutcome = RecordingResumeOutcome.ServiceRequested(7L),
            ),
        )
    }

    @Test
    fun anExplorationEndedForADeviceRestartIsNeverBlamedOnBackgroundStart() {
        // No platform restarts a service across a reboot, so naming the setting there is a lie
        // about the device.
        assertFalse(
            backgroundStartNoticeEarned(
                action = AbandonedExplorationAction.Interrupt(7L, stoppedRecordingAt = 1_000L),
                resumeOutcome = RecordingResumeOutcome.ServiceRequested(7L),
            ),
        )
        assertFalse(
            backgroundStartNoticeEarned(
                action = null,
                resumeOutcome = RecordingResumeOutcome.ServiceRequested(7L),
            ),
        )
    }

    @Test
    fun aResumeTheUserMustFixFirstSaysNothingAboutBackgroundStart() {
        // Exhaustive over the outcome type, so a new variant cannot slip in as earned silently. A
        // blocked resume is explained by its blocker, which raises its own notice pointing at the
        // same settings button; stacking a second card behind it strands the user twice.
        val resume = AbandonedExplorationAction.Resume(7L)
        RecordingStartBlocker.entries.forEach { blocker ->
            assertFalse(
                "$blocker must not earn the guidance",
                backgroundStartNoticeEarned(
                    action = resume,
                    resumeOutcome = RecordingResumeOutcome.Blocked(blocker),
                ),
            )
        }
        assertFalse(
            backgroundStartNoticeEarned(
                action = resume,
                resumeOutcome = RecordingResumeOutcome.LaunchFailure(
                    7L,
                    RecordingStartFailureKind.BACKGROUND_START_NOT_ALLOWED,
                ),
            ),
        )
        assertFalse(backgroundStartNoticeEarned(action = resume, resumeOutcome = null))
    }

    @Test
    fun theBackgroundStartGuidanceYieldsToAnActionableLocationNotice() {
        // Both cards point at the same settings button; only the one the user must act on may show.
        assertTrue(backgroundStartNoticeVisible(earned = true, locationNotice = null))
        LocationNotice.entries.forEach { notice ->
            assertFalse(
                "$notice must displace the guidance",
                backgroundStartNoticeVisible(earned = true, locationNotice = notice),
            )
        }
        assertFalse(backgroundStartNoticeVisible(earned = false, locationNotice = null))
    }

    private fun abandonedAction(
        state: RecordingDisplayState = RecordingDisplayState.ABANDONED,
        activeSessionId: Long? = 7L,
        startedAt: Long? = BOOTED_AT + AN_HOUR,
        lastPointAt: Long? = null,
        startupReconciled: Boolean = true,
        activityResumed: Boolean = true,
        claim: (Long) -> Boolean = { true },
    ): AbandonedExplorationAction? = abandonedExplorationAction(
        state = state,
        activeSessionId = activeSessionId,
        activeSessionStartedAt = startedAt,
        activeSessionLastPointAt = lastPointAt,
        bootedAtEpochMillis = BOOTED_AT,
        startupReconciled = startupReconciled,
        activityResumed = activityResumed,
        claim = claim,
    )

    @Test
    fun anAbandonedExplorationOffersBothContinuingItAndEndingIt() {
        // Offering only one of the two strands the user, and this screen has done it in both
        // directions: Stop for a runtime that no longer exists, then — once the re-arm was offered
        // once per process — no way to end the row at all when that one attempt was blocked.
        assertTrue(
            startControlOffered(state = RecordingDisplayState.ABANDONED, activeSessionId = 7L),
        )
        assertTrue(
            stopControlOffered(state = RecordingDisplayState.ABANDONED, activeSessionId = 7L),
        )
    }

    @Test
    fun theSessionsOwnLastPointRidesThePresentationAndOnlyForAnOpenSession() {
        // The terminal-dating anchor. It must be the session's own value - not the cross-session
        // latestAcceptedPoint, whose fixture timestamp here is deliberately different - and it must
        // vanish with the active session, because dating anything else with it would be a lie.
        val active = detail(RecordingHistoryStatus.ACTIVE)
            .toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)
        assertEquals(SESSION_LAST_POINT_AT, active.activeSessionLastPointAt)

        val completed = detail(RecordingHistoryStatus.COMPLETED)
            .toRecordingPresentation(stoppingSessionId = null, runtimeToken = THIS_RUNTIME)
        assertNull(completed.activeSessionLastPointAt)
    }

    @Test
    fun aLiveRecordingOffersOnlyEndingItAndAnIdleScreenOnlyBeginning() {
        assertTrue(
            stopControlOffered(state = RecordingDisplayState.RECORDING, activeSessionId = 7L),
        )
        assertFalse(
            startControlOffered(state = RecordingDisplayState.RECORDING, activeSessionId = 7L),
        )
        assertFalse(
            stopControlOffered(state = RecordingDisplayState.IDLE, activeSessionId = null),
        )
        assertTrue(
            startControlOffered(state = RecordingDisplayState.IDLE, activeSessionId = null),
        )
    }

    private fun detail(
        status: RecordingHistoryStatus,
        outcome: String = "START_ACTIVATED",
        ownerToken: String? = THIS_RUNTIME,
        sessionLastPointAt: Long? = SESSION_LAST_POINT_AT,
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
        sessionLastAcceptedPointAt = sessionLastPointAt,
    )

    private companion object {
        const val THIS_RUNTIME = "runtime-of-the-process-under-test"

        /** An arbitrary but plausible boot instant; only its distance from a start time matters. */
        const val BOOTED_AT = 1_700_000_000_000L
        const val AN_HOUR = 3_600_000L

        /**
         * Distinct from every other timestamp in the fixture, so a presentation that carried the
         * wrong field would be caught by value rather than by luck.
         */
        const val SESSION_LAST_POINT_AT = 1_700L
    }
}
