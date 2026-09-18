package app.trailveil.harness

import app.trailveil.data.map.*
import app.trailveil.map.fog.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor

class TrackRawReadEnvelopeTest {
    private val style = FogRenderStyle()
    private fun rectangle(x0: Int, x1: Int, y0: Int, y1: Int) = FogTileBounds(
        x0 / 4096.0 * 360.0 - 180.0, WebMercator.latitudeAtNormalizedY(y1 / 4096.0),
        x1 / 4096.0 * 360.0 - 180.0, WebMercator.latitudeAtNormalizedY(y0 / 4096.0))

    @Test fun exactBoundaryAndNextDownUpDomainsMatchTheFrozenPartitionArithmetic() {
        var cases = 0
        for (x in listOf(0, 1, 2048, 4093)) for (y in listOf(700, 2047, 3300)) {
            val box = rectangle(x, x + 2, y, y + 2)
            for (east in listOf(Math.nextDown(box.eastLongitude), box.eastLongitude, Math.nextUp(box.eastLongitude))) {
                val bounds = box.copy(eastLongitude = east)
                // Frozen b51 equations, preserving evaluation/nextDown/clamping order.
                val x0 = floor((bounds.westLongitude + 180.0) / 360.0 * 4096).toInt().coerceIn(0, 4095)
                val x1 = floor(Math.nextDown((bounds.eastLongitude + 180.0) / 360.0 * 4096)).toInt().coerceIn(x0, 4095)
                val y0 = floor(WebMercator.normalizedY(bounds.northLatitude) * 4096).toInt().coerceIn(0, 4095)
                val y1 = floor(Math.nextDown(WebMercator.normalizedY(bounds.southLatitude) * 4096)).toInt().coerceIn(y0, 4095)
                val expected = buildList { for (row in y0..y1) for (column in x0..x1) add(ViewportBounds(
                    WebMercator.latitudeAtNormalizedY((row + 1.0) / 4096), WebMercator.latitudeAtNormalizedY(row.toDouble() / 4096),
                    column.toDouble() / 4096 * 360 - 180, (column + 1.0) / 4096 * 360 - 180)) }
                assertEquals(expected, TrackRegionGeometryEngine().rawReadWindows(bounds, style)); cases++
            }
        }
        assertEquals(36, cases)
    }

    @Test fun unsupportedAndBudgetFallbackKeepTheirOriginalReasonsWithoutMetadataMutation() = runBlocking {
        for ((bounds, reason) in listOf(FogTileBounds(179.0, 0.0, 181.0, 1.0) to "wrapped-or-polar",
            FogTileBounds(0.0, 80.0, 1.0, 81.0) to "wrapped-or-polar", rectangle(2048, 2057, 2040, 2049) to "partition-budget")) {
            val engine = TrackRegionGeometryEngine()
            assertNull(engine.rawReadWindows(bounds, style)); assertEquals("awaiting", engine.description)
            assertNull(engine.render(bounds, style) { error("fallback must not read") })
            assertEquals("raster-fallback:$reason", engine.description)
        }
    }

    @Test fun coldMetadataDoesNotInitializeJtsOrChangeCounters() {
        val engine = TrackRegionGeometryEngine()
        val lazyFactory = engine.javaClass.getDeclaredField("factory\$delegate").apply { isAccessible = true }.get(engine) as Lazy<*>
        assertFalse(lazyFactory.isInitialized())
        assertFalse(checkNotNull(engine.rawReadWindows(rectangle(2048, 2050, 2047, 2048), style)).isEmpty())
        assertFalse(lazyFactory.isInitialized())
        assertEquals(0L, engine.cacheHits); assertEquals(0L, engine.cacheMisses); assertEquals("awaiting", engine.description)
    }

    @Test fun warmPreviewDoesNotTouchEitherLruAndRadiusOrInvalidationReopensAllDomains() = runBlocking {
        val engine = TrackRegionGeometryEngine()
        val a = rectangle(2048, 2050, 2047, 2048)
        val b = rectangle(2051, 2052, 2047, 2048)
        engine.render(a, style) { emptyList() }; engine.render(b, style) { emptyList() }
        fun state(): List<Any?> {
            fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
            val cache = field(engine, "cache") as Map<*, *>
            val union = field(engine, "unionCache")!!
            return listOf(cache.keys.toList(), (field(union, "entries") as Map<*, *>).keys.toList(), engine.cacheHits,
                engine.cacheMisses, engine.description, engine.lastFallback, field(engine, "radius"))
        }
        val before = state()
        assertTrue(checkNotNull(engine.rawReadWindows(a, style)).isEmpty())
        assertFalse(checkNotNull(engine.rawReadWindows(a, style.copy(revealRadiusMeters = 50.0))).isEmpty())
        assertEquals(before, state())
        engine.invalidate(listOf(FogRevealUpdate(GeoPoint(0.04, 0.04))), style)
        assertFalse(checkNotNull(engine.rawReadWindows(a, style)).isEmpty())
        val actual = engine.render(a, style) { emptyList() }
        val reference = TrackRegionGeometryEngine().render(a, style) { emptyList() }
        assertEquals(reference?.polygons, actual?.polygons)
    }

    @Test fun anyMissEnvelopeCoversActualBatchedSqlEvenIfAnEarlierMissEvictsAWarmEntry() = runBlocking {
        for (fill in listOf(false, true)) {
            val engine = TrackRegionGeometryEngine()
            engine.render(rectangle(2050, 2051, 2047, 2048), style) { emptyList() }
            if (fill) repeat(63) { engine.render(rectangle(2100 + it, 2101 + it, 2047, 2048), style) { emptyList() } }
            val reads = mutableListOf<ViewportBounds>()
            val source = ViewportTrackDataSource { south, north, interval -> reads += ViewportBounds(south, north, interval.west, interval.east); emptyList() }
            val c = FogViewportCoordinator(source, FogTilePipeline(FogMemoryTileCache(1), null) { _, _ -> error("native only") }, nativeGeometryEngine = engine)
            val target = rectangle(2048, 2052, 2047, 2048)
            val envelope = checkNotNull(c.nativeRawReadEnvelope(target))
            assertTrue(reads.isEmpty())
            assertNotNull(c.renderNativeGeometry(target))
            assertTrue(reads.isNotEmpty())
            assertTrue(reads.all { envelope.south <= it.south && envelope.north >= it.north && envelope.west <= it.west && envelope.east >= it.east })
            assertNull(c.nativeRawReadEnvelope(target))
        }
    }
}
