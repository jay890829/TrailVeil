package app.trailveil.data.db

import android.os.Build
import android.os.Bundle
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import app.trailveil.data.map.*
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoExplorationTrack
import app.trailveil.harness.DemoTaiwanAnchors
import app.trailveil.map.fog.*
import app.trailveil.map.fogNativeGeometryEngine
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only common DAO probe on the dedicated synthetic DB; no Google SDK work is measured. */
class ViewportDaoStageProbeTest {
    private data class Read(val south: Double, val north: Double, val interval: LongitudeInterval)
    private data class Statement(val sql: String, val args: List<Any?>)

    @Test fun capturedDaoCursorAndMaterialisationCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("trailveilDaoStages") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        check(appHash == arguments.getString("trailveilExpectedApkHash"))
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        val appDatabase = (instrumentation.targetContext.applicationContext as TrailVeilApplication).appContainer.databaseForTesting()
        val initialHash = runBlocking(Dispatchers.IO) { fixtureHash(appDatabase) }
        val capture = AtomicBoolean(false)
        val statements = CopyOnWriteArrayList<Statement>()
        // A second Room connection to the existing verified fixture, with a synchronous capture hook.
        // No seeding, clearing, schema/index mutation or DB export is performed.
        val database = Room.databaseBuilder(instrumentation.targetContext, TrailVeilDatabase::class.java, TrailVeilDatabase.DATABASE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql, args ->
                if (capture.get() && sql.trimStart().startsWith("SELECT") && sql.contains("FROM track_points p")) {
                    statements += Statement(sql, args.toList())
                }
            }, Runnable::run).build()
        try {
            runBlocking(Dispatchers.Default) {
                check(fixtureHash(database) == initialHash)
                val anchor = DemoTaiwanAnchors.anchors().first()
                val point = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).last().last()
                for (zoom in listOf(12, 14, 16)) for (padding in listOf(1, 2)) {
                    val requests = ArrayList<Read>()
                    val room = RoomViewportTrackPointReader(database.recordingDao())
                    val reader = object : ViewportTrackPointReader by room {
                        override suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> {
                            requests += Read(south, north, interval)
                            return room.read(south, north, interval)
                        }
                    }
                    val center = GeoPoint(point.latitude, point.longitude)
                    val layout = FogPocMosaic.layout(FogViewportTileGrid.around(center, zoom, FogRenderVersions.CURRENT, padding), 256)
                        .anchoredNear(center.longitude)
                    val coordinator = FogViewportCoordinator(ViewportTrackDataSource(reader),
                        FogTilePipeline(FogMemoryTileCache(1), null) { _, _ -> error("native-only discovery invoked raster") },
                        nativeGeometryEngine = checkNotNull(fogNativeGeometryEngine()))
                    check(coordinator.renderNativeGeometry(layout.bounds) != null)
                    statements.clear(); capture.set(true)
                    val expected = requests.map { daoRead(database.recordingDao(), it) }
                    capture.set(false)
                    val captured = statements.toList()
                    check(captured.size == requests.size && captured.isNotEmpty())
                    val sql = database.openHelper.readableDatabase
                    val plans = captured.map { statement ->
                        sql.query("EXPLAIN QUERY PLAN " + statement.sql, statement.args.toTypedArray()).use { cursor ->
                            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1)) }.joinToString(" | ")
                        }
                    }
                    val expectedHash = rowsHash(expected)
                    val record = JSONObject().put("case", "z${zoom}p$padding").put("appSha256", appHash)
                        .put("testSha256", testHash).put("fixtureSha256", initialHash).put("fingerprint", Build.FINGERPRINT)
                        .put("queries", captured.size).put("rowCounts", JSONArray(expected.map { it.size }))
                        .put("rowsSha256", expectedHash).put("plans", JSONArray(plans))
                        .put("sqlSha256", JSONArray(captured.map { hash(it.sql.toByteArray()) }))
                    val daoTimes = JSONArray(); val rawTimes = JSONArray(); val readerTimes = JSONArray()
                    val drainTimes = JSONArray(); val fieldsTimes = JSONArray()
                    val expectedPoints = expected.map { rows -> rows.map { row ->
                        ViewportTrackPoint(row.pointId, row.sessionId, row.segmentId, row.segmentSequence, row.pointSequence, row.latitude, row.longitude)
                    } }
                    val expectedFolds = expected.map { rows ->
                        var fold = 1L
                        rows.forEach { row ->
                            for (value in longArrayOf(row.pointId, row.sessionId, row.segmentId, row.segmentSequence,
                                row.pointSequence, row.latitude.toBits(), row.longitude.toBits())) fold = fold * 31L + value
                        }
                        rows.size to fold
                    }
                    val modeOffset = arguments.getString("trailveilModeOffset")?.toInt() ?: 0
                    repeat(7) { sample ->
                        // Coarse whole-operation timing: per-row nanoTime overhead invalidated v2 phase totals.
                        for (offset in 0..4) when ((sample + offset + modeOffset) % 5) {
                            0 -> {
                                val start = System.nanoTime()
                                val actual = requests.map { daoRead(database.recordingDao(), it) }
                                daoTimes.put(System.nanoTime() - start)
                                check(actual == expected && rowsHash(actual) == expectedHash)
                            }
                            1 -> {
                                val start = System.nanoTime()
                                val actual = captured.map { rawRead(database, it) }
                                rawTimes.put(System.nanoTime() - start)
                                check(actual == expected && rowsHash(actual) == expectedHash)
                            }
                            2 -> {
                                val start = System.nanoTime()
                                val actual = requests.map { room.read(it.south, it.north, it.interval) }
                                readerTimes.put(System.nanoTime() - start)
                                check(actual == expectedPoints)
                                check(actual.flatten().map { it.latitude.toBits() to it.longitude.toBits() } ==
                                    expectedPoints.flatten().map { it.latitude.toBits() to it.longitude.toBits() })
                            }
                            else -> {
                                val fields = (sample + offset + modeOffset) % 5 == 4
                                val start = System.nanoTime()
                                val actual = captured.map { drainCursor(database, it, fields) }
                                val elapsed = System.nanoTime() - start
                                check(actual.map { it.first } == expected.map { it.size })
                                if (fields) { check(actual == expectedFolds); fieldsTimes.put(elapsed) }
                                else drainTimes.put(elapsed)
                            }
                        }
                    }
                    record.put("daoNs", daoTimes).put("cursorPlainNs", rawTimes).put("readerNs", readerTimes)
                        .put("cursorDrainNs", drainTimes).put("cursorFieldsNs", fieldsTimes).put("modeOffset", modeOffset)
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "DAO_STAGES $record\n") })
                }
                check(fixtureHash(database) == initialHash) { "canonical fixture changed" }
            }
        } finally { capture.set(false); database.close() }
    }

    private suspend fun daoRead(dao: RecordingDao, read: Read): List<ViewportTrackPointRow> {
        val buckets = LatitudeBuckets.covering(read.south, read.north)
        return if (buckets == null) dao.fogPointsInLongitudeInterval(read.south, read.interval.west, read.north, read.interval.east)
            else dao.fogPointsInBucketedBox(buckets, read.south, read.interval.west, read.north, read.interval.east)
    }

    private fun rawRead(database: TrailVeilDatabase, statement: Statement): List<ViewportTrackPointRow> {
        val rows = ArrayList<ViewportTrackPointRow>()
        database.openHelper.readableDatabase.query(statement.sql, statement.args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) rows += ViewportTrackPointRow(cursor.getLong(0), cursor.getLong(1),
                cursor.getLong(2), cursor.getLong(3), cursor.getLong(4), cursor.getDouble(5), cursor.getDouble(6))
        }
        return rows
    }

    // Drain includes SQL execution and CursorWindow work. Fields additionally includes seven getters
    // AND the checksum arithmetic; differences are experimental costs, not exact stage attribution.
    private fun drainCursor(database: TrailVeilDatabase, statement: Statement, fields: Boolean): Pair<Int, Long> {
        var rows = 0
        var fold = 1L
        database.openHelper.readableDatabase.query(statement.sql, statement.args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows++
                if (fields) {
                    for (column in 0..4) fold = fold * 31L + cursor.getLong(column)
                    fold = fold * 31L + cursor.getDouble(5).toBits()
                    fold = fold * 31L + cursor.getDouble(6).toBits()
                }
            }
        }
        return rows to fold
    }
    private fun rowsHash(groups: List<List<ViewportTrackPointRow>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(56)
        groups.forEach { rows ->
            digest.update(ByteBuffer.allocate(4).putInt(rows.size).array())
            rows.forEach { row ->
                buffer.clear(); buffer.putLong(row.pointId).putLong(row.sessionId).putLong(row.segmentId)
                    .putLong(row.segmentSequence).putLong(row.pointSequence).putDouble(row.latitude).putDouble(row.longitude)
                digest.update(buffer.array())
            }
        }
        return hex(digest.digest())
    }

    private fun fixtureHash(database: TrailVeilDatabase): String {
        val sql = database.openHelper.readableDatabase
        val count = sql.query("SELECT COUNT(*) FROM track_points").use { check(it.moveToFirst()); it.getLong(0) }
        val demo = sql.query("SELECT COUNT(*) FROM track_points WHERE is_mock=1 AND session_id IN " +
            "(SELECT id FROM recording_sessions WHERE stop_reason=?)", arrayOf<Any>(DemoExplorationSeeder.DEMO_STOP_REASON))
            .use { check(it.moveToFirst()); it.getLong(0) }
        check(count == 204_800L && demo == count) { "requires dedicated synthetic fixture" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(80)
        sql.query("SELECT p.id,p.session_id,p.segment_id,s.sequence,p.sequence,p.timestamp,p.latitude,p.longitude,p.horizontal_accuracy,p.is_mock " +
            "FROM track_points p JOIN track_segments s ON s.id=p.segment_id AND s.session_id=p.session_id ORDER BY p.id").use { cursor ->
            while (cursor.moveToNext()) {
                buffer.clear()
                for (column in 0..5) buffer.putLong(cursor.getLong(column))
                for (column in 6..8) buffer.putDouble(cursor.getDouble(column))
                buffer.putLong(cursor.getLong(9)); digest.update(buffer.array())
            }
        }
        return hex(digest.digest())
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

