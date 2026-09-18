package app.trailveil.map.fog

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object FogPocSpatialSelection {
    /**
     * Conservatively assigns whole segments to target tiles in one traversal of their points.
     *
     * Whole-segment assignment preserves the renderer's capsule continuity. Bounding boxes
     * include endpoint reveal radii and wrapped world copies, so selection can over-include but
     * cannot omit a circle or capsule that may affect a target tile.
     */
    fun select(
        keys: List<FogTileKey>,
        segments: List<TrackSegment>,
        style: FogRenderStyle = FogRenderStyle(),
    ): Map<FogTileKey, List<TrackSegment>> {
        if (keys.isEmpty()) return emptyMap()
        val zoom = keys.first().zoom
        require(keys.all { it.zoom == zoom }) { "all target tiles must use the same zoom" }
        val targetKeys = keys.distinct()
        val selected = targetKeys.associateWith { mutableListOf<TrackSegment>() }
        val tileSize = style.tileSize
        val worldSize = tileSize.toDouble() * (1 shl zoom)
        val projectionFrame = FogRasterProjectionScope.current(zoom, style)
        val halfWorldTolerance = max(1e-9, Math.ulp(worldSize) * 4.0)

        // `V03-013`: the per-point work is the whole cost of this pass at low zoom (0.85 s over
        // 204,800 points on the API 36 AVD), so the targets are parallel arrays, the point and
        // capsule bounds are locals, and a segment stops projecting once every target already
        // takes it - the reference implementation kept in `FogRasterEquivalenceTest` marks the
        // same tiles, it just could not mark any more by then.
        val targetCount = targetKeys.size
        val targetLefts = DoubleArray(targetCount) { index -> targetKeys[index].x.toDouble() * tileSize }
        val targetTops = DoubleArray(targetCount) { index -> targetKeys[index].y.toDouble() * tileSize }
        val affected = BooleanArray(targetCount)

        for (segment in segments) {
            affected.fill(false)
            var affectedCount = 0
            val points = segment.points
            val projection = projectionFrame?.segment(segment)
            var hasPrevious = false
            var previousX = 0.0
            var previousY = 0.0
            var previousRadius = 0.0
            var previousWrappedX = 0.0
            for (index in points.indices) {
                if (affectedCount == targetCount) break
                val wrappedX: Double
                val y: Double
                val x: Double
                val radius: Double
                if (projection != null) {
                    val position = projection.ensure(index)
                    val values = projection.values
                    wrappedX = values[position + 3]
                    y = values[position + 1]
                    x = values[position]
                    radius = values[position + 2]
                } else {
                    val point = points[index]
                    wrappedX = WebMercator.normalizedX(point.longitude) * worldSize
                    y = WebMercator.normalizedY(point.latitude) * worldSize
                    x = if (hasPrevious) WebMercator.unwrapWorldX(previousX, wrappedX, worldSize) else wrappedX
                    radius = style.revealRadiusMeters /
                        WebMercator.metersPerPixel(point.latitude, zoom, tileSize)
                }
                affectedCount = markIntersectingTargets(
                    targetLefts, targetTops, tileSize, worldSize, affected, affectedCount,
                    minX = x - radius,
                    minY = y - radius,
                    maxX = x + radius,
                    maxY = y + radius,
                )

                val isAmbiguousHalfWorld = hasPrevious &&
                    abs(abs(wrappedX - previousWrappedX) - worldSize / 2.0) <= halfWorldTolerance
                if (hasPrevious && !isAmbiguousHalfWorld) {
                    affectedCount = markIntersectingTargets(
                        targetLefts, targetTops, tileSize, worldSize, affected, affectedCount,
                        minX = min(previousX - previousRadius, x - radius),
                        minY = min(previousY - previousRadius, y - radius),
                        maxX = max(previousX + previousRadius, x + radius),
                        maxY = max(previousY + previousRadius, y + radius),
                    )
                }
                hasPrevious = true
                previousX = x
                previousY = y
                previousRadius = radius
                previousWrappedX = wrappedX
            }
            for (index in 0 until targetCount) {
                if (affected[index]) selected.getValue(targetKeys[index]).add(segment)
            }
        }

        return selected
    }

    /** Marks every not-yet-affected target the box touches at some world copy; returns the new count. */
    private fun markIntersectingTargets(
        targetLefts: DoubleArray,
        targetTops: DoubleArray,
        tileSize: Int,
        worldSize: Double,
        affected: BooleanArray,
        affectedCount: Int,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): Int {
        var count = affectedCount
        val boundsCenter = (minX + maxX) / 2.0
        for (index in targetLefts.indices) {
            if (affected[index]) continue
            val tileLeft = targetLefts[index]
            val tileRight = tileLeft + tileSize
            val tileTop = targetTops[index]
            val tileBottom = tileTop + tileSize
            if (maxY < tileTop || minY > tileBottom) continue

            val tileCenter = (tileLeft + tileRight) / 2.0
            val nearestTurn = Math.round((boundsCenter - tileCenter) / worldSize)
            for (turnOffset in -1L..1L) {
                val shift = (nearestTurn + turnOffset) * worldSize
                if (maxX >= tileLeft + shift && minX <= tileRight + shift) {
                    affected[index] = true
                    count++
                    break
                }
            }
        }
        return count
    }
}
