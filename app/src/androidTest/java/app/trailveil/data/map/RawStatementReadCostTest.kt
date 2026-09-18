package app.trailveil.data.map

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.test.filters.SdkSuppress
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.LatitudeBuckets
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoTaiwanAnchors
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Same DAO SQL, private synthetic database, Cursor versus API35 read-only raw statements. */
class RawStatementReadCostTest {
    @SdkSuppress(minSdkVersion = 35)
    @Test fun cursorAndRawStatementCosts() = runBlocking<Unit>(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("y13ReadCost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val mode = checkNotNull(args.getString("y13Mode")).also { check(it in listOf("cursor", "raw", "wired")) }
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("y13ExpectedApkHash"))
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        val container = (instrumentation.targetContext.applicationContext as TrailVeilApplication).appContainer
        val database = container.databaseForTesting()
        val db = database.openHelper.readableDatabase
        fun scalar(sql: String) = db.query(sql).use { check(it.moveToFirst()); it.getLong(0) }
        check(scalar("SELECT COUNT(*) FROM track_points") == 204_800L)
        check(scalar("SELECT COUNT(*) FROM recording_sessions") == 200L)
        val demo = db.query("SELECT COUNT(*) FROM track_points WHERE is_mock=1 AND session_id IN " +
            "(SELECT id FROM recording_sessions WHERE stop_reason=?)", arrayOf<Any>(DemoExplorationSeeder.DEMO_STOP_REASON))
            .use { check(it.moveToFirst()); it.getLong(0) }
        check(demo == 204_800L)
        val cursorReader = RoomViewportTrackPointReader(database.recordingDao())
        val wiredReader = RoomViewportTrackPointReader(database.recordingDao()).apply { enableWideRawReads(database) }
        val path = checkNotNull(db.path).also { check(it != ":memory:" && File(it).isFile) }
        val daoConstants = Class.forName("app.trailveil.data.db.RecordingDaoKt")
        fun daoSql(name: String) = daoConstants.getDeclaredField(name).apply { isAccessible = true }.get(null) as String
        val wideSql = daoSql("FOG_POINTS_IN_LONGITUDE_INTERVAL")
        val bucketSql = daoSql("FOG_POINTS_IN_BUCKETED_BOX")
        val anchor = DemoTaiwanAnchors.anchors().first()
        val cases = mapOf(
            "all" to ViewportBounds(-85.0, 85.0, -180.0, 180.0),
            "region" to ViewportBounds(20.0, 28.0, 118.0, 125.0),
            "small" to ViewportBounds(anchor.latitude - 0.04, anchor.latitude + 0.04, anchor.longitude - 0.04, anchor.longitude + 0.04),
            "empty" to ViewportBounds(-60.0, -50.0, -150.0, -140.0),
        )
        val order = (args.getString("y13CaseOrder") ?: "all,region,small,empty").split(',')
        check(order.sorted() == cases.keys.sorted())
        val beforeMax = scalar("SELECT COALESCE(MAX(id),0) FROM track_points")
        for (name in order) {
            val bounds = checkNotNull(cases[name]); val interval = bounds.longitudeIntervals().single()
            val buckets = LatitudeBuckets.covering(bounds.south, bounds.north)
            val sql = if (buckets == null) wideSql else bucketSql.replace(":latitudeBuckets", List(buckets.size) { "?" }.joinToString(","))
            val reference = cursorReader.read(bounds.south, bounds.north, interval)
            val expected = rowsHash(reference.sortedWith(compareById))
            val referenceOrder = rowsHash(reference)
            if (mode == "wired") {
                // Read-only check outside clocks: a suspending Room transaction must still work.
                database.withTransaction {
                    val transactionalRows = withContext(Dispatchers.IO) { wiredReader.read(bounds.south, bounds.north, interval) }
                    check(rowsHash(transactionalRows.sortedWith(compareById)) == expected)
                }
            }
            repeat(7) { sample ->
                val cpu = Process.getElapsedCpuTime(); val start = System.nanoTime()
                val rows = when (mode) {
                    "cursor" -> cursorReader.read(bounds.south, bounds.north, interval)
                    "wired" -> wiredReader.read(bounds.south, bounds.north, interval)
                    else -> rawRead(path, sql, bounds, buckets, currentCoroutineContext())
                }
                val wall = System.nanoTime() - start; val cpuElapsed = Process.getElapsedCpuTime() - cpu
                check(rows.size == reference.size && rowsHash(rows.sortedWith(compareById)) == expected)
                val row = JSONObject().put("case", name).put("sample", sample).put("mode", mode)
                    .put("rows", rows.size).put("wallNs", wall).put("processCpuMs", cpuElapsed)
                    .put("rowSha256", expected).put("sameRawOrder", rowsHash(rows) == referenceOrder)
                    .put("sqlSha256", hash(sql.toByteArray())).put("bucketCount", buckets?.size ?: 0)
                    .put("appSha256", appHash).put("testSha256", testHash).put("fingerprint", Build.FINGERPRINT)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Y13_READ_COST $row\n") })
            }
        }
        check(scalar("SELECT COUNT(*) FROM track_points") == 204_800L && scalar("SELECT COALESCE(MAX(id),0) FROM track_points") == beforeMax)
    }

    @RequiresApi(35)
    private fun rawRead(path: String, sql: String, bounds: ViewportBounds, buckets: IntArray?, context: CoroutineContext): List<ViewportTrackPoint> {
        context.ensureActive()
        return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            check(database.isReadOnly)
            database.beginTransactionReadOnly()
            try {
                database.createRawStatement(sql).use { statement ->
                    buckets?.forEachIndexed { index, bucket -> statement.bindLong(index + 1, bucket.toLong()) }
                    for ((name, value) in listOf(":south" to bounds.south, ":north" to bounds.north, ":west" to bounds.west, ":east" to bounds.east)) {
                        val index = statement.getParameterIndex(name); check(index > 0); statement.bindDouble(index, value)
                    }
                    val columns = (0 until statement.resultColumnCount).associateBy(statement::getColumnName)
                    val indices = listOf("point_id", "session_id", "segment_id", "segment_sequence", "point_sequence", "latitude", "longitude").map { checkNotNull(columns[it]) }
                    val rows = ArrayList<ViewportTrackPoint>()
                    while (true) {
                        if (rows.size % 256 == 0) context.ensureActive()
                        if (!statement.step()) break
                        rows += ViewportTrackPoint(statement.getColumnLong(indices[0]), statement.getColumnLong(indices[1]),
                            statement.getColumnLong(indices[2]), statement.getColumnLong(indices[3]), statement.getColumnLong(indices[4]),
                            statement.getColumnDouble(indices[5]), statement.getColumnDouble(indices[6]))
                    }
                    context.ensureActive(); rows
                }
            } finally { database.endTransaction() }
        }
    }
    private val compareById = Comparator<ViewportTrackPoint> { a, b -> a.pointId.compareTo(b.pointId) }
    private fun rowsHash(rows: List<ViewportTrackPoint>): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteBuffer.allocate(56)
        for (row in rows) {
            buffer.clear(); buffer.putLong(row.pointId); buffer.putLong(row.sessionId); buffer.putLong(row.segmentId)
            buffer.putLong(row.segmentSequence); buffer.putLong(row.pointSequence)
            buffer.putLong(row.latitude.toRawBits()); buffer.putLong(row.longitude.toRawBits()); digest.update(buffer.array())
        }
        return hex(digest.digest())
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
