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
        // The fog viewport read is a latitude/longitude box. Without this the box narrows nothing:
        // every settle scans the whole table and only the returned rows differ, which is why a
        // populated database was slow at zooms whose window was already small.
        // P4-036 replaced index_track_points_latitude_longitude: an equality-led key lets SQLite
        // constrain the longitude range too, which a range-led one cannot.
        Index(
            name = "index_track_points_lat_bucket_longitude",
            value = ["lat_bucket", "longitude"],
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
    /**
     * `P4-036`: the coarse latitude bucket the fog viewport read uses as an equality so the
     * longitude half of the box can still bound the scan. Derived from [latitude] and kept
     * consistent by a database trigger — a row whose bucket disagreed would silently vanish from
     * the fog read, which draws MORE fog and so passes every leak audit.
     */
    @ColumnInfo(name = "lat_bucket", defaultValue = "0")
    val latBucket: Int = UNSET_LAT_BUCKET,
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

    internal companion object {
        /**
         * What an unset bucket looks like — deliberately not a plausible one.
         *
         * The bucket is derived from [latitude], so no default can be right for an arbitrary point;
         * `RecordingDao.insertPointRow` — the one private method all four of that DAO's insert paths
         * call — derives it on the way in, and a database trigger repairs anything written around
         * the DAO. A row carrying this value would simply never match the fog read's equality set,
         * so it would vanish from the map rather than corrupt it, which is why the derivation is a
         * choke point and not a comment.
         */
        const val UNSET_LAT_BUCKET = 0
    }
}
