package app.trailveil.data.history

import app.trailveil.data.db.HistoryAcceptedTrackPointRow
import app.trailveil.data.db.LatestRecordingSummaryRow
import app.trailveil.data.db.RecordingDao
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingSessionWithSegments
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Room-backed persisted history adapter; no service state or Room entity crosses this boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomRecordingHistoryDataSource(
    private val dao: RecordingDao,
) : RecordingHistoryDataSource {
    override fun sessions(): Flow<List<RecordingHistorySession>> =
        dao.observeHistorySessions().map { sessions -> sessions.map { session -> session.toHistory() } }

    override fun sessionDetail(sessionId: Long): Flow<RecordingHistoryDetail?> =
        dao.observeHistorySessionWithSegments(sessionId).flatMapLatest(::detailFlow)

    override fun latestSessionSummary(): Flow<RecordingLatestSessionSummary?> =
        dao.observeLatestRecordingSummary().map { row -> row?.toLatestSummary() }

    private fun detailFlow(
        projection: RecordingSessionWithSegments?,
    ): Flow<RecordingHistoryDetail?> {
        if (projection == null) return flowOf(null)
        val sessionId = projection.session.id
        return combine(
            dao.observeLatestOperationOutcome(sessionId),
            dao.observeLatestAcceptedPoint(sessionId),
            dao.observeHistoryAcceptedPoints(sessionId),
        ) { outcome, latestPoint, acceptedPoints ->
            projection.toHistoryDetail(outcome, latestPoint, acceptedPoints)
        }
    }

    private fun RecordingSessionWithSegments.toHistoryDetail(
        outcome: String?,
        latestPoint: TrackPointEntity?,
        acceptedPoints: List<HistoryAcceptedTrackPointRow>,
    ) = RecordingHistoryDetail(
        session = session.toHistory(),
        segments = segments
            .sortedWith(compareBy<TrackSegmentEntity>(TrackSegmentEntity::sequence).thenBy(TrackSegmentEntity::id))
            .map { segment -> segment.toHistory() },
        latestOperationOutcome = outcome?.let(::RecordingHistoryOperationOutcome),
        latestAcceptedPoint = latestPoint?.toHistory(),
        acceptedPointSegments = acceptedPoints.toHistorySegments(),
    )

    private fun RecordingSessionEntity.toHistory() = RecordingHistorySession(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        status = status.toHistory(),
        stopReason = stopReason,
        distanceMeters = distanceMeters,
        acceptedPointCount = acceptedPointCount,
        rejectedPointCount = rejectedPointCount,
    )

    private fun LatestRecordingSummaryRow.toLatestSummary(): RecordingLatestSessionSummary {
        val pointFields = listOf(
            latestPointId,
            latestPointSequence,
            latestPointTimestamp,
            latestPointLatitude,
            latestPointLongitude,
        )
        require(pointFields.all { it == null } || pointFields.none { it == null }) {
            "latest accepted point projection is partial"
        }
        val latestPoint = latestPointId?.let { pointId ->
            RecordingHistoryAcceptedPoint(
                id = pointId,
                sequence = requireNotNull(latestPointSequence),
                timestamp = requireNotNull(latestPointTimestamp),
                latitude = requireNotNull(latestPointLatitude),
                longitude = requireNotNull(latestPointLongitude),
            )
        }
        return RecordingLatestSessionSummary(
            session = session.toHistory(),
            latestOperationOutcome = latestOperationOutcome?.let(::RecordingHistoryOperationOutcome),
            latestAcceptedPoint = latestPoint,
            locationOwnerToken = session.locationOwnerToken,
            sessionLastAcceptedPointAt = ownLastPointTimestamp,
        )
    }

    private fun TrackSegmentEntity.toHistory() = RecordingHistorySegment(
        id = id,
        sequence = sequence,
        startedAt = startedAt,
        endedAt = endedAt,
        startReason = startReason,
        endReason = endReason,
    )

    private fun TrackPointEntity.toHistory() = RecordingHistoryAcceptedPoint(
        id = id,
        sequence = sequence,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
    )

    private fun List<HistoryAcceptedTrackPointRow>.toHistorySegments(): List<RecordingHistoryAcceptedPointSegment> =
        groupBy { point -> AcceptedPointSegmentKey(point.segmentId, point.segmentSequence) }
            .entries
            .sortedWith(
                compareBy<Map.Entry<AcceptedPointSegmentKey, List<HistoryAcceptedTrackPointRow>>> {
                    entry -> entry.key.segmentSequence
                }.thenBy { entry -> entry.key.segmentId },
            )
            .map { (key, points) ->
                RecordingHistoryAcceptedPointSegment(
                    segmentId = key.segmentId,
                    segmentSequence = key.segmentSequence,
                    points = points
                        .sortedWith(compareBy<HistoryAcceptedTrackPointRow>(HistoryAcceptedTrackPointRow::pointSequence).thenBy(HistoryAcceptedTrackPointRow::pointId))
                        .map { point -> point.toHistory() },
                )
            }

    private fun HistoryAcceptedTrackPointRow.toHistory() = RecordingHistoryAcceptedPoint(
        id = pointId,
        sequence = pointSequence,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
    )

    private fun RecordingStatus.toHistory() = when (this) {
        RecordingStatus.STARTING -> RecordingHistoryStatus.STARTING
        RecordingStatus.ACTIVE -> RecordingHistoryStatus.ACTIVE
        RecordingStatus.COMPLETED -> RecordingHistoryStatus.COMPLETED
        RecordingStatus.INTERRUPTED -> RecordingHistoryStatus.INTERRUPTED
        RecordingStatus.FAILED_TO_START -> RecordingHistoryStatus.FAILED_TO_START
    }

    private data class AcceptedPointSegmentKey(
        val segmentId: Long,
        val segmentSequence: Long,
    )
}
