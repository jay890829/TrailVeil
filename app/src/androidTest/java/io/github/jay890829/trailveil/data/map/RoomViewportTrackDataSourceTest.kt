package io.github.jay890829.trailveil.data.map

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jay890829.trailveil.data.db.RecordingDao
import io.github.jay890829.trailveil.data.db.RecordingSessionEntity
import io.github.jay890829.trailveil.data.db.RecordingStatus
import io.github.jay890829.trailveil.data.db.StartedRecording
import io.github.jay890829.trailveil.data.db.TrackPointEntity
import io.github.jay890829.trailveil.data.db.TrackSegmentEntity
import io.github.jay890829.trailveil.data.db.TrailVeilDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomViewportTrackDataSourceTest {
    private lateinit var database: TrailVeilDatabase
    private lateinit var dao: RecordingDao
    private lateinit var dataSource: ViewportTrackDataSource

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
        dataSource = ViewportTrackDataSource(RoomViewportTrackPointReader(dao))
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun readsAcrossSessionsWithoutBridgingPersistedSegments() = runBlocking {
        val first = startRecording(startedAt = 100)
        append(first, sequence = 0, latitude = 25.0, longitude = 121.0)
        append(first, sequence = 1, latitude = 25.1, longitude = 121.1)
        stop(first, endedAt = 150)

        val second = startRecording(startedAt = 200)
        append(second, sequence = 0, latitude = 25.2, longitude = 121.2)
        stop(second, endedAt = 250)

        val result = dataSource.read(
            ViewportBounds(south = 24.0, north = 26.0, west = 120.0, east = 122.0),
        )

        assertEquals(
            listOf(first.sessionId to first.segmentId, second.sessionId to second.segmentId),
            result.segments.map { segment -> segment.sessionId to segment.segmentId },
        )
        assertEquals(listOf(121.0, 121.1), result.segments[0].points.map { it.longitude })
        assertEquals(listOf(121.2), result.segments[1].points.map { it.longitude })
    }

    @Test
    fun datelineSplitReunitesOnePersistedSegmentInPointOrder() = runBlocking {
        val recording = startRecording(startedAt = 100)
        append(recording, sequence = 0, latitude = 1.0, longitude = 178.0)
        append(recording, sequence = 1, latitude = 1.0, longitude = -179.0)
        append(recording, sequence = 2, latitude = 1.0, longitude = 0.0)
        stop(recording, endedAt = 150)

        val result = dataSource.read(
            ViewportBounds(south = -5.0, north = 5.0, west = 170.0, east = -170.0),
        )

        assertEquals(1, result.segments.size)
        assertEquals(recording.sessionId, result.segments.single().sessionId)
        assertEquals(recording.segmentId, result.segments.single().segmentId)
        assertEquals(listOf(178.0, -179.0), result.segments.single().points.map { it.longitude })
    }

    private suspend fun startRecording(startedAt: Long): StartedRecording =
        dao.startSession(
            session = RecordingSessionEntity(
                startedAt = startedAt,
                status = RecordingStatus.ACTIVE,
                createdAppVersion = "viewport-instrumentation-test",
            ),
            initialSegment = TrackSegmentEntity(
                sessionId = 0,
                sequence = 0,
                startedAt = startedAt,
                startReason = "SESSION_START",
            ),
        )

    private suspend fun append(
        recording: StartedRecording,
        sequence: Long,
        latitude: Double,
        longitude: Double,
    ) {
        dao.appendAcceptedPoint(
            point = TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = sequence,
                timestamp = 100 + sequence,
                latitude = latitude,
                longitude = longitude,
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
