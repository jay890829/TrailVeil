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
 * do NOT do is repair cells after a DELETE, which is why both paths here run
 * [TrackPointCells.BACKFILL_SQL]: a stale cell makes the world-zoom read claim ground that no
 * longer has a point in it, which reveals MORE than was earned and is the one direction the fog
 * must never fail in.
 *
 * The transaction is Room's own, not the helper's, because ending a Room transaction is what
 * refreshes the invalidation tracker - and that is what makes the fog rebuild while you watch
 * instead of on the next launch.
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

    /** Good enough to be accepted, honest about being synthetic. */
    private const val HORIZONTAL_ACCURACY_METRES = 4.0

    data class Anchor(val latitude: Double, val longitude: Double, val fromDevice: Boolean)

    data class Outcome(val points: Int, val anchor: Anchor, val distanceMetres: Double)

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
            // FRESHNESS is the test, not presence, and this is not defensive padding: an emulator
            // hands out a day-old placeholder at 0.012345, 0.066932 and a phone keeps yesterday's
            // fix after a flight. Seeding at a stale position puts the demo somewhere the person is
            // not, and what they see is an empty map - indistinguishable from the seeder having
            // failed. A named park they can pan to is a worse anchor and a far better failure.
            if (freshest != null &&
                now - freshest.elapsedRealtimeNanos <= MAX_LOCATION_AGE_NANOS
            ) {
                return Anchor(freshest.latitude, freshest.longitude, fromDevice = true)
            }
        }
        return Anchor(FALLBACK_LATITUDE, FALLBACK_LONGITUDE, fromDevice = false)
    }

    /** Replaces any previously seeded demo exploration with a fresh one around [anchor]. */
    suspend fun seed(context: Context, anchor: Anchor): Outcome = withContext(Dispatchers.IO) {
        val database = database(context)
        val points = DemoExplorationTrack.around(anchor.latitude, anchor.longitude)
        val distance = DemoExplorationTrack.lengthMetres()
        // Timestamps run BACKWARDS from now, so the demo ends at the present moment and the
        // history screen shows it where a walk that just finished would be.
        val endedAt = System.currentTimeMillis()
        val startedAt = endedAt - points.size * POINT_INTERVAL_MILLIS

        database.runInTransaction {
            val helper = database.openHelper.writableDatabase
            removeSeeded(helper)

            val sessionId = helper.insert(
                "recording_sessions",
                CONFLICT_ABORT,
                ContentValues().apply {
                    put("started_at", startedAt)
                    put("ended_at", endedAt)
                    put("status", "COMPLETED")
                    put("stop_reason", DEMO_STOP_REASON)
                    put("distance_meters", distance)
                    put("accepted_point_count", points.size.toLong())
                    put("rejected_point_count", 0L)
                    put("created_app_version", BuildConfig.VERSION_NAME)
                    putNull("active_slot")
                    putNull("boot_id")
                    putNull("location_owner_token")
                },
            )
            val segmentId = helper.insert(
                "track_segments",
                CONFLICT_ABORT,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("sequence", 0L)
                    put("started_at", startedAt)
                    put("ended_at", endedAt)
                    put("start_reason", DEMO_START_REASON)
                    put("end_reason", DEMO_STOP_REASON)
                    putNull("open_slot")
                },
            )

            val statement = helper.compileStatement(
                "INSERT INTO track_points(session_id, segment_id, sequence, timestamp, latitude, " +
                    "longitude, horizontal_accuracy, lat_bucket, is_mock) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1)",
            )
            points.forEachIndexed { index, point ->
                statement.clearBindings()
                statement.bindLong(1, sessionId)
                statement.bindLong(2, segmentId)
                statement.bindLong(3, index.toLong())
                statement.bindLong(4, startedAt + index * POINT_INTERVAL_MILLIS)
                statement.bindDouble(5, point.latitude)
                statement.bindDouble(6, point.longitude)
                statement.bindDouble(7, HORIZONTAL_ACCURACY_METRES)
                statement.executeInsert()
            }
            // The insert trigger cells every row above; this repairs what the DELETE removed.
            helper.execSQL(TrackPointCells.BACKFILL_SQL)
        }
        Outcome(points = points.size, anchor = anchor, distanceMetres = distance)
    }

    /** Removes seeded data and nothing else, leaving any real exploration untouched. */
    suspend fun clear(context: Context): Int = withContext(Dispatchers.IO) {
        val database = database(context)
        var removed = 0
        database.runInTransaction {
            val helper = database.openHelper.writableDatabase
            removed = removeSeeded(helper)
            helper.execSQL(TrackPointCells.BACKFILL_SQL)
        }
        removed
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
}
