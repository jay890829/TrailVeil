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
            val frameMetrics = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
            frameMetrics.add(activity)
            try {
                performDeterministicPanAndZoom(scenario)
                val metrics = frameMetrics.remove(activity)
                    ?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
                val summary = summarize(metrics)
                assertTrue("No UI frame metrics were collected during MapLibre pan/zoom", summary.total > 0)
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

    private fun performDeterministicPanAndZoom(scenario: ActivityScenario<MainActivity>) {
        val map = awaitMap(scenario)
        var completedFogGeneration = readFogGeneration(scenario)
        repeat(PAN_ZOOM_ITERATIONS) { index ->
            val target = CAMERA_STEPS[index % CAMERA_STEPS.size]
            val idle = CountDownLatch(1)
            val listener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
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
                state.set(
                    MapState(
                        mapVisible = mapView.isShown,
                        camera = camera,
                        fogInstalled = style?.getSourceAs<ImageSource>(FogOverlayIds.Source) != null &&
                            style.getLayerAs<RasterLayer>(FogOverlayIds.Layer) != null,
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
                    "TrailVeil UI scale benchmark seed=${ScaleBenchmarkFixture.SEED} " +
                        "points=$CANONICAL_POINT_COUNT panZoom=$PAN_ZOOM_ITERATIONS " +
                        "lifecycleRecoveries=$LIFECYCLE_RECOVERY_COUNT " +
                        "frameP95=${summary.p95Millis}ms " +
                        "frozenRatio=${"%.4f".format(java.util.Locale.US, summary.frozenRatio)} " +
                        "frames=${summary.total}; basemap=local-fallback; $deviceClass; " +
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
