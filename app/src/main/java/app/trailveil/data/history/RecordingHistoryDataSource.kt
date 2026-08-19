package app.trailveil.data.history

import kotlinx.coroutines.flow.Flow

enum class RecordingHistoryStatus {
    STARTING,
    ACTIVE,
    COMPLETED,
    INTERRUPTED,
    FAILED_TO_START,
}

data class RecordingHistorySession(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val status: RecordingHistoryStatus,
    val stopReason: String?,
    val distanceMeters: Double,
    val acceptedPointCount: Long,
    val rejectedPointCount: Long,
) {
    init {
        require(id > 0) { "id must be positive" }
        require(startedAt >= 0 && (endedAt == null || endedAt >= startedAt)) {
            "session times are invalid"
        }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "distanceMeters must be finite and non-negative"
        }
        require(acceptedPointCount >= 0 && rejectedPointCount >= 0) {
            "point counts must be non-negative"
        }
    }
}

data class RecordingHistorySegment(
    val id: Long,
    val sequence: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val startReason: String,
    val endReason: String?,
) {
    init {
        require(id > 0 && sequence >= 0) { "id and sequence are invalid" }
        require(startedAt >= 0 && (endedAt == null || endedAt >= startedAt)) {
            "segment times are invalid"
        }
        require(startReason.isNotBlank()) { "startReason must not be blank" }
    }
}

data class RecordingHistoryAcceptedPoint(
    val id: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val sequence: Long = 0,
) {
    init {
        require(id > 0 && sequence >= 0 && timestamp >= 0) { "id, sequence, and timestamp are invalid" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude is invalid" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude is invalid" }
    }
}

/** One persisted segment's accepted points, ordered by their canonical point sequence. */
data class RecordingHistoryAcceptedPointSegment(
    val segmentId: Long,
    val segmentSequence: Long,
    val points: List<RecordingHistoryAcceptedPoint>,
) {
    init {
        require(segmentId > 0 && segmentSequence >= 0) { "segment identity is invalid" }
        require(points.zipWithNext().all { (left, right) -> left.sequence < right.sequence }) {
            "points must be ordered by unique sequence"
        }
    }
}

/** Exact persisted outcome text, including outcomes such as `LOCATION_REJECTED_*`. */
@JvmInline
value class RecordingHistoryOperationOutcome(val value: String) {
    init {
        require(value.isNotBlank()) { "value must not be blank" }
    }
}

data class RecordingHistoryDetail(
    val session: RecordingHistorySession,
    val segments: List<RecordingHistorySegment>,
    val latestOperationOutcome: RecordingHistoryOperationOutcome?,
    val latestAcceptedPoint: RecordingHistoryAcceptedPoint?,
    val acceptedPointSegments: List<RecordingHistoryAcceptedPointSegment> = emptyList(),
) {
    init {
        require(segments.zipWithNext().all { (left, right) -> left.sequence < right.sequence }) {
            "segments must be ordered by unique sequence"
        }
        require(
            acceptedPointSegments.zipWithNext().all { (left, right) ->
                left.segmentSequence < right.segmentSequence
            },
        ) { "accepted-point segments must be ordered by unique sequence" }
    }
}

/** Bounded newest-session projection consumed by the always-visible recording entry route. */
data class RecordingLatestSessionSummary(
    val session: RecordingHistorySession,
    val latestOperationOutcome: RecordingHistoryOperationOutcome?,
    val latestAcceptedPoint: RecordingHistoryAcceptedPoint?,
    /**
     * Which app runtime durably owns an `ACTIVE` row, carried through as data.
     *
     * The comparison against the running process is made above this layer, so this contract keeps
     * its promise of having no service or runtime dependency: it reports who the database says owns
     * the row, not whether that owner still exists.
     */
    val locationOwnerToken: String?,
    /**
     * When [session] itself last recorded a point, unlike [latestAcceptedPoint], which is the newest
     * point across all sessions because it answers "where is the user". This one dates the session.
     */
    val sessionLastAcceptedPointAt: Long? = null,
)

/** Read-only persisted history contract; it intentionally has no service/runtime dependency. */
interface RecordingHistoryDataSource {
    /** All durable sessions, newest first by persisted start time then id. */
    fun sessions(): Flow<List<RecordingHistorySession>>

    /** A selected durable session, or null when its id does not exist. */
    fun sessionDetail(sessionId: Long): Flow<RecordingHistoryDetail?>

    /** One bounded newest-session row; never reconstructs segments or the accepted-point list. */
    fun latestSessionSummary(): Flow<RecordingLatestSessionSummary?>
}
