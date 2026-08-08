package app.trailveil.data.recording

import app.trailveil.data.location.AcceptedLocationKind
import app.trailveil.data.location.LocationQualityDecision
import app.trailveil.data.location.RawLocationFix
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRepositoryTest {
    @Test fun startFirstContinuousGapAfterBreakAndStopAreTransactional() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val session = repository.begin(id("begin"), 0).sessionId
        assertTrue(repository.completeStart(id("activate"), session, 1).activated)
        assertEquals(LocationDisposition.ACCEPTED, repository.deliver(id("first"), session, raw(0, 0.0), 0).disposition)
        assertEquals(LocationDisposition.ACCEPTED, repository.deliver(id("continuous"), session, raw(SECOND, 0.00001), SECOND).disposition)
        assertEquals(LocationDisposition.REJECTED, repository.deliver(id("gap-reject"), session, raw(70 * SECOND, 0.1, accuracy = 60.0), 70 * SECOND).disposition)
        assertEquals(LocationDisposition.ACCEPTED, repository.deliver(id("after-break"), session, raw(71 * SECOND, 0.00002), 71 * SECOND).disposition)
        assertTrue(repository.stop(id("stop"), session, 72, "user").stopped)

        assertEquals(3L, store.accepted)
        assertEquals(1L, store.rejected)
        assertEquals(2, store.segmentStarts.size)
        assertEquals(listOf(AcceptedLocationKind.FIRST, AcceptedLocationKind.CONTINUOUS, AcceptedLocationKind.AFTER_BREAK), store.acceptedKinds)
        assertEquals(0.0, store.acceptedDistances.first(), 0.0)
        assertEquals(0.0, store.acceptedDistances.last(), 0.0)
        assertTrue(store.distance > 0.0)
        assertTrue(store.closedReasons.contains("GAP"))
        assertFalse(store.receivedRawCoordinate)
    }

    @Test fun receiptsAreIdempotentAndOperationKindCollisionsFail() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val begin = repository.begin(id("one"), 0)
        assertEquals(begin, repository.begin(id("one"), 999))
        assertEquals(1, store.prepareCalls)
        assertTrue(repository.completeStart(id("two"), begin.sessionId, 1).activated)
        val first = repository.deliver(id("three"), begin.sessionId, raw(0, 0.0), 0)
        assertEquals(first, repository.deliver(id("three"), begin.sessionId, raw(1, 1.0), 1))
        assertEquals(1L, store.accepted)
        assertTrue(repository.stop(id("four"), begin.sessionId, 2, "user").stopped)
        assertFalse(repository.stop(id("five"), begin.sessionId, 3, "user").stopped)
        try { repository.stop(id("one"), begin.sessionId, 3, "user") ; throw AssertionError("expected collision") } catch (_: OperationIdCollisionException) { }
    }

    @Test fun technicalInterruptIsDurableIdempotentAndDistinctFromUserStop() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val session = repository.begin(id("begin"), 0).sessionId
        repository.completeStart(id("activate"), session, 1)

        val interrupted = repository.interrupt(id("interrupt"), session, 2, "LOCATION_DISABLED")
        assertTrue(interrupted.stopped)
        assertEquals(interrupted, repository.interrupt(id("interrupt"), session, 999, "replay"))
        assertEquals(listOf(RecordingTerminalStatus.INTERRUPTED), store.terminalStatuses)
        assertEquals(RecordingLifecycle.STOPPED, repository.state().lifecycle)

        try {
            repository.stop(id("interrupt"), session, 3, "user")
            throw AssertionError("expected operation kind collision")
        } catch (_: OperationIdCollisionException) { }

        val next = repository.begin(id("next-begin"), 4).sessionId
        repository.completeStart(id("next-activate"), next, 5)
        assertTrue(repository.stop(id("user-stop"), next, 6, "USER").stopped)
        assertEquals(
            listOf(RecordingTerminalStatus.INTERRUPTED, RecordingTerminalStatus.COMPLETED),
            store.terminalStatuses,
        )
    }

    @Test fun durableStopRequestDisablesDeliveryAndIsIdempotent() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val session = repository.begin(id("begin"), 0).sessionId
        repository.completeStart(id("activate"), session, 1)

        val requested = repository.requestStop(id("request-stop"), session, 2, "user")
        assertTrue(requested.requested)
        assertEquals(requested, repository.requestStop(id("request-stop"), session, 999, "other"))
        assertEquals(
            LocationDisposition.STALE_SESSION,
            repository.deliver(id("after-request"), session, raw(0, 0.0), 3).disposition,
        )
        assertTrue(repository.stop(id("stop"), session, 2, "user").stopped)
        assertEquals(RecordingLifecycle.STOPPED, repository.state().lifecycle)
    }

    @Test fun failedStopRequestKeepsDeliveryButPendingIntentWinsFreshRecovery() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val owner = RecordingRepository(store)
        val session = owner.begin(id("begin"), 0).sessionId
        owner.completeStart(id("activate"), session, 1)

        store.failNext = RecordingOperationKind.REQUEST_STOP
        try {
            owner.requestStop(id("request-fails"), session, 2, "user")
            throw AssertionError("expected request failure")
        } catch (_: IOException) { }
        assertEquals(
            LocationDisposition.ACCEPTED,
            owner.deliver(id("still-owned"), session, raw(0, 0.0), 2).disposition,
        )

        assertTrue(owner.requestStop(id("request"), session, 3, "user").requested)
        store.failNext = RecordingOperationKind.STOP
        try {
            owner.stop(id("stop-fails"), session, 3, "user")
            throw AssertionError("expected stop failure")
        } catch (_: IOException) { }

        val fresh = RecordingRepository(store)
        assertEquals(
            RecoveryDisposition.PENDING_STOP_COMPLETED,
            fresh.recover(id("recover"), 10).disposition,
        )
        assertEquals(RecordingLifecycle.STOPPED, fresh.state().lifecycle)
        assertEquals(0, store.recoveryRotations)
    }

    @Test fun storeFailuresRestoreFilterAndDoNotAcknowledgeOrAdvanceState() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val session = repository.begin(id("begin"), 0).sessionId
        store.failNext = RecordingOperationKind.COMPLETE_START
        try { repository.completeStart(id("activation-fails"), session, 1); throw AssertionError("expected failure") } catch (_: IOException) { }
        assertEquals(RecordingLifecycle.STARTING, repository.state().lifecycle)
        repository.completeStart(id("activation"), session, 1)

        store.failNext = RecordingOperationKind.LOCATION
        try { repository.deliver(id("first-fails"), session, raw(0, 0.0), 0); throw AssertionError("expected failure") } catch (_: IOException) { }
        // If the failed decision had advanced the filter, this retry would be duplicate/out-of-order.
        assertEquals(LocationDisposition.ACCEPTED, repository.deliver(id("first"), session, raw(0, 0.0), 0).disposition)

        store.failNext = RecordingOperationKind.LOCATION
        try { repository.deliver(id("gap-fails"), session, raw(70 * SECOND, 0.1, accuracy = 60.0), 70 * SECOND); throw AssertionError("expected failure") } catch (_: IOException) { }
        assertEquals(0, store.closedReasons.size)
        assertEquals(LocationDisposition.REJECTED, repository.deliver(id("gap"), session, raw(70 * SECOND, 0.1, accuracy = 60.0), 70 * SECOND).disposition)
        assertEquals(listOf("GAP"), store.closedReasons)

        store.failNext = RecordingOperationKind.STOP
        try { repository.stop(id("stop-fails"), session, 80, "user"); throw AssertionError("expected failure") } catch (_: IOException) { }
        assertEquals(RecordingLifecycle.ACTIVE, repository.state().lifecycle)
        assertTrue(repository.stop(id("stop"), session, 80, "user").stopped)
    }

    @Test fun recoveryAndStaleSessionAreGuardedAndSharedStoreIsAuthority() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val first = RecordingRepository(store)
        val session = first.begin(id("begin"), 0).sessionId
        first.completeStart(id("active"), session, 1)
        first.deliver(id("point"), session, raw(0, 0.0), 0)
        store.failNext = RecordingOperationKind.RECOVERY
        try { first.recover(id("recover-fails"), 10); throw AssertionError("expected failure") } catch (_: IOException) { }
        assertEquals(RecordingLifecycle.ACTIVE, first.state().lifecycle)
        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, first.recover(id("recover"), 10).disposition)
        assertEquals(LocationDisposition.STALE_SESSION, first.deliver(id("stale"), session + 1, raw(SECOND, 0.0), SECOND).disposition)

        val second = RecordingRepository(store)
        assertEquals(RecordingLifecycle.ACTIVE, second.state().lifecycle)
        assertEquals(LocationDisposition.STALE_SESSION, second.deliver(id("unrecovered-process"), session, raw(SECOND, 0.0), SECOND).disposition)
        assertEquals(StartDisposition.ALREADY_ACTIVE, second.begin(id("other-begin"), 20).disposition)
        assertEquals(1, store.recoveryRotations)
    }

    @Test fun startFailureAndStopDuringStartingPersistHonestTerminalState() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val failedSession = repository.begin(id("begin-failed"), 0).sessionId

        store.failNext = RecordingOperationKind.FAIL_START
        try {
            repository.failStart(id("fail-write"), failedSession, 1, "starter failed")
            throw AssertionError("expected failure")
        } catch (_: IOException) { }
        assertEquals(RecordingLifecycle.STARTING, repository.state().lifecycle)
        assertTrue(repository.failStart(id("fail"), failedSession, 1, "starter failed").failed)
        assertEquals(RecordingLifecycle.FAILED_TO_START, repository.state().lifecycle)

        val cancelledSession = repository.begin(id("begin-cancelled"), 2).sessionId
        assertTrue(repository.stop(id("cancel"), cancelledSession, 3, "cancelled during start").stopped)
        assertEquals(RecordingLifecycle.STOPPED, repository.state().lifecycle)
    }

    @Test fun activationAndFailureReceiptsAreIdempotent() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val session = repository.begin(id("begin"), 0).sessionId
        val activation = repository.completeStart(id("activate"), session, 1)

        assertEquals(activation, repository.completeStart(id("activate"), session, 999))
        assertFalse(repository.completeStart(id("activate-again"), session, 2).activated)
        assertFalse(repository.failStart(id("late-failure"), session, 3, "late").failed)
        assertEquals(1, store.segmentStarts.size)
    }

    @Test fun aNewSessionStartsWithAFreshZeroDistanceFilterAnchor() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val firstSession = repository.begin(id("begin-one"), 0).sessionId
        repository.completeStart(id("activate-one"), firstSession, 1)
        repository.deliver(id("point-one"), firstSession, raw(0, 0.0), 0)
        repository.deliver(id("point-two"), firstSession, raw(SECOND, 0.00001), SECOND)
        repository.stop(id("stop-one"), firstSession, 2, "user")

        val secondSession = repository.begin(id("begin-two"), 3).sessionId
        repository.completeStart(id("activate-two"), secondSession, 4)
        val result = repository.deliver(
            id("far-first"),
            secondSession,
            raw(2 * SECOND, 120.0),
            2 * SECOND,
        )

        assertEquals(LocationDisposition.ACCEPTED, result.disposition)
        assertEquals(AcceptedLocationKind.FIRST, store.acceptedKinds.last())
        assertEquals(0.0, store.acceptedDistances.last(), 0.0)
        assertEquals(1L, result.state.acceptedPointCount)
    }

    @Test fun recoveryRotatesOnceAndReplayRestoresDeliveryGate() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val original = RecordingRepository(store)
        val session = original.begin(id("begin"), 0).sessionId
        original.completeStart(id("activate"), session, 1)
        original.deliver(id("point"), session, raw(0, 0.0), 0)

        val recovered = RecordingRepository(store)
        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, recovered.recover(id("recover"), 10).disposition)
        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, recovered.recover(id("recover"), 999).disposition)
        assertEquals(
            LocationDisposition.ACCEPTED,
            recovered.deliver(id("post-recovery-first"), session, raw(SECOND, 30.0), SECOND).disposition,
        )

        val another = RecordingRepository(store)
        assertEquals(
            RecoveryDisposition.ACTIVE_ALREADY_RECOVERED,
            another.recover(id("recover-again"), 20).disposition,
        )
        val acceptedBeforeStaleOwners = store.accepted
        assertEquals(
            LocationDisposition.STALE_SESSION,
            another.deliver(id("non-owner"), session, raw(2 * SECOND, 30.00001), 2 * SECOND).disposition,
        )
        assertEquals(
            LocationDisposition.STALE_SESSION,
            original.deliver(id("old-owner"), session, raw(2 * SECOND, 0.00001), 2 * SECOND).disposition,
        )
        assertEquals(acceptedBeforeStaleOwners, store.accepted)
        assertEquals(1, store.recoveryRotations)
        assertEquals(2, store.segmentStarts.size)
    }
    @Test fun receiptReplayReturnsTheOriginalProjectionWithoutRewindingAuthority() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val begin = repository.begin(id("stable-begin"), 0)
        repository.completeStart(id("activate"), begin.sessionId, 1)
        repository.deliver(id("point"), begin.sessionId, raw(0, 0.0), 0)
        repository.stop(id("stop"), begin.sessionId, 2, "user")

        val replay = repository.begin(id("stable-begin"), 999)
        assertEquals(begin, replay)
        assertEquals(RecordingLifecycle.STARTING, replay.state.lifecycle)
        assertEquals(RecordingLifecycle.STOPPED, repository.state().lifecycle)
    }

    @Test fun aDurableLocalGuardCannotLaterMutateAfterRecovery() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val owner = RecordingRepository(store)
        val session = owner.begin(id("begin"), 0).sessionId
        owner.completeStart(id("activate"), session, 1)

        val fresh = RecordingRepository(store)
        fresh.state()
        val stale = fresh.deliver(id("stable-stale"), session, raw(0, 10.0), 0)
        assertEquals(LocationDisposition.STALE_SESSION, stale.disposition)
        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, fresh.recover(id("recover"), 10).disposition)
        val replay = fresh.deliver(id("stable-stale"), session, raw(SECOND, 10.1), SECOND)

        assertEquals(stale, replay)
        assertEquals(0L, store.accepted)
    }
    @Test fun aReceiptRaceReplayCannotDisableTheCurrentLocationOwner() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val session = repository.begin(id("begin"), 0).sessionId
        repository.completeStart(id("activate"), session, 1)
        store.injectConcurrentGuardReceipt(id("raced-guard"), session)

        assertEquals(
            LocationDisposition.STALE_SESSION,
            repository.deliver(id("raced-guard"), session, raw(0, 50.0), 0).disposition,
        )
        val accepted = repository.deliver(id("after-race"), session, raw(0, 50.0), 0)

        assertEquals(LocationDisposition.ACCEPTED, accepted.disposition)
        assertEquals(AcceptedLocationKind.FIRST, store.acceptedKinds.single())
        assertEquals(0.0, store.acceptedDistances.single(), 0.0)
    }

    @Test fun replayingAnOldStopCannotDisableANewActiveSession() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val repository = RecordingRepository(store)
        val oldSession = repository.begin(id("begin-old"), 0).sessionId
        repository.completeStart(id("activate-old"), oldSession, 1)
        val oldStop = repository.stop(id("stop-old"), oldSession, 2, "user")

        val currentSession = repository.begin(id("begin-current"), 3).sessionId
        repository.completeStart(id("activate-current"), currentSession, 4)
        assertEquals(oldStop, repository.stop(id("stop-old"), oldSession, 999, "replay"))

        assertEquals(
            LocationDisposition.ACCEPTED,
            repository.deliver(id("current-first"), currentSession, raw(0, 45.0), 0).disposition,
        )
        assertEquals(RecordingLifecycle.ACTIVE, repository.state().lifecycle)
    }
    @Test fun stateRefreshRevokesAnOwnerWhoseSegmentWasRotatedExternally() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val owner = RecordingRepository(store)
        val session = owner.begin(id("begin"), 0).sessionId
        owner.completeStart(id("activate"), session, 1)

        val replacement = RecordingRepository(store)
        assertEquals(
            RecoveryDisposition.ACTIVE_ROTATED,
            replacement.recover(id("replacement-recovery"), 10).disposition,
        )
        assertEquals(RecordingLifecycle.ACTIVE, owner.state().lifecycle)
        val acceptedBeforeOldOwner = store.accepted

        assertEquals(
            LocationDisposition.STALE_SESSION,
            owner.deliver(id("refreshed-old-owner"), session, raw(0, 12.0), 0).disposition,
        )
        assertEquals(acceptedBeforeOldOwner, store.accepted)
    }
    @Test fun aNewProcessRotatesAnExistingRecoverySegmentAndAcquiresOwnership() = runBlocking {
        val store = FakeTransactionalRecordingStore()
        val original = RecordingRepository(store)
        val session = original.begin(id("begin"), 0).sessionId
        original.completeStart(id("activate"), session, 1)
        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, original.recover(id("recover-one"), 10).disposition)

        val restarted = RecordingRepository(
            store = store,
            runtimeId = RecordingRuntimeId("new-process"),
        )
        assertEquals(
            RecoveryDisposition.ACTIVE_ROTATED,
            restarted.recover(id("recover-two"), 20).disposition,
        )
        assertEquals(
            LocationDisposition.ACCEPTED,
            restarted.deliver(id("new-process-first"), session, raw(0, 40.0), 0).disposition,
        )
        assertEquals(2, store.recoveryRotations)
        assertEquals(3, store.segmentStarts.size)
    }
    private suspend fun RecordingRepository.begin(
        operationId: RecordingOperationId,
        startedAtEpochMillis: Long,
    ) = beginStart(operationId, startedAtEpochMillis, createdAppVersion = "test")

    private suspend fun RecordingRepository.deliver(
        operationId: RecordingOperationId,
        sessionId: Long,
        rawFix: RawLocationFix,
        nowElapsedRealtimeNanos: Long,
    ) = deliverLocation(
        operationId,
        sessionId,
        rawFix,
        nowElapsedRealtimeNanos,
        recordedAtEpochMillis = nowElapsedRealtimeNanos / SECOND,
    )
    private fun id(value: String) = RecordingOperationId(value)
    private fun raw(time: Long, longitude: Double, accuracy: Double = 5.0) = RawLocationFix(0.0, longitude, accuracy, time, time / 1_000_000L)
    private companion object { const val SECOND = 1_000_000_000L }
}

/** Deterministic in-memory model of the atomic Room adapter contract, including durable receipts. */
private class FakeTransactionalRecordingStore : RecordingStore {
    private data class Session(
        var id: Long,
        var lifecycle: RecordingLifecycle,
        var openSegment: Long? = null,
        var openSegmentReason: String? = null,
        var locationOwnerToken: RecordingRuntimeId? = null,
        var pendingStopReason: String? = null,
    )
    private val receipts = linkedMapOf<RecordingOperationId, StoreReceipt>()
    private var missNextReceiptLookupFor: RecordingOperationId? = null
    private var session: Session? = null
    private var nextSession = 1L
    private var nextSegment = 1L
    var failNext: RecordingOperationKind? = null
    var prepareCalls = 0
    var accepted = 0L
    var rejected = 0L
    var distance = 0.0
    val segmentStarts = mutableListOf<String>()
    val acceptedKinds = mutableListOf<AcceptedLocationKind>()
    val acceptedDistances = mutableListOf<Double>()
    val closedReasons = mutableListOf<String>()
    val terminalStatuses = mutableListOf<RecordingTerminalStatus>()
    var recoveryRotations = 0
    var receivedRawCoordinate = false

    override suspend fun receiptFor(
        operationId: RecordingOperationId,
        expectedKind: RecordingOperationKind,
    ): StoreReceipt? {
        if (missNextReceiptLookupFor == operationId) {
            missNextReceiptLookupFor = null
            return null
        }
        return receipts[operationId]?.let { existing ->
            if (existing.kind != expectedKind) {
                throw OperationIdCollisionException(
                    "operation id reused for ${existing.kind}, not $expectedKind",
                )
            }
            existing.copy(replayed = true)
        }
    }

    fun injectConcurrentGuardReceipt(
        operationId: RecordingOperationId,
        requestedSessionId: Long,
    ) {
        receipts[operationId] = StoreReceipt(
            operationId = operationId,
            kind = RecordingOperationKind.LOCATION,
            outcome = StoreOutcome.SessionGuardRejected(requestedSessionId),
            projection = project(),
        )
        missNextReceiptLookupFor = operationId
    }

    override suspend fun projection(): RecordingProjection = project()

    override suspend fun prepareStart(transaction: PrepareStartTransaction): StoreReceipt = once(transaction.operationId, RecordingOperationKind.BEGIN_START) {
        prepareCalls++
        val current = session
        when (current?.lifecycle) {
            RecordingLifecycle.STARTING -> StoreOutcome.StartAlreadyPending(current.id)
            RecordingLifecycle.ACTIVE -> StoreOutcome.StartAlreadyActive(current.id)
            else -> {
                accepted = 0L
                rejected = 0L
                distance = 0.0
                StoreOutcome.StartPrepared(
                    Session(nextSession++, RecordingLifecycle.STARTING).also { session = it }.id,
                )
            }
        }
    }
    override suspend fun activateStart(transaction: ActivateStartTransaction): StoreReceipt = once(transaction.operationId, RecordingOperationKind.COMPLETE_START) {
        val current = session
        if (
            current?.id == transaction.sessionId &&
            current.lifecycle == RecordingLifecycle.STARTING &&
            current.pendingStopReason == null
        ) {
            current.lifecycle = RecordingLifecycle.ACTIVE
            current.openSegment = nextSegment++
            current.openSegmentReason = "INITIAL"
            current.locationOwnerToken = transaction.locationOwnerToken
            segmentStarts += "INITIAL"
            StoreOutcome.StartActivated(current.id)
        } else StoreOutcome.StartNotPending(transaction.sessionId, current?.lifecycle ?: RecordingLifecycle.STOPPED)
    }
    override suspend fun failStart(transaction: FailStartTransaction): StoreReceipt = once(transaction.operationId, RecordingOperationKind.FAIL_START) {
        val current = session
        if (current?.id == transaction.sessionId && current.lifecycle == RecordingLifecycle.STARTING) {
            current.lifecycle = RecordingLifecycle.FAILED_TO_START
            current.openSegment = null
            current.openSegmentReason = null
            current.locationOwnerToken = null
            StoreOutcome.StartFailed(current.id)
        } else StoreOutcome.StartFailureIgnored(transaction.sessionId, current?.lifecycle ?: RecordingLifecycle.STOPPED)
    }
    override suspend fun recordLocation(transaction: RecordLocationTransaction): StoreReceipt = once(transaction.operationId, RecordingOperationKind.LOCATION) {
        val current = session
        if (
            current?.id != transaction.sessionId ||
            current.lifecycle != RecordingLifecycle.ACTIVE ||
            current.openSegment != transaction.expectedOpenSegmentId ||
            current.locationOwnerToken != transaction.locationOwnerToken
        ) {
            return@once StoreOutcome.SessionGuardRejected(transaction.sessionId)
        }
        when (val decision = transaction.decision) {
            is LocationQualityDecision.Accepted -> {
                if (decision.kind == AcceptedLocationKind.AFTER_BREAK) {
                    current.openSegment?.let { closedReasons += decision.breakReason!!.name }
                    current.openSegment = null
                    current.openSegmentReason = null
                }
                if (current.openSegment == null) {
                    current.openSegment = nextSegment++
                    current.openSegmentReason = "AFTER_BREAK"
                    segmentStarts += "AFTER_BREAK"
                }
                acceptedKinds += decision.kind
                accepted++
                distance += decision.distanceMeters
                acceptedDistances += decision.distanceMeters
                StoreOutcome.LocationAccepted(decision.kind, decision.breakReason)
            }
            is LocationQualityDecision.Rejected -> {
                rejected++
                if (decision.breakReason != null) {
                    current.openSegment?.let { closedReasons += decision.breakReason.name }
                    current.openSegment = null
                    current.openSegmentReason = null
                }
                StoreOutcome.LocationRejected(decision.breakReason)
            }
        }
    }
    override suspend fun rejectStaleLocation(
        transaction: RejectLocationTransaction,
    ): StoreReceipt = once(transaction.operationId, RecordingOperationKind.LOCATION) {
        StoreOutcome.SessionGuardRejected(transaction.requestedSessionId)
    }
    override suspend fun requestStop(
        transaction: RequestStopTransaction,
    ): StoreReceipt = once(transaction.operationId, RecordingOperationKind.REQUEST_STOP) {
        val current = session
        if (
            current?.id == transaction.sessionId &&
            current.lifecycle in setOf(RecordingLifecycle.STARTING, RecordingLifecycle.ACTIVE)
        ) {
            if (current.pendingStopReason == null) current.pendingStopReason = transaction.reason
            StoreOutcome.StopRequested(current.id)
        } else {
            StoreOutcome.StopRequestIgnored(transaction.sessionId)
        }
    }
    override suspend fun stop(transaction: StopRecordingTransaction): StoreReceipt = once(transaction.operationId, transaction.operationKind) {
        val current = session
        when {
            current?.id != transaction.sessionId -> StoreOutcome.AlreadyStopped(transaction.sessionId)
            current.lifecycle == RecordingLifecycle.ACTIVE || current.lifecycle == RecordingLifecycle.STARTING -> {
                terminalStatuses += transaction.terminalStatus
                current.openSegment?.let { closedReasons += "STOP" }
                current.openSegment = null
                current.openSegmentReason = null
                current.locationOwnerToken = null
                current.lifecycle = RecordingLifecycle.STOPPED
                StoreOutcome.Stopped(current.id)
            }
            else -> StoreOutcome.AlreadyStopped(transaction.sessionId)
        }
    }
    override suspend fun recover(transaction: RecoverRecordingTransaction): StoreReceipt = once(transaction.operationId, RecordingOperationKind.RECOVERY) {
        val current = session
        if (
            current?.pendingStopReason != null &&
            current.lifecycle in setOf(RecordingLifecycle.STARTING, RecordingLifecycle.ACTIVE)
        ) {
            terminalStatuses += if (current.lifecycle == RecordingLifecycle.ACTIVE) {
                RecordingTerminalStatus.COMPLETED
            } else {
                RecordingTerminalStatus.INTERRUPTED
            }
            current.openSegment?.let { closedReasons += "STOP" }
            current.openSegment = null
            current.openSegmentReason = null
            current.locationOwnerToken = null
            current.lifecycle = RecordingLifecycle.STOPPED
            return@once StoreOutcome.RecoveredPendingStop(current.id)
        }
        when (current?.lifecycle) {
            RecordingLifecycle.STARTING -> {
                current.lifecycle = RecordingLifecycle.FAILED_TO_START
                current.locationOwnerToken = null
                StoreOutcome.RecoveredStartingAsFailed(current.id)
            }
            RecordingLifecycle.ACTIVE -> if (
                current.openSegmentReason == "PROCESS_RECOVERY" &&
                current.locationOwnerToken == transaction.locationOwnerToken
            ) {
                StoreOutcome.RecoveredActive(current.id, false)
            } else {
                current.openSegment?.let { closedReasons += "RECOVERY" }
                current.openSegment = nextSegment++
                current.openSegmentReason = "PROCESS_RECOVERY"
                current.locationOwnerToken = transaction.locationOwnerToken
                segmentStarts += "PROCESS_RECOVERY"
                recoveryRotations++
                StoreOutcome.RecoveredActive(current.id, true)
            }
            else -> StoreOutcome.NothingToRecover
        }
    }

    private suspend fun once(id: RecordingOperationId, kind: RecordingOperationKind, block: () -> StoreOutcome): StoreReceipt {
        receiptFor(id, kind)?.let { return it }
        if (failNext == kind) { failNext = null; throw IOException("injected $kind failure") }
        return StoreReceipt(id, kind, block(), project()).also { receipts[id] = it }
    }
    private fun project(): RecordingProjection {
        val current = session ?: return RecordingProjection()
        return RecordingProjection(RecordingSessionProjection(current.id, current.lifecycle, current.openSegment, accepted, rejected, distance))
    }
}
