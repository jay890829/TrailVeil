package app.trailveil.googlepoc

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.map.bestEffortClearStuckInjectedPointers

/**
 * `V02-005` stage 3, SP5: deterministic fling injection. Velocity is a pure function of the
 * injected eventTime stamps (10 ACTION_MOVE samples at exactly 6 ms steps over 600 px), so the
 * SDK's VelocityTracker sees the same nominal ~10,000 px/s every trial. To move the camera WEST
 * the finger travels left-to-right, and the path keeps >= 100 px from every screen edge so
 * gesture navigation cannot read it as back.
 */
object FlingGestureInjector {
    const val NOMINAL_VELOCITY_PX_PER_SECOND = 10_000
    private const val MOVE_SAMPLES = 10
    private const val STEP_MILLIS = 6L
    private const val TRAVEL_PX = 600

    data class InjectedFling(val startX: Int, val endX: Int, val y: Int, val durationMillis: Long)

    fun flingCameraWest(centerX: Int, centerY: Int, screenWidth: Int): InjectedFling {
        val half = TRAVEL_PX / 2
        val startX = (centerX - half).coerceAtLeast(120)
        val endX = (centerX + half).coerceAtMost(screenWidth - 120)
        inject(startX.toFloat(), endX.toFloat(), centerY.toFloat())
        return InjectedFling(startX, endX, centerY, STEP_MILLIS * MOVE_SAMPLES)
    }

    fun tripleFlingCameraWest(centerX: Int, centerY: Int, screenWidth: Int): List<InjectedFling> =
        (0 until 3).map { index ->
            if (index > 0) SystemClock.sleep(150L)
            flingCameraWest(centerX, centerY, screenWidth)
        }

    private fun inject(startX: Float, endX: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        bestEffortClearStuckInjectedPointers()
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, eventTime: Long): MotionEvent =
            MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }

        fun injectChecked(action: String, motionEvent: MotionEvent) {
            val accepted = try {
                automation.injectInputEvent(motionEvent, true)
            } finally {
                motionEvent.recycle()
            }
            check(accepted) { "fling $action was rejected by InputDispatcher" }
        }

        var streamEnded = false
        try {
            injectChecked("DOWN", event(MotionEvent.ACTION_DOWN, startX, downTime))
            for (sample in 1..MOVE_SAMPLES) {
                val progress = sample.toFloat() / MOVE_SAMPLES
                injectChecked(
                    "MOVE_$sample",
                    event(
                        MotionEvent.ACTION_MOVE,
                        startX + (endX - startX) * progress,
                        downTime + sample * STEP_MILLIS,
                    ),
                )
            }
            injectChecked(
                "UP",
                event(MotionEvent.ACTION_UP, endX, downTime + MOVE_SAMPLES * STEP_MILLIS),
            )
            streamEnded = true
        } finally {
            if (!streamEnded) bestEffortClearStuckInjectedPointers()
        }
    }
}
