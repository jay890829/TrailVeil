package app.trailveil.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `P4-036`: the derived bucket has two independent guards, and this binds each one alone.
 *
 * `lat_bucket` is a function of the latitude, so a row whose bucket disagrees with its own latitude
 * silently vanishes from the fog viewport read — the map then draws MORE fog than it earned, and
 * every leak audit in the suite accepts extra fog. Nothing goes red; the user just loses ground
 * they walked.
 *
 * Two mechanisms prevent that: the DAO derives the bucket at its single insert choke point, and a
 * database trigger repairs anything written around the DAO in raw SQL. Tested together they hide
 * each other — remove the DAO's derivation and the trigger silently covers for it. So each test
 * here removes the OTHER guard: the derivation test opens a database with no invariant callback at
 * all, and the trigger test writes SQL the DAO never sees.
 */
@RunWith(AndroidJUnit4::class)
class LatitudeBucketDerivationTest {
    private lateinit var database: TrailVeilDatabase

    @After
    fun closeDatabase() {
        if (this::database.isInitialized) database.close()
    }

    /**
     * Deliberately WITHOUT the invariant callback, so no trigger exists to repair anything: the
     * only thing that can put a correct bucket on this row is the DAO itself.
     */
    private fun openWithoutTriggers(): TrailVeilDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()

    private fun openWithTriggers(): TrailVeilDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()

    /**
     * A point stored the way the app stores points is findable by the read the app uses to draw.
     *
     * The latitude is the mutation-sensitive part. [TrackPointEntity]'s constructor default has to
     * be SOME number, and any fixed number is the correct bucket for exactly one band of the Earth;
     * an earlier draft defaulted it to the bucket for latitude 0.0, which a fixture at the equator
     * would have happily confirmed. This point sits where the user actually walks, so the default
     * cannot accidentally be right.
     *
     * Not caught by this test: that all four of the DAO's insert paths derive it. They are correct
     * by construction rather than by coverage — `insertPointRow` is the single private method every
     * one of them calls, and it is the method this test's mutation lives in.
     */
    @Test
    fun aPointStoredThroughTheDaoCarriesTheBucketItsLatitudeImplies() = runBlocking {
        database = openWithoutTriggers()
        val dao = database.recordingDao()
        val recording = dao.startSession(
            session = RecordingSessionEntity(
                startedAt = 100,
                status = RecordingStatus.ACTIVE,
                createdAppVersion = "instrumentation-test",
            ),
            initialSegment = TrackSegmentEntity(
                sessionId = 0,
                sequence = 0,
                startedAt = 100,
                startReason = "SESSION_START",
            ),
        )
        dao.appendAcceptedPoint(
            TrackPointEntity(
                sessionId = recording.sessionId,
                segmentId = recording.segmentId,
                sequence = 0,
                timestamp = 100,
                latitude = WALKED_LATITUDE,
                longitude = WALKED_LONGITUDE,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 0.0,
        )

        assertEquals(
            "the stored point's bucket disagrees with its own latitude",
            LatitudeBuckets.of(WALKED_LATITUDE),
            storedBucket(),
        )
        // And the consequence, stated as the consequence: the read that draws the map finds it.
        // A bucket assertion alone would still pass if `covering` and `of` drifted apart together.
        assertEquals(
            "the point the app stored is invisible to the read the app draws with",
            1,
            RoomViewportTrackPointReaderProbe.rowsAround(dao, WALKED_LATITUDE, WALKED_LONGITUDE),
        )
    }

    /**
     * The backstop, bound where the DAO cannot reach: raw SQL, carrying a bucket that is wrong.
     *
     * The wrong value is not zero. Zero is what an unset column holds, so a trigger that fired only
     * on zero would pass this test while leaving every genuinely-miscomputed row broken; this one
     * writes a plausible-but-wrong bucket for a different part of the world.
     */
    @Test
    fun theTriggerRepairsABucketWrittenAroundTheDao() {
        database = openWithTriggers()
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO recording_sessions (started_at, status, ended_at, distance_meters, " +
                "accepted_point_count, rejected_point_count, created_app_version) " +
                "VALUES (1, 'COMPLETED', 2, 0.0, 0, 0, 'test')",
        )
        db.execSQL(
            "INSERT INTO track_segments (session_id, sequence, started_at, start_reason, " +
                "ended_at, end_reason) VALUES (1, 0, 1, 'test', 2, 'test')",
        )
        db.execSQL(
            "INSERT INTO track_points (session_id, segment_id, sequence, timestamp, latitude, " +
                "longitude, horizontal_accuracy, lat_bucket) VALUES " +
                "(1, 1, 0, 100, $WALKED_LATITUDE, $WALKED_LONGITUDE, 5.0, $WRONG_BUCKET)",
        )

        assertEquals(
            "a raw-SQL row kept a bucket that does not match its latitude",
            LatitudeBuckets.of(WALKED_LATITUDE),
            storedBucket(),
        )

        // Moving the point must move its bucket too. An insert-only trigger leaves an edited row
        // stranded in the bucket it used to be in, which is the same silent disappearance.
        db.execSQL("UPDATE track_points SET latitude = $MOVED_LATITUDE WHERE id = 1")
        assertEquals(
            "an updated latitude left the row in its old bucket",
            LatitudeBuckets.of(MOVED_LATITUDE),
            storedBucket(),
        )
    }

    private fun storedBucket(): Int =
        database.openHelper.readableDatabase
            .query("SELECT lat_bucket FROM track_points ORDER BY id ASC LIMIT 1")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    private companion object {
        /** Where the user's own tracks are — not the equator, and not any constructor default. */
        const val WALKED_LATITUDE = 25.0359
        const val WALKED_LONGITUDE = 121.5654

        /** Far enough to land in a different bucket, close enough to stay a plausible mistake. */
        const val MOVED_LATITUDE = 25.9

        /** A real bucket, for somewhere else entirely: not zero, so "unset" is not the case here. */
        const val WRONG_BUCKET = 12345
    }
}
