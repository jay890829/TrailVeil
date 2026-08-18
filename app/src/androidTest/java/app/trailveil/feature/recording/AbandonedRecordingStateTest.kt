package app.trailveil.feature.recording

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.recording.RecordingForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen may never tell the user an exploration is being recorded by a runtime that is gone.
 *
 * `P4-038`: on a HyperOS device the platform does not restart a killed foreground service, so an
 * `ACTIVE` row outlives the process that owned it. The unit tests bind the mapping function; this
 * one binds the wiring, which is a different claim — the mapping can be correct while the screen
 * hands it the wrong runtime token, and that is exactly what shipped.
 *
 * A killed runtime is modelled by its durable trace rather than by killing this process, which a
 * test cannot survive: an `ACTIVE` row whose `location_owner_token` belongs to no live runtime is
 * precisely what a process death leaves behind, and it is the only thing the screen can read.
 */
@RunWith(AndroidJUnit4::class)
class AbandonedRecordingStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun anExplorationOwnedByADeadRuntimeIsNeverPresentedAsRecording() {
        val database = TrailVeilDatabase.open(context)
        val sqlite = database.openHelper.writableDatabase
        val deadRuntime = "runtime-of-a-process-that-died-$FIXTURE_SUFFIX"
        val sessionId = seedAbandonedSession(sqlite, deadRuntime)

        try {
            val thisRuntime =
                (context as TrailVeilApplication).appContainer.recordingRuntimeToken
            assertNotEquals(
                "the fixture must not accidentally be owned by this process",
                thisRuntime,
                deadRuntime,
            )

            val state = awaitPublishedState()
            assertNotNull("the route never published a presentation state", state)

            val owner = ownerToken(sqlite, sessionId)
            if (state == RECORDING) {
                // Recording may only be claimed once ownership has actually moved here, which is
                // the re-arm doing its job rather than the screen assuming.
                assertEquals(
                    "the screen claimed RECORDING for a row this process does not own",
                    thisRuntime,
                    owner,
                )
                assertTrue(
                    "recovery claimed the row without opening a recovery segment",
                    hasRecoverySegment(sqlite, sessionId),
                )
            } else {
                assertEquals(
                    "an unowned ACTIVE row must be presented as abandoned",
                    ABANDONED,
                    state,
                )
                assertEquals(
                    "nothing may take ownership without recovering",
                    deadRuntime,
                    owner,
                )
            }
        } finally {
            RecordingForegroundService.stopFromVisibleActivity(context, sessionId)
            SystemClock.sleep(STOP_SETTLE_MILLIS)
            sqlite.execSQL("DELETE FROM recording_sessions WHERE id = $sessionId")
        }
    }

    /** The durable remains of a process death: an ACTIVE row with an owner that no longer exists. */
    private fun seedAbandonedSession(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        deadRuntime: String,
    ): Long {
        val startedAt = System.currentTimeMillis()
        sqlite.execSQL(
            "INSERT INTO recording_sessions(" +
                "started_at, ended_at, status, stop_reason, distance_meters, accepted_point_count, " +
                "rejected_point_count, created_app_version, active_slot, location_owner_token" +
                ") VALUES($startedAt, NULL, 'ACTIVE', NULL, 0, 0, 0, 'abandoned-state-test', 1, " +
                "'$deadRuntime')",
        )
        val sessionId = sqlite.query("SELECT MAX(id) FROM recording_sessions").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        sqlite.execSQL(
            "INSERT INTO track_segments(" +
                "session_id, sequence, started_at, ended_at, start_reason, end_reason, open_slot" +
                ") VALUES($sessionId, 0, $startedAt, NULL, 'SESSION_START', NULL, 1)",
        )
        return sessionId
    }

    private fun ownerToken(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        sessionId: Long,
    ): String? = sqlite
        .query("SELECT location_owner_token FROM recording_sessions WHERE id = $sessionId")
        .use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun hasRecoverySegment(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        sessionId: Long,
    ): Boolean = sqlite
        .query(
            "SELECT COUNT(*) FROM track_segments " +
                "WHERE session_id = $sessionId AND start_reason = 'PROCESS_RECOVERY'",
        )
        .use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) > 0
        }

    /**
     * The route publishes its presentation state onto the decor view, so what is read here is what
     * the production wiring decided rather than a value the test recomputed.
     */
    private fun awaitPublishedState(): String? {
        var waited = 0L
        var published: String? = null
        while (waited < STATE_TIMEOUT_MILLIS) {
            composeRule.waitForIdle()
            composeRule.activityRule.scenario.onActivity { activity ->
                published = activity.window.decorView
                    .getTag(R.id.recording_presentation_state) as? String
            }
            if (published != null && published != IDLE) return published
            SystemClock.sleep(STATE_POLL_MILLIS)
            waited += STATE_POLL_MILLIS
        }
        return published
    }

    private companion object {
        const val RECORDING = "RECORDING"
        const val ABANDONED = "ABANDONED"

        /** Published while the newest session is still being read; not an answer yet. */
        const val IDLE = "IDLE"
        const val STATE_TIMEOUT_MILLIS = 20_000L
        const val STATE_POLL_MILLIS = 250L
        const val STOP_SETTLE_MILLIS = 1_500L
        const val FIXTURE_SUFFIX = "p4-038"
    }
}
