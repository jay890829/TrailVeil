package app.trailveil.map

import android.os.Bundle
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPointReader
import app.trailveil.map.fog.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses only stable b45 APIs so the same test APK can reject the original Main-thread conversion. */
class MapLibreNativeWorkerIntegrationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun rekeyDuringPreparationCancelsOldPayloadBeforeStyleInstallation() {
        // Pins both fields because every sibling native-arm fixture does
        // (MapLibreFinalGeometryCostTest:87-88, MapLibreFinalGeometryOpportunityTest:106-107,
        // MapLibreIdleReuseTest:85-86) - NOT because it was ever the cause. `enabled` is false at
        // declaration and its only product-side writer hardcodes false (FogMosaicPaddingSeam.kt:19,35),
        // and nothing in app/src sets it true, so pinning it cannot change behaviour. The
        // order-dependence this comment used to allege was never real: the cause was a latch that
        // only the first payload could arm, repaired below by guarding every generation until one does.
        val previous = MapLibreVectorFogState.trackEnabled
        val previousVector = MapLibreVectorFogState.enabled
        MapLibreVectorFogState.trackEnabled = true
        MapLibreVectorFogState.enabled = false
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrailVeilDatabase::class.java)
            .addCallback(TrailVeilDatabase.invariantCallback).build()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Set when one payload's conversion has actually reached the latch index. Until then every
        // generation is guarded; see the render() comment for why only guarding the first is a race.
        val armed = AtomicBoolean(false)
        val oldJob = AtomicReference<Job>()
        val oldGeometry = AtomicReference<FogNativeGeometry>()
        // The read counter belonging to the payload that armed the latch - the one the final
        // "stopped within one chunk" assertion is about. Reads by payloads that were superseded
        // before arming are counted in totalReads instead, so they cannot inflate that window.
        val oldReads = AtomicReference<AtomicInteger>()
        val totalReads = AtomicInteger()
        val guardedPayloads = AtomicInteger()
        // Stage counters for the first wait. Without them a stall anywhere between setContent and
        // the worker's conversion arrives as one ComposeTimeoutException naming a line number, and
        // every diagnosis has to start by guessing which stage stopped.
        val renderCalls = AtomicInteger()
        val fogRenders = AtomicInteger()
        val installCheckpoints = AtomicInteger()
        val lastPublishedZoom = AtomicReference<Double>()
        val oldInstalled = AtomicBoolean(false)
        val oldPublished = AtomicBoolean(false)
        val successor = AtomicBoolean(false)
        val view = AtomicReference<MapView>()
        val request = mutableStateOf(FogViewportRequest(GeoPoint(0.0, 0.0), 16.0))
        val engine = object : FogNativeGeometryEngine {
            override val description = "trackNative[rekey-fixture]"
            override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle, read: suspend (ViewportBounds) -> List<TrackSegment>): FogNativeGeometry {
                renderCalls.incrementAndGet()
                val corners = listOf(GeoPoint(bounds.southLatitude, bounds.westLongitude), GeoPoint(bounds.southLatitude, bounds.eastLongitude),
                    GeoPoint(bounds.northLatitude, bounds.eastLongitude), GeoPoint(bounds.northLatitude, bounds.westLongitude),
                    GeoPoint(bounds.southLatitude, bounds.westLongitude))
                // Guard EVERY generation until the latch is armed, not only the first one.
                //
                // The product cancels a superseded conversion, which is precisely the behaviour this
                // case exists to prove. The converter notices cancellation on a 256-point stride
                // (MapLibreVectorFog.kt: `if (index % 256 == 0) context.ensureActive()`), so a
                // cancelled conversion stops with 0, 257 or 513 reads and never anything in between -
                // and every failing run on record reported exactly 0 or 257, i.e. cancelled just
                // before the latch index. Guarding only the first payload therefore made the
                // precondition a race the fixture loses whenever anything supersedes generation one:
                // no later payload could arm the latch, so the wait timed out having proved nothing.
                // Guarding every generation removes the race without weakening any assertion, because
                // the payload that blocks names itself below.
                if (armed.get()) return FogNativeGeometry(bounds, listOf(FogNativePolygon(corners)), description)
                val myJob = checkNotNull(currentCoroutineContext()[Job])
                val myGeometry = AtomicReference<FogNativeGeometry>()
                val myReads = AtomicInteger()
                // 1024 perimeter points and a closing point. Block inside the real worker converter.
                val guarded = object : AbstractList<GeoPoint>() {
                    override val size = 1025
                    override fun get(index: Int): GeoPoint {
                        myReads.incrementAndGet()
                        totalReads.incrementAndGet()
                        // Only the first payload to arrive here is the one under test; a later
                        // guarded payload must pass straight through rather than block as well.
                        if (index == 257) {
                            // Holds for every guarded payload, not just the one under test.
                            check(Looper.myLooper() != Looper.getMainLooper())
                        }
                        if (index == 257 && !armed.get()) {
                            // Resolve the one statement that can throw BEFORE claiming the latch.
                            // Claiming first would let a throw here leave `armed` true with nothing
                            // published: `entered` would never count down, and 30s later the case
                            // would report "no payload armed the latch" - a fixture defect wearing
                            // the costume of a product failure. After the CAS succeeds only
                            // AtomicReference.set and countDown remain, and neither throws.
                            val claimed = checkNotNull(myGeometry.get())
                            if (armed.compareAndSet(false, true)) {
                                oldJob.set(myJob)
                                oldGeometry.set(claimed)
                                oldReads.set(myReads)
                                entered.countDown()
                                check(release.await(20, TimeUnit.SECONDS)) { "rekey release timeout" }
                            }
                        }
                        if (index == 1024) return corners.first()
                        val edge = index / 256
                        val fraction = (index % 256) / 256.0
                        return GeoPoint(corners[edge].latitude + (corners[edge + 1].latitude - corners[edge].latitude) * fraction,
                            corners[edge].longitude + (corners[edge + 1].longitude - corners[edge].longitude) * fraction)
                    }
                }
                guardedPayloads.incrementAndGet()
                return FogNativeGeometry(bounds, listOf(FogNativePolygon(guarded)), description).also(myGeometry::set)
            }
            override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) = Unit
            override fun clear() = Unit
        }
        val runtime = FogRuntime(
            viewportCoordinator = FogViewportCoordinator(ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> emptyList() }),
                FogTilePipeline(FogMemoryTileCache(1_048_576), null, FogTileRenderer()::render), nativeGeometryEngine = engine),
            pointChanges = RoomPersistedTrackPointChangeFeed(database.recordingDao()),
        )
        try {
            composeRule.setContent {
                TrailVeilMapSurface(modifier = Modifier.fillMaxSize(), provider = MapProviderConfiguration("rekey-control", "https://tiles.invalid/rekey-control"),
                    fallbackTimeoutMillis = 100L, fogRuntime = runtime, fogRequired = true,
                    cameraRequest = MapCameraRequest(1L, GeoPoint(0.0, 0.0), 16.0), onMapViewCreatedForTesting = view::set,
                    canonicalViewportRequestForTesting = request.value, suppressFogCameraReactionsForTesting = true,
                    canonicalFogInstallCheckpointForTesting = { checkpoint ->
                        installCheckpoints.incrementAndGet()
                        val installed = (checkpoint.render.presentation as? FogNativePresentation)?.geometry
                        // `=== oldGeometry.get()` alone would match null against null for every
                        // checkpoint that fires before a payload arms the latch.
                        if (installed != null && installed === oldGeometry.get()) oldInstalled.set(true)
                    },
                    onFogRendered = { render ->
                        fogRenders.incrementAndGet()
                        lastPublishedZoom.set(render.request.mapZoom)
                        val geometry = (render.presentation as? FogNativePresentation)?.geometry
                        if (geometry != null && geometry === oldGeometry.get()) oldPublished.set(true)
                        if (geometry != null && render.request.mapZoom == 15.0) successor.set(true)
                    })
            }
            try {
                composeRule.waitUntil(30_000L) { entered.count == 0L }
            } catch (timeout: ComposeTimeoutException) {
                // This wait is the one that used to fail in the field, and it failed BEFORE the rekey
                // the case exists to test. Name the stage rather than the line: mapView missing means
                // the surface never came up, and renderCalls=0 means the fog pipeline never asked the
                // engine for geometry.
                //
                // guardedReads here prints totalReads - the SUM over every guarded payload, not one
                // payload's count - so it is not a stride reading: 258 and 514 are reachable, while
                // 513 is NOT reachable in this branch, since passing index 257 arms the latch and
                // this branch only runs when nothing armed it. Read `armed` first: if it is false no
                // conversion completed at all, because before arming every payload is guarded and any
                // conversion that completes must pass index 257. Per-payload counts are not printed.
                throw AssertionError(
                    "the worker never entered the guarded conversion within 30s, so this run never " +
                        "reached the rekey: mapView=" + (if (view.get() != null) "created" else "none") +
                        " renderCalls=" + renderCalls.get() +
                        " guardedPayloads=" + guardedPayloads.get() +
                        " guardedReads=" + totalReads.get() +
                        " armed=" + armed.get() +
                        " fogRenders=" + fogRenders.get() +
                        " installCheckpoints=" + installCheckpoints.get() +
                        " lastPublishedZoom=" + lastPublishedZoom.get() +
                        " trackEnabled=" + MapLibreVectorFogState.trackEnabled,
                    timeout,
                )
            }
            composeRule.runOnUiThread {
                checkNotNull(view.get()).getMapAsync { it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 15.0)) }
                request.value = FogViewportRequest(GeoPoint(0.0, 0.0), 15.0)
            }
            composeRule.waitUntil(10_000L) { oldJob.get()?.isCancelled == true }
            release.countDown()
            composeRule.waitUntil(10_000L) { oldJob.get()?.isCompleted == true }
            composeRule.waitUntil(30_000L) { successor.get() }
            composeRule.waitForIdle()
            assertFalse("cancelled payload reached style installation checkpoint", oldInstalled.get())
            assertFalse("cancelled payload was published", oldPublished.get())
            val blockedReads = checkNotNull(oldReads.get()) { "no payload armed the latch" }.get()
            assertTrue("cancelled conversion must stop within one 256-point chunk: $blockedReads", blockedReads in 258..514)
            // Streamed on SUCCESS, which is the point: the same counters already travel with the
            // failure, so the only thing missing is what a HEALTHY run looks like. Two failures
            // carried renderCalls=4 (257 guarded reads) and renderCalls=6 (0 reads), which reads
            // like the first payload being superseded before the latch - but "more than one
            // generation means superseded" is a threshold nobody has measured, and gating on an
            // unmeasured threshold is exactly what this suite spent the round undoing. Collect the
            // passing distribution first; a later round can decide whether renderCalls > 1 before
            // entry is a fixture precondition failure or ordinary behaviour.
            //
            // `guardedPayloads` joined this line in v28. It was deliberately left off in v27 because
            // adding it alone would have cost three full matrices to make a green test print one more
            // number; HANDOVER recorded that the next build rebuilt for other reasons should carry it,
            // and the fixture repairs are that build. Without it the PASS line cannot distinguish
            // `renderCalls` (generations the surface issued) from payloads that actually reached the
            // guarded loop, which is the pair the failure line has always printed.
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "TrailVeil rekey fixture PASS: renderCalls=${renderCalls.get()} " +
                            "guardedPayloads=${guardedPayloads.get()} " +
                            "guardedReads=$blockedReads totalGuardedReads=${totalReads.get()} " +
                            "fogRenders=${fogRenders.get()} " +
                            "installCheckpoints=${installCheckpoints.get()} " +
                            "lastPublishedZoom=${lastPublishedZoom.get()}\n",
                    )
                },
            )
        } finally {
            release.countDown()
            MapLibreVectorFogState.trackEnabled = previous
            MapLibreVectorFogState.enabled = previousVector
            database.close()
        }
    }

    @Test fun productionNativeInstallationReadsGeometryOnlyOffMain() {
        val previous = MapLibreVectorFogState.trackEnabled
        MapLibreVectorFogState.trackEnabled = true
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrailVeilDatabase::class.java)
            .addCallback(TrailVeilDatabase.invariantCallback).build()
        val mainRead = AtomicBoolean(false)
        val reads = AtomicInteger()
        val rendered = AtomicReference<FogViewportRender>()
        val engine = object : FogNativeGeometryEngine {
            override val description: String = "trackNative[thread-fixture]"
            override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle, read: suspend (ViewportBounds) -> List<TrackSegment>): FogNativeGeometry {
                val points = listOf(GeoPoint(bounds.southLatitude, bounds.westLongitude), GeoPoint(bounds.southLatitude, bounds.eastLongitude),
                    GeoPoint(bounds.northLatitude, bounds.eastLongitude), GeoPoint(bounds.northLatitude, bounds.westLongitude),
                    GeoPoint(bounds.southLatitude, bounds.westLongitude))
                val guarded = object : AbstractList<GeoPoint>() {
                    override val size: Int get() = points.size
                    override fun get(index: Int): GeoPoint {
                        reads.incrementAndGet()
                        if (Looper.myLooper() == Looper.getMainLooper()) mainRead.set(true)
                        return points[index]
                    }
                }
                return FogNativeGeometry(bounds, listOf(FogNativePolygon(guarded)), description)
            }
            override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) = Unit
            override fun clear() = Unit
        }
        val runtime = FogRuntime(
            viewportCoordinator = FogViewportCoordinator(ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> emptyList() }),
                FogTilePipeline(FogMemoryTileCache(1_048_576), null, FogTileRenderer()::render), nativeGeometryEngine = engine),
            pointChanges = RoomPersistedTrackPointChangeFeed(database.recordingDao()),
        )
        try {
            composeRule.setContent {
                TrailVeilMapSurface(modifier = Modifier.fillMaxSize(), provider = MapProviderConfiguration("worker-control", "https://tiles.invalid/worker-control"),
                    fallbackTimeoutMillis = 100L, fogRuntime = runtime, fogRequired = true,
                    cameraRequest = MapCameraRequest(1L, GeoPoint(0.0, 0.0), 16.0), onFogRendered = rendered::set)
            }
            composeRule.waitUntil(30_000L) { rendered.get() != null }
            assertTrue(rendered.get().presentation is FogNativePresentation)
            assertTrue("the real native path must have consumed geometry", reads.get() >= 5)
            assertFalse("native geometry was converted on Main", mainRead.get())
        } finally {
            MapLibreVectorFogState.trackEnabled = previous
            database.close()
        }
    }
}
