package app.trailveil.map

import app.trailveil.map.fog.FogBackdropGeometry
import app.trailveil.map.fog.anchoredNear
import app.trailveil.map.fog.FogMaskContours
import app.trailveil.map.fog.FogMaskRect
import app.trailveil.map.fog.FogMosaicTile
import app.trailveil.map.fog.FogPocMosaic
import app.trailveil.map.fog.FogSurroundExtent
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileMosaic
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.FogViewportCoverageRequest
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.WebMercator
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions

/**
 * `V03-013` arm `vector`: the fog as geometry instead of pixels, with no new dependency.
 *
 * One `Polygon` over the rendered rectangle, holed where the ground is revealed, plus the same
 * surround backdrop arm 2 needs for the same reason. This is prototype B's shape reached by the
 * route that costs nothing: the holes come from the MASK rather than from the track points, so the
 * union that would otherwise need a polygon library has already happened in the rasteriser.
 *
 * **Section 15p predicts what this looks like and this arm exists to test that prediction, not to
 * win.** Holes traced from a 256 px mask follow the mask's grid, so the boundary is the same
 * staircase today's raster has, with a hard polygon edge on it instead of a 3.53 px blend - more
 * visible at high zoom, not less. The reading worth having is the other one: **how many rings a
 * real viewport costs.** A Google `Polygon` carries every hole in one object, so if a phone-shaped
 * viewport decomposes into thousands of rings, that is this arm's answer and `describe` is where
 * it is reported.
 *
 * What it inherits from arm 2 rather than rediscovering: the antimeridian split
 * ([GoogleFogPolygonGeometry.splitForGooglePolygon], which generations 2, 3 and 4 died of in
 * section 15n), the surround complement, and the rule from 15k that an installer must answer
 * `covers` from what it actually DREW rather than from what the tile path would have reached.
 */
internal class GoogleFogVectorOverlayInstaller(
    private val map: GoogleMap,
    private val step: Int = DEFAULT_STEP,
) : GoogleFogOverlayInstaller {

    private class Installed(
        val polygons: List<Polygon>,
        val extent: FogSurroundExtent,
        val rings: Int,
        val truncated: Boolean,
    )

    private val installed = LinkedHashMap<Long, Installed>()
    private var refusals = 0
    private var lastRefusal: String? = null
    private var lastRings: Int = 0
    private var lastTruncated = false

    override fun demoteExisting(): Boolean = installed.values.all { entry ->
        entry.polygons.all { polygon ->
            try {
                polygon.zIndex = OLD_POLYGON_Z
                true
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
        }
    }

    override fun attach(
        generationId: Long,
        coverage: FogViewportCoverageRequest,
        tiles: List<FogMosaicTile>,
    ): Boolean {
        if (tiles.isEmpty()) return refuse("noTiles")
        val mosaic = try {
            FogPocMosaic.compose(tiles).anchoredNear(coverage.center.longitude)
        } catch (_: IllegalArgumentException) {
            return refuse("composeFailed")
        }

        // A holed polygon cannot be halved the way a solid guard rectangle can - a hole would have
        // to be cut at the seam and reattached to whichever half owns it - so a rendered rectangle
        // wide enough to need splitting is refused rather than drawn wrong. A viewport is never
        // this wide; the check is here because "never" is what section 15n also assumed.
        val imageWidth = mosaic.bounds.eastLongitude - mosaic.bounds.westLongitude
        if (!imageWidth.isFinite() ||
            imageWidth > GoogleFogPolygonGeometry.MAX_POLYGON_RING_DEGREES
        ) {
            return refuse("imageTooWide")
        }

        val decomposed = try {
            FogMaskContours.decompose(mosaic.mask, step = step)
        } catch (_: IllegalArgumentException) {
            return refuse("decomposeFailed")
        }
        lastRings = decomposed.rects.size
        lastTruncated = decomposed.truncated

        val holes = decomposed.rects.mapNotNull { rect -> holeRing(mosaic, rect) }
        val signature = FogTilePngCodec.colorForGeneration(generationId)
        val fill = GoogleFogPolygonGeometry.argb(
            GoogleFogPolygonGeometry.VISIBLE_FOG_ALPHA,
            signature.red,
            signature.green,
            signature.blue,
        )

        val attached = mutableListOf<Polygon>()
        val image = addPolygon(
            ring = GoogleFogPolygonGeometry.ring(mosaic.bounds),
            holes = holes,
            fill = fill,
            zIndex = NEW_POLYGON_Z,
        )
        if (image == null) {
            return refuse("addImagePolygon")
        }
        attached += image

        // The backdrop, and the 15k rule with it: the extent this generation claims is the one it
        // DREW. If the surround fails to attach, the claim shrinks to the rendered rectangle rather
        // than staying at the tile path's reach - a smaller claim raises the cover sooner, which is
        // the safe direction, where the reverse put 89.344% of unexplored ground on screen.
        val strips = try {
            GoogleFogPolygonGeometry.surroundComplementOf(mosaic)
                .map(FogBackdropGeometry::anchoredInsideWorld)
                .flatMap { bounds -> GoogleFogPolygonGeometry.splitForGooglePolygon(bounds) }
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        var backdropComplete = strips.isNotEmpty()
        strips.forEach { bounds ->
            val polygon = addPolygon(
                ring = GoogleFogPolygonGeometry.ring(bounds),
                holes = emptyList(),
                fill = fill,
                zIndex = BACKDROP_Z,
            )
            if (polygon == null) backdropComplete = false else attached += polygon
        }

        installed[generationId] = Installed(
            polygons = attached,
            extent = if (backdropComplete) {
                FogBackdropGeometry.extent(mosaic)
            } else {
                imageExtent(mosaic)
            },
            rings = decomposed.rects.size,
            truncated = decomposed.truncated,
        )
        return true
    }

    /**
     * Shows the successor and leaves the predecessor alone.
     *
     * Same reasoning as arm 2's installer: with no delivery barrier there is nothing here that
     * proves the successor actually painted, so hiding the predecessor in the same turn is the one
     * action that could put bare basemap on screen. Two coats of fog for a moment fails closed.
     */
    override fun reveal(generationId: Long, previousGenerationId: Long?): Boolean {
        val entry = installed[generationId] ?: return false
        return entry.polygons.all { polygon ->
            try {
                polygon.zIndex = NEW_POLYGON_Z
                polygon.isVisible = true
                true
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
        }
    }

    override fun remove(generationId: Long): Boolean {
        val entry = installed.remove(generationId) ?: return true
        var removed = true
        entry.polygons.forEach { polygon ->
            try {
                polygon.remove()
            } catch (_: Exception) {
                removed = false
            } catch (_: LinkageError) {
                removed = false
            }
        }
        return removed
    }

    override fun covers(generationId: Long?, visibleCorners: List<GeoPoint>): Boolean {
        val entry = installed[generationId ?: return false] ?: return false
        return entry.extent.covers(visibleCorners)
    }

    override fun release() {
        installed.values.forEach { entry ->
            entry.polygons.forEach { polygon ->
                try {
                    polygon.remove()
                } catch (_: Exception) {
                    // The surface is going away with it.
                } catch (_: LinkageError) {
                    // As above.
                }
            }
        }
        installed.clear()
    }

    /** Ring count is the measurement this arm exists for, so it leads. */
    override fun describe(): String =
        "vector=[rings=$lastRings truncated=$lastTruncated step=$step " +
            "generations=${installed.keys} refusals=$refusals last=$lastRefusal]"

    private fun addPolygon(
        ring: List<LatLng>,
        holes: List<List<LatLng>>,
        fill: Int,
        zIndex: Float,
    ): Polygon? = try {
        map.addPolygon(
            PolygonOptions()
                .addAll(ring)
                .apply { holes.forEach { hole -> addHole(hole) } }
                // Rhumb lines, so a rectangle in longitude and latitude stays one: a geodesic edge
                // would bow away from the parallel it is meant to follow and leave a sliver.
                .geodesic(false)
                .fillColor(fill)
                .strokeWidth(0f)
                .strokeColor(GoogleFogPolygonGeometry.TRANSPARENT)
                .zIndex(zIndex)
                .visible(false)
                .clickable(false),
        )
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    /**
     * A mask rectangle as a geographic ring.
     *
     * Longitude is linear in mask x because Web Mercator's x is linear in longitude. Latitude is
     * NOT linear in y, so it is interpolated in normalised Mercator y and inverted - reading it
     * linearly in latitude would put every hole progressively wrong towards the top and bottom of
     * the rendered rectangle, which at a phone's zoom is a hole sitting off the trail it belongs to.
     */
    private fun holeRing(mosaic: FogTileMosaic, rect: FogMaskRect): List<LatLng>? {
        val bounds = mosaic.bounds
        val width = mosaic.mask.width.toDouble()
        val height = mosaic.mask.height.toDouble()
        if (width <= 0.0 || height <= 0.0) return null
        val northY = WebMercator.normalizedY(bounds.northLatitude)
        val southY = WebMercator.normalizedY(bounds.southLatitude)
        fun longitudeAt(x: Int): Double =
            bounds.westLongitude + (bounds.eastLongitude - bounds.westLongitude) * (x / width)
        fun latitudeAt(y: Int): Double =
            WebMercator.latitudeAtNormalizedY(northY + (southY - northY) * (y / height))

        val west = longitudeAt(rect.left)
        val east = longitudeAt(rect.right)
        val north = latitudeAt(rect.top)
        val south = latitudeAt(rect.bottom)
        if (!west.isFinite() || !east.isFinite() || !north.isFinite() || !south.isFinite()) {
            return null
        }
        if (east <= west || north <= south) return null
        return GoogleFogPolygonGeometry.ring(
            FogTileBounds(
                westLongitude = west,
                southLatitude = south,
                eastLongitude = east,
                northLatitude = north,
            ),
        )
    }

    private fun imageExtent(mosaic: FogTileMosaic): FogSurroundExtent {
        val bounds = mosaic.bounds
        return FogSurroundExtent(
            centerLongitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
            halfWorlds = (bounds.eastLongitude - bounds.westLongitude) / 2.0 /
                FogBackdropGeometry.WORLD_LONGITUDE_SPAN,
            northNormalizedY = WebMercator.normalizedY(bounds.northLatitude),
            southNormalizedY = WebMercator.normalizedY(bounds.southLatitude),
            wrapsWorld = false,
        )
    }

    private fun refuse(reason: String): Boolean {
        refusals += 1
        lastRefusal = reason
        return false
    }

    private companion object {
        const val OLD_POLYGON_Z = 0f
        const val BACKDROP_Z = 0.5f
        const val NEW_POLYGON_Z = 1f

        /**
         * Sample the mask every this many pixels.
         *
         * 1 traces the mask's own granularity, which is what section 15p describes, and costs the
         * most rings. 2 halves the row count and is where a phone-shaped viewport stays inside a
         * ring budget a `Polygon` can carry; the boundary it produces is coarser, and a coarser
         * boundary here can only SHRINK what is revealed, never grow it - see
         * `FogMaskContours.decompose`.
         */
        const val DEFAULT_STEP = 2
    }
}
