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
 *
 * `V02-007` adds [withStream]: the same raw-MotionEvent plumbing opened as a multi-pointer
 * stream (ACTION_POINTER_DOWN/ACTION_POINTER_UP with stable pointer ids and coordinated
 * per-frame moves), which is what the pinch, shove, rotate, two-finger-tap and shove-into-pinch
 * exposure twins need. The single-pointer fling API above is unchanged in signature and in
 * behaviour: [flingCameraWest] and [tripleFlingCameraWest] still build their own events through
 * the original private [inject], so the eight call sites that use them are unaffected.
 */
object FlingGestureInjector {
    const val NOMINAL_VELOCITY_PX_PER_SECOND = 10_000
    private const val MOVE_SAMPLES = 10
    private const val STEP_MILLIS = 6L
    private const val TRAVEL_PX = 600

    /**
     * Two is the whole injectable range, not an arbitrary cap:
     * `bestEffortClearStuckInjectedPointers` probes exactly the {0} and {0, 1} stuck states, so a
     * three-pointer stream that failed part way could not be cleaned up, and would wedge every
     * later injection in the process.
     */
    private const val MAXIMUM_POINTERS = 2

    data class InjectedFling(val startX: Int, val endX: Int, val y: Int, val durationMillis: Long)

    /** One screen-space pointer position inside an injected stream. */
    data class TouchPoint(val x: Float, val y: Float)

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

    /**
     * Opens one raw pointer stream, hands it to [block], and ALWAYS abandons it afterwards.
     *
     * Scoped rather than open-and-close because every failure mode of a stream strands pointer ids
     * for the whole process, not just for the failing test: a rejected event, an `AssertionError`
     * from the caller's own engagement check, and the `check()` preconditions below all leave
     * fingers "down" as far as the input dispatcher is concerned, and every later injection in the
     * process is then refused. [PointerStream.abandonIfOpen] is a no-op after a clean lift, so the
     * happy path costs nothing.
     *
     * Every event in a stream shares a single downTime (stamped at [PointerStream.down], not when
     * the stream is opened) and identical pointer properties, because a pointer whose tool type or
     * precision changes part way through is a different pointer as far as the input pipeline is
     * concerned and the gesture never begins. The caller owns the geometry; this owns only the
     * event shape and the stuck-pointer cleanup.
     */
    fun <T> withStream(block: (PointerStream) -> T): T {
        val stream = PointerStream()
        return try {
            block(stream)
        } finally {
            stream.abandonIfOpen()
        }
    }

    /**
     * A single injected touch stream. Not thread-safe: one stream belongs to one driving thread.
     *
     * The class refuses malformed streams up front (a POINTER_DOWN whose count does not grow by
     * one, a MOVE whose count does not match, an UP with a partner still down) rather than
     * letting the input dispatcher reject them later, because a rejection mid-stream strands
     * pointer ids for the rest of the process.
     */
    class PointerStream internal constructor() {
        private var downTime = 0L
        private var openPointers = 0
        private var closed = false

        init {
            // A stuck injected-pointer state from any earlier crashed stream would reject this
            // stream's opening DOWN; clear it rather than inherit it.
            bestEffortClearStuckInjectedPointers()
        }

        /** Pointers currently down in this stream. */
        val pointerCount: Int get() = openPointers

        fun down(point: TouchPoint) {
            check(openPointers == 0) { "stream already has $openPointers pointers down" }
            // Stamped HERE rather than when the stream was opened. A tap is a DOWN and an UP whose
            // separation the detector reads as `eventTime - downTime`, so a downTime taken while
            // the caller was still resolving view geometry turns a 60 ms tap into a long press and
            // the double-tap and two-finger-tap detectors never fire at all. The DOWN carries that
            // same instant as its own eventTime, exactly as a real device's first event does.
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, listOf(point), "DOWN", downTime)
            openPointers = 1
        }

        /** Adds one pointer; [points] must list every pointer including the new last one. */
        fun pointerDown(points: List<TouchPoint>) {
            check(openPointers in 1 until MAXIMUM_POINTERS) {
                "cannot add a pointer to a stream holding $openPointers"
            }
            check(points.size == openPointers + 1) {
                "POINTER_DOWN needs ${openPointers + 1} points, got ${points.size}"
            }
            val index = points.size - 1
            send(
                MotionEvent.ACTION_POINTER_DOWN or
                    (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                points,
                "POINTER_DOWN",
            )
            openPointers = points.size
        }

        /** One coordinated frame: every pointer moves in the same event, as a real hand does. */
        fun move(points: List<TouchPoint>) {
            check(points.size == openPointers) {
                "MOVE needs $openPointers points, got ${points.size}"
            }
            send(MotionEvent.ACTION_MOVE, points, "MOVE")
        }

        /** Lifts the last pointer; [points] must still list every pointer that is down. */
        fun pointerUp(points: List<TouchPoint>) {
            check(openPointers >= 2) { "cannot lift a partner from $openPointers pointers" }
            check(points.size == openPointers) {
                "POINTER_UP needs $openPointers points, got ${points.size}"
            }
            val index = points.size - 1
            send(
                MotionEvent.ACTION_POINTER_UP or
                    (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                points,
                "POINTER_UP",
            )
            openPointers = points.size - 1
        }

        fun up(point: TouchPoint) {
            check(openPointers == 1) { "UP needs exactly one pointer down, has $openPointers" }
            send(MotionEvent.ACTION_UP, listOf(point), "UP")
            openPointers = 0
            closed = true
        }

        /** Ends the stream cleanly from any pointer count, lifting partners last-first. */
        fun liftAll(points: List<TouchPoint>) {
            check(points.size == openPointers) {
                "liftAll needs $openPointers points, got ${points.size}"
            }
            var remaining = points
            while (openPointers > 1) {
                pointerUp(remaining)
                remaining = remaining.dropLast(1)
            }
            if (openPointers == 1) up(remaining.first())
        }

        /**
         * Abandons an unfinished stream. A failure can strand either {0, 1} or just {0}, and a
         * CANCEL only clears a state whose pointer count it matches, so both are probed and
         * neither is asserted: this must never replace the original failure.
         */
        fun abandonIfOpen() {
            if (closed) return
            closed = true
            openPointers = 0
            bestEffortClearStuckInjectedPointers()
        }

        private fun send(
            action: Int,
            points: List<TouchPoint>,
            name: String,
            eventTime: Long = SystemClock.uptimeMillis(),
        ) {
            check(!closed) { "the injected pointer stream is already closed" }
            check(downTime != 0L) { "$name was sent before the stream's opening DOWN" }
            val properties = Array(points.size) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coordinates = Array(points.size) { index ->
                MotionEvent.PointerCoords().apply {
                    x = points[index].x
                    y = points[index].y
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                points.size,
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
            val accepted = try {
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(
                    event,
                    true,
                )
            } finally {
                event.recycle()
            }
            if (!accepted) {
                closed = true
                openPointers = 0
                bestEffortClearStuckInjectedPointers()
                error("$name with ${points.size} pointers was rejected by InputDispatcher")
            }
        }
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
