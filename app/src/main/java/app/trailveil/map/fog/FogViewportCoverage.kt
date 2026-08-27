package app.trailveil.map.fog

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The camera and the four ground points visible at the edges of its viewport.
 *
 * The point names describe the order supplied by a map projection: near-left, far-left,
 * far-right, near-right.  Coverage is an axis-aligned Web Mercator rectangle, so the order is
 * retained for diagnostics but does not change the calculated tile set.
 */
data class FogViewportCoverageRequest(
    val center: GeoPoint,
    val floorZoom: Int,
    val nearLeft: GeoPoint,
    val farLeft: GeoPoint,
    val farRight: GeoPoint,
    val nearRight: GeoPoint,
) {
    init {
        require(floorZoom in SUPPORTED_ZOOM_RANGE) { "floorZoom must be in 0..22" }
        listOf(center, nearLeft, farLeft, farRight, nearRight).forEach { point ->
            require(point.latitude in -90.0..90.0) {
                "viewport latitudes must be in -90..90"
            }
        }
    }

    fun visibleCorners(): List<GeoPoint> =
        listOf(nearLeft, farLeft, farRight, nearRight)

    private companion object {
        val SUPPORTED_ZOOM_RANGE: IntRange = 0..22
    }
}

/**
 * A finite, deterministic set of canonical XYZ keys covering one complete viewport.
 *
 * [keys] is row-major (north-to-south, then west-to-east in the camera's unwrapped world copy).
 * The map provider must use the keys as canonical identities; the order is only useful for
 * deterministic greedy rendering and tests.
 */
class FogViewportCoveragePlan internal constructor(
    val request: FogViewportCoverageRequest,
    keys: List<FogTileKey>,
) {
    val keys: List<FogTileKey> = Collections.unmodifiableList(ArrayList(keys))
    val keySet: Set<FogTileKey> = Collections.unmodifiableSet(LinkedHashSet(keys))

    init {
        require(keys.isNotEmpty()) { "coverage plan must contain at least one tile" }
        require(this.keySet.size == keys.size) { "coverage plan keys must be unique" }
    }
}

/**
 * Computes the complete axis-aligned XYZ tile rectangle visible from a camera.
 *
 * Longitudes are unwrapped by the shortest delta from the camera centre before the bbox is built.
 * That keeps an antimeridian viewport contiguous while still allowing a camera whose longitude is
 * in another world copy.  The resulting x coordinates are canonicalized with [Math.floorMod] only
 * after the rectangle is known.  Y never wraps and is clipped to the valid Web Mercator tile
 * range.  Invalid, non-finite or over-budget input throws before any plan is returned.
 */
class FogViewportCoveragePlanner(
    private val paddingTiles: Int = DEFAULT_PADDING_TILES,
    private val maxTiles: Int = DEFAULT_MAX_TILES,
    private val renderVersion: Int = FogRenderVersions.CURRENT,
) {
    init {
        require(paddingTiles >= 0) { "paddingTiles must be non-negative" }
        require(maxTiles > 0) { "maxTiles must be positive" }
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
    }

    fun plan(request: FogViewportCoverageRequest): FogViewportCoveragePlan {
        val tileCount = 1L shl request.floorZoom
        val corners = request.visibleCorners()
        val rawLongitudeRange = longitudeRange(corners)
        val projectedX = corners.map { corner ->
            projectUnwrappedX(
                longitude = corner.longitude,
                centerLongitude = request.center.longitude,
                tileCount = tileCount,
            )
        }
        val projectedY = corners.map { corner ->
            projectY(corner.latitude, tileCount)
        }

        val projectedXMin = snapNearInteger(projectedX.minOrNull()!!)
        val projectedXMax = snapNearInteger(projectedX.maxOrNull()!!)
        val projectedYMin = snapNearInteger(projectedY.minOrNull()!!)
        val projectedYMax = snapNearInteger(projectedY.maxOrNull()!!)
        val xFloorMin = floorToLong(projectedXMin)
        val xFloorMax = inclusiveTileEnd(projectedXMin, projectedXMax)
        val yFloorMin = floorToLong(projectedYMin)
        val yFloorMax = inclusiveTileEnd(projectedYMin, projectedYMax)

        val xStartUnwrapped = addPadding(xFloorMin, -paddingTiles.toLong())
        val xEndUnwrapped = addPadding(xFloorMax, paddingTiles.toLong())
        val visibleXCount = inclusiveCount(xStartUnwrapped, xEndUnwrapped)
        // A pair such as -180/180 is the same canonical meridian, but it can also be the
        // projection's representation of a viewport wider than one world.  Treat an explicit
        // whole-world raw span conservatively as full coverage rather than guessing the shorter
        // interpretation and leaving an unfogged world copy outside the mask.
        val fullWorld = rawLongitudeRange >= WORLD_LONGITUDE_DEGREES || visibleXCount >= tileCount
        val xCount = if (fullWorld) tileCount else visibleXCount
        val xFirst = if (fullWorld) 0L else xStartUnwrapped

        val yStart = addPadding(yFloorMin, -paddingTiles.toLong()).coerceAtLeast(0L)
        val yEnd = addPadding(yFloorMax, paddingTiles.toLong()).coerceAtMost(tileCount - 1L)
        require(yStart <= yEnd) { "visible viewport has no valid Web Mercator rows" }
        val yCount = inclusiveCount(yStart, yEnd)
        val tileTotal = multiplyCount(xCount, yCount)
        require(tileTotal <= maxTiles.toLong()) {
            "visible viewport requires $tileTotal tiles, maxTiles is $maxTiles"
        }

        val keys = ArrayList<FogTileKey>(tileTotal.toInt())
        var y = yStart
        while (y <= yEnd) {
            var offset = 0L
            while (offset < xCount) {
                val unwrappedX = try {
                    Math.addExact(xFirst, offset)
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("viewport tile coordinate overflow")
                }
                val canonicalX = Math.floorMod(unwrappedX, tileCount)
                keys += FogTileKey(
                    zoom = request.floorZoom,
                    x = canonicalX.toInt(),
                    y = y.toInt(),
                    renderVersion = renderVersion,
                )
                offset += 1L
            }
            y += 1L
        }
        return FogViewportCoveragePlan(request, keys)
    }

    private fun projectUnwrappedX(
        longitude: Double,
        centerLongitude: Double,
        tileCount: Long,
    ): Double {
        val rawDelta = longitude - centerLongitude
        require(rawDelta.isFinite()) { "viewport longitude delta must be finite" }
        val delta = WebMercator.wrapLongitude(rawDelta)
        require(delta.isFinite()) { "viewport longitude delta must be finite" }
        val centerX = ((centerLongitude + 180.0) / 360.0) * tileCount.toDouble()
        require(centerX.isFinite()) { "viewport centre projects outside finite world coordinates" }
        val x = centerX + delta / 360.0 * tileCount.toDouble()
        require(x.isFinite()) { "viewport longitude projects outside finite world coordinates" }
        return x
    }

    private fun projectY(latitude: Double, tileCount: Long): Double {
        require(latitude in -90.0..90.0) { "viewport latitude must be in -90..90" }
        val normalized = WebMercator.normalizedY(latitude)
        require(normalized.isFinite()) { "viewport latitude does not project to finite Y" }
        val y = normalized * tileCount.toDouble()
        require(y.isFinite()) { "viewport latitude projects outside finite world coordinates" }
        return y
    }

    private fun longitudeRange(corners: List<GeoPoint>): Double {
        val minimum = corners.minOf(GeoPoint::longitude)
        val maximum = corners.maxOf(GeoPoint::longitude)
        val range = maximum - minimum
        require(range.isFinite()) { "viewport longitude range must be finite" }
        return range
    }

    private fun floorToLong(value: Double): Long {
        val floored = kotlin.math.floor(value)
        require(
            floored.isFinite() &&
                floored >= Long.MIN_VALUE.toDouble() &&
                floored <= Long.MAX_VALUE.toDouble(),
        ) { "viewport tile coordinate is outside the supported integer range" }
        return floored.toLong()
    }

    /**
     * Converts the exclusive maximum of a positive-width viewport to its last covered tile.
     * A maximum exactly on an XYZ boundary touches the next tile with zero visible area, so that
     * tile must not become a delivery-barrier requirement. A degenerate interval still names the
     * tile containing its single point.
     */
    private fun inclusiveTileEnd(minimum: Double, maximum: Double): Long =
        floorToLong(if (maximum > minimum) Math.nextDown(maximum) else maximum)

    /** Removes only floating-point round-trip noise around an exact XYZ boundary. */
    private fun snapNearInteger(value: Double): Double {
        val nearest = Math.rint(value)
        val tolerance = Math.ulp(value) * INTEGER_SNAP_ULPS
        return if (kotlin.math.abs(value - nearest) <= tolerance) nearest else value
    }

    private fun addPadding(value: Long, padding: Long): Long = try {
        Math.addExact(value, padding)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("viewport tile padding overflow")
    }

    private fun inclusiveCount(first: Long, last: Long): Long {
        require(first <= last) { "viewport tile interval is empty" }
        val distance = try {
            Math.subtractExact(last, first)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("viewport tile interval overflow")
        }
        return try {
            Math.addExact(distance, 1L)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("viewport tile interval overflow")
        }
    }

    private fun multiplyCount(first: Long, second: Long): Long = try {
        Math.multiplyExact(first, second)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("viewport tile count overflow")
    }

    companion object {
        // The visible-region corners already bound the complete settled viewport. Movement keeps
        // the separate opaque cover raised, so off-screen prefetch is optional rather than a safety
        // requirement and would multiply canonical render cost on portrait displays.
        const val DEFAULT_PADDING_TILES: Int = 0
        // Matches FogTileProviderAdapter's default encoded-entry budget.
        const val DEFAULT_MAX_TILES: Int = 256
        private const val WORLD_LONGITUDE_DEGREES: Double = 360.0
        private const val INTEGER_SNAP_ULPS: Double = 8.0
    }
}

/** One batch call into the canonical coordinator for an exact rectangular provider viewport. */
fun interface FogViewportBatchSubrenderer {
    suspend fun render(
        request: FogViewportRequest,
        keys: List<FogTileKey>,
    ): FogViewportTileRender
}

/**
 * Converts a visible-region plan into one Room/cache/render transaction.
 *
 * The returned map is published only after the coordinator echoes the exact request and key order.
 * A shifted, missing, reordered, failed or cancelled batch therefore cannot expose partial fog.
 */
class FogViewportBatchCoverageRenderer(
    private val subrenderer: FogViewportBatchSubrenderer,
    paddingTiles: Int = FogViewportCoveragePlanner.DEFAULT_PADDING_TILES,
    maxTiles: Int = FogViewportCoveragePlanner.DEFAULT_MAX_TILES,
    renderVersion: Int = FogRenderVersions.CURRENT,
) {
    private val planner = FogViewportCoveragePlanner(
        paddingTiles = paddingTiles,
        maxTiles = maxTiles,
        renderVersion = renderVersion,
    )

    suspend fun render(request: FogViewportCoverageRequest): Map<FogTileKey, FogPixelMask> {
        currentCoroutineContext().ensureActive()
        val plan = planner.plan(request)
        val tileRequest = FogViewportRequest(
            center = request.center,
            mapZoom = request.floorZoom.toDouble(),
        )
        val rendered = subrenderer.render(tileRequest, plan.keys)
        currentCoroutineContext().ensureActive()
        require(rendered.request == tileRequest) {
            "batch viewport render returned a response for a different request"
        }
        require(rendered.keys == plan.keys) {
            "batch viewport render returned shifted, missing, or reordered keys"
        }
        val masks = rendered.masksByKey()
        require(masks.keys == plan.keySet && masks.size == plan.keys.size) {
            "batch viewport masks do not exactly match the desired coverage"
        }
        currentCoroutineContext().ensureActive()
        return Collections.unmodifiableMap(LinkedHashMap(masks))
    }
}
