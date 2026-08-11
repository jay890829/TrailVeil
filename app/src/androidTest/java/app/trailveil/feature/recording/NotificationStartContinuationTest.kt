package app.trailveil.feature.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.recording.RecordingForegroundService
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity Result/SavedState regression for P3-006's pending notification Start. */
@RunWith(AndroidJUnit4::class)
class NotificationStartContinuationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun recreationWhileThePromptIsVisibleContinuesOneDeniedStartExactlyOnce() {
        val historyStore = PermissionHistoryStore(context)
        val originalHistory = runBlocking { historyStore.current() }
        val notificationWasGranted =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        var sessionIdsBeforeStart: Set<Long>? = null

        try {
            enableSystemLocation()
            grant(Manifest.permission.ACCESS_COARSE_LOCATION)
            grant(Manifest.permission.ACCESS_FINE_LOCATION)
            prepareFreshNotificationRequest()
            runBlocking {
                historyStore.replaceForTesting(
                    PermissionHistory(
                        hasSeenIntroduction = true,
                        hasRequestedLocation = true,
                        hasRetriedLocation = true,
                        hasRequestedPreciseUpgrade = true,
                        hasRequestedNotifications = false,
                    ),
                )
            }
            // The rule launches before the test body. Recreate once after installing the isolated
            // permission fixture so the production route starts from that exact saved/platform
            // state; the second recreation below is the one under acceptance.
            composeRule.activityRule.scenario.recreate()

            val application = context.applicationContext as TrailVeilApplication
            val repository = application.appContainer.recordingRepository
            val database = application.appContainer.databaseForTesting()
            sessionIdsBeforeStart = database.sessionIds()
            val beginReceiptsBeforeStart = database.beginStartReceiptCount()

            composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
            composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
                runCatching {
                    composeRule.onNodeWithTag(RecordingEntryTestTags.Start)
                        .assertIsEnabled()
                    // The test replaced the persistent request marker before recreating the
                    // Activity. Wait until the new route has consumed that exact value; otherwise
                    // a stale previously-requested snapshot can legitimately skip the dialog.
                    composeRule.onNodeWithTag(RecordingEntryTestTags.NotificationNotice)
                        .assertDoesNotExist()
                    true
                }.getOrDefault(false)
            }
            composeRule.onNodeWithTag(RecordingEntryTestTags.Start).performClick()
            composeRule.waitForIdle()

            val firstDeny = awaitDenyButton()
            assertNotNull(
                "The runtime notification prompt was never shown",
                firstDeny,
            )
            val activityBeforePromptRecreation = composeRule.activity
            instrumentation.runOnMainSync { activityBeforePromptRecreation.recreate() }
            val recreationDeadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MILLIS
            while (
                !activityBeforePromptRecreation.isDestroyed &&
                SystemClock.uptimeMillis() < recreationDeadline
            ) {
                SystemClock.sleep(POLL_MILLIS)
            }
            assertTrue(
                "The Activity behind the runtime notification prompt was not recreated",
                activityBeforePromptRecreation.isDestroyed,
            )
            val recreatedDeny = awaitDenyButton()
            assertNotNull(
                "The runtime notification prompt did not survive Activity recreation",
                recreatedDeny,
            )
            SystemClock.sleep(PRE_RESULT_DWELL_MILLIS)
            assertNotNull(
                "The runtime notification prompt disappeared during the pre-result dwell",
                visibleDenyButton(),
            )
            val beginReceiptsWhilePromptVisible = database.beginStartReceiptCount()
            assertEquals(
                "A saved pending marker started before the permission result was observed",
                beginReceiptsBeforeStart,
                beginReceiptsWhilePromptVisible,
            )
            val stateWhilePromptVisible = runBlocking { repository.state() }
            assertTrue(
                "Recording became active before the permission result was observed",
                stateWhilePromptVisible.lifecycle != RecordingLifecycle.STARTING &&
                    stateWhilePromptVisible.lifecycle != RecordingLifecycle.ACTIVE,
            )
            assertTrue(
                "The permission controller denied click was not accepted",
                requireNotNull(recreatedDeny).performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            assertTrue(
                "The runtime notification prompt did not close after denial",
                awaitDenyButtonGone(),
            )
            composeRule.waitForIdle()

            val sessionId = runBlocking {
                withTimeout(SERVICE_TIMEOUT_MILLIS) {
                    while (true) {
                        val state = repository.state()
                        if (state.lifecycle == RecordingLifecycle.ACTIVE) {
                            return@withTimeout requireNotNull(state.sessionId)
                        }
                        delay(POLL_MILLIS)
                    }
                    error("unreachable")
                }
            }
            val beginReceiptCount = database.beginStartReceiptCount(sessionId)
            assertEquals(
                "Activity recreation or duplicate result delivery issued a second Start command",
                1,
                beginReceiptCount,
            )
            val beginReceiptsAfterStart = database.beginStartReceiptCount()
            assertEquals(
                "The pending user action did not produce exactly one durable Start command",
                beginReceiptsBeforeStart + 1,
                beginReceiptsAfterStart,
            )
            assertEquals(
                PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
            )
            composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
            composeRule.onNodeWithTag(RecordingEntryTestTags.Stop).assertIsEnabled()
            composeRule.onNodeWithTag(RecordingEntryTestTags.Stop).performClick()
            runBlocking {
                withTimeout(SERVICE_TIMEOUT_MILLIS) {
                    while (repository.state().lifecycle != RecordingLifecycle.STOPPED) {
                        delay(POLL_MILLIS)
                    }
                }
            }
        } finally {
            val application = context.applicationContext as TrailVeilApplication
            val database = application.appContainer.databaseForTesting()
            val createdSessionIds = sessionIdsBeforeStart
                ?.let { before -> database.sessionIds() - before }
                .orEmpty()
            runBlocking {
                val current = runCatching {
                    application.appContainer.recordingRepository.state()
                }.getOrNull()
                current?.sessionId
                    ?.takeIf { it in createdSessionIds }
                    ?.let { sessionId ->
                        runCatching {
                            context.startService(
                                Intent(context, RecordingForegroundService::class.java).apply {
                                    action = RecordingForegroundService.ACTION_STOP
                                    putExtra(
                                        RecordingForegroundService.EXTRA_SESSION_ID,
                                        sessionId,
                                    )
                                },
                            )
                        }
                        runCatching {
                            withTimeout(SERVICE_TIMEOUT_MILLIS) {
                                while (
                                    application.appContainer.recordingRepository.state().lifecycle ==
                                    RecordingLifecycle.STARTING ||
                                    application.appContainer.recordingRepository.state().lifecycle ==
                                    RecordingLifecycle.ACTIVE
                                ) {
                                    delay(POLL_MILLIS)
                                }
                            }
                        }
                    }
                database.withTransaction {
                    createdSessionIds.forEach { sessionId ->
                        database.openHelper.writableDatabase.execSQL(
                            "DELETE FROM recording_operation_receipts WHERE session_id = ?",
                            arrayOf(sessionId),
                        )
                        database.recordingDao().deleteSession(sessionId)
                    }
                }
            }
            runBlocking { historyStore.replaceForTesting(originalHistory) }
            if (notificationWasGranted) {
                grant(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                prepareFreshNotificationRequest()
            }
        }
    }

    private fun prepareFreshNotificationRequest() {
        shell("pm revoke ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        shell(
            "pm clear-permission-flags ${context.packageName} " +
                "${Manifest.permission.POST_NOTIFICATIONS} user-set",
        )
        shell(
            "pm clear-permission-flags ${context.packageName} " +
                "${Manifest.permission.POST_NOTIFICATIONS} user-fixed",
        )
        shell(
            "pm clear-permission-flags ${context.packageName} " +
                "${Manifest.permission.POST_NOTIFICATIONS} review-required revoked-compat " +
                "revoke-when-requested",
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    private fun grant(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
    }

    private fun enableSystemLocation() {
        shell("cmd location set-location-enabled true")
    }

    private fun shell(command: String) {
        val descriptor: ParcelFileDescriptor =
            instrumentation.uiAutomation.executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }

    private fun awaitDenyButton(): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val found = visibleDenyButton()
            if (found != null) return found
            SystemClock.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun visibleDenyButton(): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(DENY_BUTTON_VIEW_ID)
            .orEmpty()
            .firstOrNull { node -> node.isVisibleToUser && node.isEnabled }

    private fun awaitDenyButtonGone(): Boolean {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val visible = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(DENY_BUTTON_VIEW_ID)
                .orEmpty()
                .any { node -> node.isVisibleToUser }
            if (!visible) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun TrailVeilDatabase.sessionIds(): Set<Long> = query(
        "SELECT id FROM recording_sessions",
        emptyArray(),
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getLong(0))
        }
    }

    private fun TrailVeilDatabase.beginStartReceiptCount(sessionId: Long? = null): Int {
        val (sql, args) = if (sessionId == null) {
            "SELECT COUNT(*) FROM recording_operation_receipts " +
                "WHERE command_kind = 'BEGIN_START'" to emptyArray()
        } else {
            "SELECT COUNT(*) FROM recording_operation_receipts " +
                "WHERE session_id = ? AND command_kind = 'BEGIN_START'" to arrayOf(sessionId)
        }
        return query(sql, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private companion object {
        const val DENY_BUTTON_VIEW_ID =
            "com.android.permissioncontroller:id/permission_deny_button"
        const val UI_TIMEOUT_MILLIS = 10_000L
        const val SERVICE_TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 50L
        const val PRE_RESULT_DWELL_MILLIS = 500L
    }
}
