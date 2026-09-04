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
import app.trailveil.BuildConfig
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.map.BasemapLoadState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in UI-scale engineering evidence for the production activity and its hosted Google surface;
 * the provider twin of the MapLibre `UiScaleBenchmarkTest`.
 *
 * Run with `-PtrailveilAndroidTestBuildType=googlePoc`
 * `-Pandroid.testInstrumentationRunnerArguments.trailveilUiScale=true` on a dedicated, empty app
 * install with a valid external PoC key. A non-empty database is safely skipped rather than
 * modified. Non-designated devices remain engineering evidence only.
 *
 * The workload is the twin's, unchanged: the same canonical dataset and seed, the same 20 pan/zoom
 * steps over the same four camera targets and zooms, the same frame histogram and thresholds, and
 * the same 20 lifecycle recoveries. What the hosted surface publishes is not the twin's, so the
 * proofs wrapped around that workload differ in five stated ways.
 *
 * 1. Basemap, the accepted readiness difference. The MapLibre benchmark runs with device
 *    networking disabled and requires the packaged local basemap fallback, so provider/network
 *    state cannot vary its workload. The Google map has no such fallback: offline it fails closed
 *    and builds no MapView at all, so there would be nothing to pan. This twin therefore runs
 *    online against the live Google basemap, requires the hosted surface to publish
 *    [BasemapLoadState.ONLINE] instead of `LOCAL_FALLBACK`, and records `basemap=google-live` in
 *    its status line. The consequence is that its frame numbers are not workload-isolated the way
 *    the twin's are: `MAX_FRAME_P95_MILLIS` and `MAX_FROZEN_RATIO` keep the twin's values, but here
 *    they are measured with live basemap tile traffic inside the sampled window, so an enforced run
 *    can fail on network latency. Read an enforced failure here as "this device on this network",
 *    not as the twin's isolated device gate.
 * 2. What an advancing generation proves. Both surfaces publish
 *    `R.id.map_fog_canonical_generation`, but the MapLibre tag is a viewport counter bumped on
 *    every programmed camera move, while this one is the installed generation id, allocated only
 *    where a rebuild actually completed an install. "Strictly greater after a pan/zoom step" is
 *    therefore the stronger claim here — the step installed new canonical fog — and a step that
 *    settled inside the already-published surround would not satisfy it. The four camera steps
 *    alternate floor zooms, so no step can be served by the previous step's coverage. The tag is
 *    also the decimal string of that id on this surface, not a `Long`.
 * 3. What readiness proves. The twin interrogates the live MapLibre style for the fog source and
 *    layer of the active slot. The Google overlay has no style-object analogue, so readiness here
 *    is what the hosted surface publishes about itself: shown, camera at the target, the safety
 *    cover (`R.id.map_fog_cover_up`) down, and the generation advanced.
 * 4. Waits. The camera settle is a per-animation [GoogleMap.CancelableCallback], not an idle
 *    listener: the single `setOnCameraIdleListener` slot is production-owned and is what drives
 *    the post-move fog rebuild, so installing a test listener would evict the thing under test.
 *    The timeouts follow the googlePoc convention rather than the twin's, because Play services
 *    and the binding's own staged budgets — not this test — decide when the surface can be ready.
 * 5. The dedicated-empty-install check uses the fuller canonical table list of
 *    `GoogleFogScaleBenchmarkTest`.
 */
@RunWith(AndroidJUnit4::class)
class GoogleUiScaleBenchmarkTest {
    @Test
    fun mainMapPanZoomAndLifecycleRecoveryAtCanonicalScale() {
        assumeTrue(
            "UI scale benchmark is opt-in; pass trailveilUiScale=true",
            InstrumentationRegistry.getArguments().getString(UI_SCALE_ARGUMENT) == "true",
        )
        assumeTrue(
            "Google PoC runtime key is not configured; host builds remain compile-only",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        val enforcePhysicalGate =
            InstrumentationRegistry.getArguments().getString(ENFORCE_GATE_ARGUMENT) == "true"
        if (enforcePhysicalGate) {
            assertTrue("The designated mid-range gate cannot run on an emulator", !isEmulator())
        }
        populateEmptyProductionDatabase()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val activity = scenario.requireActivity()
            // Exclude provider startup and the initial canonical rebuild from the fixed operation
            // histogram. Readiness itself remains a hard precondition for collecting samples.
            awaitMapState(scenario)
            val frameMetrics = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
            frameMetrics.add(activity)
            try {
                performDeterministicPanAndZoom(scenario)
                val metrics = frameMetrics.remove(activity)
                    ?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
                val summary = summarize(metrics)
                assertTrue("No UI frame metrics were collected during Google pan/zoom", summary.total > 0)
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
                    awaitHostStopped(scenario, recoveryIndex + 1)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitMapState(
                        scenario = scenario,
                        expectedCamera = expectedCamera,
                        requireOrientation = true,
                        minimumFogGeneration = expectedFogGeneration,
                        recoveryNumber = recoveryIndex + 1,
                    )
                }
                report(summary, enforcePhysicalGate)
            } finally {
                // A crash or ANR prevents a lifecycle transition or map-ready callback from
                // completing, and therefore fails this instrumentation test.
                frameMetrics.stop()
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
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
            }
            assumeTrue(
                "UI scale benchmark needs a dedicated empty app install; found $existingRows",
                existingRows.values.all { count -> count == 0L },
            )
            ScaleBenchmarkFixture.populateCanonicalDataset(database, CANONICAL_POINT_COUNT)
        } finally {
            database.close()
        }
    }

    private fun performDeterministicPanAndZoom(scenario: ActivityScenario<MainActivity>) {
        var completedFogGeneration = readFogGeneration(scenario)
        repeat(PAN_ZOOM_ITERATIONS) { index ->
            // Re-resolve the live map every step. The hosted surface may replace its MapView, and a
            // step animated on a detached instance would settle against tags that never move.
            val map = awaitMap(scenario)
            val target = CAMERA_STEPS[index % CAMERA_STEPS.size]
            val settled = CountDownLatch(1)
            scenario.onActivity {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(target.location, target.zoom),
                    CAMERA_ANIMATION_MILLIS,
                    object : GoogleMap.CancelableCallback {
                        override fun onFinish() {
                            settled.countDown()
                        }

                        override fun onCancel() {
                            settled.countDown()
                        }
                    },
                )
            }
            assertTrue(
                "Google camera did not settle for pan/zoom sample ${index + 1}",
                settled.await(CAMERA_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
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
    }

    /**
     * The stop half of a lifecycle recovery, observed rather than assumed.
     *
     * Everything the recovery proof reads is retained across `ON_STOP` by design: the surface
     * re-proves its installed generation on `ON_START` instead of re-rendering (SP6), and
     * `onHostStopped` pauses the prover without raising the cover. The recovery poll could
     * therefore be satisfied on its first pass by the state that was already true before the stop.
     * Waiting here for the hosted map to leave the screen makes the proof that follows necessarily
     * a post-restart observation.
     */
    private fun awaitHostStopped(scenario: ActivityScenario<MainActivity>, recoveryNumber: Int) {
        repeat(HOST_STOPPED_POLL_COUNT) {
            val shown = AtomicReference<Boolean?>()
            scenario.onActivity { activity ->
                shown.set(activity.window.decorView.findMapView()?.isShown)
            }
            if (shown.get() != true) return
            Thread.sleep(MAP_STATE_POLL_MILLIS)
        }
        error("The hosted Google map never left the screen for lifecycle recovery $recoveryNumber")
    }

    private fun awaitMapState(
        scenario: ActivityScenario<MainActivity>,
        expectedCamera: CameraPosition? = null,
        requireOrientation: Boolean = false,
        minimumFogGeneration: Long? = null,
        recoveryNumber: Int? = null,
    ): MapState {
        var lastState: MapState? = null
        repeat(MAP_STATE_POLL_COUNT) {
            val mapView = awaitMapView(scenario)
            val map = awaitMap(scenario, mapView)
            val state = AtomicReference<MapState?>()
            scenario.onActivity {
                state.set(
                    MapState(
                        mapVisible = mapView.isShown,
                        camera = map.cameraPosition,
                        coverDown = mapView.getTag(R.id.map_fog_cover_up) == false,
                        // GoogleHostedMapSurface publishes the canonical generation as the decimal
                        // string of the Long id, not as a Long. Read it as it is actually typed.
                        fogGeneration =
                            (mapView.getTag(R.id.map_fog_canonical_generation) as? String)
                                ?.toLongOrNull(),
                        basemapOnline = mapView.getTag(R.id.map_basemap_load_state) ==
                            BasemapLoadState.ONLINE.name,
                    ),
                )
            }
            lastState = state.get()
            if (
                lastState?.mapVisible == true &&
                lastState?.coverDown == true &&
                lastState?.basemapOnline == true &&
                lastState?.camera != null &&
                expectedCamera.matches(lastState?.camera, requireOrientation) &&
                lastState?.fogGeneration.satisfies(minimumFogGeneration)
            ) {
                return requireNotNull(lastState)
            }
            Thread.sleep(MAP_STATE_POLL_MILLIS)
        }
        val suffix = recoveryNumber?.let { " after lifecycle recovery $it" }.orEmpty()
        error("Production Google map/fog/camera did not recover$suffix: ${lastState?.describe()}")
    }

    private fun readFogGeneration(scenario: ActivityScenario<MainActivity>): Long =
        requireNotNull(awaitMapState(scenario).fogGeneration)

    private fun awaitMap(scenario: ActivityScenario<MainActivity>): GoogleMap =
        awaitMap(scenario, awaitMapView(scenario))

    private fun awaitMap(
        scenario: ActivityScenario<MainActivity>,
        mapView: MapView,
    ): GoogleMap {
        val ready = CountDownLatch(1)
        val map = AtomicReference<GoogleMap?>()
        scenario.onActivity {
            mapView.getMapAsync {
                map.set(it)
                ready.countDown()
            }
        }
        assertTrue("Google map did not become ready", ready.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
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
        error("Google MapView was not attached to MainActivity")
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

    private fun report(summary: FrameSummary, enforcePhysicalGate: Boolean) {
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
                    "TrailVeil Google UI scale benchmark seed=${ScaleBenchmarkFixture.SEED} " +
                        "points=$CANONICAL_POINT_COUNT panZoom=$PAN_ZOOM_ITERATIONS " +
                        "lifecycleRecoveries=$LIFECYCLE_RECOVERY_COUNT " +
                        "frameP95=${summary.p95Millis}ms " +
                        "frozenRatio=${"%.4f".format(Locale.US, summary.frozenRatio)} " +
                        "frames=${summary.total}; basemap=google-live; $deviceClass; " +
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

    private data class CameraStep(val location: LatLng, val zoom: Float)

    private data class MapState(
        val mapVisible: Boolean,
        val camera: CameraPosition?,
        val coverDown: Boolean,
        val fogGeneration: Long?,
        val basemapOnline: Boolean,
    ) {
        /**
         * Flags, names and counts only, never a coordinate: the same diagnostic discipline the
         * hosted Google surface holds itself to. A raw [CameraPosition] would print its target.
         */
        fun describe(): String =
            "[shown=$mapVisible cameraPresent=${camera != null} zoom=${camera?.zoom} " +
                "coverDown=$coverDown generation=$fogGeneration basemapOnline=$basemapOnline]"
    }

    private fun Long?.satisfies(minimum: Long?): Boolean =
        this != null && (minimum == null || this >= minimum)

    /**
     * The twin guards against a null camera target; the Maps SDK declares `target` non-null and
     * enforces it in the [CameraPosition] constructor, so there is no analogous guard to keep.
     *
     * [requireOrientation] is set only where the expectation was read back from the live camera —
     * the lifecycle recoveries, which must return the camera unchanged. A pan/zoom expectation is
     * built from a target and a zoom alone, so comparing bearing and tilt there would assert a
     * default the camera update never promised.
     */
    private fun CameraPosition?.matches(
        actual: CameraPosition?,
        requireOrientation: Boolean,
    ): Boolean {
        if (this == null) return true
        if (actual == null) return false
        if (
            kotlin.math.abs(target.latitude - actual.target.latitude) > CAMERA_TOLERANCE ||
            kotlin.math.abs(target.longitude - actual.target.longitude) > CAMERA_TOLERANCE ||
            kotlin.math.abs(zoom - actual.zoom) > ZOOM_TOLERANCE
        ) {
            return false
        }
        if (!requireOrientation) return true
        return kotlin.math.abs(bearing - actual.bearing) <= ORIENTATION_TOLERANCE &&
            kotlin.math.abs(tilt - actual.tilt) <= ORIENTATION_TOLERANCE
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

        /** 30 s, the googlePoc convention: Play services decides when getMapAsync lands. */
        const val MAP_READY_TIMEOUT_SECONDS = 30L

        /**
         * 30 s, the googlePoc convention rather than the twin's 10 s: the hosted MapView is
         * attached only after the Play services availability check and the connectivity probe have
         * reported in and the composition has produced the map, and this helper fails hard rather
         * than degrading, so a slow cold start would otherwise be reported as a missing MapView.
         */
        const val MAP_VIEW_POLL_COUNT = 300
        const val MAP_VIEW_POLL_MILLIS = 100L

        /**
         * 45 s, matching the googlePoc lifecycle tests rather than the twin's 30 s. The first
         * proven generation waits on the binding's staged budgets in series: cell synchronization
         * (15 s) before the baseline is ready, then the first canonical render (15 s), then the
         * delivery barrier and the snapshot proof under a 20 s maximum cover window. A healthy
         * cold install at this point count can legitimately outlast 30 s, and this helper's
         * timeout is a hard failure attributed to the surface.
         */
        const val MAP_STATE_POLL_COUNT = 450
        const val MAP_STATE_POLL_MILLIS = 100L

        /** 10 s for the stopped window; the transition is driven by this test, not awaited on. */
        const val HOST_STOPPED_POLL_COUNT = 100
        const val FROZEN_FRAME_MILLIS = 700
        const val MAX_FRAME_P95_MILLIS = 32
        const val MAX_FROZEN_RATIO = 0.01
        const val CAMERA_TOLERANCE = 0.0001
        const val ZOOM_TOLERANCE = 0.01f

        /** The tolerance `GoogleTwentyCycleLifecycleTest` holds the same camera fields to. */
        const val ORIENTATION_TOLERANCE = 0.01f

        val CAMERA_STEPS = listOf(
            CameraStep(LatLng(25.0280, 121.5000), 12.0f),
            CameraStep(LatLng(25.0420, 121.5350), 13.5f),
            CameraStep(LatLng(25.0660, 121.5400), 14.5f),
            CameraStep(LatLng(25.0380, 121.5100), 13.0f),
        )

        val CANONICAL_TABLES = listOf(
            "recording_sessions",
            "track_segments",
            "track_points",
            "track_point_cells",
            "recording_operation_receipts",
            "recording_location_receipt_windows",
            "recording_location_receipt_retention_states",
        )
    }
}
