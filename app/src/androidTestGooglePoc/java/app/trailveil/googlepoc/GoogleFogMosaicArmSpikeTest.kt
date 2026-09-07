package app.trailveil.googlepoc

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.MainActivity
import app.trailveil.feature.recording.PermissionHistory
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.map.GoogleFogCoverageArm
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * `V03-011` arm 2, measured: the same ZOOM under `BASELINE` and under `MOSAIC_OVERLAY`.
 *
 * **Why a zoom, and why this class is not [GoogleFogPaddingRingArmSpikeTest] with another arm
 * appended.** Arm 1's ladder is a pan, because a padding ring is a pan device: its whole capacity
 * is measured in tiles of translation. Prototype A's claim is the one a ring cannot make at any
 * width - a coverage key carries the zoom it was planned at, so crossing an integer zoom boundary
 * invalidates every published key at once and `ring(64)` is defeated by it exactly as `ring(0)` is.
 * Run on arm 1's pan ladder, arm 2 would tie with `ring(1)` at a fraction of its cost, and that tie
 * would read as prototype A failing when it would really be the experiment failing to test the only
 * thing prototype A is for. See [FogContinuityArm.MOSAIC_OVERLAY] and [GestureKind.PINCH_ZOOM_IN].
 *
 * **Zoom IN is the measurement; zoom OUT is the control, and the control is not a formality.**
 * Zooming in shrinks the ground the camera sees, so it stays inside a ground-anchored image by
 * construction and the only thing left that can raise the cover is the tile grid's zoom step.
 * Zooming out grows it, so it leaves the image geometrically and MUST still cover. An arm 2 that
 * removed the cover from both would not be a better fog, it would be a fog whose surround predicate
 * lies in the fail-OPEN direction - bare basemap over unexplored ground, which is the one outcome
 * this repository treats as a defect rather than a trade-off. So this class asserts exactly that
 * one thing and reports everything else.
 *
 * **Opt-in, and otherwise not a gate.** Skipped unless `trailveilFogArmSpike=true` is passed. Like
 * arm 1 it emits rather than asserts: the sampler inflates the durations it measures, so arms
 * compare while an absolute figure is not the app's own, and a spike that failed a build would put
 * a measurement in the position of a correctness gate. The two exceptions are the fail-open control
 * above, and the count of trials - a run that measured nothing must not read as a run that measured
 * no difference.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogMosaicArmSpikeTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(CASE_TIMEOUT_SECONDS)

    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: PermissionHistory? = null

    @Before
    fun setUp() {
        GoogleFogCoverageArm.reset()
        permissionHistory = PermissionHistoryStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        originalPermissionHistory = runBlocking { permissionHistory.current() }
        runBlocking {
            permissionHistory.replaceForTesting(
                requireNotNull(originalPermissionHistory).copy(hasSeenIntroduction = true),
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            originalPermissionHistory?.let { history ->
                permissionHistory.replaceForTesting(history)
            }
        }
        // Whatever happened above, the next class in this process must find the shipped surface as
        // well as the shipped profile: arm 2 rides its own switch, and `reset` clears both.
        GoogleFogCoverageArm.reset()
    }

    /**
     * The measurement: a held two-finger spread across an integer zoom boundary, both arms.
     *
     * Two poses. Flat is arm 1's own first pose, so a mosaic number is readable beside a ring
     * number rather than only beside another mosaic number. Tilted is here because a `GroundOverlay`
     * is anchored to the ground rather than blitted to the screen, and whether the SDK draws it
     * correctly under perspective is an assumption of prototype A rather than a measurement of it -
     * `worstInWindowExposedPct` at 45 degrees is what turns that into one.
     */
    @Test
    fun aZoomInIsMeasuredUnderTheBaselineAndUnderTheMosaic() {
        assumeTheSpikeWasRequested()

        val arms = listOf(FogContinuityArm.BASELINE, FogContinuityArm.MOSAIC_OVERLAY)
        val measured = mutableListOf<String>()
        START_CAMERAS.forEach { camera ->
            arms.forEach { arm ->
                // BEFORE the Activity launches: a binding reads its coverage profile AND its
                // overlay installer once, in its field initialisers, so a surface already attached
                // would stay on the old arm.
                arm.install()
                val report = runOneTrial(arm, camera, GestureKind.PINCH_ZOOM_IN) { live ->
                    driveHeldPinch(
                        harness = live,
                        label = "spread/${camera.name}",
                        spans = SPREAD_SPANS,
                        minimumZoomChange = requireNotNull(GestureKind.PINCH_ZOOM_IN.minimumZoomIn),
                    )
                }
                measured += "V03-011-MOSAIC arm=${arm.label} pose=${camera.name} " +
                    report.describe()
                GestureExposureVerdict.emit(report, bare = null)
            }
        }

        emitEvidence(measured)
        assertEveryArmMeasured(measured, START_CAMERAS.size * arms.size)
    }

    /**
     * The same boundary crossed by the gesture users actually zoom in with, both arms.
     *
     * A double tap is granted as exactly one zoom level by the SDK, so it crosses exactly one
     * integer boundary - the smallest step that invalidates every published key, and therefore the
     * cheapest possible defeat of any ring. The held spread above crosses two or three and holds
     * its fingers down while it does; this one is over in an animation the SDK runs on the
     * gesture's behalf, which is why [GestureKind.DOUBLE_TAP_ZOOM_IN] audits a tail past the last
     * UP. Both are reported because a design that helps only the long, held case would be a
     * different claim than the one prototype A makes.
     */
    @Test
    fun aDoubleTapZoomInIsMeasuredUnderBothArms() {
        assumeTheSpikeWasRequested()

        val arms = listOf(FogContinuityArm.BASELINE, FogContinuityArm.MOSAIC_OVERLAY)
        val measured = mutableListOf<String>()
        arms.forEach { arm ->
            arm.install()
            val camera = START_CAMERAS.first()
            val report = runOneTrial(arm, camera, GestureKind.DOUBLE_TAP_ZOOM_IN) { live ->
                driveDoubleTapZoomIn(live, "doubleTap/${camera.name}")
            }
            measured += "V03-011-MOSAIC-TAP arm=${arm.label} pose=${camera.name} " +
                report.describe()
            GestureExposureVerdict.emit(report, bare = null)
        }

        emitEvidence(measured)
        assertEveryArmMeasured(measured, arms.size)
    }

    /**
     * The control, and the one assertion in this class: a zoom-OUT leaves the image, so it must
     * still cover.
     *
     * Prototype A answers the surround question from the mosaic's own ground extent instead of from
     * a published tile key set. That is what removes the cover on a zoom-in, and it is also what
     * could remove it on a gesture that really did leave the fogged ground - the same predicate,
     * answering the same way, in the direction where answering that way puts unexplored basemap on
     * screen. Nothing above would catch it: every metric this spike reports would simply look
     * better.
     *
     * So this case is guarded rather than reported. It fails only when the camera genuinely
     * achieved the zoom-out its kind requires and the cover still never rose, which is a statement
     * about the arm and not about the emulator's gesture lottery: an under-travelled pinch is
     * recorded in the evidence line and passes.
     */
    @Test
    fun aZoomOutLeavesTheMosaicAndMustStillCover() {
        assumeTheSpikeWasRequested()

        val arms = listOf(FogContinuityArm.BASELINE, FogContinuityArm.MOSAIC_OVERLAY)
        val measured = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val required = requireNotNull(GestureKind.PINCH_ZOOM_OUT.minimumZoomOut)
        arms.forEach { arm ->
            arm.install()
            val camera = START_CAMERAS.first()
            val report = runOneTrial(arm, camera, GestureKind.PINCH_ZOOM_OUT) { live ->
                driveHeldPinch(
                    harness = live,
                    label = "pinchOut/${camera.name}",
                    spans = PINCH_OUT_SPANS,
                    minimumZoomChange = required,
                )
            }
            measured += "V03-011-MOSAIC-CONTROL arm=${arm.label} pose=${camera.name} " +
                report.describe()
            GestureExposureVerdict.emit(report, bare = null)

            val zoomedOutBy = -report.zoomDelta
            if (arm == FogContinuityArm.MOSAIC_OVERLAY && zoomedOutBy >= required &&
                !report.coverRose
            ) {
                failures += "COVER_NEVER_ROSE_ON_A_ZOOM_OUT - arm ${arm.label} zoomed out by " +
                    "${"%.3f".format(zoomedOutBy)} levels, which grows the ground the camera sees " +
                    "past the mosaic it is anchored to, and the safety cover never rose. That is " +
                    "the fail-OPEN direction: the surround predicate answered covered for a " +
                    "viewport that had left the fogged ground. " +
                    measured.last()
            }
        }

        emitEvidence(measured)
        assertEveryArmMeasured(measured, arms.size)
        assertTrue(failures.joinToString(" | "), failures.isEmpty())
    }

    // ---- trial body ------------------------------------------------------------------------------

    private fun assumeTheSpikeWasRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "the fog arm spike is opt-in; pass $SPIKE_ARGUMENT=true",
            arguments.getString(SPIKE_ARGUMENT) == "true",
        )
        assumeTrue(
            "the keyed googlePoc runtime is required; this spike measures, it closes nothing",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
    }

    private fun emitEvidence(measured: List<String>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        measured.forEach { line -> SpikeEvidence.emit(context, EVIDENCE_FILE, line) }
    }

    private fun assertEveryArmMeasured(measured: List<String>, expected: Int) {
        assertTrue(
            "every arm must have produced a trial at every pose; a run that measured nothing must " +
                "not be read as a run that measured no difference. Got ${measured.size} of " +
                "$expected",
            measured.size == expected,
        )
    }

    private fun runOneTrial(
        arm: FogContinuityArm,
        startCamera: GestureStartCamera,
        kind: GestureKind,
        drive: (GestureExposureHarness) -> GestureDrive,
    ): GestureTrialReport {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val harness = GestureExposureHarness.attach(scenario)
            val where = "arm=${arm.label} pose=${startCamera.name} kind=${kind.label}"
            try {
                assertTrue(
                    "$where: no online basemap, so there were no real map pixels under the fog to " +
                        "judge. " + harness.diagnostics(),
                    harness.awaitUntil(BASEMAP_TIMEOUT_MILLIS) {
                        harness.basemapLoadState() == GestureExposureHarness.ONLINE_STATE
                    },
                )
                assertTrue(
                    "$where: the launcher never proved a first canonical generation, so a trial " +
                        "here could not tell a start camera that produced nothing from a surface " +
                        "that never installed anything. " + harness.diagnostics(),
                    harness.awaitUntil(FIRST_GENERATION_TIMEOUT_MILLIS) {
                        harness.generation() != null
                    },
                )
                assertTrue(
                    "$where: the entry route's one-shot camera flight was still in the air, so it " +
                        "would have landed inside the audited gesture. " + harness.diagnostics(),
                    harness.awaitQuietCamera(),
                )
                return harness.runTrial(kind, startCamera) { live -> drive(live) }
            } finally {
                harness.close()
            }
        }
    }

    // ---- drivers ---------------------------------------------------------------------------------

    /**
     * One held two-finger pinch, in either direction, retried with a wider opening each time.
     *
     * The direction is read off the spans rather than passed: an opening that ends wider than it
     * started is a zoom IN, and every engagement and travel test below is written in terms of "how
     * far it went the way it was asked to go" so one body serves both cases.
     *
     * **Why the opening widens on a retry.** Google's scale detector has a minimum span, this file
     * does not know what it is on this device, and a spread has to START narrow to have room to
     * travel - the mirror image of the zoom-out case, whose start span is the widest of the three.
     * A first attempt that opens too narrow engages nothing, which is indistinguishable at this
     * level from a map that ignored the gesture. So each attempt opens wider than the last while
     * keeping the span RATIO above what the required zoom change needs, and the accepted attempt
     * records which rung it was on. Reaching the last rung and still failing throws, as before.
     */
    private fun driveHeldPinch(
        harness: GestureExposureHarness,
        label: String,
        spans: List<Pair<Float, Float>>,
        minimumZoomChange: Float,
    ): GestureDrive {
        val origin = harness.viewOrigin()
        val size = harness.viewSize()
        val width = size.first.toFloat()
        val height = size.second.toFloat()
        val centreX = origin[0] + width / 2f
        val y = origin[1] + height * PINCH_POINTER_Y_FRACTION

        fun pair(span: Float): List<FlingGestureInjector.TouchPoint> = listOf(
            FlingGestureInjector.TouchPoint(centreX - span / 2f, y),
            FlingGestureInjector.TouchPoint(centreX + span / 2f, y),
        )

        var downs = 0
        var attempts = 0
        val notes = mutableListOf<String>()
        repeat(GESTURE_ATTEMPTS) { attempt ->
            attempts += 1
            val rung = spans[attempt.coerceAtMost(spans.lastIndex)]
            val startSpan = width * rung.first
            val endSpan = width * rung.second
            val zoomingIn = endSpan > startSpan
            val zoomAtDown = harness.cameraPosition().zoom
            fun travelled(): Float {
                val zoom = harness.cameraPosition().zoom
                return if (zoomingIn) zoom - zoomAtDown else zoomAtDown - zoom
            }

            val openedAt = SystemClock.elapsedRealtime()
            val drive = FlingGestureInjector.withStream { stream ->
                stream.down(pair(startSpan).first())
                downs += 1
                stream.pointerDown(pair(startSpan))
                val engageSpan = startSpan + (endSpan - startSpan) * GESTURE_ENGAGE_TRAVEL
                repeat(GESTURE_ENGAGE_MOVES) { move ->
                    stream.move(
                        pair(
                            startSpan +
                                (engageSpan - startSpan) * (move + 1) / GESTURE_ENGAGE_MOVES,
                        ),
                    )
                    SystemClock.sleep(GESTURE_STEP_MILLIS)
                }
                val engagement = travelled()
                if (engagement < MINIMUM_PINCH_ENGAGEMENT) {
                    stream.liftAll(pair(engageSpan))
                    notes += "rung=${rung.first}->${rung.second} " +
                        "engagement=${"%.3f".format(engagement)}"
                    return@withStream null
                }
                var span = engageSpan
                var step = 0
                while (step < GESTURE_MEASURED_STEPS) {
                    step += 1
                    span = engageSpan + (endSpan - engageSpan) * step / GESTURE_MEASURED_STEPS
                    stream.move(pair(span))
                    SystemClock.sleep(GESTURE_STEP_MILLIS)
                    // Travel until the requirement is met with margin, then stop - the same rule
                    // the parity gate's pinch uses, and for the same reason: a fixed span ratio
                    // would be a guess at the SDK's span-to-zoom mapping in both directions.
                    if (step % GESTURE_TRAVEL_CHECK_EVERY == 0 &&
                        travelled() >= minimumZoomChange + ZOOM_TRAVEL_MARGIN
                    ) {
                        break
                    }
                }
                // Held, so the audited window contains frames of a stopped camera at the new zoom
                // rather than only frames of one still moving towards it.
                SystemClock.sleep(GESTURE_HOLD_MILLIS)
                stream.liftAll(pair(span))
                GestureDrive(
                    note = "$label ${if (zoomingIn) "spread" else "pinch"} " +
                        "spanPx=${startSpan.toInt()}->${span.toInt()} moves=$step " +
                        "travelled=${"%.3f".format(travelled())}",
                    downAtMillis = openedAt,
                    upAtMillis = SystemClock.elapsedRealtime(),
                    injectedDownCount = downs,
                    attempts = attempts,
                )
            }
            if (drive != null) return drive
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(
            "$label: the injected stream never engaged the SDK's scale detector in $attempts " +
                "attempts ($downs injected ACTION_DOWNs): ${notes.joinToString(" ")} " +
                harness.diagnostics(),
        )
    }

    /**
     * Two taps at one point, then a wait for the SDK's own zoom animation to stop.
     *
     * The camera movement here belongs to the animation rather than to the fingers, so the drive
     * ends at the second UP and [GestureKind.DOUBLE_TAP_ZOOM_IN]'s animation tail is what puts the
     * moving frames inside the audited window.
     */
    private fun driveDoubleTapZoomIn(
        harness: GestureExposureHarness,
        label: String,
    ): GestureDrive {
        val origin = harness.viewOrigin()
        val size = harness.viewSize()
        val x = origin[0] + size.first.toFloat() / 2f
        val y = origin[1] + size.second.toFloat() * TAP_POINTER_Y_FRACTION

        var downs = 0
        var attempts = 0
        val notes = mutableListOf<String>()
        repeat(GESTURE_ATTEMPTS) {
            attempts += 1
            val zoomAtTap = harness.cameraPosition().zoom
            val openedAt = SystemClock.elapsedRealtime()
            var liftedAt = openedAt
            repeat(2) { tap ->
                FlingGestureInjector.withStream { stream ->
                    stream.down(FlingGestureInjector.TouchPoint(x, y))
                    downs += 1
                    SystemClock.sleep(TAP_DURATION_MILLIS)
                    stream.up(FlingGestureInjector.TouchPoint(x, y))
                    liftedAt = SystemClock.elapsedRealtime()
                }
                if (tap == 0) SystemClock.sleep(DOUBLE_TAP_GAP_MILLIS)
            }
            val travelled = awaitTapZoomIn(harness, zoomAtTap)
            if (travelled >= MINIMUM_TAP_ZOOM_ENGAGEMENT) {
                return GestureDrive(
                    note = "$label travelled=${"%.3f".format(travelled)}",
                    downAtMillis = openedAt,
                    upAtMillis = liftedAt,
                    injectedDownCount = downs,
                    attempts = attempts,
                )
            }
            notes += "doubleTapTravel=${"%.3f".format(travelled)}"
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(
            "$label: the injected stream never engaged the SDK's double-tap zoom in $attempts " +
                "attempts ($downs injected ACTION_DOWNs): ${notes.joinToString(" ")} " +
                harness.diagnostics(),
        )
    }

    /** The largest zoom-in the animation reached, once the camera has stopped changing. */
    private fun awaitTapZoomIn(harness: GestureExposureHarness, zoomAtTap: Float): Float {
        var travelled = 0f
        var last = zoomAtTap
        var stable = 0
        val deadline = SystemClock.elapsedRealtime() + TAP_ANIMATION_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline && stable < TAP_STABLE_POLLS) {
            val zoom = harness.cameraPosition().zoom
            travelled = maxOf(travelled, zoom - zoomAtTap)
            stable = if (
                travelled >= MINIMUM_TAP_ZOOM_ENGAGEMENT &&
                abs(zoom - last) < TAP_ZOOM_STABLE_EPSILON
            ) {
                stable + 1
            } else {
                0
            }
            last = zoom
            SystemClock.sleep(TAP_POLL_MILLIS)
        }
        return travelled
    }

    private companion object {
        const val SPIKE_ARGUMENT = "trailveilFogArmSpike"

        /** Arm 1's file, so both arms are read side by side rather than in two streams. */
        const val EVIDENCE_FILE = "v03-011-arm-spike.txt"

        /** Two poses x two arms, each a full launch plus a settle plus a trial. */
        const val CASE_TIMEOUT_SECONDS = 900L
        const val BASEMAP_TIMEOUT_MILLIS = 60_000L
        const val FIRST_GENERATION_TIMEOUT_MILLIS = 45_000L

        /**
         * Flat is arm 1's own first pose, so the two arms' numbers are comparable directly.
         *
         * Tilted holds place and zoom and changes only the pose, which is what makes it a
         * one-variable step - and it is the pose that tests prototype A's one geometric assumption.
         * The mosaic is a flat image pinned to a ground rectangle; the fog it replaces is a stack
         * of tiles the SDK draws in the same ground plane. If a `GroundOverlay` is drawn under
         * perspective the way tiles are, a tilted trial reads like a flat one; if it is not, this
         * is where the difference shows up as exposed basemap rather than as a slower cover.
         *
         * Open ocean throughout, and nowhere near any fixture the suite writes points at, so
         * "unexplored" holds and there are no labels to argue with.
         */
        val START_CAMERAS = listOf(
            GestureStartCamera(
                name = "flatExplorationZoom",
                latitude = -25.5,
                longitude = -130.5,
                zoom = 16.0f,
            ),
            GestureStartCamera(
                name = "tiltedExplorationZoom",
                latitude = -25.5,
                longitude = -130.5,
                zoom = 16.0f,
                tilt = 45.0f,
            ),
        )

        /**
         * Opening and closing spans for the SPREAD, in fractions of the map's width, widest travel
         * first.
         *
         * Every rung keeps a span ratio above `2^(1.5 + 0.35)` = 3.61, which is what the kind's
         * 1.5 levels plus the travel margin costs, so a later rung buys engagement without
         * quietly buying a gesture too small to cross the boundary being measured. The closing
         * spans stop short of the full width for the reason the parity gate's opening span does:
         * a pointer at the very edge is in the system's back-gesture strip.
         */
        val SPREAD_SPANS = listOf(
            0.16f to 0.86f,
            0.20f to 0.86f,
            0.24f to 0.90f,
        )

        /** The control's spans: the parity gate's own proven pinch, unchanged. */
        val PINCH_OUT_SPANS = listOf(0.76f to 0.06f)

        /**
         * Injection geometry and cadence, taken from `GoogleGestureExposureTest` because those are
         * the values this repository has already made deterministic on an emulator.
         */
        const val PINCH_POINTER_Y_FRACTION = 0.52f
        const val TAP_POINTER_Y_FRACTION = 0.52f
        const val GESTURE_ATTEMPTS = 4
        const val GESTURE_ENGAGE_MOVES = 8
        const val GESTURE_ENGAGE_TRAVEL = 0.30f
        const val GESTURE_MEASURED_STEPS = 30
        const val GESTURE_STEP_MILLIS = 16L
        const val GESTURE_TRAVEL_CHECK_EVERY = 5
        const val GESTURE_RETRY_SETTLE_MILLIS = 2_500L
        const val GESTURE_HOLD_MILLIS = 600L
        const val MINIMUM_PINCH_ENGAGEMENT = 0.03f
        const val ZOOM_TRAVEL_MARGIN = 0.35f

        const val TAP_DURATION_MILLIS = 60L
        const val DOUBLE_TAP_GAP_MILLIS = 80L
        const val TAP_ANIMATION_TIMEOUT_MILLIS = 5_000L
        const val TAP_POLL_MILLIS = 50L
        const val TAP_STABLE_POLLS = 3
        const val TAP_ZOOM_STABLE_EPSILON = 0.001f
        const val MINIMUM_TAP_ZOOM_ENGAGEMENT = 0.2f
    }
}
