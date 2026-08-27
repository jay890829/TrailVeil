package app.trailveil.map.fog

import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.ViewportTrackPointReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FogViewportBatchCoordinatorTest {
    @Test
    fun fullProviderWindowUsesOneCanonicalReadAndWarmsEveryRequestedTile() = runTest {
        var pointReads = 0
        val point = ViewportTrackPoint(
            pointId = 1L,
            sessionId = 1L,
            segmentId = 1L,
            segmentSequence = 0L,
            pointSequence = 0L,
            latitude = 25.033964,
            longitude = 121.564468,
        )
        val coordinator = coordinator {
            pointReads += 1
            listOf(point)
        }
        val coverageRequest = FogViewportCoverageRequest(
            center = GeoPoint(point.latitude, point.longitude),
            floorZoom = 14,
            nearLeft = GeoPoint(25.07, 121.51),
            farLeft = GeoPoint(25.07, 121.62),
            farRight = GeoPoint(24.99, 121.62),
            nearRight = GeoPoint(24.99, 121.51),
        )
        val plan = FogViewportCoveragePlanner(maxTiles = 256).plan(coverageRequest)
        val tileRequest = FogViewportRequest(coverageRequest.center, 14.0)

        val first = coordinator.renderTiles(tileRequest, plan.keys)
        val warm = coordinator.renderTiles(tileRequest, plan.keys)

        assertEquals(1, pointReads)
        assertEquals(plan.keys, first.keys)
        assertEquals(plan.keys, warm.keys)
        assertEquals(first.masksByKey(), warm.masksByKey())
        assertNotNull(first.queryBounds)
        assertEquals(null, warm.queryBounds)
    }

    @Test
    fun malformedOrOverBudgetProviderWindowsFailBeforeCanonicalReads() {
        var pointReads = 0
        val coordinator = coordinator {
            pointReads += 1
            emptyList()
        }
        val request = FogViewportRequest(GeoPoint(0.0, 0.0), 2.0)
        val shiftedRows = listOf(
            FogTileKey(2, 3, 1, FogRenderVersions.CURRENT),
            FogTileKey(2, 0, 1, FogRenderVersions.CURRENT),
            FogTileKey(2, 0, 2, FogRenderVersions.CURRENT),
            FogTileKey(2, 1, 2, FogRenderVersions.CURRENT),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runTest { coordinator.renderTiles(request, shiftedRows) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runTest {
                val duplicate = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
                coordinator.renderTiles(request, listOf(duplicate, duplicate))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runTest {
                coordinator.renderTiles(
                    request,
                    List(FogViewportCoordinator.MAX_PROVIDER_VIEWPORT_TILES + 1) {
                        FogTileKey(2, 0, 0, FogRenderVersions.CURRENT)
                    },
                )
            }
        }
        assertEquals(0, pointReads)
    }

    private fun coordinator(
        read: suspend () -> List<ViewportTrackPoint>,
    ): FogViewportCoordinator {
        val style = FogRenderStyle()
        return FogViewportCoordinator(
            trackDataSource = ViewportTrackDataSource(
                ViewportTrackPointReader { _, _, _ -> read() },
            ),
            pipeline = FogTilePipeline(
                memoryCache = FogMemoryTileCache(maxBytes = 16L * 1024L * 1024L),
                diskCache = null,
                renderMask = FogTileRenderer(style)::render,
            ),
            style = style,
        )
    }
}
