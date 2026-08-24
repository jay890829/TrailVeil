package app.trailveil.feature.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.recording.RecordingForegroundService
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Activity Result/SavedState regression for P3-006's pending notification Start.
 *
 * P4-049 keeps this device-only case out of hosted CI. The hosted Google APIs emulator has never
 * presented and completed this scenario: every finished sample skipped after the 60-second prompt
 * budget, while intermittent downstream stalls inside this exact test killed the whole rest shard.
 * The blocked frame was never captured, so this does not assign a permission-controller root cause.
 * The source and manifest entry remain, and explicit device/unfiltered runs still execute it.
 */
@RunWith(AndroidJUnit4::class)
class NotificationStartContinuationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Single-threaded on purpose: see [denyButtonNodes]. Daemon, so a stuck probe cannot hold the
     *  process open. JUnit builds one instance per test method, so this is one thread per test. */
    private val accessibilityProbe = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "deny-button-probe").apply { isDaemon = true }
    }

    /** Non-zero means the wedge described on [boundedProbe] actually happened in this run. */
    private val probeExpiries = AtomicInteger(0)

    @Test
    fun recreationWhileThePromptIsVisibleContinuesOneDeniedStartExactlyOnce() {
        val historyStore = PermissionHistoryStore(context)
        val originalHistory = runBlocking { historyStore.current() }
        var sessionIdsBeforeStart: Set<Long>? = null

        // P4-046: this guard is OUTSIDE the try on purpose. Revoking a granted runtime permission
        // from in here does not fail this test, it ENDS THE RUN - Android kills a process when a
        // permission it holds is taken away, and instrumentation lives inside the app's process, so
        // the revoke below would kill the runner and every remaining test would be silently skipped,
        // reported only as `Process crashed` with no assertion and no name.
        //
        // Measured: a full local suite aborted here at case ~90 of 175 twice. The permission state
        // comes from whatever ran before — `RecordingOutcomeNotificationTest` grants it and leaves
        // it granted — so the FIRST run after a fresh install works and every later one aborts.
        //
        // It sits outside the try because the `finally` also revokes: a first version put the guard
        // inside, the skip fired, and the cleanup then killed the process anyway. Skipping loudly
        // is the honest answer, and the revoke is safe from the HOST, where no app process is alive
        // to kill — so the run gets prepared rather than repaired.
        assumeTrue(
            "P4-046: POST_NOTIFICATIONS is granted, and revoking it from inside instrumentation " +
                "would kill this process and abort the whole run. Prepare the device from the host " +
                "first: adb shell pm revoke app.trailveil android.permission.POST_NOTIFICATIONS",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_DENIED,
        )

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

            val firstRequestedAt = SystemClock.uptimeMillis()
            val firstDeny = awaitDenyButton(PROMPT_APPEARANCE_TIMEOUT_MILLIS)
            val firstPromptMillis = SystemClock.uptimeMillis() - firstRequestedAt
            if (firstDeny == null) {
                // Observed on hosted CI at run 31599322862: the app had launched the request and was
                // correctly parked in AWAITING_RESULT, and the platform still presented no dialog and
                // returned no result for 60 s. The app's half of this is observable, so the two cases
                // are separated rather than both blamed on the product. A continuation that never
                // reached AWAITING_RESULT means the request was never launched, which *is* this
                // task's defect, and falls through to the hard failure below.
                // An expired probe is not an absent dialog, it is the P4-049 wedge: the prompt was
                // unobservable, not missing. Assert before the skip so it can never go green.
                assertEquals(
                    "The accessibility probe stopped returning, so the prompt could not be observed " +
                        "at all. This is the hosted wedge from P4-049, not a host without a " +
                        "dialog: ${promptDiagnostics()}",
                    0,
                    probeExpiries.get(),
                )
                assumeTrue(
                    "This host never presented the notification prompt within " +
                        "$PROMPT_APPEARANCE_TIMEOUT_MILLIS ms while the app was correctly waiting " +
                        "for its result, so it cannot exercise recreation behind a live dialog: " +
                        promptDiagnostics(),
                    currentContinuation() != AWAITING_RESULT,
                )
            }
            assertNotNull(
                "The runtime notification prompt was never shown within " +
                    "$PROMPT_APPEARANCE_TIMEOUT_MILLIS ms and the saved continuation never reached " +
                    "$AWAITING_RESULT, so the start request was never launched: ${promptDiagnostics()}",
                firstDeny,
            )
            // Also proves the diagnostic tag below is really wired: if it ever silently returned
            // "unavailable", promptDiagnostics() would be decorative and this fails first.
            assertEquals(
                "The saved continuation did not reach $AWAITING_RESULT while the prompt was visible",
                AWAITING_RESULT,
                currentContinuation(),
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
            val recreatedRequestedAt = SystemClock.uptimeMillis()
            val recreatedDeny = awaitDenyButton(PROMPT_APPEARANCE_TIMEOUT_MILLIS)
            val recreatedPromptMillis = SystemClock.uptimeMillis() - recreatedRequestedAt
            assertNotNull(
                "The runtime notification prompt did not survive Activity recreation " +
                    "within $PROMPT_APPEARANCE_TIMEOUT_MILLIS ms: ${promptDiagnostics()}",
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

            // Recorded so a future hosted regression can be compared against real appearance
            // latency instead of being re-diagnosed from scratch.
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "TrailVeil notification-start continuation: " +
                            "firstPromptMillis=$firstPromptMillis " +
                            "recreatedPromptMillis=$recreatedPromptMillis " +
                            "promptBudgetMillis=$PROMPT_APPEARANCE_TIMEOUT_MILLIS " +
                            "beginStartReceipts=${beginReceiptsAfterStart - beginReceiptsBeforeStart}\n",
                    )
                },
            )
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
            // Deliberately NOT restoring a grant. `notificationWasGranted` can only be false to
            // reach here (the assume above), and re-granting would arm the same abort for the next
            // run - which is exactly how this defect stayed invisible for two occurrences.
            prepareFreshNotificationRequest()
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

    private fun awaitDenyButton(timeoutMillis: Long = UI_TIMEOUT_MILLIS): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val found = visibleDenyButton()
            if (found != null) return found
            SystemClock.sleep(POLL_MILLIS)
        }
        return null
    }

    /**
     * `P4-049`: this query is why the shard hung, and the bound above it was only apparent.
     *
     * `rootInActiveWindow` is a SYNCHRONOUS binder call into whichever process is drawing the
     * prompt. Measured on 2026-08-23 across six hosted runs of one unchanged commit, `rest-1` hung
     * in four of them, always here: `ps` at the stall showed `app.trailveil`, `app.trailveil.test`
     * and `com.google.android.permissioncontroller` all in interruptible sleep, **zero**
     * `do_freezer_trap`, no process in uninterruptible sleep and none burning CPU. The last
     * app-side log line was an `AccessibilityNodeInfo` access, then 35 minutes of nothing until the
     * shard timeout killed the job.
     *
     * [awaitDenyButton] re-checks its deadline only BETWEEN calls, so one call that never returns
     * escapes the deadline completely and takes the whole shard with it. Bounding the call is what
     * makes that deadline real. On expiry this reports "no nodes" and counts the expiry, the caller
     * keeps polling until its own budget runs out, and the run then ends through an assertion that
     * names the wedge and carries [promptDiagnostics] — instead of a dead job.
     *
     * Measured on 2026-08-23 by making this read block forever: the class finished in 72.8 s rather
     * than hanging. That first bound alone ended the run as a SKIP, because an unobservable prompt
     * is indistinguishable from an absent one until [probeExpiries] separates them; the guard at
     * the `assumeTrue` below is what keeps this from quietly going green.
     *
     * The worker is abandoned rather than joined on expiry: a thread parked in a binder
     * transaction cannot be interrupted, so waiting on it would reproduce the very hang this
     * removes. It is a daemon, and a stuck one makes every later probe expire too, which is the
     * behaviour worth having — the prompt is genuinely unreachable at that point.
     */
    private fun denyButtonNodes(): List<AccessibilityNodeInfo> =
        boundedProbe(emptyList()) { readDenyButtonNodes() }

    /**
     * Runs [read] on the probe thread and gives up after [ACCESSIBILITY_PROBE_TIMEOUT_MILLIS].
     *
     * Every accessibility read on the polling path goes through here, [promptDiagnostics] included:
     * a diagnostic that hangs is precisely the dead job it exists to report.
     */
    private fun <T> boundedProbe(fallback: T, read: () -> T): T = try {
        accessibilityProbe.submit<T> { read() }
            .get(ACCESSIBILITY_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (expired: TimeoutException) {
        probeExpiries.incrementAndGet()
        fallback
    } catch (rejected: RejectedExecutionException) {
        probeExpiries.incrementAndGet()
        fallback
    }

    private fun readDenyButtonNodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        // The permission controller ships as com.google.android.permissioncontroller on Google APIs
        // images while keeping the AOSP resource namespace. Match both so an image that namespaces
        // its own resources cannot be misread as "the prompt never appeared".
        return DENY_BUTTON_VIEW_IDS.flatMap { viewId ->
            root.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
        }
    }

    private fun visibleDenyButton(): AccessibilityNodeInfo? =
        denyButtonNodes().firstOrNull { node -> node.isVisibleToUser && node.isEnabled }

    /** The route's saved notification continuation, published as a view-tree diagnostic. */
    private fun currentContinuation(): String = runCatching {
        composeRule.runOnIdle {
            composeRule.activity.window.decorView
                .getTag(R.id.recording_notification_start_continuation)
                ?.toString()
        }
    }.getOrNull() ?: "unavailable"

    /**
     * Separates the two ways this gate can fail. An environmental slow start leaves the permission
     * controller owning a window; a genuine P3-006 defect leaves this app active with no prompt
     * ever launched. Without this, both present as "the prompt was never shown".
     */
    private fun promptDiagnostics(): String {
        val automation = instrumentation.uiAutomation
        val activePackage = boundedProbe("unreadable") {
            automation.rootInActiveWindow?.packageName?.toString() ?: "none"
        }
        val windowPackages = boundedProbe(emptyList<String>()) {
            runCatching {
                automation.windows.mapNotNull { window ->
                    window.root?.packageName?.toString()
                }.distinct()
            }.getOrDefault(emptyList())
        }
        val controllerPresent = windowPackages.any { name -> name.endsWith("permissioncontroller") }
        // The decisive field. IDLE or REQUESTING_PERMISSION means the app never reached
        // `launcher.launch` — a stall on the route's own DataStore writes looks exactly like a lost
        // continuation without it. AWAITING_RESULT means the request really was launched and the
        // prompt is what failed to appear.
        val continuation = currentContinuation()
        return "continuation=$continuation activeWindow=$activePackage windows=$windowPackages " +
            "permissionControllerWindow=$controllerPresent denyNodes=${denyButtonNodes().size} " +
            "selfPermission=${context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)} " +
            "probeExpiries=${probeExpiries.get()}"
    }

    private fun awaitDenyButtonGone(): Boolean {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (denyButtonNodes().none { node -> node.isVisibleToUser }) return true
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
        val DENY_BUTTON_VIEW_IDS = listOf(
            "com.android.permissioncontroller:id/permission_deny_button",
            "com.google.android.permissioncontroller:id/permission_deny_button",
        )
        const val UI_TIMEOUT_MILLIS = 10_000L

        /**
         * Only the prompt's first appearance uses this longer budget. A cold permission-controller
         * start on a headless software-rendered CI emulator exceeded the 10 s general UI budget in
         * two of three hosted runs while passing locally, so the old value measured the emulator
         * rather than the product. Every product assertion — the pre-result dwell, the exactly-once
         * BEGIN_START accounting, and the post-denial ACTIVE session — keeps its original budget.
         */
        const val PROMPT_APPEARANCE_TIMEOUT_MILLIS = 60_000L
        const val SERVICE_TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 50L

        /**
         * Generous against a healthy call, which returns in milliseconds, and far inside
         * [PROMPT_APPEARANCE_TIMEOUT_MILLIS] so the caller's budget is what decides the verdict.
         * This exists only to stop a call that never returns from being waited on forever.
         */
        const val ACCESSIBILITY_PROBE_TIMEOUT_MILLIS = 5_000L
        const val PRE_RESULT_DWELL_MILLIS = 500L

        /** The saved continuation state that proves the request really was launched. */
        const val AWAITING_RESULT = "AWAITING_RESULT"
    }
}
