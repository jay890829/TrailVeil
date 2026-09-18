package app.trailveil.map.fog

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V03-013`: the low-zoom raster's two CPU stages were rewritten allocation-free (the z8 render of
 * a 204,800-point fixture cost 0.85 s of spatial selection and 1.1 s of raster on the API 36 AVD).
 * The rewrite claims to be EXACT - the same pixels cleared, the same segments assigned - and this
 * test is where that claim is made to fail if it is false: the previous implementations are kept
 * here verbatim as references and both are run over the synthetic datasets, crafted geometry cases
 * and a spread of zooms, tile windows and styles, then compared bit for bit.
 */
class FogRasterEquivalenceTest {
    private val styles = listOf(
        FogRenderStyle(),
        FogRenderStyle(tileSize = 64, fogAlpha = 180, revealRadiusMeters = 100_000.0),
        FogRenderStyle(tileSize = 128, revealRadiusMeters = 5_000.0),
    )
    private val zooms = listOf(0, 1, 2, 3, 8, 12, 16, 22)

    @Test
    fun masksAreBitIdenticalToTheReferenceRenderer() {
        var tiles = 0
        cases().forEach { (label, segments) ->
            styles.forEach { style ->
                val reference = ReferenceFogTileRenderer(style)
                val renderer = FogTileRenderer(style)
                windows(segments).forEach { keys ->
                    keys.forEach { key ->
                        val expected = reference.render(key, segments)
                        val actual = renderer.render(key, segments)
                        assertTrue(
                            "mask differs for $label style=$style key=$key",
                            expected.copyAlpha().contentEquals(actual.copyAlpha()),
                        )
                        tiles++
                    }
                }
            }
        }
        // 3 styles x 8 zooms x up to 6 centres x 9 tiles per case: about 2,500 today. A bound
        // near that notices a trimmed sweep, not only an empty one.
        assertTrue("the sweep must actually render tiles: $tiles", tiles > 2_000)
    }

    @Test
    fun selectionsAreIdenticalToTheReferenceSelection() {
        var windows = 0
        cases().forEach { (label, segments) ->
            styles.forEach { style ->
                windows(segments).forEach { keys ->
                    val expected = ReferenceFogPocSpatialSelection.select(keys, segments, style)
                    val actual = FogPocSpatialSelection.select(keys, segments, style)
                    assertEquals("selection differs for $label style=$style keys=$keys", expected, actual)
                    // Also the arbitrary-subset form the coordinator hands over (the missing
                    // tiles of a window, not a rectangle) and a window with a duplicated key.
                    val subset = keys.filterIndexed { index, _ -> index % 2 == 0 }
                    assertEquals(
                        ReferenceFogPocSpatialSelection.select(subset, segments, style),
                        FogPocSpatialSelection.select(subset, segments, style),
                    )
                    val duplicated = keys + keys.first()
                    assertEquals(
                        ReferenceFogPocSpatialSelection.select(duplicated, segments, style),
                        FogPocSpatialSelection.select(duplicated, segments, style),
                    )
                    windows++
                }
            }
        }
        assertTrue("the sweep must actually select: $windows", windows > 300)
    }

    @Test
    fun theStressDatasetMatchesAtTheZoomsTheProbeMeasured() {
        val segments = SyntheticFogDatasets.stress100k()
        val style = FogRenderStyle()
        val reference = ReferenceFogTileRenderer(style)
        val renderer = FogTileRenderer(style)
        listOf(8, 12).forEach { zoom ->
            val keys = FogPocTileGrid.around(segments[7].points.first(), zoom, renderVersion = 1)
            assertEquals(
                ReferenceFogPocSpatialSelection.select(keys, segments, style),
                FogPocSpatialSelection.select(keys, segments, style),
            )
            val selected = FogPocSpatialSelection.select(keys, segments, style)
            keys.forEach { key ->
                val perTile = selected.getValue(key)
                assertTrue(
                    "stress zoom=$zoom key=$key",
                    reference.render(key, perTile).copyAlpha().contentEquals(renderer.render(key, perTile).copyAlpha()),
                )
            }
        }
    }

    private fun cases(): List<Pair<String, List<TrackSegment>>> = listOf(
        "typical10k" to SyntheticFogDatasets.typical10k(),
        "edgeCases" to SyntheticFogDatasets.edgeCases(),
        "crafted" to listOf(
            // Exact half-world transition in both directions.
            TrackSegment(0, listOf(GeoPoint(0.0, -90.0), GeoPoint(0.0, 90.0))),
            TrackSegment(1, listOf(GeoPoint(0.0, 90.0), GeoPoint(0.0, -90.0))),
            // Dateline crossings, short and long.
            TrackSegment(2, listOf(GeoPoint(20.0, 179.0), GeoPoint(20.0, -179.0), GeoPoint(21.0, 178.0))),
            TrackSegment(3, listOf(GeoPoint(-40.0, 170.0), GeoPoint(-40.0, -170.0))),
            // Poles, clamped latitude and a zero-length pair.
            TrackSegment(4, listOf(GeoPoint(89.0, 10.0), GeoPoint(WebMercator.MAX_LATITUDE, 10.0))),
            TrackSegment(5, listOf(GeoPoint(-89.0, -10.0))),
            TrackSegment(6, listOf(GeoPoint(10.0, 10.0), GeoPoint(10.0, 10.0), GeoPoint(10.0, 10.0))),
            // A long walk across many tiles at every zoom, and a tight cluster.
            TrackSegment(7, (0..200).map { step -> GeoPoint(-30.0 + step * 0.3, -100.0 + step * 0.7) }),
            TrackSegment(8, (0..50).map { step -> GeoPoint(45.0 + step * 1e-5, 8.0 - step * 2e-5) }),
            // Points exactly on tile edges at several zooms.
            TrackSegment(9, listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 45.0), GeoPoint(0.0, 90.0), GeoPoint(0.0, 180.0))),
            TrackSegment(10, listOf(GeoPoint(0.0, -180.0), GeoPoint(0.0, -135.0))),
        ),
    )

    /** Windows around a spread of the dataset's own points, at every zoom, plus a whole-world one. */
    private fun windows(segments: List<TrackSegment>): List<List<FogTileKey>> {
        val centres = buildList {
            add(GeoPoint(0.0, 0.0))
            add(GeoPoint(0.0, 179.9))
            add(GeoPoint(-70.0, -179.9))
            segments.forEachIndexed { index, segment ->
                if (index % 9 == 0) add(segment.points.first())
            }
        }.distinct().take(6)
        return zooms.flatMap { zoom ->
            centres.map { centre -> FogPocTileGrid.around(centre, zoom, renderVersion = 1) }
        } + listOf(FogViewportTileGrid.around(GeoPoint(0.0, 0.0), 1, 1, paddingTiles = 1)) +
            listOf(FogViewportTileGrid.around(GeoPoint(30.0, 120.0), 2, 1, paddingTiles = 2))
    }
}

/** Verbatim copy of `FogTileRenderer` before `V03-013`'s allocation-free rewrite. */
private class ReferenceFogTileRenderer(
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

/** Verbatim copy of `FogPocSpatialSelection` before `V03-013`'s allocation-free rewrite. */
private object ReferenceFogPocSpatialSelection {
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
