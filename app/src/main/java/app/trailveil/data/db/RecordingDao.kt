package app.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import app.trailveil.data.recording.OperationIdCollisionException
import kotlinx.coroutines.flow.Flow

internal data class StartedRecording(val sessionId: Long, val segmentId: Long)
internal data class RecordingOperationResult(val receipt: RecordingOperationReceiptEntity, val replayed: Boolean)
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
    const val LOCATION_SESSION_GUARD = "LOCATION_SESSION_GUARD"
    const val STOPPED = "STOPPED"
    const val ALREADY_STOPPED = "ALREADY_STOPPED"
    const val RECOVERED_STARTING = "RECOVERED_STARTING"
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
}
@Dao
internal abstract class RecordingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertSessionRow(session: RecordingSessionEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertSegmentRow(segment: TrackSegmentEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertPointRow(point: TrackPointEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertReceiptRow(receipt: RecordingOperationReceiptEntity)

    @Query("SELECT * FROM recording_operation_receipts WHERE operation_id = :operationId")
    abstract suspend fun receiptByOperationId(operationId: String): RecordingOperationReceiptEntity?

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

    private suspend fun replay(operationId: String, commandKind: String): RecordingOperationResult? {
        val receipt = receiptByOperationId(operationId) ?: return null
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
        return RecordingOperationResult(stored, replayed = false)
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
        """,
    )
    abstract suspend fun persistedPointChangesAfter(afterPointId: Long): List<PersistedTrackPointChangeRow>

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
        if (session?.status != RecordingStatus.STARTING) {
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
        if (session?.status != RecordingStatus.STARTING) {
            return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.startFailureIgnored(session?.status), sessionId = sessionId, createdAt = createdAt))
        }
        val reason = message?.trim()?.takeIf { it.isNotEmpty() }?.let { "START_FAILED:".plus(it) } ?: "START_FAILED"
        check(closeSessionRow(sessionId, RecordingStatus.STARTING, RecordingStatus.FAILED_TO_START, maxOf(failedAt, session.startedAt), reason) == 1)
        return record(RecordingOperationReceiptEntity(operationId, commandKind, RecordingReceiptOutcome.START_FAILED, sessionId, createdAt = createdAt))
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
                it.locationOwnerToken == expectedLocationOwnerToken
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