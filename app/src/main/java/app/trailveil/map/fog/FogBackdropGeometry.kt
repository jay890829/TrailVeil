package app.trailveil.map.fog

import kotlin.math.max
import kotlin.math.min

/**
 * The four bands that surround a rendered fog mosaic.
 *
 * North and south run the full width of the surround, so the corners belong to them and the side
 * bands only have to close the mosaic's own latitudes.
 */
data class FogBackdropBands(
    val north: FogTileBounds,
    val south: FogTileBounds,
    val west: FogTileBounds,
    val east: FogTileBounds,
) {
    fun asList(): List<FogTileBounds> = listOf(north, south, west, east)
}

/**
 * Builds the map-space surround of a rendered fog mosaic.
 *
 * The bands are geographic, so the renderer transforms them with the camera in the same frame
 * that draws the mosaic. Coverage therefore cannot lag a gesture the way any decision taken from
 * an already-dispatched camera callback does.
 */
object FogBackdropGeometry {
    /**
     * Bands overlap the mosaic by half of one mosaic pixel. Both shapes are projected
     * independently, so an exact shared edge could still round apart into a one-pixel seam of
     * unfogged map; overlapping instead can only ever cost half a pixel of revealed area.
     */
    const val MOSAIC_OVERLAP_PIXELS: Double = 0.5

    /** How far past the mosaic the bands reach, in multiples of the mosaic's own size. */
    const val BAND_REACH_MOSAICS: Double = 512.0

    /**
     * Absolute limits on how far a band reaches. Image quads that approach the size of the world
     * stop being drawn at all on this renderer, taking the mosaic down with them, so the surround
     * is deliberately finite. What it buys is bounded: at these limits a single uninterrupted
     * gesture would have to zoom out past level three before the camera could see past it, and
     * every gesture ends in a rebuild centred on wherever it stopped.
     */
    const val MAX_LONGITUDE_REACH_DEGREES: Double = 60.0
    const val MAX_BAND_LONGITUDE_SPAN_DEGREES: Double = 180.0
    const val MAX_NORMALIZED_Y_REACH: Double = 0.125

    fun bands(mosaic: FogTileMosaic): FogBackdropBands {
        val bounds = mosaic.bounds
        val longitudeSpan = bounds.eastLongitude - bounds.westLongitude
        val longitudeOverlap = longitudeSpan / mosaic.mask.width * MOSAIC_OVERLAP_PIXELS
        val longitudeReach = max(
            0.0,
            minOf(
                longitudeSpan * BAND_REACH_MOSAICS,
                MAX_LONGITUDE_REACH_DEGREES,
                (MAX_BAND_LONGITUDE_SPAN_DEGREES - longitudeSpan) / 2.0,
            ),
        )
        val northY = WebMercator.normalizedY(bounds.northLatitude)
        val southY = WebMercator.normalizedY(bounds.southLatitude)
        val normalizedYSpan = southY - northY
        val overlapY = normalizedYSpan / mosaic.mask.height * MOSAIC_OVERLAP_PIXELS
        val reachY = min(normalizedYSpan * BAND_REACH_MOSAICS, MAX_NORMALIZED_Y_REACH)
        val innerNorth = WebMercator.latitudeAtNormalizedY(northY + overlapY)
        val innerSouth = WebMercator.latitudeAtNormalizedY(southY - overlapY)
        // The projection has no finite value at the poles, so bands stop at the world edge.
        val outerNorth = WebMercator.latitudeAtNormalizedY(max(0.0, northY - reachY))
        val outerSouth = WebMercator.latitudeAtNormalizedY(min(1.0, southY + reachY))
        val outerWest = bounds.westLongitude - longitudeReach
        val outerEast = bounds.eastLongitude + longitudeReach
        // A mosaic wide enough to wrap the world would otherwise hand the full-width bands the
        // very span the renderer refuses to draw. Keeping them centred on the mosaic costs only
        // coverage that is half a world away from the camera the mosaic was built for.
        val center = (bounds.westLongitude + bounds.eastLongitude) / 2.0
        val widestWest = max(outerWest, center - MAX_BAND_LONGITUDE_SPAN_DEGREES / 2.0)
        val widestEast = min(outerEast, center + MAX_BAND_LONGITUDE_SPAN_DEGREES / 2.0)
        // The side bands still have to reach the mosaic itself, or that clamp would open a gap
        // between them and it rather than only trimming the far end of the surround.
        val sideWest = min(widestWest, bounds.westLongitude)
        val sideEast = max(widestEast, bounds.eastLongitude)
        return FogBackdropBands(
            north = FogTileBounds(
                westLongitude = widestWest,
                southLatitude = innerNorth,
                eastLongitude = widestEast,
                northLatitude = outerNorth,
            ),
            south = FogTileBounds(
                westLongitude = widestWest,
                southLatitude = outerSouth,
                eastLongitude = widestEast,
                northLatitude = innerSouth,
            ),
            west = FogTileBounds(
                westLongitude = sideWest,
                southLatitude = innerSouth,
                eastLongitude = bounds.westLongitude + longitudeOverlap,
                northLatitude = innerNorth,
            ),
            east = FogTileBounds(
                westLongitude = bounds.eastLongitude - longitudeOverlap,
                southLatitude = innerSouth,
                eastLongitude = sideEast,
                northLatitude = innerNorth,
            ),
        )
    }
}
