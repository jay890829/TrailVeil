package app.trailveil.map

import app.trailveil.map.fog.FogBackdropGeometry
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileMosaic
import app.trailveil.map.fog.FogViewportPresentation
import app.trailveil.map.fog.WebMercator
import com.google.android.gms.maps.model.LatLng
import app.trailveil.harness.NativeFogBackdropGeometry

/**
 * The `Polygon` geometry both non-tile arms need, in one place because getting it wrong reveals
 * ground.
 *
 * `V03-011` section 15n paid for [splitForGooglePolygon] the hard way: generations 2, 3 and 4 died
 * of it. `V03-013`'s vector arm needs exactly the same rules as arm 2's backdrop - the same
 * antimeridian hazard, the same surround, the same fill - so they are shared rather than copied.
 * A second private copy of a fix like that is how one of them silently stops matching.
 */
internal object GoogleFogPolygonGeometry {

    /**
     * Halves a guard rectangle until no ring spans enough longitude to be read the long way round.
     *
     * The ceiling is well under 180 rather than just under it. What a `Polygon` does at exactly 180
     * is undefined rather than merely tight, and a rectangle whose width sits a rounding error
     * below the boundary would put the whole surface on the far side of an SDK's tie-break. Halving
     * costs one more polygon per split and nothing per frame.
     */
    fun splitForGooglePolygon(bounds: FogTileBounds): List<FogTileBounds> {
        val width = bounds.eastLongitude - bounds.westLongitude
        if (!width.isFinite() || width <= MAX_POLYGON_RING_DEGREES) return listOf(bounds)
        val middle = (bounds.westLongitude + bounds.eastLongitude) / 2.0
        if (middle <= bounds.westLongitude || middle >= bounds.eastLongitude) return listOf(bounds)
        return listOf(
            bounds.copy(eastLongitude = middle),
            bounds.copy(westLongitude = middle),
        ).flatMap { half -> splitForGooglePolygon(half) }
    }

    /**
     * The surround minus the image: the ground a non-tile surface must draw for itself.
     *
     * A `TileOverlay` is its own backdrop, because the provider answers every key outside the plan
     * with opaque fog. An anchored image and a holed polygon are not, so both have to draw the rest
     * of the surround or they report a reach they do not cover - which is the defect section 15k
     * found, at 89.344% of bare basemap.
     *
     * Four strips, each clipped to the surround; north and south own the full width so the corners
     * belong to them, the same division of labour as `extentGuard`. Any strip that comes out
     * degenerate is dropped rather than emitted as a zero-area ring.
     */
    fun surroundComplementOf(mosaic: FogTileMosaic): List<FogTileBounds> =
        NativeFogBackdropGeometry.complement(FogBackdropGeometry.extent(mosaic), mosaic.bounds)

    fun backdropRectangles(mosaic: FogViewportPresentation): List<FogTileBounds> =
        NativeFogBackdropGeometry.googleRectangles(FogBackdropGeometry.extent(mosaic), mosaic.bounds)

    /** A rectangle as a Google ring. Counter-clockwise is not required; closure is implicit. */
    fun ring(bounds: FogTileBounds): List<LatLng> = listOf(
        LatLng(bounds.southLatitude, bounds.westLongitude),
        LatLng(bounds.southLatitude, bounds.eastLongitude),
        LatLng(bounds.northLatitude, bounds.eastLongitude),
        LatLng(bounds.northLatitude, bounds.westLongitude),
    )

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    /** The fog's own alpha, so a non-tile surface is the same fog rather than a similar one. */
    const val VISIBLE_FOG_ALPHA = 184

    const val TRANSPARENT = 0

    /** Well under 180; see [splitForGooglePolygon]. */
    const val MAX_POLYGON_RING_DEGREES = 90.0
}
