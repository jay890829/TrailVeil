package app.trailveil.map

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry

/** Best-effort cleanup for pointer IDs stranded by a failed injected stream. */
internal fun bestEffortClearStuckInjectedPointers() {
    // CANCEL terminates the injected device stream by pointer ID; it does not need a target View.
    // Fixed finite coordinates keep this safe when the stress harness calls it from a worker.
    val centerX = 100f
    val centerY = 100f
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    // The suite injects at most IDs {0, 1}. A CANCEL against a clean state is expected to be
    // rejected, so both cardinality probes are deliberately unasserted and cannot replace the
    // original stream failure.
    intArrayOf(2, 1).forEach { pointerCount ->
        val properties = Array(pointerCount) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(pointerCount) { index ->
            MotionEvent.PointerCoords().apply {
                x = centerX + index * 10f
                y = centerY
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_CANCEL,
            pointerCount,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        runCatching { automation.injectInputEvent(event, true) }
        event.recycle()
    }
}
