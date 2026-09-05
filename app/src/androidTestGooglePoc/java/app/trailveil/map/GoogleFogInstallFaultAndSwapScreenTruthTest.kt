package app.trailveil.map

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.R
import app.trailveil.data.db.RecordingDao
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.googlepoc.SpikeCaptureSupport
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * `V02-007`: what the hosted Google surface PRESENTS while a canonical generation is replaced.
 *
 * Two failure shapes the shipped variant asserts and this one did not. A replacement whose install
 * fails part way through must leave the previously proven generation complete and on screen — no
 * install guard, no blank frame — and must recover when the fault clears; and an ordinary in-extent
 * replacement driven by a new track point must complete without any sampled frame going bare. The
 * other googlePoc cases assert install bookkeeping across those transitions (a new generation
 * arrives, the cover falls) but never look at the screen during them, and nothing else faults an
 * install at all, so the healthy path is the only path anything covers.
 *
 * **The failure mode inverts on this variant, so the assertion does too.** The shipped renderer's
 * fog transmits light, so its cases can ask "never black"; here the safety cover is itself
 * fog-coloured and the fog tiles are fully opaque, so the only observable defect is bare basemap.
 * Both cases therefore assert NEVER BARE, against a coverage floor measured on the settled map
 * immediately beforehand rather than against an absolute one: basemap labels composite above the
 * fog, so "every pixel is fog" is not an available oracle on this SDK, while "materially less of
 * the map is fog or cover than it was a moment ago, at this very camera" is.
 *
 * **Why the harness activity and not the production launcher.** The first version of both cases
 * hosted `MainActivity` and used a persisted canonical point as the stimulus. That point is exactly
 * what `RecordingEntryRoute` observes as `latestAcceptedPoint`, so appending one fired the route's
 * one-shot open-at-known-location flight and moved the camera. The camera leaving published
 * coverage is precisely when the surface is *supposed* to raise the opaque cover, so case (a)
 * measured a cover its own stimulus had caused and blamed it on the install fault, and case (b)
 * compared frames at the flown-to scene against a floor calibrated at the scene before it. Both
 * were red for that reason. [GoogleMapSurfaceTestActivity] composes the same neutral surface with
 * the same production code path and a FIXED [MapCameraRequest] and no route, so the appended point
 * is a data-only change: the camera that the floor is measured at is the camera every audited frame
 * comes from, and each case asserts that with its own drift witness rather than assuming it.
 *
 * On this variant the contract a data-only change must satisfy behind a proven generation is
 * therefore the un-inverted one — `FogOverlaySurfaceCoordinator.failPending` classifies an install
 * failure with a proven generation as RETRY_BEHIND_PLACEHOLDERS, which leaves the cover exactly as
 * it was and keeps the old proven set serving — so case (a) asserts the cover stays DOWN, the
 * proven generation stays installed, and no frame goes bare, and only then releases the fault and
 * requires a newer generation to install.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogInstallFaultAndSwapScreenTruthTest {

    /**
     * Every wait in this class is already bounded, so this rule can only fire on the one thing that
     * is not: a main looper the Maps SDK has wedged, where the `runOnMainSync` behind every tag
     * read never returns. Sized at roughly twice the sum of this class's own deadlines, so a real
     * hang becomes a diagnosable failure instead of a dead single-emulator shard, and a slow device
     * inside its own budget still passes.
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(CASE_TIMEOUT_SECONDS)

    private var database: TrailVeilDatabase? = null
    private val fogStates = CopyOnWriteArrayList<GoogleCanonicalFogState>()
    private val mapViewRef = AtomicReference<MapView?>(null)
    private val mapRef = AtomicReference<GoogleMap?>(null)

    @Before
    fun setUp() = GoogleMapSurfaceTestHooks.reset()

    @After
    fun tearDown() {
        // Always release the seam, including on a failed assertion: a fault left installed would
        // fault every later case's installs, and none of them would say why. `reset()` owns that.
        GoogleMapSurfaceTestHooks.reset()
        database?.close()
        database = null
    }

    /**
     * A faulted replacement must change nothing the user can see, and must recover when released.
     *
     * The fault is installed only AFTER a generation has been proven, deliberately: with no proven
     * generation an install failure is terminal by design (TERMINAL_FOR_COMPOSITION), and this case
     * is about the other classification — a replacement failing behind a complete, published
     * predecessor.
     */
    @Test
    fun aFaultedCanonicalInstallKeepsTheProvenGenerationPresentedUntilTheRetrySucceeds() {
        assumeTrue(
            "faulting a real canonical install requires the keyed googlePoc runtime",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        withSettledFogSurface { hosted ->
            val rejections = AtomicInteger(0)
            val stateMark = fogStates.size
            GoogleMapSurfaceTestHooks.fogInstallFault = {
                rejections.incrementAndGet()
                throw InjectedCanonicalInstallFault()
            }
            val sampler = Sampler(hosted.mapView)
            val samplerFailure: Throwable?
            try {
                appendCanonicalPoint(hosted.dao, hosted.recording, sequence = 0L)
                val faulted = awaitUntil(FAULT_TIMEOUT_MILLIS) {
                    rejections.get() >= MINIMUM_REJECTED_ATTACHES
                }
                assertTrue(
                    "the installed fault never rejected a canonical overlay attach, so no install " +
                        "was actually faulted and the rest of this case is vacuous: " +
                        "rejections=${rejections.get()} " + describe(hosted.mapView),
                    faulted,
                )
                sampler.start()
                SystemClock.sleep(FAULTED_WINDOW_MILLIS)
            } finally {
                samplerFailure = sampler.stop()
                GoogleMapSurfaceTestHooks.fogInstallFault = null
            }
            val rejectedAttaches = rejections.get()
            val samples = sampler.samples.toList()
            val faultedStates = fogStates.toList().drop(stateMark)

            assertNull(
                "the screen sampler died instead of finishing its window: " +
                    samplerFailure?.stackTraceToString(),
                samplerFailure,
            )
            assertTrue(
                "only ${samples.size} screen frames were captured while installs were being " +
                    "faulted, too few to claim anything about what was presented: " +
                    describe(hosted.mapView),
                samples.size >= MINIMUM_FAULTED_SAMPLES,
            )
            val movedGeneration = samples.filter { sample -> sample.generation != hosted.proven }
            assertTrue(
                "a faulted replacement published its generation anyway; the previously proven " +
                    "one must stay the installed one: proven=${hosted.proven} saw=" +
                    movedGeneration.map { sample -> sample.generation }.distinct() + " " +
                    describe(hosted.mapView),
                movedGeneration.isEmpty(),
            )
            // Both cover witnesses, not just the Compose one: the opaque guard is the ViewOverlay
            // drawable toggled synchronously by GoogleFogSafetyOverlay, and the Compose tag it is
            // usually read through lags it by a composition.
            val coveredFrames = samples.filter { sample ->
                sample.coverUp || sample.synchronousCoverUp
            }
            assertTrue(
                "an ordinary faulted replacement raised the opaque cover instead of leaving the " +
                    "complete proven generation presented; the camera never moved, so nothing " +
                    "else could ask for one: ${coveredFrames.size}/${samples.size} frames were " +
                    "covered: " + describeStates(faultedStates) + " " + describe(hosted.mapView),
                coveredFrames.isEmpty(),
            )
            // Positive attribution, so "nothing happened" cannot pass as "the retry arm held": the
            // binding must have published the RETRY_BEHIND_PLACEHOLDERS state itself.
            assertTrue(
                "no published fog state showed a retry armed behind the proven generation with " +
                    "the cover down, so the audited window is not the arm this case is about: " +
                    describeStates(faultedStates),
                faultedStates.any { state ->
                    state.retryScheduled &&
                        !state.coverUp &&
                        !state.terminal &&
                        state.installedGeneration?.toString() == hosted.proven
                },
            )
            assertBareFrameNeverPresented(
                hosted.baseline,
                hosted.baselineCluster,
                samples,
                hosted.mapView,
            )
            val lastFailure = tagOnMain(hosted.mapView, R.id.map_fog_last_failure) as? String
            assertTrue(
                "the fog failure the surface reported is not the one this case injected, so the " +
                    "audited window belongs to some other failure: lastFailure=$lastFailure",
                lastFailure?.startsWith(
                    InjectedCanonicalInstallFault::class.java.simpleName,
                ) == true,
            )
            assertCameraNeverDrifted(hosted, "the faulted window")

            val abandoned = faultedStates
                .mapNotNull { state -> state.pendingGeneration?.toString() }
                .toSet()
            assertTrue(
                "no faulted attempt was ever published as a pending generation, so the " +
                    "recovered-identity check below would be vacuous: " +
                    describeStates(faultedStates),
                abandoned.isNotEmpty(),
            )
            // Watched across the recovery rather than waited for afterwards. The cover is
            // already down when the recovery starts - the faulted window asserted that on every
            // sampled frame, and the retry arm by construction never touches it - so "the cover
            // fell" was satisfiable on its first poll by retained state and could not fail. What
            // can fail, and is what the retry arm actually owes, is that the cover never RISES: a
            // replacement that blanks the map on its way in is the regression this class guards.
            var coverRoseDuringRecovery: String? = null
            val installedNewer = awaitTag(
                hosted.mapView,
                R.id.map_fog_canonical_generation,
                RECOVERY_POLLS,
            ) { value ->
                val tags = readTags(hosted.mapView)
                if (coverRoseDuringRecovery == null && (tags.coverUp || tags.synchronousCoverUp)) {
                    coverRoseDuringRecovery =
                        "coverUp=${tags.coverUp} syncCoverUp=${tags.synchronousCoverUp} " +
                            "generation=${tags.generation} pendingSlot=${tags.pendingSlot}"
                }
                value != null && value != hosted.proven
            }
            assertTrue(
                "releasing the install fault never installed a newer generation, so the retry " +
                    "never recovered: " + describe(hosted.mapView),
                installedNewer,
            )
            val recovered = requireNotNull(
                tagOnMain(hosted.mapView, R.id.map_fog_canonical_generation),
            ).toString()
            // A regression guard on the id allocator, and recorded as no more than that: ids come
            // from `++nextGeneration` in `FogTileProviderAdapter`, so an abandoned id cannot be
            // reissued today and this line cannot fail in this run. It fails the day that changes.
            assertTrue(
                "an abandoned faulted attempt's id became the published generation: " +
                    "recovered=$recovered abandoned=$abandoned",
                recovered !in abandoned,
            )
            assertNull(
                "the retry raised the opaque cover on its way in, so the map was blanked while a " +
                    "complete proven generation was available to keep presenting: " +
                    coverRoseDuringRecovery + " " + describe(hosted.mapView),
                coverRoseDuringRecovery,
            )
            report(
                "fog_install_fault rejectedAttaches=$rejectedAttaches " +
                    "auditedFrames=${samples.size} publishedStates=${faultedStates.size} " +
                    "baseline=${percent(hosted.baseline)} " +
                    "worst=${percent(samples.minOf { sample -> sample.coveredFraction })} " +
                    "baselineCluster=${hosted.baselineCluster} " +
                    "worstCluster=${samples.maxOf { it.largestUncoveredCluster }}/" +
                    "${samples.first().analyzedPoints} " +
                    "abandonedGenerations=${abandoned.size}",
            )
        }
    }

    /**
     * An ordinary in-extent swap, audited on screen instead of in the diagnostics.
     *
     * A new track point is the way a walking user reaches this path, so it is the driver here too;
     * on this host it reaches the surface as what it is — a canonical change at a stationary camera
     * — instead of also flying the camera. Sampling starts a beat before the point lands and stops
     * after the published generation has moved, and the case refuses to pass unless frames were
     * captured on both sides of that move, unless at least one frame fell inside the in-flight
     * window the swap actually passes through, and unless the camera the floor was calibrated at is
     * still the camera those frames came from.
     */
    @Test
    fun anInExtentGenerationSwapFromANewPointNeverPresentsABareFrame() {
        assumeTrue(
            "auditing a real canonical swap requires the keyed googlePoc runtime",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        withSettledFogSurface { hosted ->
            val sampler = Sampler(hosted.mapView)
            val samplerFailure: Throwable?
            sampler.start()
            try {
                // A short pre-roll, so the "before" side of the swap is captured by construction
                // rather than by winning a race with the append on the very first capture.
                SystemClock.sleep(PRE_SWAP_ROLL_MILLIS)
                appendCanonicalPoint(hosted.dao, hosted.recording, sequence = 0L)
                val advanced = awaitTag(
                    hosted.mapView,
                    R.id.map_fog_canonical_generation,
                    GENERATION_POLLS,
                ) { value -> value != null && value != hosted.proven }
                assertTrue(
                    "the appended canonical point never advanced the published generation, so no " +
                        "swap was audited: " + describe(hosted.mapView),
                    advanced,
                )
                SystemClock.sleep(SWAP_SETTLE_MILLIS)
            } finally {
                samplerFailure = sampler.stop()
            }
            val samples = sampler.samples.toList()

            assertNull(
                "the screen sampler died instead of finishing the swap window: " +
                    samplerFailure?.stackTraceToString(),
                samplerFailure,
            )
            assertTrue(
                "the sampler captured ${samples.size} frames across the swap, too few to claim " +
                    "anything about what was presented during it",
                samples.size >= MINIMUM_SWAP_SAMPLES,
            )
            assertTrue(
                "no frame was captured before the generation advanced, so the sampler started " +
                    "after the swap it claims to audit",
                samples.any { sample -> sample.generation == hosted.proven },
            )
            assertTrue(
                "no frame was captured after the generation advanced, so the sampler stopped " +
                    "before the swap it claims to audit",
                samples.any { sample ->
                    sample.generation != null && sample.generation != hosted.proven
                },
            )
            // The in-flight witness. Without it every frame could come from either settled side and
            // the claim would be about two static scenes rather than about the transition between
            // them: the pending slot is published from `beginRebuild` and cleared at the proof, so a
            // frame carrying one is a frame taken while the replacement overlay was being installed.
            assertTrue(
                "no sampled frame carried a pending generation, so nothing was captured inside " +
                    "the install window this case claims to audit: " +
                    "frames=${samples.size} " + describe(hosted.mapView),
                samples.any { sample -> sample.pendingSlot != null },
            )
            // The floor is only a reference for these frames if the scene did not change under
            // them. This is the assertion whose absence made the first version of this case red.
            assertCameraNeverDrifted(hosted, "the swap window")
            assertBareFrameNeverPresented(
                hosted.baseline,
                hosted.baselineCluster,
                samples,
                hosted.mapView,
            )
            val settled = readTags(hosted.mapView)
            assertTrue(
                "the swap left the safety cover up: " + describe(hosted.mapView),
                !settled.coverUp && !settled.synchronousCoverUp,
            )
            report(
                "fog_generation_swap auditedFrames=${samples.size} " +
                    "inFlightFrames=${samples.count { sample -> sample.pendingSlot != null }} " +
                    "baseline=${percent(hosted.baseline)} " +
                    "worst=${percent(samples.minOf { sample -> sample.coveredFraction })} " +
                    "baselineCluster=${hosted.baselineCluster} " +
                    "worstCluster=${samples.maxOf { it.largestUncoveredCluster }}/" +
                    "${samples.first().analyzedPoints}",
            )
        }
    }

    /** The test-owned rejection, named so `map_fog_last_failure` can be matched against it. */
    private class InjectedCanonicalInstallFault :
        IllegalStateException("injected canonical overlay install rejection")

    /** One ACTIVE in-memory recording session; canonical points are appended to it. */
    private data class Recording(val sessionId: Long, val segmentId: Long)

    /** A hosted surface that has settled: proven, uncovered, quiet, and parked at a known camera. */
    private class HostedSurface(
        val mapView: MapView,
        val map: GoogleMap,
        val dao: RecordingDao,
        val recording: Recording,
        val proven: String,
        val camera: CameraPosition,
        val baseline: Double,
        val baselineCluster: Int,
    )

    /**
     * The published tags this class reads, captured together on the thread that writes them.
     *
     * All eight tag writes happen in one `SideEffect` on the main thread, so a batched read there
     * is both internally consistent and free of the torn `SparseArray` reads a worker thread can
     * see while that effect runs.
     */
    private data class PublishedTags(
        val generation: String?,
        val pendingSlot: String?,
        val coverUp: Boolean,
        val synchronousCoverUp: Boolean,
    )

    private data class ScreenSample(
        val coveredFraction: Double,
        val analyzedPoints: Int,
        val largestUncoveredCluster: Int,
        val generation: String?,
        val pendingSlot: String?,
        val coverUp: Boolean,
        val synchronousCoverUp: Boolean,
    )

    /**
     * Launches the unexported harness on a real DAO-backed runtime at a FIXED camera and hands the
     * case a settled surface.
     *
     * "Settled" is asserted, not assumed: the programmed flight has landed, the camera really is at
     * the requested point and zoom (a clamp that ate either would put every later frame at a scene
     * nobody chose), a generation is installed with both covers down, no rebuild is in flight, and
     * the same generation is still the installed one when the floor has finished being measured —
     * so the generation this case calls `proven` and the floor it compares frames against belong to
     * one scene, and neither can be superseded by work that was already running when it was read.
     *
     * The recording session is opened BEFORE the surface exists. It carries no points, so it cannot
     * produce a canonical revision, but opening it after the floor was measured would leave that
     * question to be argued instead of removed.
     */
    private fun withSettledFogSurface(body: (HostedSurface) -> Unit) {
        val db = inMemoryDatabase()
        database = db
        val dao = db.recordingDao()
        val recording = startRecording(dao)
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.fogRuntime =
            fogRuntime(db, RoomPersistedTrackPointChangeFeed(dao))
        // Explicit, so a transiently unvalidated network cannot turn this into a fallback surface
        // and report it as a fog finding. The key assumption above is the real precondition.
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequest = FIXED_CAMERA
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { view -> mapViewRef.set(view) }
        GoogleMapSurfaceTestHooks.onMapReady.set { map -> mapRef.set(map) }
        GoogleMapSurfaceTestHooks.onFogState.set { state -> fogStates += state }
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
            val mapView = awaitMapView()
            val map = awaitMap()
            val camera = awaitParkedCamera(map, mapView)
            val proven = awaitQuiescentProvenGeneration(mapView)
            val (baseline, baselineCluster) = calibrate(mapView)
            assertTrue(
                "only ${percent(baseline)} of the settled hosted map reads as fog or cover, so " +
                    "this oracle could not tell a bare frame from a covered one: " +
                    describe(mapView),
                baseline >= MINIMUM_SETTLED_COVERAGE,
            )
            val afterCalibration = readTags(mapView)
            assertTrue(
                "a rebuild landed while the settled floor was being measured, so the floor and " +
                    "the generation this case calls proven do not belong to the same scene: " +
                    "proven=$proven now=${afterCalibration.generation} " +
                    "pendingSlot=${afterCalibration.pendingSlot} " + describe(mapView),
                afterCalibration.generation == proven &&
                    afterCalibration.pendingSlot == null &&
                    !afterCalibration.coverUp &&
                    !afterCalibration.synchronousCoverUp,
            )
            body(
                HostedSurface(
                    mapView = mapView,
                    map = map,
                    dao = dao,
                    recording = recording,
                    proven = proven,
                    camera = camera,
                    baseline = baseline,
                    baselineCluster = baselineCluster,
                ),
            )
        }
    }

    /**
     * Every audited frame must stay within [COVERAGE_MARGIN] of the settled floor.
     *
     * The margin is not slack for a leak: it absorbs the chrome that legitimately changes between
     * calibration and the audit — most of all the fog-unavailable status badge, which the surface
     * displays by design while a replacement is retrying behind a proven generation.
     */
    private fun assertBareFrameNeverPresented(
        baseline: Double,
        baselineCluster: Int,
        samples: List<ScreenSample>,
        mapView: MapView,
    ) {
        val floor = baseline - COVERAGE_MARGIN
        val worst = samples.minByOrNull { sample -> sample.coveredFraction }
        val bare = worst == null || worst.coveredFraction < floor
        assertTrue(
            "a presented frame was bare: only ${percent(worst?.coveredFraction ?: 0.0)} of the " +
                "map read as fog or cover against a settled floor of ${percent(baseline)} " +
                "(minus a ${percent(COVERAGE_MARGIN)} chrome margin). frames=${samples.size} " +
                "analyzedPoints=${worst?.analyzedPoints} generation=${worst?.generation} " +
                "pendingSlot=${worst?.pendingSlot} coverUp=${worst?.coverUp} " +
                "syncCoverUp=${worst?.synchronousCoverUp} " + describe(mapView),
            !bare,
        )
        // The shape rule. The area rule above sums the whole frame, so a contiguous bare patch and
        // label pixels sprinkled across the map are the same number to it, and only one of them is
        // a defect. This asks a different question: how big is the largest single patch that read
        // as neither fog nor cover, measured against the one the settled floor already carries.
        val worstCluster = samples.maxOfOrNull { sample -> sample.largestUncoveredCluster } ?: 0
        // The floor has to leave the rule able to fire. It is a measurement of this scene, so a
        // scene whose settled frames already carry a tile-sized patch would push the ceiling above
        // the smallest defect this rule exists to catch and disable it silently. Asserted rather
        // than assumed, and asserted against the defect size rather than against the recorded
        // figure, so a noisier scene fails calibration instead of quietly relaxing the bound.
        assertTrue(
            "the settled floor at this camera already carries a contiguous uncovered patch of " +
                "$baselineCluster sampled cells, at or past the $TILE_HOLE_CELLS one uncovered " +
                "zoom-16 tile would occupy, so no shape bound derived from it could still catch " +
                "such a tile. frames=${samples.size} " + describe(mapView),
            baselineCluster < TILE_HOLE_CELLS,
        )
        // Relative to this scene's own floor, but never above the defect size: the floor keeps the
        // rule from firing on ordinary label variation, and the cap keeps a drifting floor from
        // lifting the rule past the hole it is for.
        val ceiling = minOf(baselineCluster + CLUSTER_MARGIN_CELLS, TILE_HOLE_CELLS - 1)
        assertTrue(
            "a presented frame carried one contiguous patch of $worstCluster sampled cells that " +
                "read as neither fog nor cover, past the $ceiling this scene allows " +
                "($baselineCluster measured on the settled floor at this same camera, plus a " +
                "$CLUSTER_MARGIN_CELLS cell margin). frames=${samples.size} " +
                "analyzedPoints=${samples.firstOrNull()?.analyzedPoints} " + describe(mapView),
            worstCluster <= ceiling,
        )
    }

    /** Deltas only; the absolute camera is never emitted. */
    private fun assertCameraNeverDrifted(hosted: HostedSurface, window: String) {
        val now = cameraOnMain(hosted.map)
        val latitudeDelta = abs(now.target.latitude - hosted.camera.target.latitude)
        val longitudeDelta = abs(now.target.longitude - hosted.camera.target.longitude)
        val zoomDelta = abs(now.zoom - hosted.camera.zoom)
        val stayed = latitudeDelta <= CAMERA_DRIFT_DEGREES &&
            longitudeDelta <= CAMERA_DRIFT_DEGREES &&
            zoomDelta <= CAMERA_DRIFT_ZOOM
        assertTrue(
            "the camera moved across $window, so the settled floor was calibrated at a different " +
                "scene from the frames it is compared against: " +
                "latDeltaDeg=$latitudeDelta lonDeltaDeg=$longitudeDelta zoomDelta=$zoomDelta " +
                describe(hosted.mapView),
            stayed,
        )
    }

    /**
     * The settled floor, as an area and as a shape.
     *
     * Both halves take the WORST of several settled frames - the lowest covered area and the
     * largest uncovered cluster - so neither is optimistic, and the shape half is what makes the
     * later cluster bound a measurement of this scene rather than a guess about it.
     */
    private fun calibrate(mapView: MapView): Pair<Double, Int> {
        val readings = ArrayList<Double>()
        var cluster = 0
        repeat(CALIBRATION_SAMPLES) {
            sampleScreen(mapView)?.let { sample ->
                readings += sample.coveredFraction
                if (sample.largestUncoveredCluster > cluster) {
                    cluster = sample.largestUncoveredCluster
                }
            }
            SystemClock.sleep(CALIBRATION_GAP_MILLIS)
        }
        check(readings.isNotEmpty()) { "no calibration frame could be captured" }
        return readings.min() to cluster
    }

    /**
     * The background capture loop, with the worker's own death made observable.
     *
     * The loop carries its own wall-clock deadline as well as the stop flag. A case killed by the
     * [timeout] rule never reaches [stop], and a capture loop that outlived its case would keep
     * taking full-screen bitmaps for the rest of the process and perturb every later case in the
     * shard; the deadline is what makes that impossible rather than unlikely.
     */
    private inner class Sampler(private val mapView: MapView) {
        val samples = CopyOnWriteArrayList<ScreenSample>()
        private val running = AtomicBoolean(true)
        private val failure = AtomicReference<Throwable?>(null)
        private val worker = Thread {
            val deadline = SystemClock.elapsedRealtime() + SAMPLER_LIFETIME_MILLIS
            try {
                while (running.get() && SystemClock.elapsedRealtime() < deadline) {
                    sampleScreen(mapView)?.let { sample -> samples += sample }
                }
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }

        fun start() = worker.start()

        /** Stops the loop and returns whatever killed the worker, or `null` if it died clean. */
        fun stop(): Throwable? {
            running.set(false)
            worker.join(SAMPLER_JOIN_MILLIS)
            return failure.get()
        }
    }

    /**
     * One screen capture reduced to a fog-or-cover fraction over the live map's own rectangle.
     *
     * A grid rather than every pixel: the capture rate is what makes a transient bare frame
     * observable at all, and a full-bitmap scan at this size costs more than another capture.
     *
     * The map's geometry and the published tags are read in ONE main-thread round trip, so the
     * labels a frame is filed under were written by the same composition that positioned it, and
     * the worker never touches the tag store the composition is writing.
     */
    private fun sampleScreen(mapView: MapView): ScreenSample? {
        val origin = IntArray(2)
        val size = IntArray(2)
        val tags = AtomicReference<PublishedTags?>(null)
        val exclusions = AtomicReference<List<Rect>>(emptyList())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getLocationOnScreen(origin)
            size[0] = mapView.width
            size[1] = mapView.height
            tags.set(publishedTags(mapView))
            // Everything in this window that is not the map, located live in the same round
            // trip as the geometry it is measured against, and all of it drawn ABOVE the fog
            // overlay by design so none of it can ever read as fog.
            //
            // `bars` is window-relative and `origin` is screen-relative, so the two agree only for
            // a window at the screen origin, which is what this unexported harness activity always
            // is. In a freeform or multi-window host the shift would be wrong; an over-wide band
            // would exclude map and an inverted one excludes nothing, and neither can turn a bare
            // frame into a covered one, so it degrades toward abstention rather than toward a
            // false pass.
            //
            // The shape rule below is why this matters. Measured on the API 36 AVD: with nothing
            // excluded, every settled frame carried one contiguous 224-cell uncovered patch, and
            // the SDK watermark and compass accounted for 17 of it. The rest is the system bars,
            // which this activity draws under - a full-width band across the top of the map. A
            // shape bound derived from a 200-cell band could not catch any hole smaller than the
            // band, which is most of them, so the band is removed instead of tolerated.
            val rects = ArrayList(SpikeCaptureSupport.liveExclusionRects(mapView).first)
            mapView.rootWindowInsets
                ?.getInsets(WindowInsets.Type.systemBars())
                ?.let { bars ->
                    // Window coordinates, shifted into the MapView's own. An empty or inverted
                    // rect - which is what a MapView already inset by the bars produces - contains
                    // nothing, so a surface that does not draw under them costs nothing here.
                    rects += Rect(0, 0, mapView.width, bars.top - origin[1])
                    rects += Rect(
                        0,
                        mapView.rootView.height - bars.bottom - origin[1],
                        mapView.width,
                        mapView.height,
                    )
                }
            exclusions.set(rects)
        }
        if (size[0] <= 0 || size[1] <= 0) return null
        val published = tags.get() ?: return null
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: return null
        return try {
            tally(bitmap, origin, size, exclusions.get())?.let { counted ->
                ScreenSample(
                    coveredFraction = counted.covered.toDouble() / counted.analyzed,
                    analyzedPoints = counted.analyzed,
                    largestUncoveredCluster = counted.largestUncoveredCluster,
                    generation = published.generation,
                    pendingSlot = published.pendingSlot,
                    coverUp = published.coverUp,
                    synchronousCoverUp = published.synchronousCoverUp,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * What one frame read as, both as an area and as a shape.
     *
     * The area alone cannot separate a contiguous bare seam from noise spread thinly over the
     * whole map, and the two have opposite meanings: label pixels the fog family does not claim
     * are expected everywhere, while a hole big enough to see is the defect this class exists to
     * catch. [largestUncoveredCluster] is the shape half.
     */
    private data class ScreenTally(
        val analyzed: Int,
        val covered: Int,
        val largestUncoveredCluster: Int,
    )

    private fun tally(
        bitmap: Bitmap,
        origin: IntArray,
        size: IntArray,
        exclusions: List<Rect>,
    ): ScreenTally? {
        var analyzed = 0
        var covered = 0
        val uncovered = BooleanArray(GRID_ROWS * GRID_COLUMNS)
        val row = IntArray(bitmap.width)
        for (gridY in 0 until GRID_ROWS) {
            val withinMapY = size[1] * (2 * gridY + 1) / (2 * GRID_ROWS)
            val y = origin[1] + withinMapY
            if (y < 0 || y >= bitmap.height) continue
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (gridX in 0 until GRID_COLUMNS) {
                val withinMapX = size[0] * (2 * gridX + 1) / (2 * GRID_COLUMNS)
                val x = origin[0] + withinMapX
                if (x < 0 || x >= bitmap.width) continue
                if (exclusions.any { rect -> rect.contains(withinMapX, withinMapY) }) continue
                analyzed += 1
                if (isFogOrCover(row[x])) covered += 1 else uncovered[gridY * GRID_COLUMNS + gridX] = true
            }
        }
        return if (analyzed == 0) {
            null
        } else {
            ScreenTally(analyzed, covered, largestUncoveredCluster(uncovered))
        }
    }

    /**
     * Size of the largest 4-connected run of sampled cells that read as neither fog nor cover.
     *
     * A cell that was never sampled - one whose grid point fell outside the screenshot - is left
     * false, so it is a barrier rather than a bridge: an unsampled band can never join two
     * unrelated holes into one apparently larger one.
     */
    private fun largestUncoveredCluster(uncovered: BooleanArray): Int {
        val seen = BooleanArray(uncovered.size)
        val stack = IntArray(uncovered.size)
        var largest = 0
        for (start in uncovered.indices) {
            if (!uncovered[start] || seen[start]) continue
            var depth = 0
            var size = 0
            seen[start] = true
            stack[depth++] = start
            while (depth > 0) {
                val index = stack[--depth]
                size += 1
                val gridX = index % GRID_COLUMNS
                if (gridX > 0) {
                    val left = index - 1
                    if (uncovered[left] && !seen[left]) { seen[left] = true; stack[depth++] = left }
                }
                if (gridX < GRID_COLUMNS - 1) {
                    val right = index + 1
                    if (uncovered[right] && !seen[right]) { seen[right] = true; stack[depth++] = right }
                }
                val up = index - GRID_COLUMNS
                if (up >= 0 && uncovered[up] && !seen[up]) { seen[up] = true; stack[depth++] = up }
                val down = index + GRID_COLUMNS
                if (down < uncovered.size && uncovered[down] && !seen[down]) {
                    seen[down] = true
                    stack[depth++] = down
                }
            }
            if (size > largest) largest = size
        }
        return largest
    }

    /** Fog tiles carry the generation palette; the safety cover carries its own single colour. */
    private fun isFogOrCover(pixel: Int): Boolean =
        SpikeCaptureSupport.isFogFamily(pixel) || isSafetyCover(pixel)

    /** The exact drawable colour asserted by `GoogleProductionLauncherMapHostTest`, same tolerance. */
    private fun isSafetyCover(pixel: Int): Boolean =
        abs(Color.red(pixel) - COVER_RED) <= COVER_TOLERANCE &&
            abs(Color.green(pixel) - COVER_GREEN) <= COVER_TOLERANCE &&
            abs(Color.blue(pixel) - COVER_BLUE) <= COVER_TOLERANCE

    // ROOT, not the default locale: this string goes into an instrumentation status stream
    // that later runs are compared against, so a decimal separator that follows the device
    // would make the same measurement read differently on different images.
    private fun percent(fraction: Double): String =
        String.format(java.util.Locale.ROOT, "%.1f%%", fraction * 100.0)

    private fun report(stream: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", stream + "\n") },
        )
    }

    /**
     * One ACTIVE session in this case's own in-memory database.
     *
     * `appendAcceptedPoint` only accepts points into an ACTIVE session. Nothing outside this case
     * reads this database — the surface under test was handed a runtime built on it — so unlike a
     * launcher-hosted case there is no app state to leave behind and none to clean up.
     */
    private fun startRecording(dao: RecordingDao): Recording {
        val started = runBlocking {
            dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = FIXTURE_TIMESTAMP,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "google-v02-007-test",
                ),
                initialSegment = TrackSegmentEntity(
                    sessionId = 0L,
                    sequence = 0L,
                    startedAt = FIXTURE_TIMESTAMP,
                    startReason = "SESSION_START",
                ),
            )
        }
        return Recording(sessionId = started.sessionId, segmentId = started.segmentId)
    }

    /** The data-only stimulus: one accepted point at the parked camera's own centre. */
    private fun appendCanonicalPoint(dao: RecordingDao, recording: Recording, sequence: Long): Long =
        runBlocking {
            dao.appendAcceptedPoint(
                point = TrackPointEntity(
                    sessionId = recording.sessionId,
                    segmentId = recording.segmentId,
                    sequence = sequence,
                    timestamp = FIXTURE_TIMESTAMP + sequence * 1_000L,
                    latitude = FIXTURE_POINT.latitude,
                    longitude = FIXTURE_POINT.longitude,
                    horizontalAccuracy = 5.0,
                ),
                distanceDeltaMeters = 10.0,
            )
        }

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return condition()
    }

    private fun awaitTag(
        mapView: MapView,
        key: Int,
        polls: Int,
        predicate: (Any?) -> Boolean,
    ): Boolean {
        repeat(polls) {
            if (predicate(tagOnMain(mapView, key))) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return predicate(tagOnMain(mapView, key))
    }

    /**
     * A proven generation with both covers down and NO rebuild in flight, held across several polls.
     *
     * The quiet conjunct is what makes the returned id usable as "the generation that was installed
     * when this case started": without it a rebuild that was already running could install moments
     * later and be misread as the faulted replacement getting through.
     */
    private fun awaitQuiescentProvenGeneration(mapView: MapView): String {
        var stable = 0
        var seen: String? = null
        repeat(GENERATION_POLLS) {
            val published = readTags(mapView)
            val generation = published.generation
            val quiet = generation != null &&
                published.pendingSlot == null &&
                !published.coverUp &&
                !published.synchronousCoverUp
            stable = if (quiet && generation == seen) stable + 1 else 0
            seen = generation
            if (quiet && stable >= QUIET_POLLS) return requireNotNull(generation)
            SystemClock.sleep(GENERATION_POLL_MILLIS)
        }
        error("canonical fog never settled on the hosted surface: " + describe(mapView))
    }

    /**
     * The camera the floor and every audited frame belong to.
     *
     * Asserts the requested camera was actually REACHED, not merely requested: the SDK clamps a
     * camera it cannot honour and reports the clamped one, so a case that only asked would audit
     * whatever scene it happened to get.
     */
    private fun awaitParkedCamera(map: GoogleMap, mapView: MapView): CameraPosition {
        var previous: CameraPosition? = null
        var stable = 0
        repeat(CAMERA_POLLS) {
            val current = cameraOnMain(map)
            val airborne = tagOnMain(mapView, R.id.map_camera_flight_active) == true
            val still = previous != null &&
                abs(current.target.latitude - requireNotNull(previous).target.latitude) <=
                CAMERA_DRIFT_DEGREES &&
                abs(current.target.longitude - requireNotNull(previous).target.longitude) <=
                CAMERA_DRIFT_DEGREES &&
                abs(current.zoom - requireNotNull(previous).zoom) <= CAMERA_DRIFT_ZOOM
            stable = if (!airborne && still) stable + 1 else 0
            previous = current
            if (stable >= QUIET_POLLS) {
                val latitudeDelta = abs(current.target.latitude - FIXTURE_POINT.latitude)
                val longitudeDelta = abs(current.target.longitude - FIXTURE_POINT.longitude)
                val zoomDelta = abs(current.zoom - FIXTURE_ZOOM.toFloat())
                val arrived = latitudeDelta <= CAMERA_ARRIVAL_DEGREES &&
                    longitudeDelta <= CAMERA_ARRIVAL_DEGREES &&
                    zoomDelta <= CAMERA_ARRIVAL_ZOOM
                assertTrue(
                    "the hosted camera settled somewhere other than the requested fixture " +
                        "camera, so every frame this case audits comes from a scene it did not " +
                        "choose: latDeltaDeg=$latitudeDelta lonDeltaDeg=$longitudeDelta " +
                        "zoomDelta=$zoomDelta " + describe(mapView),
                    arrived,
                )
                return current
            }
            SystemClock.sleep(GENERATION_POLL_MILLIS)
        }
        error("the hosted camera never stopped moving: " + describe(mapView))
    }

    private fun cameraOnMain(map: GoogleMap): CameraPosition {
        val position = AtomicReference<CameraPosition?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { position.set(map.cameraPosition) }
        return requireNotNull(position.get()) { "the SDK returned no camera position" }
    }

    private fun awaitMapView(): MapView {
        repeat(MAP_VIEW_POLLS) {
            mapViewRef.get()?.let { return it }
            SystemClock.sleep(GENERATION_POLL_MILLIS)
        }
        error("the hosted surface never created a Google MapView")
    }

    private fun awaitMap(): GoogleMap {
        repeat(MAP_VIEW_POLLS) {
            mapRef.get()?.let { return it }
            SystemClock.sleep(GENERATION_POLL_MILLIS)
        }
        error("the hosted surface never reported a ready GoogleMap")
    }

    /** One keyed tag, read on the thread the composition writes it from. */
    private fun tagOnMain(mapView: MapView, key: Int): Any? {
        val value = AtomicReference<Any?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value.set(mapView.getTag(key)) }
        return value.get()
    }

    /** The four install-state tags in one main-thread round trip. */
    private fun readTags(mapView: MapView): PublishedTags {
        val captured = AtomicReference<PublishedTags?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            captured.set(publishedTags(mapView))
        }
        return requireNotNull(captured.get())
    }

    /** Main thread only; every caller reaches it through [readTags] or [sampleScreen]. */
    private fun publishedTags(mapView: MapView): PublishedTags = PublishedTags(
        generation = mapView.getTag(R.id.map_fog_canonical_generation) as? String,
        pendingSlot = mapView.getTag(R.id.map_fog_active_slot) as? String,
        coverUp = mapView.getTag(R.id.map_fog_cover_up) == true,
        synchronousCoverUp = mapView.getTag(R.id.map_fog_synchronous_cover_up) == true,
    )

    /** Counts, ids and flags only; nothing positional. */
    private fun describeStates(states: List<GoogleCanonicalFogState>): String =
        "[states=${states.size} " +
            "installed=${states.map { state -> state.installedGeneration }.distinct()} " +
            "pending=${states.map { state -> state.pendingGeneration }.distinct()} " +
            "coverUp=${states.map { state -> state.coverUp }.distinct()} " +
            "coverReason=${states.map { state -> state.coverReason }.distinct()} " +
            "retry=${states.map { state -> state.retryScheduled }.distinct()} " +
            "terminal=${states.map { state -> state.terminal }.distinct()}]"

    /**
     * Booleans, names, ids and counts only; nothing positional.
     *
     * Assembled inside one main-thread round trip, and never passed to an assertion before the
     * condition it explains has been evaluated: a diagnostic built from the state BEFORE a wait
     * describes the wrong instant, and Kotlin evaluates a message argument eagerly.
     */
    private fun describe(mapView: MapView): String {
        val description = AtomicReference("")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            description.set(
                "[binding=${mapView.getTag(R.id.map_fog_binding_state)} " +
                    "phase=${mapView.getTag(R.id.map_fog_phase)} " +
                    "gates=[${mapView.getTag(R.id.map_fog_binding_gates)}] " +
                    "lastFogFailure=${mapView.getTag(R.id.map_fog_last_failure)} " +
                    "basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
                    "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
                    "pendingSlot=${mapView.getTag(R.id.map_fog_active_slot)} " +
                    "cover=${mapView.getTag(R.id.map_fog_cover_up)} " +
                    "syncCover=${mapView.getTag(R.id.map_fog_synchronous_cover_up)} " +
                    "coverIntervalMs=${mapView.getTag(R.id.map_fog_last_cover_interval_ms)} " +
                    "flightActive=${mapView.getTag(R.id.map_camera_flight_active)} " +
                    "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown}]",
            )
        }
        return description.get()
    }

    private companion object {
        const val POLL_MILLIS = 100L
        const val GENERATION_POLL_MILLIS = 250L
        const val GENERATION_POLLS = 180
        const val TAG_POLLS = 180

        /**
         * The recovery wait, deliberately longer than [TAG_POLLS].
         *
         * A generation that installs after the fault is released costs a render, an attach, a
         * delivery barrier and a snapshot proof, and it starts immediately after several seconds of
         * screenshot hammering. Budgeting it below what the CHEAPER first generation is allowed
         * ([GENERATION_POLLS]) measured the harness, not the product.
         */
        const val RECOVERY_POLLS = 450

        const val MAP_VIEW_POLLS = 120
        const val CAMERA_POLLS = 180

        /** Consecutive unchanged polls a state must hold before it counts as settled. */
        const val QUIET_POLLS = 3

        /** Roughly twice the sum of every deadline a single case here can serialise. */
        const val CASE_TIMEOUT_SECONDS = 480L

        const val FAULT_TIMEOUT_MILLIS = 30_000L

        /** Several retry ticks, and far short of the binding's 20 s cover deadline. */
        const val FAULTED_WINDOW_MILLIS = 5_000L
        const val MINIMUM_REJECTED_ATTACHES = 2
        const val MINIMUM_FAULTED_SAMPLES = 4
        const val MINIMUM_SWAP_SAMPLES = 8
        const val PRE_SWAP_ROLL_MILLIS = 600L
        const val SWAP_SETTLE_MILLIS = 1_500L
        const val SAMPLER_JOIN_MILLIS = 10_000L

        /** A capture loop may never outlive the case that started it, however that case ended. */
        const val SAMPLER_LIFETIME_MILLIS = 120_000L

        const val CALIBRATION_SAMPLES = 4
        const val CALIBRATION_GAP_MILLIS = 150L

        /** A settled map below this is chrome, not fog, and cannot host a bare-frame oracle. */
        const val MINIMUM_SETTLED_COVERAGE = 0.60
        const val COVERAGE_MARGIN = 0.08

        /**
         * How much larger than the settled floor's own largest uncovered patch an audited frame's
         * patch may be, in sampled grid cells.
         *
         * The floor is measured in the same run at the same camera, on a frame with a proven
         * generation installed and both covers down, so it already carries whatever this scene
         * legitimately shows through the fog - Google's labels composite above the tile overlay by
         * design. Recorded on the API 36 Play Store AVD with the system bars and the SDK's
         * watermark and compass excluded: 3,440 sampled cells, a settled floor of 84.8 % covered,
         * and a largest uncovered patch of **36** cells. Both cases' audited frames measured 36 as
         * well, so neither the faulted install nor the swap adds anything to it.
         *
         * The margin is set from the smallest defect that must fail, not from that zero delta:
         * [TILE_HOLE_CELLS]. A margin of 40 puts the alarm at 76 on a 36-cell floor, below the 88
         * cells the smallest such hole is guaranteed to occupy. The floor is not perfectly stable -
         * a later run measured 49, which would put the alarm at 89 and let that hole through - so
         * the ceiling is additionally capped one cell below the hole size, and the floor itself is
         * asserted to sit under it, rather than trusting the recorded figure to stay where it was.
         */
        const val CLUSTER_MARGIN_CELLS = 40

        /**
         * One uncovered zoom-16 tile, in sampled cells: the smallest hole that must always fail.
         *
         * The GUARANTEED count, not the average one. A tile is 256 px square; the grid samples this
         * 1080 x 2400 map every 1080/48 = 22.5 px across and every 2400/80 = 30 px down. An
         * interval 256 px long contains at least `floor(256 / spacing)` sample points wherever it
         * happens to fall, so a tile-sized hole is guaranteed to cover at least 11 columns by 8
         * rows: **88** cells. It averages nearer 97 and can reach 108, but a bound built on the
         * average lets an unluckily aligned tile through, which is the one alignment an attacker of
         * this rule would pick.
         */
        const val TILE_HOLE_CELLS = 88
        const val GRID_COLUMNS = 48
        const val GRID_ROWS = 80
        const val COVER_RED = 0x3C
        const val COVER_GREEN = 0x3D
        const val COVER_BLUE = 0x3A
        const val COVER_TOLERANCE = 2

        /** Nobody moves this camera, so two reads of it must agree to well inside a rendered pixel. */
        const val CAMERA_DRIFT_DEGREES = 1.0e-5
        const val CAMERA_DRIFT_ZOOM = 1.0e-3f

        /** What "the requested camera was actually reached" allows: metres, not scenes. */
        const val CAMERA_ARRIVAL_DEGREES = 1.0e-4
        const val CAMERA_ARRIVAL_ZOOM = 0.01f

        const val FIXTURE_TIMESTAMP = 9_013_200_000L
        const val FIXTURE_ZOOM = 16.0
        val FIXTURE_POINT = GeoPoint(latitude = 25.0330, longitude = 121.5654)
        val FIXED_CAMERA = MapCameraRequest(
            requestId = 1L,
            point = FIXTURE_POINT,
            zoom = FIXTURE_ZOOM,
        )
    }
}
