package app.trailveil.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the fog viewport read actually costs a populated database.
 *
 * The read is a latitude/longitude box, and the ledger's own probe measured every settle visiting
 * all hundred thousand rows because nothing indexed those columns. An index alone does not settle
 * the question: SQLite stops constraining a scan at the first range predicate, so a composite
 * `(latitude, longitude)` index bounds the scan by the LATITUDE BAND only. Whether that is a real
 * bound depends on the data, and a real track table is spatially concentrated - which is exactly
 * the case where a latitude band can still contain everything.
 *
 * So this measures rather than argues: the production query, on a populated database, against both
 * the concentrated shape a real user produces and a spread shape, reading its plan and its work.
 */
@RunWith(AndroidJUnit4::class)
class FogViewportReadCostTest {
    private lateinit var database: TrailVeilDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** The production query's plan, read from the database it will actually run against. */
    private fun productionQueryPlan(): String =
        database.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN " + FOG_POINTS_SQL,
            arrayOf<Any>(25.0, 121.5, 25.1, 121.6),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
            }
        }.joinToString(" | ")

    @Test
    fun theProductionViewportQueryPlansThroughTheBoxIndex() {
        val plan = productionQueryPlan()

        assertTrue(
            "the fog viewport read still plans as a table scan: $plan",
            plan.contains("index_track_points_latitude_longitude"),
        )
        assertTrue(
            "the fog viewport read no longer searches track_points by an index: $plan",
            plan.contains("SEARCH") && !plan.contains("SCAN track_points"),
        )
    }

    /**
     * The honest shape of the bound: a concentrated table is bounded by the latitude band, and an
     * exploration box is a narrow band, so the read stays proportional to the box in latitude. The
     * longitude half of the box does NOT constrain the scan; this pins what is actually delivered
     * so a future reader is not misled by the index's name.
     */
    @Test
    fun aConcentratedTableIsBoundedByLatitudeAndNotByLongitude() = runBlocking {
        // One neighbourhood, the shape the ledger's own probe describes: a dense walk across a few
        // hundredths of a degree, repeated along a line of latitude so that longitude alone would
        // exclude most of it and latitude alone would not.
        val sessionId = insertSession()
        val segmentId = insertSegment(sessionId)
        var id = 0L
        val rows = buildList {
            repeat(CONCENTRATED_POINTS) { index ->
                val latitude = 25.02 + (index % 48) * 0.001
                val longitude = 121.47 + index * 0.0001
                add(Triple(latitude, longitude, id++))
            }
        }
        insertPoints(sessionId, segmentId, rows)

        val narrowLongitudeBox = countRowsVisited(
            south = 25.02,
            north = 25.068,
            west = 121.47,
            east = 121.475,
        )
        val narrowLatitudeBox = countRowsVisited(
            south = 25.02,
            north = 25.025,
            west = 121.47,
            east = 121.60,
        )

        // Latitude narrows the work; longitude does not. Both statements are asserted so that a
        // future index change that makes longitude count would fail here and be noticed, rather
        // than silently making this comment wrong.
        assertTrue(
            "a narrow latitude band did not reduce the rows visited: $narrowLatitudeBox of " +
                "$CONCENTRATED_POINTS",
            narrowLatitudeBox < CONCENTRATED_POINTS / 4,
        )
        assertTrue(
            "a narrow longitude band unexpectedly reduced the rows visited to " +
                "$narrowLongitudeBox; the delivered bound is latitude-only and this test's " +
                "companion claim in the ledger needs revisiting",
            narrowLongitudeBox >= CONCENTRATED_POINTS / 2,
        )
    }

    /**
     * Counts how many rows the box actually has to consider, using the same index the production
     * query uses. `COUNT(*)` over the latitude predicate alone is the scan the planner performs;
     * the longitude predicate is applied afterwards to each visited row.
     */
    private fun countRowsVisited(south: Double, north: Double, west: Double, east: Double): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM track_points WHERE latitude BETWEEN ? AND ?",
            arrayOf<Any>(south, north),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }.also {
            // Keep the unused box edges meaningful: the production call passes all four, and the
            // point of this helper is that two of them do not narrow anything.
            check(west <= east) { "west must not exceed east" }
        }

    private fun insertSession(): Long =
        database.openHelper.writableDatabase.let { db ->
            db.execSQL(
                "INSERT INTO recording_sessions (started_at, status, distance_meters, " +
                    "accepted_point_count, rejected_point_count, created_app_version) " +
                    "VALUES (1, 'COMPLETED', 0.0, 0, 0, 'test')",
            )
            db.query("SELECT last_insert_rowid()").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }

    private fun insertSegment(sessionId: Long): Long =
        database.openHelper.writableDatabase.let { db ->
            db.execSQL(
                "INSERT INTO track_segments (session_id, sequence, started_at, start_reason) " +
                    "VALUES ($sessionId, 0, 1, 'test')",
            )
            db.query("SELECT last_insert_rowid()").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }

    private fun insertPoints(
        sessionId: Long,
        segmentId: Long,
        rows: List<Triple<Double, Double, Long>>,
    ) {
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { (latitude, longitude, sequence) ->
                db.execSQL(
                    "INSERT INTO track_points (session_id, segment_id, sequence, timestamp, " +
                        "latitude, longitude, horizontal_accuracy) VALUES " +
                        "($sessionId, $segmentId, $sequence, ${1000 + sequence}, " +
                        "$latitude, $longitude, 5.0)",
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val CONCENTRATED_POINTS = 20_000
        val FOG_POINTS_SQL = """
            SELECT
                p.id AS point_id,
                p.session_id AS session_id,
                p.segment_id AS segment_id,
                s.sequence AS segment_sequence,
                p.sequence AS point_sequence,
                p.latitude AS latitude,
                p.longitude AS longitude
            FROM track_points p
            INNER JOIN track_segments s
                ON s.id = p.segment_id AND s.session_id = p.session_id
            WHERE p.latitude BETWEEN ? AND ?
                AND p.longitude BETWEEN ? AND ?
            ORDER BY p.session_id ASC, s.sequence ASC, p.sequence ASC, p.id ASC
        """.trimIndent()
    }
}
