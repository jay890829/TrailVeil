package app.trailveil.map.fog

import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPointReader
import kotlin.math.floor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FogPlaceholderExactTest {
    @Test fun uniformPlaceholderMatchesPriorCompositionAndNeverTouchesDataOrCaches() {
        var cases = 0
        for (tileSize in listOf(1, 7, 32, 256)) for (alpha in listOf(0, 1, 127, 128, 184, 255)) {
            val style = FogRenderStyle(tileSize = tileSize, fogAlpha = alpha)
            val memory = FogMemoryTileCache(4L * 1024 * 1024)
            val sentinelKey = FogTileKey(0, 0, 0, 13)
            val sentinel = FogPixelMask(2, 2, byteArrayOf(0, 1, 127, -1))
            memory.put(sentinelKey, sentinel)
            val pipeline = FogTilePipeline(memory, null) { _, _ -> error("pipeline render") }
            val coordinator = FogViewportCoordinator(
                ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> error("canonical read") }),
                pipeline, style, renderVersion = 13, mosaicPaddingTiles = 2,
            )
            for (zoom in listOf(-0.5, 0.0, 1.75, 2.0, 12.75, 22.0, 30.0)) {
                for (center in listOf(GeoPoint(0.0, 0.0), GeoPoint(85.0, 179.99), GeoPoint(-85.0, -179.99),
                    GeoPoint(25.0, 539.99), GeoPoint(-25.0, -539.99))) {
                    val request = FogViewportRequest(center, zoom)
                    val keys = FogViewportTileGrid.around(center, floor(zoom).toInt().coerceIn(0, 22), 13)
                    // Frozen b54 algorithm: render empty tiles, compose them, then anchor.
                    val renderer = FogTileRenderer(style)
                    val expected = FogPocMosaic.compose(keys.map { FogMosaicTile(it, renderer.render(it, emptyList())) })
                        .anchoredNear(center.longitude)
                    val actual = coordinator.placeholder(request)
                    assertSame(request, actual.request)
                    assertEquals(keys, actual.keys)
                    assertNull(actual.queryBounds)
                    assertNull(actual.nativeGeometryStatus)
                    val mosaic = actual.presentation as FogTileMosaic
                    assertEquals(expected, mosaic)
                    assertEquals(expected.bounds.westLongitude.toRawBits(), mosaic.bounds.westLongitude.toRawBits())
                    assertEquals(expected.bounds.eastLongitude.toRawBits(), mosaic.bounds.eastLongitude.toRawBits())
                    assertEquals(expected.bounds.northLatitude.toRawBits(), mosaic.bounds.northLatitude.toRawBits())
                    assertEquals(expected.bounds.southLatitude.toRawBits(), mosaic.bounds.southLatitude.toRawBits())
                    assertEquals(setOf(sentinelKey), pipeline.cachedKeys())
                    assertSame(sentinel, pipeline.loadCached(sentinelKey)?.mask)
                    assertEquals(FogTileLoadSource.MEMORY, pipeline.loadCached(sentinelKey)?.source)
                    assertNotSame(mosaic.mask, (coordinator.placeholder(request).presentation as FogTileMosaic).mask)
                    cases++
                }
            }
        }
        assertEquals(840, cases)
    }

    @Test fun placeholderDoesNotReplaceTheActiveViewportForRevealMerges() = runTest {
        val style = FogRenderStyle(tileSize = 32)
        val pipeline = FogTilePipeline(FogMemoryTileCache(4L * 1024 * 1024), null, FogTileRenderer(style)::render)
        val coordinator = FogViewportCoordinator(
            ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> emptyList() }), pipeline, style,
        )
        val origin = GeoPoint(25.0, 121.0)
        val initial = coordinator.render(FogViewportRequest(origin, 14.0))
        coordinator.placeholder(FogViewportRequest(GeoPoint(25.0, 122.0), 14.0))
        val merge = coordinator.mergePersistedReveals(listOf(FogRevealUpdate(origin)))
        assertTrue(merge.updatedKeys.isNotEmpty())
        assertTrue(merge.updatedKeys.all { it in initial.keys })
        assertTrue(merge.missingKeys.none { it in initial.keys })
    }
}
