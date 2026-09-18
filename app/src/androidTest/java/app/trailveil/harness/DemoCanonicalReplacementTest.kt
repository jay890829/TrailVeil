package app.trailveil.harness

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.map.fog.FogMemoryTileCache
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.FogRenderVersions
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileKey
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.WebMercator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room + one warm runtime, using an isolated in-memory database; no device history writes. */
@RunWith(AndroidJUnit4::class)
class DemoCanonicalReplacementTest {
    @Test fun clearAndReseedInvalidateBothWarmDerivedRepresentations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TrailVeilDatabase::class.java)
            .addCallback(TrailVeilDatabase.invariantCallback).build()
        withContext(Dispatchers.Default) {
            val dao = database.recordingDao()
            val preserved = dao.startSession(
                RecordingSessionEntity(startedAt = 1_000L, status = RecordingStatus.ACTIVE,
                    createdAppVersion = "canonical-reset-test"),
                TrackSegmentEntity(sessionId = 0L, sequence = 0L, startedAt = 1_000L,
                    startReason = "SESSION_START"),
            )
            dao.appendAcceptedPoint(TrackPointEntity(sessionId = preserved.sessionId,
                segmentId = preserved.segmentId, sequence = 0L, timestamp = 1_000L,
                latitude = -25.5, longitude = -130.5, horizontalAccuracy = 5.0,
                isMock = false), distanceDeltaMeters = 0.0)
            fun preservedPoints(): Long = database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM track_points WHERE session_id = ? AND is_mock = 0",
                arrayOf<Any>(preserved.sessionId),
            ).use { check(it.moveToFirst()); it.getLong(0) }
            assertEquals("preservation control was not inserted", 1L, preservedPoints())
            val runtime = FogRuntime(
                FogViewportCoordinator(ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
                    FogTilePipeline(FogMemoryTileCache(8L * 1024L * 1024L), null,
                        renderMask = FogTileRenderer()::render),
                    nativeGeometryEngine = TrackRegionGeometryEngine()),
                RoomPersistedTrackPointChangeFeed(dao),
            )
            val a = DemoExplorationSeeder.Anchor(25.0296, 121.5357, false)
            val b = DemoExplorationSeeder.Anchor(23.5, 120.5, false)
            runtime.changeSynchronizer.synchronizeTo()
            try {
                DemoExplorationSeeder.seedHere(database, runtime, a)
                runtime.changeSynchronizer.synchronizeTo()
                assertRevealed(runtime, a, true)
                assertRevealed(runtime, a, true) // warm native partitions AND raster mask cache

                DemoExplorationSeeder.clear(database, runtime)
                // No manual cache clear and no runtime recreation: these are the production calls.
                runtime.changeSynchronizer.synchronizeTo()
                assertRevealed(runtime, a, false)

                DemoExplorationSeeder.seedHere(database, runtime, b)
                runtime.changeSynchronizer.synchronizeTo()
                assertRevealed(runtime, a, false)
                assertRevealed(runtime, b, true)

                DemoExplorationSeeder.seedHere(database, runtime, a)
                runtime.changeSynchronizer.synchronizeTo()
                assertRevealed(runtime, b, false)
                assertRevealed(runtime, a, true)

                val cancelled = kotlinx.coroutines.CancellationException("cancel after a committed session")
                val failure = runCatching {
                    DemoExplorationSeeder.seedRegion(database, runtime, sessions = 1, pointsPerSession = 128) { _, _ ->
                        throw cancelled
                    }
                }.exceptionOrNull()
                assertTrue("mutation cancellation was swallowed", failure === cancelled)
                assertEquals(0L, runtime.canonicalEpoch.value % 2L)
                assertTrue(runtime.changeSynchronizer.synchronizeTo().bootstrapped)
                assertRevealed(runtime, a, false)
                val partial = DemoTaiwanAnchors.anchors(1).single()
                assertRevealed(runtime, DemoExplorationSeeder.Anchor(partial.latitude, partial.longitude, false), true)
                DemoExplorationSeeder.clear(database, runtime)
                assertEquals("non-demo history changed", 1L, preservedPoints())
            } finally {
                database.close()
            }
        }
    }

    private suspend fun assertRevealed(
        runtime: FogRuntime,
        anchor: DemoExplorationSeeder.Anchor,
        expected: Boolean,
    ) {
        // Select an actual deterministic track point, rather than assuming the anchor is walked.
        val sample = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).first().first()
        val point = GeoPoint(sample.latitude, sample.longitude)
        val tile = WebMercator.tile(point, 16)
        val key = FogTileKey(16, tile.x, tile.y, FogRenderVersions.CURRENT)
        val mask = runtime.viewportCoordinator.renderTiles(FogViewportRequest(point, 16.0), listOf(key))
            .tiles.single().mask
        val rasterRevealed = mask.copyAlpha().any { (it.toInt() and 0xff) < runtime.viewportCoordinator.style.fogAlpha }
        assertEquals("raster reveal must match current Room fixture", expected, rasterRevealed)
        val bounds = FogTileBounds(point.longitude - 0.004, point.latitude - 0.004,
            point.longitude + 0.004, point.latitude + 0.004)
        val native = checkNotNull(runtime.viewportCoordinator.renderNativeGeometry(bounds))
        // Full fog has exactly the rectangle's four corners and no holes. Any subtraction changes it.
        val fullArea = (bounds.eastLongitude - bounds.westLongitude) *
            (bounds.northLatitude - bounds.southLatitude)
        fun area(ring: List<GeoPoint>): Double = kotlin.math.abs(ring.indices.sumOf { i ->
            val p = ring[i]; val q = ring[(i + 1) % ring.size]
            (p.longitude - bounds.westLongitude) * (q.latitude - bounds.southLatitude) -
                (q.longitude - bounds.westLongitude) * (p.latitude - bounds.southLatitude)
        }) / 2.0
        val fogArea = native.polygons.sumOf { area(it.shell) - it.holes.sumOf(::area) }
        assertTrue("native fog cannot exceed its rectangle", fogArea <= fullArea + 1e-12)
        assertEquals("native reveal must match current Room fixture", expected, fogArea < fullArea - 1e-10)
    }
}
