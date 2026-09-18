package app.trailveil.data.map

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.db.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Owns a unique temporary DB; never opens or mutates the application's history. */
class ViewportRawPointMemoRoomTest {
    private val broad = ViewportBounds(25.0, 25.2, 121.0, 121.2)
    private val narrow = ViewportBounds(25.04, 25.06, 121.04, 121.06)
    private class Rig(val database: TrailVeilDatabase, val writer: TrailVeilDatabase, val sql: List<String>) {
        val dao = database.recordingDao()
        val reader = RoomViewportTrackPointReader(dao)
        val source = ViewportTrackDataSource(reader)
        lateinit var recording: StartedRecording
        suspend fun append(sequence: Long, longitude: Double) {
            writer.recordingDao().appendAcceptedPoint(TrackPointEntity(sessionId = recording.sessionId, segmentId = recording.segmentId,
                sequence = sequence, timestamp = 100L + sequence, latitude = 25.05, longitude = longitude, horizontalAccuracy = 5.0), 0.0)
        }
        fun pointQueries() = sql.count { it.contains("FROM track_points p") && it.contains("BETWEEN") }
        fun stamps() = sql.count { it.contains("COALESCE(MAX(id), 0)") }
    }
    private fun trial(body: suspend (Rig) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "u4-memo-test-${UUID.randomUUID()}.db"
        check(name != TrailVeilDatabase.DATABASE_NAME && !context.getDatabasePath(name).exists())
        val statements = CopyOnWriteArrayList<String>()
        fun open(capture: Boolean) = Room.databaseBuilder(context, TrailVeilDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(TrailVeilDatabase.invariantCallback)
            .setQueryCallback({ sql, _ -> if (capture) statements += sql }, Runnable::run).build()
        val database = open(true); val writer = open(false)
        try {
            runBlocking(Dispatchers.Default) {
                val rig = Rig(database, writer, statements)
                rig.recording = writer.recordingDao().startSession(RecordingSessionEntity(startedAt = 0,
                    status = RecordingStatus.ACTIVE, createdAppVersion = "u4-isolated-test"),
                    TrackSegmentEntity(sessionId = 0, sequence = 0, startedAt = 0, startReason = "SESSION_START"))
                body(rig)
            }
        } finally {
            writer.close(); database.close()
            check(context.deleteDatabase(name)) { "temporary test database cleanup failed" }
        }
    }

    @Test fun rawReuseKeepsRealSqlTuplesAndDoesNotReconnectAnOmittedMiddlePoint() = trial { rig ->
        rig.append(0, 121.045); rig.append(1, 121.15); rig.append(2, 121.055)
        val baseline = rig.source.read(narrow)
        assertEquals(listOf(1, 1), baseline.segments.map { it.points.size })
        val memo = ViewportRawPointMemo({ true }); val before = rig.pointQueries()
        try {
            withContext(memo) {
                rig.source.read(broad); memo.seal()
                assertEquals(baseline, rig.source.read(narrow))
                assertEquals(before + 1, rig.pointQueries())
            }
        } finally { withContext(NonCancellable) { memo.close() } }
    }

    @Test fun secondRoomConnectionAppendBetweenPhasesForcesARealFreshPointQuery() = trial { rig ->
        rig.append(0, 121.045)
        val memo = ViewportRawPointMemo({ true }); val before = rig.pointQueries()
        try {
            withContext(memo) {
                rig.source.read(broad); memo.seal()
                withContext(Dispatchers.IO) { rig.append(1, 121.055) }
                val actual = rig.source.read(narrow)
                assertEquals(2, actual.segments.single().points.size)
                assertEquals(before + 2, rig.pointQueries())
            }
        } finally { withContext(NonCancellable) { memo.close() } }
    }

    @Test fun sameMaxIdCanonicalChangeUsesReplacementTokenInsteadOfTrustingMax() = trial { rig ->
        rig.append(0, 121.045)
        val epoch = AtomicLong(0)
        val memo = ViewportRawPointMemo({ epoch.get() == 0L }); val before = rig.pointQueries()
        try {
            withContext(memo) {
                rig.source.read(broad); memo.seal()
                val oldMax = rig.dao.latestPersistedPointId()
                epoch.set(1)
                withContext(Dispatchers.IO) {
                    rig.writer.openHelper.writableDatabase.execSQL("UPDATE track_points SET longitude = ? WHERE id = ?", arrayOf(121.055, oldMax))
                }
                epoch.set(2)
                assertEquals(oldMax, rig.dao.latestPersistedPointId())
                val actual = rig.source.read(narrow)
                assertEquals(121.055, actual.segments.single().points.single().longitude, 0.0)
                assertEquals(before + 2, rig.pointQueries())
            }
        } finally { withContext(NonCancellable) { memo.close() } }
    }

    @Test fun coarseEmptyCaptureClosedAndOrdinaryReadersDoNotIssueMemoStamps() = trial { rig ->
        rig.append(0, 121.045)
        val memo = ViewportRawPointMemo({ true })
        val before = rig.stamps()
        withContext(memo) {
            rig.reader.readCoarseCells(broad.south, broad.north, broad.longitudeIntervals().single())
            memo.seal(); rig.source.read(narrow)
        }
        assertEquals(before, rig.stamps())
        memo.close()
        withContext(memo) { rig.source.read(narrow) }
        rig.source.read(narrow)
        assertEquals(before, rig.stamps())
    }
}
