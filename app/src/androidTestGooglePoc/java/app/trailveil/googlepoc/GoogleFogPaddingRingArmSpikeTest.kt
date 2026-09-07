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
 * `V03-011` arm 1, measured: the same pan under `BASELINE` and under `PADDING_RING`, at three
 * poses.
 *
 * **Opt-in, and not a gate.** It is skipped unless `trailveilFogArmSpike=true` is passed, and it
 * asserts almost nothing: the numbers are emitted for the evidence file. Two reasons, both recorded
 * in [GestureTrialReport.cost]. The pixel sampler inflates the frame durations it measures, so arms
 * compare while an absolute figure is not the app's own; and a spike that failed a build would put
 * a measurement in the position of a correctness gate. The one thing it does assert is that each
 * arm produced a trial at all - a run that measured nothing must not read as a run that measured no
 * difference.
 *
 * **Why a pan and not one of the six kinds the parity gate drives.** `FogPaddingRingSurroundTest`
 * establishes that a coverage key carries the zoom it was planned at, so a ring of any size is
 * defeated by one integer zoom step; four of those six kinds are zoom gestures and the fifth turns
 * the camera. Running them here would produce numbers that are structurally guaranteed to show no
 * benefit and would read as "prefetch does not work". See [GestureKind.PAN].
 *
 * **Why a separate class.** `GoogleGestureExposureTest` is the sole closure of eight gesture parity
 * rows against the SHIPPED fog configuration. Driving it under an arm would re-point that
 * certification at a measurement fixture; that class now resets the profile on the way in as well
 * as out, and this one owns the arm switching instead.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogPaddingRingArmSpikeTest {

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
        // Whatever happened above, the next class in this process must find the shipped profile.
        GoogleFogCoverageArm.reset()
    }

    @Test
    fun aPanIsMeasuredUnderTheBaselineAndUnderThePaddingRing() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "the fog arm spike is opt-in; pass $SPIKE_ARGUMENT=true",
            arguments.getString(SPIKE_ARGUMENT) == "true",
        )
        assumeTrue(
            "the keyed googlePoc runtime is required; this spike measures, it closes nothing",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )

        val measured = mutableListOf<String>()
        // Named rather than derived from `entries`: this case is the recorded pose ladder, and a
        // new arm must not silently re-shape a result other documents quote.
        val arms = listOf(FogContinuityArm.BASELINE, FogContinuityArm.PADDING_RING)
        START_CAMERAS.forEach { camera ->
            arms.forEach { arm ->
                // BEFORE the Activity launches: a binding reads its coverage profile once, in its
                // field initialisers, so a surface already attached would stay on the old arm.
                arm.install()
                val report = runOneTrial(arm, camera, SHORT_DRAG)
                measured += "V03-011-ARM arm=${arm.label} pose=${camera.name} ${report.describe()}"
                // The standard line too, into the standard file, so an arm trial is readable next
                // to the parity gate's own trials rather than only in this spike's own stream.
                GestureExposureVerdict.emit(report, bare = null)
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        measured.forEach { line -> SpikeEvidence.emit(context, EVIDENCE_FILE, line) }
        val expected = START_CAMERAS.size * arms.size
        assertTrue(
            "every arm must have produced a trial at every pose; a run that measured nothing must " +
                "not be read as a run that measured no difference. Got ${measured.size} of " +
                "$expected",
            measured.size == expected,
        )
    }

    /**
     * One straight one-finger drag, in fractions of the map view.
     *
     * A fraction rather than a distance because the point of varying it is to vary how many TILES
     * the camera travels, and that is what a fraction of the viewport buys - the same fraction is a
     * longer pan in tiles on a larger dp viewport, which is the whole of section 14b.
     */
    private data class PanDrag(
        val label: String,
        val startXFraction: Float,
        val endXFraction: Float,
        val startYFraction: Float,
        val endYFraction: Float,
        /**
         * How many times the same drag is injected inside ONE audited window.
         *
         * A ring is re-published around the new camera after each move, but only once a new
         * generation has rendered - which the 420 dpi trials measured at 234 ms of render plus 94 ms
         * of publish for 35 keys. A second drag arriving inside that gap meets the OLD ring. One
         * drag cannot defeat `ring(1)` on a phone-shaped viewport because the screen is too small to
         * ask for more than the ring holds; two drags in quick succession can ask for twice as much,
         * and that is a thing users do.
         */
        val repeats: Int = 1,
        /** Gap between repeats. Short on purpose: this is a flick-flick, not two decisions. */
        val gapMillis: Long = 0L,
    )

    /**
     * `p` as the variable, on a phone-shaped viewport: does a WIDER ring hold a LONGER pan?
     *
     * Section 14b established `ring(p)` helps only while the pan is under about `p` tiles, but it
     * did so by shrinking the screen density, which moves the viewport and the gesture together.
     * This case moves neither: same device, same pose, same density, one longer drag, three rings.
     * What it measures is the CAPACITY of a ring in tiles of pan, bracketed by two drag lengths,
     * which is what separates "a ring is a fix you can size" from "a ring happened to fit one
     * trial". See [LONG_DRAGS] for why one drag was not enough.
     */
    @Test
    fun aLongerPanIsHeldOnlyByAWiderRing() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "the fog arm spike is opt-in; pass $SPIKE_ARGUMENT=true",
            arguments.getString(SPIKE_ARGUMENT) == "true",
        )
        assumeTrue(
            "the keyed googlePoc runtime is required; this spike measures, it closes nothing",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )

        val arms = listOf(
            FogContinuityArm.BASELINE,
            FogContinuityArm.PADDING_RING,
            FogContinuityArm.PADDING_RING_2,
        )
        val measured = mutableListOf<String>()
        LONG_DRAGS.forEach { drag ->
            arms.forEach { arm ->
                arm.install()
                val report = runOneTrial(arm, START_CAMERAS.first(), drag)
                measured += "V03-011-RING-WIDTH arm=${arm.label} drag=${drag.label} " +
                    report.describe()
                GestureExposureVerdict.emit(report, bare = null)
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        measured.forEach { line -> SpikeEvidence.emit(context, EVIDENCE_FILE, line) }
        val expected = LONG_DRAGS.size * arms.size
        assertTrue(
            "every ring width must have produced a trial at every drag; a run that measured " +
                "nothing must not be read as a run that measured no difference. Got " +
                "${measured.size} of $expected",
            measured.size == expected,
        )
    }

    /**
     * The edge a single drag cannot reach: two flicks before the ring has been re-published.
     *
     * Section 14d's conclusion is a statement about the SCREEN - one finger cannot ask for more
     * ground than the ring holds, because the screen is only about 1.6 tiles wide. Two drags inside
     * one audited window can ask for twice as much, and the fog only re-publishes its ring after a
     * new generation renders (measured at 234 ms render plus 94 ms publish for 35 keys). So this is
     * the realistic way a user defeats `ring(1)`, and it is worth knowing whether `ring(2)` - which
     * bought nothing against one drag - earns its 63 keys here.
     *
     * Reported, never asserted, like everything else in this spike.
     */
    @Test
    fun aSecondFlickBeforeTheRingIsRepublished() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "the fog arm spike is opt-in; pass $SPIKE_ARGUMENT=true",
            arguments.getString(SPIKE_ARGUMENT) == "true",
        )
        assumeTrue(
            "the keyed googlePoc runtime is required; this spike measures, it closes nothing",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )

        val arms = listOf(
            FogContinuityArm.BASELINE,
            FogContinuityArm.PADDING_RING,
            FogContinuityArm.PADDING_RING_2,
        )
        val measured = mutableListOf<String>()
        arms.forEach { arm ->
            arm.install()
            val report = runOneTrial(arm, START_CAMERAS.first(), DOUBLE_FLICK)
            measured += "V03-011-DOUBLE-FLICK arm=${arm.label} " + report.describe()
            GestureExposureVerdict.emit(report, bare = null)
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        measured.forEach { line -> SpikeEvidence.emit(context, EVIDENCE_FILE, line) }
        assertTrue(
            "every ring width must have produced a trial; a run that measured nothing must not be " +
                "read as a run that measured no difference. Got ${measured.size} of ${arms.size}",
            measured.size == arms.size,
        )
    }

    private fun runOneTrial(
        arm: FogContinuityArm,
        startCamera: GestureStartCamera,
        drag: PanDrag,
    ): GestureTrialReport {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val harness = GestureExposureHarness.attach(scenario)
            try {
                assertTrue(
                    "arm=${arm.label} pose=${startCamera.name}: no online basemap, so there were no real map pixels under " +
                        "the fog to judge. " + harness.diagnostics(),
                    harness.awaitUntil(BASEMAP_TIMEOUT_MILLIS) {
                        harness.basemapLoadState() == GestureExposureHarness.ONLINE_STATE
                    },
                )
                assertTrue(
                    "arm=${arm.label} pose=${startCamera.name}: the launcher never proved a first canonical generation, so " +
                        "a trial here could not tell a start camera that produced nothing from a " +
                        "surface that never installed anything. " + harness.diagnostics(),
                    harness.awaitUntil(FIRST_GENERATION_TIMEOUT_MILLIS) {
                        harness.generation() != null
                    },
                )
                assertTrue(
                    "arm=${arm.label} pose=${startCamera.name}: the entry route's one-shot camera flight was still in the " +
                        "air, so it would have landed inside the audited gesture. " +
                        harness.diagnostics(),
                    harness.awaitQuietCamera(),
                )
                return harness.runTrial(GestureKind.PAN, startCamera) { live ->
                    drivePan(live, drag)
                }
            } finally {
                harness.close()
            }
        }
    }

    /**
     * One finger, one slow drag, and a deliberate hold before the lift.
     *
     * The hold is the point: Google's map applies fling inertia to a drag that ends while moving,
     * and an inertial fling is a different gesture with a different audited window. Stopping first
     * keeps this a pan, which is what a ring can help with.
     *
     * Retried like every other driver here, because gesture injection on an emulator is a lottery;
     * the trial's own `GESTURE_SWALLOWED` clause is what decides whether the camera really moved
     * the tile this kind requires.
     */
    private fun format(value: Float): String = "%.3f".format(value)

    private fun drivePan(harness: GestureExposureHarness, drag: PanDrag): GestureDrive {
        val origin = harness.viewOrigin()
        val size = harness.viewSize()
        val width = size.first.toFloat()
        val height = size.second.toFloat()
        // Keep both ends out of the platform's own gesture bands. A drag that begins or ends
        // inside them is consumed as a back gesture and the map never moves - which is exactly what
        // this test reported on the designated phone before this clamp existed. The margin is added
        // to the reserved inset rather than used instead of it, because the exclusion is a target
        // area and landing on its exact boundary is not a design anyone should rely on.
        val (gestureLeft, gestureRight) = harness.systemGestureEdges()
        val safeLeft = origin[0] + gestureLeft + GESTURE_EDGE_MARGIN_PX
        val safeRight = origin[0] + width - gestureRight - GESTURE_EDGE_MARGIN_PX
        fun clampX(raw: Float): Float =
            if (safeRight <= safeLeft) raw else raw.coerceIn(safeLeft, safeRight)
        val startX = clampX(origin[0] + width * drag.startXFraction)
        val endX = clampX(origin[0] + width * drag.endXFraction)
        val startY = origin[1] + height * drag.startYFraction
        val endY = origin[1] + height * drag.endYFraction

        var downs = 0
        var attempts = 0
        repeat(GESTURE_ATTEMPTS) {
            attempts += 1
            val before = harness.cameraPosition()
            val openedAt = SystemClock.elapsedRealtime()
            var liftedAt = openedAt
            repeat(drag.repeats) { repeatIndex ->
                if (repeatIndex > 0) SystemClock.sleep(drag.gapMillis)
                FlingGestureInjector.withStream { stream ->
                    stream.down(FlingGestureInjector.TouchPoint(startX, startY))
                    downs += 1
                    repeat(DRAG_STEPS) { step ->
                        val progress = (step + 1).toFloat() / DRAG_STEPS
                        stream.move(
                            listOf(
                                FlingGestureInjector.TouchPoint(
                                    startX + (endX - startX) * progress,
                                    startY + (endY - startY) * progress,
                                ),
                            ),
                        )
                        SystemClock.sleep(DRAG_STEP_MILLIS)
                    }
                    // Held still, so the SDK sees a stopped finger and starts no fling.
                    SystemClock.sleep(DRAG_HOLD_BEFORE_LIFT_MILLIS)
                    stream.up(FlingGestureInjector.TouchPoint(endX, endY))
                    liftedAt = SystemClock.elapsedRealtime()
                }
            }
            val after = harness.cameraPosition()
            val moved = before.target != after.target
            if (moved) {
                return GestureDrive(
                    note = "oneFingerPan drag=${drag.label} repeats=${drag.repeats} " +
                        "gapMs=${drag.gapMillis} steps=$DRAG_STEPS " +
                        "holdMs=$DRAG_HOLD_BEFORE_LIFT_MILLIS " +
                        // What was actually injected, which is not always what the drag asked for:
                        // the endpoints are clamped out of the platform's gesture bands, and a
                        // reader comparing pan lengths across devices needs to see that.
                        "askedX=${format(drag.startXFraction)}->${format(drag.endXFraction)} " +
                        "injectedX=${format((startX - origin[0]) / width)}->" +
                        "${format((endX - origin[0]) / width)} " +
                        "gestureEdgesPx=$gestureLeft/$gestureRight",
                    downAtMillis = openedAt,
                    upAtMillis = liftedAt,
                    injectedDownCount = downs,
                    attempts = attempts,
                )
            }
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(
            "the map never moved for $attempts injected one-finger drag rounds. " +
                harness.diagnostics(),
        )
    }

    private companion object {
        /**
         * Extra pixels beyond the reserved system-gesture inset, on each side.
         *
         * Small enough not to change what a pan measures where nothing is reserved - it is added to
         * a zero inset and then clamped against endpoints already inside the view - and large
         * enough that a drag does not begin on the exact pixel where the platform's exclusion area
         * ends.
         */
        const val GESTURE_EDGE_MARGIN_PX = 24f

        const val SPIKE_ARGUMENT = "trailveilFogArmSpike"
        const val EVIDENCE_FILE = "v03-011-arm-spike.txt"

        /** Three poses x two arms, each a full launch plus a settle plus a trial. */
        const val CASE_TIMEOUT_SECONDS = 900L
        const val BASEMAP_TIMEOUT_MILLIS = 60_000L
        const val FIRST_GENERATION_TIMEOUT_MILLIS = 45_000L
        const val GESTURE_ATTEMPTS = 4
        const val GESTURE_RETRY_SETTLE_MILLIS = 1_500L

        /**
         * Right to left across most of the map. Measured at 1.06 tiles on a 411 dp wide viewport -
         * the kind's own `minimumPanTiles` is what checks it cleared one at all.
         */
        val SHORT_DRAG = PanDrag(
            label = "shortPan",
            startXFraction = 0.82f,
            endXFraction = 0.14f,
            startYFraction = 0.45f,
            endYFraction = 0.45f,
        )

        /**
         * Two diagonal drags, to bracket where one ring stops holding.
         *
         * Screen-space is the only budget a one-finger drag has: the widest horizontal sweep on a
         * 411 dp viewport is about 1.4 tiles, so height has to be borrowed to get any further. The
         * first version of this case used one drag at 1.63 tiles expecting `ring(1)` to fail it,
         * and `ring(1)` held it - because the published rectangle is TILE-ALIGNED, so the plan
         * already reaches past the visible viewport by whatever the alignment leaves over, and a
         * ring of `p` buys `p` PLUS that slack. On this viewport the visible span is 1.61 tiles
         * inside a 3-tile box, so there is about 1.4 tiles of slack to share between the two sides.
         *
         * Both drags are therefore kept: 1.63 tiles is the measured point where one ring still
         * holds, and 2.14 is where the same model says it cannot. Reporting both is what makes the
         * capacity a bracket rather than a single pass that could have been luck.
         */
        val LONG_DRAGS = listOf(
            PanDrag(
                label = "diagonalPan1_6",
                startXFraction = 0.94f,
                endXFraction = 0.06f,
                startYFraction = 0.30f,
                endYFraction = 0.55f,
            ),
            PanDrag(
                label = "diagonalPan2_1",
                startXFraction = 0.94f,
                endXFraction = 0.06f,
                startYFraction = 0.22f,
                endYFraction = 0.67f,
            ),
            /**
             * The widest PURE horizontal drag the screen can produce, edge to edge.
             *
             * The two diagonals above did not defeat one ring, and the reason is that the surround
             * predicate is rectangle containment: it is left when an AXIS runs out, not when a
             * hypotenuse does, so a diagonal buys distance the ring never has to hold. This drag
             * spends the whole screen on one axis, which is the most a single finger can ask of the
             * x term. If one ring holds THIS, then on a viewport this size no one-finger drag can
             * defeat it - which is a statement about the screen, not about the gesture.
             */
            PanDrag(
                label = "fullWidthPan",
                startXFraction = 0.97f,
                endXFraction = 0.03f,
                startYFraction = 0.45f,
                endYFraction = 0.45f,
            ),
        )

        /**
         * The same full-width drag, twice, with 120 ms between - a flick-flick rather than two
         * decisions, and well inside the 234 + 94 ms a 35-key generation takes to render and
         * publish. Asks for about twice one screen of ground, which is past what one ring holds.
         */
        val DOUBLE_FLICK = PanDrag(
            label = "doubleFullWidthFlick",
            startXFraction = 0.97f,
            endXFraction = 0.03f,
            startYFraction = 0.45f,
            endYFraction = 0.45f,
            repeats = 2,
            gapMillis = 120L,
        )

        const val DRAG_STEPS = 16
        const val DRAG_STEP_MILLIS = 22L
        const val DRAG_HOLD_BEFORE_LIFT_MILLIS = 260L

        /**
         * Two poses, because the ring's cost is a property of the VIEWPORT, not of the app.
         *
         * Flat at exploration zoom is the parity gate's own camera and plans a small mosaic, where
         * a ring is nearly free. Measuring only that would report that a ring is cheap and would be
         * describing the emulator's viewport rather than the arm.
         *
         * The other two say how the rectangle grows. `tilted` holds place and zoom and only changes
         * the pose, so it is a clean one-variable step; `steepWideAngle` is the reconstruction of
         * the pose that produced the 240-of-256 completion, and is the one the ring has to be
         * affordable at. Open ocean throughout: no labels to argue with.
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
            GestureStartCamera(
                name = "steepWideAngle",
                latitude = -25.5,
                longitude = -130.5,
                zoom = WIDE_ZOOM,
                tilt = WIDE_TILT,
            ),
        )

        /**
         * The pose that actually produces a wide rectangle, reconstructed from `V02-012`.
         *
         * That task's three AVD runs of the shove-then-held-pinch trial reported
         * `tiltDelta=61.94 zoomDelta=-1.871 installedKeys=240` - 240 published masks against the
         * hard 256, which is where `V03-011`'s "the ring does not fit" claim comes from. `tilt=45`
         * is nowhere near it: it moves a 3x5 mosaic to 3x7. The horizon is what explodes the
         * floor-zoom bounding box, and it arrives near the SDK's tilt ceiling rather than halfway
         * up. Stated as constants so a reader can see this pose is a reconstruction of a recorded
         * measurement rather than a number picked to be large.
         *
         * **The zoom is not free, and this is measured rather than assumed.** Google's map clamps
         * tilt as a function of zoom. A first attempt at `zoom=14.1` was granted **46.5 degrees**
         * for a requested 61.9 - caught by `settleAtStartCamera`'s pose assertion, which exists
         * because of this run - so the wide pose has to be asked for where the SDK will grant it.
         * `V02-012`'s own trial reached that tilt after zooming OUT by 1.871, so its camera was
         * higher-zoom still.
         */
        const val WIDE_TILT = 61.9f
        const val WIDE_ZOOM = 16.1f
    }
}
