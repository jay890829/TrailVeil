package app.trailveil.benchmark

import android.os.Build
import android.os.Bundle
import android.util.SparseIntArray
import android.view.View
import android.view.ViewGroup
import androidx.core.app.FrameMetricsAggregator
import androidx.core.util.size
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.map.BasemapLoadState
import app.trailveil.map.FogGenerationSlot
import app.trailveil.map.FogOverlayIds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource

/**
 * Opt-in UI-scale engineering evidence for the production activity and its MapLibre surface.
 *
 * Run with `-Pandroid.testInstrumentationRunnerArguments.trailveilUiScale=true` on a dedicated,
 * empty app install with device networking disabled. A non-empty database is safely skipped
 * rather than modified. The test requires the production local fallback so provider/network state
 * cannot vary its workload. Non-designated devices remain engineering evidence only.
 */
@RunWith(AndroidJUnit4::class)
class UiScaleBenchmarkTest {
    @Test
    fun mainMapPanZoomAndLifecycleRecoveryAtCanonicalScale() {
        assumeTrue(
            "UI scale benchmark is opt-in; pass trailveilUiScale=true",
            InstrumentationRegistry.getArguments().getString(UI_SCALE_ARGUMENT) == "true",
        )
        val enforcePhysicalGate =
            InstrumentationRegistry.getArguments().getString(ENFORCE_GATE_ARGUMENT) == "true"
        if (enforcePhysicalGate) {
            assertTrue("The designated mid-range gate cannot run on an emulator", !isEmulator())
        }
        populateEmptyProductionDatabase()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val activity = scenario.requireActivity()
            // Exclude provider fallback and the initial canonical rebuild from the fixed operation
            // histogram. Readiness itself remains a hard precondition for collecting samples.
            awaitMapState(scenario)
            // The activity window is kept only as context. It cannot see this map - see
            // [SurfaceFlingerPresentIntervals] - and the gate below is judged on the map's own
            // SurfaceFlinger layer instead. Reporting both is what makes the difference legible
            // rather than something a later reader has to rediscover.
            val frameMetrics = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
            frameMetrics.add(activity)
            try {
                SurfaceFlingerPresentIntervals.arm()
                val presented = performDeterministicPanAndZoom(scenario)
                val windowSummary = summarize(
                    frameMetrics.remove(activity)?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX),
                )
                val summary = summarize(presented.histogram)
                // Blindness first, and on its own terms. A camera animation that produced no
                // interval for the map's layer did not measure a slow map - nothing observed the
                // map at all - and that is a different failure from a slow one, so it is asserted
                // apart from any threshold on speed. See [MAP_SURFACE_LAYER].
                assertTrue(
                    "The map's SurfaceFlinger layer presented during only " +
                        "${presented.presentingWindows} of $PAN_ZOOM_ITERATIONS camera " +
                        "animations, so this run did not observe the map for the rest, and " +
                        "frameP95=${summary.p95Millis}ms is not a measurement of it. First thing " +
                        "to check: that TimeStats is tracking a layer whose name contains " +
                        "\"$MAP_SURFACE_LAYER\". For contrast, the activity window - which does " +
                        "NOT contain this map - reported ${windowSummary.total} frames across " +
                        "the same animations.",
                    presented.presentingWindows == PAN_ZOOM_ITERATIONS,
                )
                // Then the worst single animation, which the pooled histogram cannot express.
                // A stalled window contributes ONE interval where a healthy one contributes
                // fourteen, so pooling lets the windows that behaved outvote the ones that did
                // not. See [MIN_INTERVALS_PER_WINDOW].
                assertTrue(
                    "The worst camera animation produced only ${presented.leanestWindow} " +
                        "presentation intervals, below the $MIN_INTERVALS_PER_WINDOW per-window " +
                        "floor. The map stalled during at least one animation, and a pooled " +
                        "frameP95=${summary.p95Millis}ms cannot show that, because a stalled " +
                        "window contributes fewer samples than a healthy one and is outvoted by " +
                        "them.",
                    presented.leanestWindow >= MIN_INTERVALS_PER_WINDOW,
                )
                // Then whether there are enough of them for a p95 to mean anything.
                assertTrue(
                    "Only ${summary.total} presentation intervals were recorded, below the " +
                        "$MIN_PRESENT_INTERVALS floor, so frameP95=${summary.p95Millis}ms and " +
                        "frozenRatio=${summary.frozenRatio} are too thinly sampled to quote.",
                    summary.total >= MIN_PRESENT_INTERVALS,
                )
                assertTrue("p95 frame time was invalid: ${summary.p95Millis}", summary.p95Millis >= 0)
                assertTrue(
                    "Frozen-frame ratio was invalid: ${summary.frozenRatio}",
                    summary.frozenRatio in 0.0..1.0,
                )
                if (enforcePhysicalGate) {
                    assertTrue(
                        "Pan/zoom p95 exceeded ${MAX_FRAME_P95_MILLIS}ms: " +
                            "${summary.p95Millis}ms",
                        summary.p95Millis <= MAX_FRAME_P95_MILLIS,
                    )
                    assertTrue(
                        "Frozen-frame ratio must be below $MAX_FROZEN_RATIO: " +
                            "${summary.frozenRatio}",
                        summary.frozenRatio < MAX_FROZEN_RATIO,
                    )
                }
                val expectedCamera = readCamera(scenario)
                val expectedFogGeneration = readFogGeneration(scenario)

                repeat(LIFECYCLE_RECOVERY_COUNT) { recoveryIndex ->
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitMapState(
                        scenario = scenario,
                        expectedCamera = expectedCamera,
                        minimumFogGeneration = expectedFogGeneration,
                        recoveryNumber = recoveryIndex + 1,
                    )
                }
                report(summary, windowSummary, presented.leanestWindow, enforcePhysicalGate)
            } finally {
                // A crash or ANR prevents a lifecycle transition or map-ready callback from
                // completing, and therefore fails this instrumentation test.
                frameMetrics.stop()
                // TimeStats is global to SurfaceFlinger and off by default. Leaving it enabled
                // would change how the device behaves for everything measured after this test.
                SurfaceFlingerPresentIntervals.disable()
            }
        }
    }

    private fun populateEmptyProductionDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = TrailVeilDatabase.open(context)
        try {
            val existingRows = CANONICAL_TABLES.associateWith { table ->
                database.openHelper.readableDatabase.query(
                    "SELECT COUNT(*) FROM $table",
                ).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            }
            assumeTrue(
                "UI scale benchmark needs a dedicated empty app install; found $existingRows",
                existingRows.values.all { count -> count == 0 },
            )
            ScaleBenchmarkFixture.populateCanonicalDataset(database, CANONICAL_POINT_COUNT)
        } finally {
            database.close()
        }
    }

    /**
     * Runs the fixed script and returns the map layer's summed presentation intervals.
     *
     * The histogram is armed and read around each animation rather than once around the whole
     * phase. Between animations this test waits for a strictly newer canonical fog generation,
     * which can take seconds, and a `WHEN_DIRTY` map that has nothing to draw presents nothing -
     * so a single window would record one enormous interval per wait and score it as a frozen
     * frame. Measuring only while the camera is actually animating is the same restriction
     * `P1-002` got by gesturing continuously.
     */
    private fun performDeterministicPanAndZoom(
        scenario: ActivityScenario<MainActivity>,
    ): PresentedIntervals {
        val presented = SparseIntArray()
        var presentingWindows = 0
        var leanestWindow = Int.MAX_VALUE
        val map = awaitMap(scenario)
        var completedFogGeneration = readFogGeneration(scenario)
        repeat(PAN_ZOOM_ITERATIONS) { index ->
            val target = CAMERA_STEPS[index % CAMERA_STEPS.size]
            val idle = CountDownLatch(1)
            val listener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
            SurfaceFlingerPresentIntervals.clear()
            scenario.onActivity {
                map.addOnCameraIdleListener(listener)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(target.location, target.zoom),
                    CAMERA_ANIMATION_MILLIS,
                )
            }
            try {
                assertTrue(
                    "MapLibre camera did not settle for pan/zoom sample ${index + 1}",
                    idle.await(CAMERA_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
            } finally {
                scenario.onActivity {
                    map.removeOnCameraIdleListener(listener)
                }
            }
            // Read while the animation's frames are the newest thing this layer presented, before
            // the fog wait below can open a gap that would be recorded as one huge interval.
            val window = SurfaceFlingerPresentIntervals.presentIntervals(MAP_SURFACE_LAYER)
            // A layer that exists but contributed no interval presented at most once in this
            // window, which is indistinguishable from not presenting - so counted as neither.
            val windowIntervals = window?.let { histogram ->
                var count = 0
                repeat(histogram.size) { index -> count += histogram.valueAt(index) }
                count
            } ?: 0
            if (windowIntervals > 0) {
                presentingWindows++
                SurfaceFlingerPresentIntervals.merge(presented, requireNotNull(window))
            }
            // Tracked per window, not only pooled. See [MIN_INTERVALS_PER_WINDOW]: a stalled
            // animation contributes FEWER samples than a healthy one, so pooling lets the windows
            // that behaved outvote the ones that did not.
            leanestWindow = minOf(leanestWindow, windowIntervals)
            val rendered = awaitMapState(
                scenario = scenario,
                expectedCamera = CameraPosition.Builder()
                    .target(target.location)
                    .zoom(target.zoom)
                    .build(),
                minimumFogGeneration = completedFogGeneration + 1L,
            )
            completedFogGeneration = requireNotNull(rendered.fogGeneration)
        }
        return PresentedIntervals(
            histogram = presented,
            presentingWindows = presentingWindows,
            leanestWindow = if (leanestWindow == Int.MAX_VALUE) 0 else leanestWindow,
        )
    }

    /**
     * The map layer's presentation intervals: pooled, per-window presence, and the worst window.
     *
     * [leanestWindow] exists because the pooled histogram cannot express the failure it guards.
     */
    private data class PresentedIntervals(
        val histogram: SparseIntArray,
        val presentingWindows: Int,
        val leanestWindow: Int,
    )

    private fun awaitMapState(
        scenario: ActivityScenario<MainActivity>,
        expectedCamera: CameraPosition? = null,
        minimumFogGeneration: Long? = null,
        recoveryNumber: Int? = null,
    ): MapState {
        var lastState: MapState? = null
        repeat(MAP_STATE_POLL_COUNT) {
            val mapView = awaitMapView(scenario)
            val map = awaitMap(scenario)
            val state = AtomicReference<MapState?>()
            scenario.onActivity {
                val camera = map.cameraPosition
                val style = map.style
                val slot = (mapView.getTag(R.id.map_fog_active_slot) as? String)
                    ?.let(FogGenerationSlot::valueOf)
                state.set(
                    MapState(
                        mapVisible = mapView.isShown,
                        camera = camera,
                        fogInstalled = slot != null &&
                            style?.getSourceAs<ImageSource>(FogOverlayIds.source(slot)) != null &&
                            style.getLayerAs<RasterLayer>(FogOverlayIds.layer(slot)) != null,
                        fogGeneration = mapView.getTag(R.id.map_fog_canonical_generation) as? Long,
                        localFallback =
                            mapView.getTag(R.id.map_basemap_load_state) ==
                                BasemapLoadState.LOCAL_FALLBACK.name,
                    ),
                )
            }
            lastState = state.get()
            if (
                lastState?.mapVisible == true &&
                lastState?.fogInstalled == true &&
                lastState?.localFallback == true &&
                lastState?.camera != null &&
                expectedCamera.matches(lastState?.camera) &&
                lastState?.fogGeneration.satisfies(minimumFogGeneration)
            ) {
                return requireNotNull(lastState)
            }
            Thread.sleep(MAP_STATE_POLL_MILLIS)
        }
        val suffix = recoveryNumber?.let { " after lifecycle recovery $it" }.orEmpty()
        error("Production map/fog/camera did not recover$suffix: $lastState")
    }

    private fun readFogGeneration(scenario: ActivityScenario<MainActivity>): Long =
        requireNotNull(awaitMapState(scenario).fogGeneration)

    private fun awaitMap(scenario: ActivityScenario<MainActivity>): MapLibreMap {
        val mapView = awaitMapView(scenario)
        val ready = CountDownLatch(1)
        val map = AtomicReference<MapLibreMap?>()
        scenario.onActivity {
            mapView.getMapAsync {
                map.set(it)
                ready.countDown()
            }
        }
        assertTrue("MapLibre map did not become ready", ready.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return requireNotNull(map.get())
    }

    private fun readCamera(scenario: ActivityScenario<MainActivity>): CameraPosition {
        val map = awaitMap(scenario)
        val camera = AtomicReference<CameraPosition?>()
        scenario.onActivity {
            camera.set(map.cameraPosition)
        }
        return requireNotNull(camera.get())
    }

    private fun awaitMapView(scenario: ActivityScenario<MainActivity>): MapView {
        val found = AtomicReference<MapView?>()
        repeat(MAP_VIEW_POLL_COUNT) {
            scenario.onActivity { activity ->
                found.set(activity.window.decorView.findMapView())
            }
            found.get()?.let { return it }
            Thread.sleep(MAP_VIEW_POLL_MILLIS)
        }
        error("MapView was not attached to MainActivity")
    }

    private fun ActivityScenario<MainActivity>.requireActivity(): MainActivity {
        val found = AtomicReference<MainActivity?>()
        onActivity { found.set(it) }
        return requireNotNull(found.get())
    }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }

    private fun summarize(histogram: SparseIntArray?): FrameSummary {
        if (histogram == null) return FrameSummary(total = 0, p95Millis = -1, frozenRatio = 0.0)
        var total = 0
        var frozen = 0
        repeat(histogram.size) { index ->
            val durationMillis = histogram.keyAt(index)
            val count = histogram.valueAt(index)
            total += count
            if (durationMillis >= FROZEN_FRAME_MILLIS) frozen += count
        }
        if (total == 0) return FrameSummary(total = 0, p95Millis = -1, frozenRatio = 0.0)

        val percentileRank = (total * 95 + 99) / 100
        var cumulative = 0
        var p95Millis = 0
        repeat(histogram.size) { index ->
            if (cumulative < percentileRank) {
                cumulative += histogram.valueAt(index)
                p95Millis = histogram.keyAt(index)
            }
        }
        return FrameSummary(total, p95Millis, frozen.toDouble() / total)
    }

    private fun report(
        summary: FrameSummary,
        windowSummary: FrameSummary,
        leanestWindow: Int,
        enforcePhysicalGate: Boolean,
    ) {
        val deviceClass = when {
            enforcePhysicalGate -> "designated mid-range physical device"
            isEmulator() -> "emulator; engineering evidence only"
            else -> "physical device not designated as mid-range; engineering evidence only"
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stream",
                    "TrailVeil UI scale benchmark seed=${ScaleBenchmarkFixture.SEED} " +
                        "points=$CANONICAL_POINT_COUNT panZoom=$PAN_ZOOM_ITERATIONS " +
                        "lifecycleRecoveries=$LIFECYCLE_RECOVERY_COUNT " +
                        "frameP95=${summary.p95Millis}ms " +
                        "frozenRatio=${"%.4f".format(java.util.Locale.US, summary.frozenRatio)} " +
                        "frames=${summary.total} leanestWindow=$leanestWindow " +
                        "surface=mapSurfaceViewPresentIntervals " +
                        "activityWindowFrames=${windowSummary.total}; " +
                        "basemap=local-fallback; $deviceClass; " +
                        if (enforcePhysicalGate) {
                            "mid-range performance gate enforced\n"
                        } else {
                            "no device performance gate applied\n"
                        },
                )
            },
        )
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true)

    private data class CameraStep(val location: LatLng, val zoom: Double)

    private data class MapState(
        val mapVisible: Boolean,
        val camera: CameraPosition?,
        val fogInstalled: Boolean,
        val fogGeneration: Long?,
        val localFallback: Boolean,
    )

    private fun Long?.satisfies(minimum: Long?): Boolean =
        this != null && (minimum == null || this >= minimum)

    private fun CameraPosition?.matches(actual: CameraPosition?): Boolean {
        if (this == null) return true
        if (actual == null) return false
        val expectedTarget = target ?: return false
        val actualTarget = actual.target ?: return false
        return kotlin.math.abs(expectedTarget.latitude - actualTarget.latitude) <= CAMERA_TOLERANCE &&
            kotlin.math.abs(expectedTarget.longitude - actualTarget.longitude) <= CAMERA_TOLERANCE &&
            kotlin.math.abs(zoom - actual.zoom) <= ZOOM_TOLERANCE
    }

    private data class FrameSummary(
        val total: Int,
        val p95Millis: Int,
        val frozenRatio: Double,
    )

    private companion object {
        const val UI_SCALE_ARGUMENT = "trailveilUiScale"
        const val ENFORCE_GATE_ARGUMENT = "trailveilEnforceFrameGate"
        const val CANONICAL_POINT_COUNT = 100_000
        const val PAN_ZOOM_ITERATIONS = 20
        const val LIFECYCLE_RECOVERY_COUNT = 20
        const val CAMERA_ANIMATION_MILLIS = 250
        const val CAMERA_IDLE_TIMEOUT_SECONDS = 10L
        const val MAP_READY_TIMEOUT_SECONDS = 10L
        const val MAP_VIEW_POLL_COUNT = 100
        const val MAP_VIEW_POLL_MILLIS = 100L
        const val MAP_STATE_POLL_COUNT = 300
        const val MAP_STATE_POLL_MILLIS = 100L
        const val FROZEN_FRAME_MILLIS = 700
        /**
         * The fewest presentation intervals that can carry a p95, for the map's own layer.
         *
         * `summary.total > 0` stood here, and two frames satisfied it - which is how a run that
         * could not see the map at all published a `frameP95` into this project's evidence. What
         * replaces it has to be derived for the quantity now being counted, and that quantity
         * changed: these are `present2present` intervals on a `SurfaceView` layer, sampled only
         * while the camera animates, not frames of the activity window.
         *
         * On that basis the arithmetic is tight, and it works against a high floor rather than
         * for one. Each of the [PAN_ZOOM_ITERATIONS] animations lasts [CAMERA_ANIMATION_MILLIS],
         * which is 15 vsyncs at 60 Hz, and the first presentation after each `-clear` has no
         * predecessor inside its own window and yields no interval - so a *perfect* 60 Hz run
         * tops out near 20 x 14 = 280. The first real run measured 277 (emulator, 2026-09-04),
         * 99% of that ceiling. A floor of 300 would have condemned a map presenting on
         * essentially every vsync, and did: that run failed this assertion before the number was
         * corrected. The lesson is that a floor derived for one basis does not survive a change
         * of basis, however well argued it was.
         *
         * So this floor is now set by what it must never do - report slowness as blindness.
         * Blindness has its own assertion (`presentingWindows`, which is 0 when the layer is
         * absent and cannot be confused with a slow map), leaving this one a purely statistical
         * job: keep enough samples that the nearest-rank p95 has a tail. The slowest run that
         * could still pass [MAX_FRAME_P95_MILLIS] presents roughly every 32 ms, giving about
         * 20 x (250/32 - 1) = 136 intervals, so 120 sits just below every run this gate is
         * capable of passing and cannot fire in place of the p95 assertion. At 120 samples the
         * p95 leaves 6 in the tail; `P4-008` saw a p95 wander 36/37/51/37 ms at 60 samples, so
         * this bounds that instability rather than removing it - raise it if a p95 is ever seen
         * to wander at this count.
         *
         * The Google twin keeps a floor of 300 on a different basis, and that divergence is
         * correct rather than an oversight: it counts activity-window frames of a `TextureView`
         * that really does draw through the window, against an observed 557-1096.
         *
         * Asserted unconditionally, not only under the enforced gate, because the vacuous runs
         * that reached the ledger as evidence were engineering-evidence runs.
         */
        const val MIN_PRESENT_INTERVALS = 120

        /**
         * The fewest presentation intervals a SINGLE camera animation may contribute.
         *
         * [MIN_PRESENT_INTERVALS] pools all twenty windows, and a pooled percentile cannot see the
         * failure this gate exists to catch. The reason is that the sample count is itself a
         * function of the badness: a healthy 250 ms window contributes about fourteen intervals,
         * while a window in which the map presented only twice contributes exactly one. The
         * windows that behaved therefore outvote the ones that stalled, and the arithmetic is not
         * close - worked against `summarize`, twelve healthy windows and **eight fully stalled
         * ones** produce a pool of {16ms: 168, 250ms: 8}, whose nearest-rank p95 is 16 ms and
         * whose frozen ratio is 0.0. Every other assertion passes: blindness sees twenty
         * presenting windows because one interval is enough, and 176 clears the pooled floor of
         * 120. Forty percent of the scripted workload can be stalled while the evidence line reads
         * `frameP95=16ms frozenRatio=0.0000`. That is the same class of vacuity this whole change
         * set out to remove, reintroduced one level down, and it was found by adversarial review
         * rather than by a run.
         *
         * So the quantity that is stable under stalling is asserted directly: the worst window's
         * interval count. The value is derived to interlock with [MAX_FRAME_P95_MILLIS] rather
         * than to duplicate it. A window that presents at exactly the 32 ms p95 limit yields about
         * `250/32 = 7.8` intervals, so a floor of 6 sits just below the slowest window the p95
         * gate could pass and cannot fire in its place. Above it, the two assertions close on each
         * other: with every window at 6 or more, at most two of twenty can run at 36 ms before the
         * pooled p95 exceeds 32 and fails - against eight fully stalled windows that passed
         * before.
         *
         * Measured, not assumed: the leanest window observed on the emulator is reported by every
         * run as `leanestWindow=` and recorded in `V02-007-gates.md`. Raise this only with a run
         * that forced it, and never above the 7.8 that would make it fire in place of the p95.
         */
        const val MIN_INTERVALS_PER_WINDOW = 6

        /**
         * Enough of the map layer's SurfaceFlinger name to pick it out of the dump.
         *
         * The full name carries a per-instance handle and a layer id (`bf7a79f
         * SurfaceView[app.trailveil/app.trailveil.MainActivity](BLAST)#57813`), neither of which is
         * stable across runs, so this matches the part that is: the `SurfaceView[` marker plus the
         * package. That is enough to separate it from the activity window's own layer, which is
         * named without the `SurfaceView[` prefix and is exactly the layer this benchmark must not
         * measure.
         */
        const val MAP_SURFACE_LAYER = "SurfaceView[app.trailveil"
        const val MAX_FRAME_P95_MILLIS = 32
        const val MAX_FROZEN_RATIO = 0.01
        const val CAMERA_TOLERANCE = 0.0001
        const val ZOOM_TOLERANCE = 0.01

        val CAMERA_STEPS = listOf(
            CameraStep(LatLng(25.0280, 121.5000), 12.0),
            CameraStep(LatLng(25.0420, 121.5350), 13.5),
            CameraStep(LatLng(25.0660, 121.5400), 14.5),
            CameraStep(LatLng(25.0380, 121.5100), 13.0),
        )

        val CANONICAL_TABLES = listOf(
            "recording_sessions",
            "track_segments",
            "track_points",
            "recording_operation_receipts",
        )
    }
}
