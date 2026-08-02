package app.trailveil.benchmark

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.trailveil.data.db.TrailVeilDatabase
import java.util.Random

internal object ScaleBenchmarkFixture {
    const val SEED = 20_260_801L
    const val SEGMENT_COUNT = 500

    fun populateCanonicalDataset(database: TrailVeilDatabase, pointCount: Int) {
        require(pointCount % SEGMENT_COUNT == 0)
        val db = database.openHelper.writableDatabase
        val pointsPerSegment = pointCount / SEGMENT_COUNT
        val random = Random(SEED)
        db.beginTransaction()
        try {
            val sessionId = db.insert(
                "recording_sessions",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("started_at", 1_000L)
                    put("ended_at", 1_000L + pointCount)
                    put("status", "COMPLETED")
                    put("stop_reason", "SCALE_BENCHMARK")
                    put("distance_meters", pointCount.toDouble())
                    put("accepted_point_count", pointCount.toLong())
                    put("rejected_point_count", 0L)
                    put("created_app_version", "scale-benchmark")
                    putNull("active_slot")
                    putNull("location_owner_token")
                },
            )
            check(sessionId > 0)
            val pointInsert = db.compileStatement(
                """
                INSERT INTO track_points(
                    session_id, segment_id, sequence, timestamp, latitude, longitude,
                    horizontal_accuracy, altitude, speed, bearing, is_mock
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
            repeat(SEGMENT_COUNT) { segmentSequence ->
                val segmentId = db.insert(
                    "track_segments",
                    SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("session_id", sessionId)
                        put("sequence", segmentSequence.toLong())
                        put("started_at", 1_000L + segmentSequence * pointsPerSegment)
                        put("ended_at", 1_001L + (segmentSequence + 1) * pointsPerSegment)
                        put("start_reason", "SCALE")
                        put("end_reason", "SCALE")
                        putNull("open_slot")
                    },
                )
                check(segmentId > 0)

                repeat(pointsPerSegment) { pointSequence ->
                    pointInsert.clearBindings()
                    pointInsert.bindLong(1, sessionId)
                    pointInsert.bindLong(2, segmentId)
                    pointInsert.bindLong(3, pointSequence.toLong())
                    pointInsert.bindLong(
                        4,
                        1_000L + segmentSequence * pointsPerSegment + pointSequence,
                    )
                    pointInsert.bindDouble(
                        5,
                        25.02 + (segmentSequence % 25) * 0.002 + random.nextDouble() * 0.0001,
                    )
                    pointInsert.bindDouble(
                        6,
                        121.47 + (segmentSequence / 25) * 0.004 + random.nextDouble() * 0.0001,
                    )
                    pointInsert.bindDouble(7, 5.0)
                    pointInsert.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
