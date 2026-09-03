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

    /**
     * The tiles whose rendered pixels change because of one persisted update.
     *
     * [worthTesting] is asked about each conservative candidate before the exact comparison is paid
     * for, and a rejected candidate is absent from the result. Comparing masks costs two full tile
     * renders, and this scan covers every configured zoom level, so a caller that already knows an
     * answer would be discarded can use this to avoid producing it. Rejecting a candidate whose
     * answer is used would under-report invalidation and leave a stale cached tile. The production
     * merge no longer calls this at all: it takes candidates from [candidateKeysAmong] over the
     * keys the caches and the active viewport actually hold, so nothing here is materialised on
     * the hot path.
     */
    fun affectedKeys(
        update: FogRevealUpdate,
        renderVersion: Int,
        worthTesting: (FogTileKey) -> Boolean = { true },
    ): Set<FogTileKey> {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        val before = update.previousInSegment
            ?.let { previous -> listOf(TrackSegment(id = 0, points = listOf(previous))) }
            .orEmpty()
        val afterPoints = update.previousInSegment
            ?.let { previous -> listOf(previous, update.current) }
            ?: listOf(update.current)
        val after = listOf(TrackSegment(id = 0, points = afterPoints))

        return candidateKeys(update, renderVersion).filterTo(linkedSetOf()) { key ->
            worthTesting(key) && renderer.render(key, before) != renderer.render(key, after)
        }
    }

    /**
     * Conservatively bounds every tile that may change without rendering a mask.
     *
     * Callers may safely invalidate every returned key. [affectedKeys] is narrower, but pays for
     * two complete mask renders per candidate and should only be used when that exactness is worth
     * its cost.
     */
    fun candidateKeys(update: FogRevealUpdate, renderVersion: Int): Set<FogTileKey> {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        val afterPoints = update.previousInSegment
            ?.let { previous -> listOf(previous, update.current) }
            ?: listOf(update.current)
        return buildSet {
            zoomLevels.forEach { zoom ->
                val candidatePoints = if (isAmbiguousHalfWorld(update, zoom)) {
                    listOf(update.current)
                } else {
                    afterPoints
                }
                candidateBounds(candidatePoints, zoom)?.addKeysTo(this, renderVersion)
            }
        }
    }

    /** Returns only candidates from an already bounded collection, without expanding the region. */
    fun candidateKeysAmong(
        update: FogRevealUpdate,
        renderVersion: Int,
        keys: Collection<FogTileKey>,
    ): Set<FogTileKey> {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        // No distinct(): the result is built into a set, so a duplicate input key collapses there.
        val eligible = keys.asSequence()
            .filter { key -> key.renderVersion == renderVersion && key.zoom in zoomLevels }
            .groupBy(FogTileKey::zoom)
        val afterPoints = update.previousInSegment
            ?.let { previous -> listOf(previous, update.current) }
            ?: listOf(update.current)
        return buildSet {
            eligible.forEach { (zoom, zoomKeys) ->
                val candidatePoints = if (isAmbiguousHalfWorld(update, zoom)) {
                    listOf(update.current)
                } else {
                    afterPoints
                }
                val bounds = candidateBounds(candidatePoints, zoom) ?: return@forEach
                zoomKeys.filterTo(this) { key -> bounds.contains(key) }
            }
        }
    }

    /** Counts the conservative region without allocating one key per tile. */
    fun candidateKeyCount(update: FogRevealUpdate): Long {
        val afterPoints = update.previousInSegment
            ?.let { previous -> listOf(previous, update.current) }
            ?: listOf(update.current)
        return zoomLevels.fold(0L) { total, zoom ->
            val candidatePoints = if (isAmbiguousHalfWorld(update, zoom)) {
                listOf(update.current)
            } else {
                afterPoints
            }
            Math.addExact(total, candidateBounds(candidatePoints, zoom)?.keyCount ?: 0L)
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

    private fun candidateBounds(
        points: List<GeoPoint>,
        zoom: Int,
    ): CandidateTileBounds? {
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
        if (firstY > lastY) return null

        val firstX = floor(minX / style.tileSize).toLong()
        val lastX = floor(maxX / style.tileSize).toLong()
        return CandidateTileBounds(zoom, tileCount, firstX, lastX, firstY, lastY)
    }

    private data class CandidateTileBounds(
        val zoom: Int,
        val tileCount: Int,
        val firstX: Long,
        val lastX: Long,
        val firstY: Long,
        val lastY: Long,
    ) {
        val xCount: Long = minOf(lastX - firstX + 1L, tileCount.toLong())
        val keyCount: Long = Math.multiplyExact(xCount, lastY - firstY + 1L)

        fun contains(key: FogTileKey): Boolean {
            if (key.zoom != zoom || key.y.toLong() !in firstY..lastY) return false
            if (xCount == tileCount.toLong()) return true
            val firstWrapped = Math.floorMod(firstX, tileCount.toLong()).toInt()
            val wrappedOffset = Math.floorMod(key.x - firstWrapped, tileCount)
            return wrappedOffset.toLong() < xCount
        }

        fun addKeysTo(destination: MutableSet<FogTileKey>, renderVersion: Int) {
            if (xCount == tileCount.toLong()) {
                repeat(tileCount) { x -> addColumnTo(destination, x, renderVersion) }
                return
            }
            repeat(xCount.toInt()) { offset ->
                val x = Math.floorMod(firstX + offset, tileCount.toLong()).toInt()
                addColumnTo(destination, x, renderVersion)
            }
        }

        private fun addColumnTo(
            destination: MutableSet<FogTileKey>,
            x: Int,
            renderVersion: Int,
        ) {
            for (rawY in firstY..lastY) {
                destination += FogTileKey(zoom, x, rawY.toInt(), renderVersion)
            }
        }
    }
}
