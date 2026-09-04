package app.trailveil.map

import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Winds the composition clock alongside wall time, for cases that host their own Activity under an
 * empty Compose rule.
 *
 * `createEmptyComposeRule` installs the Compose test environment's recomposer for every composition
 * created in this process while the rule is active, and that environment intercepts the
 * continuations of composition-launched coroutines instead of letting them run on the UI
 * dispatcher. A `LaunchedEffect` that calls `delay` therefore resumes only when the test drives
 * that clock. Measured on the API 36 AVD while diagnosing `V02-007`: a poll loop of `waitForIdle()`
 * plus `SystemClock.sleep` advanced the composition clock **32 ms across 20.4 s of wall time**, and
 * a `delay(500)` placed inside the host's basemap load deadline ticked **zero** times before the
 * composition was torn down - while the fog binding's own `Handler`-scheduled deadline, which is
 * not composition-launched, fired on time. A case that waits on anything the composition produces
 * on its own clock - a host deadline, a state published from recomposition - must pump, or it is
 * waiting on a clock nothing is winding, and it will report the product as stuck when the product
 * is merely unscheduled.
 *
 * The same reading explains why such a case can pass alone and fail in a suite: `waitForIdle()`
 * does advance the clock a little, so a short wait sometimes lands and a long one never does.
 */
internal fun ComposeTestRule.pumpComposition(stepMillis: Long = DEFAULT_PUMP_STEP_MILLIS) {
    mainClock.advanceTimeBy(stepMillis)
    waitForIdle()
    SystemClock.sleep(stepMillis)
}

/**
 * Polls [condition] while pumping, and reports whether it held before [timeoutMillis] of wall time.
 *
 * The bound is wall time deliberately: the caller is usually waiting on something outside the
 * composition too - the Maps SDK loading a basemap, a tile arriving - and those run on the real
 * clock. Cases that need to attribute a *composition* deadline should measure
 * `mainClock.currentTime` instead, because that is the clock the deadline is counted on.
 */
internal fun ComposeTestRule.awaitPumping(
    timeoutMillis: Long,
    stepMillis: Long = DEFAULT_PUMP_STEP_MILLIS,
    condition: () -> Boolean,
): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (true) {
        if (condition()) return true
        if (SystemClock.elapsedRealtime() >= deadline) return false
        pumpComposition(stepMillis)
    }
}

private const val DEFAULT_PUMP_STEP_MILLIS = 100L
