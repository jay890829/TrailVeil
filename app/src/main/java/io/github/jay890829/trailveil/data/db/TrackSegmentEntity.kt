package io.github.jay890829.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_segments",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            name = "index_track_segments_session_id_sequence",
            value = ["session_id", "sequence"],
            unique = true,
        ),
        Index(
            name = "index_track_segments_id_session_id",
            value = ["id", "session_id"],
            unique = true,
        ),
    ],
)
data class TrackSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    val sequence: Long,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,
    @ColumnInfo(name = "start_reason")
    val startReason: String,
    @ColumnInfo(name = "end_reason")
    val endReason: String? = null,
) {
    init {
        require(sequence >= 0) { "sequence must be non-negative" }
        require(startedAt >= 0) { "startedAt must be non-negative" }
        require(endedAt == null || endedAt >= startedAt) {
            "endedAt must not precede startedAt"
        }
        require(startReason.isNotBlank()) { "startReason must not be blank" }
        require((endedAt == null) == (endReason == null)) {
            "endedAt and endReason must both be present or both be absent"
        }
    }
}