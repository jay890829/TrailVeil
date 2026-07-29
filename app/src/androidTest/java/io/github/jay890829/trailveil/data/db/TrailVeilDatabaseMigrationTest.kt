package io.github.jay890829.trailveil.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrailVeilDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TrailVeilDatabase::class.java,
    )

    @Test
    fun migrate1To2PreservesCanonicalTrackAndRepairsDuplicateActiveState() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO recording_sessions(
                    id, started_at, ended_at, status, stop_reason, distance_meters,
                    accepted_point_count, rejected_point_count, created_app_version
                ) VALUES (1, 100, NULL, 'ACTIVE', NULL, 12.5, 1, 2, '0.0.1')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO recording_sessions(
                    id, started_at, ended_at, status, stop_reason, distance_meters,
                    accepted_point_count, rejected_point_count, created_app_version
                ) VALUES (2, 200, NULL, 'ACTIVE', NULL, 0, 0, 0, '0.0.1')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO track_segments(
                    id, session_id, sequence, started_at, ended_at, start_reason, end_reason
                ) VALUES (10, 1, 0, 100, NULL, 'SESSION_START', NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO track_points(
                    id, session_id, segment_id, sequence, timestamp, latitude, longitude,
                    horizontal_accuracy, altitude, speed, bearing, is_mock
                ) VALUES (100, 1, 10, 0, 110, 25.033, 121.5654, 4.5, NULL, NULL, NULL, 0)
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            MIGRATION_1_2,
        )

        migrated.query(
            """
            SELECT status, ended_at, stop_reason, active_slot
            FROM recording_sessions
            WHERE id = 1
            """.trimIndent(),
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("INTERRUPTED", cursor.getString(0))
            assertEquals(100L, cursor.getLong(1))
            assertEquals("MIGRATION_RECOVERY_DUPLICATE_ACTIVE", cursor.getString(2))
            assertEquals(true, cursor.isNull(3))
        }
        migrated.query(
            """
            SELECT status, active_slot
            FROM recording_sessions
            WHERE id = 2
            """.trimIndent(),
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("ACTIVE", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        migrated.query(
            """
            SELECT session_id, segment_id, sequence, latitude, longitude
            FROM track_points
            WHERE id = 100
            """.trimIndent(),
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1L, cursor.getLong(0))
            assertEquals(10L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals(25.033, cursor.getDouble(3), 0.0)
            assertEquals(121.5654, cursor.getDouble(4), 0.0)
        }

        assertEquals(
            0,
            migrated.query("PRAGMA foreign_key_check").use { cursor -> cursor.count },
        )
        assertEquals(
            setOf(
                "recording_sessions_validate_insert",
                "recording_sessions_validate_update",
            ),
            migrated.query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'trigger' AND name LIKE 'recording_sessions_validate_%'
                """.trimIndent(),
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            },
        )
        assertThrows(SQLiteConstraintException::class.java) {
            migrated.execSQL(
                """
                INSERT INTO recording_sessions(
                    started_at, ended_at, status, stop_reason, distance_meters,
                    accepted_point_count, rejected_point_count, created_app_version, active_slot
                ) VALUES (300, NULL, 'ACTIVE', NULL, 0, 0, 0, '0.0.2', NULL)
                """.trimIndent(),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            migrated.execSQL(
                """
                INSERT INTO recording_sessions(
                    started_at, ended_at, status, stop_reason, distance_meters,
                    accepted_point_count, rejected_point_count, created_app_version, active_slot
                ) VALUES (400, NULL, 'ACTIVE', NULL, 0, 0, 0, '0.0.2', 1)
                """.trimIndent(),
            )
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DATABASE = "migration-p2-001"
    }
}