package app.trailveil.map.fog

import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.ViewportTrackPointReader
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class FogNativeFirstRenderTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun nativeSuccessTouchesNeitherRasterCacheRoomNorPaintForBothFootprints() = runTest {
        for (padding in listOf(1, 2)) {
            val cache = FogMemoryTileCache(1_000_000)
            // Pipeline is deliberately final. Poison its cache's backing map so even a warm
            // loadCached attempt fails, without adding a production-only testing callback.
            val backing = FogMemoryTileCache::class.java.getDeclaredField("entries")
            backing.isAccessible = true
            backing.set(cache, object : LinkedHashMap<FogTileKey, FogPixelMask>() {
                override fun get(key: FogTileKey): FogPixelMask? = error("native touched raster cache")
                override fun put(key: FogTileKey, value: FogPixelMask): FogPixelMask? = error("native stored raster")
            })
            val geometry = Geometry()
            val coordinator = FogViewportCoordinator(
                ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> error("native triggered raster Room read") }),
                FogTilePipeline(cache, null) { _, _ -> error("native painted raster") },
                nativeGeometryEngine = geometry, mosaicPaddingTiles = padding,
            )
            val probe = FogRasterWorkProbe()
            val rendered = withContext(probe) { coordinator.render(request, nativeGeometry = true) }
            val native = rendered.presentation as FogNativePresentation
            assertEquals((padding * 2 + 1) * (padding * 2 + 1), native.tileCount)
            assertEquals((padding * 2 + 1) * 256, native.samplingGridWidth)
            assertEquals(native.bounds, native.geometry.bounds)
            assertNull(rendered.queryBounds)
            assertEquals(1, geometry.renders)
            assertEquals(0L, probe.readNanos.get())
            assertEquals(0L, probe.selectionNanos.get())
            assertEquals(0L, probe.paintNanos.get())
        }
    }

    @Test fun nullGeometryFallbackIsBitIdenticalAndKeepsItsWarmRasterCache() = runTest {
        for (center in listOf(origin, GeoPoint(-25.0, -179.999), GeoPoint(84.9, 30.0))) {
            for (padding in listOf(1, 2)) {
                for (zoom in listOf(12.0, 16.0)) {
                    val points = listOf(point(center))
                    val reference = Rig(points, padding)
                    val measured = Rig(points, padding)
                    measured.geometry.outcome = Outcome.FALLBACK
                    val input = FogViewportRequest(center, zoom)
                    val expected = reference.coordinator.render(input)
                    val actual = measured.coordinator.render(input, nativeGeometry = true)
                    assertEquals(expected.keys, actual.keys)
                    assertEquals(expected.queryBounds, actual.queryBounds)
                    assertEquals(expected.presentation, actual.presentation)
                    assertEquals("raster-fallback:forced", actual.nativeGeometryStatus)
                    val paints = measured.paints
                    val warm = measured.coordinator.render(input, nativeGeometry = true)
                    assertEquals(actual.presentation, warm.presentation)
                    assertEquals(paints, measured.paints)
                    assertNull(warm.queryBounds)
                }
            }
        }
    }

    @Test fun nativeRevealInvalidatesOldRasterWithoutDeltaPaintingAndFallbackReadsTheUpdate() = runTest {
        val disk = FogDiskTileCache(temporary.newFolder(), 4_000_000)
        val rig = Rig(emptyList(), disk = disk)
        val initial = rig.coordinator.render(request)
        val centerKey = initial.keys.single { key -> WebMercator.tile(origin, key.zoom).let { it.x == key.x && it.y == key.y } }
        assertNotNull(rig.pipeline.loadCached(centerKey))
        assertNotNull(disk.get(centerKey))
        rig.coordinator.render(request, nativeGeometry = true)
        val beforeUpdate = rig.paints
        rig.points = listOf(point(origin))
        val merge = rig.coordinator.mergePersistedReveals(listOf(FogRevealUpdate(origin)))
        assertEquals(beforeUpdate, rig.paints)
        assertEquals(1, rig.geometry.invalidations)
        assertTrue(merge.updatedKeys.isEmpty())
        assertTrue(centerKey in merge.missingKeys)
        assertNull(rig.pipeline.loadCached(centerKey))
        assertNull(disk.get(centerKey))
        rig.geometry.outcome = Outcome.FALLBACK
        val fallback = rig.coordinator.render(request, nativeGeometry = true)
        val reference = Rig(rig.points).coordinator.render(request)
        assertEquals(reference.presentation, fallback.presentation)
        assertTrue((fallback.presentation as FogTileMosaic).mask.copyAlpha().any { it.toInt() == 0 })
        rig.coordinator.clearDerivedCache()
        assertEquals(1, rig.geometry.clears)
        assertTrue(rig.pipeline.cachedKeys().isEmpty())
        assertTrue(disk.keys().isEmpty())
    }

    @Test fun failedOrCancelledNativePreparationPreservesThePreviousRasterMaintenance() = runTest {
        for (outcome in listOf(Outcome.FAILURE, Outcome.CANCELLED)) {
            val rig = Rig(emptyList())
            rig.coordinator.render(request)
            rig.geometry.outcome = outcome
            val failure = runCatching {
                rig.coordinator.render(FogViewportRequest(GeoPoint(25.0, 122.0), 16.0), nativeGeometry = true)
            }.exceptionOrNull()
            assertTrue(if (outcome == Outcome.CANCELLED) failure is CancellationException else failure is IllegalStateException)
            val beforeUpdate = rig.paints
            val merge = rig.coordinator.mergePersistedReveals(listOf(FogRevealUpdate(origin)))
            assertTrue("last raster viewport was lost by $outcome", merge.updatedKeys.isNotEmpty())
            assertTrue(rig.paints > beforeUpdate)
        }
    }

    @Test fun aCancelledNullResultCannotPublishAnAlreadyWarmFallbackViewport() = runTest {
        val rig = Rig(emptyList())
        val other = FogViewportRequest(GeoPoint(25.0, 122.0), 16.0)
        rig.coordinator.render(other) // warm target B
        rig.coordinator.render(request) // active A, leaving B warm
        rig.geometry.outcome = Outcome.CANCELLED_NULL
        val attempt = async { rig.coordinator.render(other, nativeGeometry = true) }
        assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
        val beforeUpdate = rig.paints
        val merge = rig.coordinator.mergePersistedReveals(listOf(FogRevealUpdate(origin)))
        assertTrue("cancelled warm B displaced active A", merge.updatedKeys.isNotEmpty())
        assertTrue(rig.paints > beforeUpdate)
    }

    @Test fun layoutAndComposeMatchIndependentPixelsBoundsAndBackdropAtPolesAndDateline() {
        var cases = 0
        for (latitude in listOf(-85.0, 0.0, 85.0)) for (longitude in listOf(-179.999, 30.0, 179.999)) {
            for (zoom in listOf(0, 1, 2, 12, 22)) for (padding in listOf(1, 2)) {
                val center = GeoPoint(latitude, longitude)
                val keys = FogViewportTileGrid.around(center, zoom, 1, padding)
                val tiles = keys.mapIndexed { index, key -> FogMosaicTile(key,
                    FogPixelMask(8, 5, ByteArray(40) { pixel -> ((index * 31 + pixel * 7) % 256).toByte() })) }
                val expected = referenceCompose(tiles).anchoredNear(longitude)
                val actual = FogPocMosaic.compose(tiles).anchoredNear(longitude)
                val layout = FogPocMosaic.layout(keys, 8, 5).anchoredNear(longitude)
                assertEquals(layout, FogPocMosaic.layout(tiles).anchoredNear(longitude))
                val native = FogNativePresentation(FogNativeGeometry(layout.bounds, emptyList(), "fixture"), layout)
                assertEquals(expected, actual)
                assertEquals(expected.bounds, native.bounds)
                assertEquals(expected.mask.width, native.samplingGridWidth)
                assertEquals(expected.mask.height, native.samplingGridHeight)
                assertEquals(FogBackdropGeometry.extent(expected), FogBackdropGeometry.extent(native))
                assertEquals(FogBackdropGeometry.bands(expected), FogBackdropGeometry.bands(native))
                assertEquals(FogBackdropGeometry.worldRepeats(expected), FogBackdropGeometry.worldRepeats(native))
                cases++
            }
        }
        assertEquals(90, cases)
    }

    @Test fun nativePresentationCannotBeSplitIntoInventedRasterTiles() = runTest {
        val native = Rig(emptyList()).coordinator.render(request, nativeGeometry = true)
        assertTrue(runCatching { FogTileMosaicSplitter.split(native) }.exceptionOrNull() is IllegalArgumentException)
    }

    /** Independent pixel-index oracle, rather than reusing the production copy/layout loop. */
    private fun referenceCompose(tiles: List<FogMosaicTile>): FogTileMosaic {
        val columns = tiles.count { it.key.y == tiles.first().key.y }
        val rows = tiles.size / columns
        val width = tiles.first().mask.width * columns
        val height = tiles.first().mask.height * rows
        val alpha = ByteArray(width * height) { offset ->
            val x = offset % width
            val y = offset / width
            val tile = tiles[(y / 5) * columns + x / 8]
            tile.mask.alphaAt(x % 8, y % 5).toByte()
        }
        val first = FogPocTileGrid.bounds(tiles.first().key)
        val last = FogPocTileGrid.bounds(tiles.last().key)
        return FogTileMosaic(FogPixelMask(width, height, alpha),
            FogTileBounds(first.westLongitude, last.southLatitude,
                first.westLongitude + columns * (360.0 / (1 shl tiles.first().key.zoom)), first.northLatitude), tiles.size)
    }

    private enum class Outcome { NATIVE, FALLBACK, FAILURE, CANCELLED, CANCELLED_NULL }
    private class Geometry : FogNativeGeometryEngine {
        var outcome = Outcome.NATIVE
        var renders = 0
        var invalidations = 0
        var clears = 0
        override val description: String get() = if (outcome == Outcome.FALLBACK) "raster-fallback:forced" else "native-fixture"
        override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle,
            read: suspend (ViewportBounds) -> List<TrackSegment>): FogNativeGeometry? {
            renders++
            return when (outcome) {
                Outcome.NATIVE -> FogNativeGeometry(bounds, emptyList(), "native-fixture")
                Outcome.FALLBACK -> null
                Outcome.FAILURE -> error("injected native failure")
                Outcome.CANCELLED -> throw CancellationException("injected native cancellation")
                Outcome.CANCELLED_NULL -> { currentCoroutineContext().cancel(); null }
            }
        }
        override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) { invalidations++ }
        override fun clear() { clears++ }
    }

    private class Rig(var points: List<ViewportTrackPoint>, padding: Int = 1, disk: FogDiskTileCache? = null) {
        val geometry = Geometry()
        var paints = 0
        private val style = FogRenderStyle(tileSize = 64)
        val pipeline = FogTilePipeline(FogMemoryTileCache(4_000_000), disk) { key, segments ->
            paints++
            FogTileRenderer(style).render(key, segments)
        }
        val coordinator = FogViewportCoordinator(
            ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> points }), pipeline, style,
            mosaicPaddingTiles = padding, nativeGeometryEngine = geometry,
        )
    }

    private companion object {
        val origin = GeoPoint(25.0, 121.0)
        val request = FogViewportRequest(origin, 16.0)
        fun point(center: GeoPoint) = ViewportTrackPoint(1L, 1L, 1L, 0, 0, center.latitude, center.longitude)
    }
}
