package app.trailveil.googlepoc

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-007`: settled-camera screen coverage on the real hosted production map.
 *
 * The Google twin of `MapSurfaceTest#noSettledCameraPresentsUnexploredMapAsRevealed` and its
 * production-style sibling, which collapse into one case here because the Google surface has no
 * fallback/production style split - it fails closed and builds no MapView at all when the shipped
 * basemap cannot be reached, so every case already runs against the style that ships.
 *
 * With an empty install and no revealed history, every pixel of the map area belongs to the fog:
 * the canonical TileOverlay where a generation is published, the fail-closed opaque placeholder
 * everywhere else, and the opaque `GoogleFogSafetyOverlay` while a generation is being proven. Each
 * scene is therefore audited only when the camera has settled, the cover is down and a canonical
 * generation is published - the state in which the user is being shown the map.
 *
 * Four traps shape the oracle.
 *
 * 1. `countNonFog == 0` is NOT available. Basemap labels composite ABOVE the fog TileOverlay, so a
 *    settled fogged frame always carries non-fog pixels. The oracle is therefore
 *    [FlingExposureVideoAnalyzer]'s label-aware rule - an 8-connected cluster of non-fog samples at
 *    or above [FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX] *and* above a measured floor - applied
 *    to a still capture instead of a video frame, with the shape rule below carrying the part of the
 *    discrimination an area threshold cannot.
 * 2. Area alone cannot separate a label from a leak, and the area floor derived from the bare arm
 *    degenerates to a constant of a few percent (a bare frame's largest cluster is the whole frame),
 *    which a full-height twenty-pixel bare seam slips under. So the floor is now CAPPED at
 *    [LEAK_CLUSTER_CEILING_PCT] - it may only be lowered by the measurement, never raised by it -
 *    and a second, shape-based rule runs beside it: the largest SOLID square of non-fog samples must
 *    stay under [SOLID_BLOCK_MINIMUM_SIDE_PX] on a side. Labels and POI glyphs are thin, separated
 *    strokes and cannot fill a square that size; a fog tile the renderer never received is a filled
 *    block one [app.trailveil.map.fog.FogTilePngCodec.TILE_SIZE] square wide on screen. That rule is
 *    not taken on trust: the fog-detached arm below must TRIP it at every scene, so a threshold too
 *    strict to ever fire is reported as ORACLE_BLIND rather than passed. Total clustered non-fog
 *    area is bounded too, so many sub-threshold blocks cannot add up to a bare frame.
 * 3. The safety cover is a `ViewOverlay` drawable in the view layer, and its colour is deliberately
 *    NOT inside the fog palette window, so a frame captured while it is up would read as one
 *    enormous leak through the composited channel and as raw basemap through a surface readback.
 *    Every capture is bracketed by a cover-down check and retaken if the cover moved - and that
 *    check reads BOTH published cover tags. `map_fog_cover_up` is a Compose tag written on the next
 *    recomposition, while the cover itself is raised synchronously by `GoogleFogSafetyOverlay
 *    .setVisible`, which writes `map_fog_synchronous_cover_up` in the same call. Reading only the
 *    lagging tag lets a stale `false` capture un-proven bare basemap and fail a healthy product.
 * 4. `moveCamera` CLAMPS into the SDK's own zoom range, and the viewport-dependent minimum on a
 *    full-screen phone map is somewhere near 2. Reading the camera back after the move and comparing
 *    everything downstream against that post-clamp value would let every rung of a ladder collapse
 *    onto one identical camera and still report green. So the floor is measured once from the live
 *    map (`GoogleMap.minZoomLevel`) and every scene asserts that it settled at `max(requested,
 *    floor)`, exactly as the MapLibre original does; a sweep whose zoom IS the property abstains,
 *    naming the floor, rather than passing over a camera it never reached.
 *
 * The per-scene floor and the sensitivity control are the same measurement. A second pass replays
 * each scene's *applied* camera on [GoogleMapSurfaceTestActivity] with `fogRequired` false - the
 * same `GoogleHostedMapSurface`, the same hardening, the same capture channel, the same oracle, with
 * the fog detached - and records what bare basemap looks like at that exact camera. That reading is
 * both the proof that the oracle can see bare basemap in this run and the scale from which the
 * scene's leak floor is derived. A scene whose bare arm is not obviously exposed is reported as
 * ORACLE_BLIND rather than passed.
 *
 * The third case supplies the arm the fog-detached pass cannot: ground the PRODUCT itself reveals.
 * Detaching the overlay proves the oracle can see basemap when nothing is drawn over it; it says
 * nothing about whether this fog is opaque *because it is fog* or merely because it is an overlay
 * that covers everything unconditionally. So a real canonical reveal is recorded into an in-memory
 * Room, the same hosted surface installs and proves a generation from it, and the same oracle on the
 * same capture channel must read the revealed patch as a large solid non-fog block while an
 * otherwise identical camera far from it stays fogged. Together the two directions make the sweep's
 * COVERED verdicts falsifiable: the detector demonstrably fires on bare ground, and demonstrably
 * does not fire on the label load of a correctly fogged frame.
 *
 * Runs in the unfiltered googlePoc suite. It abstains - never passes - when the key is not
 * configured, when the install carries revealed history, when the basemap never reports loaded, when
 * the SDK's minimum zoom makes the requested rungs unreachable, or when the map surface cannot be
 * read back directly (the composited screenshot channel cannot tell app chrome from a basemap leak).
 */
@RunWith(AndroidJUnit4::class)
class GoogleSettledFogCoverageSweepTest {

    @Before
    fun setUp() = GoogleMapSurfaceTestHooks.reset()

    @After
    fun tearDown() = GoogleMapSurfaceTestHooks.reset()

    /**
     * A settled zoom ladder from as far out as this viewport allows to exploration zoom at the
     * dateline, at the reused boundary latitudes and just inside both Mercator limits, and at an
     * ordinary mid-continent place at three zooms - so a defect at an ordinary location is never
     * mis-attributed to a boundary. Asserts that at every settled camera, with the cover down and a
     * generation proven, the camera really reached `max(requested zoom, this viewport's SDK
     * minimum)` and no basemap-coloured cluster or solid block survives above that scene's floor.
     *
     * The world-zoom rungs are viewpoints, not zoom claims: the SDK keeps the world covering the
     * viewport, so "world zoom" here means "as far out as this display allows", which is what the
     * asserted `max(requested, floor)` says and what the evidence line records.
     */
    @Test
    fun noSettledCameraOnTheProductionMapPresentsUnexploredGroundAsRevealed() {
        auditSettledSweep("ladder", LADDER_STEPS)
    }

    /**
     * The same settled audit stepped a hundredth of a zoom level either side of the app's
     * world-copy repetition boundary on both sides of the dateline, then carried across wrapped
     * world copies by half-viewport scrolls below that boundary. Asserts the map is fully fogged at
     * every one of those settled cells - a whole-frame audit where the existing boundary case
     * asserts install bookkeeping and snapshot probe blocks.
     *
     * Here the zoom IS the property: a rung a hundredth either side of the boundary is meaningless
     * if both clamp to the same camera. Every rung is therefore marked [SweepStep.zoomIsTheProperty]
     * and the case abstains, naming the measured minimum, on any viewport whose SDK floor sits at or
     * above the boundary - which is what a portrait phone gives, since the SDK's floor is the zoom
     * at which one world copy still covers the taller axis and wrapped copies never appear.
     */
    @Test
    fun bothSidesOfTheWorldCopyRepetitionBoundaryStaySettledAndFullyFogged() {
        auditSettledSweep("worldCopyEdge", WORLD_COPY_STEPS)
    }

    /**
     * The oracle's other direction: ground this product deliberately reveals must READ as revealed.
     *
     * Hosted on [GoogleMapSurfaceTestActivity] with a real DAO-backed `FogRuntime` over an
     * in-memory Room, so nothing here touches the install the other two cases audit. A canonical
     * reveal patch is persisted before the surface composes, the hosted surface installs and proves
     * a generation from it, and the SAME capture channel and the SAME cluster/solid-block oracle are
     * pointed first at the revealed patch and then at an otherwise identical camera far away from
     * it. The revealed frame must show a large solid non-fog block; the control frame must not, and
     * must be an order of magnitude quieter than the revealed one by the same measure.
     *
     * The revealed half alone would not attribute anything: "this camera reads as bare" is
     * satisfied identically by a reveal that worked and by a generation that failed to cover that
     * camera. So the same camera is audited a second time on a second hosted surface over an
     * install that has revealed nothing, and must read as fogged there. With that pair in place an
     * overlay that covered everything unconditionally, or an oracle blind to this basemap, fails
     * the revealed half; an overlay that leaked fails the empty-install half; and a COVERED verdict
     * in the other two cases is evidence rather than a tautology.
     */
    @Test
    fun revealedGroundReadsAsRevealedWhileUnvisitedGroundBesideItStaysFogged() {
        SpikeScenarioSupport.assumeKeyConfigured()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Two hosted surfaces, one over a canonical reveal and one over an empty install, audited
        // at the SAME camera. That pairing is what the arm rests on: "something reads as bare at
        // the patch" is satisfied identically by a reveal that worked and by a generation that
        // failed to cover that camera, and only the empty install separates them.
        val revealedScenes = auditRevealScenes(
            label = "revealedGround",
            withDatabase = { database -> revealPatch(database) },
            scenes = listOf(
                "revealedPatch" to REVEALED_PATCH_CENTRE,
                "unrevealedControl" to REVEALED_CONTROL_CENTRE,
            ),
        )
        val emptyScenes = auditRevealScenes(
            label = "unrevealedInstall",
            withDatabase = { },
            scenes = listOf("unrevealedSameCamera" to REVEALED_PATCH_CENTRE),
        )
        val revealed = requireNotNull(revealedScenes["revealedPatch"])
        val control = requireNotNull(revealedScenes["unrevealedControl"])
        val sameCamera = requireNotNull(emptyScenes["unrevealedSameCamera"])

        val line = "TRAILVEIL-V02007-SWEEP-REVEAL " +
            "revealedChannel=${revealed.captureMethod} " +
            "controlChannel=${control.captureMethod} " +
            "sameCameraChannel=${sameCamera.captureMethod} " +
            "revealedLargestClusterPct=${"%.3f".format(revealed.largestClusterPct)} " +
            "controlLargestClusterPct=${"%.3f".format(control.largestClusterPct)} " +
            "sameCameraLargestClusterPct=${"%.3f".format(sameCamera.largestClusterPct)} " +
            "revealedSolidSidePx=${revealed.largestSolidSquareSidePx} " +
            "controlSolidSidePx=${control.largestSolidSquareSidePx} " +
            "sameCameraSolidSidePx=${sameCamera.largestSolidSquareSidePx} " +
            "revealedClusteredPct=${"%.2f".format(revealed.clusteredExposedPct)} " +
            "controlClusteredPct=${"%.2f".format(control.clusteredExposedPct)} " +
            "revealedExposedPct=${"%.2f".format(revealed.exposedPct)} " +
            "controlExposedPct=${"%.2f".format(control.exposedPct)} " +
            "sameCameraExposedPct=${"%.2f".format(sameCamera.exposedPct)}"
        SpikeEvidence.emit(context, EVIDENCE_FILE, line)

        assertTrue(
            "$line\nrevealedPatch: the canonical reveal recorded before this surface composed " +
                "produced a largest non-fog cluster of only " +
                "${"%.3f".format(revealed.largestClusterPct)}% of the analyzed map area, under " +
                "the ${REVEALED_MINIMUM_CLUSTER_PCT}% a revealed patch this size must occupy, so " +
                "either the reveal never reached the screen or this oracle cannot see basemap on " +
                "this image - in which case every COVERED verdict in the other two cases is " +
                "unfalsified.",
            revealed.largestClusterPct >= REVEALED_MINIMUM_CLUSTER_PCT,
        )
        assertTrue(
            "$line\nrevealedPatch: the largest SOLID non-fog square on the revealed patch was " +
                "${revealed.largestSolidSquareSidePx}px on a side, under the " +
                "${SOLID_BLOCK_MINIMUM_SIDE_PX}px the sweep's shape rule fires at, so that rule " +
                "is not shown able to fire at all in this run",
            revealed.largestSolidSquareSidePx >= SOLID_BLOCK_MINIMUM_SIDE_PX,
        )
        assertTrue(
            "$line\nunrevealedControl: a settled camera over ground this install has never " +
                "revealed carried a solid non-fog square ${control.largestSolidSquareSidePx}px " +
                "on a side, at or above the ${SOLID_BLOCK_MINIMUM_SIDE_PX}px block rule; " +
                "unexplored ground is being presented as revealed.",
            control.largestSolidSquareSidePx < SOLID_BLOCK_MINIMUM_SIDE_PX,
        )
        assertTrue(
            "$line\nunrevealedControl: the revealed patch's largest cluster " +
                "(${"%.3f".format(revealed.largestClusterPct)}%) is not " +
                "${REVEALED_MARGIN_MULTIPLE}x the unrevealed control's " +
                "(${"%.3f".format(control.largestClusterPct)}%), so this oracle does not " +
                "separate revealed ground from the label load of a fogged frame and its COVERED " +
                "verdicts carry no information",
            revealed.largestClusterPct >= control.largestClusterPct * REVEALED_MARGIN_MULTIPLE,
        )
        // The A/B that makes the revealed half evidence rather than an observation. Same camera,
        // same zoom, same oracle, same capture channel; the only difference is whether the install
        // had ever visited this ground. A generation that simply failed to cover this camera would
        // read as bare in BOTH runs and fail here, which the far-away control cannot detect.
        assertTrue(
            "$line\nunrevealedSameCamera: the SAME camera over an install that has revealed " +
                "nothing carried a solid non-fog square ${sameCamera.largestSolidSquareSidePx}px " +
                "on a side, at or above the ${SOLID_BLOCK_MINIMUM_SIDE_PX}px block rule, so what " +
                "the revealed run read as a reveal is what an empty install reads as too and the " +
                "revealed half attributes nothing to the reveal",
            sameCamera.largestSolidSquareSidePx < SOLID_BLOCK_MINIMUM_SIDE_PX,
        )
        assertTrue(
            "$line\nunrevealedSameCamera: the revealed patch's largest cluster " +
                "(${"%.3f".format(revealed.largestClusterPct)}%) is not " +
                "${REVEALED_MARGIN_MULTIPLE}x the same camera's over an empty install " +
                "(${"%.3f".format(sameCamera.largestClusterPct)}%), so the reveal is not what " +
                "opened that ground",
            revealed.largestClusterPct >= sameCamera.largestClusterPct * REVEALED_MARGIN_MULTIPLE,
        )
    }

    /**
     * Hosts one surface over its own in-memory install and audits the named scenes on it.
     *
     * [withDatabase] runs before the surface composes, so a reveal it writes is already canonical
     * when the first generation is built; passing an empty body is what produces the never-visited
     * control install that the same-camera A/B above rests on.
     */
    private fun auditRevealScenes(
        label: String,
        withDatabase: (TrailVeilDatabase) -> Unit,
        scenes: List<Pair<String, LatLng>>,
    ): Map<String, SceneReading> {
        val database = inMemoryDatabase()
        val readings = LinkedHashMap<String, SceneReading>()
        try {
            withDatabase(database)
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
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
                assertTrue(
                    "$label: the hosted surface never produced a Google map",
                    mapReady.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                val map = requireNotNull(mapRef.get())
                val mapView = awaitHostedMapView(scenario, "the $label host")
                val activity = AtomicReference<Activity>()
                scenario.onActivity { activity.set(it) }
                val basemapOnline = awaitTag(mapView, R.id.map_basemap_load_state) { value ->
                    value == ONLINE_STATE
                }
                assumeTrue(
                    "$label: the hosted Google basemap never reported loaded, so there is " +
                        "nothing settled to audit; abstaining rather than reporting a pass. " +
                        describeMapView(mapView),
                    basemapOnline,
                )
                // The opening generation is waited for BEFORE any scene, so every scene has a
                // generation to advance past. Without it the first scene could settle on the
                // opening one, whose published keys do not include this camera's - the
                // generation-bound provider answers those fail-closed and opaque, which is the
                // product working and would read here as a reveal that never happened.
                assertTrue(
                    "$label: the hosted surface loaded its basemap but never published a " +
                        "canonical fog generation. " + describeMapView(mapView),
                    awaitTag(mapView, R.id.map_fog_canonical_generation) { value -> value != null },
                )
                val zoomFloor = readZoomFloor(scenario, map)
                scenes.forEach { (scene, target) ->
                    readings[scene] = auditRevealScene(
                        scene = scene,
                        scenario = scenario,
                        map = map,
                        mapView = mapView,
                        activity = requireNotNull(activity.get()),
                        target = target,
                        zoomFloor = zoomFloor,
                    )
                }
            }
        } finally {
            GoogleMapSurfaceTestHooks.reset()
            database.close()
        }
        return readings
    }

    // ---------------------------------------------------------------------------------------
    // Sweep
    // ---------------------------------------------------------------------------------------

    private fun auditSettledSweep(label: String, steps: List<SweepStep>) {
        SpikeScenarioSupport.assumeKeyConfigured()
        assumeTrue(
            "$label: this sweep asserts that EVERY map pixel is fog, which is only true of an " +
                "install that has revealed nothing; the canonical tables are not empty, so the " +
                "run abstains instead of reporting a pass over legitimately revealed ground",
            canonicalTablesAreEmpty(),
        )
        val fogged = sweepProductionMap(label, steps)
        val bare = measureDetachedFogReference(label, fogged.map { it.name to it.camera })
        assertSettledCoverage(label, fogged, bare)
    }

    private fun sweepProductionMap(label: String, steps: List<SweepStep>): List<SceneReading> {
        val readings = mutableListOf<SceneReading>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val mapView = awaitHostedMapView(scenario, "the real MainActivity")
            val activity = AtomicReference<Activity>()
            scenario.onActivity { activity.set(it) }
            val map = awaitGoogleMap(scenario, mapView)
            assumeTrue(
                "$label: the production Google basemap never reported loaded, so there is " +
                    "nothing settled to audit; abstaining rather than reporting a pass. " +
                    describeMapView(mapView),
                awaitTag(mapView, R.id.map_basemap_load_state) { value -> value == ONLINE_STATE },
            )
            assertTrue(
                "$label: the production surface loaded its basemap but never published a " +
                    "canonical fog generation. " + describeMapView(mapView),
                awaitTag(mapView, R.id.map_fog_canonical_generation) { value -> value != null },
            )

            // `V02-007` B4: `moveCamera` clamps into the SDK's own range, and everything downstream
            // reads the camera back AFTER the move, so without this the whole sweep can audit one
            // clamped camera and report every rung green. The floor belongs to the viewport, not to
            // this app, so it is measured from the live map rather than assumed.
            val zoomFloor = readZoomFloor(scenario, map)
            // Exact, not tolerant. `ZOOM_APPLIED_TOLERANCE` is 0.05 while the rungs that
            // straddle the world-copy boundary are `REPETITION_STEP` = 0.01 apart, so a tolerant
            // filter leaves a band five times the separation in which a rung below the floor is
            // NOT abstained, is clamped up to the floor, and then satisfies the assertion below
            // because that assertion expects `max(requested, floor)`. That is B4's original defect
            // surviving in a narrower band; a rung asking for anything under the measured floor
            // cannot be audited at the zoom it names, however close it is.
            val unreachable = steps.filter { step ->
                step.zoomIsTheProperty && step.zoom < zoomFloor
            }
            assumeTrue(
                "$label: ${unreachable.size} of ${steps.size} rungs ask for a zoom below this " +
                    "viewport's SDK minimum of ${"%.3f".format(zoomFloor)}, and for these rungs " +
                    "the zoom IS the property under test - clamped, every one of them audits the " +
                    "SAME camera and neither side of the boundary is ever entered. Abstaining " +
                    "with the measured floor rather than reporting a pass over one camera " +
                    "visited ${steps.size} times. " + describeMapView(mapView),
                unreachable.isEmpty(),
            )

            var previousSettledZoom: Float? = null
            steps.forEach { step ->
                val requested = applyStep(scenario, map, mapView, step)
                val settled = awaitSettledUncoveredCamera(
                    scenario = scenario,
                    map = map,
                    mapView = mapView,
                    scene = step.name,
                    applied = requested,
                )
                // A targeted step must land on the zoom it named; a scroll step must not move
                // the zoom at all, which is what makes `SweepStep.zoom` live on those rows too.
                //
                // Rungs where the zoom IS the property are held to `step.zoom` itself rather than
                // to `max(step.zoom, floor)`. Expecting the clamped value makes the assertion
                // unable to detect a clamp at all - it only detects a clamp landing somewhere
                // other than the floor - which left every clamp check resting on the filter above.
                // Those rungs are already abstained when they sit below the floor, so this can
                // only fire on a clamp the filter did not predict, which is exactly what it is for.
                val expectedZoom = when {
                    step.target != null && step.zoomIsTheProperty -> step.zoom
                    step.target != null -> maxOf(step.zoom, zoomFloor)
                    else -> previousSettledZoom ?: maxOf(step.zoom, zoomFloor)
                }
                assertTrue(
                    "$label/${step.name}: the camera settled at zoom " +
                        "${"%.3f".format(settled.zoom)} where this rung asked for " +
                        "${"%.3f".format(step.zoom)} and this viewport's SDK minimum is " +
                        "${"%.3f".format(zoomFloor)}, so the audited scene is not the one this " +
                        "rung names and neighbouring rungs collapse onto one camera. " +
                        describeMapView(mapView),
                    abs(settled.zoom - expectedZoom) <= ZOOM_APPLIED_TOLERANCE,
                )
                previousSettledZoom = settled.zoom
                readings += captureScene(
                    label = label,
                    scene = step.name,
                    scenario = scenario,
                    activity = requireNotNull(activity.get()),
                    mapView = mapView,
                    camera = settled,
                    requestedZoom = step.zoom,
                    coverAware = true,
                )
            }
        }
        return readings
    }

    /**
     * The sensitivity control and the floor in one pass: the same hosted surface with `fogRequired`
     * false, replaying each scene's applied camera, so what is measured is bare basemap at exactly
     * the camera the fogged reading was taken at rather than at a nominal one the SDK may have
     * clamped.
     */
    private fun measureDetachedFogReference(
        label: String,
        cameras: List<Pair<String, CameraPosition>>,
    ): List<SceneReading> {
        val readings = mutableListOf<SceneReading>()
        GoogleMapSurfaceTestHooks.reset()
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        val mapReady = CountDownLatch(1)
        val mapRef = AtomicReference<GoogleMap>()
        GoogleMapSurfaceTestHooks.onMapReady.set { readyMap ->
            mapRef.set(readyMap)
            mapReady.countDown()
        }
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue(
                "$label: the fog-detached calibration host never produced a Google map",
                mapReady.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val map = requireNotNull(mapRef.get())
            val mapView = awaitHostedMapView(scenario, "the fog-detached calibration host")
            val activity = AtomicReference<Activity>()
            scenario.onActivity { activity.set(it) }
            assumeTrue(
                "$label: the fog-detached calibration basemap never reported loaded, so this run " +
                    "cannot show that the oracle sees bare basemap; abstaining rather than " +
                    "reporting a pass. " + describeMapView(mapView),
                awaitTag(mapView, R.id.map_basemap_load_state) { value -> value == ONLINE_STATE },
            )
            cameras.forEach { (name, camera) ->
                val requested = CameraPosition.Builder()
                    .target(camera.target)
                    .zoom(camera.zoom)
                    .bearing(camera.bearing)
                    .tilt(camera.tilt)
                    .build()
                scenario.onActivity {
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(requested))
                }
                val settled = awaitStableCamera(scenario, map, requested)
                readings += captureScene(
                    label = label,
                    scene = name,
                    scenario = scenario,
                    activity = requireNotNull(activity.get()),
                    mapView = mapView,
                    camera = settled,
                    requestedZoom = camera.zoom,
                    coverAware = false,
                )
            }
        }
        return readings
    }

    private fun assertSettledCoverage(
        label: String,
        fogged: List<SceneReading>,
        bare: List<SceneReading>,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(fogged.size == bare.size) {
            "the fog-detached arm measured ${bare.size} scenes for ${fogged.size} audited ones"
        }
        val failures = mutableListOf<String>()
        fogged.forEachIndexed { index, scene ->
            val reference = bare[index]
            // `V02-007` M3. The floor is still measured rather than assumed - a fraction of the
            // largest cluster the same oracle reads at the same camera with the fog detached - but
            // the measurement may now only LOWER it. A bare frame is one cluster covering the whole
            // map, so the measured arm degenerates to a constant near
            // LEAK_CLUSTER_FRACTION * 100%, twenty times the MapLibre twin's settled-revealed
            // bound; capping it keeps the derived floor at parity scale while a scene whose bare
            // arm reads a smaller largest cluster still tightens it further. Never below the
            // analyzer's own minimum cluster, which is the smallest thing this oracle can see.
            val analyzerFloorPct = FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX * 100.0 /
                scene.analyzedPx.coerceAtLeast(1)
            val measuredFloorPct = reference.largestClusterPct * LEAK_CLUSTER_FRACTION
            val floorPct = maxOf(
                analyzerFloorPct,
                minOf(measuredFloorPct, LEAK_CLUSTER_CEILING_PCT),
            )
            val exposedByArea =
                scene.largestClusterPx >= FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX &&
                    scene.largestClusterPct >= floorPct
            // The shape half: labels are strokes, a missing fog tile is a filled block. This fires
            // on thickness rather than area, which is the discrimination an area threshold cannot
            // make, and the ORACLE_BLIND check below requires the bare arm to trip it at every
            // scene so a threshold too strict to ever fire cannot pass silently.
            val exposedByBlock = scene.largestSolidSquareSidePx >= SOLID_BLOCK_MINIMUM_SIDE_PX
            val line = "TRAILVEIL-V02007-SWEEP sweep=$label scene=${scene.name} " +
                "zoomRequested=${"%.3f".format(scene.requestedZoom)} " +
                "zoomApplied=${"%.3f".format(scene.camera.zoom)} " +
                "bareZoomApplied=${"%.3f".format(reference.camera.zoom)} " +
                "mapPx=${scene.mapWidthPx}x${scene.mapHeightPx} " +
                "barePx=${reference.mapWidthPx}x${reference.mapHeightPx} " +
                "channel=${scene.captureMethod} bareChannel=${reference.captureMethod} " +
                "analyzedPx=${scene.analyzedPx} excludedPct=${"%.2f".format(scene.excludedPct)} " +
                "exclusionFallback=${scene.exclusionFallbackUsed} " +
                "calibrationDelta=${scene.calibrationDelta} " +
                "labelLoadPct=${"%.2f".format(scene.exposedPct)} " +
                "largestClusterPct=${"%.3f".format(scene.largestClusterPct)} " +
                "clusteredPct=${"%.2f".format(scene.clusteredExposedPct)} " +
                "solidSidePx=${scene.largestSolidSquareSidePx} " +
                "floorPct=${"%.3f".format(floorPct)} " +
                "measuredFloorPct=${"%.3f".format(measuredFloorPct)} " +
                "bareExposedPct=${"%.2f".format(reference.exposedPct)} " +
                "bareLargestClusterPct=${"%.2f".format(reference.largestClusterPct)} " +
                "bareClusteredPct=${"%.2f".format(reference.clusteredExposedPct)} " +
                "bareSolidSidePx=${reference.largestSolidSquareSidePx} " +
                "verdict=${if (exposedByArea || exposedByBlock) "EXPOSED" else "COVERED"}"
            SpikeEvidence.emit(context, EVIDENCE_FILE, line)

            if (
                reference.exposedPct < BARE_EXPOSURE_MINIMUM_PCT ||
                reference.largestClusterPx < FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX ||
                reference.largestSolidSquareSidePx < SOLID_BLOCK_MINIMUM_SIDE_PX
            ) {
                failures += "${scene.name}: ORACLE_BLIND - the fog-detached arm read only " +
                    "${"%.2f".format(reference.exposedPct)}% of the map area as non-fog " +
                    "(largest cluster ${reference.largestClusterPx}px, largest solid square " +
                    "${reference.largestSolidSquareSidePx}px on a side), so this run never " +
                    "showed the oracle - and in particular its ${SOLID_BLOCK_MINIMUM_SIDE_PX}px " +
                    "block rule - seeing bare basemap here, and its silence proves nothing"
            }
            if (abs(reference.camera.zoom - scene.camera.zoom) > CALIBRATION_ZOOM_TOLERANCE) {
                failures += "${scene.name}: CALIBRATION_CAMERA_MISMATCH - the fog-detached host " +
                    "settled at zoom ${"%.3f".format(reference.camera.zoom)} where the production " +
                    "host settled at ${"%.3f".format(scene.camera.zoom)}, so the floor was not " +
                    "measured at the audited scene"
            }
            if (exposedByArea) {
                failures += "${scene.name}: BASEMAP_CLUSTER - a non-fog cluster of " +
                    "${"%.3f".format(scene.largestClusterPct)}% of the analyzed map area survived " +
                    "at a settled camera with the cover down, above this scene's floor of " +
                    "${"%.3f".format(floorPct)}%; unexplored ground is being presented as revealed"
            }
            if (exposedByBlock) {
                failures += "${scene.name}: BASEMAP_BLOCK - a SOLID non-fog square " +
                    "${scene.largestSolidSquareSidePx}px on a side survived at a settled camera " +
                    "with the cover down. Labels and POI glyphs are strokes and cannot fill a " +
                    "square that size; a fog tile the renderer never received can"
            }
            if (
                scene.clusteredExposedPct >
                reference.clusteredExposedPct * CLUSTERED_AREA_FRACTION
            ) {
                failures += "${scene.name}: CLUSTERED_AREA - " +
                    "${"%.2f".format(scene.clusteredExposedPct)}% of the analyzed map area sits " +
                    "in non-fog clusters at or above the analyzer's minimum, more than " +
                    "${CLUSTERED_AREA_FRACTION} of the " +
                    "${"%.2f".format(reference.clusteredExposedPct)}% the same camera reads with " +
                    "the fog detached, so many sub-threshold blocks are adding up to a bare frame"
            }
            if (scene.exposedPct > reference.exposedPct * AREA_HALFWAY_FRACTION) {
                failures += "${scene.name}: AREA_HALFWAY - ${"%.2f".format(scene.exposedPct)}% of " +
                    "the map area is non-fog, more than half of the " +
                    "${"%.2f".format(reference.exposedPct)}% the same camera reads with the fog " +
                    "detached, so the frame is closer to bare than to fogged however it clusters"
            }
            if (scene.excludedPct > EXCLUDED_PCT_BOUND) {
                failures += "${scene.name}: EXCLUSION_TOO_LARGE - " +
                    "${"%.2f".format(scene.excludedPct)}% of the map was excluded as SDK chrome " +
                    "(fallbackRect=${scene.exclusionFallbackUsed}), which is enough to hide a leak"
            }
            if (scene.calibrationDelta > CALIBRATION_BOUND) {
                failures += "${scene.name}: CALIBRATION_DELTA - the captured frame sat " +
                    "${scene.calibrationDelta} off the installed generation colour after " +
                    "$CALIBRATION_RETRIES retakes, so it was not a settled fogged frame"
            }
        }
        val summary = "TRAILVEIL-V02007-SWEEP-SUMMARY sweep=$label scenes=${fogged.size} " +
            "api=${android.os.Build.VERSION.SDK_INT} product=${android.os.Build.PRODUCT} " +
            "worstClusterPct=${"%.3f".format(fogged.maxOfOrNull { it.largestClusterPct } ?: 0.0)} " +
            "worstSolidSidePx=${fogged.maxOfOrNull { it.largestSolidSquareSidePx } ?: 0} " +
            "worstClusteredPct=" +
            "${"%.2f".format(fogged.maxOfOrNull { it.clusteredExposedPct } ?: 0.0)} " +
            "worstLabelLoadPct=${"%.2f".format(fogged.maxOfOrNull { it.exposedPct } ?: 0.0)} " +
            "minBareExposedPct=${"%.2f".format(bare.minOfOrNull { it.exposedPct } ?: 0.0)} " +
            "minBareSolidSidePx=${bare.minOfOrNull { it.largestSolidSquareSidePx } ?: 0} " +
            "failures=${failures.size}"
        SpikeEvidence.emit(context, EVIDENCE_FILE, summary)
        assertTrue(
            "$summary\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Camera driving
    // ---------------------------------------------------------------------------------------

    private fun <A : Activity> applyStep(
        scenario: ActivityScenario<A>,
        map: GoogleMap,
        mapView: MapView,
        step: SweepStep,
    ): CameraPosition {
        val applied = AtomicReference<CameraPosition>()
        scenario.onActivity {
            val target = step.target
            if (target != null) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, step.zoom))
            } else {
                map.moveCamera(
                    CameraUpdateFactory.scrollBy(
                        mapView.width * step.scrollViewportWidths,
                        0f,
                    ),
                )
            }
            applied.set(map.cameraPosition)
        }
        return requireNotNull(applied.get())
    }

    /**
     * How far out THIS viewport allows, measured from the live map rather than assumed.
     *
     * The SDK keeps one world copy covering the view, so the minimum belongs to the display and the
     * map's size, not to this app - which is exactly why a hard-coded expectation would either
     * break on a different screen or silently accept a clamp. Every zoom assertion in this file is
     * `max(requested, this)`.
     */
    private fun <A : Activity> readZoomFloor(
        scenario: ActivityScenario<A>,
        map: GoogleMap,
    ): Float {
        val floor = AtomicReference<Float>()
        scenario.onActivity { floor.set(map.minZoomLevel) }
        return requireNotNull(floor.get())
    }

    /**
     * A settled scene on the production surface: the camera the SDK actually applied is still the
     * camera on screen, no programmed flight is running, a canonical generation is published and
     * BOTH cover tags are down - held for [STABLE_POLL_COUNT] consecutive polls so a cover that is
     * about to rise, or a follow fix about to move the camera, is never mistaken for a settled one.
     *
     * `V02-007` M4: the synchronous tag is the one the cover itself writes; the Compose tag lags it
     * by a recomposition, and a surface readback bypasses the view-layer cover entirely, so a stale
     * `false` on the Compose tag alone would capture un-proven bare basemap through the SDK's own
     * surface and fail a product that was working.
     */
    private fun <A : Activity> awaitSettledUncoveredCamera(
        scenario: ActivityScenario<A>,
        map: GoogleMap,
        mapView: MapView,
        scene: String,
        applied: CameraPosition,
        newerThanGeneration: Long? = null,
    ): CameraPosition {
        var stable = 0
        var last = applied
        repeat(SETTLE_POLL_COUNT) {
            val sample = readSettleSample(scenario, map, mapView)
            val settled = cameraMatches(sample.camera, applied) &&
                !sample.flightActive &&
                sample.coverDown &&
                sample.generation != null &&
                (newerThanGeneration == null || sample.generation != newerThanGeneration)
            if (settled) {
                stable += 1
                last = sample.camera
                if (stable >= STABLE_POLL_COUNT) return last
            } else {
                stable = 0
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "scene $scene never settled with the cover down: " +
                "cameraDrifted=" +
                "${!cameraMatches(readSettleSample(scenario, map, mapView).camera, applied)} " +
                describeMapView(mapView),
        )
    }

    /** The calibration host has no fog and therefore no cover; only the camera must settle. */
    private fun awaitStableCamera(
        scenario: ActivityScenario<GoogleMapSurfaceTestActivity>,
        map: GoogleMap,
        requested: CameraPosition,
    ): CameraPosition {
        var stable = 0
        var last = requested
        repeat(SETTLE_POLL_COUNT) {
            val current = readCamera(scenario, map)
            if (cameraMatches(current, last) && stable > 0) {
                stable += 1
                if (stable >= STABLE_POLL_COUNT) return current
            } else {
                stable = 1
            }
            last = current
            SystemClock.sleep(POLL_MILLIS)
        }
        return last
    }

    private fun cameraMatches(actual: CameraPosition, expected: CameraPosition): Boolean =
        abs(actual.target.latitude - expected.target.latitude) < CAMERA_DEGREE_TOLERANCE &&
            abs(actual.target.longitude - expected.target.longitude) < CAMERA_DEGREE_TOLERANCE &&
            abs(actual.zoom - expected.zoom) < CAMERA_ZOOM_TOLERANCE

    private fun <A : Activity> readCamera(
        scenario: ActivityScenario<A>,
        map: GoogleMap,
    ): CameraPosition {
        val camera = AtomicReference<CameraPosition>()
        scenario.onActivity { camera.set(map.cameraPosition) }
        return requireNotNull(camera.get())
    }

    /**
     * Camera, flight state, both cover tags and the installed generation read in ONE main-thread
     * turn, so a settle decision is never assembled from four samples taken across a cover
     * transition - and so the keyed view tags are read on the thread the Compose `SideEffect` and
     * `GoogleFogSafetyOverlay.setVisible` write them on.
     */
    private fun <A : Activity> readSettleSample(
        scenario: ActivityScenario<A>,
        map: GoogleMap,
        mapView: MapView,
    ): SettleSample {
        val holder = AtomicReference<SettleSample>()
        scenario.onActivity {
            holder.set(
                SettleSample(
                    camera = map.cameraPosition,
                    flightActive = mapView.getTag(R.id.map_camera_flight_active) == true,
                    coverDown = mapView.getTag(R.id.map_fog_cover_up) == false &&
                        mapView.getTag(R.id.map_fog_synchronous_cover_up) == false,
                    generation = installedGeneration(mapView),
                ),
            )
        }
        return requireNotNull(holder.get())
    }

    /** Both published cover tags, read together on the main thread; see the settle helper above. */
    private fun <A : Activity> coverIsDown(
        scenario: ActivityScenario<A>,
        mapView: MapView,
    ): Boolean {
        val down = AtomicReference(false)
        scenario.onActivity {
            down.set(
                mapView.getTag(R.id.map_fog_cover_up) == false &&
                    mapView.getTag(R.id.map_fog_synchronous_cover_up) == false,
            )
        }
        return down.get()
    }

    // ---------------------------------------------------------------------------------------
    // Capture and the label-aware cluster oracle
    // ---------------------------------------------------------------------------------------

    private fun <A : Activity> captureScene(
        label: String,
        scene: String,
        scenario: ActivityScenario<A>,
        activity: Activity,
        mapView: MapView,
        camera: CameraPosition,
        requestedZoom: Float,
        coverAware: Boolean,
        savePngEvidence: Boolean = true,
    ): SceneReading {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var worst: SceneReading? = null
        CAPTURE_DELAYS_MILLIS.forEach { delayMillis ->
            SystemClock.sleep(delayMillis)
            val exclusions = readExclusions(scenario, mapView)
            val generation = installedGeneration(mapView)
            var attempt = 0
            var reading: SceneReading? = null
            while (attempt <= CALIBRATION_RETRIES && reading == null) {
                attempt += 1
                if (coverAware && !coverIsDown(scenario, mapView)) {
                    SystemClock.sleep(RETAKE_MILLIS)
                    continue
                }
                val capture = captureSurfaceFrame(label, scene, activity, mapView)
                // A capture taken across a cover transition reads the un-proven basemap through the
                // surface, so it is retaken rather than judged: the cover is the product working.
                if (coverAware && !coverIsDown(scenario, mapView)) {
                    capture.bitmap.recycle()
                    SystemClock.sleep(RETAKE_MILLIS)
                    continue
                }
                val delta = if (generation != null) {
                    SpikeCaptureSupport.calibrationDelta(capture.bitmap, generation)
                } else {
                    0
                }
                val scan = scanForBasemapClusters(capture.bitmap, exclusions.first)
                val candidate = SceneReading(
                    name = scene,
                    camera = camera,
                    requestedZoom = requestedZoom,
                    analyzedPx = scan.analyzedPx,
                    excludedPx = scan.excludedPx,
                    exposedPx = scan.exposedPx,
                    clusteredExposedPx = scan.clusteredExposedPx,
                    largestClusterPx = scan.largestClusterPx,
                    largestSolidSquareSidePx = scan.largestSolidSquareSidePx,
                    calibrationDelta = delta,
                    captureMethod = capture.method,
                    exclusionFallbackUsed = exclusions.second,
                    mapWidthPx = capture.bitmap.width,
                    mapHeightPx = capture.bitmap.height,
                )
                if (delta > CALIBRATION_BOUND && attempt <= CALIBRATION_RETRIES) {
                    capture.bitmap.recycle()
                    SystemClock.sleep(RETAKE_MILLIS)
                    continue
                }
                // Only a BLOCK-shaped reading is worth a full-resolution frame of the real basemap
                // on disk. Triggering on cluster area alone dumped a PNG for every ordinary
                // label-dense scene, through a helper whose own doc scopes it to synthetic,
                // privacy-cleared fixtures.
                if (
                    coverAware && savePngEvidence &&
                    candidate.largestSolidSquareSidePx >= SOLID_BLOCK_MINIMUM_SIDE_PX
                ) {
                    SpikeEvidence.savePng(
                        context,
                        capture.bitmap,
                        "v02007-sweep-$label-$scene-$delayMillis.png",
                    )
                }
                capture.bitmap.recycle()
                reading = candidate
            }
            val judged = reading ?: throw AssertionError(
                "$label/$scene: no frame could be captured with the safety cover down after " +
                    "${CALIBRATION_RETRIES + 1} attempts. " + describeMapView(mapView),
            )
            // Worst on the SHAPE measure first: a frame carrying a solid block is a worse frame
            // than one carrying a larger total of strokes, and keeping the larger cluster alone
            // would let a block-shaped capture be discarded in favour of a label-dense one.
            val incumbent = worst
            val worseThanIncumbent = incumbent == null ||
                judged.largestSolidSquareSidePx > incumbent.largestSolidSquareSidePx ||
                (
                    judged.largestSolidSquareSidePx == incumbent.largestSolidSquareSidePx &&
                        judged.largestClusterPx > incumbent.largestClusterPx
                    )
            if (worseThanIncumbent) {
                worst = judged
            }
        }
        return requireNotNull(worst)
    }

    /**
     * The composited screenshot channel is refused here. On the production launcher the entry
     * screen's Compose chrome sits above the map in the window, so a whole-frame audit through
     * `UI_AUTOMATION` could not tell a notice card from a missing fog tile. The surface readback
     * sees exactly what the Maps SDK renderer drew, which is the thing under test.
     */
    private fun captureSurfaceFrame(
        label: String,
        scene: String,
        activity: Activity,
        mapView: MapView,
    ): SpikeScenarioSupport.CaptureResult {
        var lastChannel = "none"
        repeat(CAPTURE_CHANNEL_ATTEMPTS) {
            val capture = SpikeScenarioSupport.captureMapView(activity, mapView)
            when {
                capture == null -> lastChannel = "unavailable"
                capture.method in SURFACE_CAPTURE_METHODS -> return capture
                else -> {
                    lastChannel = capture.method
                    capture.bitmap.recycle()
                }
            }
            SystemClock.sleep(RETAKE_MILLIS)
        }
        assumeTrue(
            "$label/$scene: the Maps SDK render surface could not be read back directly " +
                "(last channel=$lastChannel). A whole-frame fog audit cannot use the composited " +
                "screenshot channel, because app chrome drawn above the map would be " +
                "indistinguishable from a basemap leak; abstaining rather than reporting a pass.",
            false,
        )
        error("unreachable: the assumption above always throws")
    }

    /**
     * [FlingExposureVideoAnalyzer]'s rule against a still capture, plus the shape measure an area
     * rule cannot make: sample on the analyzer's stride, classify every sample outside the exclusion
     * rects with the shared palette window, then take the largest 8-connected component of the
     * non-fog samples, the total area of every component at or above the analyzer's minimum, and the
     * side of the largest SOLID square of non-fog samples. Kept private here because the analyzer is
     * owned by the gesture harness and consumes video frames, not bitmaps.
     */
    private fun scanForBasemapClusters(bitmap: Bitmap, exclusions: List<Rect>): ClusterScan {
        val columns = (bitmap.width + CLUSTER_STRIDE_PX - 1) / CLUSTER_STRIDE_PX
        val rows = (bitmap.height + CLUSTER_STRIDE_PX - 1) / CLUSTER_STRIDE_PX
        val exposedGrid = BooleanArray(columns * rows)
        val row = IntArray(bitmap.width)
        var analyzed = 0
        var excluded = 0
        var exposed = 0
        var y = 0
        var gridY = 0
        while (y < bitmap.height && gridY < rows) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            var gridX = 0
            while (x < bitmap.width && gridX < columns) {
                if (exclusions.any { rect -> rect.contains(x, y) }) {
                    excluded += 1
                } else {
                    analyzed += 1
                    if (!SpikeCaptureSupport.isFogFamily(row[x])) {
                        exposed += 1
                        exposedGrid[gridY * columns + gridX] = true
                    }
                }
                x += CLUSTER_STRIDE_PX
                gridX += 1
            }
            y += CLUSTER_STRIDE_PX
            gridY += 1
        }
        val clusters = clusterStats(exposedGrid, columns, rows)
        return ClusterScan(
            analyzedPx = analyzed * CLUSTER_CELL_PX,
            excludedPx = excluded * CLUSTER_CELL_PX,
            exposedPx = exposed * CLUSTER_CELL_PX,
            clusteredExposedPx = clusters.clusteredCells * CLUSTER_CELL_PX,
            largestClusterPx = clusters.largestCells * CLUSTER_CELL_PX,
            largestSolidSquareSidePx =
                largestSolidSquareCells(exposedGrid, columns, rows) * CLUSTER_STRIDE_PX,
        )
    }

    private fun clusterStats(grid: BooleanArray, columns: Int, rows: Int): ClusterStats {
        val visited = BooleanArray(grid.size)
        val stack = ArrayDeque<Int>()
        var largest = 0
        var clustered = 0
        for (start in grid.indices) {
            if (!grid[start] || visited[start]) continue
            var size = 0
            stack.addLast(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                size += 1
                val cellX = cell % columns
                val cellY = cell / columns
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cellX + dx
                        val ny = cellY + dy
                        if (nx !in 0 until columns || ny !in 0 until rows) continue
                        val neighbour = ny * columns + nx
                        if (grid[neighbour] && !visited[neighbour]) {
                            visited[neighbour] = true
                            stack.addLast(neighbour)
                        }
                    }
                }
            }
            largest = maxOf(largest, size)
            if (size >= CLUSTER_MINIMUM_CELLS) clustered += size
        }
        return ClusterStats(largestCells = largest, clusteredCells = clustered)
    }

    /**
     * Side, in grid cells, of the largest axis-aligned square in which EVERY sample is non-fog.
     *
     * The standard maximal-square recurrence over the sampled grid. A stroke - a road casing, a
     * label glyph, a coastline - is a few pixels thick however long it runs, so it can carry a large
     * cluster area and still score two or three cells here; a fog tile the renderer never received
     * is a filled block, and scores its whole on-screen width.
     */
    private fun largestSolidSquareCells(grid: BooleanArray, columns: Int, rows: Int): Int {
        if (columns == 0 || rows == 0) return 0
        val previous = IntArray(columns)
        val current = IntArray(columns)
        var best = 0
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                current[x] = when {
                    !grid[y * columns + x] -> 0
                    x == 0 || y == 0 -> 1
                    else -> minOf(previous[x], previous[x - 1], current[x - 1]) + 1
                }
                if (current[x] > best) best = current[x]
            }
            current.copyInto(previous)
        }
        return best
    }

    private fun <A : Activity> readExclusions(
        scenario: ActivityScenario<A>,
        mapView: MapView,
    ): Pair<List<Rect>, Boolean> {
        val holder = AtomicReference<Pair<List<Rect>, Boolean>>()
        scenario.onActivity { holder.set(SpikeCaptureSupport.liveExclusionRects(mapView)) }
        return requireNotNull(holder.get())
    }

    // ---------------------------------------------------------------------------------------
    // Revealed-ground arm
    // ---------------------------------------------------------------------------------------

    /**
     * One settled scene of the revealed-ground arm, driven and captured exactly like a sweep rung.
     *
     * The generation is required to CHANGE before the scene is judged: both cameras in that arm are
     * a long way outside the other's published coverage, so a settle predicate satisfied on its
     * first poll by the previous scene's retained generation and lowered cover would audit the
     * frame before the rebuild it is waiting for.
     */
    private fun auditRevealScene(
        scene: String,
        scenario: ActivityScenario<GoogleMapSurfaceTestActivity>,
        map: GoogleMap,
        mapView: MapView,
        activity: Activity,
        target: LatLng,
        zoomFloor: Float,
    ): SceneReading {
        val before = installedGeneration(mapView)
        val step = SweepStep(scene, target, REVEALED_ZOOM)
        val applied = applyStep(scenario, map, mapView, step)
        val settled = awaitSettledUncoveredCamera(
            scenario = scenario,
            map = map,
            mapView = mapView,
            scene = scene,
            applied = applied,
            newerThanGeneration = before,
        )
        assertTrue(
            "$scene: the camera settled at zoom ${"%.3f".format(settled.zoom)} where this arm " +
                "asked for ${"%.3f".format(REVEALED_ZOOM)} and this viewport's SDK minimum is " +
                "${"%.3f".format(zoomFloor)}, so the revealed patch is not being audited at the " +
                "scale its size was chosen for. " + describeMapView(mapView),
            abs(settled.zoom - maxOf(REVEALED_ZOOM, zoomFloor)) <= ZOOM_APPLIED_TOLERANCE,
        )
        return captureScene(
            label = "reveal",
            scene = scene,
            scenario = scenario,
            activity = activity,
            mapView = mapView,
            camera = settled,
            requestedZoom = REVEALED_ZOOM,
            coverAware = true,
            // A revealed frame is bare basemap BY DESIGN; dumping it would put a full-resolution
            // real-basemap PNG on disk for a scene that is behaving correctly.
            savePngEvidence = false,
        )
    }

    /**
     * A canonical reveal big enough to be unmistakable at [REVEALED_ZOOM] on any density.
     *
     * Rows are spaced under twice `FogRenderStyle.revealRadiusMeters` apart so they merge into one
     * filled patch rather than a comb of separate discs - the arm's whole point is a SOLID block, so
     * a shape the solid-square rule could not score would prove nothing about that rule.
     */
    private fun revealPatch(database: TrailVeilDatabase) {
        val dao = database.recordingDao()
        runBlocking {
            val recording = dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = 1_000L,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "v02007-settled-sweep-reveal",
                ),
                initialSegment = TrackSegmentEntity(
                    sessionId = 0,
                    sequence = 0,
                    startedAt = 1_000L,
                    startReason = "SESSION_START",
                ),
            )
            var sequence = 0L
            repeat(REVEALED_ROWS) { rowIndex ->
                repeat(REVEALED_COLUMNS) { columnIndex ->
                    dao.appendAcceptedPoint(
                        point = TrackPointEntity(
                            sessionId = recording.sessionId,
                            segmentId = recording.segmentId,
                            sequence = sequence,
                            timestamp = 1_000L + sequence * 5_000L,
                            latitude = REVEALED_ORIGIN.latitude +
                                rowIndex * REVEALED_LATITUDE_STEP_DEGREES,
                            longitude = REVEALED_ORIGIN.longitude +
                                columnIndex * REVEALED_LONGITUDE_STEP_DEGREES,
                            horizontalAccuracy = 5.0,
                        ),
                        distanceDeltaMeters = REVEALED_POINT_DISTANCE_METERS,
                    )
                    sequence += 1L
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Host plumbing
    // ---------------------------------------------------------------------------------------

    private fun canonicalTablesAreEmpty(): Boolean = try {
        SpikeScenarioSupport.assumeEmptyCanonicalTables()
        true
    } catch (_: org.junit.AssumptionViolatedException) {
        false
    }

    private fun <A : Activity> awaitHostedMapView(
        scenario: ActivityScenario<A>,
        host: String,
    ): MapView {
        val found = AtomicReference<MapView>()
        repeat(MAP_VIEW_POLL_COUNT) {
            scenario.onActivity { activity ->
                found.set(findMapView(activity.window.decorView))
            }
            found.get()?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("$host never attached a Google MapView")
    }

    private fun awaitGoogleMap(
        scenario: ActivityScenario<MainActivity>,
        mapView: MapView,
    ): GoogleMap {
        val ready = CountDownLatch(1)
        val map = AtomicReference<GoogleMap>()
        scenario.onActivity {
            mapView.getMapAsync { googleMap ->
                map.set(googleMap)
                ready.countDown()
            }
        }
        assertTrue(
            "the production Google map never became ready",
            ready.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return requireNotNull(map.get())
    }

    private fun awaitTag(mapView: MapView, key: Int, predicate: (Any?) -> Boolean): Boolean {
        repeat(TAG_POLL_COUNT) {
            if (predicate(mapView.getTag(key))) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return predicate(mapView.getTag(key))
    }

    private fun installedGeneration(mapView: MapView): Long? =
        (mapView.getTag(R.id.map_fog_canonical_generation) as? String)?.toLongOrNull()

    /** Names, booleans and ids only - no camera targets ever reach an evidence or failure line. */
    private fun describeMapView(mapView: MapView): String =
        "[basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
            "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
            "cover=${mapView.getTag(R.id.map_fog_cover_up)} " +
            "syncCover=${mapView.getTag(R.id.map_fog_synchronous_cover_up)} " +
            "flightActive=${mapView.getTag(R.id.map_camera_flight_active)} " +
            "phase=${mapView.getTag(R.id.map_fog_phase)} " +
            "gates=${mapView.getTag(R.id.map_fog_binding_gates)} " +
            "bindingState=${mapView.getTag(R.id.map_fog_binding_state)} " +
            "lastFailure=${mapView.getTag(R.id.map_fog_last_failure)} " +
            "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown}]"

    private fun findMapView(view: View): MapView? {
        if (view is MapView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findMapView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private data class SweepStep(
        val name: String,
        val target: LatLng?,
        val zoom: Float,
        val scrollViewportWidths: Float = 0f,
        /**
         * True where the exact zoom IS the property under test, so a rung the SDK clamps makes the
         * whole sweep meaningless rather than merely differently framed.
         */
        val zoomIsTheProperty: Boolean = false,
    )

    private data class SettleSample(
        val camera: CameraPosition,
        val flightActive: Boolean,
        val coverDown: Boolean,
        val generation: Long?,
    )

    private data class ClusterStats(
        val largestCells: Int,
        val clusteredCells: Int,
    )

    private data class ClusterScan(
        val analyzedPx: Int,
        val excludedPx: Int,
        val exposedPx: Int,
        val clusteredExposedPx: Int,
        val largestClusterPx: Int,
        val largestSolidSquareSidePx: Int,
    )

    private data class SceneReading(
        val name: String,
        val camera: CameraPosition,
        val requestedZoom: Float,
        val analyzedPx: Int,
        val excludedPx: Int,
        val exposedPx: Int,
        val clusteredExposedPx: Int,
        val largestClusterPx: Int,
        val largestSolidSquareSidePx: Int,
        val calibrationDelta: Int,
        val captureMethod: String,
        val exclusionFallbackUsed: Boolean,
        val mapWidthPx: Int,
        val mapHeightPx: Int,
    ) {
        val exposedPct: Double = exposedPx * 100.0 / analyzedPx.coerceAtLeast(1)
        val clusteredExposedPct: Double =
            clusteredExposedPx * 100.0 / analyzedPx.coerceAtLeast(1)
        val largestClusterPct: Double = largestClusterPx * 100.0 / analyzedPx.coerceAtLeast(1)
        val excludedPct: Double =
            excludedPx * 100.0 / (analyzedPx + excludedPx).coerceAtLeast(1)
    }

    private companion object {
        const val EVIDENCE_FILE = "v02007-settled-fog-sweep.txt"
        const val ONLINE_STATE = "ONLINE"

        /** Mirrors `FlingExposureVideoAnalyzer.SAMPLE_STRIDE`, which is private to the analyzer. */
        const val CLUSTER_STRIDE_PX = 3

        /** Area of one sampled grid cell, in device pixels. */
        const val CLUSTER_CELL_PX = CLUSTER_STRIDE_PX * CLUSTER_STRIDE_PX

        /** The analyzer's minimum cluster expressed in this scan's grid cells, rounded up. */
        const val CLUSTER_MINIMUM_CELLS =
            (FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX + CLUSTER_CELL_PX - 1) / CLUSTER_CELL_PX

        /**
         * A leak must reach 2% of what the same oracle reads at the same camera with the fog
         * detached - but only where that is TIGHTER than [LEAK_CLUSTER_CEILING_PCT]. A bare frame
         * is one cluster covering the whole map, so on its own this fraction degenerates to a
         * constant near 2% of the map area, twenty times the MapLibre twin's
         * `MAXIMUM_SETTLED_REVEALED_FRACTION`, and a thin full-height bare seam slips under it.
         */
        const val LEAK_CLUSTER_FRACTION = 0.02

        /**
         * Absolute ceiling on the derived floor, in percent of the analyzed map area.
         *
         * The MapLibre twin bounds settled revealed area at 0.1% of the frame. This is not that
         * number: the Google oracle reads Google's own labels and POI glyphs as non-fog, and no
         * measurement in this repository yet records how large their largest CONNECTED cluster gets
         * at a label-dense settled camera on this image. Half a percent is the tightest value the
         * available evidence supports - it fails the worked counter-example the `V02-007` review
         * names (a full-height twenty-pixel bare seam, about 1.8% of a phone viewport) with room to
         * spare, while staying above a glyph run. The evidence line records `largestClusterPct`,
         * `clusteredPct` and `solidSidePx` per scene precisely so a keyed device run can replace
         * this with the measured label ceiling; [SOLID_BLOCK_MINIMUM_SIDE_PX] carries the rest of
         * the discrimination in the meantime.
         */
        const val LEAK_CLUSTER_CEILING_PCT = 0.5

        /**
         * Side, in device pixels, of the smallest SOLID non-fog square treated as a leak.
         *
         * Chosen from what the two populations can produce, not from a measurement of one of them:
         * Google's label layer is strokes and glyphs a handful of pixels thick, and a POI icon is a
         * outlined multi-colour sprite tens of pixels across at most, while one
         * `FogTilePngCodec.TILE_SIZE` tile renders at 256dp - at least 256 device pixels on the
         * least dense screen this app supports. Forty-eight pixels sits an order of magnitude below
         * a missing tile and comfortably above anything the label layer draws. Its sensitivity is
         * asserted rather than assumed: the fog-detached arm must trip this rule at EVERY scene, and
         * the revealed-ground case must trip it on real product-revealed ground, so a value too
         * strict to fire is reported as ORACLE_BLIND instead of quietly passing.
         */
        const val SOLID_BLOCK_MINIMUM_SIDE_PX = 48

        /**
         * How much of the bare reference's clustered area a fogged frame may carry in clusters of
         * its own. Bounds the total the per-cluster rules do not: many blocks each just under the
         * floor still add up to a frame that is substantially bare.
         */
        const val CLUSTERED_AREA_FRACTION = 0.25

        /** SP5's falsify bound: below this the fog-detached arm did not see bare basemap. */
        const val BARE_EXPOSURE_MINIMUM_PCT = 30.0

        /** A fogged frame more than half as exposed as its own bare reference is not fogged. */
        const val AREA_HALFWAY_FRACTION = 0.5

        /** SP1's bounds, reused unchanged. */
        const val EXCLUDED_PCT_BOUND = 5.0
        const val CALIBRATION_BOUND = 60
        const val CALIBRATION_RETRIES = 3

        const val CAPTURE_CHANNEL_ATTEMPTS = 4
        const val CAMERA_DEGREE_TOLERANCE = 0.0005
        const val CAMERA_ZOOM_TOLERANCE = 0.01f
        const val CALIBRATION_ZOOM_TOLERANCE = 0.25f

        /** Matches the MapLibre twin's `ZOOM_TOLERANCE`: sampling slack, not clamp slack. */
        const val ZOOM_APPLIED_TOLERANCE = 0.05f

        const val POLL_MILLIS = 250L
        const val RETAKE_MILLIS = 400L
        const val SETTLE_POLL_COUNT = 240
        const val STABLE_POLL_COUNT = 6
        const val MAP_VIEW_POLL_COUNT = 120
        const val TAG_POLL_COUNT = 240
        const val MAP_READY_TIMEOUT_SECONDS = 30L
        val CAPTURE_DELAYS_MILLIS = listOf(400L, 1_200L)
        val SURFACE_CAPTURE_METHODS = setOf("PIXEL_COPY_SURFACE", "TEXTURE_VIEW")

        /** The app's world-copy repetition constant on the renderer that has one. */
        const val WORLD_COPY_ZOOM = 1.0f
        const val REPETITION_STEP = 0.01f
        const val EXPLORATION_ZOOM = 16.0f
        const val WORLD_ZOOM = 0.0f

        /** Reused from `GoogleFogViewportBoundaryTest`'s camera list. */
        val DATELINE_EAST = LatLng(0.0, 179.9)
        val DATELINE_WEST = LatLng(0.0, -179.9)
        val BOUNDARY_NORTH = LatLng(84.5, 0.0)
        val BOUNDARY_SOUTH = LatLng(-84.5, 0.0)

        /** Just inside the Web Mercator limit of +-85.05113 degrees. */
        val JUST_INSIDE_NORTH_LIMIT = LatLng(85.0, 0.0)
        val JUST_INSIDE_SOUTH_LIMIT = LatLng(-85.0, 0.0)

        /**
         * An ordinary mid-continent place far from the antimeridian and from both limits, so a
         * defect there is not mis-attributed to a boundary. Sparse-label inland ground keeps the
         * measured label floor small and the scene deterministic.
         */
        val MID_CONTINENT = LatLng(-24.0, 133.5)

        // -- revealed-ground arm ------------------------------------------------------------

        const val REVEALED_ZOOM = EXPLORATION_ZOOM
        const val REVEALED_ROWS = 6
        const val REVEALED_COLUMNS = 40

        /** Under two reveal radii apart, so the rows merge into one filled patch. */
        const val REVEALED_LATITUDE_STEP_DEGREES = 0.0004

        /** Well under one reveal radius, so the patch has no gaps along a row. */
        const val REVEALED_LONGITUDE_STEP_DEGREES = 0.0002
        const val REVEALED_POINT_DISTANCE_METERS = 20.0

        /**
         * Far enough that no revealed pixel is in view at [REVEALED_ZOOM], where the viewport spans
         * about a kilometre, and near enough to be the same kind of sparse inland ground.
         */
        const val REVEALED_CONTROL_OFFSET_DEGREES = 0.5

        /** The patch's largest cluster must reach this share of the analyzed map area. */
        const val REVEALED_MINIMUM_CLUSTER_PCT = 0.5

        /** And must exceed the unrevealed control's by this multiple. */
        const val REVEALED_MARGIN_MULTIPLE = 10.0

        val REVEALED_ORIGIN = MID_CONTINENT

        /** The patch's own centre, so the camera frames it rather than one of its corners. */
        val REVEALED_PATCH_CENTRE = LatLng(
            REVEALED_ORIGIN.latitude +
                (REVEALED_ROWS - 1) * REVEALED_LATITUDE_STEP_DEGREES / 2.0,
            REVEALED_ORIGIN.longitude +
                (REVEALED_COLUMNS - 1) * REVEALED_LONGITUDE_STEP_DEGREES / 2.0,
        )

        val REVEALED_CONTROL_CENTRE = LatLng(
            REVEALED_PATCH_CENTRE.latitude - REVEALED_CONTROL_OFFSET_DEGREES,
            REVEALED_PATCH_CENTRE.longitude + REVEALED_CONTROL_OFFSET_DEGREES,
        )

        val LADDER_STEPS = listOf(
            SweepStep("datelineWorldZoom", DATELINE_EAST, WORLD_ZOOM),
            SweepStep("datelineZoomFour", DATELINE_EAST, 4.0f),
            SweepStep("datelineZoomEight", DATELINE_EAST, 8.0f),
            SweepStep("datelineZoomTwelve", DATELINE_EAST, 12.0f),
            SweepStep("datelineExplorationZoom", DATELINE_EAST, EXPLORATION_ZOOM),
            SweepStep("boundaryNorthLowZoom", BOUNDARY_NORTH, 2.0f),
            SweepStep("justInsideNorthLimitExploration", JUST_INSIDE_NORTH_LIMIT, EXPLORATION_ZOOM),
            SweepStep("boundarySouthLowZoom", BOUNDARY_SOUTH, 2.0f),
            SweepStep("justInsideSouthLimitExploration", JUST_INSIDE_SOUTH_LIMIT, EXPLORATION_ZOOM),
            SweepStep("midContinentWorldZoom", MID_CONTINENT, WORLD_ZOOM),
            SweepStep("midContinentZoomEight", MID_CONTINENT, 8.0f),
            SweepStep("midContinentExplorationZoom", MID_CONTINENT, EXPLORATION_ZOOM),
        )

        val WORLD_COPY_STEPS = listOf(
            SweepStep(
                name = "belowRepetitionEast",
                target = DATELINE_EAST,
                zoom = WORLD_COPY_ZOOM - REPETITION_STEP,
                zoomIsTheProperty = true,
            ),
            SweepStep(
                name = "aboveRepetitionEast",
                target = DATELINE_EAST,
                zoom = WORLD_COPY_ZOOM + REPETITION_STEP,
                zoomIsTheProperty = true,
            ),
            SweepStep(
                name = "belowRepetitionWest",
                target = DATELINE_WEST,
                zoom = WORLD_COPY_ZOOM - REPETITION_STEP,
                zoomIsTheProperty = true,
            ),
            SweepStep(
                name = "aboveRepetitionWest",
                target = DATELINE_WEST,
                zoom = WORLD_COPY_ZOOM + REPETITION_STEP,
                zoomIsTheProperty = true,
            ),
            SweepStep(
                name = "belowRepetitionAnchor",
                target = DATELINE_EAST,
                zoom = WORLD_COPY_ZOOM - REPETITION_STEP,
                zoomIsTheProperty = true,
            ),
            SweepStep(
                name = "wrappedCopyScrollOne",
                target = null,
                zoom = WORLD_COPY_ZOOM - REPETITION_STEP,
                scrollViewportWidths = 0.75f,
                zoomIsTheProperty = true,
            ),
            SweepStep(
                name = "wrappedCopyScrollTwo",
                target = null,
                zoom = WORLD_COPY_ZOOM - REPETITION_STEP,
                scrollViewportWidths = 0.75f,
                zoomIsTheProperty = true,
            ),
            SweepStep(
                name = "wrappedCopyScrollThree",
                target = null,
                zoom = WORLD_COPY_ZOOM - REPETITION_STEP,
                scrollViewportWidths = 0.75f,
                zoomIsTheProperty = true,
            ),
            SweepStep(
                name = "wrappedCopyScrollFour",
                target = null,
                zoom = WORLD_COPY_ZOOM - REPETITION_STEP,
                scrollViewportWidths = 0.75f,
                zoomIsTheProperty = true,
            ),
        )
    }
}
