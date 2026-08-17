package app.trailveil.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
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
 * So this measures rather than argues: the production query's plan, taken from the engine against a
 * populated database holding the concentrated shape a real user produces.
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
            // south, north, west, east - the order Room binds the generated SQL's placeholders
            // in, which is not the order the DAO's parameter list declares. The plan is unaffected
            // by the values; the returned-row assertions below are not, so this order matters.
            arrayOf<Any>(25.020, 25.068, 121.470, 121.546),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
            }
        }.joinToString(" | ")

    @Test
    fun theProductionViewportQueryPlansThroughTheBoxIndex() {
        insertConcentratedTrack()

        val plan = productionQueryPlan()

        assertTrue(
            "the fog viewport read still plans as a table scan: $plan",
            plan.contains("index_track_points_latitude_longitude"),
        )
        // The plan names track_points by its query alias, so a scan of it reads "SCAN p" - which
        // is what a dropped index produces, and what an assertion looking for "SCAN track_points"
        // would happily accept.
        assertTrue(
            "the fog viewport read scans the track points instead of searching them: $plan",
            !plan.contains("SCAN p"),
        )
    }

    /**
     * What the engine says it constrains the scan by, read from the plan of the production query
     * against a populated table.
     *
     * SQLite names the bounding columns in the plan text itself, so this is the database's own
     * account rather than the test's. It is written this way after two earlier attempts measured
     * their own construction instead: one counted rows returned by an in-memory fake, the next
     * counted rows matching a predicate the test had written. A plan string can do neither.
     *
     * Today the engine reports latitude alone, because SQLite stops applying index constraints at
     * the first range predicate - so the longitude half of the viewport box narrows nothing, and
     * the bound this entry delivers is the latitude band. When `P4-036` gives the table a spatial
     * key, the constraint list changes and this assertion fails, which is the point: the successor
     * work cannot land while the ledger still describes a one-dimensional bound.
     */
    @Test
    fun theEngineReportsTheViewportScanBoundedByLatitudeAlone() {
        insertConcentratedTrack()

        val plan = productionQueryPlan()

        assertTrue(
            "the fog viewport read no longer searches through the box index: $plan",
            plan.contains("USING INDEX index_track_points_latitude_longitude"),
        )
        assertTrue(
            "the engine now reports a different constraint list for the viewport scan, so the " +
                "one-dimensional bound this entry records is out of date: $plan",
            plan.contains("(latitude>? AND latitude<?)"),
        )
        // And the band is narrowing real work, not merely named in a plan: half this table lies
        // outside it and must not come back.
        val returned = rowsReturnedForTheBox()
        val total = totalRows()
        assertEquals(
            "the viewport box returned rows from outside the queried band: $returned of $total",
            CONCENTRATED_POINTS,
            returned,
        )
        assertTrue(
            "the box returned $returned of $total rows, which is not a bounded slice of the table",
            returned <= total / 2,
        )
    }

    /**
     * A walked neighbourhood inside the queried band, and as many points again well outside it.
     *
     * The decoys are what make "populated" evidence rather than decoration: SQLite's plan for this
     * query is identical on an empty table, so a fixture whose every row satisfies the box proves
     * nothing about narrowing. With half the table outside the latitude band, the returned
     * fraction is a fact about the engine's work, and a regression that stopped using the index
     * would have to visit them all.
     *
     * Extents are stated as the arithmetic produces them: the walked block spans 0.0479 degrees of
     * latitude (about 5.3 km) and 0.0479 of longitude (about 4.8 km at this latitude), a rough
     * square; the decoys sit three to five degrees away, hundreds of kilometres north and south.
     */
    private fun insertConcentratedTrack() {
        val sessionId = insertSession()
        val segmentId = insertSegment(sessionId)
        var sequence = 0L
        val rows = buildList {
            repeat(CONCENTRATED_POINTS) { index ->
                add(
                    Triple(
                        25.020 + (index % 480) * 0.0001,
                        121.470 + (index / 480) * 0.0001 * 12.0,
                        sequence++,
                    ),
                )
            }
            repeat(DECOY_POINTS) { index ->
                val northward = index % 2 == 0
                val offset = 3.0 + (index % 400) * 0.005
                add(
                    Triple(
                        if (northward) 25.020 + offset else 25.020 - offset,
                        121.470 + (index % 480) * 0.0001,
                        sequence++,
                    ),
                )
            }
        }
        insertPoints(sessionId, segmentId, rows)
    }

    /** How many rows the production query actually returns for the queried box. */
    private fun rowsReturnedForTheBox(): Int =
        database.openHelper.readableDatabase.query(
            FOG_POINTS_SQL,
            arrayOf<Any>(25.020, 25.068, 121.470, 121.546),
        ).use { cursor -> cursor.count }

    private fun totalRows(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_points")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
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

        /** As many again, far outside the queried latitude band, so the band has work to exclude. */
        const val DECOY_POINTS = 20_000
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
