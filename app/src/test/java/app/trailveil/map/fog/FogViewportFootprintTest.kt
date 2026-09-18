package app.trailveil.map.fog

import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPointReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogViewportFootprintTest {
    @Test
    fun fractionalZoomWithinTheSameCenterTileKeepsTheSameFootprint() {
        val coordinator = coordinator()
        val firstRequest = request(
            latitude = 25.0330,
            longitude = 121.5654,
            mapZoom = 14.1,
        )
        val secondRequest = request(
            latitude = 25.0331,
            longitude = 121.5655,
            mapZoom = 14.9,
        )

        assertEquals(
            WebMercator.tile(firstRequest.center, zoom = 14),
            WebMercator.tile(secondRequest.center, zoom = 14),
        )
        val first = coordinator.footprint(firstRequest)
        val second = coordinator.footprint(secondRequest)

        assertEquals(first.keys, second.keys)
        assertEquals(first.layout, second.layout)
    }

    @Test
    fun changingATileColumnRowOrIntegerZoomChangesTheFootprint() {
        val coordinator = coordinator()
        val baseRequest = request(latitude = 0.0, longitude = 0.0, mapZoom = 4.0)
        val base = coordinator.footprint(baseRequest)
        val changedRequests = listOf(
            "tile column" to request(latitude = 0.0, longitude = 30.0, mapZoom = 4.0),
            "tile row" to request(latitude = 30.0, longitude = 0.0, mapZoom = 4.0),
            "integer render zoom" to request(latitude = 0.0, longitude = 0.0, mapZoom = 5.0),
        )

        changedRequests.forEach { (change, changedRequest) ->
            val changed = coordinator.footprint(changedRequest)
            assertFalse(
                "$change must change the requested tile keys",
                base.keys == changed.keys,
            )
            assertFalse(
                "$change must change the complete footprint",
                base == changed,
            )
        }
    }

    @Test
    fun wrappedKeysCanHaveDifferentWorldAnchoredBounds() {
        val coordinator = coordinator()
        val westWorld = coordinator.footprint(
            request(latitude = 0.0, longitude = -179.5, mapZoom = 4.0),
        )
        val eastWorld = coordinator.footprint(
            request(latitude = 0.0, longitude = 180.5, mapZoom = 4.0),
        )

        assertEquals("wrapped longitudes must address the same keys", westWorld.keys, eastWorld.keys)
        assertFalse(
            "the world copy is part of the footprint identity",
            westWorld.layout.bounds == eastWorld.layout.bounds,
        )
        assertEquals(
            westWorld.layout.bounds.westLongitude + 360.0,
            eastWorld.layout.bounds.westLongitude,
            1e-9,
        )
        assertEquals(
            westWorld.layout.bounds.eastLongitude + 360.0,
            eastWorld.layout.bounds.eastLongitude,
            1e-9,
        )
    }

    @Test
    fun polarCentersKeepTheMosaicLayoutInsideTheMercatorClamp() {
        val coordinator = coordinator()

        listOf(90.0, -90.0).forEach { latitude ->
            val footprint = coordinator.footprint(
                request(latitude = latitude, longitude = 0.0, mapZoom = 4.0),
            )
            val bounds = footprint.layout.bounds

            assertEquals(6, footprint.keys.size)
            assertEquals(6, footprint.layout.tileCount)
            assertEquals(3, footprint.layout.columns)
            assertEquals(2, footprint.layout.rows)
            assertTrue(bounds.southLatitude <= bounds.northLatitude)
            assertTrue(bounds.southLatitude >= -WebMercator.MAX_LATITUDE)
            assertTrue(bounds.northLatitude <= WebMercator.MAX_LATITUDE)
            assertEquals(
                if (latitude > 0.0) WebMercator.MAX_LATITUDE else -WebMercator.MAX_LATITUDE,
                if (latitude > 0.0) bounds.northLatitude else bounds.southLatitude,
                1e-9,
            )
            assertEquals(
                if (latitude > 0.0) listOf(0, 1) else listOf(14, 15),
                footprint.keys.map(FogTileKey::y).distinct(),
            )
        }
    }

    @Test
    fun footprintMatchesTheActualRenderAndRejectsItsKeysBoundsCountAndDimensions() = runTest {
        val style = FogRenderStyle(tileSize = 8)
        val coordinator = coordinator(style)
        val request = request(latitude = 25.0330, longitude = 121.5654, mapZoom = 6.25)
        val render = coordinator.render(request)
        val footprint = coordinator.footprint(request)
        val mosaic = render.presentation as FogTileMosaic

        assertTrue("the coordinator render must use its own footprint", footprint.matches(render))
        assertFalse(
            "a different key list must not match",
            footprint.matches(render.copy(keys = render.keys.drop(1))),
        )
        assertFalse(
            "different world bounds must not match",
            footprint.matches(
                render.copy(
                    presentation = mosaic.copy(
                        bounds = mosaic.bounds.copy(
                            eastLongitude = mosaic.bounds.eastLongitude + 1.0,
                        ),
                    ),
                ),
            ),
        )
        assertFalse(
            "a different tile count must not match",
            footprint.matches(
                render.copy(presentation = mosaic.copy(tileCount = mosaic.tileCount + 1)),
            ),
        )
        assertFalse(
            "a different sampling width must not match",
            footprint.matches(
                render.copy(
                    presentation = mosaic.copy(
                        mask = FogPixelMask(
                            width = mosaic.mask.width + 1,
                            height = mosaic.mask.height,
                            alpha = ByteArray((mosaic.mask.width + 1) * mosaic.mask.height),
                        ),
                    ),
                ),
            ),
        )
        assertFalse(
            "a different sampling height must not match",
            footprint.matches(
                render.copy(
                    presentation = mosaic.copy(
                        mask = FogPixelMask(
                            width = mosaic.mask.width,
                            height = mosaic.mask.height + 1,
                            alpha = ByteArray(mosaic.mask.width * (mosaic.mask.height + 1)),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun request(latitude: Double, longitude: Double, mapZoom: Double) =
        FogViewportRequest(
            center = GeoPoint(latitude = latitude, longitude = longitude),
            mapZoom = mapZoom,
        )

    private fun coordinator(style: FogRenderStyle = FogRenderStyle(tileSize = 8)) =
        FogViewportCoordinator(
            trackDataSource = ViewportTrackDataSource(
                ViewportTrackPointReader { _, _, _ -> emptyList() },
            ),
            pipeline = FogTilePipeline(
                memoryCache = FogMemoryTileCache(maxBytes = 1024L * 1024L),
                diskCache = null,
                renderMask = FogTileRenderer(style)::render,
            ),
            style = style,
        )
}
