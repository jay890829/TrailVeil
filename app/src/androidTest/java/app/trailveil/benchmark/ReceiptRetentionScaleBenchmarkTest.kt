package app.trailveil.benchmark

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.recording.LOCATION_RECEIPT_PRUNE_INTERVAL
import app.trailveil.data.recording.LOCATION_RECEIPT_RETAIN_COUNT
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in SQLite retention measurement. Run with
 * `-Pandroid.testInstrumentationRunnerArguments.trailveilReceiptScale=true`.
 *
 * Each dataset is intentionally one bulk transaction: this measures the schema/retention write
 * cost and retained file overhead at 100k/1M canonical operations, not foreground-service
 * per-fix latency. The ordinary Room integration tests own transactional correctness.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptRetentionScaleBenchmarkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun boundedReceiptOverheadAtOneHundredThousandAndOneMillionOperations() {
        assumeTrue(
            "Receipt scale benchmark is opt-in; pass trailveilReceiptScale=true",
            InstrumentationRegistry.getArguments().getString(SCALE_ARGUMENT) == "true",
        )

        val results = POINT_COUNTS.map { pointCount ->
            val canonical = measure(pointCount, includeReceipts = false)
            val retained = measure(pointCount, includeReceipts = true)
            assertEquals(pointCount, canonical.pointCount)
            assertEquals(pointCount, retained.pointCount)
            assertTrue(retained.receiptCount in 1..MAX_RETAINED_RECEIPTS)
            assertTrue(retained.expiredThrough > 0L)
            assertTrue(
                "bounded receipt file overhead exceeded $MAX_RECEIPT_FILE_OVERHEAD_BYTES bytes",
                retained.fileBytes - canonical.fileBytes <= MAX_RECEIPT_FILE_OVERHEAD_BYTES,
            )
            ScaleResult(canonical, retained)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stream",
                    results.joinToString(
                        prefix = "TrailVeil receipt retention bulk-transaction benchmark: ",
                        separator = "; ",
                    ) { result -> result.statusLine() } + "\n",
                )
            },
        )
    }

    private fun measure(pointCount: Int, includeReceipts: Boolean): DatasetResult {
        val databaseName = "receipt-scale-${pointCount}-${includeReceipts}-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, TrailVeilDatabase::class.java, databaseName)
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()
        var receiptCount = 0
        var expiredThrough = 0L
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            database.runInTransaction {
                populateCanonicalSession(database.openHelper.writableDatabase, pointCount, includeReceipts)
            }
            val sqlite = database.openHelper.writableDatabase
            receiptCount = sqlite.count("recording_operation_receipts")
            expiredThrough = sqlite.longOrZero(
                "SELECT expired_through_sequence FROM recording_location_receipt_windows " +
                    "WHERE runtime_token = '$RUNTIME_TOKEN'",
            )
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            sqlite.execSQL("VACUUM")
        } finally {
            database.close()
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started
        val fileBytes = context.getDatabasePath(databaseName).length()
        val result = DatasetResult(
            pointCount = pointCount,
            includeReceipts = includeReceipts,
            elapsedMillis = elapsedNanos / NANOS_PER_MILLISECOND,
            fileBytes = fileBytes,
            receiptCount = receiptCount,
            expiredThrough = expiredThrough,
        )
        check(context.deleteDatabase(databaseName)) { "failed to remove receipt benchmark database" }
        return result
    }

    private fun populateCanonicalSession(
        sqlite: SupportSQLiteDatabase,
        pointCount: Int,
        includeReceipts: Boolean,
    ) {
        sqlite.execSQL(
            "INSERT INTO recording_sessions(" +
                "id, started_at, ended_at, status, stop_reason, distance_meters, " +
                "accepted_point_count, rejected_point_count, created_app_version, active_slot, " +
                "location_owner_token" +
                ") VALUES(1, 1, 2, 'COMPLETED', 'BENCHMARK', 0, $pointCount, 0, " +
                "'benchmark', NULL, NULL)",
        )
        sqlite.execSQL(
            "INSERT INTO track_segments(" +
                "id, session_id, sequence, started_at, ended_at, start_reason, end_reason, open_slot" +
                ") VALUES(1, 1, 0, 1, 2, 'BENCHMARK', 'BENCHMARK', NULL)",
        )
        val point = sqlite.compileStatement(
            "INSERT INTO track_points(" +
                "id, session_id, segment_id, sequence, timestamp, latitude, longitude, " +
                "horizontal_accuracy" +
                ") VALUES(?, 1, 1, ?, ?, 25.0, 121.0, 5.0)",
        )
        val receipt = if (includeReceipts) sqlite.compileStatement(
            "INSERT INTO recording_operation_receipts(" +
                "operation_id, command_kind, outcome, session_id, segment_id, point_id, created_at, " +
                "projection_session_id, projection_lifecycle, projection_open_segment_id, " +
                "projection_accepted_point_count, projection_rejected_point_count, " +
                "projection_distance_meters" +
                ") VALUES(?, 'LOCATION', 'LOCATION_ACCEPTED_CONTINUOUS_NONE', 1, 1, ?, ?, 1, " +
                "'ACTIVE', NULL, ?, 0, 0)",
        ) else null
        val incrementRetainedCount = if (includeReceipts) sqlite.compileStatement(
            "INSERT INTO recording_location_receipt_retention_states(" +
                "session_id, retained_receipt_count) VALUES(1, 1) " +
                "ON CONFLICT(session_id) DO UPDATE SET retained_receipt_count = " +
                "retained_receipt_count + 1",
        ) else null
        val retainedCount = if (includeReceipts) sqlite.compileStatement(
            "SELECT retained_receipt_count FROM recording_location_receipt_retention_states " +
                "WHERE session_id = 1",
        ) else null
        repeat(pointCount) { index ->
            val sequence = index.toLong() + 1L
            point.clearBindings()
            point.bindLong(1, sequence)
            point.bindLong(2, index.toLong())
            point.bindLong(3, sequence)
            point.executeInsert()
            if (receipt != null) {
                receipt.clearBindings()
                receipt.bindString(1, "location:$RUNTIME_TOKEN:$sequence")
                receipt.bindLong(2, sequence)
                receipt.bindLong(3, sequence)
                receipt.bindLong(4, sequence)
                receipt.executeInsert()
                requireNotNull(incrementRetainedCount).executeInsert()
                if (
                    requireNotNull(retainedCount).simpleQueryForLong() >=
                    LOCATION_RECEIPT_RETAIN_COUNT + LOCATION_RECEIPT_PRUNE_INTERVAL
                ) {
                    pruneReceipts(sqlite, sequence)
                }
            }
        }
    }

    private fun pruneReceipts(sqlite: SupportSQLiteDatabase, newestSequence: Long) {
        val expiredThrough = newestSequence - LOCATION_RECEIPT_RETAIN_COUNT
        if (expiredThrough <= 0L) return
        sqlite.execSQL(
            "INSERT INTO recording_location_receipt_windows(" +
                "runtime_token, expired_through_sequence" +
                ") VALUES('$RUNTIME_TOKEN', $expiredThrough) " +
                "ON CONFLICT(runtime_token) DO UPDATE SET expired_through_sequence = " +
                "MAX(expired_through_sequence, excluded.expired_through_sequence)",
        )
        sqlite.execSQL(
            "DELETE FROM recording_operation_receipts WHERE operation_id IN (" +
                "SELECT operation_id FROM recording_operation_receipts " +
                "WHERE session_id = 1 AND command_kind = 'LOCATION' " +
                "AND operation_id LIKE 'location:%' ORDER BY rowid DESC LIMIT -1 " +
                "OFFSET $LOCATION_RECEIPT_RETAIN_COUNT)",
        )
        sqlite.execSQL(
            "UPDATE recording_location_receipt_retention_states " +
                "SET retained_receipt_count = $LOCATION_RECEIPT_RETAIN_COUNT " +
                "WHERE session_id = 1",
        )
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.longOrZero(query: String): Long =
        query(query).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private data class DatasetResult(
        val pointCount: Int,
        val includeReceipts: Boolean,
        val elapsedMillis: Long,
        val fileBytes: Long,
        val receiptCount: Int,
        val expiredThrough: Long,
    )

    private data class ScaleResult(
        val canonical: DatasetResult,
        val retained: DatasetResult,
    ) {
        fun statusLine(): String =
            "operations=${canonical.pointCount} canonical=${canonical.fileBytes}B/${canonical.elapsedMillis}ms " +
                "bounded=${retained.fileBytes}B/${retained.elapsedMillis}ms " +
                "overhead=${retained.fileBytes - canonical.fileBytes}B " +
                "receipts=${retained.receiptCount} expiredThrough=${retained.expiredThrough}"
    }

    private companion object {
        const val SCALE_ARGUMENT = "trailveilReceiptScale"
        val POINT_COUNTS = listOf(100_000, 1_000_000)
        const val RUNTIME_TOKEN = "00000000-0000-0000-0000-000000000004"
        const val MAX_RETAINED_RECEIPTS =
            LOCATION_RECEIPT_RETAIN_COUNT + LOCATION_RECEIPT_PRUNE_INTERVAL.toInt() - 1
        const val MAX_RECEIPT_FILE_OVERHEAD_BYTES = 4L * 1024L * 1024L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
