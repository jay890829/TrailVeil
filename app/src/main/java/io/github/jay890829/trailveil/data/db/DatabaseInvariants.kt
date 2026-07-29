package io.github.jay890829.trailveil.data.db

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