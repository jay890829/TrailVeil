package app.trailveil.data.map

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPersistedTrackPointChangeFeedTest {
    private lateinit var database: TrailVeilDatabase
    private lateinit var dao: RecordingDao
    private lateinit var feed: PersistedTrackPointChangeFeed

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
        feed = RoomPersistedTrackPointChangeFeed(dao)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun cursorBatchesRemainIdOrderedAndCarryOnlySameSegmentPredecessors() = runBlocking {
        val first = startRecording(100)
        append(first, sequence = 0, latitude = 25.0)
        append(first, sequence = 1, latitude = 25.1)
        val second = breakAndAppend(first, latitude = 26.0)

        val all = feed.readChangesAfter(PersistedPointCursor(0))
        val firstPoint = all[0].point
        val secondPoint = all[1].point
        val thirdPoint = all[2].point

        assertEquals(all.map { it.point.pointId }.sorted(), all.map { it.point.pointId })
        assertEquals(null, all[0].previousPoint)
        assertEquals(firstPoint, all[1].previousPoint)
        assertEquals(null, all[2].previousPoint)
        assertEquals(first.sessionId, all[1].previousPoint?.sessionId)
        assertEquals(first.segmentId, all[1].previousPoint?.segmentId)
        assertEquals(first.sessionId, thirdPoint.sessionId)
        assertEquals(second.segmentId, thirdPoint.segmentId)

        val secondBatch = feed.readChangesAfter(PersistedPointCursor(firstPoint.pointId))
        assertEquals(listOf(secondPoint.pointId, thirdPoint.pointId), secondBatch.map { it.point.pointId })
        assertEquals(firstPoint, secondBatch.first().previousPoint)
    }

    @Test
    fun baselineSuppressesExistingRevisionAndSessionLifecycleDoesNotEmit() = runBlocking {
        val existing = startRecording(100)
        append(existing, sequence = 0, latitude = 25.0)
        val baseline = feed.latestCursor()
        val revision = async(start = CoroutineStart.UNDISPATCHED) {
            feed.revisionsAfter(baseline).first()
        }

        stop(existing, 150)
        assertFalse(revision.isCompleted)
        val recording = startRecording(200)
        assertFalse(revision.isCompleted)
        append(recording, sequence = 0, latitude = 25.0)

        val emitted = withTimeout(2_000) { revision.await() }
        assertEquals(feed.latestCursor(), emitted.latestCursor)
        assertEquals(baseline.pointId + 1, emitted.latestCursor.pointId)
    }

    @Test
    fun boundedPagingReturns128Then128ThenRemainderWithBoundaryPredecessor() = runBlocking {
        val recording = startRecording(100)
        repeat(257) { index ->
            append(
                recording = recording,
                sequence = index.toLong(),
                latitude = 25.0 + index * 0.000001,
            )
        }

        var cursor = PersistedPointCursor(0L)
        val pages = mutableListOf<List<PersistedTrackPointChange>>()
        repeat(3) {
            val page = feed.readChangesAfter(cursor, limit = 128)
            pages += page
            cursor = PersistedPointCursor(page.last().point.pointId)
        }

        assertEquals(listOf(128, 128, 1), pages.map(List<*>::size))
        assertEquals(
            pages[0].last().point.pointId,
            pages[1].first().previousPoint?.pointId,
        )
        assertEquals(
            pages[1].last().point.pointId,
            pages[2].first().previousPoint?.pointId,
        )
        assertEquals(feed.latestCursor(), cursor)
        assertEquals(
            (1L..257L).toList(),
            pages.flatten().map { it.point.pointSequence + 1L },
        )
    }

    private suspend fun startRecording(startedAt: Long): StartedRecording =
        dao.startSession(
            session = RecordingSessionEntity(
                startedAt = startedAt,
                status = RecordingStatus.ACTIVE,
                createdAppVersion = "change-feed-instrumentation-test",
            ),
            initialSegment = TrackSegmentEntity(
                sessionId = 0,
                sequence = 0,
                startedAt = startedAt,
                startReason = "SESSION_START",
            ),
        )

    private suspend fun breakAndAppend(
        recording: StartedRecording,
        latitude: Double,
    ): StartedRecording {
        val result = dao.afterBreak(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = 0,
                timestamp = 130,
                latitude = latitude,
                longitude = 121.0,
                horizontalAccuracy = 5.0,
            ),
            newSegment = TrackSegmentEntity(
                sessionId = recording.sessionId,
                sequence = 999,
                startedAt = 130,
                startReason = "GAP",
            ),
            oldSegmentId = recording.segmentId,
            oldEndedAt = 130,
            oldEndReason = "GAP",
            distanceDeltaMeters = 0.0,
            operationId = "change-feed-break",
            commandKind = "LOCATION_AFTER_BREAK",
            outcome = "AFTER_BREAK",
            createdAt = 130,
        )
        return StartedRecording(recording.sessionId, requireNotNull(result.receipt.segmentId))
    }

    private suspend fun append(recording: StartedRecording, sequence: Long, latitude: Double) {
        dao.appendAcceptedPoint(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = sequence,
                timestamp = 100 + sequence,
                latitude = latitude,
                longitude = 121.0,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 0.0,
        )
    }

    private suspend fun stop(recording: StartedRecording, endedAt: Long) {
        dao.closeRecording(
            sessionId = recording.sessionId,
            segmentId = recording.segmentId,
            endedAt = endedAt,
            status = RecordingStatus.COMPLETED,
            stopReason = "TEST_COMPLETE",
            segmentEndReason = "TEST_COMPLETE",
        )
    }
}
