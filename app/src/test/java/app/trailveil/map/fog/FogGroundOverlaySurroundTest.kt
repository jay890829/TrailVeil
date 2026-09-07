package app.trailveil.map.fog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V03-011` arm 2's replacement surround gate, settled on the host.
 *
 * The survey's section 5 recorded that replacing the `TileOverlay` removes two things the reveal
 * depends on, both fed only by `TileProvider` requests: the delivery barrier, and one of the two
 * terms of the surround test (`publishedKeys.containsAll(recentActualRequests)`). A `GroundOverlay`
 * issues no requests, so both lose their only input.
 *
 * The second one has a replacement already in the tree, and it is not a new idea: it is the guard
 * the MapLibre backdrop already uses. `FogBackdropGeometry.extent(mosaic)` turns the composed
 * mosaic into a `FogSurroundExtent`, and `FogSurroundExtent.covers(visibleCorners)` asks whether
 * every corner of the ground the camera can see is inside it. Geometric, request-free, and carrying
 * a documented history of the exact mistake to avoid - it takes the map's own visible corners
 * rather than a box built from the camera position and viewport size, because a tilted or rotated
 * camera sees a larger, rotated rectangle than that box.
 *
 * These tests establish two things about that substitution. It behaves as a surround: covered at
 * the anchor camera, not covered once the camera walks off it. And - the result that matters for
 * choosing between the arms - **it does not die at an integer zoom boundary the way arm 1's ring
 * does**, because an extent is geographic while a coverage key carries the zoom it was planned at.
 * See `FogPaddingRingSurroundTest` for the ring's behaviour at the same boundary.
 */
class FogGroundOverlaySurroundTest {

    @Test
    fun `a geometric surround covers its anchor camera and not a camera walked off it`() {
        val extent = FogBackdropGeometry.extent(mosaicAround(ANCHOR, ZOOM))

        assertTrue(
            "the surround must cover the camera it was composed for",
            extent.covers(cornersAround(ANCHOR, halfSpanDegrees = 0.02)),
        )
        assertFalse(
            "and must stop covering a camera that has walked well off it",
            extent.covers(
                cornersAround(
                    GeoPoint(ANCHOR.latitude, ANCHOR.longitude + 40.0),
                    halfSpanDegrees = 0.02,
                ),
            ),
        )
    }

    /**
     * The decisive difference from arm 1. A ring of any size fails the moment `floorZoom` changes,
     * because every published key carries the zoom it was planned at. An extent is a geographic
     * region, so zooming IN - which shrinks the ground on screen - stays inside it.
     */
    @Test
    fun `zooming in does not defeat a geometric surround, unlike a tile ring`() {
        val extent = FogBackdropGeometry.extent(mosaicAround(ANCHOR, ZOOM))

        // Each step halves the ground the camera sees, the same shape a pinch produces.
        var halfSpan = 0.02
        repeat(4) {
            halfSpan /= 2.0
            assertTrue(
                "zooming in must stay covered; halfSpan=$halfSpan",
                extent.covers(cornersAround(ANCHOR, halfSpan)),
            )
        }
    }

    /**
     * Stated so the advantage above is not read as unlimited: a geometric surround is finite in the
     * other direction too, and zooming far enough OUT leaves it. That is the honest boundary of
     * arm 2's gate, and the case a prototype still has to raise something for.
     */
    @Test
    fun `zooming far out still leaves a geometric surround`() {
        val extent = FogBackdropGeometry.extent(mosaicAround(ANCHOR, ZOOM))

        assertFalse(
            "a whole-continent viewport is outside a surround composed for a walking user",
            extent.covers(cornersAround(ANCHOR, halfSpanDegrees = 30.0)),
        )
    }

    private fun mosaicAround(center: GeoPoint, zoom: Int): FogTileMosaic {
        val renderer = FogTileRenderer()
        val keys = FogViewportTileGrid.around(center = center, zoom = zoom, renderVersion = 0)
        return FogPocMosaic.compose(
            keys.map { key -> FogMosaicTile(key, renderer.render(key, emptyList())) },
        )
    }

    /** The four visible corners of a level, north-up camera, in the order the projection gives. */
    private fun cornersAround(center: GeoPoint, halfSpanDegrees: Double): List<GeoPoint> = listOf(
        GeoPoint(center.latitude - halfSpanDegrees, center.longitude - halfSpanDegrees),
        GeoPoint(center.latitude + halfSpanDegrees, center.longitude - halfSpanDegrees),
        GeoPoint(center.latitude + halfSpanDegrees, center.longitude + halfSpanDegrees),
        GeoPoint(center.latitude - halfSpanDegrees, center.longitude + halfSpanDegrees),
    )

    private companion object {
        val ANCHOR = GeoPoint(25.0, 121.5)
        const val ZOOM = 16
    }
}
