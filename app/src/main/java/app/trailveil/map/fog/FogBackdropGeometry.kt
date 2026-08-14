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
 * The edges an installed surround actually has, in fractions of the world.
 *
 * The surround has to be large rather than infinite, because a quad past the renderer's precision
 * is drawn over everything instead of being clipped. So the finite reach is measured against the
 * required gesture, and this type turns that measured margin into a check against the live camera.
 *
 * These are the *installed* edges, not a radius. An earlier version carried a centre and a radius
 * and inferred the rest, which was wrong in both directions: it reported a camera as covered over a
 * strip of map the surround had been trimmed away from, and it reported one as uncovered where the
 * coverage wrapped the world and had no edge to fall off at all.
 *
 * There used to be a second guard beside [covers]: past 0.75 zoom levels out from the zoom the
 * overlay was built at, the map was covered rather than trusted, because a surround two million
 * render pixels across is not drawn where its coordinates say — measured from render zoom 16, 0.05%
 * of the screen bare at 1.63 levels out, 0.40% at 1.96, 0.67% at 2.68. `P4-024` made the surround
 * sixty-four times smaller for an unrelated reason and the drift went with it: re-measured with
 * that guard disabled, a pinch from render zoom 16 out to 13.14 held `uncovered=0.0000%` at every
 * held frame. `P4-022` subsequently required a real four-level pinch, so the surround grew only as
 * far as that measured viewport requires — still more than fifty times below the size that produced
 * the band. [covers] is the honest guard: it measures the viewport against the surround that is
 * actually installed instead of guessing from a zoom difference.
 */
data class FogSurroundExtent(
    val centerLongitude: Double,
    val halfWorlds: Double,
    val northNormalizedY: Double,
    val southNormalizedY: Double,
    /** Whether coverage repeats across world copies, in which case it has no east or west edge. */
    val wrapsWorld: Boolean,
) {
    /**
     * Whether every corner of the ground the camera can actually see is inside the surround.
     *
     * It takes the corners of the map's own visible region, not the camera's position and half the
     * viewport's width and height. Those two are the same shape only when the camera looks straight
     * down with north up. Tilt it and the ground it sees runs away towards the horizon; turn it and
     * the same screen covers a larger, rotated rectangle. Either way an axis-aligned box built from
     * the viewport's size is smaller than what is on screen, and this returned true over map that
     * had no fog on it.
     *
     * Measured before it took corners: a shove to 60 degrees of pitch, then one pinch out, put
     * **14.75%** of the screen — `(0,0)-(1079,1238)`, `bareAtWorst=216`, the unfogged basemap
     * reference exactly — on screen as explored ground, with the safety cover never raised. The
     * same reading at 78°N. A second guard used to hide it by blanking the map past 0.75 zoom
     * levels out; `P4-022` retired that guard, which is what turned a latent blindness into a
     * reachable leak.
     *
     * A corner the projection cannot give a finite answer for never reaches here: [GeoPoint]
     * refuses one, so the caller has to decide before building it, and the only safe decision is
     * not covered. Past the horizon there is no ground to be right about, and guessing in the
     * covering direction is the one direction that shows unexplored map as explored.
     */
    fun covers(visibleCorners: List<GeoPoint>): Boolean {
        if (visibleCorners.isEmpty()) return false
        if (!wrapsWorld) {
            // Measured from the surround's own centre so that a region straddling the antimeridian
            // stays one interval instead of splitting into two far-apart ones.
            var west = Double.POSITIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            visibleCorners.forEach { corner ->
                val offset = WebMercator.wrapLongitude(corner.longitude - centerLongitude)
                west = min(west, offset)
                east = max(east, offset)
            }
            val reach = max(kotlin.math.abs(west), kotlin.math.abs(east)) /
                FogBackdropGeometry.WORLD_LONGITUDE_SPAN
            if (reach > halfWorlds + EDGE_TOLERANCE) return false
        }
        // Only the part of the view inside the world can show map, so that is the part the surround
        // has to reach. Past the poles the projection ends and nothing is drawn.
        var top = 1.0
        var bottom = 0.0
        visibleCorners.forEach { corner ->
            val y = WebMercator.normalizedY(corner.latitude)
            if (!y.isFinite()) return false
            top = min(top, max(0.0, y))
            bottom = max(bottom, min(1.0, y))
        }
        return top >= northNormalizedY - EDGE_TOLERANCE &&
            bottom <= southNormalizedY + EDGE_TOLERANCE
    }

    private companion object {
        /** A billionth of the world: far under one screen pixel at any zoom this renderer draws. */
        const val EDGE_TOLERANCE = 1e-9
    }
}

/**
 * Canonical-world rectangles that fog everything outside one finite surround.
 *
 * Each rectangle stays inside `[-180, 180]`; none crosses the antimeridian. MapLibre can therefore
 * tile and repeat these ordinary polygons without relying on an inverted polygon or a world-sized
 * exterior ring with a hole. The renderer-specific repetition is verified separately because it is
 * not a GeoJSON specification guarantee.
 */
data class FogExtentGuardGeometry(
    val rectangles: List<FogTileBounds>,
)

/**
 * Builds the map-space surround of a rendered fog mosaic.
 *
 * The bands are geographic, so the renderer transforms them with the camera in the same frame
 * that draws the mosaic. Coverage therefore cannot lag a gesture the way any decision taken from
 * an already-dispatched camera callback does.
 *
 * **The surround is measured in screens, not in degrees.** A gesture deliberately never rebuilds
 * the overlay — that is what keeps a pan smooth — so whatever is installed when the fingers land is
 * all the coverage there is until they lift, and what a zoom-out gesture grows is the viewport,
 * which is measured in screens. The two constants this replaced were fractions of the *world*:
 * 0.125 of its height and 60 degrees of its width. That is thousands of screens at zoom 16 and
 * barely one at zoom 4, which is why a single pinch out from zoom 4 presented 46% of the screen as
 * bare basemap while the same pinch at exploration zoom showed nothing wrong.
 *
 * Reaching the world edge everywhere would end the argument, but the renderer will not draw a quad
 * that large at a high zoom — it draws it over everything instead, burying the explored area. So
 * the surround is the world or [MAX_SURROUND_WORLD_PIXELS], whichever is smaller. The surface checks
 * the live camera against [FogSurroundExtent] on dispatched camera-move callbacks, while the bands
 * themselves remain renderer-anchored and cover the required gesture without relying on callback
 * timing.
 */
object FogBackdropGeometry {
    /**
     * Bands overlap the mosaic by half of one mosaic pixel. This remains the small geometric
     * overlap; a renderer-pixel seam guard covers the independent ImageSource quantization gap.
     */
    const val MOSAIC_OVERLAP_PIXELS: Double = 0.5

    const val WORLD_LONGITUDE_SPAN: Double = 360.0

    /**
     * The largest quad the surround is allowed to be, in the renderer's own world pixels.
     *
     * This is the bound the whole design turns on, and both ends of it are measured.
     *
     * The ceiling: past some size a quad is not clipped, it is drawn over the entire map, and the
     * explored area underneath disappears with it. Bisected on the API 36 emulator at render zoom
     * 16 against the revealed track: one whole world is 33.5 million pixels across and buries it
     * completely; 8.4 million also buries it; 4.2 million draws correctly and reveals 2.85% of the
     * screen, matching the reading from before any of this changed.
     *
     * The floor is what actually decides the value, and it is not about coverage at all. Where the
     * surround is clamped, the renderer draws it a little away from where its coordinates put it,
     * by an amount that grows with its size — so its inner edge slides over the mosaic and leaves a
     * band of doubled fog, which is black on a dark basemap. Measured settled, with each quad drawn
     * on its own so the band could be attributed: at 2,097,152 the south band overlapped the mosaic
     * by 50 screen pixels at render zoom 14 and 73 at render zoom 12, full width, 2.1% and 3.0% of
     * the screen; at 524,288 — the same geometry four times smaller, at render zoom 10 — nothing at
     * all. A user photographed that band twice.
     *
     * So the size is chosen to be as small as the job allows rather than as large as the renderer
     * tolerates. The job is one gesture, and `P4-022` requires a pinch through at least four zoom
     * levels on the 2,400-pixel-tall validation viewport. 40,960 render pixels provide just over
     * that geometric reach while remaining more than fifty times below the size that produced the
     * band. Additional reach is not treated as free headroom because renderer drift grows with quad
     * size.
     */
    const val MAX_SURROUND_WORLD_PIXELS: Double = 40_960.0

    /** MapLibre's own tile size, which is what makes a mosaic a known number of screen pixels. */
    const val RENDER_TILE_SIZE_PIXELS: Double = 512.0

    /**
     * How far a repeated world copy overlaps the surround beside it.
     *
     * The mosaic-pixel unit the bands use is meaningless at this seam: it is only ever on screen
     * when a whole world is, and one mosaic pixel at exploration zooms is a millionth of a screen
     * pixel there. A fixed fraction of the world is about two screen pixels at the only zooms that
     * can see it, which is enough to close a rounding gap. The cost is the usual one — a hairline
     * of doubled fog rather than a hairline of bare map.
     */
    const val WORLD_REPEAT_OVERLAP_DEGREES: Double = WORLD_LONGITUDE_SPAN / 512.0

    /**
     * The region the mosaic and its bands cover between them: a square centred on the mosaic,
     * never larger than the world or than [MAX_SURROUND_WORLD_PIXELS].
     *
     * At exploration zooms it extends farther than the required four-level pinch. Through render
     * zoom six it is the whole world, where no gesture can outrun it at all.
     */
    fun surround(mosaic: FogTileMosaic): FogTileBounds {
        val bounds = mosaic.bounds
        val center = (bounds.westLongitude + bounds.eastLongitude) / 2.0
        val halfWorlds = surroundHalfWorlds(mosaic)
        val vertical = verticalSpan(mosaic, halfWorlds)
        return FogTileBounds(
            westLongitude = center - halfWorlds * WORLD_LONGITUDE_SPAN,
            southLatitude = WebMercator.latitudeAtNormalizedY(vertical.second),
            eastLongitude = center + halfWorlds * WORLD_LONGITUDE_SPAN,
            northLatitude = WebMercator.latitudeAtNormalizedY(vertical.first),
        )
    }

    /**
     * The surround's north and south edges in normalized Y, slid inside the world rather than
     * trimmed by it.
     *
     * Trimming was a real hole and not a rounding one. A square centred on a mosaic near a pole
     * loses whatever hangs over the edge, so a surround nominally a world tall covered only three
     * quarters of one — and the quarter it lost was ordinary map with nothing over it. Measured at
     * render zoom 2: a single pan north presented 27.96% of the screen as bare basemap, and a pan
     * to MapLibre's own southern limit reaches 56%. Sliding keeps the whole budgeted height and
     * costs nothing, because past the pole there is no map to cover.
     */
    private fun verticalSpan(mosaic: FogTileMosaic, halfWorlds: Double): Pair<Double, Double> {
        val bounds = mosaic.bounds
        val centerY = (
            WebMercator.normalizedY(bounds.northLatitude) +
                WebMercator.normalizedY(bounds.southLatitude)
            ) / 2.0
        var north = centerY - halfWorlds
        var south = centerY + halfWorlds
        if (north < 0.0) {
            south = min(1.0, south - north)
            north = 0.0
        }
        if (south > 1.0) {
            north = max(0.0, north - (south - 1.0))
            south = 1.0
        }
        return north to south
    }

    /**
     * Half the surround's span, as a fraction of the world, so that longitude and normalized
     * latitude can both use it — Web Mercator is square.
     *
     * One rule: the world, or the largest square the renderer will draw, whichever is smaller.
     */
    fun surroundHalfWorlds(mosaic: FogTileMosaic): Double {
        val worldPixels = worldPixels(mosaic)
        if (worldPixels <= 0.0) return 0.5
        return min(0.5, MAX_SURROUND_WORLD_PIXELS / 2.0 / worldPixels)
    }

    /**
     * How many render pixels the whole world is across at the zoom this mosaic was built for.
     *
     * The mosaic is the only thing here that knows its own zoom, and it knows it twice over: how
     * much of the world it spans, and how many tiles wide it is. One divided by the other is the
     * world.
     */
    fun worldPixels(mosaic: FogTileMosaic): Double {
        val bounds = mosaic.bounds
        val mosaicWorlds = (bounds.eastLongitude - bounds.westLongitude) / WORLD_LONGITUDE_SPAN
        if (mosaicWorlds <= 0.0) return 0.0
        val aspect = mosaic.mask.width.toDouble() / mosaic.mask.height.toDouble()
        val columns = max(1.0, kotlin.math.sqrt(mosaic.tileCount.toDouble() * aspect))
        return columns * RENDER_TILE_SIZE_PIXELS / mosaicWorlds
    }

    /** Where the installed surround is and how far it reaches, for checking a live camera. */
    fun extent(mosaic: FogTileMosaic): FogSurroundExtent {
        val bounds = mosaic.bounds
        val halfWorlds = surroundHalfWorlds(mosaic)
        val vertical = verticalSpan(mosaic, halfWorlds)
        return FogSurroundExtent(
            centerLongitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
            halfWorlds = halfWorlds,
            northNormalizedY = vertical.first,
            southNormalizedY = vertical.second,
            // Where the surround reaches all the way round, a world copy is installed on each side
            // of it, so there is no east or west edge for a camera to fall off.
            wrapsWorld = halfWorlds >= 0.5,
        )
    }

    /**
     * Builds the canonical complement of [extent]. North and south own the corners; longitude
     * complements only span the latitude interval that remains between them.
     */
    fun extentGuard(extent: FogSurroundExtent): FogExtentGuardGeometry {
        val northLatitude = WebMercator.latitudeAtNormalizedY(extent.northNormalizedY)
        val southLatitude = WebMercator.latitudeAtNormalizedY(extent.southNormalizedY)
        val rectangles = buildList {
            if (extent.northNormalizedY > GUARD_EDGE_EPSILON) {
                add(
                    FogTileBounds(
                        westLongitude = -WORLD_LONGITUDE_SPAN / 2.0,
                        southLatitude = northLatitude,
                        eastLongitude = WORLD_LONGITUDE_SPAN / 2.0,
                        northLatitude = WebMercator.MAX_LATITUDE,
                    ),
                )
            }
            if (extent.southNormalizedY < 1.0 - GUARD_EDGE_EPSILON) {
                add(
                    FogTileBounds(
                        westLongitude = -WORLD_LONGITUDE_SPAN / 2.0,
                        southLatitude = -WebMercator.MAX_LATITUDE,
                        eastLongitude = WORLD_LONGITUDE_SPAN / 2.0,
                        northLatitude = southLatitude,
                    ),
                )
            }
            if (!extent.wrapsWorld && northLatitude > southLatitude) {
                addAll(
                    longitudeGuardIntervals(extent).map { interval ->
                        FogTileBounds(
                            westLongitude = interval.first,
                            southLatitude = southLatitude,
                            eastLongitude = interval.second,
                            northLatitude = northLatitude,
                        )
                    },
                )
            }
        }
        return FogExtentGuardGeometry(
            rectangles.flatMap { rectangle -> rectangle.splitForCanonicalGeoJson() },
        )
    }

    /**
     * Splits a ring wider than half the canonical world into halves of at most
     * [MAX_GUARD_RECTANGLE_DEGREES]. A span strictly wider than 180° is ambiguous to GeoJSON
     * winding — the renderer may interpret it as the complementary short way round — while an
     * exactly-180° ring is unambiguous and is deliberately emitted by this split; the unit gate
     * pins that ceiling.
     */
    private fun FogTileBounds.splitForCanonicalGeoJson(): List<FogTileBounds> {
        val width = eastLongitude - westLongitude
        if (width <= MAX_GUARD_RECTANGLE_DEGREES + GUARD_EDGE_EPSILON) return listOf(this)
        val middle = (westLongitude + eastLongitude) / 2.0
        return listOf(
            copy(eastLongitude = middle),
            copy(westLongitude = middle),
        ).flatMap { it.splitForCanonicalGeoJson() }
    }

    /** The longitude complement, split so every interval is canonical and non-wrapping. */
    private fun longitudeGuardIntervals(extent: FogSurroundExtent): List<Pair<Double, Double>> {
        val worldWest = -WORLD_LONGITUDE_SPAN / 2.0
        val worldEast = WORLD_LONGITUDE_SPAN / 2.0
        val center = WebMercator.wrapLongitude(extent.centerLongitude)
        val halfDegrees = extent.halfWorlds * WORLD_LONGITUDE_SPAN
        val safeWest = center - halfDegrees
        val safeEast = center + halfDegrees
        val intervals = when {
            safeWest < worldWest -> listOf(safeEast to (safeWest + WORLD_LONGITUDE_SPAN))
            safeEast > worldEast -> listOf((safeEast - WORLD_LONGITUDE_SPAN) to safeWest)
            else -> listOf(worldWest to safeWest, safeEast to worldEast)
        }
        return intervals.filter { (west, east) -> east - west > GUARD_EDGE_EPSILON }
    }

    /**
     * The same quad, moved by whole worlds until its centre lies inside the canonical one.
     *
     * Below the zoom where the renderer repeats an image source across world copies by itself, a
     * quad that lies *entirely* past the world's edge is drawn twice — once where its coordinates
     * put it and once where that repetition puts it, on the same pixels, as a second coat of fog.
     * A quad that merely crosses the edge is drawn once.
     *
     * Measured on the production style with every other fog layer hidden, so nothing else could be
     * the second coat: the east band alone reported 5.31% of the screen under more than one coat
     * over its own footprint at an ordinary place and 8.25% past the antimeridian, while the mosaic
     * and the full-width bands — which cross the edge rather than lying past it — reported none.
     * The east band was the only quad whose centre was outside the world, at 236.25° and 281.25°.
     *
     * A quad contains its own centre, so keeping the centre inside is what keeps the quad from
     * lying wholly outside. Shifting by whole worlds is exact and changes no ground: what the quad
     * covers is the same ground named in a neighbouring copy.
     */
    fun anchoredInsideWorld(bounds: FogTileBounds): FogTileBounds {
        val center = (bounds.westLongitude + bounds.eastLongitude) / 2.0
        if (!center.isFinite()) return bounds
        val worlds = Math.floor(
            (center + WORLD_LONGITUDE_SPAN / 2.0) / WORLD_LONGITUDE_SPAN,
        )
        if (worlds == 0.0) return bounds
        val shift = worlds * WORLD_LONGITUDE_SPAN
        return bounds.copy(
            westLongitude = bounds.westLongitude - shift,
            eastLongitude = bounds.eastLongitude - shift,
        )
    }

    /**
     * Everything at the mosaic's own latitudes that the mosaic does not cover, as a single quad.
     *
     * This is what the west and east bands become where the renderer repeats by itself. Those two
     * are anchored to the camera's copy of the world, which is what they must be when the renderer
     * does not repeat — but it puts whichever of them is on the far side of the mosaic wholly past
     * the world's edge, and [anchoredInsideWorld] records what that costs. One quad from the
     * mosaic's east edge round to its west cannot have a far side, tiles the world exactly once
     * with the mosaic, and is repeated into every copy by the renderer at no cost.
     */
    fun wrappedSideBand(mosaic: FogTileMosaic): FogTileBounds? {
        if (spansWorld(mosaic) || !surroundSpansWorld(mosaic)) return null
        val bounds = mosaic.bounds
        val overlap = longitudeOverlap(mosaic)
        val inner = innerLatitudes(mosaic)
        return anchoredInsideWorld(
            FogTileBounds(
                westLongitude = bounds.eastLongitude - overlap,
                southLatitude = inner.second,
                eastLongitude = bounds.westLongitude + overlap + WORLD_LONGITUDE_SPAN,
                northLatitude = inner.first,
            ),
        )
    }

    private fun longitudeOverlap(mosaic: FogTileMosaic): Double {
        val bounds = mosaic.bounds
        return (bounds.eastLongitude - bounds.westLongitude) / mosaic.mask.width *
            MOSAIC_OVERLAP_PIXELS
    }

    /** The mosaic's north and south edges pulled half a mosaic pixel inwards, north first. */
    private fun innerLatitudes(mosaic: FogTileMosaic): Pair<Double, Double> {
        val bounds = mosaic.bounds
        val northY = WebMercator.normalizedY(bounds.northLatitude)
        val southY = WebMercator.normalizedY(bounds.southLatitude)
        val overlapY = (southY - northY) / mosaic.mask.height * MOSAIC_OVERLAP_PIXELS
        return WebMercator.latitudeAtNormalizedY(northY + overlapY) to
            WebMercator.latitudeAtNormalizedY(southY - overlapY)
    }

    fun bands(mosaic: FogTileMosaic): FogBackdropBands {
        val bounds = mosaic.bounds
        val longitudeOverlap = longitudeOverlap(mosaic)
        val inner = innerLatitudes(mosaic)
        val innerNorth = inner.first
        val innerSouth = inner.second
        val surround = surround(mosaic)
        // A mosaic that spans the whole world is exactly as wide as its own surround, so the side
        // bands would otherwise be handed an inverted span rather than a closed one.
        val sideWest = min(surround.westLongitude, bounds.westLongitude)
        val sideEast = max(surround.eastLongitude, bounds.eastLongitude)
        return FogBackdropBands(
            north = FogTileBounds(
                westLongitude = surround.westLongitude,
                southLatitude = innerNorth,
                eastLongitude = surround.eastLongitude,
                northLatitude = surround.northLatitude,
            ),
            south = FogTileBounds(
                westLongitude = surround.westLongitude,
                southLatitude = surround.southLatitude,
                eastLongitude = surround.eastLongitude,
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

    /**
     * The world either side of the surround, west first.
     *
     * One world of coverage is not one world of viewport. The basemap repeats across world copies
     * and an image source does not, so a viewport wider than the world — a landscape or desktop
     * window at the zoom MapLibre floors at — sees the same ground again beside the surround with
     * nothing over it. These carry the same flat fog there. Each stays exactly one world wide, so
     * no quad this code installs is ever larger than the largest one the renderer is known to
     * draw: the whole-world mosaic it already builds at render zoom 0.
     */
    fun worldRepeats(mosaic: FogTileMosaic): List<FogTileBounds> {
        val surround = surround(mosaic)
        return listOf(
            surround.copy(
                westLongitude = surround.westLongitude - WORLD_LONGITUDE_SPAN +
                    WORLD_REPEAT_OVERLAP_DEGREES,
                eastLongitude = surround.westLongitude + WORLD_REPEAT_OVERLAP_DEGREES,
            ),
            surround.copy(
                westLongitude = surround.eastLongitude - WORLD_REPEAT_OVERLAP_DEGREES,
                eastLongitude = surround.eastLongitude + WORLD_LONGITUDE_SPAN -
                    WORLD_REPEAT_OVERLAP_DEGREES,
            ),
        )
    }

    /**
     * Whether a mosaic already covers a whole world by itself.
     *
     * When it does it is repeated a world either side instead, because those copies still have to
     * show what the user explored rather than flat fog.
     */
    fun spansWorld(mosaic: FogTileMosaic): Boolean =
        mosaic.bounds.eastLongitude - mosaic.bounds.westLongitude >= WORLD_LONGITUDE_SPAN

    /**
     * Whether the surround reaches all the way round, which is the only case where a world copy
     * beside it can be seen — and the only case where one may be installed, since a world-wide quad
     * is only within [MAX_SURROUND_WORLD_PIXELS] at the zooms where the world itself is.
     */
    fun surroundSpansWorld(mosaic: FogTileMosaic): Boolean = surroundHalfWorlds(mosaic) >= 0.5

    private const val GUARD_EDGE_EPSILON = 1e-12
    private const val MAX_GUARD_RECTANGLE_DEGREES = 180.0
}
