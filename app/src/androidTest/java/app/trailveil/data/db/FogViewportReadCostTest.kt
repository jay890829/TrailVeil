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
        // Opened with the invariant callback, exactly as production opens it. This matters twice
        // over. The fixture writes raw SQL, so `P4-036`'s derived bucket arrives only if the
        // trigger repairs it - built without the callback, every seeded row kept an unset bucket
        // and the bucketed read returned nothing at all, which is precisely the silent shape the
        // trigger exists to prevent. And the callback also holds the session and segment
        // invariants to the real rules, which caught this fixture seeding a COMPLETED session that
        // had never ended: rows in a shape the app cannot produce are not evidence about the app.
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * The production query's plan, read from the database it will actually run against.
     *
     * The bucket list is expanded into placeholders the way Room expands `IN (:latitudeBuckets)`,
     * so the plan measured here is the plan the app gets rather than a hand-written approximation.
     */
    private fun productionQueryPlan(): String {
        val buckets = requireNotNull(LatitudeBuckets.covering(BOX_SOUTH, BOX_NORTH))
        val sql = "EXPLAIN QUERY PLAN " + FOG_POINTS_SQL.format(buckets.joinToString(",") { "?" })
        val arguments = buildList<Any> {
            buckets.forEach { add(it) }
            add(BOX_WEST)
            add(BOX_EAST)
            add(BOX_SOUTH)
            add(BOX_NORTH)
        }.toTypedArray()
        return database.openHelper.readableDatabase.query(sql, arguments).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
            }
        }.joinToString(" | ")
    }

    /** The fallback's plan, for the band too tall to spell as equalities. */
    private fun fallbackQueryPlan(): String =
        database.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN " + FOG_POINTS_RANGE_SQL,
            arrayOf<Any>(BOX_SOUTH, BOX_NORTH, BOX_WEST, BOX_EAST),
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
            plan.contains("index_track_points_lat_bucket_longitude"),
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
    fun theEngineReportsTheViewportScanBoundedByBothDimensions() {
        insertConcentratedTrack()

        val plan = productionQueryPlan()

        assertTrue(
            "the fog viewport read no longer searches through the spatial index: $plan",
            plan.contains("USING INDEX index_track_points_lat_bucket_longitude"),
        )
        // P4-036 delivered the two-dimensional bound this assertion used to record the absence of.
        // The engine now names BOTH columns in its own constraint list - the latitude band arrives
        // as an equality (an IN list is a set of equalities, which is why the next index column can
        // still be range-bounded) and the longitude box narrows the scan inside it. If a future
        // change loses either half, this fails and says which.
        assertTrue(
            "the engine no longer reports the latitude bucket as an equality: $plan",
            plan.contains("lat_bucket=?"),
        )
        assertTrue(
            "the engine no longer constrains the scan by longitude, so the read is one-dimensional " +
                "again: $plan",
            plan.contains("longitude>? AND longitude<?"),
        )
        // The fallback DOES plan as a scan now, and that is the deliberate trade rather than an
        // oversight: `P4-036` retired the `(latitude, longitude)` index because keeping both cost
        // a measured +15.3% on every write, and dropping it paid for the new one exactly. The
        // fallback only runs for a band taller than about 1.3 degrees, which is coarser than any
        // zoom where an index helps at all - the box already contains the whole table there, and
        // all candidate designs measured 1.00x. Asserted rather than assumed so the trade stays
        // visible: if the fallback ever starts running at an exploration zoom, this says so.
        val fallback = fallbackQueryPlan()
        assertTrue(
            "the fallback viewport read unexpectedly found an index: $fallback",
            fallback.contains("SCAN p"),
        )
        // And the band is narrowing real work, not merely named in a plan: half this table lies
        // outside it and must not come back.
        val returned = rowsReturnedForTheBox()
        val total = totalRows()
        // The bucket narrows the scan; it must never decide the answer. Both routes carry the same
        // latitude and longitude predicates, so a disagreement here means the equality set dropped
        // a stripe - an under-read, which draws MORE fog and so passes every leak audit.
        assertEquals(
            "the bucketed read and its fallback disagree about the box's contents",
            rowsReturnedByTheFallback(),
            returned,
        )
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
     * latitude (about 5.3 km) and 0.0492 of longitude (about 5.0 km at this latitude), a rough
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
    private fun rowsReturnedForTheBox(): Int {
        val buckets = requireNotNull(LatitudeBuckets.covering(BOX_SOUTH, BOX_NORTH))
        val sql = FOG_POINTS_SQL.format(buckets.joinToString(",") { "?" })
        val arguments = buildList<Any> {
            buckets.forEach { add(it) }
            add(BOX_WEST)
            add(BOX_EAST)
            add(BOX_SOUTH)
            add(BOX_NORTH)
        }.toTypedArray()
        return database.openHelper.readableDatabase.query(sql, arguments)
            .use { cursor -> cursor.count }
    }

    /** The same rows by the fallback route, so "the bucket never changes the answer" is measured. */
    private fun rowsReturnedByTheFallback(): Int =
        database.openHelper.readableDatabase.query(
            FOG_POINTS_RANGE_SQL,
            arrayOf<Any>(BOX_SOUTH, BOX_NORTH, BOX_WEST, BOX_EAST),
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
                // A finished session as the invariants define one: COMPLETED carries an ended_at
                // and holds no active slot. The counts stay at zero deliberately - this fixture
                // measures the fog read's plan, and the summary columns are another entry's
                // subject; the triggers require only that they be non-negative.
                "INSERT INTO recording_sessions (started_at, status, ended_at, distance_meters, " +
                    "accepted_point_count, rejected_point_count, created_app_version) " +
                    "VALUES (1, 'COMPLETED', 2, 0.0, 0, 0, 'test')",
            )
            db.query("SELECT last_insert_rowid()").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }

    private fun insertSegment(sessionId: Long): Long =
        database.openHelper.writableDatabase.let { db ->
            db.execSQL(
                // Closed to match its session: a segment is either open (no ended_at, no
                // end_reason, open_slot 1) or closed with both - the invariants reject the halves.
                "INSERT INTO track_segments (session_id, sequence, started_at, start_reason, " +
                    "ended_at, end_reason) VALUES ($sessionId, 0, 1, 'test', 2, 'test')",
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

        /** The viewport box every measurement here uses; the concentrated track sits inside it. */
        const val BOX_SOUTH = 25.020
        const val BOX_NORTH = 25.068
        const val BOX_WEST = 121.470
        const val BOX_EAST = 121.546

        /** As many again, far outside the queried latitude band, so the band has work to exclude. */
        const val DECOY_POINTS = 20_000
        /** The bucketed production query `P4-036` shipped; see [FOG_POINTS_RANGE_SQL] for its
         *  fallback twin, which is the same answer by the older, one-dimensional route. */
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
            WHERE p.lat_bucket IN (%s)
                AND p.longitude BETWEEN ? AND ?
                AND p.latitude BETWEEN ? AND ?
            ORDER BY p.session_id ASC, s.sequence ASC, p.sequence ASC, p.id ASC
        """.trimIndent()

        /** The fallback for a band too tall to spell as equalities: identical predicates, older plan. */
        val FOG_POINTS_RANGE_SQL = """
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
