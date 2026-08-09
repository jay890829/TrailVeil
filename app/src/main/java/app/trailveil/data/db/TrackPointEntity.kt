package app.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackSegmentEntity::class,
            parentColumns = ["id", "session_id"],
            childColumns = ["segment_id", "session_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            name = "index_track_points_segment_id_sequence",
            value = ["segment_id", "sequence"],
            unique = true,
        ),
        Index(
            name = "index_track_points_session_id_timestamp",
            value = ["session_id", "timestamp"],
        ),
        Index(
            name = "index_track_points_session_id_id",
            value = ["session_id", "id"],
        ),
        Index(
            name = "index_track_points_segment_id_session_id",
            value = ["segment_id", "session_id"],
        ),
    ],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "segment_id")
    val segmentId: Long,
    val sequence: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "horizontal_accuracy")
    val horizontalAccuracy: Double,
    val altitude: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    @ColumnInfo(name = "is_mock")
    val isMock: Boolean? = null,
) {
    init {
        require(sequence >= 0) { "sequence must be non-negative" }
        require(timestamp >= 0) { "timestamp must be non-negative" }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be finite and in [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be finite and in [-180, 180]"
        }
        require(horizontalAccuracy.isFinite() && horizontalAccuracy >= 0.0) {
            "horizontalAccuracy must be finite and non-negative"
        }
        require(altitude == null || altitude.isFinite()) { "altitude must be finite" }
        require(speed == null || speed.isFinite() && speed >= 0.0) {
            "speed must be finite and non-negative"
        }
        require(bearing == null || bearing.isFinite() && bearing >= 0.0 && bearing < 360.0) {
            "bearing must be finite and in [0, 360)"
        }
    }
}
