package app.trailveil.map.fog

import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FogBackdropGeometryTest {
    @Test
    fun bandsAndMosaicLeaveNoGapAnywhereInTheSurround() {
        assertSurroundIsSolid(mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16))
    }

    /**
     * The mosaic quad deliberately keeps unwrapped longitudes so a dateline mosaic stays
     * continuous instead of spanning the long way around the world. The bands extend that same
     * unwrapped frame, so they must not wrap either or they would close around the wrong side.
     */
    @Test
    fun bandsStayContinuousWithAMosaicThatStraddlesTheAntimeridian() {
        val mosaic = mosaicAround(GeoPoint(0.0, 179.9999), zoom = 16)
        val bounds = mosaic.bounds
        assertTrue(
            "expected an unwrapped dateline mosaic, was $bounds",
            bounds.eastLongitude > 180.0,
        )
        val bands = FogBackdropGeometry.bands(mosaic)

        assertTrue(
            "the west band wrapped away from the mosaic: ${bands.west}",
            bands.west.eastLongitude > bounds.westLongitude &&
                bands.west.westLongitude < bounds.westLongitude,
        )
        assertTrue(
            "the east band wrapped away from the mosaic: ${bands.east}",
            bands.east.westLongitude < bounds.eastLongitude &&
                bands.east.eastLongitude > bounds.eastLongitude,
        )
        assertSurroundIsSolid(mosaic)
    }

    @Test
    fun bandsLeaveNoGapWhenTheMosaicSpansTheWholeWorldWidth() {
        val mosaic = mosaicAround(GeoPoint(0.0, 0.0), zoom = 1)
        assertEquals(360.0, mosaic.bounds.eastLongitude - mosaic.bounds.westLongitude, 1e-9)

        assertSurroundIsSolid(mosaic)
    }

    @Test
    fun bandsLeaveNoGapWhenTheMosaicIsClippedAtThePole() {
        val mosaic = mosaicAround(GeoPoint(85.0, 10.0), zoom = 4)
        assertEquals(WebMercator.MAX_LATITUDE, mosaic.bounds.northLatitude, 1e-6)

        assertSurroundIsSolid(mosaic)
    }

    @Test
    fun bandsOverlapTheMosaicByHalfOfOneOfItsPixels() {
        val mosaic = mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16)
        val bounds = mosaic.bounds
        val bands = FogBackdropGeometry.bands(mosaic)

        val longitudePixel = (bounds.eastLongitude - bounds.westLongitude) / mosaic.mask.width
        assertEquals(
            longitudePixel * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS,
            bands.west.eastLongitude - bounds.westLongitude,
            longitudePixel * 1e-6,
        )
        assertEquals(
            longitudePixel * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS,
            bounds.eastLongitude - bands.east.westLongitude,
            longitudePixel * 1e-6,
        )
        val northY = WebMercator.normalizedY(bounds.northLatitude)
        val southY = WebMercator.normalizedY(bounds.southLatitude)
        val pixelY = (southY - northY) / mosaic.mask.height
        assertEquals(
            pixelY * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS,
            WebMercator.normalizedY(bands.north.southLatitude) - northY,
            pixelY * 1e-6,
        )
        assertEquals(
            pixelY * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS,
            southY - WebMercator.normalizedY(bands.south.northLatitude),
            pixelY * 1e-6,
        )
    }

    /** Fog is translucent, so two bands over the same map would render as a darker seam. */
    @Test
    fun bandsNeverOverlapEachOther() {
        everyMosaic().forEach { mosaic ->
            val bands = FogBackdropGeometry.bands(mosaic).asList()
            bands.indices.forEach { first ->
                (first + 1 until bands.size).forEach { second ->
                    assertTrue(
                        "bands ${bands[first]} and ${bands[second]} overlap",
                        !overlaps(bands[first], bands[second]),
                    )
                }
            }
        }
    }

    @Test
    fun bandsAreNeverInvertedOrProjectedPastTheWorldEdge() {
        everyMosaic().forEach { mosaic ->
            FogBackdropGeometry.bands(mosaic).asList().forEach { band ->
                assertTrue(
                    "band $band is inverted",
                    band.eastLongitude >= band.westLongitude &&
                        band.northLatitude >= band.southLatitude,
                )
                assertTrue(
                    "band $band reaches past the projected world edge",
                    band.northLatitude <= WebMercator.MAX_LATITUDE + 1e-9 &&
                        band.southLatitude >= -WebMercator.MAX_LATITUDE - 1e-9,
                )
            }
        }
    }

    /**
     * A quad approaching the size of the world is not drawn by this renderer at all, and it takes
     * the rest of the fog stack down with it, so the surround has to stay well inside that.
     */
    @Test
    fun bandsNeverApproachTheSizeOfTheWorld() {
        everyMosaic().forEach { mosaic ->
            FogBackdropGeometry.bands(mosaic).asList().forEach { band ->
                assertTrue(
                    "band spans too much of the world: $band",
                    band.eastLongitude - band.westLongitude <=
                        FogBackdropGeometry.MAX_BAND_LONGITUDE_SPAN_DEGREES + 1e-9,
                )
            }
        }
    }

    /**
     * The surround is what absorbs a gesture that leaves the mosaic, so how far it reaches is
     * the property worth pinning at the zooms exploration actually happens at.
     */
    @Test
    fun theSurroundReachesManyMosaicsPastTheMosaicAtExplorationZooms() {
        (12..20).forEach { zoom ->
            val mosaic = mosaicAround(GeoPoint(25.0330, 121.5654), zoom = zoom)
            val bounds = mosaic.bounds
            val bands = FogBackdropGeometry.bands(mosaic)
            val longitudeSpan = bounds.eastLongitude - bounds.westLongitude
            val northY = WebMercator.normalizedY(bounds.northLatitude)
            val southY = WebMercator.normalizedY(bounds.southLatitude)
            val westReach = (bounds.westLongitude - bands.west.westLongitude) / longitudeSpan
            val northReach =
                (northY - WebMercator.normalizedY(bands.north.northLatitude)) / (southY - northY)

            assertTrue(
                "zoom $zoom reaches only $westReach mosaics west",
                westReach >= MINIMUM_REACH_MOSAICS,
            )
            assertTrue(
                "zoom $zoom reaches only $northReach mosaics north",
                northReach >= MINIMUM_REACH_MOSAICS,
            )
        }
    }

    /**
     * Every point of the mosaic's own surround has to be painted by something. Sampling is
     * densest immediately around the mosaic, which is the only place a rounding gap could hide.
     */
    private fun assertSurroundIsSolid(mosaic: FogTileMosaic) {
        val bands = FogBackdropGeometry.bands(mosaic)
        val shapes = bands.asList() + mosaic.bounds
        // The promised region is what the full-width bands span horizontally by the whole
        // vertical reach; the side bands always run at least that wide.
        val surround = FogTileBounds(
            westLongitude = bands.north.westLongitude,
            southLatitude = bands.south.southLatitude,
            eastLongitude = bands.north.eastLongitude,
            northLatitude = bands.north.northLatitude,
        )
        samplePoints(surround, mosaic.bounds)
            .filter { point -> contains(surround, point) }
            .forEach { point ->
                assertTrue(
                    "$point lies in the surround but in neither the mosaic nor any band",
                    shapes.any { contains(it, point) },
                )
            }
    }

    private fun samplePoints(
        surround: FogTileBounds,
        mosaic: FogTileBounds,
    ): List<GeoPoint> = buildList {
        val latitudeSpan = surround.northLatitude - surround.southLatitude
        val longitudeSpan = surround.eastLongitude - surround.westLongitude
        (0..SURROUND_SAMPLES).forEach { row ->
            (0..SURROUND_SAMPLES).forEach { column ->
                add(
                    GeoPoint(
                        latitude = surround.southLatitude +
                            latitudeSpan * row / SURROUND_SAMPLES,
                        longitude = surround.westLongitude +
                            longitudeSpan * column / SURROUND_SAMPLES,
                    ),
                )
            }
        }
        val mosaicLatitudeSpan = mosaic.northLatitude - mosaic.southLatitude
        val mosaicLongitudeSpan = mosaic.eastLongitude - mosaic.westLongitude
        (-6..6).forEach { row ->
            (-6..6).forEach { column ->
                add(
                    GeoPoint(
                        latitude = (
                            mosaic.southLatitude + mosaicLatitudeSpan * (0.5 + row * 0.0834)
                            ).coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE),
                        longitude = mosaic.westLongitude +
                            mosaicLongitudeSpan * (0.5 + column * 0.0834),
                    ),
                )
            }
        }
    }

    private fun contains(bounds: FogTileBounds, point: GeoPoint): Boolean =
        point.latitude >= bounds.southLatitude - EDGE_TOLERANCE &&
            point.latitude <= bounds.northLatitude + EDGE_TOLERANCE &&
            point.longitude >= bounds.westLongitude - EDGE_TOLERANCE &&
            point.longitude <= bounds.eastLongitude + EDGE_TOLERANCE

    private fun overlaps(first: FogTileBounds, second: FogTileBounds): Boolean {
        val latitudeOverlap = min(first.northLatitude, second.northLatitude) -
            max(first.southLatitude, second.southLatitude)
        val longitudeOverlap = min(first.eastLongitude, second.eastLongitude) -
            max(first.westLongitude, second.westLongitude)
        return latitudeOverlap > EDGE_TOLERANCE && longitudeOverlap > EDGE_TOLERANCE
    }

    private fun everyMosaic(): List<FogTileMosaic> = listOf(
        mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16),
        mosaicAround(GeoPoint(0.0, 179.9999), zoom = 16),
        mosaicAround(GeoPoint(-85.0, -170.0), zoom = 4),
        mosaicAround(GeoPoint(85.0, 10.0), zoom = 4),
        mosaicAround(GeoPoint(0.0, 0.0), zoom = 2),
        mosaicAround(GeoPoint(0.0, 0.0), zoom = 1),
        mosaicAround(GeoPoint(0.0, 0.0), zoom = 0),
        mosaicAround(GeoPoint(48.8566, 2.3522), zoom = 22),
    )

    private fun mosaicAround(center: GeoPoint, zoom: Int): FogTileMosaic {
        val renderer = FogTileRenderer()
        val keys = FogViewportTileGrid.around(center = center, zoom = zoom, renderVersion = 0)
        return FogPocMosaic.compose(
            keys.map { key -> FogMosaicTile(key, renderer.render(key, emptyList())) },
        )
    }

    private companion object {
        const val EDGE_TOLERANCE = 1e-9
        const val SURROUND_SAMPLES = 60
        const val MINIMUM_REACH_MOSAICS = 8.0
    }
}
