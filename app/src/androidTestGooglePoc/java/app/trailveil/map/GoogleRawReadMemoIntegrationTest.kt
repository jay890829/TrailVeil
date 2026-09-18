package app.trailveil.map

import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.*
import app.trailveil.googlepoc.FogContinuityArm
import app.trailveil.map.fog.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real binding lifecycle and actual Room reader; isolated empty canonical DB makes all-fog exact. */
class GoogleRawReadMemoIntegrationTest {
    @Test fun realTrackPreparationUsesSealedMemoAndClosesBeforePublication() = trial(false)
    @Test fun stoppingDuringNativePreparationCancelsAndClosesTheAttemptMemo() = trial(true)

    private fun trial(cancel: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val queries = CopyOnWriteArrayList<String>()
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, TrailVeilDatabase::class.java)
            .addCallback(TrailVeilDatabase.invariantCallback)
            .setQueryCallback({ sql, _ -> queries += sql }, Runnable::run).build()
        val entered = CountDownLatch(1)
        val held = CompletableDeferred<Unit>()
        val observed = AtomicReference<ViewportRawPointMemo>()
        val ownerJob = AtomicReference<Job>()
        val failure = AtomicReference<Throwable>()
        val published = CountDownLatch(1)
        val engine = object : FogNativeGeometryEngine {
            override val description = "raster-fallback:test-observer"
            override fun rawReadWindows(bounds: FogTileBounds, style: FogRenderStyle): List<ViewportBounds> {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                return listOf(ViewportBounds(bounds.southLatitude, bounds.northLatitude, bounds.westLongitude, bounds.eastLongitude))
            }
            override fun clear() = Unit
            override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) = Unit
            override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle, read: suspend (ViewportBounds) -> List<TrackSegment>): FogNativeGeometry? {
                try {
                    val memo = checkNotNull(currentCoroutineContext()[ViewportRawPointMemo])
                    observed.set(memo)
                    ownerJob.set(checkNotNull(currentCoroutineContext()[Job]))
                    assertEquals("REUSE", phase(memo))
                    val before = queries.count { it.contains("FROM track_points p") && it.contains("BETWEEN") }
                    assertTrue(read(ViewportBounds(bounds.southLatitude, bounds.northLatitude, bounds.westLongitude, bounds.eastLongitude)).isEmpty())
                    assertEquals(before, queries.count { it.contains("FROM track_points p") && it.contains("BETWEEN") })
                    entered.countDown()
                    if (cancel) held.await()
                    return null // existing raster fallback; no artificial native geometry
                } catch (error: Throwable) {
                    if (error !is CancellationException) failure.set(error)
                    entered.countDown(); throw error
                }
            }
        }
        val dao = database.recordingDao()
        val runtime = FogRuntime(FogViewportCoordinator(ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
            FogTilePipeline(FogMemoryTileCache(16L * 1024 * 1024), null, FogTileRenderer()::render), nativeGeometryEngine = engine),
            RoomPersistedTrackPointChangeFeed(dao))
        GoogleMapSurfaceTestHooks.reset()
        FogContinuityArm.TRACK_NATIVE_FLOOR.install()
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.fogRuntime = runtime
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(1, GeoPoint(25.0296, 121.5357), zoom = 12.0)
        GoogleMapSurfaceTestHooks.cameraRequestDurationMillis = 1
        GoogleMapSurfaceTestHooks.onFogState.set {
            if (it.installedGeneration != null && it.pendingGeneration == null && observed.get() != null) {
                try { assertEquals("memo must already be closed at publication", "CLOSED", phase(observed.get())) }
                catch (error: Throwable) { failure.set(error) }
                published.countDown()
            }
        }
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
                assertTrue("native observer reached", entered.await(25, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("observer failed", it) }
                if (cancel) {
                    assertTrue("held native attempt must still be active before stop", ownerJob.get().isActive)
                    assertEquals("REUSE", phase(observed.get()))
                    scenario.moveToState(Lifecycle.State.CREATED)
                } else assertTrue("canonical published", published.await(25, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("publication observer failed", it) }
                val memo = checkNotNull(observed.get())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (phase(memo) != "CLOSED" && System.nanoTime() < deadline) Thread.sleep(20)
                assertEquals("CLOSED", phase(memo))
                val entries = ViewportRawPointMemo::class.java.getDeclaredField("entries").apply { isAccessible = true }.get(memo) as List<*>
                assertTrue(entries.isEmpty())
            }
        } finally { held.complete(Unit); GoogleMapSurfaceTestHooks.reset(); database.close() }
    }
    private fun phase(memo: ViewportRawPointMemo): String = ViewportRawPointMemo::class.java.getDeclaredField("phase")
        .apply { isAccessible = true }.get(memo).toString()
}
