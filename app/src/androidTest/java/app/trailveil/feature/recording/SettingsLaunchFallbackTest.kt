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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * `P4-040`: an unresolvable vendor settings component must degrade to a screen that exists, not
 * throw. The JVM tests bind the decision table with synthetic exceptions; this binds the arm a JVM
 * test cannot reach — a real [android.content.ActivityNotFoundException] from the real platform,
 * raised by a component name the device genuinely does not have.
 *
 * The component used to be MIUI's autostart screen, picked as an example of something a normal
 * platform does not ship. On the owner's Xiaomi it resolves: the launch succeeds, the outcome is
 * not `FELL_BACK`, and the case fails while proving nothing about the fallback — and it puts the
 * MIUI screen in front of the test, backgrounding the activity, which is exactly what the comment
 * below says this fixture avoids. "A component that does not resolve" is now a checked property of
 * a name that cannot exist anywhere, not an assumption about a vendor.
 */
class SettingsLaunchFallbackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun anUnresolvableSettingsComponentFallsBackToAScreenThatExists() {
        val fallbackRuns = AtomicInteger()
        var outcome: SettingsLaunchOutcome? = null
        // Our own package, a class that does not exist in it. No device can resolve this, so the
        // platform raises the real exception on every one of them — and unlike a vendor name, it
        // cannot start an activity if some device happens to have it.
        val absent = ComponentName(
            ApplicationProvider.getApplicationContext<android.content.Context>().packageName,
            "app.trailveil.feature.recording.NoSuchSettingsActivityForTesting",
        )
        // Assert the precondition instead of assuming it: if this ever resolves, the case has
        // stopped testing the fallback and must say so rather than pass.
        assertNull(
            "the component chosen to be unresolvable resolves on this device, so the primary launch " +
                "would succeed and the fallback arm would never be reached: " + absent,
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .packageManager
                .resolveActivity(Intent().setComponent(absent), 0),
        )

        composeRule.activityRule.scenario.onActivity { activity ->
            outcome = launchSettingsWithFallback(
                primary = {
                    // A component that cannot exist on any device: resolving it fails, so the real
                    // platform throws the real exception. The fallback is recorded rather than
                    // actually launched, because launching Settings mid-suite backgrounds the test
                    // activity and this suite already records cross-test-state flakes.
                    activity.startActivity(Intent().setComponent(absent))
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
