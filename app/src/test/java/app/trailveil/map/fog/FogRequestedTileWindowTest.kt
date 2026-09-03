package app.trailveil.map.fog

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FogRequestedTileWindowTest {
    private val planner = FogRequestedTileWindowPlanner(maxTiles = 32)

    @Test
    fun `multiple observed LOD zooms become separate deterministic windows`() {
        val requested = setOf(key(3, 2, 4), key(3, 3, 4), key(2, 1, 2))
        val plan = planner.plan(requested)

        assertEquals(listOf(2, 3), plan.windows.map { it.first().zoom })
        assertTrue(plan.keys.containsAll(requested))
        plan.windows.forEach { FogViewportTileGrid.queryBounds(it, 0.0) }
    }

    @Test
    fun `antimeridian requests choose the two-column cyclic window`() {
        val plan = planner.plan(setOf(key(3, 7, 4), key(3, 0, 4)))

        assertEquals(listOf(7, 0), plan.windows.single().map(FogTileKey::x))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rectangle expansion remains bounded`() {
        FogRequestedTileWindowPlanner(maxTiles = 8).plan(
            setOf(key(4, 0, 0), key(4, 4, 4)),
        )
    }

    @Test
    fun `renderer validates every exact window response`() = runTest {
        val seen = mutableListOf<List<FogTileKey>>()
        val renderer = FogRequestedTileWindowRenderer(
            subrenderer = FogViewportBatchSubrenderer { request, keys ->
                seen += keys
                FogViewportTileRender(
                    request = request,
                    keys = keys,
                    queryBounds = null,
                    tiles = keys.map { candidate ->
                        FogMosaicTile(candidate, opaqueMask())
                    },
                )
            },
            maxTiles = 32,
        )
        val requested = setOf(key(3, 7, 4), key(3, 0, 4), key(2, 1, 2))

        val rendered = renderer.render(GeoPoint(0.0, 179.0), requested)

        assertEquals(2, seen.size)
        assertTrue(rendered.keys.containsAll(requested))
        assertEquals(seen.flatten().toSet(), rendered.keys)
    }

    private fun key(zoom: Int, x: Int, y: Int) = FogTileKey(
        zoom = zoom,
        x = x,
        y = y,
        renderVersion = FogRenderVersions.CURRENT,
    )

    private fun opaqueMask() = FogPixelMask(
        width = 256,
        height = 256,
        alpha = ByteArray(256 * 256) { 0xff.toByte() },
    )
}
