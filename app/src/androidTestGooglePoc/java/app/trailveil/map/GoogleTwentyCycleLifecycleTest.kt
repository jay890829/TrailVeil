package app.trailveil.map

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.feature.recording.PermissionHistory
import app.trailveil.feature.recording.PermissionHistoryStore
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 9: the ledger's "camera/fog survive recreation and 20 lifecycle cycles" on the
 * REAL googlePoc launcher, twin of the MapLibre `UiScaleBenchmarkTest` recovery loop.
 *
 * Two loops, because they prove different things and fail differently:
 *
 * 1. Twenty `CREATED -> RESUMED` cycles on ONE Activity instance. SP6 measured that the SDK's tile
 *    cache survives stop/start untouched, so the design (§6) re-proves the installed generation on
 *    `ON_START` and never re-renders. This loop pins that: the generation id must be IDENTICAL
 *    after every cycle, the camera unchanged, and the cover down again inside the bounded window.
 *    A generation that changes here means a restart silently became a rebuild.
 * 2. Twenty `recreate()` cycles. Each one destroys the MapView and restores it from the
 *    provider-tagged saved-state envelope (SP7). Camera fields must come back field-by-field; fog
 *    is deliberately NOT persisted and must be rebuilt behind the cover, so here the generation is
 *    only required to be present and proven, not equal.
 *
 * The baseline for loop 1 is a SETTLED generation: the programmed viewport change legitimately
 * dispatches a render, and a stop/start that merely finishes an in-flight rebuild would advance
 * the id for a reason the design allows ("advancing only where a render was legitimately
 * dispatched"). So the test lets that render land and prove, then requires a quiet window before
 * it records the id it will hold every cycle to.
 *
 * Evidence is streamed coordinate-free: cycle counts and the slowest cover-down per loop.
 */
@RunWith(AndroidJUnit4::class)
class GoogleTwentyCycleLifecycleTest {
    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: PermissionHistory? = null

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
            originalPermissionHistory?.let { permissionHistory.replaceForTesting(it) }
        }
    }

    @Test
    fun twentyStopStartCyclesKeepTheProvenGenerationAndTwentyRecreationsRestoreTheCamera() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Resolve the launcher's MapView by its first proven generation rather than by first
            // sight: a view the host replaces during startup would otherwise be watched while its
            // tags never move.
            var mapView = awaitLiveGeneration(scenario)
            var map = awaitMap(scenario, mapView)
            assertTrue(
                "the initial cover never lowered: " + describe(mapView),
                awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
            )
            val initialGeneration = requireNotNull(mapView.getTag(R.id.map_fog_canonical_generation))

            scenario.onActivity {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(EXPECTED_CAMERA))
            }
            assertTrue(
                "the programmed move never installed a different proven generation: " +
                    describe(mapView),
                awaitTag(mapView, R.id.map_fog_canonical_generation) { value ->
                    value != null && value != initialGeneration
                },
            )
            assertTrue(
                "the safety cover never lowered after the programmed move: " + describe(mapView),
                awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
            )
            val provenGeneration = awaitQuietGeneration(mapView)
            assertCamera("before any cycle", readCamera(scenario, map))
            val baseline = describe(mapView)

            // Loop 1: stop/start on the same instance. Nothing may be rebuilt.
            var slowestStopStartCoverMillis = 0L
            repeat(STOP_START_CYCLES) { index ->
                val cycle = index + 1
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val startedAt = SystemClock.uptimeMillis()
                val liveMapView = awaitMapView(scenario)
                assertSame("stop/start cycle $cycle replaced the MapView", mapView, liveMapView)
                assertTrue(
                    "stop/start cycle $cycle: cover never lowered: " + describe(mapView) +
                        " baseline=" + baseline,
                    awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
                )
                slowestStopStartCoverMillis =
                    maxOf(slowestStopStartCoverMillis, SystemClock.uptimeMillis() - startedAt)
                assertEquals(
                    "stop/start cycle $cycle re-rendered instead of re-proving (SP6): " +
                        describe(mapView) + " baseline=" + baseline,
                    provenGeneration,
                    mapView.getTag(R.id.map_fog_canonical_generation),
                )
                assertEquals(
                    "stop/start cycle $cycle lost the online basemap: " + describe(mapView),
                    "ONLINE",
                    mapView.getTag(R.id.map_basemap_load_state),
                )
                assertCamera("after stop/start cycle $cycle", readCamera(scenario, map))
            }

            // Loop 2: recreate. The MapView is new each time; the camera must come back exactly,
            // and fog must be rebuilt and proven behind the cover.
            var slowestRecreateCoverMillis = 0L
            repeat(RECREATE_CYCLES) { index ->
                val cycle = index + 1
                val previousMapView = mapView
                scenario.recreate()
                val startedAt = SystemClock.uptimeMillis()
                mapView = awaitLiveGeneration(scenario, "recreate cycle $cycle")
                assertNotSame(
                    "recreate cycle $cycle reused a destroyed MapView",
                    previousMapView,
                    mapView,
                )
                map = awaitMap(scenario, mapView)
                // Read once as soon as the SDK hands the map back: a camera that is already the
                // default here was never restored, whereas one that drifts later was overridden.
                val restoredAtReady = readCamera(scenario, map).matchesExpected()
                assertNotNull(
                    "recreate cycle $cycle: no generation was proven on the restored map: " +
                        describe(mapView),
                    mapView.getTag(R.id.map_fog_canonical_generation),
                )
                assertTrue(
                    "recreate cycle $cycle: cover never lowered: " + describe(mapView),
                    awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
                )
                slowestRecreateCoverMillis =
                    maxOf(slowestRecreateCoverMillis, SystemClock.uptimeMillis() - startedAt)
                assertCamera(
                    "after recreate cycle $cycle (cameraAtReady=" +
                        (if (restoredAtReady) "restored" else "default") + " " +
                        describe(mapView) +
                        " previousSave=" + previousMapView.getTag(R.id.map_saved_state_last_save) +
                        " previousDisposedAt=" + previousMapView.getTag(R.id.map_disposed_at) +
                        " previousEntryDestroyedAt=" +
                        previousMapView.getTag(R.id.map_entry_destroyed_at) +
                        " previousEntryDestroyStack=" +
                        previousMapView.getTag(R.id.map_entry_destroy_stack) +
                        ")",
                    readCamera(scenario, map),
                )
            }

            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "stage9_twenty_cycle stopStartCycles=$STOP_START_CYCLES " +
                            "recreateCycles=$RECREATE_CYCLES " +
                            "slowestStopStartCoverMs=$slowestStopStartCoverMillis " +
                            "slowestRecreateCoverMs=$slowestRecreateCoverMillis\n",
                    )
                },
            )
        }
    }

    private fun assertCamera(moment: String, actual: CameraPosition) {
        assertEquals(
            "$moment: latitude drifted",
            EXPECTED_CAMERA.target.latitude,
            actual.target.latitude,
            0.0001,
        )
        assertEquals(
            "$moment: longitude drifted",
            EXPECTED_CAMERA.target.longitude,
            actual.target.longitude,
            0.0001,
        )
        assertEquals("$moment: zoom drifted", EXPECTED_CAMERA.zoom, actual.zoom, 0.01f)
        assertEquals("$moment: bearing drifted", EXPECTED_CAMERA.bearing, actual.bearing, 0.01f)
        assertEquals("$moment: tilt drifted", EXPECTED_CAMERA.tilt, actual.tilt, 0.01f)
    }

    private fun readCamera(scenario: ActivityScenario<MainActivity>, map: GoogleMap): CameraPosition {
        val camera = AtomicReference<CameraPosition>()
        scenario.onActivity { camera.set(map.cameraPosition) }
        return requireNotNull(camera.get())
    }

    private fun CameraPosition.matchesExpected(): Boolean =
        kotlin.math.abs(target.latitude - EXPECTED_CAMERA.target.latitude) < 0.0001 &&
            kotlin.math.abs(target.longitude - EXPECTED_CAMERA.target.longitude) < 0.0001 &&
            kotlin.math.abs(zoom - EXPECTED_CAMERA.zoom) < 0.01f &&
            kotlin.math.abs(bearing - EXPECTED_CAMERA.bearing) < 0.01f &&
            kotlin.math.abs(tilt - EXPECTED_CAMERA.tilt) < 0.01f

    private fun describe(mapView: MapView): String =
        "[restored=${mapView.getTag(R.id.map_saved_state_restored)} " +
            "defaultAtReady=${mapView.getTag(R.id.map_camera_default_at_ready)} " +
            "runtimePresent=${mapView.getTag(R.id.map_fog_runtime_present)} " +
            "effectEpoch=${mapView.getTag(R.id.map_fog_effect_epoch)} " +
            "binding=${mapView.getTag(R.id.map_fog_binding_state)} " +
            "phase=${mapView.getTag(R.id.map_fog_phase)} " +
            "gates=[${mapView.getTag(R.id.map_fog_binding_gates)}] " +
            "lastFogFailure=${mapView.getTag(R.id.map_fog_last_failure)} " +
            "basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
            "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
            "cover=${mapView.getTag(R.id.map_fog_cover_up)} " +
            "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown}]"

    private fun awaitMap(scenario: ActivityScenario<MainActivity>, mapView: MapView): GoogleMap {
        val ready = CountDownLatch(1)
        val mapRef = AtomicReference<GoogleMap>()
        scenario.onActivity {
            mapView.getMapAsync { map ->
                mapRef.set(map)
                ready.countDown()
            }
        }
        assertTrue("launcher map did not become ready", ready.await(30, TimeUnit.SECONDS))
        return requireNotNull(mapRef.get())
    }

    /**
     * The generation once it has stopped changing for [QUIET_MILLIS] with the cover down. A
     * generation read the instant it appears may still have a successor in flight; holding a
     * stop/start loop to that id would fail the loop for a render the design allows.
     */
    private fun awaitQuietGeneration(mapView: MapView): Any {
        val deadline = SystemClock.uptimeMillis() + QUIET_DEADLINE_MILLIS
        var candidate = mapView.getTag(R.id.map_fog_canonical_generation)
        var quietSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            val now = mapView.getTag(R.id.map_fog_canonical_generation)
            val coverDown = mapView.getTag(R.id.map_fog_cover_up) == false
            if (now != candidate || !coverDown) {
                candidate = now
                quietSince = SystemClock.uptimeMillis()
            } else if (now != null && SystemClock.uptimeMillis() - quietSince >= QUIET_MILLIS) {
                return now
            }
            Thread.sleep(POLL_MILLIS)
        }
        error("the launcher's generation never settled for ${QUIET_MILLIS}ms: " + describe(mapView))
    }

    /** The attached MapView that carries a proven generation, re-resolved on every poll. */
    private fun awaitLiveGeneration(
        scenario: ActivityScenario<MainActivity>,
        moment: String = "launch",
    ): MapView {
        val live = AtomicReference<List<MapView>>(emptyList())
        val tree = AtomicReference<String>()
        repeat(GENERATION_POLLS) {
            scenario.onActivity { activity ->
                live.set(activity.window.decorView.findMapViews())
                tree.set(activity.window.decorView.describeTree())
            }
            live.get().firstOrNull { it.getTag(R.id.map_fog_canonical_generation) != null }
                ?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error(
            "$moment: canonical fog never installed on the production launcher: mapViews=" +
                live.get().withIndex().joinToString { (index, view) -> "#$index" + describe(view) } +
                " decorView=[${tree.get()}]",
        )
    }

    private fun View.findMapViews(): List<MapView> {
        if (this is MapView) return listOf(this)
        if (this !is ViewGroup) return emptyList()
        return (0 until childCount).flatMap { index -> getChildAt(index).findMapViews() }
    }

    private fun awaitTag(mapView: MapView, key: Int, predicate: (Any?) -> Boolean): Boolean {
        repeat(TAG_POLLS) {
            if (predicate(mapView.getTag(key))) return true
            Thread.sleep(POLL_MILLIS)
        }
        return predicate(mapView.getTag(key))
    }

    private fun awaitMapView(scenario: ActivityScenario<MainActivity>): MapView {
        val found = AtomicReference<MapView>()
        repeat(MAP_VIEW_POLLS) {
            scenario.onActivity { activity ->
                found.set(activity.window.decorView.findMapView())
            }
            found.get()?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error("real MainActivity did not attach a Google MapView")
    }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }

    /** Class names only: what is on screen, never anything a user typed. */
    private fun View.describeTree(): String {
        val names = linkedSetOf<String>()
        fun walk(view: View) {
            names += view.javaClass.simpleName.ifEmpty { view.javaClass.name.substringAfterLast('.') }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> walk(view.getChildAt(index)) }
            }
        }
        walk(this)
        return names.joinToString(",")
    }

    private companion object {
        const val STOP_START_CYCLES = 20
        const val RECREATE_CYCLES = 20
        const val POLL_MILLIS = 250L
        const val QUIET_MILLIS = 3_000L
        const val QUIET_DEADLINE_MILLIS = 45_000L

        /** 45 s: comfortably past the binding's 20 s maximum cover window plus a cold rebuild. */
        const val GENERATION_POLLS = 180
        const val TAG_POLLS = 180
        const val MAP_VIEW_POLLS = 120

        /** Non-default bearing and tilt so a "restored" camera cannot be a fresh default. */
        val EXPECTED_CAMERA: CameraPosition = CameraPosition.Builder()
            .target(LatLng(25.0330, 121.5654))
            .zoom(15.5f)
            .bearing(27f)
            .tilt(30f)
            .build()
    }
}
