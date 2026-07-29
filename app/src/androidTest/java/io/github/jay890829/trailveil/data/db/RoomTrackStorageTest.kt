package io.github.jay890829.trailveil.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTrackStorageTest {
    private lateinit var database: TrailVeilDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()
        dao = database.recordingDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun databaseAndDaoRejectEverySecondOrInconsistentActiveSession() = runBlocking {
        startRecording(startedAt = 100)

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { startRecording(startedAt = 200) }
        }
        assertEquals(1, dao.sessionCount())
        assertEquals(1, dao.segmentCount())

        val sql = database.openHelper.writableDatabase
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL(
                """
                INSERT INTO recording_sessions(
                    started_at, ended_at, status, stop_reason, distance_meters,
                    accepted_point_count, rejected_point_count, created_app_version, active_slot
                ) VALUES (?, NULL, 'ACTIVE', NULL, 0, 0, 0, 'raw-test', NULL)
                """.trimIndent(),
                arrayOf(300L),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL(
                """
                INSERT INTO recording_sessions(
                    started_at, ended_at, status, stop_reason, distance_meters,
                    accepted_point_count, rejected_point_count, created_app_version, active_slot
                ) VALUES (?, ?, 'COMPLETED', 'USER_STOP', 0, 0, 0, 'raw-test', 1)
                """.trimIndent(),
                arrayOf(300L, 301L),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL(
                """
                INSERT INTO recording_sessions(
                    started_at, ended_at, status, stop_reason, distance_meters,
                    accepted_point_count, rejected_point_count, created_app_version, active_slot
                ) VALUES (?, NULL, 'ACTIVE', NULL, 0, 0, 0, 'raw-test', 1)
                """.trimIndent(),
                arrayOf(400L),
            )
        }
        assertEquals(1, dao.sessionCount())
    }

    @Test
    fun sequenceAndCompositeRelationshipConstraintsProtectCanonicalTrack() = runBlocking {
        val first = startRecording(startedAt = 100)
        dao.appendAcceptedPoint(point(first, sequence = 0), distanceDeltaMeters = 0.0)

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.appendAcceptedPoint(point(first, sequence = 0), distanceDeltaMeters = 99.0)
            }
        }
        assertEquals(1, dao.pointCount())
        assertEquals(1L, dao.sessionById(first.sessionId)?.acceptedPointCount)
        assertEquals(0.0, dao.sessionById(first.sessionId)?.distanceMeters ?: -1.0, 0.0)

        assertThrows(SQLiteConstraintException::class.java) {
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO track_segments(
                    session_id, sequence, started_at, ended_at, start_reason, end_reason
                ) VALUES (?, 0, 101, NULL, 'DUPLICATE', NULL)
                """.trimIndent(),
                arrayOf(first.sessionId),
            )
        }

        assertThrows(SQLiteConstraintException::class.java) {
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO track_points(
                    session_id, segment_id, sequence, timestamp, latitude, longitude,
                    horizontal_accuracy, altitude, speed, bearing, is_mock
                ) VALUES (?, 999999, 1, 120, 25.0, 121.0, 5.0, NULL, NULL, NULL, 0)
                """.trimIndent(),
                arrayOf(first.sessionId),
            )
        }
        dao.closeRecording(
            sessionId = first.sessionId,
            segmentId = first.segmentId,
            endedAt = 150,
            status = RecordingStatus.COMPLETED,
            stopReason = "USER_STOP",
            segmentEndReason = "USER_STOP",
        )
        val second = startRecording(startedAt = 200)

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.appendAcceptedPoint(
                    point(second, sequence = 0).copy(segmentId = first.segmentId),
                    distanceDeltaMeters = 0.0,
                )
            }
        }
        assertEquals(1, dao.pointCount())
    }

    @Test
    fun failedMultiRowTransactionRollsBackSessionTransition() = runBlocking {
        val recording = startRecording(startedAt = 100)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                dao.closeRecording(
                    sessionId = recording.sessionId,
                    segmentId = Long.MAX_VALUE,
                    endedAt = 200,
                    status = RecordingStatus.COMPLETED,
                    stopReason = "USER_STOP",
                    segmentEndReason = "USER_STOP",
                )
            }
        }

        val active = dao.activeSession()
        assertNotNull(active)
        assertEquals(recording.sessionId, active?.id)
        assertEquals(RecordingStatus.ACTIVE, active?.status)
        assertNull(active?.endedAt)
    }

    @Test
    fun relationshipQueryBoundingBoxAndCascadeRemainConsistent() = runBlocking {
        val recording = startRecording(startedAt = 100)
        dao.appendAcceptedPoint(
            point(recording, sequence = 0, latitude = 25.0, longitude = 121.0),
            distanceDeltaMeters = 0.0,
        )
        dao.appendAcceptedPoint(
            point(recording, sequence = 1, latitude = 26.0, longitude = 122.0),
            distanceDeltaMeters = 10.0,
        )

        val relation = dao.sessionWithSegments(recording.sessionId)
        assertEquals(listOf(0L), relation?.segments?.map(TrackSegmentEntity::sequence))
        assertEquals(
            listOf(0L),
            dao.pointsInBoundingBox(
                sessionId = recording.sessionId,
                south = 24.5,
                west = 120.5,
                north = 25.5,
                east = 121.5,
            ).map(TrackPointEntity::sequence),
        )

        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM track_segments WHERE id = ?",
            arrayOf(recording.segmentId),
        )
        assertEquals(0, dao.segmentCount())
        assertEquals(0, dao.pointCount())
        assertEquals(1, dao.sessionCount())

        assertEquals(1, dao.deleteSession(recording.sessionId))
        assertEquals(0, dao.sessionCount())
    }

    private suspend fun startRecording(startedAt: Long): StartedRecording =
        dao.startSession(
            session = RecordingSessionEntity(
                startedAt = startedAt,
                status = RecordingStatus.ACTIVE,
                createdAppVersion = "instrumentation-test",
            ),
            initialSegment = TrackSegmentEntity(
                sessionId = 0,
                sequence = 0,
                startedAt = startedAt,
                startReason = "SESSION_START",
            ),
        )

    private fun point(
        recording: StartedRecording,
        sequence: Long,
        latitude: Double = 25.0,
        longitude: Double = 121.0,
    ) = TrackPointEntity(
        sessionId = recording.sessionId,
        segmentId = recording.segmentId,
        sequence = sequence,
        timestamp = 100 + sequence,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracy = 5.0,
    )
}