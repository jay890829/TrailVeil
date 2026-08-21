package app.trailveil.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * The database's OWN arithmetic, read back out of it and evaluated against the Kotlin's.
     *
     * [LatitudeBuckets.BUCKETS_PER_DEGREE] is the reader's bucket size; the trigger and the
     * migration are the writers. While those were bare `500.0` literals, changing the constant
     * moved the reader alone and every already-stored point would have fallen out of the fog
     * viewport read at once — silently, and in the direction every leak audit accepts. They are
     * interpolated from the constant now, and this checks that the text which actually reached
     * SQLite still computes what Kotlin computes.
     *
     * It reads the trigger from `sqlite_master` and asks the database to evaluate its own
     * expression, so a change to the interpolation, the offset, or the rounding shows up here. The
     * sibling JVM test compares `of()` against a hand-written expression, which cannot catch any of
     * those.
     */
    @Test
    fun theTriggersArithmeticAgreesWithTheKotlinAtEveryKindOfLatitude() {
        database = openWithTriggers()
        val db = database.openHelper.readableDatabase

        val triggerSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = " +
                "'track_points_lat_bucket_insert'",
        ).use { cursor ->
            assertEquals("the bucket repair trigger is not installed", 1, cursor.count)
            cursor.moveToFirst()
            cursor.getString(0)
        }
        // The expression as the trigger actually spells it, lifted from the installed text rather
        // than retyped here — retyping is the mistake this whole test exists to catch.
        val expression = BUCKET_EXPRESSION.find(triggerSql)?.value
            ?: error("the trigger no longer computes the bucket in the expected shape: $triggerSql")

        listOf(WALKED_LATITUDE, MOVED_LATITUDE, 0.0, -0.0001, -33.8688, 89.9999, -90.0, 90.0)
            .forEach { latitude ->
                val fromSql = db.query(
                    "SELECT " + expression.replace("NEW.latitude", latitude.toString()),
                ).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
                assertEquals(
                    "the database and the Kotlin disagree about latitude $latitude",
                    LatitudeBuckets.of(latitude),
                    fromSql,
                )
            }
    }

    /**
     * An ALREADY-INSTALLED database gets the current arithmetic, not the one it was created with.
     *
     * This closes the blind spot that produced the defect it guards. Both bucket triggers were
     * `CREATE TRIGGER IF NOT EXISTS`, which on an existing database is a no-op — so retuning
     * [LatitudeBuckets.BUCKETS_PER_DEGREE] would have left every installed phone repairing rows
     * with the OLD arithmetic while the app read them with the new. No test could see it, for
     * exactly the reason stated here: every test database in the suite is created fresh, with no
     * trigger to be stale.
     *
     * So this creates the stale state deliberately — a trigger with a DIFFERENT bucket size,
     * installed under the production name — and then runs the installer over it.
     */
    @Test
    fun anOutdatedBucketTriggerIsReplacedRatherThanLeftInPlace() {
        database = openWithTriggers()
        val db = database.openHelper.writableDatabase
        val stale = "CREATE TRIGGER track_points_lat_bucket_insert AFTER INSERT ON track_points " +
            "FOR EACH ROW WHEN NEW.lat_bucket != CAST((NEW.latitude + 90.0) * 250.0 AS INTEGER) " +
            "BEGIN UPDATE track_points SET lat_bucket = " +
            "CAST((NEW.latitude + 90.0) * 250.0 AS INTEGER) WHERE id = NEW.id; END"
        db.execSQL("DROP TRIGGER IF EXISTS track_points_lat_bucket_insert")
        db.execSQL(stale)
        assertTrue(
            "the stale trigger was not installed, so this test would prove nothing",
            installedTriggerSql().contains("250.0"),
        )

        createTrackPointInvariantTriggers(db)

        val repaired = installedTriggerSql()
        assertTrue(
            "an outdated bucket trigger survived the installer, so an upgraded database would " +
                "keep repairing rows with arithmetic the app no longer reads: $repaired",
            !repaired.contains("250.0"),
        )
        assertTrue(
            "the replacement does not carry the current bucket size: $repaired",
            repaired.contains(LatitudeBuckets.BUCKETS_PER_DEGREE.toString()),
        )
        // And it actually fires with the new arithmetic, not merely reads correctly.
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
            "a row written after the replacement carries the old arithmetic's bucket",
            LatitudeBuckets.of(WALKED_LATITUDE),
            storedBucket(),
        )
    }

    private fun installedTriggerSql(): String =
        database.openHelper.readableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = " +
                "'track_points_lat_bucket_insert'",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
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

        /**
         * The bucket arithmetic as it appears inside the installed trigger.
         *
         * Loose on the numbers and strict on the shape: it must not pin the very constant whose
         * drift this test exists to detect, but it must fail rather than silently match nothing if
         * the expression is rewritten into a different form.
         */
        val BUCKET_EXPRESSION =
            Regex("""CAST\(\(NEW\.latitude \+ [0-9.]+\) \* [0-9.]+ AS INTEGER\)""")
    }
}
