package app.trailveil.harness

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon

/** Avoids a no-op overlay operation; the clip must be the engine's axis-aligned partition rectangle. */
internal fun clipTrackAreaToRectangle(area: Geometry, clip: Geometry): Geometry {
    require(clip is Polygon && clip.isRectangle) { "native partition clip must be a rectangle" }
    return if (area.isEmpty || clip.envelopeInternal.contains(area.envelopeInternal)) area
    else area.intersection(clip)
}
