package app.trailveil.map

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.googlepoc.FogContinuityArm
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoExplorationTrack
import app.trailveil.harness.TrackRegionGeometryEngine
import app.trailveil.map.fog.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same real SDK map + runtime; Room is isolated and no device history is changed. */
@RunWith(AndroidJUnit4::class)
class GoogleCanonicalReplacementTest {
    @Test fun clearReplacesTheInstalledNativeSurfaceAndLongReplacementDoesNotTimeout() = trial(native = true)
    @Test fun clearReplacesTheInstalledRasterSurface() = trial(native = false)

    private fun trial(native: Boolean) {
        assumeTrue("requires keyed Google test build", BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, TrailVeilDatabase::class.java)
            .addCallback(TrailVeilDatabase.invariantCallback).build()
        val dao = database.recordingDao()
        val failClear = AtomicBoolean(false)
        val geometry = TrackRegionGeometryEngine()
        val faultableGeometry = object : FogNativeGeometryEngine by geometry {
            override fun clear() {
                check(!failClear.get()) { "injected persistent cache clear failure" }
                geometry.clear()
            }
        }
        val runtime = FogRuntime(FogViewportCoordinator(
            ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
            FogTilePipeline(FogMemoryTileCache(8L * 1024L * 1024L), null,
                renderMask = FogTileRenderer()::render),
            nativeGeometryEngine = faultableGeometry), RoomPersistedTrackPointChangeFeed(dao))
        val anchor = DemoExplorationSeeder.Anchor(25.0296, 121.5357, false)
        val sample = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).first().first()
        val point = GeoPoint(sample.latitude, sample.longitude)
        val state = AtomicReference<GoogleCanonicalFogState?>()
        val map = AtomicReference<GoogleMap?>()
        val terminal = AtomicReference<ProviderFallbackReason?>()
        GoogleMapSurfaceTestHooks.reset()
        (if (native) FogContinuityArm.TRACK_NATIVE_FLOOR else FogContinuityArm.BASELINE).install()
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.fogRuntime = runtime
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(1L, point, zoom = 16.0)
        GoogleMapSurfaceTestHooks.onMapReady.set(map::set)
        GoogleMapSurfaceTestHooks.onFogState.set(state::set)
        GoogleMapSurfaceTestHooks.onTerminalFailure.set(terminal::set)
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                fun installedAfter(previous: Long?) = await {
                    val current = state.get()
                    current?.installedGeneration != null && current.installedGeneration != previous &&
                        !current.coverUp && !current.terminal && current.pendingGeneration == null
                }
                installedAfter(null)
                val stableMap = checkNotNull(map.get())
                val fogged = sampleColor(stableMap, point)
                val initial = state.get()?.installedGeneration
                runBlocking(Dispatchers.IO) { DemoExplorationSeeder.seedHere(database, runtime, anchor) }
                installedAfter(initial)
                val revealed = sampleColor(stableMap, point)
                assertTrue("sensitivity control cannot distinguish a revealed walk from fog",
                    colorDistance(fogged, revealed) > 30)
                val seeded = state.get()?.installedGeneration
                if (native) {
                    failClear.set(true)
                    assertTrue(runBlocking(Dispatchers.IO) {
                        runCatching { DemoExplorationSeeder.clear(database, runtime) }.isFailure
                    })
                    await { state.get()?.coverUp == true }
                    Thread.sleep(2_500L) // spans multiple synchronization AND generation-retry ticks
                    assertTrue("failed synchronization lowered its cover", state.get()?.coverUp == true)
                    assertEquals("stale cache installed a new generation", seeded, state.get()?.installedGeneration)
                    assertEquals("generation retry ran without a baseline", null, state.get()?.pendingGeneration)
                    failClear.set(false)
                } else {
                    runBlocking(Dispatchers.IO) { DemoExplorationSeeder.clear(database, runtime) }
                }
                installedAfter(seeded)
                val cleared = sampleColor(stableMap, point)
                assertTrue("cleared walk is still visible in SDK snapshot",
                    colorDistance(fogged, cleared) <= 15)
                assertTrue("map was recreated", stableMap === map.get())
                if (native) {
                    val previous = state.get()?.installedGeneration
                    runBlocking(Dispatchers.IO) {
                        runtime.replaceCanonicalData {
                            // Longer than BOTH existing 20s renderer deadlines.
                            delay(21_500L)
                            assertTrue("replacement wait lost its cover", state.get()?.coverUp == true)
                            assertTrue("replacement wait was not published", state.get()?.canonicalReplacementInProgress == true)
                            assertEquals("fixture wait triggered terminal failure", null, terminal.get())
                        }
                    }
                    installedAfter(previous)
                }
                assertEquals(null, terminal.get())
            }
        } finally {
            GoogleMapSurfaceTestHooks.reset()
            GoogleFogCoverageArm.reset()
            database.close()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000L
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50L)
        assertTrue("canonical surface did not reach the requested new proven generation", condition())
    }

    private fun sampleColor(map: GoogleMap, point: GeoPoint): Int {
        Thread.sleep(400L)
        val completed = CountDownLatch(1)
        val bitmap = AtomicReference<Bitmap?>()
        val pixel = AtomicReference<android.graphics.Point>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            pixel.set(map.projection.toScreenLocation(LatLng(point.latitude, point.longitude)))
            map.snapshot { image -> bitmap.set(image); completed.countDown() }
        }
        assertTrue("SDK snapshot timed out", completed.await(10, TimeUnit.SECONDS))
        val image = checkNotNull(bitmap.get())
        return try { image[pixel.get().x, pixel.get().y] } finally { image.recycle() }
    }

    private fun colorDistance(a: Int, b: Int): Int = listOf(
        kotlin.math.abs(Color.red(a) - Color.red(b)),
        kotlin.math.abs(Color.green(a) - Color.green(b)),
        kotlin.math.abs(Color.blue(a) - Color.blue(b)),
    ).max()
}
