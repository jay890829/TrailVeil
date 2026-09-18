package app.trailveil.map.fog

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused L1 coverage for the transparent-block short circuit in [FogTileRenderer].
 *
 * The oracle below is an independent copy of the pre-L1 pixel-centre renderer: it does not keep
 * occupancy state and visits every candidate pixel for every shape.  The candidate must therefore
 * produce the same bytes even when an earlier overlapping shape has made whole 16-pixel blocks
 * transparent, or when the whole tile is already transparent.  The cases intentionally include
 * signed alpha bytes, non-power-of-two tile sizes, dateline/half-world transitions, clamped high
 * latitudes, and pixels on tile boundaries.
 */
class FogRasterBlockSkipTest {
    @Test
    fun sparseGeometryMatchesThePreL1RendererAcrossProjectionBoundaries() {
        val styles = listOf(
            FogRenderStyle(tileSize = 1, fogAlpha = 1, revealRadiusMeters = 25.0),
            FogRenderStyle(tileSize = 15, fogAlpha = 127, revealRadiusMeters = 25.0),
            FogRenderStyle(tileSize = 16, fogAlpha = 128, revealRadiusMeters = 25.0),
            FogRenderStyle(tileSize = 17, fogAlpha = 184, revealRadiusMeters = 5_000.0),
            FogRenderStyle(tileSize = 31, fogAlpha = 255, revealRadiusMeters = 100_000.0),
        )
        val boundaryZooms = listOf(0, 1, 2, 4, 8, 16, 22)

        styles.forEach { style ->
            val segments = sparseSegments(style)
            val keys = boundaryZooms.map { zoom ->
                val point = when (zoom % 3) {
                    0 -> GeoPoint(0.0, 179.999)
                    1 -> GeoPoint(WebMercator.MAX_LATITUDE, -179.999)
                    else -> GeoPoint(-WebMercator.MAX_LATITUDE, 0.0)
                }
                val tile = WebMercator.tile(point, zoom)
                FogTileKey(zoom, tile.x, tile.y, renderVersion = 7)
            } + listOf(
                boundaryKey(style, x = 0.5, y = 0.5),
                boundaryKey(style, x = style.tileSize - 0.5, y = style.tileSize - 0.5),
            )

            val reference = PreBlockSkipFogTileRenderer(style)
            val renderer = FogTileRenderer(style)
            keys.forEach { key ->
                assertEquivalent(
                    label = "sparse style=$style key=$key",
                    expected = reference.render(key, segments),
                    actual = renderer.render(key, segments),
                )
            }
        }
    }

    @Test
    fun denseOverlappingPixelsRemainBitIdenticalAtBlockAndTileBoundaries() {
        val tileSizes = listOf(1, 2, 15, 16, 17, 31, 32, 33)
        val key = FogTileKey(zoom = 16, x = 32_768, y = 32_768, renderVersion = 9)

        tileSizes.forEach { tileSize ->
            val style = FogRenderStyle(tileSize = tileSize, fogAlpha = 184, revealRadiusMeters = 25.0)
            val segments = densePixelCentres(style, key)
            val reference = PreBlockSkipFogTileRenderer(style)
            val renderer = FogTileRenderer(style)
            val expected = reference.render(key, segments)
            val actual = renderer.render(key, segments)
            assertEquivalent(
                label = "dense tileSize=$tileSize",
                expected = expected,
                actual = actual,
            )
            assertTrue("dense tileSize=$tileSize should clear every pixel", actual.copyAlpha().all { it == 0.toByte() })
        }
    }

    @Test
    fun signedAlphaAndZeroAlphaTilesKeepTheirExactReferenceBytes() {
        val key = FogTileKey(zoom = 8, x = 128, y = 128, renderVersion = 11)
        val segments = listOf(
            TrackSegment(
                id = 1,
                points = listOf(
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 179.9),
                    GeoPoint(0.0, -179.9),
                ),
            ),
        )

        listOf(0, 1, 127, 128, 184, 255).forEach { fogAlpha ->
            val style = FogRenderStyle(tileSize = 17, fogAlpha = fogAlpha, revealRadiusMeters = 25.0)
            val reference = PreBlockSkipFogTileRenderer(style)
            val renderer = FogTileRenderer(style)
            assertEquivalent(
                label = "alpha=$fogAlpha with geometry",
                expected = reference.render(key, segments),
                actual = renderer.render(key, segments),
            )
            assertEquivalent(
                label = "alpha=$fogAlpha empty tile",
                expected = reference.render(key, emptyList()),
                actual = renderer.render(key, emptyList()),
            )
        }
    }

    private fun sparseSegments(style: FogRenderStyle): List<TrackSegment> = listOf(
        // Dateline crossing and exact half-world ambiguity: endpoints stay, the ambiguous capsule
        // is deliberately omitted by both renderers.
        TrackSegment(1, listOf(GeoPoint(20.0, 179.9), GeoPoint(20.0, -179.9))),
        TrackSegment(2, listOf(GeoPoint(0.0, -90.0), GeoPoint(0.0, 90.0))),
        // Web Mercator clamps these two points at its supported projection latitude.
        TrackSegment(3, listOf(GeoPoint(85.0, 10.0), GeoPoint(-85.0, -10.0))),
        TrackSegment(4, listOf(GeoPoint(10.0, 10.0), GeoPoint(10.0, 10.0))),
        TrackSegment(
            5,
            listOf(
                pixelPoint(boundaryKey(style, 0.5, 0.5), style.tileSize, 0.5, 0.5),
                pixelPoint(
                    boundaryKey(style, 0.5, 0.5),
                    style.tileSize,
                    style.tileSize - 0.5,
                    0.5,
                ),
                pixelPoint(
                    boundaryKey(style, 0.5, 0.5),
                    style.tileSize,
                    0.5,
                    style.tileSize - 0.5,
                ),
            ),
        ),
    )

    private fun densePixelCentres(style: FogRenderStyle, key: FogTileKey): List<TrackSegment> =
        (0 until style.tileSize).map { y ->
            TrackSegment(
                id = y,
                points = (0 until style.tileSize).map { x ->
                    pixelPoint(key, style.tileSize, x + 0.5, y + 0.5)
                },
            )
        }

    private fun boundaryKey(style: FogRenderStyle, x: Double, y: Double): FogTileKey {
        val zoom = 16
        val tileSize = style.tileSize
        val anchor = FogTileKey(zoom, 32_768, 32_768, renderVersion = 7)
        val point = pixelPoint(anchor, tileSize, x, y)
        val tile = WebMercator.tile(point, zoom)
        return FogTileKey(zoom, tile.x, tile.y, renderVersion = 7)
    }

    private fun pixelPoint(key: FogTileKey, tileSize: Int, x: Double, y: Double): GeoPoint =
        WebMercator.pointAtWorldPixel(
            WorldPixel(
                x = key.x * tileSize + x,
                y = key.y * tileSize + y,
            ),
            zoom = key.zoom,
            tileSize = tileSize,
        )

    private fun assertEquivalent(label: String, expected: FogPixelMask, actual: FogPixelMask) {
        assertTrue(
            "$label expected ${expected.width}x${expected.height} but got ${actual.width}x${actual.height}",
            expected.width == actual.width && expected.height == actual.height,
        )
        assertTrue(
            "$label changed mask bytes",
            expected.copyAlpha().contentEquals(actual.copyAlpha()),
        )
    }
}

/** A deliberately unoptimised copy of the renderer used before L1's transparent-block skip. */
private class PreBlockSkipFogTileRenderer(
    private val style: FogRenderStyle,
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
                val projected = ProjectedPoint(
                    x = previous?.let { WebMercator.unwrapWorldX(it.x, worldPixel.x, worldSize) }
                        ?: worldPixel.x,
                    y = worldPixel.y,
                    radius = style.revealRadiusMeters /
                        WebMercator.metersPerPixel(point.latitude, key.zoom, style.tileSize),
                )
                drawWrappedCircle(alpha, projected, tileLeft, tileTop, tileCenterX, worldSize)
                val isAmbiguousHalfWorld = previousWrappedX?.let { priorWrappedX ->
                    abs(abs(worldPixel.x - priorWrappedX) - worldSize / 2.0) <=
                        max(1e-9, Math.ulp(worldSize) * 4.0)
                } ?: false
                previous?.takeUnless { isAmbiguousHalfWorld }?.let { start ->
                    drawWrappedCapsule(alpha, start, projected, tileLeft, tileTop, tileCenterX, worldSize)
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
            drawCircle(alpha, point.x + (nearestTurn + turnOffset) * worldSize - tileLeft, point.y - tileTop, point.radius)
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
        val nearestTurn = Math.round((tileCenterX - (start.x + end.x) / 2.0) / worldSize)
        for (turnOffset in -1L..1L) {
            val shift = (nearestTurn + turnOffset) * worldSize
            drawCapsule(
                alpha,
                start.x + shift - tileLeft,
                start.y - tileTop,
                start.radius,
                end.x + shift - tileLeft,
                end.y - tileTop,
                end.radius,
            )
        }
    }

    private fun drawCircle(alpha: ByteArray, centerX: Double, centerY: Double, radius: Double) {
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
                if (dx * dx + dy * dy <= radiusSquared) alpha[y * style.tileSize + x] = 0
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

    private data class ProjectedPoint(val x: Double, val y: Double, val radius: Double)
}
