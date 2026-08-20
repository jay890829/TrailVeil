package app.trailveil.feature.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the blocked automatic resume from a clean pre-Activity fixture.
 *
 * The abandoned row and disabled location state exist before [MainActivity] is launched, so the
 * first composition is the production path that discovers the row and raises its blocker. This
 * avoids both a lifecycle race around an already-running Activity and an assertion that can observe
 * only a decor diagnostic left by an earlier composition.
 */
@RunWith(AndroidJUnit4::class)
class BlockedResumeComposeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context as TrailVeilApplication).appContainer

    @Test
    fun aBlockedResumeRaisesItsBlockerAndNeverTheBackgroundStartGuidance() {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        shell("cmd location set-location-enabled false")
        assertFalse(
            "location services could not be disabled before the blocked-resume fixture",
            requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled,
        )

        var sqlite: SupportSQLiteDatabase? = null
        var sessionId = 0L
        try {
            val fixtureSqlite = container.databaseForTesting().openHelper.writableDatabase
            sqlite = fixtureSqlite
            container.databaseForTesting().runInTransaction {
                seedAbandonedSession(
                    fixtureSqlite,
                    deadRuntime = "runtime-behind-a-blocked-resume-p4-040",
                ).also { sessionId = it }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitSemanticsTag(
                    scenario = scenario,
                    tag = RecordingEntryTestTags.Menu,
                    label = "Menu",
                )
                // The v2 rule uses StandardTestDispatcher. Once a real root exists, this is the
                // safe point to advance startup/history collectors and the automatic resume effect;
                // before the root exists, waitForIdle can hide the actual lifecycle diagnosis.
                composeRule.waitForIdle()
                awaitPublishedState(scenario, expected = "ABANDONED")
                awaitSemanticsTag(
                    scenario = scenario,
                    tag = RecordingEntryTestTags.LocationNotice,
                    label = "automatic location blocker notice",
                    pumpCompose = true,
                )

                // The positive notice is the route-level oracle: the automatic Abandoned Resume ran
                // and returned LOCATION_DISABLED. ABANDONED alone would prove only presentation.
                composeRule.onNodeWithTag(RecordingEntryTestTags.BackgroundStartNotice)
                    .assertDoesNotExist()

                val notNow = context.getString(R.string.permission_not_now)
                composeRule.onNodeWithText(notNow).performScrollTo().performClick()
                awaitSemanticsAbsent(
                    scenario = scenario,
                    tag = RecordingEntryTestTags.LocationNotice,
                    label = "dismissed location blocker notice",
                )
                composeRule.onNodeWithTag(RecordingEntryTestTags.LocationNotice)
                    .assertDoesNotExist()
                composeRule.onNodeWithTag(RecordingEntryTestTags.BackgroundStartNotice)
                    .assertDoesNotExist()
            }
        } finally {
            shell("cmd location set-location-enabled true")
            if (sessionId != 0L) {
                sqlite?.execSQL("DELETE FROM recording_sessions WHERE id = $sessionId")
            }
        }
    }

    private fun awaitSemanticsTag(
        scenario: ActivityScenario<MainActivity>,
        tag: String,
        label: String,
        pumpCompose: Boolean = false,
    ) {
        val deadline = SystemClock.uptimeMillis() + READINESS_TIMEOUT_MILLIS
        var lifecycleState = "unknown"
        var publishedState: String? = null
        var decorState = "unknown"
        var lastError: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            if (pumpCompose) composeRule.waitForIdle()
            runCatching {
                scenario.onActivity { activity ->
                    lifecycleState = activity.lifecycle.currentState.name
                    publishedState = activity.window.decorView
                        .getTag(R.id.recording_presentation_state) as? String
                    decorState = describeDecor(activity)
                }
            }.onFailure { failure -> lastError = failure.toString() }
            val present = runCatching {
                composeRule.onAllNodesWithTag(tag)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            }.onFailure { failure -> lastError = failure.toString() }
                .getOrDefault(false)
            if (present) return
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "Timed out waiting for $label: lifecycle=$lifecycleState " +
                "publishedState=$publishedState decor=$decorState lastError=$lastError",
        )
    }

    private fun awaitPublishedState(
        scenario: ActivityScenario<MainActivity>,
        expected: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + READINESS_TIMEOUT_MILLIS
        var lifecycleState = "unknown"
        var publishedState: String? = null
        var decorState = "unknown"
        var lastError: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            runCatching {
                scenario.onActivity { activity ->
                    lifecycleState = activity.lifecycle.currentState.name
                    publishedState = activity.window.decorView
                        .getTag(R.id.recording_presentation_state) as? String
                    decorState = describeDecor(activity)
                }
            }.onFailure { failure -> lastError = failure.toString() }
            if (publishedState == expected) return
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "Timed out waiting for published state $expected: lifecycle=$lifecycleState " +
                "publishedState=$publishedState decor=$decorState lastError=$lastError",
        )
    }

    private fun awaitSemanticsAbsent(
        scenario: ActivityScenario<MainActivity>,
        tag: String,
        label: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + READINESS_TIMEOUT_MILLIS
        var lifecycleState = "unknown"
        var publishedState: String? = null
        var decorState = "unknown"
        var lastError: String? = null
        var menuPresent = false
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            runCatching {
                scenario.onActivity { activity ->
                    lifecycleState = activity.lifecycle.currentState.name
                    publishedState = activity.window.decorView
                        .getTag(R.id.recording_presentation_state) as? String
                    decorState = describeDecor(activity)
                }
            }.onFailure { failure -> lastError = failure.toString() }
            val present = runCatching {
                composeRule.onAllNodesWithTag(tag)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            }.onFailure { failure -> lastError = failure.toString() }
                .getOrDefault(false)
            menuPresent = runCatching {
                composeRule.onAllNodesWithTag(RecordingEntryTestTags.Menu)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            }.onFailure { failure -> lastError = failure.toString() }
                .getOrDefault(false)
            if (!present && menuPresent) return
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "Timed out waiting for $label to disappear: lifecycle=$lifecycleState " +
                "publishedState=$publishedState menuPresent=$menuPresent " +
                "decor=$decorState lastError=$lastError",
        )
    }

    private fun describeDecor(activity: MainActivity): String {
        val decor = activity.window.decorView
        return "attached=${decor.isAttachedToWindow},children=" +
            "${(decor as? android.view.ViewGroup)?.childCount ?: -1}," +
            "destroyed=${activity.isDestroyed},finishing=${activity.isFinishing}"
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

    /** The durable remains of a process death: an ACTIVE row with an owner that no live runtime holds. */
    private fun seedAbandonedSession(
        sqlite: SupportSQLiteDatabase,
        deadRuntime: String,
        startedAt: Long = System.currentTimeMillis(),
    ): Long {
        sqlite.execSQL(
            "INSERT INTO recording_sessions(" +
                "started_at, ended_at, status, stop_reason, distance_meters, accepted_point_count, " +
                "rejected_point_count, created_app_version, active_slot, location_owner_token" +
                ") VALUES($startedAt, NULL, 'ACTIVE', NULL, 0, 0, 0, 'abandoned-state-test', " +
                "1, '$deadRuntime')",
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

    private companion object {
        const val READINESS_TIMEOUT_MILLIS = 25_000L
        const val POLL_MILLIS = 250L
    }
}
