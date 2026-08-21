package app.trailveil.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Version-2 triggers must not reference columns introduced by later migrations. */
private val v2SessionInvariantTriggerSql = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS recording_sessions_validate_insert
    BEFORE INSERT ON recording_sessions
    FOR EACH ROW
    WHEN NEW.status NOT IN ('ACTIVE', 'COMPLETED', 'INTERRUPTED', 'FAILED_TO_START')
      OR NEW.started_at < 0
      OR NEW.distance_meters < 0
      OR NEW.accepted_point_count < 0
      OR NEW.rejected_point_count < 0
      OR length(trim(NEW.created_app_version)) = 0
      OR (NEW.ended_at IS NOT NULL AND NEW.ended_at < NEW.started_at)
      OR (
          NEW.status = 'ACTIVE'
          AND (NEW.active_slot IS NOT 1 OR NEW.ended_at IS NOT NULL)
      )
      OR (
          NEW.status <> 'ACTIVE'
          AND (NEW.active_slot IS NOT NULL OR NEW.ended_at IS NULL)
      )
    BEGIN
        SELECT RAISE(ABORT, 'invalid recording session invariant');
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS recording_sessions_validate_update
    BEFORE UPDATE ON recording_sessions
    FOR EACH ROW
    WHEN NEW.status NOT IN ('ACTIVE', 'COMPLETED', 'INTERRUPTED', 'FAILED_TO_START')
      OR NEW.started_at < 0
      OR NEW.distance_meters < 0
      OR NEW.accepted_point_count < 0
      OR NEW.rejected_point_count < 0
      OR length(trim(NEW.created_app_version)) = 0
      OR (NEW.ended_at IS NOT NULL AND NEW.ended_at < NEW.started_at)
      OR (
          NEW.status = 'ACTIVE'
          AND (NEW.active_slot IS NOT 1 OR NEW.ended_at IS NOT NULL)
      )
      OR (
          NEW.status <> 'ACTIVE'
          AND (NEW.active_slot IS NOT NULL OR NEW.ended_at IS NULL)
      )
    BEGIN
        SELECT RAISE(ABORT, 'invalid recording session invariant');
    END
    """.trimIndent(),
)
private val sessionInvariantTriggerSql = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS recording_sessions_validate_insert
    BEFORE INSERT ON recording_sessions
    FOR EACH ROW
    WHEN NEW.status NOT IN ('STARTING', 'ACTIVE', 'COMPLETED', 'INTERRUPTED', 'FAILED_TO_START')
      OR NEW.started_at < 0
      OR NEW.distance_meters < 0
      OR NEW.accepted_point_count < 0
      OR NEW.rejected_point_count < 0
      OR length(trim(NEW.created_app_version)) = 0
      OR (NEW.ended_at IS NOT NULL AND NEW.ended_at < NEW.started_at)
      OR (
          NEW.status IN ('STARTING', 'ACTIVE')
          AND (NEW.active_slot IS NOT 1 OR NEW.ended_at IS NOT NULL)
      )
      OR (
          NEW.status NOT IN ('STARTING', 'ACTIVE')
          AND (NEW.active_slot IS NOT NULL OR NEW.ended_at IS NULL)
      )
      OR (NEW.status = 'STARTING' AND NEW.location_owner_token IS NOT NULL)
      OR (
          NEW.status = 'ACTIVE'
          AND (NEW.location_owner_token IS NULL OR length(trim(NEW.location_owner_token)) = 0)
      )
      OR (NEW.status NOT IN ('STARTING', 'ACTIVE') AND NEW.location_owner_token IS NOT NULL)
    BEGIN
        SELECT RAISE(ABORT, 'invalid recording session invariant');
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS recording_sessions_validate_update
    BEFORE UPDATE ON recording_sessions
    FOR EACH ROW
    WHEN NEW.status NOT IN ('STARTING', 'ACTIVE', 'COMPLETED', 'INTERRUPTED', 'FAILED_TO_START')
      OR NEW.started_at < 0
      OR NEW.distance_meters < 0
      OR NEW.accepted_point_count < 0
      OR NEW.rejected_point_count < 0
      OR length(trim(NEW.created_app_version)) = 0
      OR (NEW.ended_at IS NOT NULL AND NEW.ended_at < NEW.started_at)
      OR (
          NEW.status IN ('STARTING', 'ACTIVE')
          AND (NEW.active_slot IS NOT 1 OR NEW.ended_at IS NOT NULL)
      )
      OR (
          NEW.status NOT IN ('STARTING', 'ACTIVE')
          AND (NEW.active_slot IS NOT NULL OR NEW.ended_at IS NULL)
      )
      OR (NEW.status = 'STARTING' AND NEW.location_owner_token IS NOT NULL)
      OR (
          NEW.status = 'ACTIVE'
          AND (NEW.location_owner_token IS NULL OR length(trim(NEW.location_owner_token)) = 0)
      )
      OR (NEW.status NOT IN ('STARTING', 'ACTIVE') AND NEW.location_owner_token IS NOT NULL)
    BEGIN
        SELECT RAISE(ABORT, 'invalid recording session invariant');
    END
    """.trimIndent(),
)

private val segmentInvariantTriggerSql = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS track_segments_validate_insert
    BEFORE INSERT ON track_segments
    FOR EACH ROW
    WHEN NEW.sequence < 0
      OR NEW.started_at < 0
      OR length(trim(NEW.start_reason)) = 0
      OR (NEW.ended_at IS NOT NULL AND NEW.ended_at < NEW.started_at)
      OR (
          (NEW.ended_at IS NULL OR NEW.end_reason IS NULL)
          AND NOT (
              NEW.ended_at IS NULL
              AND NEW.end_reason IS NULL
              AND NEW.open_slot IS 1
          )
      )
      OR (
          (NEW.ended_at IS NOT NULL OR NEW.end_reason IS NOT NULL)
          AND NOT (
              NEW.ended_at IS NOT NULL
              AND NEW.end_reason IS NOT NULL
              AND NEW.open_slot IS NULL
          )
      )
    BEGIN
        SELECT RAISE(ABORT, 'invalid track segment invariant');
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS track_segments_validate_update
    BEFORE UPDATE ON track_segments
    FOR EACH ROW
    WHEN NEW.sequence < 0
      OR NEW.started_at < 0
      OR length(trim(NEW.start_reason)) = 0
      OR (NEW.ended_at IS NOT NULL AND NEW.ended_at < NEW.started_at)
      OR (
          (NEW.ended_at IS NULL OR NEW.end_reason IS NULL)
          AND NOT (
              NEW.ended_at IS NULL
              AND NEW.end_reason IS NULL
              AND NEW.open_slot IS 1
          )
      )
      OR (
          (NEW.ended_at IS NOT NULL OR NEW.end_reason IS NOT NULL)
          AND NOT (
              NEW.ended_at IS NOT NULL
              AND NEW.end_reason IS NOT NULL
              AND NEW.open_slot IS NULL
          )
      )
    BEGIN
        SELECT RAISE(ABORT, 'invalid track segment invariant');
    END
    """.trimIndent(),
)

internal fun dropDatabaseInvariantTriggers(database: SupportSQLiteDatabase) {
    listOf(
        "recording_sessions_validate_insert",
        "recording_sessions_validate_update",
        "track_segments_validate_insert",
        "track_segments_validate_update",
    ).forEach { database.execSQL("DROP TRIGGER IF EXISTS $it") }
}

internal fun createV2SessionInvariantTriggers(database: SupportSQLiteDatabase) {
    v2SessionInvariantTriggerSql.forEach(database::execSQL)
}
internal fun createSessionInvariantTriggers(database: SupportSQLiteDatabase) {
    sessionInvariantTriggerSql.forEach(database::execSQL)
}

internal fun createDatabaseInvariantTriggers(database: SupportSQLiteDatabase) {
    createSessionInvariantTriggers(database)
    segmentInvariantTriggerSql.forEach(database::execSQL)
}

/**
 * `P4-036`'s derived column, kept honest by the database rather than by every writer remembering.
 *
 * Deliberately NOT part of [createDatabaseInvariantTriggers]: that function is called from a
 * pre-v7 migration, where `lat_bucket` does not exist yet. SQLite resolves trigger columns lazily,
 * so such a trigger is ACCEPTED at CREATE time and fails only later when it fires — a latent break
 * instead of a loud one. This is called from the open callback and from `MIGRATION_6_7` only.
 *
 * The failure it prevents is silent in the worst way: a row whose bucket disagrees with its
 * latitude drops out of the fog viewport read, which draws MORE fog than it should, and every leak
 * audit in the suite accepts extra fog.
 */
internal fun createTrackPointInvariantTriggers(database: SupportSQLiteDatabase) {
    trackPointBucketTriggerSql.forEach { (name, sql) ->
        // NOT `CREATE TRIGGER IF NOT EXISTS` on its own. These triggers embed
        // [LatitudeBuckets.BUCKETS_PER_DEGREE], and `IF NOT EXISTS` on an EXISTING database is a
        // no-op — so retuning the bucket size would leave every already-installed phone repairing
        // rows with the OLD arithmetic while the app read them with the new, which drops points out
        // of the fog viewport silently. Compared and replaced only on a mismatch, so the common
        // open path still writes nothing.
        val installed = database.query(
            "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?",
            arrayOf<Any>(name),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (installed == sql) return@forEach
        database.execSQL("DROP TRIGGER IF EXISTS $name")
        database.execSQL(sql)
    }
}

/**
 * `P4-037`'s derived table, kept honest by the database rather than by the one writer that remembers.
 *
 * Separate from [createTrackPointInvariantTriggers] for the reason that function's KDoc gives about
 * itself: this trigger names `track_point_cells`, which does not exist before v8, and SQLite accepts
 * a trigger over a missing table at CREATE time and fails only when it fires. Called from the open
 * callback and from `MIGRATION_7_8` only.
 *
 * A trigger rather than a DAO write, which is the opposite of how `lat_bucket` is handled and is
 * deliberate. There the DAO derives in Kotlin and the trigger only guards other writers, so it fires
 * on a mismatch and is otherwise a comparison. Here the two would do the SAME `INSERT OR IGNORE`, so
 * a DAO copy would be redundant work on the recording path and a second place to forget. The `WHEN
 * NOT EXISTS` guard means the common case -- the second and every later point in an already-occupied
 * cell -- is one primary-key probe and no write at all.
 *
 * The failure it prevents is the silent direction again: a point whose cell was never written drops
 * its region out of the world-zoom read, which draws MORE fog than earned, and every leak audit in
 * the suite accepts extra fog.
 */
internal fun createTrackPointCellTriggers(database: SupportSQLiteDatabase) {
    trackPointCellTriggerSql.forEach { (name, sql) ->
        // Compared and replaced on a mismatch, never `IF NOT EXISTS`, for the reason spelled out in
        // [createTrackPointInvariantTriggers]: retuning the cell size would otherwise leave every
        // installed phone writing cells with the old arithmetic while the read used the new.
        val installed = database.query(
            "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?",
            arrayOf<Any>(name),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (installed == sql) return@forEach
        database.execSQL("DROP TRIGGER IF EXISTS $name")
        database.execSQL(sql)
    }
}

/**
 * One entry, and both cells computed from `NEW.latitude`/`NEW.longitude` rather than from
 * `NEW.lat_bucket`.
 *
 * Reusing the stored bucket would look like sharing but would couple this to the firing ORDER of two
 * independent triggers: on a raw-SQL insert the bucket is wrong until its own `AFTER INSERT` trigger
 * repairs it, and SQLite does not promise which runs first. Deriving from the columns the row
 * actually carries is order-free.
 */
private val trackPointCellTriggerSql = listOf(
    "track_points_cell_insert" to """
    CREATE TRIGGER track_points_cell_insert
    AFTER INSERT ON track_points
    FOR EACH ROW WHEN NOT EXISTS (
        SELECT 1 FROM track_point_cells
        WHERE lat_cell = ${TrackPointCells.LAT_CELL_SQL}
            AND lon_cell = ${TrackPointCells.LON_CELL_SQL}
    )
    BEGIN
        INSERT OR IGNORE INTO track_point_cells(lat_cell, lon_cell)
        VALUES(${TrackPointCells.LAT_CELL_SQL}, ${TrackPointCells.LON_CELL_SQL});
    END
    """.trimIndent(),
)

private const val LAT_BUCKET_SQL =
    "CAST((NEW.latitude + 90.0) * ${LatitudeBuckets.BUCKETS_PER_DEGREE} AS INTEGER)"

/**
 * One source of truth for the bucket arithmetic, interpolated rather than retyped.
 *
 * `BUCKETS_PER_DEGREE` used to appear as a bare `500.0` here and in the migration's backfill,
 * neither of which read the Kotlin constant. Changing the constant alone would then have moved the
 * READER without moving the WRITERS, and every previously stored point would fall out of the fog
 * viewport read at once - total silent fog loss, in the direction every leak audit accepts.
 * `LatitudeBucketDerivationTest` reads this text back out of `sqlite_master` and evaluates it, so
 * the coupling is measured rather than trusted to this comment.
 *
 * Each entry carries its own name so the installer can compare what is already there against what
 * this text says and replace it on a mismatch; `CREATE TRIGGER IF NOT EXISTS` alone would leave an
 * existing database on the old arithmetic forever. Note the limit: this heals FUTURE writes only.
 * Retuning the bucket size on a shipped build still needs a migration to re-derive the column for
 * rows already stored.
 */
private val trackPointBucketTriggerSql = listOf(
    "track_points_lat_bucket_insert" to """
    CREATE TRIGGER track_points_lat_bucket_insert
    AFTER INSERT ON track_points
    FOR EACH ROW WHEN NEW.lat_bucket != $LAT_BUCKET_SQL
    BEGIN
        UPDATE track_points
        SET lat_bucket = $LAT_BUCKET_SQL
        WHERE id = NEW.id;
    END
    """.trimIndent(),
    "track_points_lat_bucket_update" to """
    CREATE TRIGGER track_points_lat_bucket_update
    AFTER UPDATE OF latitude, lat_bucket ON track_points
    FOR EACH ROW WHEN NEW.lat_bucket != $LAT_BUCKET_SQL
    BEGIN
        UPDATE track_points
        SET lat_bucket = $LAT_BUCKET_SQL
        WHERE id = NEW.id;
    END
    """.trimIndent(),
)
