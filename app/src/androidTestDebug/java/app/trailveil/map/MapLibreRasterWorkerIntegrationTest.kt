package app.trailveil.map

import android.os.Looper
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.map.*
import app.trailveil.map.fog.*
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap

/** Reflection keeps this APK linkable with b48; these worker assertions require the candidate. */
class MapLibreRasterWorkerIntegrationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun productionRasterAndPlaceholderPackingRunOffMain() = verifyProductionPacking(nativeRequested = false, nativeSucceeds = false)
    @Test fun nativeFallbackUsesPreparedRasterPixels() = verifyProductionPacking(nativeRequested = true, nativeSucceeds = false)
    @Test fun successfulNativeCanonicalDoesNotPrepareRasterPixels() = verifyProductionPacking(nativeRequested = true, nativeSucceeds = true)

    private fun verifyProductionPacking(nativeRequested: Boolean, nativeSucceeds: Boolean) {
        val priorNative = MapLibreVectorFogState.trackEnabled
        val priorVector = MapLibreVectorFogState.enabled
        MapLibreVectorFogState.trackEnabled = nativeRequested
        MapLibreVectorFogState.enabled = false
        val chunks = Collections.synchronizedMap(IdentityHashMap<FogTileMosaic, Int>())
        val ranOnMain = AtomicBoolean()
        val canonical = AtomicReference<FogViewportPresentation>()
        val completed = AtomicBoolean()
        val checkpointPrepared = AtomicBoolean()
        val placeholderStarts = AtomicInteger()
        val placeholderProbe = observePlaceholderStart { job ->
            if (Looper.myLooper() == Looper.getMainLooper()) ranOnMain.set(true)
            checkNotNull(job)
            placeholderStarts.incrementAndGet()
        }
        val probe = observeChunks { mosaic, until, job ->
            if (Looper.myLooper() == Looper.getMainLooper()) ranOnMain.set(true)
            checkNotNull(job)
            chunks[mosaic] = until
        }
        try {
            val runtime = runtime(if (nativeRequested) fixtureEngine(nativeSucceeds) else null)
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(), provider = provider(), fallbackTimeoutMillis = 100L,
                    fogRuntime = runtime, fogRequired = true,
                    cameraRequest = MapCameraRequest(1L, CENTER, 16.25),
                    canonicalViewportRequestForTesting = FogViewportRequest(CENTER, 16.25),
                    suppressFogCameraReactionsForTesting = true,
                    canonicalFogInstallCheckpointForTesting = { checkpoint ->
                        if (checkpoint.phase == CanonicalFogInstallCheckpointPhase.BEFORE_STYLE_INSTALL) {
                            check(Looper.myLooper() == Looper.getMainLooper())
                            val presentation = checkpoint.render.presentation
                            canonical.set(presentation)
                            checkpointPrepared.set(when (presentation) {
                                is FogTileMosaic -> chunks[presentation] == presentation.mask.width * presentation.mask.height
                                is FogNativePresentation -> true
                            })
                        }
                    },
                    onFogRendered = { completed.set(true) },
                )
            }
            composeRule.waitUntil(30_000L) { completed.get() }
            assertFalse("A real production packing callback ran on Main", ranOnMain.get())
            assertTrue("Placeholder construction must begin on the worker", placeholderStarts.get() > 0)
            assertTrue("Canonical installation did not consume its own completed worker preparation", checkpointPrepared.get())
            val observed = synchronized(chunks) { chunks.entries.map { it.key to it.value } }
            assertTrue("Initial opaque placeholder must also have worker preparation", observed.any { (mosaic, end) ->
                mosaic.tileCount == 9 && end == mosaic.mask.width * mosaic.mask.height
            })
            if (nativeSucceeds) {
                assertTrue(canonical.get() is FogNativePresentation)
                assertTrue("Native canonical must not build an unused 5x5 raster payload", observed.none { it.first.tileCount == 25 })
            } else {
                val raster = canonical.get() as FogTileMosaic
                assertEquals(25, raster.tileCount)
                assertEquals(raster.mask.width * raster.mask.height, chunks[raster])
            }
        } finally {
            probe.close()
            placeholderProbe.close()
            MapLibreVectorFogState.trackEnabled = priorNative
            MapLibreVectorFogState.enabled = priorVector
        }
    }

    @Test fun rekeyDuringPackingCancelsOldPixelsBeforeInstallation() {
        val priorNative = MapLibreVectorFogState.trackEnabled
        val priorVector = MapLibreVectorFogState.enabled
        MapLibreVectorFogState.trackEnabled = false
        MapLibreVectorFogState.enabled = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = AtomicBoolean()
        val oldMosaic = AtomicReference<FogTileMosaic>()
        val oldJob = AtomicReference<Job>()
        val oldChunks = AtomicInteger()
        val oldInstalled = AtomicBoolean()
        val oldPublished = AtomicBoolean()
        val successor = AtomicBoolean()
        val request = mutableStateOf(FogViewportRequest(CENTER, 16.25))
        val probe = observeChunks { mosaic, until, job ->
            check(Looper.myLooper() != Looper.getMainLooper())
            if (mosaic.tileCount == 25 && until == 12_288 && once.compareAndSet(false, true)) {
                oldMosaic.set(mosaic)
                oldJob.set(checkNotNull(job))
                oldChunks.incrementAndGet()
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "packing release timeout" }
            } else if (mosaic === oldMosaic.get()) {
                oldChunks.incrementAndGet()
            }
        }
        try {
            val runtime = runtime(null)
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(), provider = provider(), fallbackTimeoutMillis = 100L,
                    fogRuntime = runtime, fogRequired = true,
                    cameraRequest = MapCameraRequest(1L, CENTER, 16.25),
                    canonicalViewportRequestForTesting = request.value,
                    suppressFogCameraReactionsForTesting = true,
                    canonicalFogInstallCheckpointForTesting = { checkpoint ->
                        if (checkpoint.render.presentation === oldMosaic.get()) oldInstalled.set(true)
                    },
                    onFogRendered = { render ->
                        if (render.presentation === oldMosaic.get()) oldPublished.set(true)
                        if (render.request.mapZoom == 15.25) successor.set(true)
                    },
                )
            }
            composeRule.waitUntil(30_000L) { entered.count == 0L }
            composeRule.runOnUiThread { request.value = FogViewportRequest(CENTER, 15.25) }
            composeRule.waitUntil(15_000L) { oldJob.get()?.isCancelled == true }
            release.countDown()
            composeRule.waitUntil(30_000L) { successor.get() && oldJob.get()?.isCompleted == true }
            assertEquals("Cancelled packing must not process another chunk", 1, oldChunks.get())
            assertFalse("Old pixels reached the style-install checkpoint", oldInstalled.get())
            assertFalse("Old pixels were published", oldPublished.get())
        } finally {
            release.countDown()
            probe.close()
            MapLibreVectorFogState.trackEnabled = priorNative
            MapLibreVectorFogState.enabled = priorVector
        }
    }

    @Test fun replacingStyleDuringPlaceholderPackingCancelsTheOldPreparation() {
        val priorNative = MapLibreVectorFogState.trackEnabled
        val priorVector = MapLibreVectorFogState.enabled
        MapLibreVectorFogState.trackEnabled = false
        MapLibreVectorFogState.enabled = false
        val release = CountDownLatch(1)
        val blockArmed = AtomicBoolean()
        val held = CopyOnWriteArrayList<Pair<FogTileMosaic, Job>>()
        val oldMosaic = AtomicReference<FogTileMosaic>()
        val oldJob = AtomicReference<Job>()
        val laterOldChunks = AtomicInteger()
        val completed = AtomicBoolean()
        val view = AtomicReference<MapView>()
        val providerState = mutableStateOf(provider())
        val probe = observeChunks { mosaic, until, job ->
            check(Looper.myLooper() != Looper.getMainLooper())
            if (blockArmed.get() && mosaic.tileCount == 9 && until == 4096) {
                check(held.size < 8) { "unexpected placeholder churn in test setup" }
                held += mosaic to checkNotNull(job)
                check(release.await(20, TimeUnit.SECONDS)) { "placeholder release timeout" }
            } else if (mosaic === oldMosaic.get()) laterOldChunks.incrementAndGet()
        }
        try {
            val runtime = runtime(null)
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(), provider = providerState.value, fallbackTimeoutMillis = 100L,
                    fogRuntime = runtime, fogRequired = true, onMapViewCreatedForTesting = view::set,
                    cameraRequest = MapCameraRequest(1L, CENTER, 16.25),
                    suppressFogCameraReactionsForTesting = true,
                    onFogRendered = { completed.set(true) },
                )
            }
            // Initial attachment can supersede its own placeholder. Finish that setup before
            // deliberately creating the exact style/preparation interval under test.
            composeRule.waitUntil(30_000L) { completed.get() }
            val map = AtomicReference<MapLibreMap>()
            composeRule.runOnUiThread { view.get().getMapAsync(map::set) }
            composeRule.waitUntil(10_000L) { map.get() != null }
            composeRule.runOnUiThread {
                completed.set(false)
                blockArmed.set(true)
                providerState.value = MapProviderConfiguration("u2-preparing-style", "https://tiles.invalid/u2-preparing-style")
            }
            val priorStyle = AtomicReference<org.maplibre.android.maps.Style>()
            composeRule.waitUntil(15_000L) {
                // Select and replace in the same Main turn: first attachment may have abandoned
                // an earlier placeholder, but it cannot invalidate this chosen active job between
                // our assertion and the deliberately triggered style change.
                composeRule.runOnUiThread {
                    held.lastOrNull { it.second.isActive }?.let { (mosaic, job) ->
                        assertFalse("Canonical must wait behind the blocked placeholder", completed.get())
                        assertTrue(job.isActive)
                        oldMosaic.set(mosaic); oldJob.set(job)
                        priorStyle.set(checkNotNull(map.get().style))
                        blockArmed.set(false)
                        providerState.value = MapProviderConfiguration("u2-replaced-style", "https://tiles.invalid/u2-replaced-style")
                    }
                }
                oldJob.get() != null
            }
            composeRule.waitUntil(15_000L) { oldJob.get()?.isCancelled == true }
            release.countDown()
            composeRule.waitUntil(30_000L) { completed.get() && held.all { it.second.isCompleted } }
            assertEquals("Old placeholder cannot pack another chunk after cancellation", 0, laterOldChunks.get())
            composeRule.runOnIdle { assertTrue("A new loaded style must own the successor", map.get().style !== priorStyle.get() && map.get().style?.isFullyLoaded == true) }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "U2_PLACEHOLDER_STYLE activeBeforeReplacement=true heldJobs=${held.size} allCompleted=true laterSelectedChunks=0\n")
            })
        } finally {
            release.countDown(); probe.close()
            MapLibreVectorFogState.trackEnabled = priorNative
            MapLibreVectorFogState.enabled = priorVector
        }
    }

    @Test fun replacingStyleBeforePlaceholderConstructionCancelsWithoutPacking() {
        val priorNative = MapLibreVectorFogState.trackEnabled
        val priorVector = MapLibreVectorFogState.enabled
        MapLibreVectorFogState.trackEnabled = false
        MapLibreVectorFogState.enabled = false
        val release = CountDownLatch(1)
        val armed = AtomicBoolean()
        val held = CopyOnWriteArrayList<Job>()
        val selected = AtomicReference<Job>()
        val selectedChunks = AtomicInteger()
        val completed = AtomicBoolean()
        val providerState = mutableStateOf(provider())
        val startProbe = observePlaceholderStart { job ->
            check(Looper.myLooper() != Looper.getMainLooper())
            if (armed.get()) {
                check(held.size < 8)
                held += checkNotNull(job)
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        val chunkProbe = observeChunks { _, _, job ->
            if (job === selected.get()) selectedChunks.incrementAndGet()
        }
        try {
            val runtime = runtime(null)
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(), provider = providerState.value, fallbackTimeoutMillis = 100L,
                    fogRuntime = runtime, fogRequired = true,
                    cameraRequest = MapCameraRequest(1L, CENTER, 16.25),
                    suppressFogCameraReactionsForTesting = true,
                    onFogRendered = { completed.set(true) },
                )
            }
            composeRule.waitUntil(30_000L) { completed.get() }
            composeRule.runOnUiThread {
                completed.set(false); armed.set(true)
                providerState.value = MapProviderConfiguration("w2-start-held", "https://tiles.invalid/w2-start-held")
            }
            composeRule.waitUntil(15_000L) {
                composeRule.runOnUiThread {
                    held.lastOrNull { it.isActive }?.let { job ->
                        assertFalse(completed.get())
                        selected.set(job)
                        armed.set(false)
                        providerState.value = MapProviderConfiguration("w2-start-replaced", "https://tiles.invalid/w2-start-replaced")
                    }
                }
                selected.get() != null
            }
            composeRule.waitUntil(15_000L) { selected.get()?.isCancelled == true }
            release.countDown()
            composeRule.waitUntil(30_000L) { completed.get() && held.all { it.isCompleted } }
            assertEquals("Cancelled placeholder must not reach pixel packing", 0, selectedChunks.get())
        } finally {
            release.countDown(); startProbe.close(); chunkProbe.close()
            MapLibreVectorFogState.trackEnabled = priorNative
            MapLibreVectorFogState.enabled = priorVector
        }
    }

    private fun observePlaceholderStart(callback: (Job?) -> Unit): AutoCloseable {
        val type = Class.forName("app.trailveil.map.RasterFogPreparationTestProbe")
        val instance = type.getField("INSTANCE").get(null)
        val getter = type.methods.single { it.name.startsWith("getOnPlaceholderStart") && it.parameterCount == 0 }
        val setter = type.methods.single { it.name.startsWith("setOnPlaceholderStart") && it.parameterCount == 1 }
        val previous = getter.invoke(instance)
        setter.invoke(instance, callback)
        return AutoCloseable { setter.invoke(instance, previous) }
    }

    private fun observeChunks(callback: (FogTileMosaic, Int, Job?) -> Unit): AutoCloseable {
        val type = Class.forName("app.trailveil.map.RasterFogPreparationTestProbe")
        val instance = type.getField("INSTANCE").get(null)
        val getter = type.methods.single { it.name.startsWith("getOnChunk") && it.parameterCount == 0 }
        val setter = type.methods.single { it.name.startsWith("setOnChunk") && it.parameterCount == 1 }
        val previous = getter.invoke(instance)
        setter.invoke(instance, callback)
        return AutoCloseable { setter.invoke(instance, previous) }
    }

    private fun runtime(engine: FogNativeGeometryEngine?): FogRuntime = FogRuntime(
        FogViewportCoordinator(
            ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> emptyList() }),
            FogTilePipeline(FogMemoryTileCache(16L * 1024 * 1024), null, FogTileRenderer()::render),
            mosaicPaddingTiles = 2, nativeGeometryEngine = engine,
        ),
        object : PersistedTrackPointChangeFeed {
            override suspend fun latestCursor() = PersistedPointCursor(0L)
            override fun revisionsAfter(cursor: PersistedPointCursor): Flow<PersistedPointRevision> = emptyFlow()
            override suspend fun readChangesAfter(cursor: PersistedPointCursor, limit: Int) = emptyList<PersistedTrackPointChange>()
        },
    )

    private fun fixtureEngine(succeeds: Boolean) = object : FogNativeGeometryEngine {
        override val description = "u2-native-fixture"
        override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle, read: suspend (ViewportBounds) -> List<TrackSegment>): FogNativeGeometry? =
            if (!succeeds) null else FogNativeGeometry(bounds, listOf(FogNativePolygon(listOf(
                GeoPoint(bounds.southLatitude, bounds.westLongitude), GeoPoint(bounds.southLatitude, bounds.eastLongitude),
                GeoPoint(bounds.northLatitude, bounds.eastLongitude), GeoPoint(bounds.northLatitude, bounds.westLongitude),
                GeoPoint(bounds.southLatitude, bounds.westLongitude),
            ))), description)
        override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) = Unit
        override fun clear() = Unit
    }

    private fun provider() = MapProviderConfiguration("u2-worker", "https://tiles.invalid/u2-worker")
    private companion object { val CENTER = GeoPoint(25.0330, 121.5654) }
}
