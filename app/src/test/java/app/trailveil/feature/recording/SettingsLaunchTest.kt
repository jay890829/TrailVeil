package app.trailveil.feature.recording

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLaunchTest {
    @Test
    fun `a launch that does not throw never invokes the fallback`() {
        val fallbackRuns = AtomicInteger()

        val outcome = launchSettingsWithFallback(
            primary = {},
            fallback = { fallbackRuns.incrementAndGet() },
        )

        assertEquals(SettingsLaunchOutcome.LAUNCHED, outcome)
        assertEquals(0, fallbackRuns.get())
    }

    @Test
    fun `a screen the app may not launch falls back instead of throwing`() {
        // SecurityException is the arm the previous shape rethrew: a component that exists but is
        // unexported or permission-guarded does not throw ActivityNotFoundException, and the crash
        // went straight through four existing notices' settings buttons.
        val fallbackRuns = AtomicInteger()

        val outcome = launchSettingsWithFallback(
            primary = { throw SecurityException("not exported") },
            fallback = { fallbackRuns.incrementAndGet() },
        )

        assertEquals(SettingsLaunchOutcome.FELL_BACK, outcome)
        assertEquals(1, fallbackRuns.get())
    }

    @Test
    fun `a fallback that also cannot launch is reported rather than thrown`() {
        // The previous shape's fallback startActivity sat outside the try; a device whose Settings
        // activity is somehow unlaunchable killed the activity instead of degrading.
        assertEquals(
            SettingsLaunchOutcome.UNREACHABLE,
            launchSettingsWithFallback(
                primary = { throw SecurityException("primary") },
                fallback = { throw SecurityException("fallback") },
            ),
        )
    }
}
