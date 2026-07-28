package io.github.jay890829.trailveil.map.fog

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class FogRenderStyle(
    val tileSize: Int = 256,
    val fogAlpha: Int = 184,
    val revealRadiusMeters: Double = 25.0,
) {
    init {
        require(tileSize > 0) { "tileSize must be positive" }
        require(fogAlpha in 0..255) { "fogAlpha must be in 0..255" }
        require(revealRadiusMeters > 0.0 && revealRadiusMeters.isFinite()) {
            "revealRadiusMeters must be finite and positive"
        }
    }
}

class FogPixelMask internal constructor(
    val width: Int,
    val height: Int,
    private val alpha: ByteArray,
) {
    fun alphaAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "pixel is outside the mask" }
        return alpha[y * width + x].toInt() and 0xff
    }

    fun copyAlpha(): ByteArray = alpha.copyOf()

    override fun equals(other: Any?): Boolean =
        other is FogPixelMask &&
            width == other.width &&
            height == other.height &&
            alpha.contentEquals(other.alpha)

    override fun hashCode(): Int = 31 * (31 * width + height) + alpha.contentHashCode()
}

class FogTileRenderer(
    private val style: FogRenderStyle = FogRenderStyle(),
) {
    fun render(key: FogTileKey, segments: List<TrackSegment>): FogPixelMask {
        val alpha = ByteArray(style.tileSize * style.tileSize) { style.fogAlpha.toByte() }
        val worldSize = style.tileSize.toDouble() * (1 shl key.zoom)
        val tileLeft = key.x.toDouble() * style.tileSize
        val tileTop = key.y.toDouble() * style.tileSize
        val tileCenterX = tileLeft + style.tileSize / 2.0

        segments.forEach { segment ->
            var previous: ProjectedPoint? = null
            var previousWrappedX: Double? = null
            segment.points.forEach { point ->
                val worldPixel = WebMercator.worldPixel(point, key.zoom, style.tileSize)
                val unwrappedX = previous?.let {
                    WebMercator.unwrapWorldX(it.x, worldPixel.x, worldSize)
                } ?: worldPixel.x
                val projected = ProjectedPoint(
                    x = unwrappedX,
                    y = worldPixel.y,
                    radius = style.revealRadiusMeters /
                        WebMercator.metersPerPixel(point.latitude, key.zoom, style.tileSize),
                )

                drawWrappedCircle(
                    alpha = alpha,
                    point = projected,
                    tileLeft = tileLeft,
                    tileTop = tileTop,
                    tileCenterX = tileCenterX,
                    worldSize = worldSize,
                )
                // A 180° transition has two equally short paths. Reveal its endpoints but
                // require the upstream track builder to split or disambiguate the segment.
                val isAmbiguousHalfWorld = previousWrappedX?.let { priorWrappedX ->
                    val distanceFromHalfWorld =
                        abs(abs(worldPixel.x - priorWrappedX) - worldSize / 2.0)
                    distanceFromHalfWorld <= max(1e-9, Math.ulp(worldSize) * 4.0)
                } ?: false
                previous?.takeUnless { isAmbiguousHalfWorld }?.let { start ->
                    drawWrappedCapsule(
                        alpha = alpha,
                        start = start,
                        end = projected,
                        tileLeft = tileLeft,
                        tileTop = tileTop,
                        tileCenterX = tileCenterX,
                        worldSize = worldSize,
                    )
                }
                previous = projected
                previousWrappedX = worldPixel.x
            }
        }

        return FogPixelMask(style.tileSize, style.tileSize, alpha)
    }

    private fun drawWrappedCircle(
        alpha: ByteArray,
        point: ProjectedPoint,
        tileLeft: Double,
        tileTop: Double,
        tileCenterX: Double,
        worldSize: Double,
    ) {
        val nearestTurn = Math.round((tileCenterX - point.x) / worldSize)
        for (turnOffset in -1L..1L) {
            val shiftedX = point.x + (nearestTurn + turnOffset) * worldSize
            drawCircle(alpha, shiftedX - tileLeft, point.y - tileTop, point.radius)
        }
    }

    private fun drawWrappedCapsule(
        alpha: ByteArray,
        start: ProjectedPoint,
        end: ProjectedPoint,
        tileLeft: Double,
        tileTop: Double,
        tileCenterX: Double,
        worldSize: Double,
    ) {
        val midpointX = (start.x + end.x) / 2.0
        val nearestTurn = Math.round((tileCenterX - midpointX) / worldSize)
        for (turnOffset in -1L..1L) {
            val shift = (nearestTurn + turnOffset) * worldSize
            drawCapsule(
                alpha = alpha,
                startX = start.x + shift - tileLeft,
                startY = start.y - tileTop,
                startRadius = start.radius,
                endX = end.x + shift - tileLeft,
                endY = end.y - tileTop,
                endRadius = end.radius,
            )
        }
    }

    private fun drawCircle(
        alpha: ByteArray,
        centerX: Double,
        centerY: Double,
        radius: Double,
    ) {
        val minX = max(0, floor(centerX - radius - 0.5).toInt())
        val maxX = min(style.tileSize - 1, ceil(centerX + radius - 0.5).toInt())
        val minY = max(0, floor(centerY - radius - 0.5).toInt())
        val maxY = min(style.tileSize - 1, ceil(centerY + radius - 0.5).toInt())
        if (minX > maxX || minY > maxY) return

        val radiusSquared = radius * radius
        for (y in minY..maxY) {
            val dy = y + 0.5 - centerY
            for (x in minX..maxX) {
                val dx = x + 0.5 - centerX
                if (dx * dx + dy * dy <= radiusSquared) {
                    alpha[y * style.tileSize + x] = 0
                }
            }
        }
    }

    private fun drawCapsule(
        alpha: ByteArray,
        startX: Double,
        startY: Double,
        startRadius: Double,
        endX: Double,
        endY: Double,
        endRadius: Double,
    ) {
        val radius = max(startRadius, endRadius)
        val minX = max(0, floor(min(startX, endX) - radius - 0.5).toInt())
        val maxX = min(style.tileSize - 1, ceil(max(startX, endX) + radius - 0.5).toInt())
        val minY = max(0, floor(min(startY, endY) - radius - 0.5).toInt())
        val maxY = min(style.tileSize - 1, ceil(max(startY, endY) + radius - 0.5).toInt())
        if (minX > maxX || minY > maxY) return

        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared == 0.0) {
            drawCircle(alpha, startX, startY, startRadius)
            return
        }

        for (y in minY..maxY) {
            val pixelY = y + 0.5
            for (x in minX..maxX) {
                val pixelX = x + 0.5
                val fraction = (
                    ((pixelX - startX) * deltaX + (pixelY - startY) * deltaY) /
                        lengthSquared
                ).coerceIn(0.0, 1.0)
                val closestX = startX + fraction * deltaX
                val closestY = startY + fraction * deltaY
                val interpolatedRadius = startRadius + fraction * (endRadius - startRadius)
                val distanceX = pixelX - closestX
                val distanceY = pixelY - closestY
                if (
                    distanceX * distanceX + distanceY * distanceY <=
                    interpolatedRadius * interpolatedRadius
                ) {
                    alpha[y * style.tileSize + x] = 0
                }
            }
        }
    }

    private data class ProjectedPoint(
        val x: Double,
        val y: Double,
        val radius: Double,
    )
}
