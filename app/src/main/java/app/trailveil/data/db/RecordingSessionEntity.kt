package app.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val LEGACY_DIRECT_START_LOCATION_OWNER_TOKEN = "LEGACY_DIRECT_START"

@Entity(
    tableName = "recording_sessions",
    indices = [
        Index(
            name = "index_recording_sessions_active_slot",
            value = ["active_slot"],
            unique = true,
        ),
    ],
)
data class RecordingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,
    val status: RecordingStatus,
    @ColumnInfo(name = "stop_reason")
    val stopReason: String? = null,
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double = 0.0,
    @ColumnInfo(name = "accepted_point_count")
    val acceptedPointCount: Long = 0,
    @ColumnInfo(name = "rejected_point_count")
    val rejectedPointCount: Long = 0,
    @ColumnInfo(name = "created_app_version")
    val createdAppVersion: String,
    @ColumnInfo(name = "active_slot")
    val activeSlot: Int? = if (
        status == RecordingStatus.STARTING || status == RecordingStatus.ACTIVE
    ) {
        ACTIVE_SESSION_SLOT
    } else {
        null
    },
    /** ACTIVE sessions are exclusively writable by this token; legacy direct DAO tests use a sentinel. */
    @ColumnInfo(name = "location_owner_token")
    val locationOwnerToken: String? = if (status == RecordingStatus.ACTIVE) {
        LEGACY_DIRECT_START_LOCATION_OWNER_TOKEN
    } else {
        null
    },
) {
    init {
        require(startedAt >= 0) { "startedAt must be non-negative" }
        require(endedAt == null || endedAt >= startedAt) {
            "endedAt must not precede startedAt"
        }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "distanceMeters must be finite and non-negative"
        }
        require(acceptedPointCount >= 0) { "acceptedPointCount must be non-negative" }
        require(rejectedPointCount >= 0) { "rejectedPointCount must be non-negative" }
        require(createdAppVersion.isNotBlank()) { "createdAppVersion must not be blank" }
        require(
            if (status == RecordingStatus.STARTING || status == RecordingStatus.ACTIVE) {
                activeSlot == ACTIVE_SESSION_SLOT && endedAt == null
            } else {
                activeSlot == null && endedAt != null
            },
        ) {
            "reserved status, active slot, and end timestamp are inconsistent"
        }
        require(
            when (status) {
                RecordingStatus.ACTIVE -> !locationOwnerToken.isNullOrBlank()
                else -> locationOwnerToken == null
            },
        ) { "location owner token is inconsistent with session status" }
    }
}