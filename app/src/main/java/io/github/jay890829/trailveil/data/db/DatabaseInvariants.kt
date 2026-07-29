package io.github.jay890829.trailveil.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

private val invariantTriggerSql = listOf(
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

internal fun createDatabaseInvariantTriggers(database: SupportSQLiteDatabase) {
    invariantTriggerSql.forEach(database::execSQL)
}