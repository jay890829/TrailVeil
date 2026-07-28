package io.github.jay890829.trailveil.map.fog

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
        val worldSize = style.tileSize.toDouble() * (1 shl zoom)

        segments.forEach { segment ->
            val affected = mutableSetOf<FogTileKey>()
            var previous: SelectionPoint? = null
            var previousWrappedX: Double? = null
            segment.points.forEach { point ->
                val worldPixel = WebMercator.worldPixel(point, zoom, style.tileSize)
                val projected = SelectionPoint(
                    x = previous?.let {
                        WebMercator.unwrapWorldX(it.x, worldPixel.x, worldSize)
                    } ?: worldPixel.x,
                    y = worldPixel.y,
                    radius = style.revealRadiusMeters /
                        WebMercator.metersPerPixel(point.latitude, zoom, style.tileSize),
                )
                markIntersectingTargets(
                    targets = targetKeys,
                    bounds = SelectionBounds.around(projected),
                    tileSize = style.tileSize,
                    worldSize = worldSize,
                    affected = affected,
                )

                val isAmbiguousHalfWorld = previousWrappedX?.let { priorWrappedX ->
                    val distanceFromHalfWorld =
                        abs(abs(worldPixel.x - priorWrappedX) - worldSize / 2.0)
                    distanceFromHalfWorld <= max(1e-9, Math.ulp(worldSize) * 4.0)
                } ?: false
                previous?.takeUnless { isAmbiguousHalfWorld }?.let { start ->
                    markIntersectingTargets(
                        targets = targetKeys,
                        bounds = SelectionBounds.around(start, projected),
                        tileSize = style.tileSize,
                        worldSize = worldSize,
                        affected = affected,
                    )
                }
                previous = projected
                previousWrappedX = worldPixel.x
            }
            affected.forEach { key -> selected.getValue(key).add(segment) }
        }

        return selected
    }

    private fun markIntersectingTargets(
        targets: List<FogTileKey>,
        bounds: SelectionBounds,
        tileSize: Int,
        worldSize: Double,
        affected: MutableSet<FogTileKey>,
    ) {
        targets.forEach { key ->
            if (key in affected) return@forEach
            val tileLeft = key.x.toDouble() * tileSize
            val tileRight = tileLeft + tileSize
            val tileTop = key.y.toDouble() * tileSize
            val tileBottom = tileTop + tileSize
            if (bounds.maxY < tileTop || bounds.minY > tileBottom) return@forEach

            val tileCenter = (tileLeft + tileRight) / 2.0
            val boundsCenter = (bounds.minX + bounds.maxX) / 2.0
            val nearestTurn = Math.round((boundsCenter - tileCenter) / worldSize)
            for (turnOffset in -1L..1L) {
                val shift = (nearestTurn + turnOffset) * worldSize
                if (
                    bounds.maxX >= tileLeft + shift &&
                    bounds.minX <= tileRight + shift
                ) {
                    affected += key
                    return@forEach
                }
            }
        }
    }

    private data class SelectionPoint(
        val x: Double,
        val y: Double,
        val radius: Double,
    )

    private data class SelectionBounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    ) {
        companion object {
            fun around(point: SelectionPoint): SelectionBounds = SelectionBounds(
                minX = point.x - point.radius,
                minY = point.y - point.radius,
                maxX = point.x + point.radius,
                maxY = point.y + point.radius,
            )

            fun around(start: SelectionPoint, end: SelectionPoint): SelectionBounds =
                SelectionBounds(
                    minX = min(start.x - start.radius, end.x - end.radius),
                    minY = min(start.y - start.radius, end.y - end.radius),
                    maxX = max(start.x + start.radius, end.x + end.radius),
                    maxY = max(start.y + start.radius, end.y + end.radius),
                )
        }
    }
}
