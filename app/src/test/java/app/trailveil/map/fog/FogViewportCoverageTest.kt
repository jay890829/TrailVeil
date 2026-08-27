package app.trailveil.map.fog

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FogViewportCoverageTest {
    @Test
    fun portraitCoverageIncludesEveryVisibleRowAndIsNotLimitedToThreeRows() {
        val plan = FogViewportCoveragePlanner(maxTiles = 512).plan(
            request(
                center = GeoPoint(0.0, 0.0),
                zoom = 5,
                nearLeft = GeoPoint(50.0, -4.0),
                farLeft = GeoPoint(50.0, 4.0),
                farRight = GeoPoint(-50.0, 4.0),
                nearRight = GeoPoint(-50.0, -4.0),
            ),
        )

        val rows = plan.keys.groupBy(FogTileKey::y)
        assertTrue("portrait coverage must exceed the old 3-row window", rows.size > 3)
        assertEquals(
            (rows.keys.minOrNull()!!..rows.keys.maxOrNull()!!).toList(),
            rows.keys.toList(),
        )
        val firstRow = rows.values.first().map(FogTileKey::x)
        assertTrue(rows.values.all { row -> row.map(FogTileKey::x) == firstRow })
        assertEquals(rows.size * firstRow.size, plan.keys.size)
    }

    @Test
    fun datelineCoverageIsOneUnwrappedRectangleWithCanonicalKeys() {
        val plan = FogViewportCoveragePlanner(paddingTiles = 1, maxTiles = 512).plan(
            request(
                center = GeoPoint(0.0, 179.5),
                zoom = 4,
                nearLeft = GeoPoint(5.0, 178.0),
                farLeft = GeoPoint(5.0, -179.0),
                farRight = GeoPoint(-5.0, -179.0),
                nearRight = GeoPoint(-5.0, 178.0),
            ),
        )

        val rows = plan.keys.groupBy(FogTileKey::y)
        assertEquals(listOf(14, 15, 0, 1), rows.values.first().map(FogTileKey::x))
        assertTrue(plan.keys.all { key -> key.x in 0 until (1 shl 4) })
        assertTrue(plan.keys.none { key -> key.x in 2..13 })
    }

    @Test
    fun worldCopyCenterUsesFloorModWithoutLosingTheLocalTileOrder() {
        val plan = FogViewportCoveragePlanner(paddingTiles = 1, maxTiles = 512).plan(
            request(
                center = GeoPoint(0.0, 540.0),
                zoom = 3,
                nearLeft = GeoPoint(2.0, 539.0),
                farLeft = GeoPoint(2.0, 541.0),
                farRight = GeoPoint(-2.0, 541.0),
                nearRight = GeoPoint(-2.0, 539.0),
            ),
        )

        val x = plan.keys.groupBy(FogTileKey::y).values.first().map(FogTileKey::x)
        assertEquals(listOf(6, 7, 0, 1), x)
        assertTrue(plan.keys.all { key -> key.x in 0 until (1 shl 3) })
    }

    @Test
    fun zoomZeroAndZoomOneNeverCreateInvalidOrDuplicateWorldTiles() {
        val worldCorners = listOf(
            GeoPoint(80.0, -170.0),
            GeoPoint(80.0, 170.0),
            GeoPoint(-80.0, 170.0),
            GeoPoint(-80.0, -170.0),
        )
        val zoomZero = FogViewportCoveragePlanner(maxTiles = 512).plan(
            request(GeoPoint(0.0, 0.0), 0, worldCorners),
        )
        val zoomOne = FogViewportCoveragePlanner(maxTiles = 512).plan(
            request(GeoPoint(0.0, 0.0), 1, worldCorners),
        )

        assertEquals(listOf(FogTileKey(0, 0, 0, FogRenderVersions.CURRENT)), zoomZero.keys)
        assertEquals(4, zoomOne.keys.size)
        assertEquals(setOf(0, 1), zoomOne.keys.map(FogTileKey::x).toSet())
        assertEquals(setOf(0, 1), zoomOne.keys.map(FogTileKey::y).toSet())
    }

    @Test
    fun polarCoverageClipsYInsteadOfWrappingPastTheWorld() {
        val north = FogViewportCoveragePlanner(maxTiles = 512).plan(
            request(
                center = GeoPoint(85.0, 0.0),
                zoom = 4,
                nearLeft = GeoPoint(90.0, -1.0),
                farLeft = GeoPoint(80.0, -1.0),
                farRight = GeoPoint(80.0, 1.0),
                nearRight = GeoPoint(90.0, 1.0),
            ),
        )
        val south = FogViewportCoveragePlanner(maxTiles = 512).plan(
            request(
                center = GeoPoint(-85.0, 0.0),
                zoom = 4,
                nearLeft = GeoPoint(-90.0, -1.0),
                farLeft = GeoPoint(-80.0, -1.0),
                farRight = GeoPoint(-80.0, 1.0),
                nearRight = GeoPoint(-90.0, 1.0),
            ),
        )

        assertEquals(0, north.keys.minOf(FogTileKey::y))
        assertTrue(north.keys.all { key -> key.y in 0 until 16 })
        assertEquals(15, south.keys.maxOf(FogTileKey::y))
        assertTrue(south.keys.all { key -> key.y in 0 until 16 })
    }

    @Test
    fun wideViewportBecomesTheCompleteWorldAtItsZoom() {
        val plan = FogViewportCoveragePlanner(maxTiles = 512).plan(
            request(
                center = GeoPoint(0.0, 0.0),
                zoom = 4,
                nearLeft = GeoPoint(1.0, -179.0),
                farLeft = GeoPoint(1.0, 179.0),
                farRight = GeoPoint(-1.0, 179.0),
                nearRight = GeoPoint(-1.0, -179.0),
            ),
        )

        val x = plan.keys.map(FogTileKey::x).toSet()
        assertEquals((0 until 16).toSet(), x)
        assertTrue(plan.keys.size >= 16)
    }

    @Test
    fun explicitMinus180And180SpanIsConservativelyTreatedAsAFullWorld() {
        val plan = FogViewportCoveragePlanner(maxTiles = 512).plan(
            request(
                center = GeoPoint(0.0, 0.0),
                zoom = 3,
                nearLeft = GeoPoint(1.0, -180.0),
                farLeft = GeoPoint(1.0, 180.0),
                farRight = GeoPoint(-1.0, 180.0),
                nearRight = GeoPoint(-1.0, -180.0),
            ),
        )

        assertEquals((0 until 8).toSet(), plan.keys.map(FogTileKey::x).toSet())
    }

    @Test
    fun plannerSupportsOptionalOffscreenPaddingWithoutRequiringItForCoverage() {
        val viewport = request(
            center = GeoPoint(0.0, 0.0),
            zoom = 5,
            nearLeft = GeoPoint(1.0, -0.1),
            farLeft = GeoPoint(1.0, 0.1),
            farRight = GeoPoint(-1.0, 0.1),
            nearRight = GeoPoint(-1.0, -0.1),
        )
        val zero = FogViewportCoveragePlanner(paddingTiles = 0, maxTiles = 512).plan(viewport)
        val one = FogViewportCoveragePlanner(paddingTiles = 1, maxTiles = 512).plan(viewport)

        assertTrue(one.keys.size > zero.keys.size)
        assertTrue(zero.keys.isNotEmpty())
    }

    @Test
    fun exactViewportMaximumTileEdgesDoNotRequireZeroAreaNeighborTiles() {
        val tile = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(tile)
        val plan = FogViewportCoveragePlanner(maxTiles = 16).plan(
            request(
                center = GeoPoint(
                    latitude = (bounds.northLatitude + bounds.southLatitude) / 2.0,
                    longitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
                ),
                zoom = 2,
                nearLeft = GeoPoint(bounds.southLatitude, bounds.westLongitude),
                farLeft = GeoPoint(bounds.northLatitude, bounds.westLongitude),
                farRight = GeoPoint(bounds.northLatitude, bounds.eastLongitude),
                nearRight = GeoPoint(bounds.southLatitude, bounds.eastLongitude),
            ),
        )

        assertEquals(listOf(tile), plan.keys)
    }

    @Test
    fun malformedOrOverBudgetCoverageFailsBeforeReturningAnyPlan() {
        assertThrows(IllegalArgumentException::class.java) {
            FogViewportCoveragePlanner().plan(
                request(
                    center = GeoPoint(91.0, 0.0),
                    zoom = 4,
                    nearLeft = GeoPoint(0.0, 0.0),
                    farLeft = GeoPoint(0.0, 0.0),
                    farRight = GeoPoint(0.0, 0.0),
                    nearRight = GeoPoint(0.0, 0.0),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FogViewportCoveragePlanner(maxTiles = 1).plan(
                request(
                    center = GeoPoint(0.0, 0.0),
                    zoom = 4,
                    nearLeft = GeoPoint(5.0, -5.0),
                    farLeft = GeoPoint(5.0, 5.0),
                    farRight = GeoPoint(-5.0, 5.0),
                    nearRight = GeoPoint(-5.0, -5.0),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FogViewportCoveragePlanner(paddingTiles = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FogViewportCoveragePlanner().plan(
                request(
                    center = GeoPoint(0.0, Double.MAX_VALUE),
                    zoom = 22,
                    nearLeft = GeoPoint(0.0, Double.MAX_VALUE),
                    farLeft = GeoPoint(0.0, Double.MAX_VALUE),
                    farRight = GeoPoint(0.0, Double.MAX_VALUE),
                    nearRight = GeoPoint(0.0, Double.MAX_VALUE),
                ),
            )
        }
    }

    @Test
    fun batchRenderingUsesOneExactRectangularCallAndReturnsImmutableCoverage() = runTest {
        val viewport = request(
            center = GeoPoint(0.0, 0.0),
            zoom = 4,
            nearLeft = GeoPoint(16.0, -20.0),
            farLeft = GeoPoint(16.0, 20.0),
            farRight = GeoPoint(-16.0, 20.0),
            nearRight = GeoPoint(-16.0, -20.0),
        )
        val desired = FogViewportCoveragePlanner(maxTiles = 256).plan(viewport)
        var calls = 0
        val renderer = FogViewportBatchCoverageRenderer(
            subrenderer = FogViewportBatchSubrenderer { request, keys ->
                calls += 1
                validTileRender(request, keys)
            },
            maxTiles = 256,
        )

        val result = renderer.render(viewport)

        assertEquals(1, calls)
        assertEquals(desired.keySet, result.keys)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (result as MutableMap<FogTileKey, FogPixelMask>).clear()
        }
    }

    @Test
    fun shiftedBatchResponseFailsClosed() {
        val renderer = FogViewportBatchCoverageRenderer(
            subrenderer = FogViewportBatchSubrenderer { request, keys ->
                val tileCount = 1 shl keys.first().zoom
                val shifted = keys.map { key ->
                    key.copy(x = Math.floorMod(key.x + 1, tileCount))
                }
                validTileRender(request, shifted)
            },
            maxTiles = 256,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runTest {
                renderer.render(
                    request(
                        center = GeoPoint(0.0, 0.0),
                        zoom = 4,
                        nearLeft = GeoPoint(4.0, -4.0),
                        farLeft = GeoPoint(4.0, 4.0),
                        farRight = GeoPoint(-4.0, 4.0),
                        nearRight = GeoPoint(-4.0, -4.0),
                    ),
                )
            }
        }
    }

    @Test
    fun batchFailureAndCancellationPropagateWithoutPartialCoverage() {
        val viewport = request(
            center = GeoPoint(0.0, 0.0),
            zoom = 4,
            nearLeft = GeoPoint(4.0, -4.0),
            farLeft = GeoPoint(4.0, 4.0),
            farRight = GeoPoint(-4.0, 4.0),
            nearRight = GeoPoint(-4.0, -4.0),
        )
        val failure = FogViewportBatchCoverageRenderer(
            subrenderer = FogViewportBatchSubrenderer { _, _ ->
                throw IllegalStateException("synthetic batch failure")
            },
        )
        assertThrows(IllegalStateException::class.java) {
            runTest { failure.render(viewport) }
        }

        val cancellation = CancellationException("synthetic batch cancellation")
        val cancelled = FogViewportBatchCoverageRenderer(
            subrenderer = FogViewportBatchSubrenderer { _, _ -> throw cancellation },
        )
        assertEquals(
            cancellation,
            assertThrows(CancellationException::class.java) {
                runTest { cancelled.render(viewport) }
            },
        )
    }

    private fun validTileRender(
        request: FogViewportRequest,
        keys: List<FogTileKey>,
    ): FogViewportTileRender = FogViewportTileRender(
        request = request,
        keys = keys,
        queryBounds = null,
        tiles = keys.map { key ->
            FogMosaicTile(
                key = key,
                mask = FogPixelMask(
                    width = FogTilePngCodec.TILE_SIZE,
                    height = FogTilePngCodec.TILE_SIZE,
                    alpha = ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE) {
                        FogRenderStyle().fogAlpha.toByte()
                    },
                ),
            )
        },
    )

    private fun request(
        center: GeoPoint,
        zoom: Int,
        corners: List<GeoPoint>,
    ): FogViewportCoverageRequest = FogViewportCoverageRequest(
        center = center,
        floorZoom = zoom,
        nearLeft = corners[0],
        farLeft = corners[1],
        farRight = corners[2],
        nearRight = corners[3],
    )

    private fun request(
        center: GeoPoint,
        zoom: Int,
        nearLeft: GeoPoint,
        farLeft: GeoPoint,
        farRight: GeoPoint,
        nearRight: GeoPoint,
    ): FogViewportCoverageRequest = FogViewportCoverageRequest(
        center = center,
        floorZoom = zoom,
        nearLeft = nearLeft,
        farLeft = farLeft,
        farRight = farRight,
        nearRight = nearRight,
    )
}
