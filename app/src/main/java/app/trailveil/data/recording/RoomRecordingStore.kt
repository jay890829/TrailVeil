package app.trailveil.data.recording

import app.trailveil.data.db.RecordingDao
import app.trailveil.data.db.RecordingReceiptOutcome
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.location.AcceptedLocationKind
import app.trailveil.data.location.LocationBreakReason
import app.trailveil.data.location.LocationQualityDecision

/**
 * Room-backed recording boundary. DAO command methods own each complete SQLite transaction:
 * receipt replay/collision detection, singleton guards, sequence allocation, and all writes.
 */
internal class RoomRecordingStore(
    private val dao: RecordingDao,
) : RecordingStore {
    override suspend fun receiptFor(
        operationId: RecordingOperationId,
        expectedKind: RecordingOperationKind,
    ): StoreReceipt? {
        val receipt = dao.receiptByOperationId(operationId.value) ?: run {
            dao.ensureMissingOperationCanUseKind(operationId.value, expectedKind.name)
            return null
        }
        val storedKind = runCatching { RecordingOperationKind.valueOf(receipt.commandKind) }.getOrNull()
        if (storedKind != expectedKind) {
            throw OperationIdCollisionException(
                "operation id ".plus(operationId.value).plus(" reused for ").plus(receipt.commandKind).plus(", not ").plus(expectedKind.name),
            )
        }
        return decode(receipt, storedKind, replayed = true)
    }

    override suspend fun projection(): RecordingProjection {
        val session = dao.activeSession()
            ?: dao.reservedSession()
            ?: dao.latestSession()
            ?: return RecordingProjection()
        return projectionFor(session.id)
    }

    override suspend fun prepareStart(transaction: PrepareStartTransaction): StoreReceipt =
        decodeResult(
            dao.executePrepareStart(
                startedAt = transaction.startedAtEpochMillis,
                createdAppVersion = transaction.createdAppVersion,
                operationId = transaction.operationId.value,
                commandKind = RecordingOperationKind.BEGIN_START.name,
                createdAt = transaction.startedAtEpochMillis,
            ),
            RecordingOperationKind.BEGIN_START,
        )

    override suspend fun activateStart(transaction: ActivateStartTransaction): StoreReceipt =
        decodeResult(
            dao.executeActivateStart(
                sessionId = transaction.sessionId,
                activatedAt = transaction.activatedAtEpochMillis,
                locationOwnerToken = transaction.locationOwnerToken.value,
                operationId = transaction.operationId.value,
                commandKind = RecordingOperationKind.COMPLETE_START.name,
                createdAt = transaction.activatedAtEpochMillis,
            ),
            RecordingOperationKind.COMPLETE_START,
        )

    override suspend fun failStart(transaction: FailStartTransaction): StoreReceipt =
        decodeResult(
            dao.executeFailStart(
                sessionId = transaction.sessionId,
                failedAt = transaction.failedAtEpochMillis,
                message = transaction.message,
                operationId = transaction.operationId.value,
                commandKind = RecordingOperationKind.FAIL_START.name,
                createdAt = transaction.failedAtEpochMillis,
            ),
            RecordingOperationKind.FAIL_START,
        )

    override suspend fun reconcileStarting(transaction: ReconcileStartingTransaction): StoreReceipt =
        decodeResult(
            dao.executeReconcileStarting(
                reconciledAt = transaction.reconciledAtEpochMillis,
                operationId = transaction.operationId.value,
                commandKind = RecordingOperationKind.RECONCILE_STARTING.name,
                createdAt = transaction.reconciledAtEpochMillis,
            ),
            RecordingOperationKind.RECONCILE_STARTING,
        )

    override suspend fun recordLocation(transaction: RecordLocationTransaction): StoreReceipt {
        val decision = transaction.decision
        val accepted = decision as? LocationQualityDecision.Accepted
        val point = accepted?.fix?.let { fix ->
            TrackPointEntity(
                sessionId = transaction.sessionId,
                segmentId = 0L,
                sequence = 0L,
                timestamp = fix.epochMillis,
                latitude = fix.latitude,
                longitude = fix.longitude,
                horizontalAccuracy = fix.horizontalAccuracyMeters,
                altitude = fix.altitudeMeters,
                speed = fix.speedMetersPerSecond,
                bearing = fix.bearingDegrees,
                isMock = fix.isMock,
            )
        }
        val kind = accepted?.kind?.name
        val breakReason = when (decision) {
            is LocationQualityDecision.Accepted -> decision.breakReason?.name
            is LocationQualityDecision.Rejected -> decision.breakReason?.name
        }
        val distance = accepted?.distanceMeters ?: 0.0
        return decodeResult(
            dao.executeLocation(
                sessionId = transaction.sessionId,
                expectedOpenSegmentId = transaction.expectedOpenSegmentId,
                expectedLocationOwnerToken = transaction.locationOwnerToken.value,
                point = point,
                acceptedKind = kind,
                breakReason = breakReason,
                distanceDeltaMeters = distance,
                recordedAt = transaction.recordedAtEpochMillis,
                operationId = transaction.operationId.value,
                commandKind = RecordingOperationKind.LOCATION.name,
                createdAt = transaction.recordedAtEpochMillis,
            ),
            RecordingOperationKind.LOCATION,
        )
    }

    override suspend fun rejectStaleLocation(transaction: RejectLocationTransaction): StoreReceipt =
        decodeResult(
            dao.executeLocationGuard(
                sessionId = transaction.requestedSessionId,
                operationId = transaction.operationId.value,
                commandKind = RecordingOperationKind.LOCATION.name,
                createdAt = transaction.recordedAtEpochMillis,
            ),
            RecordingOperationKind.LOCATION,
        )

    override suspend fun requestStop(transaction: RequestStopTransaction): StoreReceipt =
        decodeResult(
            dao.executeRequestStop(
                sessionId = transaction.sessionId,
                requestedAt = transaction.requestedAtEpochMillis,
                reason = transaction.reason,
                operationId = transaction.operationId.value,
                commandKind = RecordingOperationKind.REQUEST_STOP.name,
                createdAt = transaction.requestedAtEpochMillis,
            ),
            RecordingOperationKind.REQUEST_STOP,
        )

    override suspend fun stop(transaction: StopRecordingTransaction): StoreReceipt =
        decodeResult(
            dao.executeStop(
                sessionId = transaction.sessionId,
                stoppedAt = transaction.stoppedAtEpochMillis,
                reason = transaction.reason,
                terminalStatus = when (transaction.terminalStatus) {
                    RecordingTerminalStatus.COMPLETED -> RecordingStatus.COMPLETED
                    RecordingTerminalStatus.INTERRUPTED -> RecordingStatus.INTERRUPTED
                },
                operationId = transaction.operationId.value,
                commandKind = transaction.operationKind.name,
                createdAt = transaction.stoppedAtEpochMillis,
            ),
            transaction.operationKind,
        )

    override suspend fun recover(transaction: RecoverRecordingTransaction): StoreReceipt =
        decodeResult(
            dao.executeRecovery(
                recoveredAt = transaction.recoveredAtEpochMillis,
                locationOwnerToken = transaction.locationOwnerToken.value,
                operationId = transaction.operationId.value,
                commandKind = RecordingOperationKind.RECOVERY.name,
                createdAt = transaction.recoveredAtEpochMillis,
            ),
            RecordingOperationKind.RECOVERY,
        )

    private fun decodeResult(
        result: app.trailveil.data.db.RecordingOperationResult,
        kind: RecordingOperationKind,
    ): StoreReceipt = decode(result.receipt, kind, result.replayed)

    private fun decode(
        receipt: app.trailveil.data.db.RecordingOperationReceiptEntity,
        kind: RecordingOperationKind,
        replayed: Boolean,
    ): StoreReceipt = StoreReceipt(
        operationId = RecordingOperationId(receipt.operationId),
        kind = kind,
        outcome = receipt.outcome.toStoreOutcome(receipt.sessionId),
        projection = receipt.toProjection(),
        replayed = replayed,
    )

    /** Receipts never consult the live database; this is the transactionally captured outcome state. */
    private fun app.trailveil.data.db.RecordingOperationReceiptEntity.toProjection(): RecordingProjection {
        val sessionId = projectionSessionId ?: return RecordingProjection()
        val lifecycle = requireNotNull(projectionLifecycle) { "receipt projection lifecycle missing" }
        return RecordingProjection(
            RecordingSessionProjection(
                sessionId = sessionId,
                lifecycle = RecordingStatus.valueOf(lifecycle).toLifecycle(),
                openSegmentId = projectionOpenSegmentId,
                acceptedPointCount = requireNotNull(projectionAcceptedPointCount),
                rejectedPointCount = requireNotNull(projectionRejectedPointCount),
                distanceMeters = requireNotNull(projectionDistanceMeters),
            ),
        )
    }

    private suspend fun projectionFor(sessionId: Long): RecordingProjection {
        val state = dao.recordingState(sessionId) ?: return RecordingProjection()
        return RecordingProjection(
            RecordingSessionProjection(
                sessionId = state.session.id,
                lifecycle = state.session.status.toLifecycle(),
                openSegmentId = state.openSegment?.id,
                acceptedPointCount = state.session.acceptedPointCount,
                rejectedPointCount = state.session.rejectedPointCount,
                distanceMeters = state.session.distanceMeters,
            ),
        )
    }

    private fun String.toStoreOutcome(sessionId: Long?): StoreOutcome {
        if (this == RecordingReceiptOutcome.NOTHING_TO_RECOVER) return StoreOutcome.NothingToRecover
        if (this == RecordingReceiptOutcome.NOTHING_TO_RECONCILE) return StoreOutcome.NothingToReconcile
        val id = requireNotNull(sessionId) { "receipt ".plus(this).plus(" requires a session id") }
        return when {
            this == RecordingReceiptOutcome.START_PREPARED -> StoreOutcome.StartPrepared(id)
            this == RecordingReceiptOutcome.START_ALREADY_PENDING -> StoreOutcome.StartAlreadyPending(id)
            this == RecordingReceiptOutcome.START_ALREADY_ACTIVE -> StoreOutcome.StartAlreadyActive(id)
            this == RecordingReceiptOutcome.START_ACTIVATED -> StoreOutcome.StartActivated(id)
            this == RecordingReceiptOutcome.START_FAILED -> StoreOutcome.StartFailed(id)
            this == RecordingReceiptOutcome.RECONCILED_STARTING -> StoreOutcome.ReconciledStartingAsFailed(id)
            this == RecordingReceiptOutcome.RECONCILED_PENDING_STOP -> StoreOutcome.ReconciledPendingStop(id)
            this == RecordingReceiptOutcome.LOCATION_SESSION_GUARD -> StoreOutcome.SessionGuardRejected(id)
            startsWith(RecordingReceiptOutcome.STOP_REQUESTED_PREFIX) -> StoreOutcome.StopRequested(id)
            this == RecordingReceiptOutcome.STOP_REQUEST_IGNORED -> StoreOutcome.StopRequestIgnored(id)
            this == RecordingReceiptOutcome.STOPPED -> StoreOutcome.Stopped(id)
            this == RecordingReceiptOutcome.ALREADY_STOPPED -> StoreOutcome.AlreadyStopped(id)
            this == RecordingReceiptOutcome.RECOVERED_STARTING -> StoreOutcome.RecoveredStartingAsFailed(id)
            this == RecordingReceiptOutcome.RECOVERED_PENDING_STOP -> StoreOutcome.RecoveredPendingStop(id)
            this == RecordingReceiptOutcome.RECOVERED_ACTIVE -> StoreOutcome.RecoveredActive(id, openedRecoverySegment = true)
            this == RecordingReceiptOutcome.RECOVERED_ACTIVE_ALREADY -> StoreOutcome.RecoveredActive(id, openedRecoverySegment = false)
            startsWith("START_NOT_PENDING_") -> StoreOutcome.StartNotPending(id, suffixLifecycle("START_NOT_PENDING_"))
            startsWith("START_FAILURE_IGNORED_") -> StoreOutcome.StartFailureIgnored(id, suffixLifecycle("START_FAILURE_IGNORED_"))
            startsWith("LOCATION_ACCEPTED_") -> {
                val suffix = removePrefix("LOCATION_ACCEPTED_")
                val accepted = AcceptedLocationKind.entries
                    .sortedByDescending { it.name.length }
                    .firstOrNull { suffix == it.name || suffix.startsWith(it.name.plus("_")) }
                    ?: error("Unknown accepted location kind: ".plus(suffix))
                val breakValue = suffix.removePrefix(accepted.name).removePrefix("_")
                val breakReason = breakValue.takeUnless { it.isEmpty() || it == "NONE" }?.let(LocationBreakReason::valueOf)
                StoreOutcome.LocationAccepted(accepted, breakReason)
            }
            startsWith("LOCATION_REJECTED_") -> {
                val value = removePrefix("LOCATION_REJECTED_")
                StoreOutcome.LocationRejected(value.takeUnless { it == "NONE" }?.let(LocationBreakReason::valueOf))
            }
            else -> error("Unknown durable recording receipt outcome: ".plus(this))
        }
    }

    private fun String.suffixLifecycle(prefix: String): RecordingLifecycle =
        removePrefix(prefix).let { value ->
            when (value) {
                RecordingStatus.STARTING.name -> RecordingLifecycle.STARTING
                RecordingStatus.ACTIVE.name -> RecordingLifecycle.ACTIVE
                RecordingStatus.FAILED_TO_START.name -> RecordingLifecycle.FAILED_TO_START
                else -> RecordingLifecycle.STOPPED
            }
        }

    private fun RecordingStatus.toLifecycle(): RecordingLifecycle = when (this) {
        RecordingStatus.STARTING -> RecordingLifecycle.STARTING
        RecordingStatus.ACTIVE -> RecordingLifecycle.ACTIVE
        RecordingStatus.FAILED_TO_START -> RecordingLifecycle.FAILED_TO_START
        RecordingStatus.COMPLETED, RecordingStatus.INTERRUPTED -> RecordingLifecycle.STOPPED
    }
}
