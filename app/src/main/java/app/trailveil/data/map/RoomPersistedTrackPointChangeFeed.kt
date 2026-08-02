package app.trailveil.data.map

import app.trailveil.data.db.PersistedTrackPointChangeRow
import app.trailveil.data.db.RecordingDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Room adapter for canonical incremental point changes; Room entities never cross this boundary. */
internal class RoomPersistedTrackPointChangeFeed(
    private val dao: RecordingDao,
) : PersistedTrackPointChangeFeed {
    override suspend fun latestCursor(): PersistedPointCursor =
        PersistedPointCursor(dao.latestPersistedPointId())

    override fun revisionsAfter(cursor: PersistedPointCursor): Flow<PersistedPointRevision> =
        dao.observeLatestPersistedPointId()
            .filter { latestPointId -> latestPointId > cursor.pointId }
            .map { latestPointId -> PersistedPointRevision(PersistedPointCursor(latestPointId)) }

    override suspend fun readChangesAfter(
        cursor: PersistedPointCursor,
        limit: Int,
    ): List<PersistedTrackPointChange> =
        dao.persistedPointChangesAfter(
            afterPointId = cursor.pointId,
            limit = limit.also { require(it > 0) { "limit must be positive" } },
        ).map { row -> row.toChange() }

    private fun PersistedTrackPointChangeRow.toChange(): PersistedTrackPointChange {
        val point = PersistedTrackPoint(
            pointId = pointId,
            sessionId = sessionId,
            segmentId = segmentId,
            segmentSequence = segmentSequence,
            pointSequence = pointSequence,
            timestamp = pointTimestamp,
            latitude = pointLatitude,
            longitude = pointLongitude,
        )
        val previousPoint = previousPointId?.let { id ->
            PersistedTrackPoint(
                pointId = id,
                sessionId = sessionId,
                segmentId = segmentId,
                segmentSequence = segmentSequence,
                pointSequence = requireNotNull(previousPointSequence),
                timestamp = requireNotNull(previousPointTimestamp),
                latitude = requireNotNull(previousPointLatitude),
                longitude = requireNotNull(previousPointLongitude),
            )
        }
        return PersistedTrackPointChange(point = point, previousPoint = previousPoint)
    }
}
