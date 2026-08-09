package app.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Compact tombstone proving structured location operations at or below the watermark expired. */
@Entity(tableName = "recording_location_receipt_windows")
internal data class LocationReceiptWindowEntity(
    @PrimaryKey @ColumnInfo(name = "runtime_token") val runtimeToken: String,
    @ColumnInfo(name = "expired_through_sequence") val expiredThroughSequence: Long,
) {
    init {
        require(runtimeToken.isNotBlank()) { "runtimeToken must not be blank" }
        require(expiredThroughSequence > 0L) { "expiredThroughSequence must be positive" }
    }
}
