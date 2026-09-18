package app.trailveil.map.fog

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlin.math.abs
import kotlin.math.round

// Frozen b49 planner body; only the class name is changed.
class FogSnapshotVisualProbePlannerB49Reference(
    private val blocksPerAxis: Int = DEFAULT_BLOCKS_PER_AXIS,
) {
    init {
        require(blocksPerAxis > 0) { "blocksPerAxis must be positive" }
    }

    fun plan(
        request: FogViewportCoverageRequest,
        masks: Map<FogTileKey, FogPixelMask>,
        exclusionZones: List<FogProbeExclusionZone> = emptyList(),
        /** Called at bounded work boundaries; cancellation throws rather than returning a partial plan. */
        checkActive: () -> Unit = {},
    ): FogSnapshotVisualProbePlan {
        checkActive()
        require(masks.isNotEmpty()) { "visual probe masks must not be empty" }
        require(masks.keys.all { key -> key.zoom == request.floorZoom }) {
            "visual probe masks must match the viewport zoom"
        }
        val tileCount = 1 shl request.floorZoom
        val centerX = ((request.center.longitude + 180.0) / 360.0) * tileCount
        require(centerX.isFinite()) { "visual probe centre must project to finite X" }
        val fullWorld = request.visibleCorners().let { corners ->
            corners.maxOf(GeoPoint::longitude) - corners.minOf(GeoPoint::longitude) >= 360.0
        }
        val polygon = request.visibleCorners().map { point ->
            ProjectedProbePoint(
                x = centerX +
                    WebMercator.wrapLongitude(point.longitude - request.center.longitude) /
                    360.0 * tileCount,
                y = WebMercator.normalizedY(point.latitude) * tileCount,
            )
        }
        require(polygon.all { point -> point.x.isFinite() && point.y.isFinite() }) {
            "visual probe polygon must be finite"
        }
        // V02-012 (seventh AVD run): a tile whose square meets the visible polygon in no world
        // column has no visible pixel centre, so both passes of findVisibleOpaqueProbes would
        // scan all of it, with a point-in-polygon test per pixel per world column, and find
        // nothing. A tilted, zoomed-out camera publishes about 240 floor-zoom masks of which the
        // SDK draws about 31; scanning the other 209 held the main thread for 6.8 s per proof on
        // the API 36 AVD, and the screen with it. The cull below is a separating-axis test, exact
        // for a convex polygon (the SDK's visible region is a perspective trapezoid); a polygon
        // that is not convex takes the per-pixel path for every tile, so the plan is identical
        // either way. The full-world rule is the y-range test isVisible applies, at tile grain.
        val convexPolygon = !fullWorld && isConvex(polygon)
        val fullWorldMinimumY = polygon.minOf(ProjectedProbePoint::y)
        val fullWorldMaximumY = polygon.maxOf(ProjectedProbePoint::y)

        /** Whether the projected rectangle (tile units, one world column) can hold a visible pixel centre. */
        fun rectangleMayHoldVisiblePixel(x0: Double, y0: Double, x1: Double, y1: Double): Boolean = when {
            fullWorld -> y0 < fullWorldMaximumY && y1 > fullWorldMinimumY
            convexPolygon -> rectangleMeetsConvexPolygon(x0, y0, x1, y1, polygon)
            else -> true
        }

        val probesByKey = LinkedHashMap<FogTileKey, List<FogSnapshotVisualProbe>>()
        val zoneBlockedKeys = LinkedHashSet<FogTileKey>()
        masks.forEach { (key, mask) ->
            checkActive()
            require(mask.width > 0 && mask.height > 0) { "visual probe mask must be non-empty" }
            val nearestWorld = round((centerX - (key.x + 0.5)) / tileCount).toInt()
            val worldColumns = ((nearestWorld - 1)..(nearestWorld + 1)).map { world ->
                key.x + world * tileCount
            }
            val mayHoldVisiblePixel = worldColumns.any { column ->
                rectangleMayHoldVisiblePixel(column.toDouble(), key.y.toDouble(), column + 1.0, key.y + 1.0)
            }
            if (!mayHoldVisiblePixel) return@forEach
            val selection = findVisibleOpaqueProbes(
                key = key,
                mask = mask,
                polygon = polygon,
                worldColumns = worldColumns,
                tileCount = tileCount,
                fullWorld = fullWorld,
                exclusionZones = exclusionZones,
                rectangleMayHoldVisiblePixel = ::rectangleMayHoldVisiblePixel,
                checkActive = checkActive,
            )
            if (selection.probes.isNotEmpty()) {
                probesByKey[key] = selection.probes
            } else if (selection.visibleOpaqueSeen) {
                // Bounded-exclusion rule: every visible opaque pixel fell inside a zone. The
                // tile stays REQUIRED (never probe-exempt) via the plan's zone-blocked set.
                zoneBlockedKeys += key
            }
        }
        checkActive()
        return FogSnapshotVisualProbePlan(masks.keys, probesByKey, zoneBlockedKeys)
    }

    private class ProbeSelection(
        val probes: List<FogSnapshotVisualProbe>,
        val visibleOpaqueSeen: Boolean,
    )

    private fun findVisibleOpaqueProbes(
        key: FogTileKey,
        mask: FogPixelMask,
        polygon: List<ProjectedProbePoint>,
        worldColumns: List<Int>,
        tileCount: Int,
        fullWorld: Boolean,
        exclusionZones: List<FogProbeExclusionZone>,
        rectangleMayHoldVisiblePixel: (Double, Double, Double, Double) -> Boolean,
        checkActive: () -> Unit,
    ): ProbeSelection {
        val probes = ArrayList<FogSnapshotVisualProbe>(blocksPerAxis * blocksPerAxis)
        var visibleOpaqueSeen = false
        fun outsideZones(x: Int, y: Int): Boolean {
            visibleOpaqueSeen = true
            if (exclusionZones.isEmpty()) return true
            val normalizedX = (key.x + (x + 0.5) / mask.width) / tileCount
            val normalizedY = (key.y + (y + 0.5) / mask.height) / tileCount
            val latitude = WebMercator.latitudeAtNormalizedY(normalizedY)
            val longitude = normalizedX * 360.0 - 180.0
            return exclusionZones.none { zone -> zone.contains(latitude, longitude) }
        }
        for (blockY in 0 until blocksPerAxis) {
            val yStart = blockY * mask.height / blocksPerAxis
            val yEnd = (blockY + 1) * mask.height / blocksPerAxis
            for (blockX in 0 until blocksPerAxis) {
                checkActive()
                val xStart = blockX * mask.width / blocksPerAxis
                val xEnd = (blockX + 1) * mask.width / blocksPerAxis
                // V02-012 design 2h: the tile cull at block grain. A tile the visible trapezoid
                // crosses is mostly outside it, and both passes below scan every outside block in
                // full; a block that cannot hold a visible pixel centre is skipped instead. The
                // plan is unchanged (the tests compare the kept blocks with a per-pixel reference).
                val blockMayHoldVisiblePixel = worldColumns.any { column ->
                    rectangleMayHoldVisiblePixel(
                        column + xStart.toDouble() / mask.width,
                        key.y + yStart.toDouble() / mask.height,
                        column + xEnd.toDouble() / mask.width,
                        key.y + yEnd.toDouble() / mask.height,
                    )
                }
                if (!blockMayHoldVisiblePixel) continue
                // Carry-forward F: one probe per block made the whole install hinge on one pixel,
                // and the owner decision keeps Google's labels and POI icons compositing ABOVE the
                // fog overlay. A glyph over that pixel is deterministic, so re-planning a
                // stationary camera reproduces it on all ten attempts and the tile can never be
                // verified. Collect several separated candidates per block instead; the prover
                // needs any one of them, so the block still demands positive fog evidence and
                // nothing about the fail-closed rule is traded away.
                val separation = maxOf(1, minOf(xEnd - xStart, yEnd - yStart) / 2)
                val chosen = ArrayList<ProbePixel>(CANDIDATES_PER_BLOCK)
                val strongCount = collectSeparatedPixels(
                    mask, xStart, xEnd, yStart, yEnd, separation, chosen, checkActive,
                ) { x, y ->
                    hasOpaqueNeighbourhood(mask, x, y) &&
                        isVisible(key, mask, x, y, polygon, worldColumns, fullWorld) &&
                        outsideZones(x, y)
                }
                // Weak pixels only fill the slots the strong pass could not, and they are appended
                // after them, so a block's first candidate stays the best evidence available.
                collectSeparatedPixels(
                    mask, xStart, xEnd, yStart, yEnd, separation, chosen, checkActive,
                ) { x, y ->
                    mask.alphaAt(x, y) != 0 &&
                        isVisible(key, mask, x, y, polygon, worldColumns, fullWorld) &&
                        outsideZones(x, y)
                }
                chosen.forEachIndexed { index, pixel ->
                    probes += pixel.toProbe(
                        key = key,
                        mask = mask,
                        tileCount = tileCount,
                        strong = index < strongCount,
                        blockIndex = blockY * blocksPerAxis + blockX,
                    )
                }
            }
        }
        return ProbeSelection(probes, visibleOpaqueSeen)
    }

    /**
     * Appends matching pixels from one block to [into] until it holds [CANDIDATES_PER_BLOCK], each
     * at least [separation] pixels (Chebyshev) away from every pixel already there. Returns how
     * many this pass appended.
     *
     * The separation is what makes the extra candidates worth anything: pixels next to each other
     * are under the same label glyph, so unspread candidates are not fallbacks at all.
     *
     * The separation test deliberately runs BEFORE [predicate], which carries the
     * `visibleOpaqueSeen` side effect. Skipping it can only under-report on a block that has
     * already chosen a candidate, and a tile with any chosen candidate never consults that flag.
     */
    private fun collectSeparatedPixels(
        mask: FogPixelMask,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        separation: Int,
        into: MutableList<ProbePixel>,
        checkActive: () -> Unit,
        predicate: (Int, Int) -> Boolean,
    ): Int {
        var added = 0
        if (into.size >= CANDIDATES_PER_BLOCK) return added
        for (y in yStart until yEnd) {
            // A row check every eight rows bounds cancellation work without making a Job lookup
            // part of every tiny candidate row. Each block/tile also checks before it starts.
            if ((y - yStart) % 8 == 0) checkActive()
            for (x in xStart until xEnd) {
                val crowded = into.any { chosen ->
                    maxOf(abs(chosen.x - x), abs(chosen.y - y)) < separation
                }
                if (crowded || !predicate(x, y)) continue
                into += ProbePixel(x, y)
                added += 1
                if (into.size >= CANDIDATES_PER_BLOCK) return added
            }
        }
        return added
    }

    private fun hasOpaqueNeighbourhood(mask: FogPixelMask, x: Int, y: Int): Boolean {
        if (x !in 1 until mask.width - 1 || y !in 1 until mask.height - 1) return false
        for (offsetY in -1..1) {
            for (offsetX in -1..1) {
                if (mask.alphaAt(x + offsetX, y + offsetY) == 0) return false
            }
        }
        return true
    }

    private fun isVisible(
        key: FogTileKey,
        mask: FogPixelMask,
        x: Int,
        y: Int,
        polygon: List<ProjectedProbePoint>,
        worldColumns: List<Int>,
        fullWorld: Boolean,
    ): Boolean {
        val projectedY = key.y + (y + 0.5) / mask.height
        return worldColumns.any { column ->
            val point = ProjectedProbePoint(
                x = column + (x + 0.5) / mask.width,
                y = projectedY,
            )
            if (fullWorld) {
                val minimumY = polygon.minOf(ProjectedProbePoint::y)
                val maximumY = polygon.maxOf(ProjectedProbePoint::y)
                point.y in minimumY..maximumY
            } else {
                pointInPolygon(point, polygon)
            }
        }
    }

    /** Strictly convex, in either winding; a degenerate or reflex polygon answers false. */
    private fun isConvex(polygon: List<ProjectedProbePoint>): Boolean {
        if (polygon.size < 3) return false
        var sign = 0
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            val c = polygon[(index + 2) % polygon.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val current = when {
                cross > 0.0 -> 1
                cross < 0.0 -> -1
                else -> return false
            }
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    /**
     * Whether the axis-aligned rectangle [x0, x1] x [y0, y1] meets [polygon], which must be
     * convex: separating-axis test over the rectangle's two axes and the polygon's edge normals.
     * Touching counts as meeting, so a tile or block is only ever culled when no pixel centre of
     * it can be inside.
     */
    private fun rectangleMeetsConvexPolygon(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        polygon: List<ProjectedProbePoint>,
    ): Boolean {
        if (polygon.all { point -> point.x < x0 } || polygon.all { point -> point.x > x1 }) return false
        if (polygon.all { point -> point.y < y0 } || polygon.all { point -> point.y > y1 }) return false
        var previous = polygon.last()
        for (current in polygon) {
            val normalX = -(current.y - previous.y)
            val normalY = current.x - previous.x
            previous = current
            if (normalX == 0.0 && normalY == 0.0) continue
            var polygonMinimum = Double.POSITIVE_INFINITY
            var polygonMaximum = Double.NEGATIVE_INFINITY
            for (vertex in polygon) {
                val projected = vertex.x * normalX + vertex.y * normalY
                polygonMinimum = minOf(polygonMinimum, projected)
                polygonMaximum = maxOf(polygonMaximum, projected)
            }
            var squareMinimum = Double.POSITIVE_INFINITY
            var squareMaximum = Double.NEGATIVE_INFINITY
            for (cornerX in doubleArrayOf(x0, x1)) {
                for (cornerY in doubleArrayOf(y0, y1)) {
                    val projected = cornerX * normalX + cornerY * normalY
                    squareMinimum = minOf(squareMinimum, projected)
                    squareMaximum = maxOf(squareMaximum, projected)
                }
            }
            if (squareMaximum < polygonMinimum || polygonMaximum < squareMinimum) return false
        }
        return true
    }

    private fun pointInPolygon(
        point: ProjectedProbePoint,
        polygon: List<ProjectedProbePoint>,
    ): Boolean {
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            if (
                (current.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
            ) {
                inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun ProbePixel.toProbe(
        key: FogTileKey,
        mask: FogPixelMask,
        tileCount: Int,
        strong: Boolean,
        blockIndex: Int,
    ): FogSnapshotVisualProbe {
        val normalizedX = (key.x + (x + 0.5) / mask.width) / tileCount
        val normalizedY = (key.y + (y + 0.5) / mask.height) / tileCount
        return FogSnapshotVisualProbe(
            key = key,
            latitude = WebMercator.latitudeAtNormalizedY(normalizedY),
            longitude = normalizedX * 360.0 - 180.0,
            strongNeighbourhood = strong,
            blockIndex = blockIndex,
        )
    }

    private data class ProjectedProbePoint(val x: Double, val y: Double)
    private data class ProbePixel(val x: Int, val y: Int)

    companion object {
        const val DEFAULT_BLOCKS_PER_AXIS = 16

        /**
         * How many interchangeable candidates one block may contribute. Four is what a 16 x 16
         * block fits at half-block separation; more would cost plan size and prover work for
         * candidates too close together to survive a glyph the first four did not.
         */
        const val CANDIDATES_PER_BLOCK = 4
    }
}
