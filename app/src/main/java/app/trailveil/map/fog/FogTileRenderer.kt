package app.trailveil.map.fog

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

    /** Per-call scan: do not retain a summary of the backing bytes. PNG treats every nonzero as opaque. */
    internal fun hasNoTransparentPixels(): Boolean {
        for (index in 0 until Math.multiplyExact(width, height)) if (alpha[index] == 0.toByte()) return false
        return true
    }

    /** Caller supplies opaque RGBA rows with one leading PNG filter byte per row. */
    internal fun clearTransparentPngAlpha(destination: ByteArray) {
        val stride = width * 4 + 1
        var source = 0
        for (y in 0 until height) {
            var target = y * stride + 4
            for (x in 0 until width) {
                if (alpha[source++] == 0.toByte()) destination[target] = 0
                target += 4
            }
        }
    }

    /** PNG filter-none rows, one MSB-first palette index per pixel: zero clear, one opaque. */
    internal fun indexedPngRows(): ByteArray {
        val rowBytes = (width + 7) / 8
        val result = ByteArray((rowBytes + 1) * height)
        var source = 0
        for (y in 0 until height) {
            var output = y * (rowBytes + 1) + 1
            for (block in 0 until rowBytes) {
                val count = minOf(8, width - block * 8)
                var bits = 0
                repeat(count) { bits = (bits shl 1) or if (alpha[source++] == 0.toByte()) 0 else 1 }
                result[output++] = (bits shl (8 - count)).toByte()
            }
        }
        return result
    }

    /** Allows optional scans to retain the original access/exception order for malformed masks. */
    internal val hasCompleteRasterData: Boolean
        get() = alpha.size.toLong() >= width.toLong() * height

    /** Scans a caller-validated row interval, without retaining a summary of mutable input bytes. */
    internal fun nextNonZeroX(y: Int, fromX: Int, untilX: Int): Int {
        val row = y * width
        var x = fromX
        while (x < untilX && alpha[row + x] == 0.toByte()) x++
        return x
    }

    /** The mosaic copy reads exactly this rectangle; trailing bytes remain permitted. */
    internal fun requireCompleteRasterData() {
        if (alpha.size < Math.multiplyExact(width, height)) {
            throw IndexOutOfBoundsException("mask data does not cover its dimensions")
        }
    }

    /** Packs a bounded row-major range without exposing or copying the owned alpha array. */
    internal fun packArgbInto(destination: IntArray, fromIndex: Int, toIndex: Int, rgb: Int) {
        require(destination.size == alpha.size) { "ARGB destination must match the mask size" }
        require(fromIndex >= 0 && toIndex >= fromIndex && toIndex <= alpha.size) { "invalid alpha range" }
        val color = rgb and 0x00ffffff
        for (index in fromIndex until toIndex) {
            val value = alpha[index].toInt() and 0xff
            destination[index] = if (value == 0) 0 else (value shl 24) or color
        }
    }

    internal fun packArgbLookupInto(destination: IntArray, fromIndex: Int, toIndex: Int, lookup: IntArray) {
        require(destination.size == alpha.size) { "ARGB destination must match the mask size" }
        require(fromIndex >= 0 && toIndex >= fromIndex && toIndex <= alpha.size) { "invalid alpha range" }
        require(lookup.size == 256) { "ARGB lookup must contain every alpha value" }
        for (index in fromIndex until toIndex) destination[index] = lookup[alpha[index].toInt() and 0xff]
    }

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
    /**
     * Rasterises [segments] into one tile: a circle at every point and a capsule between
     * consecutive points, each drawn at every world copy that can touch the tile.
     *
     * The per-point loop keeps its projection in locals rather than objects: `V03-013` measured the
     * z8 raster of a 204,800-point fixture at 1.1 s on the API 36 AVD, a cost paid per point, and
     * the allocations were most of it. The projection chain, the ambiguity rule and the two draw
     * routines are the ones the reference implementation in `FogRasterEquivalenceTest` keeps
     * verbatim; that test holds the masks bit-identical.
     */
    fun render(key: FogTileKey, segments: List<TrackSegment>): FogPixelMask {
        val tileSize = style.tileSize
        val alpha = ByteArray(tileSize * tileSize) { style.fogAlpha.toByte() }
        // A zero-alpha fog tile is already fully transparent.  Apart from avoiding all geometry
        // work, this preserves the signed-byte representation of values such as 184: occupancy
        // below is always determined by the actual byte value, never by a signed comparison.
        if (style.fogAlpha == 0 || segments.isEmpty()) {
            return FogPixelMask(tileSize, tileSize, alpha)
        }
        val zoom = key.zoom
        val worldSize = tileSize.toDouble() * (1 shl zoom)
        val projectionFrame = FogRasterProjectionScope.current(zoom, style)
        val tileLeft = key.x.toDouble() * tileSize
        val tileTop = key.y.toDouble() * tileSize
        val tileCenterX = tileLeft + tileSize / 2.0
        val halfWorldTolerance = max(1e-9, Math.ulp(worldSize) * 4.0)
        val blocksPerAxis = (tileSize - 1) / TRANSPARENCY_BLOCK_SIZE + 1
        val blockRemaining = IntArray(blocksPerAxis * blocksPerAxis)
        for (blockY in 0 until blocksPerAxis) {
            val blockHeight = min(
                TRANSPARENCY_BLOCK_SIZE,
                tileSize - blockY * TRANSPARENCY_BLOCK_SIZE,
            )
            for (blockX in 0 until blocksPerAxis) {
                val blockWidth = min(
                    TRANSPARENCY_BLOCK_SIZE,
                    tileSize - blockX * TRANSPARENCY_BLOCK_SIZE,
                )
                blockRemaining[blockY * blocksPerAxis + blockX] = blockWidth * blockHeight
            }
        }
        var remainingBlocks = blockRemaining.size

        for (segment in segments) {
            val points = segment.points
            val projection = projectionFrame?.segment(segment)
            var hasPrevious = false
            var previousX = 0.0
            var previousY = 0.0
            var previousRadius = 0.0
            var previousWrappedX = 0.0
            for (index in points.indices) {
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

                remainingBlocks -= drawWrappedCircle(
                    alpha = alpha,
                    x = x,
                    y = y,
                    radius = radius,
                    tileLeft = tileLeft,
                    tileTop = tileTop,
                    tileCenterX = tileCenterX,
                    worldSize = worldSize,
                    blockRemaining = blockRemaining,
                    blocksPerAxis = blocksPerAxis,
                )
                if (remainingBlocks == 0) return FogPixelMask(tileSize, tileSize, alpha)
                // A 180° transition has two equally short paths. Reveal its endpoints but
                // require the upstream track builder to split or disambiguate the segment.
                val isAmbiguousHalfWorld = hasPrevious &&
                    abs(abs(wrappedX - previousWrappedX) - worldSize / 2.0) <= halfWorldTolerance
                if (hasPrevious && !isAmbiguousHalfWorld) {
                    remainingBlocks -= drawWrappedCapsule(
                        alpha = alpha,
                        startX = previousX,
                        startY = previousY,
                        startRadius = previousRadius,
                        endX = x,
                        endY = y,
                        endRadius = radius,
                        tileLeft = tileLeft,
                        tileTop = tileTop,
                        tileCenterX = tileCenterX,
                        worldSize = worldSize,
                        blockRemaining = blockRemaining,
                        blocksPerAxis = blocksPerAxis,
                    )
                    if (remainingBlocks == 0) return FogPixelMask(tileSize, tileSize, alpha)
                }
                hasPrevious = true
                previousX = x
                previousY = y
                previousRadius = radius
                previousWrappedX = wrappedX
            }
        }

        return FogPixelMask(tileSize, tileSize, alpha)
    }

    private fun drawWrappedCircle(
        alpha: ByteArray,
        x: Double,
        y: Double,
        radius: Double,
        tileLeft: Double,
        tileTop: Double,
        tileCenterX: Double,
        worldSize: Double,
        blockRemaining: IntArray,
        blocksPerAxis: Int,
    ): Int {
        val centerY = y - tileTop
        // No pixel centre lies within the radius of a circle more than a pixel outside the tile;
        // drawCircle would have found the same empty box, this just does not compute it.
        if (isOutsideTile(centerY - radius, centerY + radius)) return 0
        val nearestTurn = Math.round((tileCenterX - x) / worldSize)
        var emptiedBlocks = 0
        for (turnOffset in -1L..1L) {
            val centerX = x + (nearestTurn + turnOffset) * worldSize - tileLeft
            if (isOutsideTile(centerX - radius, centerX + radius)) continue
            emptiedBlocks += drawCircle(
                alpha = alpha,
                centerX = centerX,
                centerY = centerY,
                radius = radius,
                blockRemaining = blockRemaining,
                blocksPerAxis = blocksPerAxis,
            )
        }
        return emptiedBlocks
    }

    private fun drawWrappedCapsule(
        alpha: ByteArray,
        startX: Double,
        startY: Double,
        startRadius: Double,
        endX: Double,
        endY: Double,
        endRadius: Double,
        tileLeft: Double,
        tileTop: Double,
        tileCenterX: Double,
        worldSize: Double,
        blockRemaining: IntArray,
        blocksPerAxis: Int,
    ): Int {
        val localStartY = startY - tileTop
        val localEndY = endY - tileTop
        val radius = max(startRadius, endRadius)
        if (isOutsideTile(min(localStartY, localEndY) - radius, max(localStartY, localEndY) + radius)) {
            return 0
        }
        val midpointX = (startX + endX) / 2.0
        val nearestTurn = Math.round((tileCenterX - midpointX) / worldSize)
        var emptiedBlocks = 0
        for (turnOffset in -1L..1L) {
            val shift = (nearestTurn + turnOffset) * worldSize
            val localStartX = startX + shift - tileLeft
            val localEndX = endX + shift - tileLeft
            if (isOutsideTile(min(localStartX, localEndX) - radius, max(localStartX, localEndX) + radius)) continue
            emptiedBlocks += drawCapsule(
                alpha = alpha,
                startX = localStartX,
                startY = localStartY,
                startRadius = startRadius,
                endX = localEndX,
                endY = localEndY,
                endRadius = endRadius,
                blockRemaining = blockRemaining,
                blocksPerAxis = blocksPerAxis,
            )
        }
        return emptiedBlocks
    }

    /**
     * Whether a shape spanning [low]..[high] along one tile-local axis is more than a pixel away
     * from every pixel centre of the tile (centres lie in 0.5..tileSize-0.5), and so cannot clear
     * any pixel of it. A whole pixel of slack keeps this a strictly weaker test than the per-pixel
     * distance rule it precedes.
     */
    private fun isOutsideTile(low: Double, high: Double): Boolean =
        high + 1.0 < 0.0 || low - 1.0 > style.tileSize

    private fun drawCircle(
        alpha: ByteArray,
        centerX: Double,
        centerY: Double,
        radius: Double,
        blockRemaining: IntArray,
        blocksPerAxis: Int,
    ): Int {
        val minX = max(0, floor(centerX - radius - 0.5).toInt())
        val maxX = min(style.tileSize - 1, ceil(centerX + radius - 0.5).toInt())
        val minY = max(0, floor(centerY - radius - 0.5).toInt())
        val maxY = min(style.tileSize - 1, ceil(centerY + radius - 0.5).toInt())
        if (minX > maxX || minY > maxY) return 0

        val radiusSquared = radius * radius
        var emptiedBlocks = 0
        val firstBlockY = minY / TRANSPARENCY_BLOCK_SIZE
        val lastBlockY = maxY / TRANSPARENCY_BLOCK_SIZE
        val firstBlockX = minX / TRANSPARENCY_BLOCK_SIZE
        val lastBlockX = maxX / TRANSPARENCY_BLOCK_SIZE
        for (blockY in firstBlockY..lastBlockY) {
            val blockTop = blockY * TRANSPARENCY_BLOCK_SIZE
            val blockBottom = min(style.tileSize - 1, blockTop + TRANSPARENCY_BLOCK_SIZE - 1)
            val scanStartY = max(minY, blockTop)
            val scanEndY = min(maxY, blockBottom)
            for (blockX in firstBlockX..lastBlockX) {
                val blockIndex = blockY * blocksPerAxis + blockX
                if (blockRemaining[blockIndex] == 0) continue
                val blockLeft = blockX * TRANSPARENCY_BLOCK_SIZE
                val blockRight = min(style.tileSize - 1, blockLeft + TRANSPARENCY_BLOCK_SIZE - 1)
                val scanStartX = max(minX, blockLeft)
                val scanEndX = min(maxX, blockRight)
                for (y in scanStartY..scanEndY) {
                    if (blockRemaining[blockIndex] == 0) break
                    val dy = y + 0.5 - centerY
                    for (x in scanStartX..scanEndX) {
                        val pixelIndex = y * style.tileSize + x
                        // This renderer only clears pixels. Dense overlapping samples cannot clear an
                        // already-zero pixel further, so its distance calculation has no observable effect.
                        if (alpha[pixelIndex] == 0.toByte()) continue
                        val dx = x + 0.5 - centerX
                        if (dx * dx + dy * dy <= radiusSquared) {
                            alpha[pixelIndex] = 0
                            val blockPixelsLeft = blockRemaining[blockIndex] - 1
                            blockRemaining[blockIndex] = blockPixelsLeft
                            if (blockPixelsLeft == 0) {
                                emptiedBlocks++
                                break
                            }
                        }
                    }
                }
            }
        }
        return emptiedBlocks
    }

    private fun drawCapsule(
        alpha: ByteArray,
        startX: Double,
        startY: Double,
        startRadius: Double,
        endX: Double,
        endY: Double,
        endRadius: Double,
        blockRemaining: IntArray,
        blocksPerAxis: Int,
    ): Int {
        val radius = max(startRadius, endRadius)
        val minX = max(0, floor(min(startX, endX) - radius - 0.5).toInt())
        val maxX = min(style.tileSize - 1, ceil(max(startX, endX) + radius - 0.5).toInt())
        val minY = max(0, floor(min(startY, endY) - radius - 0.5).toInt())
        val maxY = min(style.tileSize - 1, ceil(max(startY, endY) + radius - 0.5).toInt())
        if (minX > maxX || minY > maxY) return 0

        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared == 0.0) {
            return drawCircle(
                alpha = alpha,
                centerX = startX,
                centerY = startY,
                radius = startRadius,
                blockRemaining = blockRemaining,
                blocksPerAxis = blocksPerAxis,
            )
        }

        var emptiedBlocks = 0
        val firstBlockY = minY / TRANSPARENCY_BLOCK_SIZE
        val lastBlockY = maxY / TRANSPARENCY_BLOCK_SIZE
        val firstBlockX = minX / TRANSPARENCY_BLOCK_SIZE
        val lastBlockX = maxX / TRANSPARENCY_BLOCK_SIZE
        for (blockY in firstBlockY..lastBlockY) {
            val blockTop = blockY * TRANSPARENCY_BLOCK_SIZE
            val blockBottom = min(style.tileSize - 1, blockTop + TRANSPARENCY_BLOCK_SIZE - 1)
            val scanStartY = max(minY, blockTop)
            val scanEndY = min(maxY, blockBottom)
            for (blockX in firstBlockX..lastBlockX) {
                val blockIndex = blockY * blocksPerAxis + blockX
                if (blockRemaining[blockIndex] == 0) continue
                val blockLeft = blockX * TRANSPARENCY_BLOCK_SIZE
                val blockRight = min(style.tileSize - 1, blockLeft + TRANSPARENCY_BLOCK_SIZE - 1)
                val scanStartX = max(minX, blockLeft)
                val scanEndX = min(maxX, blockRight)
                for (y in scanStartY..scanEndY) {
                    if (blockRemaining[blockIndex] == 0) break
                    val pixelY = y + 0.5
                    for (x in scanStartX..scanEndX) {
                        val pixelIndex = y * style.tileSize + x
                        if (alpha[pixelIndex] == 0.toByte()) continue
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
                            alpha[pixelIndex] = 0
                            val blockPixelsLeft = blockRemaining[blockIndex] - 1
                            blockRemaining[blockIndex] = blockPixelsLeft
                            if (blockPixelsLeft == 0) {
                                emptiedBlocks++
                                break
                            }
                        }
                    }
                }
            }
        }
        return emptiedBlocks
    }

    private companion object {
        const val TRANSPARENCY_BLOCK_SIZE = 16
    }

}
