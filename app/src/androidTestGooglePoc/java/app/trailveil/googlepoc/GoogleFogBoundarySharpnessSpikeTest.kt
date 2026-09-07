package app.trailveil.googlepoc

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.R
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.map.GoogleFogCoverageArm
import app.trailveil.map.GoogleMapSurfaceTestActivity
import app.trailveil.map.GoogleMapSurfaceTestHooks
import app.trailveil.map.ProviderStartupDecision
import app.trailveil.map.fogRuntime
import app.trailveil.map.inMemoryDatabase
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * `V03-011`'s remaining owner question, measured: what does the reveal boundary LOOK like at high
 * zoom under each arm?
 *
 * The owner asked it first, verbatim, on 2026-09-06: whether moving to vectors would remove the
 * jagged edge. Prototype A does not move to vectors - it keeps today's raster mask - but it changes
 * something the question is really about. **Sections 15j and 15k measured that an in-extent zoom
 * under arm 2 builds no new generation.** That is the whole benefit; it is also the whole cost,
 * because the image the surface keeps showing was rasterised for the zoom it was planned at, and
 * `FogOverlaySurfaceCoordinator.onCameraIdle` has nothing to re-plan for while the camera stays
 * inside the published surround. The tile path has the opposite behaviour for the same reason its
 * cover rises: its keys carry a zoom, so it re-plans and re-renders at the new one and comes back
 * sharp.
 *
 * **What is not derivable, and is the reason this is a device measurement rather than arithmetic.**
 * A mask pixel stretched by 2^1.873 covers about 3.66 screen pixels, and that much is arithmetic.
 * Whether the result reads as a hard staircase or as a soft blur is a property of how Google's SDK
 * resamples a `GroundOverlay`, which is undocumented and has no equivalent of MapLibre's
 * `rasterResampling(NEAREST)`. Those are different answers to the owner's question - one is a
 * coarser version of today's edge, the other is a different-looking edge entirely.
 *
 * ### The oracle, and why the evidence is a mask rather than a screenshot
 *
 * The acceptance criterion asks for coordinate-free screenshots. A screenshot of a real basemap
 * zoomed in on a fixture location is not coordinate-free in any useful sense, and it also buries
 * the thing being looked at under map detail. So what is saved is the fog/non-fog CLASSIFICATION of
 * a crop around the boundary - two colours, no basemap, `SpikeCaptureSupport.isFogFamily` as the
 * classifier - which is exactly the edge under discussion and carries nothing else.
 *
 * The number beside it is the staircase step: for each row of the crop, the leftmost non-fog column
 * is the boundary's position in that row, and consecutive rows sharing a position form a run. On a
 * circular boundary - one reveal disc, so every angle is present - the mean run length in pixels IS
 * the size of one mask pixel on screen. A sharp arm reads near 1; an arm showing a stretched image
 * reads near the stretch factor; a blurred edge reads differently again, because the classifier
 * finds its threshold crossing at a position that moves smoothly instead of in steps.
 *
 * **Opt-in and non-asserting**, like the other two arm spikes: it emits numbers and saves masks. The
 * only assertions are that each arm produced a reading at all and that the scene contained a
 * boundary to measure, because a run that measured nothing must not read as a run that measured no
 * difference.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogBoundarySharpnessSpikeTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(CASE_TIMEOUT_SECONDS)

    @After
    fun tearDown() {
        GoogleMapSurfaceTestHooks.reset()
        GoogleFogCoverageArm.reset()
    }

    @Test
    fun theRevealBoundaryIsMeasuredAtHighZoomUnderBothArms() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "the fog arm spike is opt-in; pass $SPIKE_ARGUMENT=true",
            arguments.getString(SPIKE_ARGUMENT) == "true",
        )
        SpikeScenarioSupport.assumeKeyConfigured()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arms = listOf(FogContinuityArm.BASELINE, FogContinuityArm.MOSAIC_OVERLAY)
        val measured = mutableListOf<String>()
        arms.forEach { arm ->
            val readings = measureArm(arm)
            readings.forEach { (stage, reading) ->
                measured += "V03-011-BOUNDARY arm=${arm.label} stage=$stage ${reading.describe()}"
            }
        }
        measured.forEach { line -> SpikeEvidence.emit(context, EVIDENCE_FILE, line) }

        val expected = arms.size * STAGES
        assertTrue(
            "every arm must have produced a reading at the plan zoom and at the zoomed-in camera; " +
                "a run that measured nothing must not be read as a run that measured no " +
                "difference. Got ${measured.size} of $expected",
            measured.size == expected,
        )
    }

    // ---- the scene -------------------------------------------------------------------------------

    private fun measureArm(arm: FogContinuityArm): List<Pair<String, BoundaryReading>> {
        val database = inMemoryDatabase()
        val readings = mutableListOf<Pair<String, BoundaryReading>>()
        try {
            revealOneDisc(database)
            GoogleMapSurfaceTestHooks.reset()
            GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
            GoogleMapSurfaceTestHooks.fogRequired = true
            GoogleMapSurfaceTestHooks.fogRuntime = fogRuntime(
                database,
                RoomPersistedTrackPointChangeFeed(database.recordingDao()),
            )
            val mapReady = CountDownLatch(1)
            val mapRef = AtomicReference<GoogleMap>()
            GoogleMapSurfaceTestHooks.onMapReady.set { readyMap ->
                mapRef.set(readyMap)
                mapReady.countDown()
            }
            // BEFORE the Activity launches: a binding reads its arm once, in its field
            // initialisers, so a surface already attached would stay on the old one.
            arm.install()

            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
                assertTrue(
                    "${arm.label}: the hosted surface never produced a Google map",
                    mapReady.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                val map = requireNotNull(mapRef.get())
                val mapView = awaitMapView(scenario, arm)
                assumeTrue(
                    "${arm.label}: the hosted Google basemap never reported loaded, so there was " +
                        "no settled scene to measure; abstaining rather than reporting a pass",
                    awaitTag(mapView, R.id.map_basemap_load_state) {
                        it == GestureExposureHarness.ONLINE_STATE
                    },
                )
                assertTrue(
                    "${arm.label}: the surface loaded its basemap but never published a canonical " +
                        "fog generation, so no boundary below could be told from a generation " +
                        "that never installed",
                    awaitTag(mapView, R.id.map_fog_canonical_generation) { it != null },
                )

                val capturer = GestureSurfaceCapturer(mapView)
                try {
                    val channel = capturer.open()
                    assertTrue(
                        "${arm.label}: the readback channel is $channel, so no frame below would " +
                            "carry pixels to classify. " + capturer.describe(),
                        channel != GestureExposurePixels.UNJUDGED_CHANNEL,
                    )
                    // The plan reading. Reached from wherever the surface opened, so it is a
                    // camera change that leaves the surround under BOTH arms - both re-plan, both
                    // land sharp, and the two arms are therefore being compared at a scene they
                    // agree on before anything is asked of them.
                    readings += "plan" to readAt(
                        arm = arm,
                        stage = "plan",
                        scenario = scenario,
                        map = map,
                        mapView = mapView,
                        capturer = capturer,
                        zoom = PLAN_ZOOM,
                    )
                    // The zoomed-in reading, reached by a real injected SPREAD.
                    //
                    // Not `moveCamera`, and the first run of this spike is why. `fogCameraReaction`
                    // returns `REBUILD_AT_IDLE` for any move whose reason is not GESTURE, even one
                    // that stays inside the published surround - so a programmatic zoom marks the
                    // viewport dirty and rebuilds under EVERY arm. That run produced
                    // `generation=3` and `meanRunPx` of 3.07 and 3.10 for the two arms: a real
                    // measurement of two surfaces that had both just re-planned, and no
                    // measurement at all of the thing this case exists for. A gesture returns
                    // `LEAVE_PUBLISHED_COVERAGE` and leaves the decision to the per-frame surround
                    // check, which is the branch arm 2's whole claim lives in.
                    readings += "zoomed" to readAfterSpread(
                        arm = arm,
                        map = map,
                        mapView = mapView,
                        capturer = capturer,
                    )
                } finally {
                    capturer.close()
                }
            }
        } finally {
            GoogleMapSurfaceTestHooks.reset()
            GoogleFogCoverageArm.reset()
            database.close()
        }
        return readings
    }

    private fun readAt(
        arm: FogContinuityArm,
        stage: String,
        scenario: ActivityScenario<GoogleMapSurfaceTestActivity>,
        map: GoogleMap,
        mapView: MapView,
        capturer: GestureSurfaceCapturer,
        zoom: Float,
    ): BoundaryReading {
        val where = "${arm.label}/$stage"
        val position = CameraPosition.Builder()
            .target(DISC_CENTRE)
            .zoom(zoom)
            .build()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
        }
        val settled = awaitStableCamera(map)
        assertTrue(
            "$where: the SDK applied zoom ${"%.3f".format(settled.zoom)} for a requested " +
                "${"%.3f".format(zoom)}; a clamped camera would measure a different scale from " +
                "the one this stage is named for",
            abs(settled.zoom - zoom) <= ZOOM_TOLERANCE,
        )
        return settleAndCapture(arm, stage, mapView, capturer, settled.zoom)
    }

    /**
     * The same reading, reached by an injected two-finger spread instead of by `moveCamera`.
     *
     * The gesture is what makes the stage in-extent in the sense the coordinator means: a spread
     * shrinks the ground the camera sees, so it cannot leave a rectangle it was already inside, and
     * `fogCameraReaction`'s GESTURE branch leaves the rebuild decision to the per-frame surround
     * check rather than marking the viewport dirty unconditionally. Under the baseline the check
     * still fails, because a coverage key carries the zoom it was planned at; under the mosaic it
     * passes, and the image the surface keeps showing is the one rasterised for the OLD zoom. The
     * difference between those two boundaries is what this whole class exists to look at.
     */
    private fun readAfterSpread(
        arm: FogContinuityArm,
        map: GoogleMap,
        mapView: MapView,
        capturer: GestureSurfaceCapturer,
    ): BoundaryReading {
        val origin = IntArray(2)
        val size = AtomicReference<Pair<Int, Int>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getLocationOnScreen(origin)
            size.set(mapView.width to mapView.height)
        }
        val measured = requireNotNull(size.get())
        val before = cameraOf(map).zoom
        val attempt = SpikePinchGesture.drive(
            label = "spread/${arm.label}",
            origin = origin,
            width = measured.first.toFloat(),
            height = measured.second.toFloat(),
            pointerYFraction = SpikePinchGesture.POINTER_Y_FRACTION,
            spans = SpikePinchGesture.SPREAD_SPANS,
            minimumZoomChange = ZOOM_STEP,
            zoom = { cameraOf(map).zoom },
            diagnostics = { mapView.getTag(R.id.map_fog_binding_gates)?.toString() ?: "no-binding" },
        )
        val settled = awaitStableCamera(map)
        assertTrue(
            "${arm.label}/zoomed: the spread moved the camera by " +
                "${"%.3f".format(settled.zoom - before)} levels, under the " +
                "${"%.3f".format(ZOOM_STEP)} this stage needs to cross an integer zoom boundary " +
                "with room to spare. ${attempt.note}",
            settled.zoom - before >= ZOOM_STEP,
        )
        return settleAndCapture(arm, "zoomed", mapView, capturer, settled.zoom)
    }

    private fun settleAndCapture(
        arm: FogContinuityArm,
        stage: String,
        mapView: MapView,
        capturer: GestureSurfaceCapturer,
        zoom: Float,
    ): BoundaryReading {
        val where = "${arm.label}/$stage"
        // Whatever the arm does, the reading is taken with the cover DOWN: a covered frame is a
        // measurement of the cover's own edge, which has no boundary in it at all.
        assertTrue(
            "$where: the safety cover was still up ${COVER_SETTLE_MILLIS}ms after the camera " +
                "settled, so there was no map to measure a boundary on. " +
                (mapView.getTag(R.id.map_fog_binding_gates)?.toString() ?: "no-binding"),
            awaitTag(mapView, R.id.map_fog_cover_up, COVER_SETTLE_POLLS) { it == false },
        )
        SystemClock.sleep(SETTLE_MILLIS)

        var best: BoundaryReading? = null
        repeat(CAPTURE_ATTEMPTS) {
            val bitmap = capturer.capture()
            if (bitmap != null) {
                val frame = Frame.of(bitmap)
                val measurement = measureBoundary(frame)
                best = measurement
                if (measurement.rows >= MINIMUM_BOUNDARY_ROWS) {
                    return measurement.copy(
                        savedAs = saveMask(frame, "$EVIDENCE_PREFIX-${arm.label}-$stage.png"),
                        generation = mapView.getTag(R.id.map_fog_canonical_generation)?.toString(),
                        installer = installerOf(mapView),
                        zoom = zoom,
                    )
                }
            }
            SystemClock.sleep(CAPTURE_RETRY_MILLIS)
        }
        throw AssertionError(
            "$where: no capture carried a boundary with at least $MINIMUM_BOUNDARY_ROWS rows of " +
                "fog-to-revealed transition inside the analysed band, so there was nothing to " +
                "measure. Either the reveal never reached the screen or the disc is off the band " +
                "at this zoom. Best attempt: " + (best?.describe() ?: "no frame captured at all"),
        )
    }

    /** One readback, unpacked once: `getPixel` per pixel over a full band costs seconds. */
    private class Frame(private val pixels: IntArray, val width: Int, val height: Int) {
        val left = (width * BAND_LEFT_FRACTION).toInt()
        val right = (width * BAND_RIGHT_FRACTION).toInt()
        val top = (height * BAND_TOP_FRACTION).toInt()
        val bottom = (height * BAND_BOTTOM_FRACTION).toInt()

        fun isFog(x: Int, y: Int): Boolean =
            SpikeCaptureSupport.isFogFamily(pixels[y * width + x])

        /**
         * A pixel that is ALMOST the fog colour but not the fog colour: a blend.
         *
         * The staircase metric cannot tell a sharp coarse edge from a smooth soft one - both let
         * the classified boundary move nearly every row, for opposite reasons - and the first run
         * of this spike measured almost the same `meanRunPx` under both arms, which is what raised
         * the question. A raster edge drawn at its own resolution is one pixel of fog beside one
         * pixel of basemap with nothing between them; an edge the SDK stretched and interpolated
         * has a band of mixtures. Counting that band is what separates the two.
         */
        fun isBlend(x: Int, y: Int): Boolean {
            val pixel = pixels[y * width + x]
            return !SpikeCaptureSupport.isFogFamily(pixel) &&
                SpikeCaptureSupport.isFogFamily(pixel, BLEND_TOLERANCE)
        }

        companion object {
            fun of(bitmap: Bitmap): Frame {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                return Frame(pixels, width, height)
            }
        }
    }

    // ---- the measurement -------------------------------------------------------------------------

    /**
     * The boundary's position per row, and how long it stays put.
     *
     * [meanRunPx] is the headline: on a circular boundary every angle is present, so a run of
     * identical leftmost-revealed columns across N rows means one mask pixel is N screen pixels
     * tall. It is a ratio between arms rather than an absolute - the same scene measured under both
     * is what the comparison rests on.
     */
    private data class BoundaryReading(
        val rows: Int,
        val steps: Int,
        val meanRunPx: Double,
        val maxRunPx: Int,
        val distinctColumns: Int,
        val fogPct: Double,
        val revealedPct: Double,
        /** Pixels that are a MIXTURE of fog and basemap. See [Frame.isBlend]. */
        val blendPx: Int,
        val blendPerRowPx: Double,
        val bandWidthPx: Int,
        val bandHeightPx: Int,
        val savedAs: String? = null,
        val generation: String? = null,
        val installer: String? = null,
        val zoom: Float = 0f,
    ) {
        fun describe(): String =
            "zoom=${"%.3f".format(zoom)} band=${bandWidthPx}x$bandHeightPx " +
                "boundaryRows=$rows steps=$steps meanRunPx=${"%.2f".format(meanRunPx)} " +
                "maxRunPx=$maxRunPx distinctColumns=$distinctColumns " +
                "fogPct=${"%.2f".format(fogPct)} revealedPct=${"%.2f".format(revealedPct)} " +
                "blendPx=$blendPx blendPerRowPx=${"%.2f".format(blendPerRowPx)} " +
                "generation=$generation installer=[$installer] mask=$savedAs"
    }

    /**
     * Classifies the analysed band into fog and not-fog, then reads the left edge row by row.
     *
     * A row counts only when it has fog before the transition, so a row where the revealed disc
     * runs off the left of the band contributes nothing rather than contributing column zero.
     */
    private fun measureBoundary(frame: Frame): BoundaryReading {
        val left = frame.left
        val right = frame.right
        val top = frame.top
        val bottom = frame.bottom
        var fog = 0
        var revealed = 0
        var blend = 0
        val columns = mutableListOf<Int>()
        for (y in top until bottom) {
            var boundary = -1
            var sawFog = false
            for (x in left until right) {
                val isFog = frame.isFog(x, y)
                if (isFog) fog += 1 else revealed += 1
                if (!isFog && frame.isBlend(x, y)) blend += 1
                if (isFog) {
                    sawFog = true
                } else if (sawFog && boundary < 0) {
                    boundary = x
                }
            }
            if (boundary >= 0) columns += boundary
        }

        var steps = 0
        var maxRun = 0
        var run = 0
        columns.forEachIndexed { index, column ->
            if (index == 0 || column != columns[index - 1]) {
                steps += 1
                maxRun = maxOf(maxRun, run)
                run = 1
            } else {
                run += 1
            }
        }
        maxRun = maxOf(maxRun, run)
        val analyzed = (fog + revealed).coerceAtLeast(1)
        return BoundaryReading(
            rows = columns.size,
            steps = steps,
            meanRunPx = if (steps == 0) 0.0 else columns.size.toDouble() / steps.toDouble(),
            maxRunPx = maxRun,
            distinctColumns = columns.distinct().size,
            fogPct = fog * 100.0 / analyzed,
            revealedPct = revealed * 100.0 / analyzed,
            blendPx = blend,
            blendPerRowPx = if (columns.isEmpty()) 0.0 else blend.toDouble() / columns.size,
            bandWidthPx = right - left,
            bandHeightPx = bottom - top,
        )
    }

    /**
     * The classification, not the basemap.
     *
     * Two colours: what the oracle called fog and what it did not. This is the edge the owner's
     * question is about, and it carries no map detail, so it is coordinate-free by construction
     * rather than by a promise about where the fixture is.
     */
    private fun saveMask(frame: Frame, name: String): String? {
        val width = frame.right - frame.left
        val height = frame.bottom - frame.top
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = if (frame.isFog(frame.left + x, frame.top + y)) {
                    MASK_FOG_COLOR
                } else {
                    MASK_REVEALED_COLOR
                }
            }
        }
        val mask = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = SpikeEvidence.savePng(context, mask, name)
        mask.recycle()
        return saved
    }

    // ---- fixture and plumbing --------------------------------------------------------------------

    /**
     * One accepted point, and therefore one reveal disc.
     *
     * A disc rather than the settled sweep's 240-point swathe on purpose: a circle presents every
     * boundary angle, which is what makes a staircase measurable at all. A long straight swathe
     * would be axis-aligned over most of its length, where a run of identical columns says nothing
     * about the raster and everything about the shape.
     */
    private fun revealOneDisc(database: TrailVeilDatabase) {
        val dao = database.recordingDao()
        runBlocking {
            val recording = dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = 1_000L,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "v03011-boundary-sharpness",
                ),
                initialSegment = TrackSegmentEntity(
                    sessionId = 0,
                    sequence = 0,
                    startedAt = 1_000L,
                    startReason = "SESSION_START",
                ),
            )
            dao.appendAcceptedPoint(
                point = TrackPointEntity(
                    sessionId = recording.sessionId,
                    segmentId = recording.segmentId,
                    sequence = 0L,
                    timestamp = 1_000L,
                    latitude = DISC_CENTRE.latitude,
                    longitude = DISC_CENTRE.longitude,
                    horizontalAccuracy = 5.0,
                ),
                distanceDeltaMeters = 0.0,
            )
        }
    }

    private fun installerOf(mapView: MapView): String? {
        val gates = mapView.getTag(R.id.map_fog_binding_gates)?.toString() ?: return null
        val start = gates.indexOf("installer[")
        if (start < 0) return "none"
        val end = gates.indexOf(']', start)
        return if (end < 0) "none" else gates.substring(start + "installer[".length, end)
    }

    private fun awaitMapView(
        scenario: ActivityScenario<GoogleMapSurfaceTestActivity>,
        arm: FogContinuityArm,
    ): MapView {
        val found = AtomicReference<MapView>()
        repeat(MAP_VIEW_POLLS) {
            scenario.onActivity { activity -> found.set(findMapView(activity.window.decorView)) }
            found.get()?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("${arm.label}: the host never attached a Google MapView")
    }

    private fun findMapView(view: View): MapView? {
        if (view is MapView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findMapView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun awaitTag(
        mapView: MapView,
        key: Int,
        polls: Int = TAG_POLLS,
        predicate: (Any?) -> Boolean,
    ): Boolean {
        repeat(polls) {
            if (predicate(mapView.getTag(key))) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return predicate(mapView.getTag(key))
    }

    private fun cameraOf(map: GoogleMap): CameraPosition {
        val position = AtomicReference<CameraPosition>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            position.set(map.cameraPosition)
        }
        return requireNotNull(position.get())
    }

    private fun awaitStableCamera(map: GoogleMap): CameraPosition {
        var last: CameraPosition? = null
        var stable = 0
        repeat(CAMERA_POLLS) {
            val current = cameraOf(map)
            stable = if (last != null && sameCamera(requireNotNull(last), current)) stable + 1 else 0
            last = current
            if (stable >= CAMERA_STABLE_POLLS) return current
            SystemClock.sleep(POLL_MILLIS)
        }
        return requireNotNull(last)
    }

    private fun sameCamera(a: CameraPosition, b: CameraPosition): Boolean =
        abs(a.zoom - b.zoom) < CAMERA_EPSILON &&
            abs(a.target.latitude - b.target.latitude) < CAMERA_EPSILON &&
            abs(a.target.longitude - b.target.longitude) < CAMERA_EPSILON

    private companion object {
        const val SPIKE_ARGUMENT = "trailveilFogArmSpike"
        const val EVIDENCE_FILE = "v03-011-arm-spike.txt"
        const val EVIDENCE_PREFIX = "v03-011-boundary"
        const val CASE_TIMEOUT_SECONDS = 900L
        const val STAGES = 2

        /** Synthetic, and nowhere any real track has been: the sweep's own fixture continent. */
        val DISC_CENTRE = LatLng(-24.0, 133.5)

        /**
         * High enough that one 25 m reveal disc is a large circle on screen, and low enough that
         * the zoomed stage below stays inside the SDK's range.
         */
        const val PLAN_ZOOM = 17.5f

        /** The spread the arms were measured apart on in section 15j, applied programmatically. */
        const val ZOOM_STEP = 1.873f

        const val ZOOM_TOLERANCE = 0.05f
        const val MAP_READY_TIMEOUT_SECONDS = 30L
        const val MAP_VIEW_POLLS = 100
        const val TAG_POLLS = 300
        const val CAMERA_POLLS = 200
        const val CAMERA_STABLE_POLLS = 3
        const val CAMERA_EPSILON = 1e-6
        const val POLL_MILLIS = 100L

        /** Long enough for a rebuild that a leaving camera provokes, plus its snapshot proof. */
        const val COVER_SETTLE_POLLS = 300
        const val COVER_SETTLE_MILLIS = COVER_SETTLE_POLLS * POLL_MILLIS

        /** After the cover falls, before the readback: the reveal animation is not the subject. */
        const val SETTLE_MILLIS = 1_500L
        const val CAPTURE_ATTEMPTS = 6
        const val CAPTURE_RETRY_MILLIS = 400L
        const val MINIMUM_BOUNDARY_ROWS = 40

        /**
         * The analysed band. Narrower than the view on every side so the SDK's own decorations -
         * the watermark along the bottom, the compass at the top right - are outside it rather than
         * excluded from it, which keeps the classifier's input pure map.
         */
        const val BAND_LEFT_FRACTION = 0.10f
        const val BAND_RIGHT_FRACTION = 0.90f
        const val BAND_TOP_FRACTION = 0.25f
        const val BAND_BOTTOM_FRACTION = 0.72f

        /**
         * How far from the fog colour a pixel may be and still count as a blend of it.
         *
         * `SpikeCaptureSupport.isFogFamily` defaults to 6, which is the tolerance the whole audit
         * calls fog. Anything inside 40 but outside 6 is close enough to the fog colour to be a
         * mixture of it and something else, and far enough to be neither the fog nor the basemap
         * this fixture sits on. It is a comparison between arms at the same scene, not a threshold.
         */
        const val BLEND_TOLERANCE = 40

        const val MASK_FOG_COLOR = 0xFF202020.toInt()
        const val MASK_REVEALED_COLOR = 0xFFFFFFFF.toInt()
    }
}
