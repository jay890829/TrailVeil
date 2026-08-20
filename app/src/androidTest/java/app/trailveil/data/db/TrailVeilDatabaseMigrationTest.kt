package app.trailveil.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun migrate2To3RepairsOpenSegmentsAndPreservesPoints() {
        migrationHelper.createDatabase("migration-p2-003", 2).apply {
            execSQL("INSERT INTO recording_sessions(id, started_at, ended_at, status, stop_reason, distance_meters, accepted_point_count, rejected_point_count, created_app_version, active_slot) VALUES (1, 100, NULL, 'ACTIVE', NULL, 0, 0, 0, 'v2', 1)")
            execSQL("INSERT INTO recording_sessions(id, started_at, ended_at, status, stop_reason, distance_meters, accepted_point_count, rejected_point_count, created_app_version, active_slot) VALUES (2, 200, 210, 'COMPLETED', 'STOP', 0, 0, 0, 'v2', NULL)")
            execSQL("INSERT INTO track_segments(id, session_id, sequence, started_at, ended_at, start_reason, end_reason) VALUES (10, 1, 0, 100, NULL, 'START', NULL)")
            execSQL("INSERT INTO track_segments(id, session_id, sequence, started_at, ended_at, start_reason, end_reason) VALUES (11, 1, 1, 130, NULL, 'BREAK', 'DANGLING')")
            execSQL("INSERT INTO track_segments(id, session_id, sequence, started_at, ended_at, start_reason, end_reason) VALUES (20, 2, 0, 200, NULL, 'START', NULL)")
            execSQL("INSERT INTO track_segments(id, session_id, sequence, started_at, ended_at, start_reason, end_reason) VALUES (21, 2, 1, 205, 210, 'CLOSED', NULL)")
            execSQL("INSERT INTO track_points(id, session_id, segment_id, sequence, timestamp, latitude, longitude, horizontal_accuracy, altitude, speed, bearing, is_mock) VALUES (100, 1, 10, 0, 120, 25, 121, 5, NULL, NULL, NULL, 0)")
            execSQL("INSERT INTO track_points(id, session_id, segment_id, sequence, timestamp, latitude, longitude, horizontal_accuracy, altitude, speed, bearing, is_mock) VALUES (200, 2, 20, 0, 220, 25, 121, 5, NULL, NULL, NULL, 0)")
            close()
        }
        val migrated = migrationHelper.runMigrationsAndValidate("migration-p2-003", 3, true, MIGRATION_2_3)
        assertEquals(0, migrated.query("PRAGMA foreign_key_check").use { it.count })
        migrated.query("SELECT id, location_owner_token FROM recording_sessions ORDER BY id").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("MIGRATION_REQUIRES_RECOVERY", cursor.getString(1))
            assertEquals(true, cursor.moveToNext())
            assertEquals(2L, cursor.getLong(0))
            assertEquals(true, cursor.isNull(1))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            migrated.execSQL("UPDATE recording_sessions SET location_owner_token = NULL WHERE id = 1")
        }
        migrated.query("SELECT id, ended_at, open_slot, end_reason FROM track_segments ORDER BY id").use { cursor ->
            assertEquals(true, cursor.moveToFirst()); assertEquals(10L, cursor.getLong(0)); assertEquals(130L, cursor.getLong(1)); assertEquals(true, cursor.isNull(2))
            assertEquals(true, cursor.moveToNext()); assertEquals(11L, cursor.getLong(0)); assertEquals(true, cursor.isNull(1)); assertEquals(1, cursor.getInt(2)); assertEquals(true, cursor.isNull(3))
            assertEquals(true, cursor.moveToNext()); assertEquals(20L, cursor.getLong(0)); assertEquals(210L, cursor.getLong(1)); assertEquals(true, cursor.isNull(2))
            assertEquals(true, cursor.moveToNext()); assertEquals(21L, cursor.getLong(0)); assertEquals(210L, cursor.getLong(1)); assertEquals(true, cursor.isNull(2))
        }
        migrated.query("SELECT end_reason FROM track_segments WHERE id = 21").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("MIGRATION_RECOVERY_MISSING_END_REASON", cursor.getString(0))
        }
        migrated.query("SELECT id, segment_id, timestamp FROM track_points ORDER BY id").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(100L, cursor.getLong(0))
            assertEquals(10L, cursor.getLong(1))
            assertEquals(120L, cursor.getLong(2))
            assertEquals(true, cursor.moveToNext())
            assertEquals(200L, cursor.getLong(0))
            assertEquals(20L, cursor.getLong(1))
            assertEquals(220L, cursor.getLong(2))
            assertEquals(false, cursor.moveToNext())
        }
        assertEquals(4, migrated.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN ('recording_sessions_validate_insert', 'recording_sessions_validate_update', 'track_segments_validate_insert', 'track_segments_validate_update')").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        assertThrows(SQLiteConstraintException::class.java) {
            migrated.execSQL("INSERT INTO track_segments(session_id, sequence, started_at, ended_at, start_reason, end_reason, open_slot) VALUES (1, 2, 140, NULL, 'SECOND_OPEN', NULL, 1)")
        }
        assertEquals(
            setOf(
                "index_track_segments_session_id_open_slot",
                "index_recording_operation_receipts_command_kind",
                "index_recording_operation_receipts_session_id",
                "index_recording_operation_receipts_created_at",
            ),
            migrated.query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'index' AND name IN (
                    'index_track_segments_session_id_open_slot',
                    'index_recording_operation_receipts_command_kind',
                    'index_recording_operation_receipts_session_id',
                    'index_recording_operation_receipts_created_at'
                )
                """.trimIndent(),
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            },
        )
        migrated.close()
    }

    @Test
    fun migrate3To4PreservesLegacyReceiptsAndAddsEmptyLocationWatermarks() {
        migrationHelper.createDatabase("migration-p2-004", 3).apply {
            execSQL(
                "INSERT INTO recording_operation_receipts(" +
                    "operation_id, command_kind, outcome, created_at" +
                    ") VALUES ('legacy-location', 'LOCATION', 'LOCATION_SESSION_GUARD', 100)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            "migration-p2-004",
            4,
            true,
            MIGRATION_3_4,
        )
        assertEquals(
            1,
            migrated.query(
                "SELECT COUNT(*) FROM recording_operation_receipts " +
                    "WHERE operation_id = 'legacy-location'",
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) },
        )
        assertEquals(
            0,
            migrated.query("SELECT COUNT(*) FROM recording_location_receipt_windows")
                .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) },
        )
        assertEquals(
            0,
            migrated.query("SELECT COUNT(*) FROM recording_location_receipt_retention_states")
                .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) },
        )
        migrated.close()
    }

    @Test
    fun migrate4To5AddsBoundedSummaryOrderingIndexes() {
        migrationHelper.createDatabase("migration-p4-033", 4).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            "migration-p4-033",
            5,
            true,
            MIGRATION_4_5,
        )
        val indexes = migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name IN (" +
                "'index_recording_operation_receipts_session_id_created_at', " +
                "'index_track_points_session_id_id') ORDER BY name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(
            listOf(
                "index_recording_operation_receipts_session_id_created_at",
                "index_track_points_session_id_id",
            ),
            indexes,
        )
        migrated.close()
    }

    @Test
    fun migrate6To7BackfillsTheLatitudeBucketAndSwapsTheIndex() {
        // P4-036. Three things must hold together, and the third is the one a migration usually
        // forgets: the column exists, EXISTING rows carry a correct value rather than the DEFAULT,
        // and the superseded index is gone so nothing can silently keep planning through it.
        migrationHelper.createDatabase("migration-p4-036", 6).use { old ->
            old.execSQL(
                "INSERT INTO recording_sessions(id, started_at, status, distance_meters, " +
                    "accepted_point_count, rejected_point_count, created_app_version, active_slot) " +
                    "VALUES(1, 1000, 'ACTIVE', 0, 0, 0, 'migration-test', 1)",
            )
            old.execSQL(
                "INSERT INTO track_segments(id, session_id, sequence, started_at, start_reason, " +
                    "open_slot) VALUES(1, 1, 0, 1000, 'SESSION_START', 1)",
            )
            listOf(25.0330, -33.8688, 0.0, 89.9).forEachIndexed { index, latitude ->
                old.execSQL(
                    "INSERT INTO track_points(id, session_id, segment_id, sequence, timestamp, " +
                        "latitude, longitude, horizontal_accuracy) VALUES(" +
                        "${index + 1}, 1, 1, $index, ${1000 + index}, $latitude, 121.5, 5.0)",
                )
            }
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            "migration-p4-036",
            7,
            true,
            MIGRATION_6_7,
        )

        // Backfilled, not defaulted: a row left at 0 would silently vanish from the fog read, which
        // draws MORE fog and therefore passes every leak audit in the suite.
        val buckets = migrated.query(
            "SELECT latitude, lat_bucket FROM track_points ORDER BY id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getDouble(0) to cursor.getInt(1))
            }
        }
        buckets.forEach { (latitude, bucket) ->
            assertEquals(
                "row at latitude $latitude kept the column default instead of its bucket",
                LatitudeBuckets.of(latitude),
                bucket,
            )
        }
        val indexes = migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN " +
                "('index_track_points_lat_bucket_longitude', " +
                "'index_track_points_latitude_longitude') ORDER BY name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(listOf("index_track_points_lat_bucket_longitude"), indexes)

        // And the trigger the migration installs actually fires, so a writer that forgets the
        // column cannot leave the database inconsistent.
        migrated.execSQL(
            "INSERT INTO track_points(id, session_id, segment_id, sequence, timestamp, latitude, " +
                "longitude, horizontal_accuracy, lat_bucket) VALUES(" +
                "99, 1, 1, 99, 9999, 51.5074, -0.1278, 5.0, 0)",
        )
        val repaired = migrated.query(
            "SELECT lat_bucket FROM track_points WHERE id = 99",
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        assertEquals(
            "the migration's trigger did not repair a row inserted with a wrong bucket",
            LatitudeBuckets.of(51.5074),
            repaired,
        )
        migrated.close()
    }

    @Test
    fun migrate5To6AddsTheViewportBoxIndex() {
        migrationHelper.createDatabase("migration-p4-020", 5).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            "migration-p4-020",
            6,
            true,
            MIGRATION_5_6,
        )
        val indexes = migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_track_points_latitude_longitude'",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(listOf("index_track_points_latitude_longitude"), indexes)
        // The index exists to be chosen: a viewport box that plans as a scan would leave the
        // read unbounded however narrow the box is, which is the defect this migration answers.
        val plan = migrated.query(
            "EXPLAIN QUERY PLAN SELECT id FROM track_points " +
                "WHERE latitude BETWEEN -1.0 AND 1.0 AND longitude BETWEEN -1.0 AND 1.0",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
            }
        }.joinToString(" | ")
        assertTrue(
            "the viewport box still plans as a table scan: $plan",
            plan.contains("index_track_points_latitude_longitude"),
        )
        migrated.close()
    }

    private companion object {
        const val TEST_DATABASE = "migration-p2-001"
    }
}
