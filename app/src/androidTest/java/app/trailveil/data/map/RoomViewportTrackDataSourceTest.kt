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

    /**
     * `P4-036`: the production reader actually takes the bucketed route at a real viewport.
     *
     * Every other guard on the bucket is about the column being CORRECT. This one is about it being
     * USED — and it needs its own instrument, because the two routes return identical rows by
     * construction (both carry the same `latitude BETWEEN` and `longitude BETWEEN` predicates), so
     * no assertion about the ANSWER can tell them apart. A reader that quietly always fell back
     * would keep every existing test in this file green while the index bought nothing.
     *
     * The instrument is Room's own query callback, so what is asserted is the SQL the database was
     * actually asked to run rather than a reconstruction of it.
     */
    @Test
    fun theReaderTakesTheBucketedRouteAtAnExplorationViewport() = runBlocking {
        val executed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val observed = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .setQueryCallback({ sql, _ -> executed.add(sql) }, Runnable::run)
            .build()
        try {
            // A viewport the size of a city block, which is where an index has anything to offer.
            RoomViewportTrackPointReader(observed.recordingDao()).read(
                south = 25.030,
                north = 25.040,
                interval = LongitudeInterval(west = 121.560, east = 121.570),
            )

            assertEquals(
                "the fog read did not go through the bucketed query at an exploration viewport: " +
                    executed.joinToString(" | "),
                1,
                executed.count { it.contains("lat_bucket IN") },
            )
            // And the fallback is genuinely reserved for the band it was written for. Asserted
            // separately: a reader that ran BOTH would satisfy the line above on its own.
            assertEquals(
                "the fog read also ran the fallback range query: " + executed.joinToString(" | "),
                0,
                executed.count { it.contains("p.latitude BETWEEN") && !it.contains("lat_bucket") },
            )
        } finally {
            observed.close()
        }
    }

    /**
     * And the fallback still exists for the band that cannot be spelled as equalities.
     *
     * Without this, "always use the bucketed query" — dropping the fallback and letting `covering`
     * throw or over-pad — would pass the routing test above. A whole-hemisphere box is coarser than
     * any zoom the app renders, but the reader must answer rather than fail.
     */
    @Test
    fun aBandTooTallForEqualitiesFallsBackAndStillAnswers() = runBlocking {
        val recording = startRecording(startedAt = 100)
        append(recording, sequence = 0, latitude = 25.0, longitude = 121.0)
        stop(recording, endedAt = 150)

        val points = RoomViewportTrackPointReader(dao).read(
            south = -80.0,
            north = 80.0,
            interval = LongitudeInterval(west = 120.0, east = 122.0),
        )

        assertEquals(listOf(121.0), points.map { it.longitude })
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

    @Test
    fun bboxGapSplitsOnePersistedSegmentWithoutConnectingAcrossExcludedPoints() = runBlocking {
        val recording = startRecording(startedAt = 100)
        (0L..6L).forEach { sequence ->
            append(
                recording,
                sequence = sequence,
                latitude = if (sequence in 3L..4L) 30.0 else sequence.toDouble(),
                longitude = 121.0,
            )
        }
        stop(recording, endedAt = 150)

        val result = dataSource.read(
            ViewportBounds(south = 0.5, north = 6.5, west = 120.0, east = 122.0),
        )

        assertEquals(listOf(recording.segmentId, recording.segmentId), result.segments.map { it.segmentId })
        assertEquals(
            listOf(listOf(1.0, 2.0), listOf(5.0, 6.0)),
            result.segments.map { segment -> segment.points.map { it.latitude } },
        )
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
