package io.github.jay890829.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable idempotency record. commandKind and outcome are stable storage strings. */
@Entity(
    tableName = "recording_operation_receipts",
    indices = [
        Index(name = "index_recording_operation_receipts_command_kind", value = ["command_kind"]),
        Index(name = "index_recording_operation_receipts_session_id", value = ["session_id"]),
        Index(name = "index_recording_operation_receipts_created_at", value = ["created_at"]),
    ],
)
data class RecordingOperationReceiptEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "command_kind") val commandKind: String,
    val outcome: String,
    @ColumnInfo(name = "session_id") val sessionId: Long? = null,
    @ColumnInfo(name = "segment_id") val segmentId: Long? = null,
    @ColumnInfo(name = "point_id") val pointId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "projection_session_id") val projectionSessionId: Long? = null,
    @ColumnInfo(name = "projection_lifecycle") val projectionLifecycle: String? = null,
    @ColumnInfo(name = "projection_open_segment_id") val projectionOpenSegmentId: Long? = null,
    @ColumnInfo(name = "projection_accepted_point_count") val projectionAcceptedPointCount: Long? = null,
    @ColumnInfo(name = "projection_rejected_point_count") val projectionRejectedPointCount: Long? = null,
    @ColumnInfo(name = "projection_distance_meters") val projectionDistanceMeters: Double? = null,
) {
    init {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(commandKind.isNotBlank()) { "commandKind must not be blank" }
        require(outcome.isNotBlank()) { "outcome must not be blank" }
        require(createdAt >= 0) { "createdAt must be non-negative" }
        require(sessionId == null || sessionId > 0) { "sessionId must be positive" }
        require(segmentId == null || segmentId > 0) { "segmentId must be positive" }
        require(pointId == null || pointId > 0) { "pointId must be positive" }
        val hasProjection = projectionSessionId != null
        require(hasProjection == (projectionLifecycle != null)) { "projection lifecycle must match projection session" }
        require(hasProjection == (projectionAcceptedPointCount != null)) { "projection accepted count must match projection session" }
        require(hasProjection == (projectionRejectedPointCount != null)) { "projection rejected count must match projection session" }
        require(hasProjection == (projectionDistanceMeters != null)) { "projection distance must match projection session" }
        require(projectionSessionId == null || projectionSessionId > 0) { "projectionSessionId must be positive" }
        require(projectionOpenSegmentId == null || projectionOpenSegmentId > 0) { "projectionOpenSegmentId must be positive" }
        require(projectionAcceptedPointCount == null || projectionAcceptedPointCount >= 0) { "projection accepted count must be non-negative" }
        require(projectionRejectedPointCount == null || projectionRejectedPointCount >= 0) { "projection rejected count must be non-negative" }
        require(projectionDistanceMeters == null || (projectionDistanceMeters.isFinite() && projectionDistanceMeters >= 0.0)) { "projection distance must be finite and non-negative" }
    }
}