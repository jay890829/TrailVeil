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
 * is drawn over everything instead of being clipped. So the reach is an argument — thousands of
 * screens, more than any gesture travels — and this is what turns that argument into a check the
 * camera is measured against on every frame it moves.
 *
 * These are the *installed* edges, not a radius. An earlier version carried a centre and a radius
 * and inferred the rest, which was wrong in both directions: it reported a camera as covered over a
 * strip of map the surround had been trimmed away from, and it reported one as uncovered where the
 * coverage wrapped the world and had no edge to fall off at all.
 */
data class FogSurroundExtent(
    val centerLongitude: Double,
    val halfWorlds: Double,
    val northNormalizedY: Double,
    val southNormalizedY: Double,
    /** Whether coverage repeats across world copies, in which case it has no east or west edge. */
    val wrapsWorld: Boolean,
    /** The camera zoom this surround was built for, or `null` when it was not recorded. */
    val builtAtZoom: Double? = null,
) {
    /**
     * How far a gesture may zoom out before the surround stops being drawn where it says it is.
     *
     * A clamped surround is millions of render pixels across, and where it lands drifts from where
     * its coordinates say by an amount that grows with its size — so the seam between it and the
     * mosaic opens as the camera zooms away from the zoom it was built for. Measured from render
     * zoom 16 on the API 36 emulator, auditing the map *underneath* the cover so the leak the cover
     * hides is still counted: nothing at 1.57 levels out, 0.05% of the screen bare at 1.63, 0.13%
     * at 1.69, 0.40% at 1.96, 0.67% at 2.68. The margin under this bound is therefore about 0.88
     * levels. The gap also shrank by the same factor as the surround when the surround was made
     * eight times smaller, which is what says it is proportional to size rather than incidental.
     *
     * Below this the map is covered rather than allowed to leak. It costs a dark map during a long
     * pinch from close in; it does not affect panning, and it does not affect the zooms where the
     * surround is the whole world — there the quads are small enough that the drift is under a
     * pixel, measured at 0.0000% through a full pinch.
     */
    fun outrunByZoom(cameraZoom: Double): Boolean {
        if (wrapsWorld) return false
        val built = builtAtZoom ?: return false
        return built - cameraZoom > MAX_UNCOVERED_ZOOM_OUT_LEVELS
    }

    /** Whether a viewport of the given size, in fractions of the world, is entirely inside. */
    fun covers(
        cameraLongitude: Double,
        cameraLatitude: Double,
        viewportHalfWorldsX: Double,
        viewportHalfWorldsY: Double,
    ): Boolean {
        if (!cameraLongitude.isFinite() || !cameraLatitude.isFinite()) return false
        if (!viewportHalfWorldsX.isFinite() || !viewportHalfWorldsY.isFinite()) return false
        if (!wrapsWorld) {
            val offset = kotlin.math.abs(
                WebMercator.wrapLongitude(cameraLongitude - centerLongitude),
            ) / FogBackdropGeometry.WORLD_LONGITUDE_SPAN
            if (offset + viewportHalfWorldsX > halfWorlds + EDGE_TOLERANCE) return false
        }
        // Only the part of the viewport inside the world can show map, so that is the part the
        // surround has to reach. Past the poles the projection ends and nothing is drawn.
        val cameraY = WebMercator.normalizedY(cameraLatitude)
        val top = max(0.0, cameraY - viewportHalfWorldsY)
        val bottom = min(1.0, cameraY + viewportHalfWorldsY)
        return top >= northNormalizedY - EDGE_TOLERANCE &&
            bottom <= southNormalizedY + EDGE_TOLERANCE
    }

    private companion object {
        /** A billionth of the world: far under one screen pixel at any zoom this renderer draws. */
        const val EDGE_TOLERANCE = 1e-9

        /** Three quarters of the 1.18 levels measured clean, so the bound has margin under it. */
        const val MAX_UNCOVERED_ZOOM_OUT_LEVELS = 0.75
    }
}

/**
 * Which of the two arrangements of the ground beside the mosaic is drawn.
 *
 * There are two, and they are alternatives: a pair of side bands anchored to the camera's copy of
 * the world, and one wrapped band that runs from the mosaic's east edge round to its west. Exactly
 * one of them must be drawn — both is a second coat of fog over the ground they share, neither is
 * that ground presented as explored.
 *
 * A rule rather than a branch at the call site, because the call site got it wrong: it returned
 * early when there was no wrapped band to show, which also skipped turning the side bands back on
 * after a rebuild removed the band that had replaced them.
 */
data class FogSideBands(
    val sideBandsVisible: Boolean,
    val wrappedBandVisible: Boolean,
) {
    init {
        require(sideBandsVisible != wrappedBandVisible) {
            "exactly one arrangement of the ground beside the mosaic may be drawn"
        }
    }

    companion object {
        /**
         * The wrapped band exists only where the surround spans a world, and is drawn only where
         * the renderer repeats an image source by itself. Everywhere else the side bands are the
         * only cover there is, so the absence of a wrapped band is what decides this, not the
         * camera alone.
         */
        fun forCamera(rendererRepeatsWorldCopies: Boolean, hasWrappedBand: Boolean): FogSideBands {
            val useWrapped = rendererRepeatsWorldCopies && hasWrappedBand
            return FogSideBands(
                sideBandsVisible = !useWrapped,
                wrappedBandVisible = useWrapped,
            )
        }
    }
}

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
 * the surround is the world or [MAX_SURROUND_WORLD_PIXELS], whichever is smaller, and the surface
 * checks the live camera against [FogSurroundExtent] every frame rather than trusting that the
 * remaining headroom is enough.
 */
object FogBackdropGeometry {
    /**
     * Bands overlap the mosaic by half of one mosaic pixel. Both shapes are projected
     * independently, so an exact shared edge could still round apart into a one-pixel seam of
     * unfogged map; overlapping instead can only ever cost half a pixel of revealed area.
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
     * tolerates. The job is one gesture: the map is rebuilt the moment a gesture ends, a measured
     * pinch travels 2.7 zoom levels, and [FogSurroundExtent.outrunByZoom] covers the map at 0.75
     * levels out regardless. This is about eighty screens across on a phone, four to five zoom
     * levels — comfortably past both, and sixty-four times under the size the band was measured at.
     */
    const val MAX_SURROUND_WORLD_PIXELS: Double = 32_768.0

    /** MapLibre's own tile size, which is what makes a mosaic a known number of screen pixels. */
    const val RENDER_TILE_SIZE_PIXELS: Double = 512.0

    /** Below this the renderer repeats an image source itself; at and above it, it does not. */
    const val LOWEST_SELF_REPEATING_RENDER_ZOOM: Int = 1

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
     * At the zooms exploration happens at that is thousands of screens in every direction — far
     * more than any one gesture travels before it ends and the fog is rebuilt. Below render zoom
     * thirteen it is the whole world, where no gesture can outrun it at all.
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

    /**
     * The render zoom a mosaic was built for, recovered from its own size.
     *
     * Below render zoom 1 MapLibre repeats an image source across world copies by itself, and above
     * it does not — which is why a mosaic that spans the world has to be repeated explicitly at
     * render zoom 1 and must *not* be at render zoom 0. Measured at the antimeridian on the
     * production style: repeating at render zoom 0 puts a second coat of fog over 50.39% of the
     * screen, which is the black half-map a user reported; not repeating at render zoom 1 leaves
     * 49.72% of it bare.
     */
    fun renderZoom(mosaic: FogTileMosaic): Int {
        val worldPixels = worldPixels(mosaic)
        if (worldPixels <= RENDER_TILE_SIZE_PIXELS) return 0
        return Math.round(kotlin.math.ln(worldPixels / RENDER_TILE_SIZE_PIXELS) / kotlin.math.ln(2.0))
            .toInt()
            .coerceIn(0, 22)
    }

    /**
     * Whether a world-spanning mosaic needs explicit copies beside it, or already has them from the
     * renderer. See [renderZoom].
     */
    fun needsMosaicRepeats(mosaic: FogTileMosaic): Boolean =
        spansWorld(mosaic) && renderZoom(mosaic) >= LOWEST_SELF_REPEATING_RENDER_ZOOM

    /** Where the installed surround is and how far it reaches, for checking a live camera. */
    fun extent(mosaic: FogTileMosaic, builtAtZoom: Double? = null): FogSurroundExtent {
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
            builtAtZoom = builtAtZoom,
        )
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
}
