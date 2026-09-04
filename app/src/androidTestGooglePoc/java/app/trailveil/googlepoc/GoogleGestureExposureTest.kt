package app.trailveil.googlepoc

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.MainActivity
import app.trailveil.feature.recording.PermissionHistory
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.map.GoogleMapSurfaceTestHooks
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * `V02-007`: the Google reading of the MapLibre gesture-exposure cases, driven by REAL injected
 * pointer streams on the REAL hosted production map (`MainActivity`).
 *
 * Until this class existed, [GestureExposureHarness] had no caller at all, so the eight gesture GAP
 * rows of `V02-007-parity-inventory.md` and the fourteen PARTIAL rows that hang off them were
 * unproven with a green suite: `GoogleInstrumentationManifestDriftTest` compares declared manifest
 * lines against discovered `@Test` methods, and a file with no `@Test` satisfies it by absence.
 *
 * **What every case asserts** (the clauses live in [GestureExposureVerdict.failuresFor], which
 * returns every failure rather than the first, so one red names every camera that failed):
 *
 * 1. **No frame shows bare basemap over unexplored ground.** Each sampled frame is either the
 *    opaque `GoogleFogSafetyOverlay` - corroborated in real screen pixels, `mismatched == 0`, at
 *    the same +-2 per-channel window `GoogleProductionLauncherMapHostTest.assertFogCoverPixels`
 *    uses - or is judged against a settled floor measured at the same camera through the same
 *    capture channel. A frame that produced no capture inside the gesture window FAILS; it is
 *    never filtered away.
 * 2. **The blanking is bounded ABOVE**, which is the Google inversion of MapLibre's "the cover was
 *    never raised": Google raises the cover on purpose the moment a moving camera leaves the
 *    published surround (`FogOverlaySurfaceCoordinator.onCameraMoveFrame`). Both the sampled
 *    covered run and the surface's own published `map_fog_last_cover_interval_ms` are bounded by
 *    [GestureExposureVerdict.COVER_INTERVAL_BOUND_MILLIS], and the published interval must have
 *    CHANGED first, so a retained measurement of an earlier cover can never satisfy the bound.
 * 3. **The gesture really happened.** Per-kind zoom/tilt/bearing minima ported from the MapLibre
 *    originals, plus `map_touch_down_count` growing by exactly the number of `ACTION_DOWN`s this
 *    driver injected - including the ones belonging to rejected engagement attempts. Without both,
 *    a gesture the SDK swallowed would pass on the strength of the post-settle frames alone.
 * 4. **The oracle is shown able to see a leak in this run**, at each trial's own start camera, by
 *    replaying that camera on the same `GoogleHostedMapSurface` with the fog detached
 *    ([GestureExposureBareReference]). SP5's falsify mode, made mandatory instead of opt-in.
 *
 * **Two structural decisions.**
 *
 * The key gate is `assertTrue`, not `assumeTrue`. A keyless run of a class that is the sole closure
 * of eight parity rows must be loud: a silent skip is indistinguishable from proof, which is
 * exactly the failure mode the hosted keyless branch job would otherwise hide.
 *
 * `gestureCoverSuppressedForTesting` is not used and cannot be: it exists only on
 * `GoogleMapsPocActivity`, and every trial here attaches to `MainActivity`. The cover under audit
 * is the shipping one.
 *
 * **Geometry rule.** Only `ACTION_DOWN` and `ACTION_POINTER_DOWN` are constrained to the band the
 * entry screen's own controls cannot reach. `GestureOwningGoogleMapView.dispatchTouchEvent` calls
 * `requestDisallowInterceptTouchEvent(true)` on `ACTION_DOWN`, so once the stream is claimed its
 * MOVEs may travel anywhere on the screen; a NEW pointer landing on a Compose control can still be
 * split away to it, which is why POINTER_DOWN is constrained too.
 *
 * **Engagement, not assumption.** An injected multi-pointer stream does not always reach the SDK's
 * scale/rotate/shove detector. Every driver therefore travels a short unmeasured engagement first
 * and CHECKS that the camera responded; an attempt that did not engage is lifted cleanly and made
 * again, and every attempt's `ACTION_DOWN` is still counted into the touch-down assertion. This is
 * the discipline `MapSurfaceTest.pinchOnce` uses for the same reason.
 */
@RunWith(AndroidJUnit4::class)
class GoogleGestureExposureTest {
    /**
     * A hang here would wedge the single-emulator shard, which is what the repository's
     * thread-stack dumping exists to diagnose after the fact. Sized from the harness's own fixed
     * budgets rather than a guess: worst case per trial is the 45 s generation wait plus the 25 s
     * cover wait plus the 12 s camera settle plus ~5 s of floor sampling and a few seconds of
     * gesture, so the four-camera case's fixed ceiling is around 380 s, plus a 30 s map-ready wait
     * and the fog-detached arm's own launch. This leaves better than a 2x margin over that.
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(CASE_TIMEOUT_SECONDS)

    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: PermissionHistory? = null

    /**
     * The first-run disclosure is a modal over the entry screen: it would swallow every injected
     * DOWN, and a swallowed DOWN reads exactly like a gesture the map ignored. Marked seen for the
     * duration of the case and restored afterwards, the same seam
     * `GoogleProductionLauncherMapHostTest`'s fling case uses.
     */
    @Before
    fun setUp() {
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
        // The fog-detached arm arms the test hooks; a case that failed an assertion mid-arm must
        // not leave a fog-free surface configured for whatever runs next.
        GoogleMapSurfaceTestHooks.reset()
    }

    /**
     * Rows 34, 35 and 36 of the inventory, plus row 54's portable halves, plus the start-camera
     * upgrades for rows 37-40.
     *
     * One case rather than four: Google has no fallback/production style split, so
     * `...OnTheProductionStyle` has no separate referent, and "from exploration zoom", "near the
     * limits" and "past the antimeridian" are start cameras of the same gesture. The antimeridian
     * trial additionally asserts that the visible region really does straddle the seam at its
     * settled camera, so the row-54 claim cannot be discharged by a camera that never reached it.
     */
    @Test
    fun aHeldTwoFingerPinchZoomOutIsCoveredOrFoggedAtEveryStartCamera() {
        runGestureCase(
            label = "heldTwoFingerPinchZoomOut",
            kind = GestureKind.PINCH_ZOOM_OUT,
            trials = listOf(
                TrialCamera(OPEN_OCEAN_MID_ZOOM),
                TrialCamera(OPEN_OCEAN_EXPLORATION_ZOOM),
                TrialCamera(ANTIMERIDIAN_SEAM, requiresTheAntimeridianSeam = true),
                TrialCamera(HIGH_NORTH_NEAR_THE_MERCATOR_LIMIT),
            ),
        ) { harness, trial ->
            drivePinch(
                harness = harness,
                canvas = GestureCanvas(harness),
                label = "pinch/${trial.camera.name}",
                minimumZoomOut = requireNotNull(GestureKind.PINCH_ZOOM_OUT.minimumZoomOut),
                endSpanFraction = PINCH_END_SPAN_FRACTION,
            )
        }
    }

    /** Row 41: the SDK's one-finger zoom - double tap, hold, drag - over never-visited ground. */
    @Test
    fun aOneFingerQuickZoomOutIsCoveredOrFoggedThroughTheHeldDrag() {
        runGestureCase(
            label = "oneFingerQuickZoomOut",
            kind = GestureKind.QUICK_ZOOM_OUT,
            trials = listOf(TrialCamera(OPEN_OCEAN_EXPLORATION_ZOOM)),
        ) { harness, trial ->
            driveQuickZoomOut(
                harness = harness,
                canvas = GestureCanvas(harness),
                label = "quickZoom/${trial.camera.name}",
                minimumZoomOut = requireNotNull(GestureKind.QUICK_ZOOM_OUT.minimumZoomOut),
            )
        }
    }

    /**
     * Row 46. The audited window deliberately outlives the fingers: a tap-triggered zoom is a
     * gesture whose camera movement is the animation the SDK runs on its behalf, so
     * [GestureKind.DOUBLE_TAP_ZOOM_IN] extends the window past the last UP and those animation
     * frames are the ones judged.
     */
    @Test
    fun aDoubleTapZoomInIsCoveredOrFoggedThroughItsAnimation() {
        runGestureCase(
            label = "doubleTapZoomIn",
            kind = GestureKind.DOUBLE_TAP_ZOOM_IN,
            trials = listOf(TrialCamera(OPEN_OCEAN_EXPLORATION_ZOOM)),
        ) { harness, trial ->
            driveDoubleTapZoomIn(
                harness = harness,
                canvas = GestureCanvas(harness),
                label = "doubleTap/${trial.camera.name}",
            )
        }
    }

    /** Row 47, the higher-value tap row: zoom-out is the direction where a late fog tile shows. */
    @Test
    fun aTwoFingerTapZoomOutIsCoveredOrFoggedThroughItsAnimation() {
        runGestureCase(
            label = "twoFingerTapZoomOut",
            kind = GestureKind.TWO_FINGER_TAP_ZOOM_OUT,
            trials = listOf(TrialCamera(OPEN_OCEAN_EXPLORATION_ZOOM)),
        ) { harness, trial ->
            driveTwoFingerTapZoomOut(
                harness = harness,
                canvas = GestureCanvas(harness),
                label = "twoFingerTap/${trial.camera.name}",
            )
        }
    }

    /**
     * Upgrades row 45 from install bookkeeping to screen truth: the MapLibre original's pose is
     * reached here by a real orbiting pointer pair rather than by a programmed camera.
     */
    @Test
    fun aTwoFingerRotateIsCoveredOrFoggedWhileTheBearingChanges() {
        runGestureCase(
            label = "twoFingerRotate",
            kind = GestureKind.TWO_FINGER_ROTATE,
            trials = listOf(TrialCamera(OPEN_OCEAN_EXPLORATION_ZOOM)),
        ) { harness, trial ->
            driveRotate(
                harness = harness,
                canvas = GestureCanvas(harness),
                label = "rotate/${trial.camera.name}",
                minimumBearingDegrees =
                    requireNotNull(GestureKind.TWO_FINGER_ROTATE.minimumBearingDegrees),
            )
        }
    }

    /**
     * Row 43, the most expensive twin: a real two-finger shove, then an immediate re-grab into a
     * held pinch inside one interaction, with the shove's tilt required to survive the re-grab and
     * the whole pinch.
     *
     * The start zoom is higher than the other cases' and the pinch's inward travel stops as soon as
     * the required zoom-out is achieved. Google clamps the maximum tilt as a function of zoom, so a
     * pinch that ran its full nominal travel could zoom out past the clamp and erase the tilt this
     * row exists to prove - which would be a failure of the test's geometry, not of the product.
     */
    @Test
    fun aTwoFingerShoveThenAnImmediateHeldPinchKeepsTiltAndStaysCoveredOrFogged() {
        runGestureCase(
            label = "twoFingerShoveThenHeldPinch",
            kind = GestureKind.SHOVE_THEN_HELD_PINCH,
            trials = listOf(TrialCamera(OPEN_OCEAN_SHOVE_START_ZOOM)),
        ) { harness, trial ->
            driveShoveThenHeldPinch(
                harness = harness,
                canvas = GestureCanvas(harness),
                label = "shoveThenPinch/${trial.camera.name}",
                minimumZoomOut = requireNotNull(GestureKind.SHOVE_THEN_HELD_PINCH.minimumZoomOut),
                minimumTiltDegrees =
                    requireNotNull(GestureKind.SHOVE_THEN_HELD_PINCH.minimumTiltDegrees),
            )
        }
    }

    // ---- case body ------------------------------------------------------------------------------

    private fun runGestureCase(
        label: String,
        kind: GestureKind,
        trials: List<TrialCamera>,
        drive: (GestureExposureHarness, TrialCamera) -> GestureDrive,
    ) {
        assertTrue(
            "$label: the hosted Google map needs the keyed googlePoc runtime. This class is the " +
                "sole closure of eight gesture parity rows, so a keyless run must be LOUD: an " +
                "abstention is indistinguishable from proof, and a green keyless shard would " +
                "record the rows as closed without one frame having been judged.",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        val reports = mutableListOf<GestureTrialReport>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val harness = GestureExposureHarness.attach(scenario)
            try {
                // Waited FIRST, described afterwards: a message built before the wait would report
                // the state the surface was in when the wait began, which is the one thing already
                // known to be wrong.
                val basemapOnline = harness.awaitUntil(BASEMAP_TIMEOUT_MILLIS) {
                    harness.basemapLoadState() == GestureExposureHarness.ONLINE_STATE
                }
                assertTrue(
                    "$label: the production launcher never reported an online basemap, so no " +
                        "trial below would have had real map pixels under the fog to judge. " +
                        harness.diagnostics(),
                    basemapOnline,
                )
                // The launcher's OWN first canonical install, waited for before any trial starts.
                //
                // Without this the first trial's readiness gate carries two claims at once: that
                // the surface can install fog at all, and that THIS trial's start camera produced a
                // new generation. They fail identically - `generation()` is still null - and only
                // the first is about the environment. Recorded on the API 36 AVD while closing
                // V02-007: a class run failed five of six cases here, each on a first install that
                // had not completed, while every one of those cases passed when run alone.
                assertTrue(
                    "$label: the production launcher never proved a canonical fog generation " +
                        "at its own start camera within ${FIRST_GENERATION_TIMEOUT_MILLIS}ms, so no " +
                        "trial below could tell a start camera that produced no new generation " +
                        "from a surface that had never installed one. " + harness.diagnostics(),
                    harness.awaitUntil(FIRST_GENERATION_TIMEOUT_MILLIS) {
                        harness.generation() != null
                    },
                )
                // The entry route flies the camera once, to the newest accepted point, the first
                // time one exists. Letting that one-shot land before the first trial keeps it from
                // arriving on top of a start camera and rewriting the scene mid-audit. Waited for
                // AFTER the first generation, and on the flight flag as well as on camera
                // stillness: before the flight has started the camera is trivially still, so a
                // stillness-only wait here returned immediately and left the flight free to land
                // inside an audited gesture.
                assertTrue(
                    "$label: the launcher's camera never went quiet - either it kept moving or a " +
                        "programmed flight stayed in progress - so a trial starting here would be " +
                        "audited while the entry route was still flying. " + harness.diagnostics(),
                    harness.awaitQuietCamera(),
                )
                trials.forEach { trial ->
                    reports += harness.runTrial(kind, trial.camera) { live ->
                        if (trial.requiresTheAntimeridianSeam) {
                            val straddles = live.visibleRegionCrossesTheSeam()
                            assertTrue(
                                "$label/${trial.camera.name}: the settled visible region does " +
                                    "not straddle the antimeridian, so the world-copy claim this " +
                                    "trial carries would be made about an ordinary viewport. " +
                                    live.diagnostics(),
                                straddles,
                            )
                        }
                        drive(live, trial)
                    }
                }
            } finally {
                harness.close()
            }
        }
        // Measured after the launcher is gone, at the cameras the fogged trials actually SETTLED
        // at rather than the ones they requested, so the sensitivity arm and the audited frames
        // describe the same scene.
        val bare = GestureExposureBareReference.measure(
            label,
            reports.map { report -> report.camera.name to report.before },
        )
        val failures = mutableListOf<String>()
        reports.forEach { report ->
            val reading = bare[report.camera.name]
            GestureExposureVerdict.emit(report, reading)
            failures += GestureExposureVerdict.failuresFor(report, reading)
        }
        GestureExposureVerdict.emitSummary(
            label = label,
            trials = reports.size,
            failures = failures,
            proofs = reports.count { report -> report.coverPixelProof != null },
        )
        assertTrue(
            "$label: ${failures.size} gesture-exposure clauses failed across ${reports.size} " +
                "trials:\n" + failures.joinToString(separator = "\n"),
            failures.isEmpty(),
        )
    }

    // ---- drivers --------------------------------------------------------------------------------

    private fun drivePinch(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        label: String,
        minimumZoomOut: Float,
        endSpanFraction: Float,
    ): GestureDrive {
        val tally = DriveTally()
        repeat(GESTURE_ATTEMPTS) {
            tally.attempts += 1
            val drive = attemptPinch(
                harness = harness,
                canvas = canvas,
                tally = tally,
                label = label,
                minimumZoomOut = minimumZoomOut,
                endSpanFraction = endSpanFraction,
                downAtMillis = null,
            )
            if (drive != null) return drive
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(tally.exhausted(label, "the SDK's scale detector", harness))
    }

    /**
     * One pinch attempt. [downAtMillis] lets the composite hand in the shove's own DOWN instant, so
     * the audited window covers the WHOLE interaction rather than only its second half.
     */
    private fun attemptPinch(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        tally: DriveTally,
        label: String,
        minimumZoomOut: Float,
        endSpanFraction: Float,
        downAtMillis: Long?,
    ): GestureDrive? = FlingGestureInjector.withStream { stream ->
        val y = canvas.bandY(PINCH_POINTER_Y_FRACTION)
        val startSpan = canvas.width * PINCH_START_SPAN_FRACTION
        val endSpan = canvas.width * endSpanFraction
        fun pair(span: Float): List<FlingGestureInjector.TouchPoint> = listOf(
            canvas.at(canvas.centreX - span / 2f, y),
            canvas.at(canvas.centreX + span / 2f, y),
        )

        val zoomAtDown = harness.cameraPosition().zoom
        val openedAt = downAtMillis ?: SystemClock.elapsedRealtime()
        stream.down(pair(startSpan).first())
        tally.downs += 1
        stream.pointerDown(pair(startSpan))
        val engageSpan = startSpan + (endSpan - startSpan) * GESTURE_ENGAGE_TRAVEL
        repeat(GESTURE_ENGAGE_MOVES) { move ->
            stream.move(
                pair(startSpan + (engageSpan - startSpan) * (move + 1) / GESTURE_ENGAGE_MOVES),
            )
            SystemClock.sleep(GESTURE_STEP_MILLIS)
        }
        val engagement = zoomAtDown - harness.cameraPosition().zoom
        if (engagement < MINIMUM_PINCH_ENGAGEMENT) {
            stream.liftAll(pair(engageSpan))
            tally.notes += "pinchEngagement=${format(engagement)}"
            return@withStream null
        }
        var span = engageSpan
        var step = 0
        while (step < GESTURE_MEASURED_STEPS) {
            step += 1
            span = engageSpan + (endSpan - engageSpan) * step / GESTURE_MEASURED_STEPS
            stream.move(pair(span))
            SystemClock.sleep(GESTURE_STEP_MILLIS)
            // Travel until the requirement is met with margin, then stop. A fixed span ratio would
            // be a guess at the SDK's span-to-zoom mapping in both directions: too little travel
            // fails a healthy product, too much walks the composite past the SDK's tilt clamp.
            if (
                step % GESTURE_TRAVEL_CHECK_EVERY == 0 &&
                zoomAtDown - harness.cameraPosition().zoom >= minimumZoomOut + ZOOM_TRAVEL_MARGIN
            ) {
                break
            }
        }
        SystemClock.sleep(GESTURE_HOLD_MILLIS)
        stream.liftAll(pair(span))
        GestureDrive(
            note = "$label spanPx=${startSpan.toInt()}->${span.toInt()} moves=$step",
            downAtMillis = openedAt,
            upAtMillis = SystemClock.elapsedRealtime(),
            injectedDownCount = tally.downs,
            attempts = tally.attempts,
        )
    }

    private fun driveQuickZoomOut(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        label: String,
        minimumZoomOut: Float,
    ): GestureDrive {
        val tally = DriveTally()
        repeat(GESTURE_ATTEMPTS) { attempt ->
            tally.attempts += 1
            // From a single-axis drag only one direction zooms out, and which one is the SDK's
            // convention rather than this test's business: alternate instead of encoding a guess.
            val drive = attemptQuickZoomOut(
                harness = harness,
                canvas = canvas,
                tally = tally,
                label = label,
                minimumZoomOut = minimumZoomOut,
                upward = attempt % 2 == 0,
            )
            if (drive != null) return drive
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(tally.exhausted(label, "the SDK's one-finger zoom detector", harness))
    }

    private fun attemptQuickZoomOut(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        tally: DriveTally,
        label: String,
        minimumZoomOut: Float,
        upward: Boolean,
    ): GestureDrive? {
        val x = canvas.centreX
        val y = canvas.bandY(TAP_POINTER_Y_FRACTION)
        val zoomAtDown = harness.cameraPosition().zoom
        val openedAt = SystemClock.elapsedRealtime()
        // The opening tap is its own stream so it carries its own downTime; a tap whose downTime
        // was stamped when the geometry was resolved reads as a long press and nothing fires.
        FlingGestureInjector.withStream { stream ->
            stream.down(canvas.at(x, y))
            tally.downs += 1
            SystemClock.sleep(TAP_DURATION_MILLIS)
            stream.up(canvas.at(x, y))
        }
        SystemClock.sleep(DOUBLE_TAP_GAP_MILLIS)
        return FlingGestureInjector.withStream { stream ->
            stream.down(canvas.at(x, y))
            tally.downs += 1
            SystemClock.sleep(TAP_DURATION_MILLIS)
            val travel = canvas.height * QUICK_ZOOM_TRAVEL_FRACTION
            val direction = if (upward) -1f else 1f
            val engageY = y + direction * travel * GESTURE_ENGAGE_TRAVEL
            var currentY = y
            repeat(GESTURE_ENGAGE_MOVES) { move ->
                currentY = y + (engageY - y) * (move + 1) / GESTURE_ENGAGE_MOVES
                stream.move(listOf(canvas.at(x, currentY)))
                SystemClock.sleep(GESTURE_STEP_MILLIS)
            }
            val engagement = zoomAtDown - harness.cameraPosition().zoom
            if (engagement < MINIMUM_QUICK_ZOOM_ENGAGEMENT) {
                stream.up(canvas.at(x, currentY))
                tally.notes += "quickZoom${if (upward) "Up" else "Down"}=${format(engagement)}"
                return@withStream null
            }
            var step = 0
            while (step < GESTURE_MEASURED_STEPS) {
                step += 1
                currentY = y + direction * travel * step / GESTURE_MEASURED_STEPS
                stream.move(listOf(canvas.at(x, currentY)))
                SystemClock.sleep(GESTURE_STEP_MILLIS)
                if (
                    step % GESTURE_TRAVEL_CHECK_EVERY == 0 &&
                    zoomAtDown - harness.cameraPosition().zoom >=
                    minimumZoomOut + ZOOM_TRAVEL_MARGIN
                ) {
                    break
                }
            }
            SystemClock.sleep(GESTURE_HOLD_MILLIS)
            stream.up(canvas.at(x, currentY))
            GestureDrive(
                note = "$label ${if (upward) "upward" else "downward"} moves=$step",
                downAtMillis = openedAt,
                upAtMillis = SystemClock.elapsedRealtime(),
                injectedDownCount = tally.downs,
                attempts = tally.attempts,
            )
        }
    }

    private fun driveDoubleTapZoomIn(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        label: String,
    ): GestureDrive {
        val tally = DriveTally()
        repeat(GESTURE_ATTEMPTS) {
            tally.attempts += 1
            val x = canvas.centreX
            val y = canvas.bandY(TAP_POINTER_Y_FRACTION)
            val zoomAtTap = harness.cameraPosition().zoom
            val openedAt = SystemClock.elapsedRealtime()
            var liftedAt = openedAt
            repeat(2) { tap ->
                FlingGestureInjector.withStream { stream ->
                    stream.down(canvas.at(x, y))
                    tally.downs += 1
                    SystemClock.sleep(TAP_DURATION_MILLIS)
                    stream.up(canvas.at(x, y))
                    liftedAt = SystemClock.elapsedRealtime()
                }
                if (tap == 0) SystemClock.sleep(DOUBLE_TAP_GAP_MILLIS)
            }
            val travelled = awaitTapZoom(harness, zoomAtTap, zoomIn = true)
            if (travelled >= MINIMUM_TAP_ZOOM_ENGAGEMENT) {
                return GestureDrive(
                    note = "$label travelled=${format(travelled)}",
                    downAtMillis = openedAt,
                    upAtMillis = liftedAt,
                    injectedDownCount = tally.downs,
                    attempts = tally.attempts,
                )
            }
            tally.notes += "doubleTapTravel=${format(travelled)}"
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(tally.exhausted(label, "the SDK's double-tap zoom", harness))
    }

    private fun driveTwoFingerTapZoomOut(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        label: String,
    ): GestureDrive {
        val tally = DriveTally()
        repeat(GESTURE_ATTEMPTS) {
            tally.attempts += 1
            val y = canvas.bandY(TAP_POINTER_Y_FRACTION)
            val gap = canvas.width * TWO_FINGER_TAP_GAP_FRACTION
            val points = listOf(
                canvas.at(canvas.centreX - gap / 2f, y),
                canvas.at(canvas.centreX + gap / 2f, y),
            )
            val zoomAtTap = harness.cameraPosition().zoom
            val openedAt = SystemClock.elapsedRealtime()
            var liftedAt = openedAt
            FlingGestureInjector.withStream { stream ->
                stream.down(points.first())
                tally.downs += 1
                stream.pointerDown(points)
                SystemClock.sleep(TAP_DURATION_MILLIS)
                stream.liftAll(points)
                liftedAt = SystemClock.elapsedRealtime()
            }
            val travelled = awaitTapZoom(harness, zoomAtTap, zoomIn = false)
            if (travelled >= MINIMUM_TAP_ZOOM_ENGAGEMENT) {
                return GestureDrive(
                    note = "$label travelled=${format(travelled)} gapPx=${gap.toInt()}",
                    downAtMillis = openedAt,
                    upAtMillis = liftedAt,
                    injectedDownCount = tally.downs,
                    attempts = tally.attempts,
                )
            }
            tally.notes += "twoFingerTapTravel=${format(travelled)}"
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(tally.exhausted(label, "the SDK's two-finger tap zoom", harness))
    }

    private fun driveRotate(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        label: String,
        minimumBearingDegrees: Float,
    ): GestureDrive {
        val tally = DriveTally()
        repeat(GESTURE_ATTEMPTS) {
            tally.attempts += 1
            val drive = attemptRotate(harness, canvas, tally, label, minimumBearingDegrees)
            if (drive != null) return drive
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(tally.exhausted(label, "the SDK's rotate detector", harness))
    }

    private fun attemptRotate(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        tally: DriveTally,
        label: String,
        minimumBearingDegrees: Float,
    ): GestureDrive? = FlingGestureInjector.withStream { stream ->
        // The pair orbits its own midpoint at a constant span, so the only degree of freedom that
        // changes is the angle between the pointers - the one the rotate detector reads, and the
        // one a vertically stacked pinch stream can never express.
        val centreY = canvas.bandY(PINCH_POINTER_Y_FRACTION)
        val radius = min(canvas.width, canvas.height) * ROTATE_RADIUS_FRACTION
        fun pairAt(angleDegrees: Double): List<FlingGestureInjector.TouchPoint> {
            val radians = Math.toRadians(angleDegrees)
            val dx = (radius * cos(radians)).toFloat()
            val dy = (radius * sin(radians)).toFloat()
            return listOf(
                canvas.at(canvas.centreX - dx, centreY - dy),
                canvas.at(canvas.centreX + dx, centreY + dy),
            )
        }

        val bearingAtDown = harness.cameraPosition().bearing
        val openedAt = SystemClock.elapsedRealtime()
        stream.down(pairAt(0.0).first())
        tally.downs += 1
        stream.pointerDown(pairAt(0.0))
        val engageAngle = ROTATE_TOTAL_DEGREES * GESTURE_ENGAGE_TRAVEL
        var angle = 0.0
        repeat(GESTURE_ENGAGE_MOVES) { move ->
            angle = engageAngle * (move + 1) / GESTURE_ENGAGE_MOVES
            stream.move(pairAt(angle))
            SystemClock.sleep(GESTURE_STEP_MILLIS)
        }
        val engagement = abs(signedTurn(bearingAtDown, harness.cameraPosition().bearing))
        if (engagement < MINIMUM_ROTATE_ENGAGEMENT_DEGREES) {
            stream.liftAll(pairAt(angle))
            tally.notes += "rotateEngagement=${format(engagement)}"
            return@withStream null
        }
        var step = 0
        while (step < GESTURE_MEASURED_STEPS) {
            step += 1
            angle = engageAngle +
                (ROTATE_TOTAL_DEGREES - engageAngle) * step / GESTURE_MEASURED_STEPS
            stream.move(pairAt(angle))
            SystemClock.sleep(GESTURE_STEP_MILLIS)
            if (
                step % GESTURE_TRAVEL_CHECK_EVERY == 0 &&
                abs(signedTurn(bearingAtDown, harness.cameraPosition().bearing)) >=
                minimumBearingDegrees + BEARING_TRAVEL_MARGIN_DEGREES
            ) {
                break
            }
        }
        SystemClock.sleep(GESTURE_HOLD_MILLIS)
        stream.liftAll(pairAt(angle))
        GestureDrive(
            note = "$label radiusPx=${radius.toInt()} turnedDeg=${format(angle.toFloat())} " +
                "moves=$step",
            downAtMillis = openedAt,
            upAtMillis = SystemClock.elapsedRealtime(),
            injectedDownCount = tally.downs,
            attempts = tally.attempts,
        )
    }

    private fun driveShoveThenHeldPinch(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        label: String,
        minimumZoomOut: Float,
        minimumTiltDegrees: Float,
    ): GestureDrive {
        val tally = DriveTally()
        repeat(GESTURE_ATTEMPTS) { attempt ->
            tally.attempts += 1
            val openedAt = SystemClock.elapsedRealtime()
            // From zero tilt only one travel direction moves the pitch, and which one is the
            // detector's convention: alternate per attempt rather than encode a guess.
            val tiltAtShoveDown = attemptShove(
                harness = harness,
                canvas = canvas,
                tally = tally,
                minimumTiltDegrees = minimumTiltDegrees,
                upward = attempt % 2 == 0,
            )
            if (tiltAtShoveDown != null) {
                // No settle between the lift and the re-grab: the claim is that the tilt survives
                // an IMMEDIATE re-grab, and a settle would let an idle rebuild stand in for it.
                SystemClock.sleep(REGRAB_GAP_MILLIS)
                val drive = attemptPinch(
                    harness = harness,
                    canvas = canvas,
                    tally = tally,
                    label = label,
                    minimumZoomOut = minimumZoomOut,
                    endSpanFraction = COMPOSITE_PINCH_END_SPAN_FRACTION,
                    downAtMillis = openedAt,
                )
                // Carried so the trial's tilt is judged from THIS attempt's own DOWN. Attempts
                // do not re-settle the start camera, so a rejected attempt's tilt is still on the
                // camera when the next one opens, and measuring from the settled start camera
                // would let that residue discharge the row's claim.
                if (drive != null) return drive.copy(tiltAtAcceptedDownDegrees = tiltAtShoveDown)
            }
            SystemClock.sleep(GESTURE_RETRY_SETTLE_MILLIS)
        }
        throw AssertionError(
            tally.exhausted(
                label,
                "the SDK's shove detector followed by its scale detector",
                harness,
            ),
        )
    }

    /** The tilt read at this attempt's own DOWN when the shove was accepted, else null. */
    private fun attemptShove(
        harness: GestureExposureHarness,
        canvas: GestureCanvas,
        tally: DriveTally,
        minimumTiltDegrees: Float,
        upward: Boolean,
    ): Float? = FlingGestureInjector.withStream { stream ->
        // A horizontally separated pair travelling vertically together: unmistakably a shove to a
        // detector that rejects near-vertical pairs, and the one shape a stacked pinch cannot be.
        val gap = canvas.width * SHOVE_POINTER_GAP_FRACTION
        val startY = canvas.bandY(
            if (upward) SHOVE_DOWN_LOW_Y_FRACTION else SHOVE_DOWN_HIGH_Y_FRACTION,
        )
        val endY = canvas.freeY(
            if (upward) SHOVE_TRAVEL_HIGH_Y_FRACTION else SHOVE_TRAVEL_LOW_Y_FRACTION,
        )
        fun pairAt(y: Float): List<FlingGestureInjector.TouchPoint> = listOf(
            canvas.at(canvas.centreX - gap / 2f, y),
            canvas.at(canvas.centreX + gap / 2f, y),
        )

        val tiltAtDown = harness.cameraPosition().tilt
        stream.down(pairAt(startY).first())
        tally.downs += 1
        stream.pointerDown(pairAt(startY))
        val engageY = startY + (endY - startY) * GESTURE_ENGAGE_TRAVEL
        var currentY = startY
        repeat(GESTURE_ENGAGE_MOVES) { move ->
            currentY = startY + (engageY - startY) * (move + 1) / GESTURE_ENGAGE_MOVES
            stream.move(pairAt(currentY))
            SystemClock.sleep(GESTURE_STEP_MILLIS)
        }
        val engagement = abs(harness.cameraPosition().tilt - tiltAtDown)
        if (engagement < MINIMUM_SHOVE_ENGAGEMENT_DEGREES) {
            stream.liftAll(pairAt(currentY))
            tally.notes += "shove${if (upward) "Up" else "Down"}=${format(engagement)}"
            return@withStream null
        }
        var step = 0
        while (step < GESTURE_MEASURED_STEPS) {
            step += 1
            currentY = engageY + (endY - engageY) * step / GESTURE_MEASURED_STEPS
            stream.move(pairAt(currentY))
            SystemClock.sleep(GESTURE_STEP_MILLIS)
            if (
                step % GESTURE_TRAVEL_CHECK_EVERY == 0 &&
                abs(harness.cameraPosition().tilt - tiltAtDown) >=
                minimumTiltDegrees + TILT_TRAVEL_MARGIN_DEGREES
            ) {
                break
            }
        }
        stream.liftAll(pairAt(currentY))
        tally.notes += "shoveTilt=${format(abs(harness.cameraPosition().tilt - tiltAtDown))}"
        tiltAtDown
    }

    // ---- shared helpers -------------------------------------------------------------------------

    /**
     * How far the tap-triggered zoom animation actually travelled, waiting for it to stop.
     *
     * The animation is the gesture's camera movement here, so an attempt is judged on what the
     * animation achieved rather than on what the injected stream looked like.
     */
    private fun awaitTapZoom(
        harness: GestureExposureHarness,
        zoomAtTap: Float,
        zoomIn: Boolean,
    ): Float {
        var travelled = 0f
        var last = zoomAtTap
        var stable = 0
        val deadline = SystemClock.elapsedRealtime() + TAP_ANIMATION_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline && stable < TAP_STABLE_POLLS) {
            val zoom = harness.cameraPosition().zoom
            travelled = maxOf(travelled, if (zoomIn) zoom - zoomAtTap else zoomAtTap - zoom)
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

    /** Signed shortest turn, so 350 -> 10 degrees reads as +20 rather than -340. */
    private fun signedTurn(from: Float, to: Float): Float =
        ((to - from) % 360f + 540f) % 360f - 180f

    private fun format(value: Float): String = "%.3f".format(value)

    /** A start camera, plus whatever about the scene the trial has to prove it really reached. */
    private data class TrialCamera(
        val camera: GestureStartCamera,
        val requiresTheAntimeridianSeam: Boolean = false,
    )

    /**
     * What a driver injected across every attempt, accepted or not.
     *
     * [downs] counts every `ACTION_DOWN`, including the ones belonging to rejected engagement
     * attempts, because `GestureOwningGoogleMapView.dispatchTouchEvent` increments
     * `map_touch_down_count` on `ACTION_DOWN` alone - never on `ACTION_POINTER_DOWN` - and the
     * verdict asserts the published count grew by exactly this number. Losing a rejected attempt's
     * DOWN from the tally would turn a healthy product red.
     */
    private class DriveTally {
        var downs = 0
        var attempts = 0
        val notes = mutableListOf<String>()

        fun exhausted(label: String, detector: String, harness: GestureExposureHarness): String =
            "$label: the injected stream never engaged $detector in $attempts attempts " +
                "($downs injected ACTION_DOWNs): ${notes.joinToString(" ")} " +
                harness.diagnostics()
    }

    /**
     * Screen-space geometry of the live `MapView`. Injected events carry display coordinates, so
     * every position is the view's origin on screen plus a fraction of its size.
     */
    private class GestureCanvas(harness: GestureExposureHarness) {
        private val origin: IntArray = harness.viewOrigin()
        private val size: Pair<Int, Int> = harness.viewSize()
        val width: Float = size.first.toFloat()
        val height: Float = size.second.toFloat()
        val centreX: Float = origin[0] + width / 2f

        fun at(x: Float, y: Float): FlingGestureInjector.TouchPoint =
            FlingGestureInjector.TouchPoint(x, y)

        /**
         * A y for a DOWN or a POINTER_DOWN: inside the band the entry screen's own controls cannot
         * reach. The `check` is a guard on this file's own constants, not on the product - a DOWN
         * that lands on the menu button or the recentre control would be swallowed, and a swallowed
         * DOWN is indistinguishable from a gesture the map ignored.
         */
        fun bandY(fraction: Float): Float {
            check(fraction in DOWN_BAND_TOP_FRACTION..DOWN_BAND_BOTTOM_FRACTION) {
                "a DOWN at $fraction of the map height can land on the entry screen's own controls"
            }
            return origin[1] + height * fraction
        }

        /** A y for a MOVE, which the claimed stream may carry anywhere inside the view. */
        fun freeY(fraction: Float): Float =
            origin[1] + height * fraction.coerceIn(FREE_Y_MINIMUM, FREE_Y_MAXIMUM)

        private companion object {
            /**
             * The band a DOWN may land in. Narrower than the audit's own chrome-free crop
             * (`GestureExposurePixels.BAND_TOP_FRACTION` / `BAND_BOTTOM_FRACTION`) so a pointer is
             * never merely at its edge.
             */
            const val DOWN_BAND_TOP_FRACTION = 0.36f
            const val DOWN_BAND_BOTTOM_FRACTION = 0.68f
            const val FREE_Y_MINIMUM = 0.03f
            const val FREE_Y_MAXIMUM = 0.97f
        }
    }

    private companion object {
        const val CASE_TIMEOUT_SECONDS = 900L
        const val BASEMAP_TIMEOUT_MILLIS = 60_000L

        /**
         * How long the launcher's own first canonical install may take before a trial starts.
         *
         * Deliberately longer than the binding's 15 s install deadline plus one retry, because a
         * first install that misses that deadline is retried rather than abandoned, and a case that
         * gave up sooner would report the retry window as a failure.
         */
        const val FIRST_GENERATION_TIMEOUT_MILLIS = 60_000L

        /**
         * Never-visited ground, and deliberately open ocean.
         *
         * Two reasons, both about the oracle rather than about scenery. Open water carries almost
         * no labels, and labels composite ABOVE the fog `TileOverlay` - the repository's own
         * recorded figure on this emulator image is `noiseFloorPctMax=12.247` - so a settled floor
         * measured here is close to zero and the leak rule is at its most sensitive. And the bare
         * basemap there is a flat light blue, far outside the fog colour family, so the mandatory
         * fog-detached arm reads near 100% exposure instead of scraping its 30% minimum.
         *
         * These are also nowhere near any fixture location the suite writes canonical points at,
         * so "unexplored" holds even if another case leaves a point behind.
         */
        val OPEN_OCEAN_MID_ZOOM = GestureStartCamera(
            name = "openOceanMidZoom",
            latitude = -25.0,
            longitude = -130.0,
            zoom = 8.0f,
        )
        val OPEN_OCEAN_EXPLORATION_ZOOM = GestureStartCamera(
            name = "openOceanExplorationZoom",
            latitude = -25.5,
            longitude = -130.5,
            zoom = 16.0f,
        )

        /** Low enough that the viewport straddles the seam; the trial asserts that it does. */
        val ANTIMERIDIAN_SEAM = GestureStartCamera(
            name = "antimeridianSeam",
            latitude = -10.0,
            longitude = 179.9,
            zoom = 7.0f,
        )

        /** Inside the Mercator limit, where the SDK's own tile grid is at its most distorted. */
        val HIGH_NORTH_NEAR_THE_MERCATOR_LIMIT = GestureStartCamera(
            name = "highNorthNearTheMercatorLimit",
            latitude = 84.0,
            longitude = -20.0,
            zoom = 8.0f,
        )

        /**
         * Higher than the other cases'. Google clamps the maximum tilt as a function of zoom, so
         * the composite starts far enough in that its zoom-out cannot reach the band where a
         * proven tilt would be clamped away.
         */
        val OPEN_OCEAN_SHOVE_START_ZOOM = GestureStartCamera(
            name = "openOceanShoveStartZoom",
            latitude = -24.5,
            longitude = -129.5,
            zoom = 17.0f,
        )

        /**
         * The pinch separates its pointers HORIZONTALLY at one fixed y, unlike the MapLibre
         * original's vertical pair. The entry screen's controls own bands of the height, not of the
         * width, so a horizontal pair keeps both DOWNs deep inside the safe band while still
         * opening to most of the screen - which is where the zoom-out travel comes from.
         *
         * The opening span stops at 0.76 rather than filling the width: that leaves each pointer
         * 0.12 of the width from its screen edge, keeping the DOWN outside the system's back-
         * gesture strip for the same reason [FlingGestureInjector]'s fling path holds 120 px clear
         * of every edge. Every MOVE afterwards travels inward, away from the edges.
         */
        const val PINCH_POINTER_Y_FRACTION = 0.52f
        const val PINCH_START_SPAN_FRACTION = 0.76f
        const val PINCH_END_SPAN_FRACTION = 0.06f

        /**
         * The composite's inward travel is capped short of the SDK's zoom-dependent tilt clamp: a
         * 0.76 -> 0.12 span still offers well over the 1.5 levels its kind demands, while leaving
         * the end camera high enough that a proven tilt cannot be clamped away underneath it.
         */
        const val COMPOSITE_PINCH_END_SPAN_FRACTION = 0.12f

        const val TAP_POINTER_Y_FRACTION = 0.52f
        const val TWO_FINGER_TAP_GAP_FRACTION = 0.40f
        const val QUICK_ZOOM_TRAVEL_FRACTION = 0.45f

        const val SHOVE_POINTER_GAP_FRACTION = 0.50f
        const val SHOVE_DOWN_LOW_Y_FRACTION = 0.64f
        const val SHOVE_DOWN_HIGH_Y_FRACTION = 0.40f
        const val SHOVE_TRAVEL_HIGH_Y_FRACTION = 0.28f
        const val SHOVE_TRAVEL_LOW_Y_FRACTION = 0.76f

        const val ROTATE_RADIUS_FRACTION = 0.28f
        const val ROTATE_TOTAL_DEGREES = 75.0

        /**
         * Injection geometry and cadence, ported from `MapSurfaceTest`'s gesture constants because
         * they are the ones this repository has already made deterministic on an emulator.
         */
        const val GESTURE_ATTEMPTS = 4
        const val GESTURE_ENGAGE_MOVES = 8
        const val GESTURE_ENGAGE_TRAVEL = 0.30f
        const val GESTURE_MEASURED_STEPS = 30
        const val GESTURE_STEP_MILLIS = 16L
        const val GESTURE_TRAVEL_CHECK_EVERY = 5
        const val GESTURE_RETRY_SETTLE_MILLIS = 2_500L

        /**
         * The fingers stay down after the travel ends. The MapLibre originals audit HELD frames,
         * and without a hold the gesture window would be short enough that the pixel claim rested
         * mostly on frames taken after the lift.
         */
        const val GESTURE_HOLD_MILLIS = 600L

        /** Short enough that the tilt cannot have been rebuilt away between the two streams. */
        const val REGRAB_GAP_MILLIS = 60L

        const val TAP_DURATION_MILLIS = 60L
        const val DOUBLE_TAP_GAP_MILLIS = 80L
        const val TAP_ANIMATION_TIMEOUT_MILLIS = 5_000L
        const val TAP_POLL_MILLIS = 50L
        const val TAP_STABLE_POLLS = 3
        const val TAP_ZOOM_STABLE_EPSILON = 0.001f

        /**
         * Engagement floors, far below the accepted endpoint movements the verdict enforces, so a
         * retry can never hide a gesture that barely moved: whatever an accepted attempt engaged
         * on, its zoom/tilt/bearing delta is still judged against the MapLibre original's minimum.
         */
        const val MINIMUM_PINCH_ENGAGEMENT = 0.03f
        const val MINIMUM_QUICK_ZOOM_ENGAGEMENT = 0.03f
        const val MINIMUM_TAP_ZOOM_ENGAGEMENT = 0.2f
        const val MINIMUM_SHOVE_ENGAGEMENT_DEGREES = 1.0f
        const val MINIMUM_ROTATE_ENGAGEMENT_DEGREES = 2.0f

        /** Travel past the requirement before stopping, so a settling camera cannot undercut it. */
        const val ZOOM_TRAVEL_MARGIN = 0.35f
        const val TILT_TRAVEL_MARGIN_DEGREES = 5.0f
        const val BEARING_TRAVEL_MARGIN_DEGREES = 8.0f
    }
}
