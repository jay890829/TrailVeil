package app.trailveil.data.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.data.db.RecordingDao
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.StartedRecording
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.db.TrailVeilDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRecordingHistoryDataSourceTest {
    private lateinit var database: TrailVeilDatabase
    private lateinit var dao: RecordingDao
    private lateinit var history: RecordingHistoryDataSource

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
        history = RoomRecordingHistoryDataSource(dao)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun emptyAndMissingHistoryAreExplicitAndPersistedSessionsAreNewestFirst() = runBlocking {
        assertEquals(emptyList<RecordingHistorySession>(), history.sessions().first())
        assertNull(history.sessionDetail(999_999).first())
        assertNull(history.latestSessionSummary().first())

        val completed = startActive(100)
        close(completed, 110, RecordingStatus.COMPLETED, "USER_STOP")
        val interrupted = startActive(200)
        close(interrupted, 210, RecordingStatus.INTERRUPTED, "INTERRUPT:GPS_DISABLED")
        val failed = startPending(300)
        dao.finishStartingReservation(
            sessionId = failed,
            terminalStatus = RecordingStatus.FAILED_TO_START,
            endedAt = 301,
            stopReason = "START_FAILED",
            operationId = "history-fail",
            commandKind = "START_FAILURE",
            outcome = "START_FAILED",
            createdAt = 301,
        )
        val starting = startPending(400)

        val sessions = history.sessions().first()
        assertEquals(listOf(400L, 300L, 200L, 100L), sessions.map(RecordingHistorySession::startedAt))
        assertEquals(
            listOf(
                RecordingHistoryStatus.STARTING,
                RecordingHistoryStatus.FAILED_TO_START,
                RecordingHistoryStatus.INTERRUPTED,
                RecordingHistoryStatus.COMPLETED,
            ),
            sessions.map(RecordingHistorySession::status),
        )
        assertEquals(starting, sessions.first().id)
    }

    @Test
    fun detailUsesDurablePointOutcomeAndSegmentUpdatesRatherThanRuntimeMemory() = runBlocking {
        val recording = startActive(100)
        dao.persistAcceptedPoint(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = 99,
                timestamp = 120,
                latitude = 25.1,
                longitude = 121.2,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 7.5,
            operationId = "z-history-accepted",
            commandKind = "LOCATION_ACCEPTED",
            outcome = "LOCATION_ACCEPTED_NONE",
            createdAt = 120,
        )
        dao.recordRejectedPoint(
            sessionId = recording.sessionId,
            operationId = "a-history-rejected",
            commandKind = "LOCATION_REJECTED",
            outcome = "LOCATION_REJECTED_ACCURACY",
            createdAt = 120,
        )

        val active = requireNotNull(history.sessionDetail(recording.sessionId).first())
        assertEquals(RecordingHistoryStatus.ACTIVE, active.session.status)
        assertEquals(7.5, active.session.distanceMeters, 0.0)
        assertEquals(1L, active.session.acceptedPointCount)
        assertEquals(1L, active.session.rejectedPointCount)
        assertEquals("LOCATION_REJECTED_ACCURACY", active.latestOperationOutcome?.value)
        assertEquals(25.1, active.latestAcceptedPoint?.latitude ?: -1.0, 0.0)
        assertEquals(121.2, active.latestAcceptedPoint?.longitude ?: -1.0, 0.0)
        assertEquals(listOf(0L), active.segments.map(RecordingHistorySegment::sequence))
        assertNull(active.segments.single().endedAt)
        assertEquals(listOf(listOf(0L)), active.acceptedPointSegments.map { it.points.map { point -> point.sequence } })

        val summary = requireNotNull(history.latestSessionSummary().first())
        assertEquals(recording.sessionId, summary.session.id)
        assertEquals(RecordingHistoryStatus.ACTIVE, summary.session.status)
        assertEquals(1L, summary.session.acceptedPointCount)
        assertEquals(1L, summary.session.rejectedPointCount)
        assertEquals("LOCATION_REJECTED_ACCURACY", summary.latestOperationOutcome?.value)
        assertEquals(active.latestAcceptedPoint, summary.latestAcceptedPoint)
        assertEquals(25.1, summary.latestAcceptedPoint?.latitude ?: -1.0, 0.0)
        assertEquals(121.2, summary.latestAcceptedPoint?.longitude ?: -1.0, 0.0)

        val completed = async(start = CoroutineStart.UNDISPATCHED) {
            history.sessionDetail(recording.sessionId)
                .filterNotNull()
                .first { detail -> detail.session.status == RecordingHistoryStatus.COMPLETED }
        }
        close(recording, 130, RecordingStatus.COMPLETED, "USER_STOP")

        val updated = withTimeout(2_000) { completed.await() }
        assertEquals("USER_STOP", updated.session.stopReason)
        assertEquals(130L, updated.segments.single().endedAt)
        assertEquals("USER_STOP", updated.segments.single().endReason)
        assertEquals("LOCATION_REJECTED_ACCURACY", updated.latestOperationOutcome?.value)
        assertEquals(recording.sessionId, history.latestSessionSummary().first()?.session?.id)
    }

    @Test
    fun latestSummaryUsesSessionInsertionOrderWhenTheClockMovesBackward() = runBlocking {
        val first = startActive(500)
        close(first, 510, RecordingStatus.COMPLETED, "FIRST")
        val laterInserted = startActive(100)

        val latest = requireNotNull(history.latestSessionSummary().first())
        assertEquals(laterInserted.sessionId, latest.session.id)
        assertEquals(100L, latest.session.startedAt)
    }

    @Test
    fun latestSummaryPreservesDetailOrderingWhenTheWallClockMovesBackward() = runBlocking {
        val recording = startActive(100)
        dao.persistAcceptedPoint(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = 0,
                timestamp = 200,
                latitude = 25.0,
                longitude = 121.0,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 0.0,
            operationId = "summary-order-point-first",
            commandKind = "LOCATION_ACCEPTED",
            outcome = "LOCATION_ACCEPTED_FIRST",
            createdAt = 200,
        )
        val laterInsertedPoint = dao.persistAcceptedPoint(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = 1,
                timestamp = 100,
                latitude = 25.1,
                longitude = 121.1,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 1.0,
            operationId = "summary-order-point-second",
            commandKind = "LOCATION_ACCEPTED",
            outcome = "LOCATION_ACCEPTED_SECOND",
            createdAt = 100,
        )
        dao.recordRejectedPoint(
            sessionId = recording.sessionId,
            operationId = "summary-order-outcome-newer-event",
            commandKind = "LOCATION_REJECTED",
            outcome = "OUTCOME_NEWER_EVENT",
            createdAt = 300,
        )
        dao.recordRejectedPoint(
            sessionId = recording.sessionId,
            operationId = "summary-order-outcome-later-commit",
            commandKind = "LOCATION_REJECTED",
            outcome = "OUTCOME_LATER_COMMIT_OLDER_EVENT",
            createdAt = 50,
        )

        val detail = requireNotNull(history.sessionDetail(recording.sessionId).first())
        val summary = requireNotNull(history.latestSessionSummary().first())
        assertEquals(requireNotNull(laterInsertedPoint.receipt.pointId), summary.latestAcceptedPoint?.id)
        assertEquals(detail.latestAcceptedPoint, summary.latestAcceptedPoint)
        assertEquals("OUTCOME_NEWER_EVENT", summary.latestOperationOutcome?.value)
        assertEquals(detail.latestOperationOutcome, summary.latestOperationOutcome)
    }

    @Test
    fun detailStreamsAllAcceptedPointsGroupedAndOrderedByPersistedSegment() = runBlocking {
        val recording = startActive(100)
        dao.persistAcceptedPoint(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = 50,
                timestamp = 110,
                latitude = 25.0,
                longitude = 121.0,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 0.0,
            operationId = "points-first-0",
            commandKind = "LOCATION_ACCEPTED",
            outcome = "LOCATION_ACCEPTED_NONE",
            createdAt = 110,
        )
        dao.persistAcceptedPoint(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = 50,
                timestamp = 111,
                latitude = 25.1,
                longitude = 121.1,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 1.0,
            operationId = "points-first-1",
            commandKind = "LOCATION_ACCEPTED",
            outcome = "LOCATION_ACCEPTED_NONE",
            createdAt = 111,
        )
        val afterBreak = dao.afterBreak(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = 50,
                timestamp = 120,
                latitude = 25.2,
                longitude = 121.2,
                horizontalAccuracy = 5.0,
            ),
            newSegment = TrackSegmentEntity(
                sessionId = recording.sessionId,
                sequence = 50,
                startedAt = 120,
                startReason = "GAP",
            ),
            oldSegmentId = recording.segmentId,
            oldEndedAt = 120,
            oldEndReason = "GAP",
            distanceDeltaMeters = 0.0,
            operationId = "points-break",
            commandKind = "LOCATION_AFTER_BREAK",
            outcome = "AFTER_BREAK",
            createdAt = 120,
        )
        val secondSegmentId = requireNotNull(afterBreak.receipt.segmentId)
        dao.persistAcceptedPoint(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = secondSegmentId,
                sequence = 50,
                timestamp = 121,
                latitude = 25.3,
                longitude = 121.3,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 1.0,
            operationId = "points-second-1",
            commandKind = "LOCATION_ACCEPTED",
            outcome = "LOCATION_ACCEPTED_NONE",
            createdAt = 121,
        )

        val detail = requireNotNull(history.sessionDetail(recording.sessionId).first())
        assertEquals(listOf(recording.segmentId, secondSegmentId), detail.acceptedPointSegments.map { it.segmentId })
        assertEquals(listOf(0L, 1L), detail.acceptedPointSegments.map { it.segmentSequence })
        assertEquals(
            listOf(listOf(0L, 1L), listOf(0L, 1L)),
            detail.acceptedPointSegments.map { segment -> segment.points.map { point -> point.sequence } },
        )
        assertEquals(
            listOf(listOf(25.0, 25.1), listOf(25.2, 25.3)),
            detail.acceptedPointSegments.map { segment -> segment.points.map { point -> point.latitude } },
        )
    }

    private suspend fun startActive(startedAt: Long): StartedRecording =
        dao.startSession(
            session = RecordingSessionEntity(
                startedAt = startedAt,
                status = RecordingStatus.ACTIVE,
                createdAppVersion = "history-instrumentation-test",
            ),
            initialSegment = TrackSegmentEntity(
                sessionId = 0,
                sequence = 0,
                startedAt = startedAt,
                startReason = "SESSION_START",
            ),
        )

    private suspend fun startPending(startedAt: Long): Long =
        requireNotNull(
            dao.prepareStartingReservation(
                session = RecordingSessionEntity(
                    startedAt = startedAt,
                    status = RecordingStatus.STARTING,
                    createdAppVersion = "history-instrumentation-test",
                ),
                operationId = "history-pending-$startedAt",
                commandKind = "START_PREPARE",
                outcome = "START_PREPARED",
                createdAt = startedAt,
            ).receipt.sessionId,
        )

    private suspend fun close(
        recording: StartedRecording,
        endedAt: Long,
        status: RecordingStatus,
        reason: String,
    ) {
        dao.closeRecording(
            sessionId = recording.sessionId,
            segmentId = recording.segmentId,
            endedAt = endedAt,
            status = status,
            stopReason = reason,
            segmentEndReason = reason,
        )
    }
}
