package app.trailveil.map.fog

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlin.math.round

/** One expected opaque canonical fog pixel expressed at its geographic pixel centre. */
data class FogSnapshotVisualProbe(
    val key: FogTileKey,
    val latitude: Double,
    val longitude: Double,
    /** True when the canonical mask has an opaque 3 x 3 neighbourhood around this point. */
    val strongNeighbourhood: Boolean,
)

/** Immutable visual-install oracle input for one exact provider viewport. */
class FogSnapshotVisualProbePlan internal constructor(
    coverageKeys: Set<FogTileKey>,
    probesByKey: Map<FogTileKey, List<FogSnapshotVisualProbe>>,
) {
    val coverageKeys: Set<FogTileKey> =
        Collections.unmodifiableSet(LinkedHashSet(coverageKeys))
    val probesByKey: Map<FogTileKey, List<FogSnapshotVisualProbe>> =
        Collections.unmodifiableMap(
            LinkedHashMap(
                probesByKey.mapValues { (_, probes) ->
                    Collections.unmodifiableList(ArrayList(probes))
                },
            ),
        )

    init {
        require(this.coverageKeys.isNotEmpty()) { "visual probe coverage must not be empty" }
        require(this.probesByKey.keys.all { key -> key in this.coverageKeys }) {
            "visual probes must belong to the exact provider coverage"
        }
        require(this.probesByKey.values.all(List<FogSnapshotVisualProbe>::isNotEmpty)) {
            "visual probe groups must not be empty"
        }
    }
}

/**
 * Finds canonical opaque pixels that are actually inside the current visible-region polygon.
 *
 * A non-null Google map snapshot is not an installation fence. These probes let the Google-only
 * bridge require visible fog-colour pixels from every rendered tile that has visible unknown
 * coverage before it removes the independent full-screen cover. Fully explored visible tiles need
 * no opaque probe because exposing their basemap is the intended canonical result.
 */
class FogSnapshotVisualProbePlanner(
    private val blocksPerAxis: Int = DEFAULT_BLOCKS_PER_AXIS,
) {
    init {
        require(blocksPerAxis > 0) { "blocksPerAxis must be positive" }
    }

    fun plan(
        request: FogViewportCoverageRequest,
        masks: Map<FogTileKey, FogPixelMask>,
    ): FogSnapshotVisualProbePlan {
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

        val probesByKey = LinkedHashMap<FogTileKey, List<FogSnapshotVisualProbe>>()
        masks.forEach { (key, mask) ->
            require(mask.width > 0 && mask.height > 0) { "visual probe mask must be non-empty" }
            val nearestWorld = round((centerX - (key.x + 0.5)) / tileCount).toInt()
            val worldColumns = ((nearestWorld - 1)..(nearestWorld + 1)).map { world ->
                key.x + world * tileCount
            }
            val probes = findVisibleOpaqueProbes(
                key = key,
                mask = mask,
                polygon = polygon,
                worldColumns = worldColumns,
                tileCount = tileCount,
                fullWorld = fullWorld,
            )
            if (probes.isNotEmpty()) probesByKey[key] = probes
        }
        return FogSnapshotVisualProbePlan(masks.keys, probesByKey)
    }

    private fun findVisibleOpaqueProbes(
        key: FogTileKey,
        mask: FogPixelMask,
        polygon: List<ProjectedProbePoint>,
        worldColumns: List<Int>,
        tileCount: Int,
        fullWorld: Boolean,
    ): List<FogSnapshotVisualProbe> {
        val probes = ArrayList<FogSnapshotVisualProbe>(blocksPerAxis * blocksPerAxis)
        for (blockY in 0 until blocksPerAxis) {
            val yStart = blockY * mask.height / blocksPerAxis
            val yEnd = (blockY + 1) * mask.height / blocksPerAxis
            for (blockX in 0 until blocksPerAxis) {
                val xStart = blockX * mask.width / blocksPerAxis
                val xEnd = (blockX + 1) * mask.width / blocksPerAxis
                val strong = firstMatchingPixel(
                    mask, xStart, xEnd, yStart, yEnd,
                ) { x, y ->
                    hasOpaqueNeighbourhood(mask, x, y) &&
                        isVisible(key, mask, x, y, polygon, worldColumns, fullWorld)
                }
                val selected = strong ?: firstMatchingPixel(
                    mask, xStart, xEnd, yStart, yEnd,
                ) { x, y ->
                    mask.alphaAt(x, y) != 0 &&
                        isVisible(key, mask, x, y, polygon, worldColumns, fullWorld)
                }
                if (selected != null) {
                    probes += selected.toProbe(
                        key = key,
                        mask = mask,
                        tileCount = tileCount,
                        strong = strong != null,
                    )
                }
            }
        }
        return probes
    }

    private fun firstMatchingPixel(
        mask: FogPixelMask,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        predicate: (Int, Int) -> Boolean,
    ): ProbePixel? {
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                if (predicate(x, y)) return ProbePixel(x, y)
            }
        }
        return null
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
    ): FogSnapshotVisualProbe {
        val normalizedX = (key.x + (x + 0.5) / mask.width) / tileCount
        val normalizedY = (key.y + (y + 0.5) / mask.height) / tileCount
        return FogSnapshotVisualProbe(
            key = key,
            latitude = WebMercator.latitudeAtNormalizedY(normalizedY),
            longitude = normalizedX * 360.0 - 180.0,
            strongNeighbourhood = strong,
        )
    }

    private data class ProjectedProbePoint(val x: Double, val y: Double)
    private data class ProbePixel(val x: Int, val y: Int)

    companion object {
        const val DEFAULT_BLOCKS_PER_AXIS = 16
    }
}
