package app.trailveil.map

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.googlepoc.FogContinuityArm
import app.trailveil.harness.TrackRegionGeometryEngine
import app.trailveil.map.fog.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual production binding and SDK, with a parked camera and isolated empty canonical store. */
class GoogleNativeHandoverBindingTest {
    @Test fun firstNativeInstallNeedsExactlyOneGeneration() = trial { rig ->
        rig.awaitProven(1L)
        assertEquals(setOf(1L), rig.states.mapNotNull { it.installedGeneration }.toSet())
        assertFalse(rig.diagnostics().contains("eval:false"))
        assertEquals("first native install has no predecessor to retire", 0, rig.committedFrames.get())
    }

    @Test fun aRefutedInstalledNativeReproofRecoversBehindAPublishedCover() = trial { rig ->
        rig.awaitProven(1L)
        Thread.sleep(150L) // include the real cover lowering settle
        rig.main {
            rig.map.clear() // controlled SDK loss: no canonical data or cache mutation
            rig.binding.onHostStopped()
            rig.binding.onHostStarted()
        }
        rig.awaitProven(2L)
        assertTrue("fault did not refute the old SDK surface: ${rig.diagnostics()}",
            rig.diagnostics().contains("start:verdict:1:false"))
        assertEquals(setOf(1L, 2L), rig.states.mapNotNull { it.installedGeneration }.toSet())
        assertFalse("cover commitment was mistaken for a renderer failure", rig.failures.any {
            it.message?.contains("cover frame not committed") == true
        })
    }

    @Test fun stoppingDuringNativeSuccessorProofDoesNotRetireThatSuccessor() = trial { rig ->
        rig.awaitProven(1L)
        val stopped = AtomicBoolean(false)
        rig.onState.set { state ->
            if (state.installedGeneration == 2L && state.coverUp && state.pendingGeneration == null && stopped.compareAndSet(false, true)) {
                rig.binding.onHostStopped()
            }
        }
        rig.main { rig.map.moveCamera(CameraUpdateFactory.zoomTo(15f)) }
        Thread.sleep(300L)
        rig.main { rig.binding.onCameraIdle() }
        rig.await { stopped.get() }
        Thread.sleep(350L)
        rig.main {
            assertEquals(2L, rig.states.last().installedGeneration)
            assertTrue(rig.states.last().coverUp)
            rig.binding.onHostStarted()
        }
        rig.awaitProven(2L)
        assertEquals(setOf(1L, 2L), rig.states.mapNotNull { it.installedGeneration }.toSet())
        assertFalse("resume removed the proof target: ${rig.diagnostics()}", rig.diagnostics().contains("eval:false"))
        assertEquals("resume must not create another predecessor fence", 1, rig.committedFrames.get())
    }

    @Test fun canonicalResetDuringTheFirstNativeProofCommitsCoverBeforeRetirement() {
        val reset = AtomicBoolean(false)
        trial(beforeStart = { rig ->
            rig.onState.set { state ->
                if (state.installedGeneration == 1L && state.coverUp && state.pendingGeneration == null && reset.compareAndSet(false, true)) {
                    rig.resetCanonicalWhileCovered()
                }
            }
        }) { rig ->
            rig.awaitProven(2L)
            assertTrue(reset.get())
            assertTrue("reset did not traverse the cover commit gate", rig.committedFrames.get() >= 1)
            assertTrue(rig.states.any { it.canonicalReplacementInProgress })
            assertEquals(setOf(1L, 2L), rig.states.mapNotNull { it.installedGeneration }.toSet())
        }
    }

    private fun trial(beforeStart: (Rig) -> Unit = {}, body: (Rig) -> Unit) {
        assumeTrue("requires keyed Google test build", BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, TrailVeilDatabase::class.java)
            .addCallback(TrailVeilDatabase.invariantCallback).build()
        val dao = database.recordingDao()
        val runtime = FogRuntime(FogViewportCoordinator(
            ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
            FogTilePipeline(FogMemoryTileCache(8L * 1024L * 1024L), null, renderMask = FogTileRenderer()::render),
            nativeGeometryEngine = TrackRegionGeometryEngine()), RoomPersistedTrackPointChangeFeed(dao))
        val map = AtomicReference<GoogleMap>()
        val view = AtomicReference<MapView>()
        val loaded = AtomicBoolean(false)
        GoogleMapSurfaceTestHooks.reset()
        GoogleMapSurfaceTestHooks.fogRequired = false
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(1L, GeoPoint(-25.5, -130.0), zoom = 16.0)
        GoogleMapSurfaceTestHooks.onMapReady.set(map::set)
        GoogleMapSurfaceTestHooks.onMapViewCreated.set(view::set)
        GoogleMapSurfaceTestHooks.onMapLoadState.set { loaded.set(it == BasemapLoadState.ONLINE) }
        FogContinuityArm.TRACK_NATIVE_FLOOR.install()
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                val readyDeadline = SystemClock.elapsedRealtime() + 40_000L
                while ((!loaded.get() || map.get() == null || view.get() == null) && SystemClock.elapsedRealtime() < readyDeadline) Thread.sleep(20L)
                assertTrue("SDK host did not become ready", loaded.get() && map.get() != null && view.get() != null)
                Thread.sleep(500L) // initial host camera must finish before installing our binding
                val rig = Rig(checkNotNull(map.get()), checkNotNull(view.get()), runtime)
                try {
                    beforeStart(rig)
                    rig.main { rig.start() }
                    body(rig)
                } finally { rig.main { rig.close() } }
            }
        } finally {
            GoogleMapSurfaceTestHooks.reset()
            GoogleFogCoverageArm.reset()
            database.close()
        }
    }

    private class Rig(val map: GoogleMap, private val view: MapView, private val runtime: FogRuntime) {
        val states = CopyOnWriteArrayList<GoogleCanonicalFogState>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val onState = AtomicReference<((GoogleCanonicalFogState) -> Unit)?>(null)
        val committedFrames = AtomicInteger(0)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val terminal = AtomicBoolean(false)
        private lateinit var cover: GoogleFogSafetyOverlay
        lateinit var binding: GoogleCanonicalFogSurfaceBinding
        fun start() {
            cover = GoogleFogSafetyOverlay(view)
            val context = GoogleFogSurfaceContext(
                map = map, runtime = runtime,
                onStateChanged = { state ->
                    cover.setVisible(state.coverUp)
                    states += state
                    // Like an Activity lifecycle event: outside the current binding mutation.
                    Handler(Looper.getMainLooper()).post { onState.get()?.invoke(state) }
                },
                onTerminalFailure = { terminal.set(true) }, onFogFailure = { failures += it },
                onFogRendered = null, onProofObserved = {}, exclusionZonesForProof = { emptyList() },
                onUnprovableProofPlan = { false }, onProofAccepted = {}, installFaultForTesting = null,
                awaitCoverCommitted = { cover.awaitCommitted().also { if (it) committedFrames.incrementAndGet() } },
            )
            binding = context.canonicalBinding()
            binding.onMapLoaded()
        }
        fun resetCanonicalWhileCovered() {
            check(states.last().coverUp)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.replaceCanonicalData { delay(150L) }
            }
        }
        fun close() { onState.set(null); scope.cancel(); binding.release(); cover.release() }
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun diagnostics(): String {
            val value = AtomicReference<String>()
            main { value.set(binding.describeForTesting()) }
            return value.get()
        }
        fun awaitProven(generation: Long) = await {
            states.lastOrNull()?.let { it.installedGeneration == generation && it.pendingGeneration == null && !it.coverUp } == true
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 25_000L
            while (!condition() && !terminal.get() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20L)
            assertFalse("unexpected terminal: ${diagnostics()}; $failures", terminal.get())
            assertTrue("binding did not reach the requested state: ${diagnostics()}; $failures", condition())
        }
    }
}
