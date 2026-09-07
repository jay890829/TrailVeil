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
 * `V03-011` arm 1, measured: the same pan under `BASELINE` and under `PADDING_RING`.
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
        FogContinuityArm.entries
            .filter { arm -> arm != FogContinuityArm.MOSAIC_OVERLAY }
            .forEach { arm ->
                // BEFORE the Activity launches: a binding reads its coverage profile once, in its
                // field initialisers, so a surface already attached would stay on the old arm.
                arm.install()
                val report = runOneTrial(arm)
                measured += "V03-011-ARM arm=${arm.label} ${report.describe()}"
                // The standard line too, into the standard file, so an arm trial is readable next
                // to the parity gate's own trials rather than only in this spike's own stream.
                GestureExposureVerdict.emit(report, bare = null)
            }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        measured.forEach { line -> SpikeEvidence.emit(context, EVIDENCE_FILE, line) }
        assertTrue(
            "both arms must have produced a trial; a run that measured nothing must not be read " +
                "as a run that measured no difference. Got ${measured.size}",
            measured.size == 2,
        )
    }

    private fun runOneTrial(arm: FogContinuityArm): GestureTrialReport {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val harness = GestureExposureHarness.attach(scenario)
            try {
                assertTrue(
                    "arm=${arm.label}: no online basemap, so there were no real map pixels under " +
                        "the fog to judge. " + harness.diagnostics(),
                    harness.awaitUntil(BASEMAP_TIMEOUT_MILLIS) {
                        harness.basemapLoadState() == GestureExposureHarness.ONLINE_STATE
                    },
                )
                assertTrue(
                    "arm=${arm.label}: the launcher never proved a first canonical generation, so " +
                        "a trial here could not tell a start camera that produced nothing from a " +
                        "surface that never installed anything. " + harness.diagnostics(),
                    harness.awaitUntil(FIRST_GENERATION_TIMEOUT_MILLIS) {
                        harness.generation() != null
                    },
                )
                assertTrue(
                    "arm=${arm.label}: the entry route's one-shot camera flight was still in the " +
                        "air, so it would have landed inside the audited gesture. " +
                        harness.diagnostics(),
                    harness.awaitQuietCamera(),
                )
                return harness.runTrial(GestureKind.PAN, START_CAMERA) { live -> drivePan(live) }
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
    private fun drivePan(harness: GestureExposureHarness): GestureDrive {
        val origin = harness.viewOrigin()
        val size = harness.viewSize()
        val width = size.first.toFloat()
        val height = size.second.toFloat()
        val y = origin[1] + height * DRAG_Y_FRACTION
        val startX = origin[0] + width * DRAG_START_X_FRACTION
        val endX = origin[0] + width * DRAG_END_X_FRACTION

        var downs = 0
        var attempts = 0
        repeat(GESTURE_ATTEMPTS) {
            attempts += 1
            val before = harness.cameraPosition()
            val openedAt = SystemClock.elapsedRealtime()
            var liftedAt = openedAt
            FlingGestureInjector.withStream { stream ->
                stream.down(FlingGestureInjector.TouchPoint(startX, y))
                downs += 1
                repeat(DRAG_STEPS) { step ->
                    val progress = (step + 1).toFloat() / DRAG_STEPS
                    stream.move(
                        listOf(
                            FlingGestureInjector.TouchPoint(
                                startX + (endX - startX) * progress,
                                y,
                            ),
                        ),
                    )
                    SystemClock.sleep(DRAG_STEP_MILLIS)
                }
                // Held still, so the SDK sees a stopped finger and starts no fling.
                SystemClock.sleep(DRAG_HOLD_BEFORE_LIFT_MILLIS)
                stream.up(FlingGestureInjector.TouchPoint(endX, y))
                liftedAt = SystemClock.elapsedRealtime()
            }
            val after = harness.cameraPosition()
            val moved = before.target != after.target
            if (moved) {
                return GestureDrive(
                    note = "oneFingerPan steps=$DRAG_STEPS holdMs=$DRAG_HOLD_BEFORE_LIFT_MILLIS",
                    downAtMillis = openedAt,
                    upAtMillis = liftedAt,
                    injectedDownCount = downs,
                    attempts = attempts,
                )
            }
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(
            "the map never moved for $attempts injected one-finger drags. " +
                harness.diagnostics(),
        )
    }

    private companion object {
        const val SPIKE_ARGUMENT = "trailveilFogArmSpike"
        const val EVIDENCE_FILE = "v03-011-arm-spike.txt"

        /** Two arms, each a full launch plus a settle plus a trial. */
        const val CASE_TIMEOUT_SECONDS = 600L
        const val BASEMAP_TIMEOUT_MILLIS = 60_000L
        const val FIRST_GENERATION_TIMEOUT_MILLIS = 45_000L
        const val GESTURE_ATTEMPTS = 4
        const val GESTURE_RETRY_SETTLE_MILLIS = 1_500L

        /**
         * Right to left across most of the map, which is several tiles at exploration zoom - the
         * kind's own `minimumPanTiles` is what checks it actually was.
         */
        const val DRAG_START_X_FRACTION = 0.82f
        const val DRAG_END_X_FRACTION = 0.14f
        const val DRAG_Y_FRACTION = 0.45f
        const val DRAG_STEPS = 16
        const val DRAG_STEP_MILLIS = 22L
        const val DRAG_HOLD_BEFORE_LIFT_MILLIS = 260L

        /** Open ocean at exploration zoom: no labels to argue with, and the parity gate's own. */
        val START_CAMERA = GestureStartCamera(
            name = "openOceanExplorationZoom",
            latitude = -25.5,
            longitude = -130.5,
            zoom = 16.0f,
        )
    }
}
