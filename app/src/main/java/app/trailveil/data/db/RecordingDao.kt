package app.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import app.trailveil.data.recording.ExpiredLocationOperationException
import app.trailveil.data.recording.LOCATION_RECEIPT_PRUNE_INTERVAL
import app.trailveil.data.recording.LOCATION_RECEIPT_RETAIN_COUNT
import app.trailveil.data.recording.OperationIdCollisionException
import app.trailveil.data.recording.locationSequenceOrNull
import kotlinx.coroutines.flow.Flow

internal data class StartedRecording(val sessionId: Long, val segmentId: Long)
internal data class RecordingOperationResult(val receipt: RecordingOperationReceiptEntity, val replayed: Boolean)
private const val LOCATION_COMMAND_KIND = "LOCATION"
internal data class RecordingSessionWithSegments(@Embedded val session: RecordingSessionEntity, @Relation(parentColumn = "id", entityColumn = "session_id") val segments: List<TrackSegmentEntity>)
internal data class ViewportTrackPointRow(
    @ColumnInfo(name = "point_id") val pointId: Long,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "segment_id") val segmentId: Long,
    @ColumnInfo(name = "segment_sequence") val segmentSequence: Long,
    @ColumnInfo(name = "point_sequence") val pointSequence: Long,
    val latitude: Double,
    val longitude: Double,
)

/** Flat Room projection for one canonical point and its predecessor in the same segment. */
internal data class PersistedTrackPointChangeRow(
    @ColumnInfo(name = "point_id") val pointId: Long,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "segment_id") val segmentId: Long,
    @ColumnInfo(name = "segment_sequence") val segmentSequence: Long,
    @ColumnInfo(name = "point_sequence") val pointSequence: Long,
    @ColumnInfo(name = "point_timestamp") val pointTimestamp: Long,
    @ColumnInfo(name = "point_latitude") val pointLatitude: Double,
    @ColumnInfo(name = "point_longitude") val pointLongitude: Double,
    @ColumnInfo(name = "previous_point_id") val previousPointId: Long?,
    @ColumnInfo(name = "previous_point_sequence") val previousPointSequence: Long?,
    @ColumnInfo(name = "previous_point_timestamp") val previousPointTimestamp: Long?,
    @ColumnInfo(name = "previous_point_latitude") val previousPointLatitude: Double?,
    @ColumnInfo(name = "previous_point_longitude") val previousPointLongitude: Double?,
)

/** Flat Room projection for ordered accepted points used by the provider-neutral history detail. */
internal data class HistoryAcceptedTrackPointRow(
    @ColumnInfo(name = "point_id") val pointId: Long,
    @ColumnInfo(name = "segment_id") val segmentId: Long,
    @ColumnInfo(name = "segment_sequence") val segmentSequence: Long,
    @ColumnInfo(name = "point_sequence") val pointSequence: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
)

/** Snapshot used by the recording adapter to resume an ACTIVE or STARTING command safely. */
internal data class RecordingStateProjection(
    @Embedded val session: RecordingSessionEntity,
    @Embedded(prefix = "segment_") val openSegment: OpenSegmentProjection?,
    @Embedded(prefix = "point_") val latestPoint: LatestPointProjection?,
    val nextSegmentSequence: Long,
    val nextPointSequence: Long,
)
internal data class OpenSegmentProjection(val id: Long, val sequence: Long, @androidx.room.ColumnInfo(name = "started_at") val startedAt: Long)
internal data class LatestPointProjection(val id: Long, val sequence: Long, val timestamp: Long)

/** Stable, coordinate-free receipt values shared with the Room recording adapter. */
internal object RecordingReceiptOutcome {
    const val START_PREPARED = "START_PREPARED"
    const val START_ALREADY_PENDING = "START_ALREADY_PENDING"
    const val START_ALREADY_ACTIVE = "START_ALREADY_ACTIVE"
    const val START_ACTIVATED = "START_ACTIVATED"
    const val START_FAILED = "START_FAILED"
    const val RECONCILED_STARTING = "RECONCILED_STARTING"
    const val RECONCILED_PENDING_STOP = "RECONCILED_PENDING_STOP"
    const val NOTHING_TO_RECONCILE = "NOTHING_TO_RECONCILE"
    const val LOCATION_SESSION_GUARD = "LOCATION_SESSION_GUARD"
    const val STOP_REQUESTED_PREFIX = "STOP_REQUESTED:"
    const val STOP_REQUEST_IGNORED = "STOP_REQUEST_IGNORED"
    const val STOPPED = "STOPPED"
    const val ALREADY_STOPPED = "ALREADY_STOPPED"
    const val RECOVERED_STARTING = "RECOVERED_STARTING"
    const val RECOVERED_PENDING_STOP = "RECOVERED_PENDING_STOP"
    const val RECOVERED_ACTIVE = "RECOVERED_ACTIVE"
    const val RECOVERED_ACTIVE_ALREADY = "RECOVERED_ACTIVE_ALREADY"
    const val NOTHING_TO_RECOVER = "NOTHING_TO_RECOVER"
    const val ACCEPTED_AFTER_BREAK = "AFTER_BREAK"
    const val SEGMENT_SESSION_START = "SESSION_START"
    const val SEGMENT_PROCESS_RECOVERY = "PROCESS_RECOVERY"
    fun startNotPending(status: RecordingStatus?): String = "START_NOT_PENDING_${status?.name ?: "MISSING"}"
    fun startFailureIgnored(status: RecordingStatus?): String = "START_FAILURE_IGNORED_${status?.name ?: "MISSING"}"
    fun locationAccepted(kind: String, breakReason: String?): String = "LOCATION_ACCEPTED_${kind}_${breakReason ?: "NONE"}"
    fun locationRejected(breakReason: String?): String = "LOCATION_REJECTED_${breakReason ?: "NONE"}"
    fun stopRequested(reason: String): String = STOP_REQUESTED_PREFIX + reason
}
@Dao
internal abstract class RecordingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertSessionRow(session: RecordingSessionEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertSegmentRow(segment: TrackSegmentEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertPointRow(point: TrackPointEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertReceiptRow(receipt: RecordingOperationReceiptEntity)

    @Query("SELECT * FROM recording_operation_receipts WHERE operation_id = :operationId")
    abstract suspend fun receiptByOperationId(operationId: String): RecordingOperationReceiptEntity?

    @Query(
        "SELECT expired_through_sequence FROM recording_location_receipt_windows " +
            "WHERE runtime_token = :runtimeToken",
    )
    protected abstract suspend fun expiredLocationSequence(runtimeToken: String): Long?

    @Query(
        "INSERT INTO recording_location_receipt_windows(runtime_token, expired_through_sequence) " +
            "VALUES(:runtimeToken, :expiredThrough) " +
            "ON CONFLICT(runtime_token) DO UPDATE SET expired_through_sequence = " +
            "MAX(expired_through_sequence, excluded.expired_through_sequence)",
    )
    protected abstract suspend fun advanceExpiredLocationSequence(
        runtimeToken: String,
        expiredThrough: Long,
    )

    @Query(
        "SELECT operation_id FROM recording_operation_receipts " +
            "WHERE session_id = :sessionId AND command_kind = 'LOCATION' " +
            "AND operation_id LIKE 'location:%' " +
            "ORDER BY rowid DESC LIMIT -1 OFFSET :retainCount",
    )
    protected abstract suspend fun prunableStructuredLocationReceipts(
        sessionId: Long,
        retainCount: Int,
    ): List<String>

    @Query("DELETE FROM recording_operation_receipts WHERE operation_id = :operationId")
    protected abstract suspend fun deleteReceiptByOperationId(operationId: String): Int

    @Query(
        "INSERT INTO recording_location_receipt_retention_states(" +
            "session_id, retained_receipt_count) VALUES(:sessionId, 1) " +
            "ON CONFLICT(session_id) DO UPDATE SET retained_receipt_count = " +
            "retained_receipt_count + 1",
    )
    protected abstract suspend fun incrementStructuredLocationReceiptCount(sessionId: Long)

    @Query(
        "SELECT retained_receipt_count FROM recording_location_receipt_retention_states " +
            "WHERE session_id = :sessionId",
    )
    abstract suspend fun retainedStructuredLocationReceiptCount(sessionId: Long): Int?

    @Query(
        "UPDATE recording_location_receipt_retention_states SET retained_receipt_count = " +
            "retained_receipt_count - :deletedCount WHERE session_id = :sessionId " +
            "AND retained_receipt_count >= :deletedCount",
    )
    protected abstract suspend fun decrementStructuredLocationReceiptCount(
        sessionId: Long,
        deletedCount: Int,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM recording_operation_receipts " +
            "WHERE session_id = :sessionId AND command_kind = 'LOCATION' " +
            "AND operation_id LIKE 'location:%'",
    )
    abstract suspend fun structuredLocationReceiptCount(sessionId: Long): Int

    @Query(
        "SELECT * FROM recording_operation_receipts " +
            "WHERE session_id = :sessionId AND command_kind = 'REQUEST_STOP' " +
            "AND outcome LIKE 'STOP_REQUESTED:%' ORDER BY created_at ASC, rowid ASC LIMIT 1",
    )
    protected abstract suspend fun pendingStopRequestForSession(
        sessionId: Long,
    ): RecordingOperationReceiptEntity?

    @Query("UPDATE recording_sessions SET accepted_point_count = accepted_point_count + 1, distance_meters = distance_meters + :distanceDeltaMeters WHERE id = :sessionId AND status = 'ACTIVE' AND active_slot = 1")
    protected abstract suspend fun incrementAcceptedSummary(sessionId: Long, distanceDeltaMeters: Double): Int
    @Query("UPDATE recording_sessions SET rejected_point_count = rejected_point_count + 1 WHERE id = :sessionId AND status = 'ACTIVE' AND active_slot = 1")
    protected abstract suspend fun incrementRejectedSummary(sessionId: Long): Int
    @Query("UPDATE recording_sessions SET status = :status, ended_at = :endedAt, stop_reason = :stopReason, active_slot = NULL, location_owner_token = NULL WHERE id = :sessionId AND status = :expectedStatus AND active_slot = 1")
    protected abstract suspend fun closeSessionRow(sessionId: Long, expectedStatus: RecordingStatus, status: RecordingStatus, endedAt: Long, stopReason: String): Int
    @Query("UPDATE recording_sessions SET status = 'ACTIVE', location_owner_token = :locationOwnerToken WHERE id = :sessionId AND status = 'STARTING' AND active_slot = 1")
    protected abstract suspend fun activateStartingRow(sessionId: Long, locationOwnerToken: String): Int
    @Query("UPDATE recording_sessions SET location_owner_token = :locationOwnerToken WHERE id = :sessionId AND status = 'ACTIVE' AND active_slot = 1")
    protected abstract suspend fun transferLocationOwnerRow(sessionId: Long, locationOwnerToken: String): Int
    @Query("UPDATE track_segments SET ended_at = :endedAt, end_reason = :endReason, open_slot = NULL WHERE id = :segmentId AND session_id = :sessionId AND open_slot = 1")
    protected abstract suspend fun closeSegmentRow(sessionId: Long, segmentId: Long, endedAt: Long, endReason: String): Int
    @Query("SELECT COUNT(*) FROM track_segments WHERE session_id = :sessionId AND open_slot = 1")
    protected abstract suspend fun openSegmentCount(sessionId: Long): Int
    @Query("SELECT COUNT(*) FROM track_segments WHERE id = :segmentId AND session_id = :sessionId AND open_slot = 1")
    protected abstract suspend fun isOpenSegment(sessionId: Long, segmentId: Long): Int
    @Query("SELECT COALESCE(MAX(sequence) + 1, 0) FROM track_segments WHERE session_id = :sessionId")
    protected abstract suspend fun nextSegmentSequence(sessionId: Long): Long
    @Query("SELECT COALESCE(MAX(sequence) + 1, 0) FROM track_points WHERE segment_id = :segmentId")
    protected abstract suspend fun nextPointSequence(segmentId: Long): Long

    suspend fun ensureMissingOperationCanUseKind(operationId: String, commandKind: String) {
        val sequence = operationId.locationSequenceOrNull() ?: return
        if (commandKind != LOCATION_COMMAND_KIND) {
            throw OperationIdCollisionException(
                "structured location operation id is reserved for LOCATION",
            )
        }
        val expiredThrough = expiredLocationSequence(sequence.runtimeToken) ?: return
        if (sequence.sequence <= expiredThrough) {
            throw ExpiredLocationOperationException(
                "location operation expired outside the supported receipt window",
            )
        }
    }

    private suspend fun replay(operationId: String, commandKind: String): RecordingOperationResult? {
        val receipt = receiptByOperationId(operationId)
        if (receipt == null) {
            ensureMissingOperationCanUseKind(operationId, commandKind)
            return null
        }
        if (receipt.commandKind != commandKind) { throw OperationIdCollisionException("operation id was already used for ".plus(receipt.commandKind)) }
        return RecordingOperationResult(receipt, replayed = true)
    }
    /**
     * Receipt replay is a command result, not a fresh state read. Capture the resulting projection
     * in the same transaction as the receipt so a later activation, stop, or recovery cannot alter
     * what an acknowledged operation reports.
     */
    private suspend fun record(receipt: RecordingOperationReceiptEntity): RecordingOperationResult {
        val session = activeSession() ?: reservedSession() ?: latestSession()
        val stored = receipt.copy(
            projectionSessionId = session?.id,
            projectionLifecycle = session?.status?.name,
            projectionOpenSegmentId = session?.let { openSegmentForSession(it.id)?.id },
            projectionAcceptedPointCount = session?.acceptedPointCount,
            projectionRejectedPointCount = session?.rejectedPointCount,
            projectionDistanceMeters = session?.distanceMeters,
        )
        insertReceiptRow(stored)
        pruneStructuredLocationReceipts(stored)
        return RecordingOperationResult(stored, replayed = false)
    }

    private suspend fun pruneStructuredLocationReceipts(receipt: RecordingOperationReceiptEntity) {
        if (receipt.commandKind != LOCATION_COMMAND_KIND) return
        receipt.operationId.locationSequenceOrNull() ?: return
        val sessionId = receipt.sessionId ?: return
        incrementStructuredLocationReceiptCount(sessionId)
        val pruneAtCount =
            LOCATION_RECEIPT_RETAIN_COUNT + LOCATION_RECEIPT_PRUNE_INTERVAL.toInt()
        if (requireNotNull(retainedStructuredLocationReceiptCount(sessionId)) < pruneAtCount) return
        val prunable = prunableStructuredLocationReceipts(
            sessionId,
            LOCATION_RECEIPT_RETAIN_COUNT,
        )
        if (prunable.isEmpty()) return
        prunable
            .map { operationId ->
                requireNotNull(operationId.locationSequenceOrNull()) {
                    "structured location receipt query returned a legacy operation id"
                }
            }
            .groupBy { it.runtimeToken }
            .forEach { (runtimeToken, sequences) ->
                advanceExpiredLocationSequence(
                    runtimeToken,
                    requireNotNull(sequences.maxOfOrNull { it.sequence }),
                )
            }
        prunable.forEach { operationId ->
            check(deleteReceiptByOperationId(operationId) == 1) {
                "location receipt changed during its pruning transaction"
            }
        }
        check(decrementStructuredLocationReceiptCount(sessionId, prunable.size) == 1) {
            "structured location receipt counter changed during its pruning transaction"
        }
    }

    /**
     * A committed user Stop outranks every later technical terminal command. Keeping this inside
     * the same Room transaction closes races from service recovery, start failure, and another
     * repository/process without relying on an earlier state read.
     */
    private suspend fun finishPendingStop(
        session: RecordingSessionEntity,
        operationId: String,
        commandKind: String,
        receiptOutcome: String,
        createdAt: Long,
    ): RecordingOperationResult? {
        if (session.status !in setOf(RecordingStatus.STARTING, RecordingStatus.ACTIVE)) return null
        val pendingStop = pendingStopRequestForSession(session.id) ?: return null
        val reason = pendingStop.outcome
            .removePrefix(RecordingReceiptOutcome.STOP_REQUESTED_PREFIX)
        check(reason.isNotBlank()) { "pending Stop reason is missing" }
        val open = openSegmentForSession(session.id)
        val terminalAt = maxOf(
            pendingStop.createdAt,
            session.startedAt,
            open?.startedAt ?: session.startedAt,
        )
        val duringStart = session.status == RecordingStatus.STARTING
        val endReason = if (duringStart) {
            "STOP_DURING_START:$reason"
        } else {
            "STOP:$reason"
        }
        if (open != null) {
            check(closeSegmentRow(session.id, open.id, terminalAt, endReason) == 1)
        }
        check(
            closeSessionRow(
                sessionId = session.id,
                expectedStatus = session.status,
                status = if (duringStart) RecordingStatus.INTERRUPTED else RecordingStatus.COMPLETED,
                endedAt = terminalAt,
                stopReason = endReason,
            ) == 1,
        )
        return record(
            RecordingOperationReceiptEntity(
                operationId,
                commandKind,
                receiptOutcome,
                session.id,
                open?.id,
                createdAt = createdAt,
            ),
        )
    }

    @Transaction
    open suspend fun prepareStartingReservation(session: RecordingSessionEntity, operationId: String, commandKind: String, outcome: String, createdAt: Long): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(session.id == 0L && session.status == RecordingStatus.STARTING)
        val sessionId = insertSessionRow(session)
        return record(RecordingOperationReceiptEntity(operationId, commandKind, outcome, sessionId = sessionId, createdAt = createdAt))
    }

    @Transaction
    open suspend fun activateStartingReservation(sessionId: Long, initialSegment: TrackSegmentEntity, operationId: String, commandKind: String, outcome: String, createdAt: Long): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(initialSegment.id == 0L && initialSegment.sessionId == sessionId && initialSegment.sequence == 0L)
        check(activateStartingRow(sessionId, operationId) == 1) { "session is not a starting reservation" }
        val segmentId = insertSegmentRow(initialSegment)
        return record(RecordingOperationReceiptEntity(operationId, commandKind, outcome, sessionId, segmentId, createdAt = createdAt))
    }

    @Transaction
    open suspend fun finishStartingReservation(sessionId: Long, terminalStatus: RecordingStatus, endedAt: Long, stopReason: String, operationId: String, commandKind: String, outcome: String, createdAt: Long): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(terminalStatus !in setOf(RecordingStatus.STARTING, RecordingStatus.ACTIVE))
        check(closeSessionRow(sessionId, RecordingStatus.STARTING, terminalStatus, endedAt, stopReason) == 1) { "session is not a starting reservation" }
        return record(RecordingOperationReceiptEntity(operationId, commandKind, outcome, sessionId = sessionId, createdAt = createdAt))
    }

    @Transaction
    open suspend fun persistAcceptedPoint(point: TrackPointEntity, distanceDeltaMeters: Double, operationId: String, commandKind: String, outcome: String, createdAt: Long): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(point.id == 0L && distanceDeltaMeters.isFinite() && distanceDeltaMeters >= 0.0)
        check(isOpenSegment(point.sessionId, point.segmentId) == 1) { "point segment is not open" }
        val storedPoint = point.copy(sequence = nextPointSequence(point.segmentId))
        val pointId = insertPointRow(storedPoint)
        check(incrementAcceptedSummary(storedPoint.sessionId, distanceDeltaMeters) == 1) { "point session is not active" }
        return record(RecordingOperationReceiptEntity(operationId, commandKind, outcome, storedPoint.sessionId, storedPoint.segmentId, pointId, createdAt))
    }

    @Transaction
    open suspend fun recordRejectedPoint(sessionId: Long, operationId: String, commandKind: String, outcome: String, createdAt: Long, closeSegmentId: Long? = null, closeAt: Long? = null, closeReason: String? = null): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        if (closeSegmentId != null) {
            require(closeAt != null && !closeReason.isNullOrBlank())
            check(closeSegmentRow(sessionId, closeSegmentId, closeAt, closeReason) == 1) { "open segment was not found" }
        }
        check(incrementRejectedSummary(sessionId) == 1) { "session is not active" }
        return record(RecordingOperationReceiptEntity(operationId, commandKind, outcome, sessionId, closeSegmentId, createdAt = createdAt))
    }

    @Transaction
    open suspend fun afterBreak(point: TrackPointEntity, newSegment: TrackSegmentEntity, oldSegmentId: Long?, oldEndedAt: Long?, oldEndReason: String?, distanceDeltaMeters: Double, operationId: String, commandKind: String, outcome: String, createdAt: Long): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(newSegment.id == 0L && newSegment.sessionId == point.sessionId && point.id == 0L)
        if (oldSegmentId != null) {
            require(oldEndedAt != null && !oldEndReason.isNullOrBlank())
            check(closeSegmentRow(point.sessionId, oldSegmentId, oldEndedAt, oldEndReason) == 1) { "old open segment was not found" }
        }
        val segmentId = insertSegmentRow(newSegment.copy(sequence = nextSegmentSequence(point.sessionId)))
        val storedPoint = point.copy(segmentId = segmentId, sequence = nextPointSequence(segmentId))
        val pointId = insertPointRow(storedPoint)
        check(incrementAcceptedSummary(storedPoint.sessionId, distanceDeltaMeters) == 1) { "session is not active" }
        return record(RecordingOperationReceiptEntity(operationId, commandKind, outcome, storedPoint.sessionId, segmentId, pointId, createdAt))
    }

    @Transaction
    open suspend fun stopRecording(sessionId: Long, segmentId: Long?, endedAt: Long, status: RecordingStatus, stopReason: String, segmentEndReason: String?, operationId: String, commandKind: String, outcome: String, createdAt: Long): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(status !in setOf(RecordingStatus.STARTING, RecordingStatus.ACTIVE) && stopReason.isNotBlank())
        if (segmentId != null) {
            require(!segmentEndReason.isNullOrBlank())
            check(closeSegmentRow(sessionId, segmentId, endedAt, segmentEndReason) == 1) { "open segment was not found" }
        } else check(openSegmentCount(sessionId) == 0) { "an open segment must be closed" }
        check(closeSessionRow(sessionId, RecordingStatus.ACTIVE, status, endedAt, stopReason) == 1) { "session is not active" }
        return record(RecordingOperationReceiptEntity(operationId, commandKind, outcome, sessionId, segmentId, createdAt = createdAt))
    }

    // Backward-compatible P2 convenience operations.
    @Transaction open suspend fun startSession(session: RecordingSessionEntity, initialSegment: TrackSegmentEntity): StartedRecording {
        require(session.id == 0L && session.status == RecordingStatus.ACTIVE && initialSegment.id == 0L && initialSegment.sessionId == 0L && initialSegment.sequence == 0L)
        val sessionId = insertSessionRow(session)
        val segmentId = insertSegmentRow(initialSegment.copy(sessionId = sessionId))
        return StartedRecording(sessionId, segmentId)
    }
    @Transaction open suspend fun appendAcceptedPoint(point: TrackPointEntity, distanceDeltaMeters: Double): Long {
        require(point.id == 0L && distanceDeltaMeters.isFinite() && distanceDeltaMeters >= 0.0)
        val pointId = insertPointRow(point)
        check(incrementAcceptedSummary(point.sessionId, distanceDeltaMeters) == 1) { "point session is not active" }
        return pointId
    }
    @Transaction open suspend fun recordRejectedPoint(sessionId: Long) { check(incrementRejectedSummary(sessionId) == 1) { "session is not active" } }
    @Transaction open suspend fun closeRecording(sessionId: Long, segmentId: Long, endedAt: Long, status: RecordingStatus, stopReason: String, segmentEndReason: String) {
        require(status !in setOf(RecordingStatus.STARTING, RecordingStatus.ACTIVE) && stopReason.isNotBlank() && segmentEndReason.isNotBlank())
        check(closeSegmentRow(sessionId, segmentId, endedAt, segmentEndReason) == 1) { "active segment was not found" }
        check(closeSessionRow(sessionId, RecordingStatus.ACTIVE, status, endedAt, stopReason) == 1) { "session is not active" }
    }

    @Query("SELECT * FROM recording_sessions WHERE status = 'ACTIVE' AND active_slot = 1") abstract suspend fun activeSession(): RecordingSessionEntity?
    @Query("SELECT * FROM recording_sessions WHERE status = 'STARTING' AND active_slot = 1") abstract suspend fun reservedSession(): RecordingSessionEntity?
    @Query("SELECT * FROM recording_sessions ORDER BY id DESC LIMIT 1") abstract suspend fun latestSession(): RecordingSessionEntity?
    @Query("SELECT * FROM recording_sessions WHERE id = :sessionId") abstract suspend fun sessionById(sessionId: Long): RecordingSessionEntity?
    @Transaction @Query("SELECT * FROM recording_sessions WHERE id = :sessionId") abstract suspend fun sessionWithSegments(sessionId: Long): RecordingSessionWithSegments?
    @Query("SELECT * FROM recording_sessions ORDER BY started_at DESC, id DESC")
    abstract fun observeHistorySessions(): Flow<List<RecordingSessionEntity>>
    @Transaction
    @Query("SELECT * FROM recording_sessions WHERE id = :sessionId")
    abstract fun observeHistorySessionWithSegments(sessionId: Long): Flow<RecordingSessionWithSegments?>
    @Transaction
    @Query("SELECT * FROM recording_sessions ORDER BY id DESC LIMIT 1")
    abstract fun observeLatestHistorySessionWithSegments(): Flow<RecordingSessionWithSegments?>
    @Query("SELECT outcome FROM recording_operation_receipts WHERE session_id = :sessionId ORDER BY created_at DESC, rowid DESC LIMIT 1")
    abstract fun observeLatestOperationOutcome(sessionId: Long): Flow<String?>
    @Query("SELECT * FROM track_points WHERE session_id = :sessionId ORDER BY id DESC LIMIT 1")
    abstract fun observeLatestAcceptedPoint(sessionId: Long): Flow<TrackPointEntity?>
    @Query(
        """
        SELECT
            p.id AS point_id,
            p.segment_id AS segment_id,
            s.sequence AS segment_sequence,
            p.sequence AS point_sequence,
            p.timestamp AS timestamp,
            p.latitude AS latitude,
            p.longitude AS longitude
        FROM track_points p
        INNER JOIN track_segments s ON s.id = p.segment_id AND s.session_id = p.session_id
        WHERE p.session_id = :sessionId
        ORDER BY s.sequence ASC, p.sequence ASC, p.id ASC
        """,
    )
    abstract fun observeHistoryAcceptedPoints(sessionId: Long): Flow<List<HistoryAcceptedTrackPointRow>>
    @Transaction
    @Query("""SELECT s.*, o.id AS segment_id, o.sequence AS segment_sequence, o.started_at AS segment_started_at, p.id AS point_id, p.sequence AS point_sequence, p.timestamp AS point_timestamp, COALESCE((SELECT MAX(sequence) + 1 FROM track_segments x WHERE x.session_id = s.id), 0) AS nextSegmentSequence, COALESCE((SELECT MAX(sequence) + 1 FROM track_points q WHERE q.segment_id = o.id), 0) AS nextPointSequence FROM recording_sessions s LEFT JOIN track_segments o ON o.session_id = s.id AND o.open_slot = 1 LEFT JOIN track_points p ON p.id = (SELECT z.id FROM track_points z JOIN track_segments zs ON zs.id = z.segment_id WHERE z.session_id = s.id ORDER BY zs.sequence DESC, z.sequence DESC, z.id DESC LIMIT 1) WHERE s.id = :sessionId""")
    abstract suspend fun recordingState(sessionId: Long): RecordingStateProjection?
    @Query("SELECT * FROM track_points WHERE segment_id = :segmentId ORDER BY sequence ASC") abstract suspend fun pointsForSegment(segmentId: Long): List<TrackPointEntity>
    @Query("SELECT * FROM track_points WHERE session_id = :sessionId AND latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east ORDER BY timestamp ASC, id ASC") abstract suspend fun pointsInBoundingBox(sessionId: Long, south: Double, west: Double, north: Double, east: Double): List<TrackPointEntity>
    @Query(
        """
        SELECT
            p.id AS point_id,
            p.session_id AS session_id,
            p.segment_id AS segment_id,
            s.sequence AS segment_sequence,
            p.sequence AS point_sequence,
            p.latitude AS latitude,
            p.longitude AS longitude
        FROM track_points p
        INNER JOIN track_segments s
            ON s.id = p.segment_id AND s.session_id = p.session_id
        WHERE p.latitude BETWEEN :south AND :north
            AND p.longitude BETWEEN :west AND :east
        ORDER BY p.session_id ASC, s.sequence ASC, p.sequence ASC, p.id ASC
        """,
    )
    abstract suspend fun fogPointsInLongitudeInterval(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<ViewportTrackPointRow>
    /** Room observes only `track_points`; lifecycle changes cannot emit a revision. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM track_points")
    abstract fun observeLatestPersistedPointId(): Flow<Long>

    @Query("SELECT COALESCE(MAX(id), 0) FROM track_points")
    abstract suspend fun latestPersistedPointId(): Long

    /**
     * Reads every canonical point after an id cursor in insertion order, with its direct same-
     * segment predecessor even when that predecessor is older than the cursor.
     */
    @Query(
        """
        SELECT
            p.id AS point_id,
            p.session_id AS session_id,
            p.segment_id AS segment_id,
            s.sequence AS segment_sequence,
            p.sequence AS point_sequence,
            p.timestamp AS point_timestamp,
            p.latitude AS point_latitude,
            p.longitude AS point_longitude,
            previous.id AS previous_point_id,
            previous.sequence AS previous_point_sequence,
            previous.timestamp AS previous_point_timestamp,
            previous.latitude AS previous_point_latitude,
            previous.longitude AS previous_point_longitude
        FROM track_points p
        INNER JOIN track_segments s ON s.id = p.segment_id AND s.session_id = p.session_id
        LEFT JOIN track_points previous ON previous.id = (
            SELECT candidate.id FROM track_points candidate
            WHERE candidate.session_id = p.session_id AND candidate.segment_id = p.segment_id
                AND (candidate.sequence < p.sequence OR (candidate.sequence = p.sequence AND candidate.id < p.id))
            ORDER BY candidate.sequence DESC, candidate.id DESC LIMIT 1
        )
        WHERE p.id > :afterPointId
        ORDER BY p.id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun persistedPointChangesAfter(
        afterPointId: Long,
        limit: Int,
    ): List<PersistedTrackPointChangeRow>

    @Query("SELECT COUNT(*) FROM recording_sessions") abstract suspend fun sessionCount(): Int
    @Query("SELECT COUNT(*) FROM track_segments") abstract suspend fun segmentCount(): Int
    @Query("SELECT COUNT(*) FROM track_points") abstract suspend fun pointCount(): Int
    @Query("DELETE FROM recording_sessions WHERE id = :sessionId") abstract suspend fun deleteSession(sessionId: Long): Int
    @Transaction
    open suspend fun executePrepareStart(
        startedAt: Long,
        createdAppVersion: String,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        activeSession()?.let { active ->
            return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.START_ALREADY_ACTIVE, active.id, createdAt = createdAt))
        }
        reservedSession()?.let { pending ->
            return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.START_ALREADY_PENDING, pending.id, createdAt = createdAt))
        }
        val sessionId = insertSessionRow(
            RecordingSessionEntity(startedAt = startedAt, status = RecordingStatus.STARTING, createdAppVersion = createdAppVersion),
        )
        return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.START_PREPARED, sessionId, createdAt = createdAt))
    }

    @Transaction
    open suspend fun executeActivateStart(
        sessionId: Long,
        activatedAt: Long,
        locationOwnerToken: String,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        val session = sessionById(sessionId)
        if (
            session?.status != RecordingStatus.STARTING ||
            pendingStopRequestForSession(sessionId) != null
        ) {
            return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.startNotPending(session?.status), sessionId = sessionId, createdAt = createdAt))
        }
        check(activateStartingRow(sessionId, locationOwnerToken) == 1)
        val segmentId = insertSegmentRow(
            TrackSegmentEntity(sessionId = sessionId, sequence = nextSegmentSequence(sessionId), startedAt = maxOf(activatedAt, session.startedAt), startReason = RecordingReceiptOutcome.SEGMENT_SESSION_START),
        )
        return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.START_ACTIVATED, sessionId, segmentId, createdAt = createdAt))
    }

    @Transaction
    open suspend fun executeFailStart(
        sessionId: Long,
        failedAt: Long,
        message: String?,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        val session = sessionById(sessionId)
        if (session != null) {
            finishPendingStop(
                session,
                operationId,
                commandKind,
                RecordingReceiptOutcome.START_FAILED,
                createdAt,
            )?.let { return it }
        }
        if (session?.status != RecordingStatus.STARTING) {
            return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.startFailureIgnored(session?.status), sessionId = sessionId, createdAt = createdAt))
        }
        val reason = message?.trim()?.takeIf { it.isNotEmpty() }?.let { "START_FAILED:".plus(it) } ?: "START_FAILED"
        check(closeSessionRow(sessionId, RecordingStatus.STARTING, RecordingStatus.FAILED_TO_START, maxOf(failedAt, session.startedAt), reason) == 1)
        return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.START_FAILED, sessionId, createdAt = createdAt))
    }

    /**
     * App-process startup repair. This deliberately considers only the still-reserved STARTING
     * row. Room serializes this transaction with service activation, so an activation that wins
     * remains ACTIVE and a reconciliation that wins leaves nothing for activation to acquire.
     */
    @Transaction
    open suspend fun executeReconcileStarting(
        reconciledAt: Long,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        val pending = reservedSession()
            ?: return record(
                RecordingOperationReceiptEntity(
                    operationId,
                    commandKind,
                    RecordingReceiptOutcome.NOTHING_TO_RECONCILE,
                    createdAt = createdAt,
                ),
            )
        finishPendingStop(
            pending,
            operationId,
            commandKind,
            RecordingReceiptOutcome.RECONCILED_PENDING_STOP,
            createdAt,
        )?.let { return it }
        val open = openSegmentForSession(pending.id)
        val terminalAt = maxOf(reconciledAt, pending.startedAt, open?.startedAt ?: pending.startedAt)
        if (open != null) {
            check(
                closeSegmentRow(
                    pending.id,
                    open.id,
                    terminalAt,
                    "APP_STARTUP_RECONCILIATION",
                ) == 1,
            )
        }
        check(
            closeSessionRow(
                pending.id,
                RecordingStatus.STARTING,
                RecordingStatus.FAILED_TO_START,
                terminalAt,
                "APP_STARTUP_RECONCILIATION",
            ) == 1,
        )
        return record(
            RecordingOperationReceiptEntity(
                operationId,
                commandKind,
                RecordingReceiptOutcome.RECONCILED_STARTING,
                pending.id,
                open?.id,
                createdAt = createdAt,
            ),
        )
    }

    /** Persists a local stale-delivery acknowledgement without evaluating coordinates or counters. */
    @Transaction
    open suspend fun executeLocationGuard(
        sessionId: Long,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        return record(
            RecordingOperationReceiptEntity(
                operationId = operationId,
                commandKind = commandKind,
                outcome = RecordingReceiptOutcome.LOCATION_SESSION_GUARD,
                sessionId = sessionId,
                createdAt = createdAt,
            ),
        )
    }
    @Transaction
    open suspend fun executeLocation(
        sessionId: Long,
        expectedOpenSegmentId: Long?,
        expectedLocationOwnerToken: String,
        point: TrackPointEntity?,
        acceptedKind: String?,
        breakReason: String?,
        distanceDeltaMeters: Double,
        recordedAt: Long,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(recordedAt >= 0L)
        require(distanceDeltaMeters.isFinite() && distanceDeltaMeters >= 0.0)
        val active = sessionById(sessionId)?.takeIf {
            it.status == RecordingStatus.ACTIVE &&
                it.activeSlot == ACTIVE_SESSION_SLOT &&
                it.locationOwnerToken == expectedLocationOwnerToken &&
                pendingStopRequestForSession(sessionId) == null
        } ?: return record(
            RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.LOCATION_SESSION_GUARD, sessionId, createdAt = createdAt),
        )
        var open = openSegmentForSession(sessionId)
        // The quality filter made its decision against this exact segment. A segment rotation by
        // another repository/process makes the decision stale; acknowledge it without counters.
        if (open?.id != expectedOpenSegmentId) {
            return record(
                RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.LOCATION_SESSION_GUARD, sessionId, createdAt = createdAt),
            )
        }
        if (point == null) {
            require(acceptedKind == null && distanceDeltaMeters == 0.0)
            if (breakReason != null && open != null) {
                check(closeSegmentRow(sessionId, open.id, maxOf(recordedAt, open.startedAt), breakReason) == 1)
            }
            check(incrementRejectedSummary(sessionId) == 1)
            return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.locationRejected(breakReason), sessionId, open?.id, createdAt = createdAt))
        }
        require(point.id == 0L && point.sessionId == sessionId && distanceDeltaMeters.isFinite() && distanceDeltaMeters >= 0.0)
        val kind = requireNotNull(acceptedKind)
        if (kind == RecordingReceiptOutcome.ACCEPTED_AFTER_BREAK) {
            require(!breakReason.isNullOrBlank())
            val transitionAt = maxOf(recordedAt, active.startedAt, open?.startedAt ?: active.startedAt)
            if (open != null) {
                check(closeSegmentRow(sessionId, open.id, transitionAt, breakReason) == 1)
            }
            val segmentId = insertSegmentRow(
                TrackSegmentEntity(
                    sessionId = sessionId,
                    sequence = nextSegmentSequence(sessionId),
                    startedAt = transitionAt,
                    startReason = "PROCESS_".plus(breakReason),
                ),
            )
            open = trackSegmentById(segmentId)
        }
        val destination = open ?: return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.LOCATION_SESSION_GUARD, sessionId, createdAt = createdAt))
        val storedPoint = point.copy(segmentId = destination.id, sequence = nextPointSequence(destination.id))
        val pointId = insertPointRow(storedPoint)
        check(incrementAcceptedSummary(sessionId, distanceDeltaMeters) == 1)
        return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.locationAccepted(kind, breakReason), sessionId, destination.id, pointId, createdAt))
    }

    /** Writes user Stop intent without ending the session; recovery consumes it before restart. */
    @Transaction
    open suspend fun executeRequestStop(
        sessionId: Long,
        requestedAt: Long,
        reason: String,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(commandKind == "REQUEST_STOP")
        require(requestedAt >= 0L && requestedAt == createdAt && reason.isNotBlank()) {
            "Stop request receipt time must equal the requested time"
        }
        val open = sessionById(sessionId)?.status in setOf(
            RecordingStatus.STARTING,
            RecordingStatus.ACTIVE,
        )
        return record(
            RecordingOperationReceiptEntity(
                operationId = operationId,
                commandKind = commandKind,
                outcome = if (open) {
                    RecordingReceiptOutcome.stopRequested(reason)
                } else {
                    RecordingReceiptOutcome.STOP_REQUEST_IGNORED
                },
                sessionId = sessionId,
                createdAt = createdAt,
            ),
        )
    }

    @Transaction
    open suspend fun executeStop(
        sessionId: Long,
        stoppedAt: Long,
        reason: String,
        terminalStatus: RecordingStatus,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        require(stoppedAt >= 0L && reason.isNotBlank())
        require(
            terminalStatus == RecordingStatus.COMPLETED ||
                terminalStatus == RecordingStatus.INTERRUPTED,
        )
        val terminalPrefix = if (terminalStatus == RecordingStatus.COMPLETED) "STOP:" else "INTERRUPT:"
        val expectedKind = if (terminalStatus == RecordingStatus.COMPLETED) "STOP" else "INTERRUPT"
        require(commandKind == expectedKind) { "terminal status and operation kind must agree" }
        val session = sessionById(sessionId)
            ?: return record(
                RecordingOperationReceiptEntity(
                    operationId,
                    commandKind,
                    RecordingReceiptOutcome.ALREADY_STOPPED,
                    sessionId,
                    createdAt = createdAt,
                ),
            )
        finishPendingStop(
            session,
            operationId,
            commandKind,
            RecordingReceiptOutcome.STOPPED,
            createdAt,
        )?.let { return it }
        when (session.status) {
            RecordingStatus.STARTING -> {
                val open = openSegmentForSession(sessionId)
                val terminalAt = maxOf(stoppedAt, session.startedAt, open?.startedAt ?: session.startedAt)
                val startPrefix = if (terminalStatus == RecordingStatus.COMPLETED) {
                    "STOP_DURING_START:"
                } else {
                    "INTERRUPT_DURING_START:"
                }
                if (open != null) {
                    check(closeSegmentRow(sessionId, open.id, terminalAt, startPrefix.plus(reason)) == 1)
                }
                check(
                    closeSessionRow(
                        sessionId,
                        RecordingStatus.STARTING,
                        RecordingStatus.INTERRUPTED,
                        terminalAt,
                        startPrefix.plus(reason),
                    ) == 1,
                )
                return record(
                    RecordingOperationReceiptEntity(
                        operationId,
                        commandKind,
                        RecordingReceiptOutcome.STOPPED,
                        sessionId,
                        createdAt = createdAt,
                    ),
                )
            }
            RecordingStatus.ACTIVE -> {
                val open = openSegmentForSession(sessionId)
                val terminalAt = maxOf(stoppedAt, session.startedAt, open?.startedAt ?: session.startedAt)
                if (open != null) {
                    check(closeSegmentRow(sessionId, open.id, terminalAt, terminalPrefix.plus(reason)) == 1)
                }
                check(
                    closeSessionRow(
                        sessionId,
                        RecordingStatus.ACTIVE,
                        terminalStatus,
                        terminalAt,
                        terminalPrefix.plus(reason),
                    ) == 1,
                )
                return record(
                    RecordingOperationReceiptEntity(
                        operationId,
                        commandKind,
                        RecordingReceiptOutcome.STOPPED,
                        sessionId,
                        createdAt = createdAt,
                    ),
                )
            }
            else -> return record(
                RecordingOperationReceiptEntity(
                    operationId,
                    commandKind,
                    RecordingReceiptOutcome.ALREADY_STOPPED,
                    sessionId,
                    createdAt = createdAt,
                ),
            )
        }
    }

    @Transaction
    open suspend fun executeRecovery(
        recoveredAt: Long,
        locationOwnerToken: String,
        operationId: String,
        commandKind: String,
        createdAt: Long,
    ): RecordingOperationResult {
        replay(operationId, commandKind)?.let { return it }
        val openSession = reservedSession() ?: activeSession()
        if (openSession != null) {
            finishPendingStop(
                openSession,
                operationId,
                commandKind,
                RecordingReceiptOutcome.RECOVERED_PENDING_STOP,
                createdAt,
            )?.let { return it }
        }
        reservedSession()?.let { pending ->
            val open = openSegmentForSession(pending.id)
            val terminalAt = maxOf(recoveredAt, pending.startedAt, open?.startedAt ?: pending.startedAt)
            if (open != null) {
                check(closeSegmentRow(pending.id, open.id, terminalAt, "PROCESS_RECOVERY_STARTING") == 1)
            }
            check(
                closeSessionRow(
                    pending.id,
                    RecordingStatus.STARTING,
                    RecordingStatus.FAILED_TO_START,
                    terminalAt,
                    "PROCESS_RECOVERY_STARTING",
                ) == 1,
            )
            return record(
                RecordingOperationReceiptEntity(
                    operationId,
                    commandKind,
                    RecordingReceiptOutcome.RECOVERED_STARTING,
                    pending.id,
                    createdAt = createdAt,
                ),
            )
        }
        val active = activeSession() ?: return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.NOTHING_TO_RECOVER, createdAt = createdAt))
        val open = openSegmentForSession(active.id)
        if (
            open?.startReason == RecordingReceiptOutcome.SEGMENT_PROCESS_RECOVERY &&
            active.locationOwnerToken == locationOwnerToken
        ) {
            return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.RECOVERED_ACTIVE_ALREADY, active.id, open.id, createdAt = createdAt))
        }
        check(transferLocationOwnerRow(active.id, locationOwnerToken) == 1)
        val transitionAt = maxOf(recoveredAt, active.startedAt, open?.startedAt ?: active.startedAt)
        if (open != null) {
            check(
                closeSegmentRow(
                    active.id,
                    open.id,
                    transitionAt,
                    RecordingReceiptOutcome.SEGMENT_PROCESS_RECOVERY,
                ) == 1,
            )
        }
        val replacementId = insertSegmentRow(
            TrackSegmentEntity(
                sessionId = active.id,
                sequence = nextSegmentSequence(active.id),
                startedAt = transitionAt,
                startReason = RecordingReceiptOutcome.SEGMENT_PROCESS_RECOVERY,
            ),
        )
        return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.RECOVERED_ACTIVE, active.id, replacementId, createdAt = createdAt))
    }

    @Query("SELECT * FROM track_segments WHERE id = :segmentId")
    protected abstract suspend fun trackSegmentById(segmentId: Long): TrackSegmentEntity

    @Query("SELECT * FROM track_segments WHERE session_id = :sessionId AND open_slot = 1 LIMIT 1")
    protected abstract suspend fun openSegmentForSession(sessionId: Long): TrackSegmentEntity?

}
