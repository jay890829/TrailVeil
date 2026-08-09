package app.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/** O(1) retained structured-receipt count used to schedule per-session batch pruning.
 *
 * This intentionally has no session foreign key: stale-location guard receipts preserve their
 * caller-supplied missing session ID, just like recording_operation_receipts.
 */
@Entity(
    tableName = "recording_location_receipt_retention_states",
    primaryKeys = ["session_id"],
)
internal data class LocationReceiptRetentionStateEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "retained_receipt_count") val retainedReceiptCount: Int,
) {
    init {
        require(sessionId > 0L) { "sessionId must be positive" }
        require(retainedReceiptCount >= 0) { "retainedReceiptCount must not be negative" }
    }
}
