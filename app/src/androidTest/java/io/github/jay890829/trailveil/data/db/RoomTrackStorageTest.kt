package io.github.jay890829.trailveil.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jay890829.trailveil.data.recording.OperationIdCollisionException
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
    fun openSlotInvariantRejectsMalformedOrSecondOpenSegmentAtSqlBoundary() = runBlocking {
        val recording = startRecording(startedAt = 100)
        val sql = database.openHelper.writableDatabase

        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL(
                """
                INSERT INTO track_segments(
                    session_id, sequence, started_at, ended_at, start_reason, end_reason, open_slot
                ) VALUES (?, 1, 110, NULL, 'SECOND_OPEN', NULL, 1)
                """.trimIndent(),
                arrayOf(recording.sessionId),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL(
                """
                INSERT INTO track_segments(
                    session_id, sequence, started_at, ended_at, start_reason, end_reason, open_slot
                ) VALUES (?, 1, 110, 120, 'MALFORMED_CLOSED', 'STOP', 1)
                """.trimIndent(),
                arrayOf(recording.sessionId),
            )
        }
        assertEquals(1, dao.segmentCount())
    }

    @Test
    fun receiptReplayDoesNotWriteSecondPointOrSummaryAndRejectsCommandCollision() = runBlocking {
        val recording = startRecording(startedAt = 100)
        val first = dao.persistAcceptedPoint(
            point(recording, sequence = 777),
            distanceDeltaMeters = 12.5,
            operationId = "accepted-once",
            commandKind = "LOCATION_ACCEPTED",
            outcome = "ACCEPTED",
            createdAt = 120,
        )
        val replay = dao.persistAcceptedPoint(
            point(recording, sequence = 778, latitude = 26.0),
            distanceDeltaMeters = 99.0,
            operationId = "accepted-once",
            commandKind = "LOCATION_ACCEPTED",
            outcome = "ACCEPTED",
            createdAt = 121,
        )

        assertEquals(false, first.replayed)
        assertEquals(true, replay.replayed)
        assertEquals(first.receipt.pointId, replay.receipt.pointId)
        assertEquals(1, dao.pointCount())
        dao.sessionById(recording.sessionId).also { session ->
            assertEquals(1L, session?.acceptedPointCount)
            assertEquals(12.5, session?.distanceMeters ?: -1.0, 0.0)
        }
        assertThrows(OperationIdCollisionException::class.java) {
            runBlocking {
                dao.persistAcceptedPoint(
                    point(recording, sequence = 779),
                    distanceDeltaMeters = 0.0,
                    operationId = "accepted-once",
                    commandKind = "STOP",
                    outcome = "STOPPED",
                    createdAt = 122,
                )
            }
        }
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """
            INSERT INTO recording_operation_receipts(
                operation_id, command_kind, outcome, session_id, segment_id, point_id, created_at
            ) VALUES ('raw-receipt', 'RAW', 'STORED', ?, NULL, NULL, 123)
            """.trimIndent(),
            arrayOf(recording.sessionId),
        )
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL(
                """
                INSERT INTO recording_operation_receipts(
                    operation_id, command_kind, outcome, session_id, segment_id, point_id, created_at
                ) VALUES ('raw-receipt', 'RAW', 'DUPLICATE', ?, NULL, NULL, 124)
                """.trimIndent(),
                arrayOf(recording.sessionId),
            )
        }
        Unit
    }

    @Test
    fun afterBreakRollbackOnReceiptFailureProtectsCanonicalState() = runBlocking {
        val recording = startRecording(startedAt = 100)
        dao.appendAcceptedPoint(point(recording, sequence = 0), distanceDeltaMeters = 0.0)
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """
            CREATE TRIGGER fail_after_break_receipt
            BEFORE INSERT ON recording_operation_receipts
            WHEN NEW.operation_id = 'after-break-rollback'
            BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END
            """.trimIndent(),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.afterBreak(
                    point = point(recording, sequence = 0, latitude = 25.1),
                    newSegment = TrackSegmentEntity(
                        sessionId = recording.sessionId,
                        sequence = 999,
                        startedAt = 140,
                        startReason = "GAP_RECOVERY",
                    ),
                    oldSegmentId = recording.segmentId,
                    oldEndedAt = 140,
                    oldEndReason = "GAP",
                    distanceDeltaMeters = 5.0,
                    operationId = "after-break-rollback",
                    commandKind = "LOCATION_AFTER_BREAK",
                    outcome = "AFTER_BREAK",
                    createdAt = 140,
                )
            }
        }

        assertEquals(1, dao.segmentCount())
        assertEquals(1, dao.pointCount())
        assertEquals(RecordingStatus.ACTIVE, dao.sessionById(recording.sessionId)?.status)
        sql.query("SELECT ended_at, open_slot FROM track_segments WHERE id = ${recording.segmentId}").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
            assertEquals(1, cursor.getInt(1))
        }
    }

    @Test
    fun stopRollbackOnReceiptFailureLeavesActiveSessionAndOpenSegment() = runBlocking {
        val recording = startRecording(startedAt = 100)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_stop_receipt
            BEFORE INSERT ON recording_operation_receipts
            WHEN NEW.operation_id = 'stop-rollback'
            BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END
            """.trimIndent(),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                dao.stopRecording(
                    sessionId = recording.sessionId,
                    segmentId = recording.segmentId,
                    endedAt = 150,
                    status = RecordingStatus.COMPLETED,
                    stopReason = "USER_STOP",
                    segmentEndReason = "USER_STOP",
                    operationId = "stop-rollback",
                    commandKind = "STOP",
                    outcome = "STOPPED",
                    createdAt = 150,
                )
            }
        }

        dao.sessionById(recording.sessionId).also { session ->
            assertEquals(RecordingStatus.ACTIVE, session?.status)
            assertNull(session?.endedAt)
        }
        assertEquals(recording.segmentId, dao.recordingState(recording.sessionId)?.openSegment?.id)
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