package app.trailveil.data.map

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrailVeilDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class Api35ViewportRawReaderTest {
    @Test fun emptyAndNonemptyRawRowsPreserveEveryColumnWithoutSkippingTheFirst() = runBlocking<Unit> {
        withDatabase { database, _, sql ->
            val dao = database.recordingDao()
            val reader = RoomViewportTrackPointReader(dao).apply { enableWideRawReads(database) }
            sql.clear()
            assertEquals(emptyList<ViewportTrackPoint>(), reader.read(-90.0, 90.0, WORLD))
            assertFalse("empty result must exercise raw path", sql.any(::isWideSql))
            val session = dao.startSession(
                RecordingSessionEntity(startedAt = 1000L, status = RecordingStatus.ACTIVE, createdAppVersion = "raw-reader-columns"),
                TrackSegmentEntity(sessionId = 0L, sequence = 0L, startedAt = 1000L, startReason = "SESSION_START"))
            val expected = (0L..1L).map { sequence ->
                val latitude = 20.125 + sequence * 0.0001
                val longitude = 120.25 + sequence * 0.0002
                val id = dao.appendAcceptedPoint(TrackPointEntity(sessionId = session.sessionId, segmentId = session.segmentId,
                    sequence = sequence, timestamp = 1000L + sequence * 1000L, latitude = latitude, longitude = longitude,
                    horizontalAccuracy = 5.0, isMock = true), 0.0)
                ViewportTrackPoint(id, session.sessionId, session.segmentId, 0L, sequence, latitude, longitude)
            }
            sql.clear()
            val actual = reader.read(-90.0, 90.0, WORLD).sortedBy { it.pointSequence }
            assertEquals(expected, actual)
            expected.zip(actual).forEach { (wanted, row) ->
                assertEquals(wanted.latitude.toRawBits(), row.latitude.toRawBits())
                assertEquals(wanted.longitude.toRawBits(), row.longitude.toRawBits())
            }
            assertFalse("nonempty result must exercise raw path", sql.any(::isWideSql))
        }
    }

    @Test fun outerTransactionKeepsItsUncommittedSnapshotAcrossDispatcherAndRollsBack() = runBlocking<Unit> {
        withDatabase { database, _, sql ->
            val dao = database.recordingDao()
            val session = dao.startSession(
                RecordingSessionEntity(startedAt = 1000L, status = RecordingStatus.ACTIVE, createdAppVersion = "raw-reader-test"),
                TrackSegmentEntity(sessionId = 0L, sequence = 0L, startedAt = 1000L, startReason = "SESSION_START"))
            fun point(sequence: Long) = TrackPointEntity(sessionId = session.sessionId, segmentId = session.segmentId,
                sequence = sequence, timestamp = 1000L + sequence * 1000L, latitude = 20.0, longitude = 120.0,
                horizontalAccuracy = 5.0, isMock = true)
            val first = dao.appendAcceptedPoint(point(0), 0.0)
            val reader = RoomViewportTrackPointReader(dao).apply { enableWideRawReads(database) }
            sql.clear()
            assertEquals(listOf(first), reader.read(-90.0, 90.0, WORLD).map { it.pointId })
            assertFalse("normal file-backed wide read must exercise raw path", sql.any(::isWideSql))
            try {
                database.withTransaction {
                    val second = dao.appendAcceptedPoint(point(1), 0.0)
                    sql.clear()
                    val rows = withContext(Dispatchers.Default) { reader.read(-90.0, 90.0, WORLD) }
                    assertEquals(setOf(first, second), rows.map { it.pointId }.toSet())
                    assertTrue("inherited transaction must use Room's snapshot", sql.any(::isWideSql))
                    throw Rollback()
                }
            } catch (_: Rollback) { }
            assertEquals(listOf(first), reader.read(-90.0, 90.0, WORLD).map { it.pointId })
        }
    }

    @Test fun unavailableExtraConnectionFallsBackToTheStillOpenRoomConnection() = runBlocking<Unit> {
        withDatabase { database, path, sql ->
            val dao = database.recordingDao()
            val session = dao.startSession(
                RecordingSessionEntity(startedAt = 1000L, status = RecordingStatus.ACTIVE, createdAppVersion = "raw-reader-fallback"),
                TrackSegmentEntity(sessionId = 0L, sequence = 0L, startedAt = 1000L, startReason = "SESSION_START"))
            val id = dao.appendAcceptedPoint(TrackPointEntity(sessionId = session.sessionId, segmentId = session.segmentId,
                sequence = 0, timestamp = 1000L, latitude = 20.0, longitude = 120.0,
                horizontalAccuracy = 5.0, isMock = true), 0.0)
            val reader = RoomViewportTrackPointReader(dao).apply { enableWideRawReads(database) }
            assertEquals(listOf(id), reader.read(-90.0, 90.0, WORLD).map { it.pointId })
            // Only this UUID-named fixture file is renamed; Room keeps its existing descriptor.
            val parked = File(path.path + ".parked")
            check(!parked.exists())
            assertTrue(path.renameTo(parked))
            try {
                assertFalse(path.exists())
                sql.clear()
                assertEquals(listOf(id), reader.read(-90.0, 90.0, WORLD).map { it.pointId })
                assertTrue("raw open failed but Room must still serve the query", sql.any(::isWideSql))
            } finally { check(parked.renameTo(path)) }
            assertEquals(listOf(id), reader.read(-90.0, 90.0, WORLD).map { it.pointId })
        }
    }

    private suspend fun withDatabase(block: suspend (TrailVeilDatabase, File, CopyOnWriteArrayList<String>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "raw-reader-" + UUID.randomUUID() + ".db"
        val path = context.getDatabasePath(name)
        check(!path.exists())
        val sql = CopyOnWriteArrayList<String>()
        val database = Room.databaseBuilder(context, TrailVeilDatabase::class.java, name)
            .addCallback(TrailVeilDatabase.invariantCallback)
            .setQueryCallback({ query, _ -> sql += query }, Runnable::run).build()
        try { withContext(Dispatchers.IO) { block(database, path, sql) } }
        finally { database.close(); check(context.deleteDatabase(name)) }
    }
    private class Rollback : RuntimeException()
    private fun isWideSql(sql: String) = sql.contains("FROM track_points p") && sql.contains("point_id")
    private companion object { val WORLD = LongitudeInterval(-180.0, 180.0) }
}
