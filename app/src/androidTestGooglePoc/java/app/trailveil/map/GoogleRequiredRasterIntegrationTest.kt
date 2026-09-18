package app.trailveil.map

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.*
import app.trailveil.map.fog.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoogleRequiredRasterIntegrationTest {
    @Test fun missingFloorAndLateObservedLodRefuseTheRealBindingProof() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, TrailVeilDatabase::class.java)
            .addCallback(TrailVeilDatabase.invariantCallback).build()
        val dao = db.recordingDao()
        val runtime = FogRuntime(FogViewportCoordinator(ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
            FogTilePipeline(FogMemoryTileCache(16L * 1024 * 1024), null, FogTileRenderer()::render),
            nativeGeometryEngine = app.trailveil.harness.TrackRegionGeometryEngine()), RoomPersistedTrackPointChangeFeed(dao))
        val state = AtomicReference<GoogleCanonicalFogState>()
        val view = AtomicReference<com.google.android.gms.maps.MapView>()
        GoogleMapSurfaceTestHooks.reset()
        app.trailveil.googlepoc.FogContinuityArm.TRACK_NATIVE.install()
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.fogRuntime = runtime
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(1, GeoPoint(25.03, 121.535), zoom = 16.0)
        GoogleMapSurfaceTestHooks.cameraRequestDurationMillis = 1
        GoogleMapSurfaceTestHooks.onMapViewCreated.set(view::set)
        GoogleMapSurfaceTestHooks.onFogState.set(state::set)
        try {
            androidx.test.core.app.ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                while (state.get()?.let { s -> s.installedGeneration != null && !s.coverUp && s.pendingGeneration == null } != true &&
                    android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                instrumentation.runOnMainSync {
                    val current = checkNotNull(state.get())
                    check(!current.coverUp && current.pendingGeneration == null && !current.terminal)
                    val generation = checkNotNull(current.installedGeneration)
                    val binding = checkNotNull(view.get()?.getTag(app.trailveil.R.id.map_fog_binding_instance) as? GoogleCanonicalFogSurfaceBinding)
                    @Suppress("UNCHECKED_CAST")
                    val generations = field(binding, "masksByGeneration") as MutableMap<Long, Map<FogTileKey, FogPixelMask>>
                    val original = checkNotNull(generations[generation])
                    val coverage = binding.javaClass.getDeclaredMethod("currentCoverageRequest")
                        .apply { isAccessible = true }.invoke(binding) as FogViewportCoverageRequest
                    val floor = FogViewportCoveragePlanner(paddingTiles = 0).plan(coverage).keys
                    assertTrue(original.keys.containsAll(floor))
                    val fresh = binding.javaClass.getDeclaredMethod("freshProofPlan", Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
                    val continuation = object : kotlin.coroutines.Continuation<FogSnapshotVisualProbePlan?> {
                        override val context = kotlin.coroutines.EmptyCoroutineContext
                        override fun resumeWith(result: Result<FogSnapshotVisualProbePlan?>) = error("missing masks must refuse before suspension")
                    }
                    generations[generation] = original - floor.first()
                    assertNull(fresh.invoke(binding, generation, 1, continuation))
                    generations[generation] = original
                    val create = binding.javaClass.getDeclaredMethod("createProvider", java.lang.Long::class.java).apply { isAccessible = true }
                    val provider = create.invoke(binding, generation)
                    check(FogTileKey(3, 0, 0, FogRenderVersions.CURRENT) !in original)
                    provider.javaClass.getMethod("getTile", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType).invoke(provider, 0, 0, 3)
                    assertNull(fresh.invoke(binding, generation, 1, continuation))
                    val refusals = field(binding, "proofPlanRefusals") as Map<*, *>
                    assertTrue(refusals.keys.any { it.toString().startsWith("floorShort:") })
                    assertTrue(refusals.keys.any { it.toString().startsWith("actualShort:") })
                }
            }
        } finally {
            GoogleMapSurfaceTestHooks.reset()
            GoogleFogCoverageArm.reset()
            db.close()
        }
    }

    @Test fun sealedRoomMemoMissesOuterRingAndKeepsExactFallbackWithEviction() = runBlocking<Unit>(Dispatchers.Default) {
        for (cacheBytes in listOf(65_536L, 16L * 1024 * 1024)) {
            val phases = CopyOnWriteArrayList<String>()
            val currentMemo = AtomicReference<ViewportRawPointMemo?>()
            val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
                TrailVeilDatabase::class.java).addCallback(TrailVeilDatabase.invariantCallback)
                .setQueryCallback({ sql, _ ->
                    if (sql.contains("FROM track_points p") && sql.contains("BETWEEN")) {
                        phases += currentMemo.get()?.let(::phase) ?: "NONE"
                    }
                }, Runnable::run).build()
            try {
                val center = GeoPoint(25.03, 121.535)
                val required = FogViewportTileGrid.around(center, 16, 1, 0).toSet()
                val full = FogViewportTileGrid.around(center, 16, 1, 2).toSet()
                val floorBounds = FogPocMosaic.layout(required.toList(), 256).anchoredNear(center.longitude).bounds
                val engine = object : FogNativeGeometryEngine {
                    override val description = "raster-fallback:x3-room-control"
                    override fun rawReadWindows(bounds: FogTileBounds, style: FogRenderStyle) =
                        listOf(ViewportBounds(bounds.southLatitude, bounds.northLatitude, bounds.westLongitude, bounds.eastLongitude))
                    override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle,
                        read: suspend (ViewportBounds) -> List<TrackSegment>): FogNativeGeometry? {
                        assertEquals("REUSE", phase(checkNotNull(currentMemo.get())))
                        val before = phases.size
                        assertTrue(read(rawReadWindows(bounds, style).single()).isEmpty())
                        assertEquals("same floor envelope should reuse captured Room rows", before, phases.size)
                        return null
                    }
                    override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) = Unit
                    override fun clear() = Unit
                }
                fun coordinator(native: FogNativeGeometryEngine?) = FogViewportCoordinator(
                    ViewportTrackDataSource(RoomViewportTrackPointReader(db.recordingDao())),
                    FogTilePipeline(FogMemoryTileCache(cacheBytes), null, FogTileRenderer()::render),
                    nativeGeometryEngine = native)
                val reference = coordinator(null)
                val expected = FogRequestedTileWindowRenderer(FogViewportBatchSubrenderer(reference::renderTiles)).render(center, full)
                phases.clear()
                val candidate = coordinator(engine)
                val renderer = FogRequestedTileWindowRenderer(FogViewportBatchSubrenderer(candidate::renderTiles))
                val memo = ViewportRawPointMemo(isCurrent = { true },
                    requiredCaptureBounds = checkNotNull(candidate.nativeRawReadEnvelope(floorBounds)))
                currentMemo.set(memo)
                val actual = try {
                    withContext(memo) {
                        renderFogWithRequiredMasks(full, required, true, 256,
                            render = { renderer.render(center, it) }, prepare = {
                                memo.seal()
                                candidate.renderNativeGeometry(floorBounds) != null
                            })
                    }
                } finally { withContext(NonCancellable) { memo.close() } }
                assertEquals(expected.keys.toList(), actual.keys.toList())
                expected.forEach { (key, mask) -> assertArrayEquals(mask.copyAlpha(), checkNotNull(actual[key]).copyAlpha()) }
                assertTrue("first stage must capture actual Room output", phases.contains("CAPTURE"))
                assertTrue("outer ring must query Room in sealed REUSE phase", phases.contains("REUSE"))
                assertEquals("CLOSED", phase(memo))
                assertTrue((field(memo, "entries") as List<*>).isEmpty())
            } finally { db.close() }
        }
    }

    private fun phase(memo: ViewportRawPointMemo): String = field(memo, "phase").toString()
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
}
