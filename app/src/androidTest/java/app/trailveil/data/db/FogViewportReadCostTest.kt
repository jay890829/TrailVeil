package app.trailveil.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
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
 *
 * **Every statement measured here is the one Room executed, captured as it ran.** An earlier
 * version of this file kept hand-typed copies of both `@Query` bodies and planned those. Closure
 * verification found what that permits: adding `OR p.lat_bucket = -1` to the DAO's real WHERE
 * changes no answer, so every assertion below stayed green while the read stopped being one bounded
 * pass. The copies are gone; `capture` records what the database was actually asked, and the plans
 * and row counts here come from replaying exactly that text.
 *
 * That mutation does NOT produce a table scan, and calling it one was a second error: SQLite plans
 * it as `MULTI-INDEX OR` with the same index used twice, so the clean plan's text survives verbatim
 * as one branch. Every `contains` assertion is therefore satisfied by a strict superset of the
 * clean plan — which is why the gate also requires that `track_points` be entered exactly once.
 */
@RunWith(AndroidJUnit4::class)
class FogViewportReadCostTest {
    private lateinit var database: TrailVeilDatabase
    private lateinit var dao: RecordingDao
    private val executed = CopyOnWriteArrayList<Pair<String, List<Any?>>>()

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
            .setQueryCallback({ sql, args -> executed += sql to args }, Runnable::run)
            .build()
        dao = database.recordingDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Runs the real DAO method and returns the SQL and arguments the database actually received.
     *
     * The `SELECT` filter is what makes this the READ rather than the fixture's writes or Room's
     * own bookkeeping, and the single-match assertion is what stops it silently picking one of
     * several statements if the DAO ever issues more than one.
     */
    private fun capture(block: suspend () -> Unit): Pair<String, Array<Any>> {
        executed.clear()
        runBlocking { block() }
        val selects = executed.filter { (sql, _) -> sql.trimStart().startsWith("SELECT") }
        assertEquals(
            "the fog read did not issue exactly one SELECT: ${selects.map { it.first }}",
            1,
            selects.size,
        )
        val (sql, args) = selects.single()
        @Suppress("UNCHECKED_CAST")
        return sql to (args.toTypedArray() as Array<Any>)
    }

    private fun bucketedRead(): Pair<String, Array<Any>> = capture {
        dao.fogPointsInBucketedBox(
            latitudeBuckets = requireNotNull(LatitudeBuckets.covering(BOX_SOUTH, BOX_NORTH)),
            south = BOX_SOUTH,
            west = BOX_WEST,
            north = BOX_NORTH,
            east = BOX_EAST,
        )
    }

    private fun fallbackRead(): Pair<String, Array<Any>> = capture {
        dao.fogPointsInLongitudeInterval(
            south = BOX_SOUTH,
            west = BOX_WEST,
            north = BOX_NORTH,
            east = BOX_EAST,
        )
    }

    private fun planOf(statement: Pair<String, Array<Any>>): String =
        database.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN " + statement.first, statement.second)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
                }
            }.joinToString(" | ")

    private fun rowsFrom(statement: Pair<String, Array<Any>>): Int =
        database.openHelper.readableDatabase
            .query(statement.first, statement.second)
            .use { cursor -> cursor.count }

    @Test
    fun theProductionViewportQueryPlansThroughTheBoxIndex() {
        insertConcentratedTrack()

        val plan = planOf(bucketedRead())

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
     * account rather than the test's. It is written this way after three earlier attempts measured
     * their own construction instead: one counted rows returned by an in-memory fake, the next
     * counted rows matching a predicate the test had written, and the third planned a hand-typed
     * copy of the `@Query` body. The statement planned here is the one Room ran.
     *
     * `P4-036` delivered the two-dimensional bound the predecessor entry recorded the absence of.
     * The engine now names BOTH columns in its own constraint list - the latitude band arrives as
     * an equality (an `IN` list is a set of equalities, which is why the next index column can
     * still be range-bounded) and the longitude box narrows the scan inside it. If a future change
     * loses either half, this fails and says which.
     */
    @Test
    fun theEngineReportsTheViewportScanBoundedByBothDimensions() {
        insertConcentratedTrack()

        val bucketed = bucketedRead()
        val plan = planOf(bucketed)

        assertTrue(
            "the fog viewport read no longer searches through the spatial index: $plan",
            plan.contains("USING INDEX index_track_points_lat_bucket_longitude"),
        )
        assertTrue(
            "the engine no longer reports the latitude bucket as an equality: $plan",
            plan.contains("lat_bucket=?"),
        )
        assertTrue(
            "the engine no longer constrains the scan by longitude, so the read is one-dimensional " +
                "again: $plan",
            plan.contains("longitude>? AND longitude<?"),
        )
        // And the box is ONE pass through ONE index. This is not an anti-mutation hack: a plan that
        // enters `track_points` twice is doing more work than a rectangle needs, and SQLite says so
        // by name. It is here because closure round 2 measured that the previous three assertions
        // are all satisfied by a plan that has already lost the bound. Appending
        // `OR p.lat_bucket = -1` to the shipped WHERE - which is what operator precedence gives you
        // for free, and the placement a careless edit would produce - yields
        // `MULTI-INDEX OR | INDEX 1 | SEARCH p ... (lat_bucket=? AND longitude>? AND longitude<?) |
        // INDEX 2 | SEARCH p ... (lat_bucket=?)`: both column names still appear, no `SCAN p`
        // appears, the answer is unchanged, and the second arm walks the whole bucket set.
        assertTrue(
            "the fog viewport read now enters the track points through more than one index arm, " +
                "so the constraint list above describes only part of the work: $plan",
            !plan.contains("MULTI-INDEX OR"),
        )
        assertEquals(
            "the fog viewport read searches the track points more than once: $plan",
            1,
            Regex("SEARCH p ").findAll(plan).count(),
        )
        // The fallback DOES plan as a scan now, and that is the deliberate trade rather than an
        // oversight: `P4-036` retired the `(latitude, longitude)` index because keeping both cost
        // a measured +13.1% on every write, and dropping it paid for the new one exactly. The
        // fallback only runs for a band taller than about 1.28 degrees. Asserted rather than
        // assumed so the trade stays visible: this IS an unindexed full scan, and the entry
        // discloses it as the cost of the swap rather than pretending the box always contains
        // everything out there.
        val fallback = fallbackRead()
        val fallbackPlan = planOf(fallback)
        assertTrue(
            "the fallback viewport read unexpectedly found an index: $fallbackPlan",
            fallbackPlan.contains("SCAN p"),
        )
        // And the band is narrowing real work, not merely named in a plan: half this table lies
        // outside it and must not come back.
        val returned = rowsFrom(bucketed)
        val total = totalRows()
        // The bucket narrows the scan; it must never decide the answer. Both routes carry the same
        // latitude and longitude predicates, so a disagreement here means the equality set dropped
        // a stripe - an under-read, which draws MORE fog and so passes every leak audit.
        assertEquals(
            "the bucketed read and its fallback disagree about the box's contents",
            rowsFrom(fallback),
            returned,
        )
        assertEquals(
            "the viewport box returned rows from outside the queried band: $returned of $total",
            CONCENTRATED_POINTS,
            returned,
        )
    }

    /**
     * How many rows the index actually makes the engine touch, which is the number the entry's
     * headline is stated in.
     *
     * **What this is, stated exactly, because a previous version overstated it.** It is not a
     * measurement of rows visited: `sqlite3_stmt_scanstatus` is not exposed on Android, and there
     * is no way from here to count what the engine actually walked. It is a PLAN CLASSIFIER that
     * then computes what a plan of that shape would visit. The classification comes from the
     * engine's own constraint list rather than from what this test believes the query does, which
     * is the part that matters and the part the first version got wrong.
     *
     * The limit that follows is real and worth naming: a mutation whose plan text is BYTE-IDENTICAL
     * to the shipped one cannot be distinguished here, however much work it actually does. Closure
     * round 3 built one — an extra redundant `longitude` bound that SQLite plans identically while
     * walking 1 722 rows instead of 82 — and no plan-derived gate can see it. The rows-visited
     * numbers in the ledger are a HOST measurement taken with a counting mechanism this platform
     * does not offer.
     *
     * The first version of this test counted rows with a hand-written
     * `WHERE lat_bucket IN (...) AND longitude BETWEEN ?` that had no connection to the production
     * statement, so every number was plan-invariant and no mutation of the real query could move
     * them. Now the plan's constraint list decides which predicates bound the count: if SQLite
     * stops reporting `longitude>?`, the longitude predicate leaves the count, `visited` jumps to
     * the whole latitude band, and the ratio below fails — which is what the A/B measures.
     */
    @Test
    fun theIndexNarrowsTheRowsVisitedAndNotOnlyTheRowsReturned() {
        insertConcentratedTrack()

        val buckets = requireNotNull(LatitudeBuckets.covering(NARROW_SOUTH, NARROW_NORTH))
        val statement = capture {
            dao.fogPointsInBucketedBox(
                latitudeBuckets = buckets,
                south = NARROW_SOUTH,
                west = NARROW_WEST,
                north = NARROW_NORTH,
                east = NARROW_EAST,
            )
        }
        val visited = rowsTheEnginesPlanWouldVisit(planOf(statement), buckets)
        val band = countWhere(
            "latitude BETWEEN ? AND ?",
            arrayOf<Any>(NARROW_SOUTH, NARROW_NORTH),
        )
        val returned = rowsFrom(statement)

        // Measured on this fixture, off the bucket boundary: visited 82, returned 42, band 882 -
        // so the plan touches about 1.95 rows per row it returns, and about 10.8x fewer than the
        // latitude band the retired `(latitude, longitude)` index would have walked. The thresholds
        // sit well outside those (82 against a ceiling of 168, and 328 against 882), because the
        // point is to catch a LOST DIMENSION - which sends visited to the full band, an order of
        // magnitude away, measured at 1 722 - rather than to pin a fixture-specific ratio. An
        // earlier version used `returned * 2`, a margin of two rows, on a fixture whose edges sat
        // exactly on bucket boundaries; it could not fail for an index reason and was one
        // 0.0001-degree edit from failing for no reason at all.
        assertTrue(
            "the bucketed read visits $visited rows to return $returned, which is not a bound " +
                "proportional to the box",
            visited <= returned * 4,
        )
        assertTrue(
            "the latitude band holds $band rows and the plan still visits $visited of them, so " +
                "the longitude half of the box narrows nothing",
            visited * 4 <= band,
        )
    }

    /**
     * The rows the engine's reported constraint list actually bounds the scan to.
     *
     * SQLite names the constrained columns in the plan text; this turns that list back into a
     * count. A column the plan does NOT name contributes no predicate, which is exactly how a lost
     * dimension shows up as a larger number instead of as an unchanged one.
     */
    private fun rowsTheEnginesPlanWouldVisit(plan: String, buckets: IntArray): Int {
        val constraints = Regex("""\(([^)]*)\)""").find(plan)?.groupValues?.get(1).orEmpty()
        val predicates = mutableListOf<String>()
        val arguments = mutableListOf<Any>()
        if (constraints.contains("lat_bucket=")) {
            predicates += "lat_bucket IN (${buckets.joinToString(",") { "?" }})"
            buckets.forEach { arguments += it }
        }
        if (constraints.contains("longitude>")) {
            predicates += "longitude BETWEEN ? AND ?"
            arguments += NARROW_WEST
            arguments += NARROW_EAST
        }
        assertTrue(
            "the engine's plan constrains the scan by nothing at all, so this measures the whole " +
                "table: $plan",
            predicates.isNotEmpty(),
        )
        return countWhere(predicates.joinToString(" AND "), arguments.toTypedArray())
    }

    private fun countWhere(predicate: String, arguments: Array<Any>): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM track_points WHERE $predicate", arguments)
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    /**
     * The exact `latitude BETWEEN` predicate decides membership; the bucket only narrows the scan.
     *
     * This is the entry's central design claim and nothing bound it: a bucket is about 223 m tall,
     * every other fixture here sits comfortably inside its cover, and deleting the latitude
     * predicate from the shipped query left the whole suite green. The row below is placed INSIDE
     * the bucket cover and OUTSIDE the requested band - the only place where the two disagree - so
     * dropping the predicate returns it and this fails.
     */
    @Test
    fun aRowInsideTheBucketCoverButOutsideTheBandIsNotReturned() {
        val sessionId = insertSession()
        val segmentId = insertSegment(sessionId)
        val north = 25.0200
        val bucketTop = (LatitudeBuckets.of(north) + 1) / LatitudeBuckets.BUCKETS_PER_DEGREE - 90.0
        val intruder = (north + bucketTop) / 2.0
        // Same bucket as the band's northern edge, but north of the edge itself. If this ever
        // stops being true the fixture has drifted, and the test says so instead of passing.
        assertEquals(LatitudeBuckets.of(north), LatitudeBuckets.of(intruder))
        assertTrue("the intruder is not actually outside the band", intruder > north)
        insertPoints(
            sessionId,
            segmentId,
            listOf(
                Triple(north - 0.0001, 121.4700, 0L),
                Triple(intruder, 121.4701, 1L),
            ),
        )

        val returned = runBlocking {
            dao.fogPointsInBucketedBox(
                latitudeBuckets = requireNotNull(LatitudeBuckets.covering(25.0100, north)),
                south = 25.0100,
                west = 121.4600,
                north = north,
                east = 121.4800,
            )
        }

        assertEquals(
            "a row inside the bucket cover but north of the requested band came back, so the " +
                "bucket is deciding membership instead of narrowing the scan",
            listOf(0L),
            returned.map { it.pointSequence },
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

    private fun totalRows(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_points")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    private fun insertSession(): Long =
        database.openHelper.writableDatabase.let { db ->
            // A finished session as the invariants define one: COMPLETED carries an ended_at
            // and holds no active slot. The counts stay at zero deliberately - this fixture
            // measures the fog read's plan, and the summary columns are another entry's
            // subject; the triggers require only that they be non-negative.
            db.execSQL(
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
            // Closed to match its session: a segment is either open (no ended_at, no
            // end_reason, open_slot 1) or closed with both - the invariants reject the halves.
            db.execSQL(
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

        /** The viewport box every plan measurement here uses; the concentrated track sits inside it. */
        const val BOX_SOUTH = 25.020
        const val BOX_NORTH = 25.068
        const val BOX_WEST = 121.470
        const val BOX_EAST = 121.546

        /**
         * A city-block viewport, for the rows-visited ratio.
         *
         * The plan gates use the whole walked block deliberately, because a box that contains
         * everything still proves the plan. The narrowing ratio needs the opposite: a box small
         * enough that the latitude band around it holds far more than the box does, which is the
         * zoom a user actually explores at.
         */
        // Deliberately OFF a bucket boundary. 25.0300 and 25.0320 are both exact multiples of the
        // bucket height at 500 per degree, so a box there always covers (2N+1) buckets against
        // (N+1) of real span - the over-cover is structural, the ratio is at its worst, and the
        // fixture is one 0.0001-degree edit away from a red that means nothing about the index.
        const val NARROW_SOUTH = 25.0303
        const val NARROW_NORTH = 25.0323
        const val NARROW_WEST = 121.4700
        const val NARROW_EAST = 121.4720

        /** As many again, far outside the queried latitude band, so the band has work to exclude. */
        const val DECOY_POINTS = 20_000
    }
}
