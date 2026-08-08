package app.trailveil.data.recording

import app.trailveil.data.location.LocationBreakReason
import app.trailveil.data.location.LocationQualityDecision
import app.trailveil.data.location.LocationQualityFilter
import app.trailveil.data.location.RawLocationFix
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opaque, caller-owned id for a single durable command or location delivery. */
@JvmInline
internal value class RecordingOperationId(val value: String) {
    init { require(value.isNotBlank()) { "operationId must not be blank" } }
}

/** Stable for one app process and different after process restart. */
@JvmInline
internal value class RecordingRuntimeId(val value: String) {
    init { require(value.isNotBlank()) { "runtimeId must not be blank" } }
}

private object RecordingProcessIdentity {
    val runtimeId = RecordingRuntimeId(UUID.randomUUID().toString())
}

internal enum class RecordingOperationKind {
    BEGIN_START, COMPLETE_START, FAIL_START, LOCATION, REQUEST_STOP, STOP, INTERRUPT, RECOVERY,
}

internal enum class RecordingLifecycle { STARTING, ACTIVE, FAILED_TO_START, STOPPED }
internal enum class RecordingTerminalStatus { COMPLETED, INTERRUPTED }

/** The store-derived state the repository may cache only after a committed transaction. */
internal data class RecordingProjection(
    val session: RecordingSessionProjection? = null,
) {
    val state: RecordingRepositoryState get() = session?.let {
        RecordingRepositoryState(it.sessionId, it.lifecycle, it.openSegmentId, it.acceptedPointCount, it.rejectedPointCount, it.distanceMeters)
    } ?: RecordingRepositoryState()
}

internal data class RecordingSessionProjection(
    val sessionId: Long,
    val lifecycle: RecordingLifecycle,
    val openSegmentId: Long?,
    val acceptedPointCount: Long,
    val rejectedPointCount: Long,
    val distanceMeters: Double,
) {
    init {
        require(sessionId > 0L)
        require(acceptedPointCount >= 0L && rejectedPointCount >= 0L)
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
    }
}

internal data class RecordingRepositoryState(
    val sessionId: Long? = null,
    val lifecycle: RecordingLifecycle? = null,
    val openSegmentId: Long? = null,
    val acceptedPointCount: Long = 0L,
    val rejectedPointCount: Long = 0L,
    val distanceMeters: Double = 0.0,
)

/** Store commands. The Room adapter allocates all ids and segment/point sequences in its transaction. */
internal data class PrepareStartTransaction(
    val operationId: RecordingOperationId,
    val startedAtEpochMillis: Long,
    val createdAppVersion: String,
) { init { require(startedAtEpochMillis >= 0L && createdAppVersion.isNotBlank()) } }
internal data class ActivateStartTransaction(
    val operationId: RecordingOperationId,
    val sessionId: Long,
    val activatedAtEpochMillis: Long,
    val locationOwnerToken: RecordingRuntimeId,
) { init { require(sessionId > 0L && activatedAtEpochMillis >= 0L) } }
internal data class FailStartTransaction(
    val operationId: RecordingOperationId,
    val sessionId: Long,
    val failedAtEpochMillis: Long,
    val message: String? = null,
) { init { require(sessionId > 0L && failedAtEpochMillis >= 0L) } }
internal data class RecordLocationTransaction(
    val operationId: RecordingOperationId,
    val sessionId: Long,
    /** The segment observed before evaluating the stateful quality decision. */
    val expectedOpenSegmentId: Long?,
    /** Durable owner token acquired by a fresh activation or recovery transaction. */
    val locationOwnerToken: RecordingRuntimeId,
    /** This is canonical accepted data or coordinate-free rejection metadata; never a raw provider fix. */
    val decision: LocationQualityDecision,
    /** Trusted command time for segment/session close timestamps; never provider wall-clock data. */
    val recordedAtEpochMillis: Long,
) {
    init {
        require(sessionId > 0L && recordedAtEpochMillis >= 0L)
        require(expectedOpenSegmentId == null || expectedOpenSegmentId > 0L)
    }
}

/** A durable, coordinate-free rejection made before the quality filter is evaluated. */
internal data class RejectLocationTransaction(
    val operationId: RecordingOperationId,
    val requestedSessionId: Long,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(requestedSessionId > 0L && recordedAtEpochMillis >= 0L)
    }
}
internal data class RequestStopTransaction(
    val operationId: RecordingOperationId,
    val sessionId: Long,
    val requestedAtEpochMillis: Long,
    val reason: String,
) {
    init {
        require(sessionId > 0L && requestedAtEpochMillis >= 0L && reason.isNotBlank())
    }
}
internal data class StopRecordingTransaction(
    val operationId: RecordingOperationId,
    val sessionId: Long,
    val stoppedAtEpochMillis: Long,
    val reason: String,
    val terminalStatus: RecordingTerminalStatus,
) {
    init { require(sessionId > 0L && stoppedAtEpochMillis >= 0L && reason.isNotBlank()) }

    val operationKind: RecordingOperationKind
        get() = when (terminalStatus) {
            RecordingTerminalStatus.COMPLETED -> RecordingOperationKind.STOP
            RecordingTerminalStatus.INTERRUPTED -> RecordingOperationKind.INTERRUPT
        }
}
internal data class RecoverRecordingTransaction(
    val operationId: RecordingOperationId,
    val recoveredAtEpochMillis: Long,
    val locationOwnerToken: RecordingRuntimeId,
) { init { require(recoveredAtEpochMillis >= 0L) } }

/** The result persisted alongside a receipt. A replay must return the original outcome and projection. */
internal data class StoreReceipt(
    val operationId: RecordingOperationId,
    val kind: RecordingOperationKind,
    val outcome: StoreOutcome,
    val projection: RecordingProjection,
    val replayed: Boolean = false,
)

internal sealed interface StoreOutcome {
    data class StartPrepared(val sessionId: Long) : StoreOutcome
    data class StartAlreadyPending(val sessionId: Long) : StoreOutcome
    data class StartAlreadyActive(val sessionId: Long) : StoreOutcome
    data class StartActivated(val sessionId: Long) : StoreOutcome
    data class StartNotPending(val sessionId: Long, val lifecycle: RecordingLifecycle) : StoreOutcome
    data class StartFailed(val sessionId: Long) : StoreOutcome
    data class StartFailureIgnored(val sessionId: Long, val lifecycle: RecordingLifecycle) : StoreOutcome
    data class LocationAccepted(val kind: app.trailveil.data.location.AcceptedLocationKind, val breakReason: LocationBreakReason?) : StoreOutcome
    data class LocationRejected(val breakReason: LocationBreakReason?) : StoreOutcome
    data class SessionGuardRejected(val requestedSessionId: Long) : StoreOutcome
    data class StopRequested(val sessionId: Long) : StoreOutcome
    data class StopRequestIgnored(val sessionId: Long) : StoreOutcome
    data class Stopped(val sessionId: Long) : StoreOutcome
    data class AlreadyStopped(val sessionId: Long) : StoreOutcome
    data class RecoveredPendingStop(val sessionId: Long) : StoreOutcome
    data class RecoveredStartingAsFailed(val sessionId: Long) : StoreOutcome
    data class RecoveredActive(val sessionId: Long, val openedRecoverySegment: Boolean) : StoreOutcome
    data object NothingToRecover : StoreOutcome
}

/**
 * Persistence boundary. Each write is one atomic, receipt-idempotent transaction. In particular,
 * `recordLocation(AFTER_BREAK)` must close the old segment, open its replacement, append the
 * zero-distance point, and update session totals together. `recordLocation(Rejected)` increments
 * rejection count and closes an open segment only when the decision carries a break; it must never
 * receive a raw coordinate. A durable Stop request blocks activation and location writes and wins
 * over later technical terminal commands; `recover` consumes it before otherwise failing STARTING
 * rows or rotating ACTIVE rows exactly once.
 */
internal interface RecordingStore {
    /** Returns a previously committed receipt, or throws [OperationIdCollisionException] for a different kind. */
    suspend fun receiptFor(operationId: RecordingOperationId, expectedKind: RecordingOperationKind): StoreReceipt?
    suspend fun projection(): RecordingProjection
    suspend fun prepareStart(transaction: PrepareStartTransaction): StoreReceipt
    suspend fun activateStart(transaction: ActivateStartTransaction): StoreReceipt
    suspend fun failStart(transaction: FailStartTransaction): StoreReceipt
    suspend fun recordLocation(transaction: RecordLocationTransaction): StoreReceipt
    suspend fun rejectStaleLocation(transaction: RejectLocationTransaction): StoreReceipt
    suspend fun requestStop(transaction: RequestStopTransaction): StoreReceipt
    suspend fun stop(transaction: StopRecordingTransaction): StoreReceipt
    suspend fun recover(transaction: RecoverRecordingTransaction): StoreReceipt
}

internal class OperationIdCollisionException(message: String) : IllegalArgumentException(message)

internal sealed interface RecordingCommandResult {
    val operationId: RecordingOperationId
    val state: RecordingRepositoryState
}
internal data class BeginStartResult(override val operationId: RecordingOperationId, val sessionId: Long, val disposition: StartDisposition, override val state: RecordingRepositoryState) : RecordingCommandResult
internal enum class StartDisposition { PREPARED, ALREADY_STARTING, ALREADY_ACTIVE }
internal data class CompleteStartResult(override val operationId: RecordingOperationId, val sessionId: Long, val activated: Boolean, override val state: RecordingRepositoryState) : RecordingCommandResult
internal data class FailStartResult(override val operationId: RecordingOperationId, val sessionId: Long, val failed: Boolean, override val state: RecordingRepositoryState) : RecordingCommandResult
internal data class LocationDeliveryResult(override val operationId: RecordingOperationId, val disposition: LocationDisposition, override val state: RecordingRepositoryState) : RecordingCommandResult
internal enum class LocationDisposition { ACCEPTED, REJECTED, STALE_SESSION }
internal data class StopRequestResult(override val operationId: RecordingOperationId, val sessionId: Long, val requested: Boolean, override val state: RecordingRepositoryState) : RecordingCommandResult
internal data class StopResult(override val operationId: RecordingOperationId, val sessionId: Long, val stopped: Boolean, override val state: RecordingRepositoryState) : RecordingCommandResult
internal data class RecoveryResult(override val operationId: RecordingOperationId, val disposition: RecoveryDisposition, override val state: RecordingRepositoryState) : RecordingCommandResult
internal enum class RecoveryDisposition {
    PENDING_STOP_COMPLETED,
    STARTING_FAILED,
    ACTIVE_ROTATED,
    ACTIVE_ALREADY_RECOVERED,
    NOTHING_TO_RECOVER,
}

/**
 * Stateful pure-Kotlin command coordinator. Its mutex only serializes one repository instance;
 * the store, receipts, and session guards remain the authority for multiple instances/processes.
 */
internal class RecordingRepository(
    private val store: RecordingStore,
    private val qualityFilter: LocationQualityFilter = LocationQualityFilter(),
    private val runtimeId: RecordingRuntimeId = RecordingProcessIdentity.runtimeId,
) {
    private val mutex = Mutex()
    private val initialFilterCheckpoint = qualityFilter.checkpoint()
    private var cachedState = RecordingRepositoryState()
    // Existing ACTIVE state is intentionally inert until this process performs recovery.
    private var locationDeliveryEnabled = false
    private var locationOwnerToken: RecordingRuntimeId? = null

    suspend fun state(): RecordingRepositoryState = mutex.withLock {
        val previousSessionId = cachedState.sessionId
        val previousOpenSegmentId = cachedState.openSegmentId
        val wasLocationOwner = locationDeliveryEnabled
        apply(store.projection())
        if (
            wasLocationOwner &&
            (
                cachedState.lifecycle != RecordingLifecycle.ACTIVE ||
                    cachedState.sessionId != previousSessionId ||
                    cachedState.openSegmentId != previousOpenSegmentId
            )
        ) {
            // An external stop/recovery/segment rotation invalidates this filter's continuity.
            resetFilter()
            locationDeliveryEnabled = false
            locationOwnerToken = null
        }
        cachedState
    }

    suspend fun beginStart(
        operationId: RecordingOperationId,
        startedAtEpochMillis: Long,
        createdAppVersion: String,
    ): BeginStartResult = mutex.withLock {
        val receipt = receiptOrNull(operationId, RecordingOperationKind.BEGIN_START)
            ?: commit(
                store.prepareStart(
                    PrepareStartTransaction(operationId, startedAtEpochMillis, createdAppVersion),
                ),
            )
        val result = beginResult(receipt)
        if (!receipt.replayed && result.disposition == StartDisposition.PREPARED) {
            resetFilter()
            locationDeliveryEnabled = false
            locationOwnerToken = null
        }
        result
    }

    suspend fun completeStart(
        operationId: RecordingOperationId,
        sessionId: Long,
        activatedAtEpochMillis: Long,
    ): CompleteStartResult = mutex.withLock {
        val receipt = receiptOrNull(operationId, RecordingOperationKind.COMPLETE_START)
            ?: commit(
                store.activateStart(
                    ActivateStartTransaction(operationId, sessionId, activatedAtEpochMillis, runtimeId),
                ),
            )
        val result = completeResult(receipt)
        // A replay observes the original result but does not acquire this process's delivery role.
        if (result.activated && !receipt.replayed) {
            resetFilter()
            locationDeliveryEnabled = true
            locationOwnerToken = runtimeId
        }
        result
    }

    suspend fun failStart(
        operationId: RecordingOperationId,
        sessionId: Long,
        failedAtEpochMillis: Long,
        message: String? = null,
    ): FailStartResult = mutex.withLock {
        val receipt = receiptOrNull(operationId, RecordingOperationKind.FAIL_START)
            ?: commit(
                store.failStart(
                    FailStartTransaction(operationId, sessionId, failedAtEpochMillis, message),
                ),
            )
        val result = failResult(receipt)
        if (result.failed && !receipt.replayed) {
            resetFilter()
            locationDeliveryEnabled = false
            locationOwnerToken = null
        }
        result
    }

    suspend fun deliverLocation(
        operationId: RecordingOperationId,
        sessionId: Long,
        rawFix: RawLocationFix,
        nowElapsedRealtimeNanos: Long,
        recordedAtEpochMillis: Long,
    ): LocationDeliveryResult = mutex.withLock {
        receiptOrNull(operationId, RecordingOperationKind.LOCATION)?.let {
            return@withLock locationResult(it)
        }

        // Persist even a local guard so one acknowledged operation ID can never mutate later.
        if (
            !locationDeliveryEnabled ||
            locationOwnerToken == null ||
            cachedState.lifecycle != RecordingLifecycle.ACTIVE ||
            cachedState.sessionId != sessionId
        ) {
            return@withLock locationResult(
                commit(
                    store.rejectStaleLocation(
                        RejectLocationTransaction(
                            operationId,
                            sessionId,
                            recordedAtEpochMillis,
                        ),
                    ),
                ),
            )
        }

        val checkpoint = qualityFilter.checkpoint()
        val decision = qualityFilter.evaluate(rawFix, nowElapsedRealtimeNanos)
        val receipt = try {
            store.recordLocation(
                RecordLocationTransaction(
                    operationId = operationId,
                    sessionId = sessionId,
                    expectedOpenSegmentId = cachedState.openSegmentId,
                    locationOwnerToken = requireNotNull(locationOwnerToken),
                    decision = decision,
                    recordedAtEpochMillis = recordedAtEpochMillis,
                ),
            )
        } catch (failure: Throwable) {
            qualityFilter.restore(checkpoint)
            throw failure
        }
        when {
            // A receipt may appear between the repository lookup and the store transaction.
            // Any replay wins over its historical outcome and cannot mutate this owner's gate.
            receipt.replayed -> qualityFilter.restore(checkpoint)
            receipt.outcome is StoreOutcome.SessionGuardRejected -> {
                resetFilter()
                locationDeliveryEnabled = false
                locationOwnerToken = null
            }
        }
        locationResult(commit(receipt))
    }

    suspend fun stop(
        operationId: RecordingOperationId,
        sessionId: Long,
        stoppedAtEpochMillis: Long,
        reason: String,
    ): StopResult = mutex.withLock {
        finishLocked(
            StopRecordingTransaction(
                operationId,
                sessionId,
                stoppedAtEpochMillis,
                reason,
                RecordingTerminalStatus.COMPLETED,
            ),
        )
    }

    /**
     * Persists user intent before the service stops location delivery. Once acknowledged, even a
     * replay disables this repository instance so no fix can race ahead of terminal recovery.
     */
    suspend fun requestStop(
        operationId: RecordingOperationId,
        sessionId: Long,
        requestedAtEpochMillis: Long,
        reason: String,
    ): StopRequestResult = mutex.withLock {
        val receipt = receiptOrNull(operationId, RecordingOperationKind.REQUEST_STOP)
            ?: commit(
                store.requestStop(
                    RequestStopTransaction(
                        operationId,
                        sessionId,
                        requestedAtEpochMillis,
                        reason,
                    ),
                ),
            )
        val result = stopRequestResult(receipt)
        if (result.requested) {
            resetFilter()
            locationDeliveryEnabled = false
            locationOwnerToken = null
        }
        result
    }

    suspend fun interrupt(
        operationId: RecordingOperationId,
        sessionId: Long,
        interruptedAtEpochMillis: Long,
        reason: String,
    ): StopResult = mutex.withLock {
        finishLocked(
            StopRecordingTransaction(
                operationId,
                sessionId,
                interruptedAtEpochMillis,
                reason,
                RecordingTerminalStatus.INTERRUPTED,
            ),
        )
    }

    suspend fun recover(
        operationId: RecordingOperationId,
        recoveredAtEpochMillis: Long,
    ): RecoveryResult = mutex.withLock {
        val receipt = receiptOrNull(operationId, RecordingOperationKind.RECOVERY)
            ?: commit(
                store.recover(
                    RecoverRecordingTransaction(operationId, recoveredAtEpochMillis, runtimeId),
                ),
            )
        when (val outcome = receipt.outcome) {
            is StoreOutcome.RecoveredPendingStop -> {
                resetFilter()
                locationDeliveryEnabled = false
                locationOwnerToken = null
            }
            is StoreOutcome.RecoveredStartingAsFailed -> if (!receipt.replayed) {
                resetFilter()
                locationDeliveryEnabled = false
                locationOwnerToken = null
            }
            is StoreOutcome.RecoveredActive -> if (!receipt.replayed) {
                resetFilter()
                // Same-process duplicate recovery does not acquire a second filter owner.
                locationDeliveryEnabled = outcome.openedRecoverySegment
                locationOwnerToken = runtimeId.takeIf { outcome.openedRecoverySegment }
            }
            else -> Unit
        }
        recoveryResult(receipt)
    }

    private suspend fun receiptOrNull(
        id: RecordingOperationId,
        kind: RecordingOperationKind,
    ): StoreReceipt? = store.receiptFor(id, kind)

    private fun commit(receipt: StoreReceipt): StoreReceipt = receipt.also {
        if (!it.replayed) apply(it.projection)
    }

    private fun apply(projection: RecordingProjection) { cachedState = projection.state }
    private fun resetFilter() = qualityFilter.restore(initialFilterCheckpoint)

    private suspend fun finishLocked(transaction: StopRecordingTransaction): StopResult {
        val receipt = receiptOrNull(transaction.operationId, transaction.operationKind)
            ?: commit(store.stop(transaction))
        val result = stopResult(receipt)
        if (result.stopped && !receipt.replayed) {
            resetFilter()
            locationDeliveryEnabled = false
            locationOwnerToken = null
        }
        return result
    }

    private fun beginResult(receipt: StoreReceipt): BeginStartResult = when (val outcome = receipt.outcome) {
        is StoreOutcome.StartPrepared -> BeginStartResult(receipt.operationId, outcome.sessionId, StartDisposition.PREPARED, receipt.projection.state)
        is StoreOutcome.StartAlreadyPending -> BeginStartResult(receipt.operationId, outcome.sessionId, StartDisposition.ALREADY_STARTING, receipt.projection.state)
        is StoreOutcome.StartAlreadyActive -> BeginStartResult(receipt.operationId, outcome.sessionId, StartDisposition.ALREADY_ACTIVE, receipt.projection.state)
        else -> error("Unexpected begin receipt: $outcome")
    }
    private fun completeResult(receipt: StoreReceipt): CompleteStartResult = when (val outcome = receipt.outcome) {
        is StoreOutcome.StartActivated -> CompleteStartResult(receipt.operationId, outcome.sessionId, true, receipt.projection.state)
        is StoreOutcome.StartNotPending -> CompleteStartResult(receipt.operationId, outcome.sessionId, false, receipt.projection.state)
        else -> error("Unexpected complete receipt: $outcome")
    }
    private fun failResult(receipt: StoreReceipt): FailStartResult = when (val outcome = receipt.outcome) {
        is StoreOutcome.StartFailed -> FailStartResult(receipt.operationId, outcome.sessionId, true, receipt.projection.state)
        is StoreOutcome.StartFailureIgnored -> FailStartResult(receipt.operationId, outcome.sessionId, false, receipt.projection.state)
        else -> error("Unexpected fail receipt: $outcome")
    }
    private fun locationResult(receipt: StoreReceipt): LocationDeliveryResult = when (receipt.outcome) {
        is StoreOutcome.LocationAccepted -> LocationDeliveryResult(receipt.operationId, LocationDisposition.ACCEPTED, receipt.projection.state)
        is StoreOutcome.LocationRejected -> LocationDeliveryResult(receipt.operationId, LocationDisposition.REJECTED, receipt.projection.state)
        is StoreOutcome.SessionGuardRejected -> LocationDeliveryResult(receipt.operationId, LocationDisposition.STALE_SESSION, receipt.projection.state)
        else -> error("Unexpected location receipt: ${receipt.outcome}")
    }
    private fun stopRequestResult(receipt: StoreReceipt): StopRequestResult = when (val outcome = receipt.outcome) {
        is StoreOutcome.StopRequested -> StopRequestResult(receipt.operationId, outcome.sessionId, true, receipt.projection.state)
        is StoreOutcome.StopRequestIgnored -> StopRequestResult(receipt.operationId, outcome.sessionId, false, receipt.projection.state)
        else -> error("Unexpected stop-request receipt: $outcome")
    }
    private fun stopResult(receipt: StoreReceipt): StopResult = when (val outcome = receipt.outcome) {
        is StoreOutcome.Stopped -> StopResult(receipt.operationId, outcome.sessionId, true, receipt.projection.state)
        is StoreOutcome.AlreadyStopped -> StopResult(receipt.operationId, outcome.sessionId, false, receipt.projection.state)
        else -> error("Unexpected stop receipt: $outcome")
    }
    private fun recoveryResult(receipt: StoreReceipt): RecoveryResult = when (val outcome = receipt.outcome) {
        is StoreOutcome.RecoveredPendingStop -> RecoveryResult(receipt.operationId, RecoveryDisposition.PENDING_STOP_COMPLETED, receipt.projection.state)
        is StoreOutcome.RecoveredStartingAsFailed -> RecoveryResult(receipt.operationId, RecoveryDisposition.STARTING_FAILED, receipt.projection.state)
        is StoreOutcome.RecoveredActive -> RecoveryResult(
            receipt.operationId,
            if (outcome.openedRecoverySegment) {
                RecoveryDisposition.ACTIVE_ROTATED
            } else {
                RecoveryDisposition.ACTIVE_ALREADY_RECOVERED
            },
            receipt.projection.state,
        )
        StoreOutcome.NothingToRecover -> RecoveryResult(receipt.operationId, RecoveryDisposition.NOTHING_TO_RECOVER, receipt.projection.state)
        else -> error("Unexpected recovery receipt: $outcome")
    }
}
