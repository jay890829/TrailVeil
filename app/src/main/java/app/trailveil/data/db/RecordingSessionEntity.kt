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
    /**
     * Which boot this session was started in, or null when the platform would not say.
     *
     * `V02-015`. The question it answers is "did the device restart under this exploration", and
     * until now that was answered by comparing the session's start time against wall clock minus
     * uptime. That is a clock comparison: a time sync after a reboot, or a manual change, moves the
     * computed boot instant, and a pre-reboot session then looks like a post-boot one - so the app
     * silently re-arms location on an exploration the user never asked to continue, which is exactly
     * what `PLAN.md` forbids.
     *
     * `Settings.Global.BOOT_COUNT` increases by one per boot and is not a clock, so equality answers
     * the question directly. Nullable because the platform may refuse it, and because every row
     * written before this column existed has none; a null is not treated as "same boot", it is
     * treated as not knowing, which is a third answer rather than a default to either side.
     */
    @ColumnInfo(name = "boot_id")
    val bootId: Long? = null,
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