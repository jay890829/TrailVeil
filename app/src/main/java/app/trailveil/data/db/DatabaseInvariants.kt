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
    trackPointBucketTriggerSql.forEach(database::execSQL)
}

private val trackPointBucketTriggerSql = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS track_points_lat_bucket_insert
    AFTER INSERT ON track_points
    FOR EACH ROW WHEN NEW.lat_bucket != CAST((NEW.latitude + 90.0) * 500.0 AS INTEGER)
    BEGIN
        UPDATE track_points
        SET lat_bucket = CAST((NEW.latitude + 90.0) * 500.0 AS INTEGER)
        WHERE id = NEW.id;
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS track_points_lat_bucket_update
    AFTER UPDATE OF latitude, lat_bucket ON track_points
    FOR EACH ROW WHEN NEW.lat_bucket != CAST((NEW.latitude + 90.0) * 500.0 AS INTEGER)
    BEGIN
        UPDATE track_points
        SET lat_bucket = CAST((NEW.latitude + 90.0) * 500.0 AS INTEGER)
        WHERE id = NEW.id;
    END
    """.trimIndent(),
)