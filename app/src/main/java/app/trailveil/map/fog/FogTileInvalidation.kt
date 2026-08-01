package app.trailveil.map.fog

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One newly persisted point and, when continuous, its predecessor in the same segment. */
data class FogRevealUpdate(
    val current: GeoPoint,
    val previousInSegment: GeoPoint? = null,
)

/**
 * Finds the tiles whose rendered pixels change because of one persisted update.
 *
 * Candidate bounds are conservative, then the shared renderer compares before/after masks. This
 * keeps invalidation aligned with circle, capsule, dateline, and ambiguous-180-degree behavior.
 */
class FogTileInvalidator(
    zoomLevels: IntRange,
    private val style: FogRenderStyle = FogRenderStyle(),
) {
    private val zoomLevels: List<Int>
    private val renderer = FogTileRenderer(style)

    init {
        require(!zoomLevels.isEmpty()) { "zoomLevels must not be empty" }
        require(zoomLevels.first in 0..22 && zoomLevels.last in 0..22) {
            "zoomLevels must be in 0..22"
        }
        this.zoomLevels = zoomLevels.toList()
    }

    fun affectedKeys(update: FogRevealUpdate, renderVersion: Int): Set<FogTileKey> {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        val before = update.previousInSegment
            ?.let { previous -> listOf(TrackSegment(id = 0, points = listOf(previous))) }
            .orEmpty()
        val afterPoints = update.previousInSegment
            ?.let { previous -> listOf(previous, update.current) }
            ?: listOf(update.current)
        val after = listOf(TrackSegment(id = 0, points = afterPoints))

        return buildSet {
            zoomLevels.forEach { zoom ->
                val candidatePoints = if (isAmbiguousHalfWorld(update, zoom)) {
                    listOf(update.current)
                } else {
                    afterPoints
                }
                candidateKeys(candidatePoints, zoom, renderVersion).forEach { key ->
                    if (renderer.render(key, before) != renderer.render(key, after)) {
                        add(key)
                    }
                }
            }
        }
    }

    private fun isAmbiguousHalfWorld(update: FogRevealUpdate, zoom: Int): Boolean {
        val previous = update.previousInSegment ?: return false
        val worldSize = style.tileSize.toDouble() * (1 shl zoom)
        val previousX = WebMercator.worldPixel(previous, zoom, style.tileSize).x
        val currentX = WebMercator.worldPixel(update.current, zoom, style.tileSize).x
        val distanceFromHalfWorld = abs(abs(currentX - previousX) - worldSize / 2.0)
        return distanceFromHalfWorld <= max(1e-9, Math.ulp(worldSize) * 4.0)
    }

    private fun candidateKeys(
        points: List<GeoPoint>,
        zoom: Int,
        renderVersion: Int,
    ): Set<FogTileKey> {
        val tileCount = 1 shl zoom
        val worldSize = style.tileSize.toDouble() * tileCount
        var previousX: Double? = null
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        points.forEach { point ->
            val wrapped = WebMercator.worldPixel(point, zoom, style.tileSize)
            val x = previousX?.let { prior ->
                WebMercator.unwrapWorldX(prior, wrapped.x, worldSize)
            } ?: wrapped.x
            val radius = style.revealRadiusMeters /
                WebMercator.metersPerPixel(point.latitude, zoom, style.tileSize)
            minX = min(minX, x - radius)
            maxX = max(maxX, x + radius)
            minY = min(minY, wrapped.y - radius)
            maxY = max(maxY, wrapped.y + radius)
            previousX = x
        }

        val firstY = floor(minY / style.tileSize).toLong().coerceAtLeast(0L)
        val lastY = floor(maxY / style.tileSize).toLong().coerceAtMost(tileCount - 1L)
        if (firstY > lastY) return emptySet()

        val firstX = floor(minX / style.tileSize).toLong()
        val lastX = floor(maxX / style.tileSize).toLong()
        val rawXValues: LongRange = if (lastX - firstX + 1 >= tileCount) {
            0L..(tileCount - 1L)
        } else {
            firstX..lastX
        }

        return buildSet {
            for (rawX in rawXValues) {
                val x = Math.floorMod(rawX, tileCount.toLong()).toInt()
                for (rawY in firstY..lastY) {
                    add(
                        FogTileKey(
                            zoom = zoom,
                            x = x,
                            y = rawY.toInt(),
                            renderVersion = renderVersion,
                        ),
                    )
                }
            }
        }
    }
}
