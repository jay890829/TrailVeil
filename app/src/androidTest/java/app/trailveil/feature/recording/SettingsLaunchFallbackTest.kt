package app.trailveil.feature.recording

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.trailveil.MainActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * `P4-040`: an unresolvable vendor settings component must degrade to a screen that exists, not
 * throw. The JVM tests bind the decision table with synthetic exceptions; this binds the arm a JVM
 * test cannot reach — a real [android.content.ActivityNotFoundException] from the real platform,
 * raised by a component name this emulator genuinely does not have.
 */
class SettingsLaunchFallbackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun anUnresolvableSettingsComponentFallsBackToAScreenThatExists() {
        val fallbackRuns = AtomicInteger()
        var outcome: SettingsLaunchOutcome? = null

        composeRule.activityRule.scenario.onActivity { activity ->
            outcome = launchSettingsWithFallback(
                primary = {
                    // A vendor autostart screen on a platform that does not ship one: resolving this
                    // component fails, so the real platform throws the real exception. The fallback
                    // is recorded rather than actually launched, because launching Settings
                    // mid-suite backgrounds the test activity and this suite already records
                    // cross-test-state flakes.
                    activity.startActivity(
                        Intent().setComponent(
                            ComponentName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.autostart.AutoStartManagementActivity",
                            ),
                        ),
                    )
                },
                fallback = { fallbackRuns.incrementAndGet() },
            )
        }

        assertEquals(SettingsLaunchOutcome.FELL_BACK, outcome)
        assertEquals(1, fallbackRuns.get())
        // "A reachable system screen" is a checked claim, not an assumption: the screen the
        // production fallback opens must actually resolve on this device.
        assertNotNull(
            "ACTION_SETTINGS resolves nowhere, so the production fallback would also fail",
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .packageManager
                .resolveActivity(Intent(Settings.ACTION_SETTINGS), 0),
        )
    }
}
