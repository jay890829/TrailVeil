package app.trailveil.map

import android.graphics.Bitmap
import app.trailveil.map.fog.FogMaskContours
import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogTileBounds
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * `V03-013` arm `vector` on this provider: the fog as geometry the renderer tessellates itself.
 *
 * The whole reason vector is more interesting here than on the other provider is that this one
 * draws it in its OWN render pass. Tilt, rotation and camera synchronisation come free and exactly
 * right, where a screen-anchored layer has to chase them and an anchored raster has to stretch. The
 * boundary is resolution-independent too, so it does not soften as the camera zooms into it.
 *
 * **This uses a `GeoJsonSource`, not `CustomGeometrySource`, and the choice is deliberate.** The
 * ledger named `CustomGeometrySource` because a whole-world resident layer must be fetched per tile
 * or its cost grows with everything ever explored. That argument does not apply to what is
 * installed here: this is one generation's already-composed mosaic, whose geometry is known in full
 * at install time and bounded by the viewport. Fetching it back per tile would add machinery
 * without changing a pixel. `CustomGeometrySource` is the right answer for the whole-world layer
 * prototype B describes, and this arm is not that.
 *
 * Holes come from the mask for the same reason as the other provider's vector arm: overlapping
 * reveal circles were unioned when they were rasterised, so a decomposition of the raster is
 * non-overlapping without a polygon library. Section 15p's prediction applies unchanged - the
 * boundary keeps the mask's staircase and hardens it.
 *
 * `debug` only. `src/mapLibre` is compiled by `release`, so none of this can live there.
 */
internal fun installVectorFogIfArmed(
    style: org.maplibre.android.maps.Style,
    sourceId: String,
    layerId: String,
    bounds: FogTileBounds,
    bitmap: Bitmap,
    belowLayerId: String,
): Boolean {
    if (!MapLibreVectorFogState.enabled) return false
    val mask = maskOf(bitmap) ?: return false
    val decomposed = FogMaskContours.decompose(mask, step = VECTOR_STEP)

    val outer = ring(
        bounds.westLongitude,
        bounds.southLatitude,
        bounds.eastLongitude,
        bounds.northLatitude,
    )
    val holes = decomposed.rects.mapNotNull { rect ->
        val west = bounds.westLongitude +
            (bounds.eastLongitude - bounds.westLongitude) * (rect.left.toDouble() / mask.width)
        val east = bounds.westLongitude +
            (bounds.eastLongitude - bounds.westLongitude) * (rect.right.toDouble() / mask.width)
        // Latitude is interpolated linearly across the quad because that is exactly how the raster
        // this replaces is stretched onto the same quad: `ImageSource` maps the image linearly
        // between the corners. Matching it keeps the two arms comparable; a Mercator-correct
        // interpolation here would put the vector arm's holes somewhere the baseline's pixels are
        // not, and the difference would be read as a property of vector fog rather than of the
        // change in interpolation.
        val north = bounds.northLatitude +
            (bounds.southLatitude - bounds.northLatitude) * (rect.top.toDouble() / mask.height)
        val south = bounds.northLatitude +
            (bounds.southLatitude - bounds.northLatitude) * (rect.bottom.toDouble() / mask.height)
        if (east <= west || north <= south) null else ring(west, south, east, north)
    }

    val polygon = Polygon.fromOuterInner(outer, holes)
    style.addSource(GeoJsonSource(sourceId, Feature.fromGeometry(polygon)))
    val layer = FillLayer(layerId, sourceId).withProperties(
        PropertyFactory.fillColor(VECTOR_FOG_COLOR),
        PropertyFactory.fillOpacity(VECTOR_FOG_OPACITY),
        PropertyFactory.fillAntialias(true),
    )
    style.addLayerBelow(layer, belowLayerId)
    return true
}

private fun ring(west: Double, south: Double, east: Double, north: Double): LineString =
    LineString.fromLngLats(
        listOf(
            Point.fromLngLat(west, south),
            Point.fromLngLat(east, south),
            Point.fromLngLat(east, north),
            Point.fromLngLat(west, north),
            Point.fromLngLat(west, south),
        ),
    )

/**
 * The bitmap's alpha channel as a mask.
 *
 * The raster fog is drawn with alpha, so "revealed" is "transparent" and reading the alpha channel
 * is reading exactly what the baseline arm displays - no second opinion about what is revealed.
 */
private fun maskOf(bitmap: Bitmap): FogPixelMask? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    return try {
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val alpha = ByteArray(width * height)
        for (index in pixels.indices) {
            alpha[index] = ((pixels[index] ushr 24) and 0xff).toByte()
        }
        FogPixelMask(width, height, alpha)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

/** Matches the raster fog's own colour and alpha, so the arms differ in geometry and nothing else. */
private const val VECTOR_FOG_COLOR = 0xFF1F262B.toInt()
private const val VECTOR_FOG_OPACITY = 184f / 255f

/**
 * Sample the mask every this many pixels.
 *
 * This renderer tessellates on the GPU and takes far more geometry than a single `Polygon` object
 * on the other provider, so this can afford to be finer than that arm's step. It is still not 1:
 * the ring count is the cost that decides whether vector fog is viable, and halving the rows is the
 * cheapest place to buy headroom before the answer is "no".
 */
private const val VECTOR_STEP = 2
