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
 * `P4-037`: the coarse cell table is derived state, and this binds the one thing that maintains it.
 *
 * A cell that is never written drops its whole region out of the world-zoom read, so the map draws
 * MORE fog than it earned. That is the direction every leak audit in the suite accepts, so nothing
 * else would go red — the user would simply find ground they walked looking unexplored when they
 * zoom out.
 *
 * Unlike `lat_bucket`, which has two independent guards, the cell has exactly ONE: the
 * `track_points_cell_insert` trigger. [createTrackPointCellTriggers] explains why a DAO copy would
 * be redundant work rather than defence in depth. The cost of a single guard is that "the trigger
 * works" is only half the property — the other half is that the production callback actually
 * installs it — so the first case here goes through the app's own open path and installs nothing
 * itself.
 */
@RunWith(AndroidJUnit4::class)
class TrackPointCellDerivationTest {
    private lateinit var database: TrailVeilDatabase

    @After
    fun closeDatabase() {
        if (this::database.isInitialized) database.close()
    }

    /** The production open path, callback and all. Nothing here installs a trigger by hand. */
    private fun openAsTheAppDoes(): TrailVeilDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()

    /**
     * A point stored the way the app stores points occupies the cell its coordinates imply.
     *
     * The strongest case here, because it removes no guard and adds none: the callback the app ships
     * installs the trigger, the DAO insert the app calls fires it, and the cell that lands is the one
     * [TrackPointCells] would compute. Delete the `createTrackPointCellTriggers` line from the
     * callback and this is what goes red.
     *
     * The coordinates are where the user actually walks rather than the equator, so neither a
     * constructor default nor a zeroed column can be accidentally right.
     */
    @Test
    fun aPointStoredThroughTheDaoOccupiesTheCellItsCoordinatesImply() = runBlocking {
        database = openAsTheAppDoes()
        appendPointThroughDao(WALKED_LATITUDE, WALKED_LONGITUDE)

        assertEquals(
            "the app's own write path left no cell, so this region vanishes from the world read",
            listOf(cellOf(WALKED_LATITUDE, WALKED_LONGITUDE)),
            storedCells(),
        )
    }

    /**
     * The route the DAO never sees, which is not hypothetical: the v7 to v8 backfill and every
     * read-cost fixture in the suite insert points as raw SQL.
     */
    @Test
    fun aPointWrittenAroundTheDaoIsCelledAnyway() {
        database = openAsTheAppDoes()
        insertPointsAsRawSql(listOf(WALKED_LATITUDE to WALKED_LONGITUDE))

        assertEquals(
            "raw SQL wrote a point with no cell, which is how a fixture measures an empty table " +
                "and reports it as a saving",
            listOf(cellOf(WALKED_LATITUDE, WALKED_LONGITUDE)),
            storedCells(),
        )
    }

    /**
     * The equivalence the whole scheme rests on, measured rather than reasoned about.
     *
     * The trigger derives cells with `CAST(... AS INTEGER)`, which truncates toward zero, while
     * [TrackPointCells.longitudeCellOf] floors. Those differ for negative operands and agree for
     * non-negative ones, and the arithmetic shifts both coordinates non-negative before scaling — so
     * they agree everywhere valid. That is an argument; this is the measurement, taken at southern
     * and western coordinates where truncation and flooring would visibly part company if the shift
     * were ever dropped.
     */
    @Test
    fun theTriggersCellArithmeticIsTheReadsCellArithmetic() {
        database = openAsTheAppDoes()
        insertPointsAsRawSql(ACROSS_THE_GLOBE)

        val expected = ACROSS_THE_GLOBE
            .map { (latitude, longitude) -> cellOf(latitude, longitude) }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals(
            "the cells SQL writes are not the cells Kotlin reads, so the read looks in the wrong " +
                "place and the region is simply absent",
            expected,
            storedCells(),
        )
    }

    /**
     * The collapse that makes the table worth having: many points, one row.
     *
     * Without this the table could be perfectly correct and still useless — a row per point
     * summarises nothing, and the world-zoom read would visit exactly what it visits today.
     */
    @Test
    fun manyPointsInOneCellOccupyOneRow() {
        database = openAsTheAppDoes()
        // Walked from the CENTRE of a cell rather than from a fixed coordinate. `WALKED_LATITUDE`
        // sits at about 0.95 of its own cell, so stepping up from it straddles the boundary and the
        // fixture would fail as a fixture rather than as a finding.
        val base = TrackPointCells.latitudeAtCentreOf(TrackPointCells.latitudeCellOf(WALKED_LATITUDE))
        val neighbours = (0 until POINTS_IN_ONE_CELL).map { step ->
            base + step * A_STEP_WELL_INSIDE_ONE_CELL to WALKED_LONGITUDE
        }
        assertEquals(
            "the fixture spreads across cells, so it would prove nothing about collapsing",
            1,
            neighbours.map { TrackPointCells.latitudeCellOf(it.first) }.distinct().size,
        )
        insertPointsAsRawSql(neighbours)

        assertEquals("the fixture did not actually store its points", POINTS_IN_ONE_CELL, pointCount())
        assertEquals(
            "$POINTS_IN_ONE_CELL points in one cell did not collapse to one row",
            1,
            storedCells().size,
        )
    }

    /**
     * Deleting a session vacates the cells its points occupied.
     *
     * The acceptance criterion names this because the write path is append-only: `INSERT OR IGNORE`
     * can only ever leave a cell behind, never remove one, and a cell occupied by nothing reveals
     * ground the user deleted. There is no production caller for the delete today, so this keeps a
     * route correct rather than fixing a live one.
     */
    @Test
    fun deletingASessionVacatesTheCellsItsPointsOccupied() = runBlocking {
        database = openAsTheAppDoes()
        val kept = appendPointThroughDao(WALKED_LATITUDE, WALKED_LONGITUDE)
        val removed = appendPointThroughDao(FAR_AWAY_LATITUDE, FAR_AWAY_LONGITUDE)
        assertTrue("both points landed in one session, so the delete would take both", kept != removed)
        assertEquals(
            "the fixture's two points share a cell, so a surviving stale one would be invisible",
            2,
            storedCells().size,
        )

        database.recordingDao().deleteSession(removed)

        assertEquals(
            "the deleted session's cell survived, so the world read still reveals ground the user " +
                "removed",
            listOf(cellOf(WALKED_LATITUDE, WALKED_LONGITUDE)),
            storedCells(),
        )
    }

    /**
     * An installed trigger carrying outdated arithmetic is replaced, not left in place.
     *
     * `CREATE TRIGGER IF NOT EXISTS` on an existing database is a no-op, so retuning the cell size
     * would leave every already-installed phone WRITING cells at the old size while the app READ
     * them at the new one — silent fog loss for everything recorded after the update. The stale
     * state is created deliberately, under the production trigger name.
     */
    @Test
    fun anOutdatedCellTriggerIsReplacedRatherThanLeftInPlace() {
        database = openAsTheAppDoes()
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS $CELL_TRIGGER_NAME")
        db.execSQL(
            "CREATE TRIGGER $CELL_TRIGGER_NAME AFTER INSERT ON track_points FOR EACH ROW " +
                "BEGIN INSERT OR IGNORE INTO track_point_cells(lat_cell, lon_cell) VALUES(" +
                "CAST((NEW.latitude + 90.0) * $STALE_CELLS_PER_DEGREE AS INTEGER), " +
                "CAST((NEW.longitude + 180.0) * $STALE_CELLS_PER_DEGREE AS INTEGER)); END",
        )
        assertTrue(
            "the stale trigger was not installed, so this test would prove nothing",
            installedCellTriggerSql().contains(STALE_CELLS_PER_DEGREE),
        )

        createTrackPointCellTriggers(db)

        val replaced = installedCellTriggerSql()
        assertTrue(
            "an outdated cell trigger survived the installer: $replaced",
            !replaced.contains(STALE_CELLS_PER_DEGREE),
        )
        assertTrue(
            "the replacement does not carry the current cell size: $replaced",
            replaced.contains(TrackPointCells.CELLS_PER_DEGREE_SQL),
        )
        // And it fires with the new arithmetic rather than merely reading correctly.
        insertPointsAsRawSql(listOf(WALKED_LATITUDE to WALKED_LONGITUDE))
        assertEquals(
            "a point written after the replacement was celled with the old arithmetic",
            listOf(cellOf(WALKED_LATITUDE, WALKED_LONGITUDE)),
            storedCells(),
        )
    }

    private fun cellOf(latitude: Double, longitude: Double): Pair<Int, Int> =
        TrackPointCells.latitudeCellOf(latitude) to TrackPointCells.longitudeCellOf(longitude)

    /**
     * Records one point in its own session and closes it again.
     *
     * Closing is not tidiness: `active_slot` is unique, so the invariants permit exactly one live
     * recording and a fixture that leaves the first session open cannot start a second. Two sessions
     * are what the delete case needs, so it takes the app's own route to a finished one.
     */
    private suspend fun appendPointThroughDao(latitude: Double, longitude: Double): Long {
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
                latitude = latitude,
                longitude = longitude,
                horizontalAccuracy = 5.0,
            ),
            distanceDeltaMeters = 0.0,
        )
        dao.closeRecording(
            sessionId = recording.sessionId,
            segmentId = recording.segmentId,
            endedAt = 200,
            status = RecordingStatus.COMPLETED,
            stopReason = "instrumentation-test",
            segmentEndReason = "instrumentation-test",
        )
        return recording.sessionId
    }

    /**
     * Points inserted the way the migrations and the read-cost fixtures insert them.
     *
     * `lat_bucket` is written as 0 on purpose: it is wrong for everywhere except the south pole, so
     * leaving the bucket trigger to repair it keeps this fixture honest about being raw SQL rather
     * than quietly doing the DAO's work by hand.
     */
    private fun insertPointsAsRawSql(points: List<Pair<Double, Double>>) {
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
        points.forEachIndexed { index, (latitude, longitude) ->
            db.execSQL(
                "INSERT INTO track_points (session_id, segment_id, sequence, timestamp, latitude, " +
                    "longitude, horizontal_accuracy, lat_bucket) VALUES " +
                    "(1, 1, $index, ${100 + index}, $latitude, $longitude, 5.0, 0)",
            )
        }
    }

    private fun storedCells(): List<Pair<Int, Int>> =
        database.openHelper.readableDatabase
            .query("SELECT lat_cell, lon_cell FROM track_point_cells ORDER BY lat_cell, lon_cell")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getInt(1))
                }
            }

    private fun pointCount(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_points")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    private fun installedCellTriggerSql(): String =
        database.openHelper.readableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = '$CELL_TRIGGER_NAME'",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }

    private companion object {
        const val CELL_TRIGGER_NAME = "track_points_cell_insert"

        /** Where the user's own tracks are — not the equator, and not any constructor default. */
        const val WALKED_LATITUDE = 25.0359
        const val WALKED_LONGITUDE = 121.5654

        /** A different cell entirely, so a stale row left behind is visible rather than absorbed. */
        const val FAR_AWAY_LATITUDE = -33.8688
        const val FAR_AWAY_LONGITUDE = 151.2093

        /** Southern and western coordinates, where truncation and flooring would part company. */
        val ACROSS_THE_GLOBE = listOf(
            WALKED_LATITUDE to WALKED_LONGITUDE,
            FAR_AWAY_LATITUDE to FAR_AWAY_LONGITUDE,
            -0.0009 to -0.0009,
            -89.9 to -179.9,
            0.0 to 0.0,
        )

        const val POINTS_IN_ONE_CELL = 25

        /**
         * A 250th of a cell, expressed against the granularity rather than as a fixed number of
         * degrees, so 25 steps stay inside one cell however the cell size is retuned.
         */
        const val A_STEP_WELL_INSIDE_ONE_CELL = 1.0 / (TrackPointCells.CELLS_PER_DEGREE * 250)

        /**
         * Half the real granularity: a plausible retune, not a value nobody could ever choose.
         *
         * DERIVED from the production constant rather than written as a literal, and the A/B round
         * is why. Mutation 3 set `CELLS_PER_DEGREE_SQL` to `"250.0"`, whereupon a hardcoded stale
         * value became IDENTICAL to the real one and this case was comparing 250 against 250. It
         * still went red, so nothing was missed — but a genuine retune of the granularity to 250
         * would have reddened it for a fixture reason with nothing wrong in the code, which is the
         * standing rule about a test that can fail for a reason unrelated to its subject.
         */
        val STALE_CELLS_PER_DEGREE: String =
            (TrackPointCells.CELLS_PER_DEGREE_SQL.toDouble() / 2.0).toString()
    }
}
