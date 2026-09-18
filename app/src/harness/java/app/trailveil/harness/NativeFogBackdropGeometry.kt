package app.trailveil.harness

import app.trailveil.map.fog.FogSurroundExtent
import app.trailveil.map.fog.FogBackdropGeometry
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.WebMercator

/** The surround minus an image, in one continuous longitude interval before SDK normalization. */
internal object NativeFogBackdropGeometry {
    /** Normalize each FINAL fragment; keep this ordering shared by both Google installers. */
    fun googleRectangles(surround: FogSurroundExtent, image: FogTileBounds): List<FogTileBounds> =
        complement(surround, image).flatMap(::split).map(FogBackdropGeometry::anchoredInsideWorld)

    private fun split(bounds: FogTileBounds): List<FogTileBounds> {
        if (bounds.eastLongitude - bounds.westLongitude <= 90.0) return listOf(bounds)
        val middle = (bounds.westLongitude + bounds.eastLongitude) / 2.0
        return split(bounds.copy(eastLongitude = middle)) + split(bounds.copy(westLongitude = middle))
    }

    fun complement(surround: FogSurroundExtent, image: FogTileBounds): List<FogTileBounds> {
        val north = WebMercator.latitudeAtNormalizedY(surround.northNormalizedY)
        val south = WebMercator.latitudeAtNormalizedY(surround.southNormalizedY)
        val halfDegrees = surround.halfWorlds * 360.0
        // For a wrapped image such as [170,190], using a fixed [-180,180] world would paint
        // [-180,170] over its normalized [180,190] half. Start the world's interval at the
        // IMAGE's west edge, so the one side band is exactly [image.east, image.west+360].
        val west = if (surround.wrapsWorld) image.westLongitude else surround.centerLongitude - halfDegrees
        val east = if (surround.wrapsWorld) image.westLongitude + 360.0 else surround.centerLongitude + halfDegrees
        return listOf(
            FogTileBounds(west, image.northLatitude, east, north),
            FogTileBounds(west, south, east, image.southLatitude),
            FogTileBounds(west, image.southLatitude, image.westLongitude, image.northLatitude),
            FogTileBounds(image.eastLongitude, image.southLatitude, east, image.northLatitude),
        ).filter { bounds ->
            bounds.westLongitude.isFinite() && bounds.eastLongitude.isFinite() &&
                bounds.northLatitude.isFinite() && bounds.southLatitude.isFinite() &&
                bounds.eastLongitude > bounds.westLongitude && bounds.northLatitude > bounds.southLatitude
        }
    }
}
