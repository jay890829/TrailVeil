package app.trailveil.googlepoc

import android.os.SystemClock

/**
 * One held two-finger pinch, in either direction, injected at display coordinates.
 *
 * Extracted from `GoogleFogMosaicArmSpikeTest` when a second spike needed the same gesture for a
 * different reason. That reason is worth stating here, because it is the whole point of injecting a
 * gesture rather than calling `moveCamera`: **`fogCameraReaction` treats a programmed move and a
 * gesture differently, and not only in which cover reason it names.** A move whose reason is not
 * `GESTURE` and which stays inside the published surround returns `REBUILD_AT_IDLE`, so it marks
 * the viewport dirty and rebuilds unconditionally; a GESTURE returns `LEAVE_PUBLISHED_COVERAGE` and
 * leaves the decision to the per-frame surround check. `V03-011` arm 2's entire claim lives in that
 * second branch, so a spike that drove the camera programmatically would rebuild under every arm
 * and measure nothing - which is exactly what the first run of the boundary spike did.
 *
 * The direction is read off the spans rather than passed: an opening that ends wider than it
 * started is a zoom IN, and every engagement and travel test is written in terms of "how far it
 * went the way it was asked to go" so one body serves both.
 *
 * **Why the opening widens on a retry.** Google's scale detector has a minimum span, this file does
 * not know what it is on this device, and a spread has to START narrow to have room to travel - the
 * mirror image of the zoom-out case, whose opening span is the widest of its rungs. A first attempt
 * that opens too narrow engages nothing, which at this level is indistinguishable from a map that
 * ignored the gesture. So each attempt opens wider than the last while keeping the span RATIO above
 * what the required zoom change needs, and the accepted attempt records which rung it was on.
 */
internal object SpikePinchGesture {

    /** What one accepted pinch did, in the caller's own clock. */
    data class Attempt(
        val note: String,
        val downAtMillis: Long,
        val upAtMillis: Long,
        val injectedDownCount: Int,
        val attempts: Int,
        val travelled: Float,
    )

    /**
     * Injects until the SDK's scale detector engages and the required travel is met, or throws.
     *
     * [origin], [width] and [height] are the live map view's screen rectangle; [zoom] reads the
     * camera's current zoom; [diagnostics] is appended to the failure message and is the caller's
     * own description of the surface, because this object knows nothing about it.
     */
    fun drive(
        label: String,
        origin: IntArray,
        width: Float,
        height: Float,
        pointerYFraction: Float,
        spans: List<Pair<Float, Float>>,
        minimumZoomChange: Float,
        zoom: () -> Float,
        diagnostics: () -> String,
    ): Attempt {
        val centreX = origin[0] + width / 2f
        val y = origin[1] + height * pointerYFraction

        fun pair(span: Float): List<FlingGestureInjector.TouchPoint> = listOf(
            FlingGestureInjector.TouchPoint(centreX - span / 2f, y),
            FlingGestureInjector.TouchPoint(centreX + span / 2f, y),
        )

        var downs = 0
        var attempts = 0
        val notes = mutableListOf<String>()
        repeat(ATTEMPTS) { attempt ->
            attempts += 1
            val rung = spans[attempt.coerceAtMost(spans.lastIndex)]
            val startSpan = width * rung.first
            val endSpan = width * rung.second
            val zoomingIn = endSpan > startSpan
            val zoomAtDown = zoom()
            fun travelled(): Float = if (zoomingIn) zoom() - zoomAtDown else zoomAtDown - zoom()

            val openedAt = SystemClock.elapsedRealtime()
            val accepted = FlingGestureInjector.withStream { stream ->
                stream.down(pair(startSpan).first())
                downs += 1
                stream.pointerDown(pair(startSpan))
                val engageSpan = startSpan + (endSpan - startSpan) * ENGAGE_TRAVEL
                repeat(ENGAGE_MOVES) { move ->
                    stream.move(
                        pair(startSpan + (engageSpan - startSpan) * (move + 1) / ENGAGE_MOVES),
                    )
                    SystemClock.sleep(STEP_MILLIS)
                }
                val engagement = travelled()
                if (engagement < MINIMUM_ENGAGEMENT) {
                    stream.liftAll(pair(engageSpan))
                    notes += "rung=${rung.first}->${rung.second} " +
                        "engagement=${"%.3f".format(engagement)}"
                    return@withStream null
                }
                var span = engageSpan
                var step = 0
                while (step < MEASURED_STEPS) {
                    step += 1
                    span = engageSpan + (endSpan - engageSpan) * step / MEASURED_STEPS
                    stream.move(pair(span))
                    SystemClock.sleep(STEP_MILLIS)
                    // Travel until the requirement is met with margin, then stop - the same rule
                    // the parity gate's pinch uses, and for the same reason: a fixed span ratio
                    // would be a guess at the SDK's span-to-zoom mapping in both directions.
                    if (step % TRAVEL_CHECK_EVERY == 0 &&
                        travelled() >= minimumZoomChange + TRAVEL_MARGIN
                    ) {
                        break
                    }
                }
                // Held, so the audited window contains frames of a stopped camera at the new zoom
                // rather than only frames of one still moving towards it.
                SystemClock.sleep(HOLD_MILLIS)
                stream.liftAll(pair(span))
                Attempt(
                    note = "$label ${if (zoomingIn) "spread" else "pinch"} " +
                        "spanPx=${startSpan.toInt()}->${span.toInt()} moves=$step " +
                        "travelled=${"%.3f".format(travelled())}",
                    downAtMillis = openedAt,
                    upAtMillis = SystemClock.elapsedRealtime(),
                    injectedDownCount = downs,
                    attempts = attempts,
                    travelled = travelled(),
                )
            }
            if (accepted != null) return accepted
            SystemClock.sleep(RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(
            "$label: the injected stream never engaged the SDK's scale detector in $attempts " +
                "attempts ($downs injected ACTION_DOWNs): ${notes.joinToString(" ")} " +
                diagnostics(),
        )
    }

    /**
     * Opening and closing spans for a SPREAD, in fractions of the map's width, widest travel first.
     *
     * Every rung keeps a span ratio above `2^(1.5 + 0.35)` = 3.61, which is what a 1.5-level
     * requirement plus the travel margin costs, so a later rung buys engagement without quietly
     * buying a gesture too small to cross the zoom boundary being measured. The closing spans stop
     * short of the full width for the reason the parity gate's opening span does: a pointer at the
     * very edge is in the system's back-gesture strip.
     */
    val SPREAD_SPANS = listOf(
        0.16f to 0.86f,
        0.20f to 0.86f,
        0.24f to 0.90f,
    )

    /** The parity gate's own proven pinch, unchanged. */
    val PINCH_OUT_SPANS = listOf(0.76f to 0.06f)

    /** Deep inside the band the entry screen's own controls cannot reach. */
    const val POINTER_Y_FRACTION = 0.52f

    /**
     * Injection geometry and cadence, taken from `GoogleGestureExposureTest` because those are the
     * values this repository has already made deterministic on an emulator.
     */
    const val ATTEMPTS = 4
    const val ENGAGE_MOVES = 8
    const val ENGAGE_TRAVEL = 0.30f
    const val MEASURED_STEPS = 30
    const val STEP_MILLIS = 16L
    const val TRAVEL_CHECK_EVERY = 5
    const val RETRY_SETTLE_MILLIS = 2_500L
    const val HOLD_MILLIS = 600L
    const val MINIMUM_ENGAGEMENT = 0.03f
    const val TRAVEL_MARGIN = 0.35f
}
