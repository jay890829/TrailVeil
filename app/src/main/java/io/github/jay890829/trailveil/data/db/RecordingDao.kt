package io.github.jay890829.trailveil.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction

internal data class StartedRecording(
    val sessionId: Long,
    val segmentId: Long,
)

internal data class RecordingSessionWithSegments(
    @Embedded
    val session: RecordingSessionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "session_id",
    )
    val segments: List<TrackSegmentEntity>,
)

@Dao
internal abstract class RecordingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSessionRow(session: RecordingSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSegmentRow(segment: TrackSegmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPointRow(point: TrackPointEntity): Long

    @Query(
        """
        UPDATE recording_sessions
        SET accepted_point_count = accepted_point_count + 1,
            distance_meters = distance_meters + :distanceDeltaMeters
        WHERE id = :sessionId AND active_slot = 1
        """,
    )
    protected abstract suspend fun incrementAcceptedSummary(
        sessionId: Long,
        distanceDeltaMeters: Double,
    ): Int

    @Query(
        """
        UPDATE recording_sessions
        SET rejected_point_count = rejected_point_count + 1
        WHERE id = :sessionId AND active_slot = 1
        """,
    )
    protected abstract suspend fun incrementRejectedSummary(sessionId: Long): Int

    @Query(
        """
        UPDATE recording_sessions
        SET status = :status,
            ended_at = :endedAt,
            stop_reason = :stopReason,
            active_slot = NULL
        WHERE id = :sessionId AND active_slot = 1
        """,
    )
    protected abstract suspend fun closeSessionRow(
        sessionId: Long,
        status: RecordingStatus,
        endedAt: Long,
        stopReason: String,
    ): Int

    @Query(
        """
        UPDATE track_segments
        SET ended_at = :endedAt,
            end_reason = :endReason
        WHERE id = :segmentId
          AND session_id = :sessionId
          AND ended_at IS NULL
        """,
    )
    protected abstract suspend fun closeSegmentRow(
        sessionId: Long,
        segmentId: Long,
        endedAt: Long,
        endReason: String,
    ): Int

    @Transaction
    open suspend fun startSession(
        session: RecordingSessionEntity,
        initialSegment: TrackSegmentEntity,
    ): StartedRecording {
        require(session.id == 0L) { "A new session must not already have an id" }
        require(session.status == RecordingStatus.ACTIVE) { "A new recording must be active" }
        require(initialSegment.id == 0L) { "A new segment must not already have an id" }
        require(initialSegment.sessionId == 0L) {
            "The transaction assigns the new session id to its initial segment"
        }
        require(initialSegment.sequence == 0L) { "The initial segment sequence must be zero" }

        val sessionId = insertSessionRow(session)
        val segmentId = insertSegmentRow(initialSegment.copy(sessionId = sessionId))
        return StartedRecording(sessionId = sessionId, segmentId = segmentId)
    }

    @Transaction
    open suspend fun appendAcceptedPoint(
        point: TrackPointEntity,
        distanceDeltaMeters: Double,
    ): Long {
        require(point.id == 0L) { "A new point must not already have an id" }
        require(distanceDeltaMeters.isFinite() && distanceDeltaMeters >= 0.0) {
            "distanceDeltaMeters must be finite and non-negative"
        }

        val pointId = insertPointRow(point)
        check(incrementAcceptedSummary(point.sessionId, distanceDeltaMeters) == 1) {
            "The point session is not active"
        }
        return pointId
    }

    @Transaction
    open suspend fun recordRejectedPoint(sessionId: Long) {
        check(incrementRejectedSummary(sessionId) == 1) {
            "The rejected point session is not active"
        }
    }

    @Transaction
    open suspend fun closeRecording(
        sessionId: Long,
        segmentId: Long,
        endedAt: Long,
        status: RecordingStatus,
        stopReason: String,
        segmentEndReason: String,
    ) {
        require(status != RecordingStatus.ACTIVE) { "A closed session cannot remain active" }
        require(stopReason.isNotBlank()) { "stopReason must not be blank" }
        require(segmentEndReason.isNotBlank()) { "segmentEndReason must not be blank" }

        check(closeSessionRow(sessionId, status, endedAt, stopReason) == 1) {
            "The session is not active"
        }
        check(closeSegmentRow(sessionId, segmentId, endedAt, segmentEndReason) == 1) {
            "The active segment was not found"
        }
    }

    @Query("SELECT * FROM recording_sessions WHERE active_slot = 1")
    abstract suspend fun activeSession(): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions WHERE id = :sessionId")
    abstract suspend fun sessionById(sessionId: Long): RecordingSessionEntity?

    @Transaction
    @Query("SELECT * FROM recording_sessions WHERE id = :sessionId")
    abstract suspend fun sessionWithSegments(sessionId: Long): RecordingSessionWithSegments?

    @Query(
        """
        SELECT * FROM track_points
        WHERE segment_id = :segmentId
        ORDER BY sequence ASC
        """,
    )
    abstract suspend fun pointsForSegment(segmentId: Long): List<TrackPointEntity>

    @Query(
        """
        SELECT * FROM track_points
        WHERE session_id = :sessionId
          AND latitude BETWEEN :south AND :north
          AND longitude BETWEEN :west AND :east
        ORDER BY timestamp ASC, id ASC
        """,
    )
    abstract suspend fun pointsInBoundingBox(
        sessionId: Long,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM recording_sessions")
    abstract suspend fun sessionCount(): Int

    @Query("SELECT COUNT(*) FROM track_segments")
    abstract suspend fun segmentCount(): Int

    @Query("SELECT COUNT(*) FROM track_points")
    abstract suspend fun pointCount(): Int

    @Query("DELETE FROM recording_sessions WHERE id = :sessionId")
    abstract suspend fun deleteSession(sessionId: Long): Int
}