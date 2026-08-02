package app.trailveil.data.map

import kotlinx.coroutines.flow.Flow

/** A durable `track_points.id` cursor. Zero denotes the empty-table baseline. */
@JvmInline
value class PersistedPointCursor(
    val pointId: Long,
) {
    init {
        require(pointId >= 0) { "pointId must be non-negative" }
    }
}

/** The latest canonical point id emitted after a committed `track_points` change. */
data class PersistedPointRevision(
    val latestCursor: PersistedPointCursor,
)

/** Provider-neutral representation of one canonical track point needed by fog rendering. */
data class PersistedTrackPoint(
    val pointId: Long,
    val sessionId: Long,
    val segmentId: Long,
    val segmentSequence: Long,
    val pointSequence: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(pointId > 0) { "pointId must be positive" }
        require(sessionId > 0) { "sessionId must be positive" }
        require(segmentId > 0) { "segmentId must be positive" }
        require(segmentSequence >= 0) { "segmentSequence must be non-negative" }
        require(pointSequence >= 0) { "pointSequence must be non-negative" }
        require(timestamp >= 0) { "timestamp must be non-negative" }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be finite and in [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be finite and in [-180, 180]"
        }
    }
}

/** One new point together with only its direct predecessor in the same persisted segment. */
data class PersistedTrackPointChange(
    val point: PersistedTrackPoint,
    val previousPoint: PersistedTrackPoint?,
) {
    init {
        previousPoint?.let { previous ->
            require(previous.sessionId == point.sessionId) { "previous point must share the session" }
            require(previous.segmentId == point.segmentId) { "previous point must share the segment" }
            require(previous.segmentSequence == point.segmentSequence) {
                "previous point must share the segment order"
            }
            require(previous.pointSequence < point.pointSequence) {
                "previous point must precede the point"
            }
        }
    }
}

/**
 * A storage-neutral feed for incremental fog invalidation. A caller can record [latestCursor] as
 * its process baseline, then collect [revisionsAfter] and drain [readChangesAfter] without
 * receiving the baseline again.
 */
interface PersistedTrackPointChangeFeed {
    suspend fun latestCursor(): PersistedPointCursor

    fun revisionsAfter(cursor: PersistedPointCursor): Flow<PersistedPointRevision>

    /**
     * Reads at most [limit] canonical changes after [cursor]. Callers must advance only to the
     * final point actually returned and continue paging until their target revision is reached.
     */
    suspend fun readChangesAfter(
        cursor: PersistedPointCursor,
        limit: Int = DEFAULT_CHANGE_PAGE_SIZE,
    ): List<PersistedTrackPointChange>

    companion object {
        const val DEFAULT_CHANGE_PAGE_SIZE = 256
    }
}
