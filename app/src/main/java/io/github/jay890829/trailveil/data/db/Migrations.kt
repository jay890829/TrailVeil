package io.github.jay890829.trailveil.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 stored the complete canonical track hierarchy but relied on callers to serialize
 * ACTIVE-session creation. Version 2 adds a nullable singleton slot, a unique index, and
 * state/slot triggers.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Keep the newest ACTIVE row active and honestly interrupt older duplicates.
        db.execSQL(
            """
            UPDATE recording_sessions
            SET status = 'INTERRUPTED',
                ended_at = COALESCE(ended_at, started_at),
                stop_reason = COALESCE(
                    stop_reason,
                    'MIGRATION_RECOVERY_DUPLICATE_ACTIVE'
                )
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
                stop_reason = COALESCE(
                    stop_reason,
                    'MIGRATION_RECOVERY_MISSING_END'
                )
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
        db.execSQL("UPDATE recording_sessions SET active_slot = 1 WHERE status = 'ACTIVE'")
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_recording_sessions_active_slot
            ON recording_sessions(active_slot)
            """.trimIndent(),
        )
        createV2SessionInvariantTriggers(db)
    }
}

/** Adds durable operation receipts and a physical singleton slot for every open segment. */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The v2 session triggers do not know STARTING, so they must be replaced.
        dropDatabaseInvariantTriggers(db)
        db.execSQL("ALTER TABLE track_segments ADD COLUMN open_slot INTEGER")
        db.execSQL("ALTER TABLE recording_sessions ADD COLUMN location_owner_token TEXT")
        // A v2 ACTIVE row is intentionally inert until a runtime claims it through recovery.
        db.execSQL("UPDATE recording_sessions SET location_owner_token = 'MIGRATION_REQUIRES_RECOVERY' WHERE status = 'ACTIVE'")
        db.execSQL("UPDATE recording_sessions SET location_owner_token = NULL WHERE status <> 'ACTIVE'")

        // A v2 database could contain many open rows. Keep only the newest open segment of
        // the single ACTIVE session and close every other row at the best lifecycle timestamp.
        db.execSQL(
            """
            UPDATE track_segments
            SET ended_at = CASE
                WHEN (
                    SELECT ended_at
                    FROM recording_sessions s
                    WHERE s.id = track_segments.session_id
                ) IS NOT NULL
                  AND (
                    SELECT ended_at
                    FROM recording_sessions s
                    WHERE s.id = track_segments.session_id
                  ) >= track_segments.started_at
                    THEN (
                        SELECT ended_at
                        FROM recording_sessions s
                        WHERE s.id = track_segments.session_id
                    )
                WHEN (
                    SELECT newer.started_at
                    FROM track_segments newer
                    WHERE newer.session_id = track_segments.session_id
                      AND newer.ended_at IS NULL
                      AND (
                          newer.sequence > track_segments.sequence
                          OR (
                              newer.sequence = track_segments.sequence
                              AND newer.id > track_segments.id
                          )
                      )
                    ORDER BY newer.sequence, newer.id
                    LIMIT 1
                ) >= track_segments.started_at
                    THEN (
                        SELECT newer.started_at
                        FROM track_segments newer
                        WHERE newer.session_id = track_segments.session_id
                          AND newer.ended_at IS NULL
                          AND (
                              newer.sequence > track_segments.sequence
                              OR (
                                  newer.sequence = track_segments.sequence
                                  AND newer.id > track_segments.id
                              )
                          )
                        ORDER BY newer.sequence, newer.id
                        LIMIT 1
                    )
                WHEN (
                    SELECT MAX(timestamp)
                    FROM track_points p
                    WHERE p.segment_id = track_segments.id
                ) IS NOT NULL
                  AND (
                    SELECT MAX(timestamp)
                    FROM track_points p
                    WHERE p.segment_id = track_segments.id
                  ) > track_segments.started_at
                    THEN (
                        SELECT MAX(timestamp)
                        FROM track_points p
                        WHERE p.segment_id = track_segments.id
                    )
                ELSE track_segments.started_at
            END,
                end_reason = 'MIGRATION_RECOVERY_OPEN_SEGMENT',
                open_slot = NULL
            WHERE ended_at IS NULL
              AND (
                  (
                      SELECT status
                      FROM recording_sessions s
                      WHERE s.id = track_segments.session_id
                  ) <> 'ACTIVE'
                  OR id <> (
                      SELECT newer.id
                      FROM track_segments newer
                      WHERE newer.session_id = track_segments.session_id
                        AND newer.ended_at IS NULL
                      ORDER BY newer.sequence DESC, newer.id DESC
                      LIMIT 1
                  )
              )
            """.trimIndent(),
        )

        // Repair partially closed raw rows that v2 had no segment trigger to reject.
        db.execSQL(
            """
            UPDATE track_segments
            SET end_reason = 'MIGRATION_RECOVERY_MISSING_END_REASON'
            WHERE ended_at IS NOT NULL AND end_reason IS NULL
            """.trimIndent(),
        )
        db.execSQL("UPDATE track_segments SET end_reason = NULL WHERE ended_at IS NULL")
        db.execSQL("UPDATE track_segments SET open_slot = 1 WHERE ended_at IS NULL")
        db.execSQL("UPDATE track_segments SET open_slot = NULL WHERE ended_at IS NOT NULL")
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_track_segments_session_id_open_slot
            ON track_segments(session_id, open_slot)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recording_operation_receipts (
                operation_id TEXT NOT NULL,
                command_kind TEXT NOT NULL,
                outcome TEXT NOT NULL,
                session_id INTEGER,
                segment_id INTEGER,
                point_id INTEGER,
                created_at INTEGER NOT NULL,
                projection_session_id INTEGER,
                projection_lifecycle TEXT,
                projection_open_segment_id INTEGER,
                projection_accepted_point_count INTEGER,
                projection_rejected_point_count INTEGER,
                projection_distance_meters REAL,
                PRIMARY KEY(operation_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_recording_operation_receipts_command_kind
            ON recording_operation_receipts(command_kind)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_recording_operation_receipts_session_id
            ON recording_operation_receipts(session_id)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_recording_operation_receipts_created_at
            ON recording_operation_receipts(created_at)
            """.trimIndent(),
        )
        createDatabaseInvariantTriggers(db)
    }
}