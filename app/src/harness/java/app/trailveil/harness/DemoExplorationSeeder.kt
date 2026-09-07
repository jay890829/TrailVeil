package app.trailveil.harness

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import app.trailveil.BuildConfig
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.LatitudeBuckets
import app.trailveil.data.db.TrackPointCells
import app.trailveil.data.db.TrailVeilDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `V03-013`: puts explored ground on the map without walking, in the harness build types alone.
 *
 * The arm-comparison artifacts were handed over able to switch between seven fog designs and unable
 * to show any of them anything but a fully fogged map, because a fresh install has walked nowhere.
 * A fog design is a BOUNDARY design; with no boundary on screen every arm looks identical, and the
 * comparison the whole task exists for cannot be made.
 *
 * Two fixtures, because the owner asked two different questions of the build:
 *
 * - [seedHere] writes ONE session where the person is standing. This is the comparison fixture: the
 *   boundary has to be on the screen they are looking at, or there is nothing to judge.
 * - [seedWorld] writes 300 sessions of about a thousand points each, scattered over the planet by
 *   [DemoWorldAnchors]. This is the LOAD fixture, and it answers a different question - what the fog
 *   does when the database holds ~307,000 points and the explored ground is not all in one place.
 *   It is not a substitute for the first: its anchors are nowhere near the person, so on its own it
 *   leaves the screen exactly as fogged as an empty database does.
 *
 * **What is written is marked as what it is, in three independent ways**, because synthetic points
 * in a database whose whole purpose is a truthful record of where someone went is a thing that must
 * never be mistaken for a real exploration:
 *
 * - every point carries `is_mock = 1`, the column the schema already has for exactly this;
 * - the session's stop reason is [DEMO_STOP_REASON], which is also how [clear] finds it again, so
 *   the mark is load-bearing rather than decorative - an unmarked row would be unremovable;
 * - the session is written `COMPLETED`, never `ACTIVE`, so it holds no active slot and no location
 *   owner token and cannot collide with the recording state machine or be resumed.
 *
 * **The write goes around the DAO, and the derived state still comes out right.** `lat_bucket` and
 * `track_point_cells` are maintained by database triggers rather than by the DAO -
 * `TrackPointCellDerivationTest` pins that a point written in raw SQL is bucketed and celled anyway
 * - so this takes the same route every read-cost fixture in the suite takes. The one thing triggers
 * do NOT do is repair cells after a DELETE, which is why the clear path runs
 * [TrackPointCells.BACKFILL_SQL]: a stale cell makes the world-zoom read claim ground that no
 * longer has a point in it, which reveals MORE than was earned and is the one direction the fog
 * must never fail in.
 *
 * The transaction is Room's own, not the helper's, because ending a Room transaction is what
 * refreshes the invalidation tracker - and that is what makes the fog rebuild while you watch
 * instead of on the next launch. The world fixture takes one transaction PER SESSION rather than one
 * for all 300: it bounds how much is lost if something goes wrong, it lets progress be reported
 * truthfully, and it lets the map fill in as it goes.
 */
internal object DemoExplorationSeeder {

    /** The mark that makes seeded data findable, and therefore removable. */
    const val DEMO_STOP_REASON = "V03_013_DEMO_SEED"

    private const val DEMO_START_REASON = "V03_013_DEMO_SEED"

    /**
     * Where the walk goes when the device will not say where it is.
     *
     * A public park in Taipei, chosen because a fallback anchor is a coordinate that ends up in a
     * screenshot: it must be somewhere recognisable and belong to nobody. It is never used when the
     * device has a location, which on a phone in the owner's hand is the normal case.
     */
    const val FALLBACK_LATITUDE = 25.0296
    const val FALLBACK_LONGITUDE = 121.5357

    /**
     * How old a fix may be and still count as "where you are".
     *
     * Half an hour is long enough to cover a phone that has been in a pocket indoors and short
     * enough that it cannot be a different place.
     */
    private const val MAX_LOCATION_AGE_NANOS = 30L * 60L * 1_000_000_000L

    /** Two seconds a point, which is what a real recording cadence looks like. */
    private const val POINT_INTERVAL_MILLIS = 2_000L

    /** One day between the world fixture's sessions, so the history screen reads as a long past. */
    private const val SESSION_SPACING_MILLIS = 24L * 60L * 60L * 1_000L

    /** Good enough to be accepted, honest about being synthetic. */
    private const val HORIZONTAL_ACCURACY_METRES = 4.0

    data class Anchor(val latitude: Double, val longitude: Double, val fromDevice: Boolean)

    data class Outcome(val sessions: Int, val points: Int, val anchor: Anchor?)

    /**
     * The anchor to seed around: the device's own last known position when it will give one.
     *
     * Seeding where the person actually is, is the difference between a demo they can walk into and
     * a demo they have to go looking for on a world map. Permission is checked rather than assumed,
     * and a refusal is answered with the fallback rather than an error - a harness that cannot seed
     * because a permission dialog has not been answered yet is a harness nobody uses.
     */
    fun anchor(context: Context): Anchor {
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            val manager = context.getSystemService(LocationManager::class.java)
            val now = SystemClock.elapsedRealtimeNanos()
            val freshest = manager?.getProviders(true).orEmpty()
                .mapNotNull { provider ->
                    runCatching { manager?.getLastKnownLocation(provider) }.getOrNull()
                }
                .filter { location ->
                    location.latitude.isFinite() && location.longitude.isFinite()
                }
                .maxByOrNull { location -> location.elapsedRealtimeNanos }
            // FRESHNESS is the test, not presence, and this is not defensive padding: a phone keeps
            // yesterday's fix after a flight, and seeding at a stale position puts the demo
            // somewhere the person is not - what they see is an empty map, indistinguishable from
            // the seeder having failed. A named park they can pan to is a worse anchor and a far
            // better failure. (It does not save the emulator, whose 0.012345, 0.066932 placeholder
            // is republished continuously and so is genuinely fresh.)
            if (freshest != null &&
                now - freshest.elapsedRealtimeNanos <= MAX_LOCATION_AGE_NANOS
            ) {
                return Anchor(freshest.latitude, freshest.longitude, fromDevice = true)
            }
        }
        return Anchor(FALLBACK_LATITUDE, FALLBACK_LONGITUDE, fromDevice = false)
    }

    /** The comparison fixture: one session, here, at the density the arms are judged at. */
    suspend fun seedHere(context: Context, anchor: Anchor): Outcome = withContext(Dispatchers.IO) {
        val database = database(context)
        clearInternal(database)
        val components = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude)
        database.runInTransaction {
            writeSession(
                helper = database.openHelper.writableDatabase,
                components = components,
                distanceMetres = DemoExplorationTrack.lengthMetres(),
                endedAt = System.currentTimeMillis(),
            )
        }
        Outcome(sessions = 1, points = components.sumOf { it.size }, anchor = anchor)
    }

    /**
     * The load fixture: many sessions, scattered, dense.
     *
     * [onProgress] is called with the number of sessions written so far. It is a suspend function so
     * the caller can hop to the main thread to touch UI state; this loop never does that itself.
     */
    suspend fun seedWorld(
        context: Context,
        sessions: Int = DemoWorldAnchors.DEFAULT_SESSIONS,
        pointsPerSession: Int = DemoWorldAnchors.DEFAULT_POINTS_PER_SESSION,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Outcome = withContext(Dispatchers.IO) {
        val database = database(context)
        clearInternal(database)
        val distance = DemoExplorationTrack.lengthMetres(pointsPerSession)
        val now = System.currentTimeMillis()
        var written = 0
        DemoWorldAnchors.anchors(sessions).forEachIndexed { index, anchor ->
            val components = DemoExplorationTrack.componentsAround(
                anchor.latitude,
                anchor.longitude,
                pointsPerSession,
            )
            database.runInTransaction {
                writeSession(
                    helper = database.openHelper.writableDatabase,
                    components = components,
                    distanceMetres = distance,
                    endedAt = now - index * SESSION_SPACING_MILLIS,
                )
            }
            written += components.sumOf { it.size }
            if ((index + 1) % PROGRESS_EVERY_SESSIONS == 0 || index + 1 == sessions) {
                onProgress(index + 1, sessions)
            }
        }
        Outcome(sessions = sessions, points = written, anchor = null)
    }

    /** Removes seeded data and nothing else, leaving any real exploration untouched. */
    suspend fun clear(context: Context): Int = withContext(Dispatchers.IO) {
        clearInternal(database(context))
    }

    /** How many seeded points are currently stored, so the UI can state a fact rather than a hope. */
    suspend fun seededPointCount(context: Context): Int = withContext(Dispatchers.IO) {
        val helper = database(context).openHelper.writableDatabase
        helper.query(
            "SELECT COUNT(*) FROM track_points WHERE session_id IN " +
                "(SELECT id FROM recording_sessions WHERE stop_reason = ?)",
            arrayOf<Any>(DEMO_STOP_REASON),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    /** How many seeded sessions are stored, which is the number the world fixture is judged by. */
    suspend fun seededSessionCount(context: Context): Int = withContext(Dispatchers.IO) {
        val helper = database(context).openHelper.writableDatabase
        helper.query(
            "SELECT COUNT(*) FROM recording_sessions WHERE stop_reason = ?",
            arrayOf<Any>(DEMO_STOP_REASON),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    private fun clearInternal(database: TrailVeilDatabase): Int {
        var removed = 0
        database.runInTransaction {
            val helper = database.openHelper.writableDatabase
            removed = removeSeeded(helper)
            // Only the delete needs this. Inserts are celled by the trigger as they land.
            helper.execSQL(TrackPointCells.BACKFILL_SQL)
        }
        return removed
    }

    /**
     * One session, one segment, and its points.
     *
     * `lat_bucket` is computed here rather than left to the repair trigger. The trigger fires
     * `WHEN NEW.lat_bucket != <expr>`, so a correct value makes it a no-op - and at the load
     * fixture's scale that is the difference between 307,000 inserts and 307,000 inserts plus
     * 307,000 UPDATEs. [LatitudeBuckets.of] is the same arithmetic the trigger carries, which is
     * exactly why the trigger still has to exist: it is what makes being wrong here survivable.
     */
    private fun writeSession(
        helper: SupportSQLiteDatabase,
        components: List<List<DemoTrackPoint>>,
        distanceMetres: Double,
        endedAt: Long,
    ) {
        val total = components.sumOf { it.size }
        val startedAt = (endedAt - total * POINT_INTERVAL_MILLIS).coerceAtLeast(0L)
        val sessionId = helper.insert(
            "recording_sessions",
            CONFLICT_ABORT,
            ContentValues().apply {
                put("started_at", startedAt)
                put("ended_at", endedAt)
                put("status", "COMPLETED")
                put("stop_reason", DEMO_STOP_REASON)
                put("distance_meters", distanceMetres)
                put("accepted_point_count", total.toLong())
                put("rejected_point_count", 0L)
                put("created_app_version", BuildConfig.VERSION_NAME)
                putNull("active_slot")
                putNull("boot_id")
                putNull("location_owner_token")
            },
        )

        val statement = helper.compileStatement(
            "INSERT INTO track_points(session_id, segment_id, sequence, timestamp, latitude, " +
                "longitude, horizontal_accuracy, lat_bucket, is_mock) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
        )
        var written = 0
        // One segment per component. The fog reveals the capsule swept between consecutive points
        // in a segment, so a single segment would draw a revealed corridor along each 700 m jump -
        // see [DemoExplorationTrack.components].
        components.forEachIndexed { componentIndex, points ->
            if (points.isEmpty()) return@forEachIndexed
            val segmentStart = startedAt + written * POINT_INTERVAL_MILLIS
            val segmentEnd = segmentStart + points.size * POINT_INTERVAL_MILLIS
            val segmentId = helper.insert(
                "track_segments",
                CONFLICT_ABORT,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("sequence", componentIndex.toLong())
                    put("started_at", segmentStart)
                    put("ended_at", segmentEnd)
                    put("start_reason", DEMO_START_REASON)
                    put("end_reason", DEMO_STOP_REASON)
                    putNull("open_slot")
                },
            )
            points.forEachIndexed { index, point ->
                statement.clearBindings()
                statement.bindLong(1, sessionId)
                statement.bindLong(2, segmentId)
                statement.bindLong(3, index.toLong())
                statement.bindLong(4, segmentStart + index * POINT_INTERVAL_MILLIS)
                statement.bindDouble(5, point.latitude)
                statement.bindDouble(6, point.longitude)
                statement.bindDouble(7, HORIZONTAL_ACCURACY_METRES)
                statement.bindLong(8, LatitudeBuckets.of(point.latitude).toLong())
                statement.executeInsert()
            }
            written += points.size
        }
    }

    /**
     * Deletes children before parents rather than trusting the cascade.
     *
     * The foreign keys do cascade, but only while the `foreign_keys` pragma is on, and that is a
     * connection property rather than a schema one. Spelling the three deletes out costs two extra
     * statements and removes the dependency entirely; leaving orphaned points behind would be a
     * demo that cannot be turned off.
     */
    private fun removeSeeded(helper: SupportSQLiteDatabase): Int {
        val selector =
            "(SELECT id FROM recording_sessions WHERE stop_reason = '$DEMO_STOP_REASON')"
        val before = helper.query(
            "SELECT COUNT(*) FROM track_points WHERE session_id IN $selector",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        helper.execSQL("DELETE FROM track_points WHERE session_id IN $selector")
        helper.execSQL("DELETE FROM track_segments WHERE session_id IN $selector")
        helper.execSQL(
            "DELETE FROM recording_sessions WHERE stop_reason = ?",
            arrayOf<Any>(DEMO_STOP_REASON),
        )
        return before
    }

    /**
     * The app's OWN database instance, never a second one.
     *
     * `TrailVeilDatabase.open` builds a new instance per call, and a second instance is a second
     * connection whose writes Room's invalidation tracker would not see - the fog would then show
     * the seeded ground only after a restart, and the reason would look like a fog bug.
     */
    private fun database(context: Context): TrailVeilDatabase =
        (context.applicationContext as TrailVeilApplication).appContainer.databaseForTesting()

    private const val CONFLICT_ABORT = 2

    /** Often enough to look alive, rarely enough not to spend the run on progress updates. */
    private const val PROGRESS_EVERY_SESSIONS = 5
}
