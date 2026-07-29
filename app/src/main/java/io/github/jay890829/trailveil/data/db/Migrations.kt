package io.github.jay890829.trailveil.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 stored the complete canonical track hierarchy but relied on callers to
 * serialize ACTIVE-session creation. Version 2 adds a nullable singleton slot,
 * a unique index, and state/slot triggers so concurrent or raw writes cannot create
 * two active sessions or an inconsistent terminal state.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Preserve every legacy session while resolving a state v1 could represent:
        // keep the newest ACTIVE row active and honestly interrupt older duplicates.
        db.execSQL(
            """
            UPDATE recording_sessions
            SET status = 'INTERRUPTED',
                ended_at = COALESCE(ended_at, started_at),
                stop_reason = COALESCE(stop_reason, 'MIGRATION_RECOVERY_DUPLICATE_ACTIVE')
            WHERE status = 'ACTIVE'
              AND id <> (
                  SELECT id
                  FROM recording_sessions
                  WHERE status = 'ACTIVE'
                  ORDER BY started_at DESC, id DESC
                  LIMIT 1
              )
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE recording_sessions
            SET ended_at = COALESCE(ended_at, started_at),
                stop_reason = COALESCE(stop_reason, 'MIGRATION_RECOVERY_MISSING_END')
            WHERE status <> 'ACTIVE' AND ended_at IS NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE recording_sessions
            SET ended_at = NULL
            WHERE status = 'ACTIVE'
            """.trimIndent(),
        )
        db.execSQL("ALTER TABLE recording_sessions ADD COLUMN active_slot INTEGER")
        db.execSQL(
            """
            UPDATE recording_sessions
            SET active_slot = 1
            WHERE status = 'ACTIVE'
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_recording_sessions_active_slot
            ON recording_sessions(active_slot)
            """.trimIndent(),
        )
        createDatabaseInvariantTriggers(db)
    }
}