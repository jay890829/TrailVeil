package app.trailveil.feature.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.recording.RecordingForegroundService
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An exploration whose owning runtime is gone must be taken back, not merely described.
 *
 * `P4-038`: on a HyperOS device the platform does not restart a killed foreground service, so an
 * `ACTIVE` row outlives the process that owned it and only the app reopening can re-arm it. The unit
 * tests bind the pure mapping; this binds the wiring, which is a different claim — the mapping can
 * be correct while the screen hands it the wrong runtime token, and the re-arm can be missing
 * entirely while every state shown is still honest.
 *
 * An earlier version of this test accepted either "resumed and owned" or "still abandoned", and a
 * verifier showed that deleting the whole re-arm left it green: the abandoned branch was an escape
 * hatch for exactly the half the task exists for. It now requires the recovery. The version after
 * that guarded the requirement with `assumeTrue` on location permission, which a second verifier
 * showed skipped the whole test on every CI shard - so the preconditions are established here
 * instead, the way the sibling classes in this package establish theirs.
 *
 * A killed runtime is modelled by its durable trace rather than by killing this process, which a
 * test cannot survive: an `ACTIVE` row whose `location_owner_token` belongs to no live runtime is
 * precisely what a process death leaves behind, and it is all the screen can read.
 */
@RunWith(AndroidJUnit4::class)
class AbandonedRecordingStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context as TrailVeilApplication).appContainer

    @Test
    fun anExplorationOwnedByADeadRuntimeIsTakenBackWhenTheAppReturns() {
        // Establish the preconditions rather than assume them. An earlier version skipped when the
        // permission was missing, which sounds conservative and is not: a shard installs the app
        // without runtime permissions, so the gate would have skipped in CI every time and been
        // indistinguishable from the assumption skips already reported there. Its three sibling
        // classes in this package grant and enable the same way.
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        shell("cmd location set-location-enabled true")
        assertTrue(
            "location services could not be enabled, so no re-arm could be attempted",
            requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled,
        )

        val sqlite = container.databaseForTesting().openHelper.writableDatabase
        val deadRuntime = "runtime-of-a-process-that-died-$FIXTURE_SUFFIX"
        val thisRuntime = container.recordingRuntimeToken
        assertNotEquals(
            "the fixture must not accidentally be owned by this process",
            thisRuntime,
            deadRuntime,
        )
        val sessionId = seedAbandonedSession(sqlite, deadRuntime)

        try {
            // Rebuild the screen over the seeded row, so what is measured is a presentation that
            // started life with the abandoned exploration already there.
            composeRule.activityRule.scenario.recreate()

            var owner: String? = null
            var recovered = false
            var state: String? = null
            var waited = 0L
            while (waited < RECOVERY_TIMEOUT_MILLIS) {
                composeRule.waitForIdle()
                owner = ownerToken(sqlite, sessionId)
                recovered = hasRecoverySegment(sqlite, sessionId)
                state = publishedState()
                if (owner == thisRuntime && recovered) break
                SystemClock.sleep(POLL_MILLIS)
                waited += POLL_MILLIS
            }

            assertTrue(
                "the abandoned exploration was never taken back: owner=$owner " +
                    "(this runtime is $thisRuntime), recoverySegment=$recovered, shown state=$state",
                owner == thisRuntime && recovered,
            )
            // Once it is genuinely owned again, saying so is the truth. Before that it never may be,
            // which is what the ownership rule and its unit tests hold. The loop above broke on a
            // database condition, so give the screen its own chance to catch up rather than reading
            // it in the same breath.
            var shown = publishedState()
            var settling = 0L
            while (shown !in LIVE_STATES && settling < STATE_SETTLE_MILLIS) {
                composeRule.waitForIdle()
                SystemClock.sleep(POLL_MILLIS)
                settling += POLL_MILLIS
                shown = publishedState()
            }
            assertTrue("recovered the row but showed '$shown'", shown in LIVE_STATES)
        } finally {
            RecordingForegroundService.stopFromVisibleActivity(context, sessionId)
            SystemClock.sleep(STOP_SETTLE_MILLIS)
            sqlite.execSQL("DELETE FROM recording_sessions WHERE id = $sessionId")
        }
    }

    private fun grant(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, permission)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }

    /** The durable remains of a process death: an ACTIVE row with an owner that no longer exists. */
    private fun seedAbandonedSession(sqlite: SupportSQLiteDatabase, deadRuntime: String): Long {
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

    private fun ownerToken(sqlite: SupportSQLiteDatabase, sessionId: Long): String? = sqlite
        .query("SELECT location_owner_token FROM recording_sessions WHERE id = $sessionId")
        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun hasRecoverySegment(sqlite: SupportSQLiteDatabase, sessionId: Long): Boolean = sqlite
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
    private fun publishedState(): String? {
        var published: String? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            published = activity.window.decorView
                .getTag(R.id.recording_presentation_state) as? String
        }
        return published
    }

    private companion object {
        /** Owned again, and saying so. A rejected fix on a bare emulator is still a live recording. */
        val LIVE_STATES = setOf("RECORDING", "POOR_SIGNAL")
        const val RECOVERY_TIMEOUT_MILLIS = 25_000L
        const val STATE_SETTLE_MILLIS = 5_000L
        const val POLL_MILLIS = 250L
        const val STOP_SETTLE_MILLIS = 1_500L
        const val FIXTURE_SUFFIX = "p4-038"
    }
}
