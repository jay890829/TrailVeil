package app.trailveil.harness

import app.trailveil.data.map.ViewportBounds
import app.trailveil.map.fog.FogNativeGeometry
import app.trailveil.map.fog.FogNativeGeometryEngine
import app.trailveil.map.fog.FogNativePolygon
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRenderVersions
import app.trailveil.map.fog.FogRevealUpdate
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileInvalidator
import app.trailveil.map.fog.FogTileKey
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.TrackSegment
import app.trailveil.map.fog.WebMercator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.TopologyException
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.union.UnaryUnionOp

/**
 * Track-derived geometry, shared by the two harness builds. The cache holds revealed AREA in
 * fixed z12 partitions, independent of the camera zoom. Native fog is viewport minus that area;
 * clipping the difference also handles a revealed corridor crossing the exterior (which cannot
 * be represented as a Google hole touching its parent shell).
 *
 * Metric coordinates are spherical Mercator, matching the canonical raster. Each buffer run uses
 * the smallest endpoint radius in that run; runs split before that deficit exceeds 0.1%. JTS round
 * buffers inscribe their arcs and input simplification is disabled. This is a conservative geometric
 * approximation of the canonical interpolated-radius capsule, not a claim of pixel identity.
 * Poles, wrapped extents and requests beyond the budgets explicitly use the existing raster path.
 * All calls are serialized by FogViewportCoordinator, off the UI thread.
 */
internal class DetailedTrackRegionGeometryEngine : FogNativeGeometryEngine {
    @Volatile override var description: String = "awaiting"
        private set
    // The runtime exists on baseline arms too. Defer JTS initialization until a worker actually
    // requests geometry, rather than paying that initialization on the application startup path.
    private val factory by lazy(LazyThreadSafetyMode.NONE) { GeometryFactory() }
    private val cache = LinkedHashMap<FogTileKey, Geometry>(16, 0.75f, true)
    private var cachedVertices = 0
    private var radius: Double? = null
    private val unionCache = TrackUnionCache()
    var cacheHits: Long = 0
        private set
    var cacheMisses: Long = 0
        private set
    var lastFallback: String? = null
        private set

    override suspend fun render(
        bounds: FogTileBounds,
        style: FogRenderStyle,
        read: suspend (ViewportBounds) -> List<TrackSegment>,
    ): FogNativeGeometry? = renderPrepared(bounds, style, read, null)

    override suspend fun renderBatched(
        bounds: FogTileBounds,
        style: FogRenderStyle,
        read: suspend (ViewportBounds) -> List<TrackSegment>,
        readBatch: suspend (List<ViewportBounds>) -> List<List<TrackSegment>>,
    ): FogNativeGeometry? = renderPrepared(bounds, style, read, readBatch)

    private suspend fun renderPrepared(
        bounds: FogTileBounds,
        style: FogRenderStyle,
        read: suspend (ViewportBounds) -> List<TrackSegment>,
        readBatch: (suspend (List<ViewportBounds>) -> List<List<TrackSegment>>)?,
    ): FogNativeGeometry? {
        lastFallback = null
        if (radius != style.revealRadiusMeters) {
            clear()
            radius = style.revealRadiusMeters
        }
        if (bounds.westLongitude < -180.0 || bounds.eastLongitude > 180.0 ||
            bounds.eastLongitude <= bounds.westLongitude ||
            bounds.southLatitude < -80.0 || bounds.northLatitude > 80.0
        ) return fallback("wrapped-or-polar")

        val west = (bounds.westLongitude + 180.0) / 360.0
        val east = (bounds.eastLongitude + 180.0) / 360.0
        val north = WebMercator.normalizedY(bounds.northLatitude)
        val south = WebMercator.normalizedY(bounds.southLatitude)
        val x0 = floor(west * PARTITIONS).toInt().coerceIn(0, PARTITIONS - 1)
        val x1 = floor(Math.nextDown(east * PARTITIONS)).toInt().coerceIn(x0, PARTITIONS - 1)
        val y0 = floor(north * PARTITIONS).toInt().coerceIn(0, PARTITIONS - 1)
        val y1 = floor(Math.nextDown(south * PARTITIONS)).toInt().coerceIn(y0, PARTITIONS - 1)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > MAX_REQUEST_PARTITIONS) {
            return fallback("partition-budget")
        }
        val started = System.nanoTime()
        val hitsBefore = cacheHits
        val missesBefore = cacheMisses
        // `V03-013`: prepareMs split by stage, so a slow prepare names its lever (Room read per
        // missed partition, JTS buffer build, the request union, the difference, ring conversion).
        var readNanos = 0L
        var buildNanos = 0L
        var batchReads = 0
        val buildWork = BuildWork()
        try {
            val revealed = ArrayList<Geometry>()
            val requestedPartitions = ArrayList<FogTileKey>()
            var requestVertices = 0
            val prefetched = HashMap<FogTileKey, List<TrackSegment>>()
            for (y in y0..y1) for (x in x0..x1) {
                currentCoroutineContext().ensureActive()
                val key = FogTileKey(PARTITION_ZOOM, x, y, FogRenderVersions.CURRENT)
                requestedPartitions += key
                val geometry = cache[key]?.also { cacheHits++ } ?: run {
                    cacheMisses++
                    val partition = partitionBounds(x, y)
                    val readStarted = System.nanoTime()
                    val segments = prefetched.remove(key) ?: run {
                        // Only consecutive misses in this row, at most four. containsKey does
                        // not touch the access-order LRU; actual cache/build/budget order stays
                        // the same. Stop at a warm entry instead of reading through cache holes.
                        val batchKeys = (x..minOf(x1, x + MAX_READ_BATCH_PARTITIONS - 1))
                            .map { column -> FogTileKey(PARTITION_ZOOM, column, y, FogRenderVersions.CURRENT) }
                            .takeWhile { candidate -> !cache.containsKey(candidate) && candidate !in prefetched }
                        if (readBatch != null && batchKeys.size > 1) {
                            val models = readBatch(batchKeys.map { partitionBounds(it.x, it.y) })
                            require(models.size == batchKeys.size) { "native batch must preserve partition order and count" }
                            currentCoroutineContext().ensureActive()
                            batchReads++
                            batchKeys.forEachIndexed { index, batchKey -> prefetched[batchKey] = models[index] }
                            checkNotNull(prefetched.remove(key))
                        } else read(partition)
                    }
                    readNanos += System.nanoTime() - readStarted
                    val buildStarted = System.nanoTime()
                    buildPartition(partition, segments, style.revealRadiusMeters, buildWork).also { built ->
                        buildNanos += System.nanoTime() - buildStarted
                        if (built.numPoints > MAX_PARTITION_VERTICES) throw GeometryBudgetExceeded()
                        // A rebuilt partition must not leave a union of its prior contents alive.
                        unionCache.invalidate(setOf(key))
                        cache[key] = built
                        cachedVertices += built.numPoints
                        trimCache()
                    }
                }
                requestVertices += geometry.numPoints
                if (requestVertices > MAX_REQUEST_VERTICES) throw GeometryBudgetExceeded()
                if (!geometry.isEmpty) revealed += geometry
            }
            currentCoroutineContext().ensureActive()
            val rectangle = rectangle(west * WORLD, north * WORLD, east * WORLD, south * WORLD)
            val unionStarted = System.nanoTime()
            val memo = unionCache[requestedPartitions]
            // Reuse a recent identical partition set; affected updates/rebuilds invalidate all
            // dependent entries. The aggregate budget remains 40k vertices. Clipping stays fresh.
            val union = memo ?: (if (revealed.isEmpty()) factory.createPolygon()
                else UnaryUnionOp.union(revealed)).also { computed ->
                unionCache.put(requestedPartitions, computed)
            }
            val unionNanos = System.nanoTime() - unionStarted
            val differenceStarted = System.nanoTime()
            val fog = rectangle.difference(union)
            val differenceNanos = System.nanoTime() - differenceStarted
            if (!fog.isValid || fog.numPoints > MAX_OUTPUT_VERTICES ||
                fog.numGeometries > MAX_OUTPUT_POLYGONS
            ) return fallback("output-budget-or-topology")
            val ringStarted = System.nanoTime()
            val polygons = (0 until if (fog.isEmpty) 0 else fog.numGeometries).map { index ->
                val polygon = fog.getGeometryN(index) as? Polygon ?: return fallback("non-polygon")
                FogNativePolygon(
                    shell = geographicRing(polygon.exteriorRing.coordinates),
                    holes = (0 until polygon.numInteriorRing).map { hole ->
                        geographicRing(polygon.getInteriorRingN(hole).coordinates)
                    },
                )
            }
            val ringNanos = System.nanoTime() - ringStarted
            return FogNativeGeometry(
                bounds, polygons,
                "trackNative[partitions=${(x1 - x0 + 1) * (y1 - y0 + 1)} " +
                    "hits=${cacheHits - hitsBefore} misses=${cacheMisses - missesBefore} " +
                    "vertices=${fog.numPoints} cachedVertices=$cachedVertices " +
                    "prepareMs=${(System.nanoTime() - started) / 1_000_000} " +
                    "rd=${readNanos / 1_000_000} bf=${buildNanos / 1_000_000} " +
                    "un=${unionNanos / 1_000_000} df=${differenceNanos / 1_000_000} " +
                    "rg=${ringNanos / 1_000_000} unionHit=${if (memo != null) 1 else 0} " +
                    "unionEntries=${unionCache.size} unionVertices=${unionCache.vertices} batchReads=$batchReads " +
                    "rejectedSegments=${buildWork.rejectedSegments} rejectedPoints=${buildWork.rejectedPoints} " +
                    "partitionBuildNs=$buildNanos bufferNs=${buildWork.bufferNs} clipNs=${buildWork.clipNs} " +
                    "partitionUnionNs=${buildWork.partitionUnionNs} fingerprintNs=${buildWork.fingerprintNs} " +
                    "bufferCalls=${buildWork.bufferCalls} repeatedBufferCalls=${buildWork.repeatedBufferCalls} " +
                    "repeatedBufferNs=${buildWork.repeatedBufferNs}]",
            ).also { description = it.diagnostics }
        } catch (_: GeometryBudgetExceeded) {
            return fallback("geometry-budget")
        } catch (_: TopologyException) {
            // Never repair an invalid polygon by growing or guessing its revealed region.
            return fallback("topology")
        }
    }

    override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) {
        val invalidator = FogTileInvalidator(PARTITION_ZOOM..PARTITION_ZOOM, style)
        // A union can outlive one of its partition-cache inputs after an LRU eviction.
        val candidates = cache.keys.toSet() + unionCache.partitionKeys
        val dirty = updates.flatMap { update ->
            invalidator.candidateKeysAmong(update, FogRenderVersions.CURRENT, candidates)
        }.toSet()
        unionCache.invalidate(dirty)
        dirty.forEach { key -> cache.remove(key)?.let { cachedVertices -= it.numPoints } }
    }

    override fun clear() {
        unionCache.clear()
        cache.clear()
        cachedVertices = 0
    }

    private suspend fun buildPartition(
        bounds: ViewportBounds,
        segments: List<TrackSegment>,
        revealRadiusMeters: Double,
        work: BuildWork,
    ): Geometry {
        if (segments.sumOf { it.points.size.toLong() } > MAX_INPUT_POINTS) throw GeometryBudgetExceeded()
        val left = (bounds.west + 180.0) / 360.0 * WORLD
        val right = (bounds.east + 180.0) / 360.0 * WORLD
        val top = WebMercator.normalizedY(bounds.north) * WORLD
        val bottom = WebMercator.normalizedY(bounds.south) * WORLD
        val clip = rectangle(left, top, right, bottom)
        val buffers = ArrayList<Geometry>()
        var bufferedVertices = 0
        val parameters = BufferParameters(QUADRANT_SEGMENTS).apply { simplifyFactor = 0.0 }
        suspend fun bufferRun(points: List<Coordinate>, minimumRadius: Double) {
            if (points.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val envelope = Envelope()
            points.forEach(envelope::expandToInclude)
            envelope.expandBy(minimumRadius)
            if (!envelope.intersects(clip.envelopeInternal)) return
            val line = if (points.size == 1) factory.createPoint(points.first())
                else factory.createLineString(points.toTypedArray())
            val fingerprintStarted = System.nanoTime()
            val fingerprint = work.fingerprint(points, minimumRadius)
            val repeated = !work.seen.add(fingerprint)
            work.fingerprintNs += System.nanoTime() - fingerprintStarted
            val bufferStarted = System.nanoTime()
            val buffered = BufferOp.bufferOp(line, minimumRadius, parameters)
            val bufferElapsed = System.nanoTime() - bufferStarted
            work.bufferNs += bufferElapsed
            work.bufferCalls++
            if (repeated) { work.repeatedBufferCalls++; work.repeatedBufferNs += bufferElapsed }
            val clipStarted = System.nanoTime()
            val area = clipTrackAreaToRectangle(buffered, clip)
            work.clipNs += System.nanoTime() - clipStarted
            bufferedVertices += area.numPoints
            if (bufferedVertices > MAX_REQUEST_VERTICES) throw GeometryBudgetExceeded()
            if (!area.isEmpty) buffers += area
        }

        for (segment in segments) {
            currentCoroutineContext().ensureActive()
            if (trackOutsidePartition(segment.points, revealRadiusMeters, MAX_RADIUS_RATIO, left, top, right, bottom)) {
                // Match the original nonempty bufferRun's post-projection cancellation fence.
                currentCoroutineContext().ensureActive()
                work.rejectedSegments++
                work.rejectedPoints += segment.points.size
                continue
            }
            var run = ArrayList<Coordinate>()
            var low = Double.POSITIVE_INFINITY
            var high = 0.0
            var previousX: Double? = null
            var previousRadius = 0.0
            for (point in segment.points) {
                val wrappedX = WebMercator.normalizedX(point.longitude) * WORLD
                val reference = previousX ?: (left + right) / 2.0
                val x = WebMercator.unwrapWorldX(reference, wrappedX, WORLD)
                val y = WebMercator.normalizedY(point.latitude) * WORLD
                val r = revealRadiusMeters / cos(Math.toRadians(WebMercator.clampLatitude(point.latitude)))
                val next = Coordinate(x, y)
                val ambiguous = previousX?.let { abs(abs(x - it) - WORLD / 2.0) < 1e-6 } ?: false
                if (ambiguous) {
                    bufferRun(run, low)
                    run = arrayListOf()
                    low = Double.POSITIVE_INFINITY
                    high = 0.0
                } else if (run.isNotEmpty() && max(high, r) / min(low, r) > MAX_RADIUS_RATIO) {
                    bufferRun(run, low)
                    val previous = run.last()
                    // Split the connector too: a long high-latitude edge can cross several scale
                    // bins even though no sampled point lies between its endpoints. Interpolate in
                    // the same Mercator space/radius as the canonical capsule.
                    val parts = ceil(abs(r - previousRadius) /
                        (min(previousRadius, r) * (MAX_RADIUS_RATIO - 1.0))).toInt().coerceAtLeast(1)
                    if (parts > MAX_CONNECTOR_PARTS) throw GeometryBudgetExceeded()
                    for (part in 0 until parts) {
                        val t0 = part.toDouble() / parts
                        val t1 = (part + 1.0) / parts
                        fun position(t: Double) = Coordinate(
                            previous.x + (next.x - previous.x) * t,
                            previous.y + (next.y - previous.y) * t,
                        )
                        val r0 = previousRadius + (r - previousRadius) * t0
                        val r1 = previousRadius + (r - previousRadius) * t1
                        bufferRun(listOf(position(t0), position(t1)), min(r0, r1))
                    }
                    run = arrayListOf()
                    low = Double.POSITIVE_INFINITY
                    high = 0.0
                }
                if (run.lastOrNull()?.equals2D(next) != true) run += next
                low = min(low, r)
                high = max(high, r)
                previousX = x
                previousRadius = r
            }
            bufferRun(run, low)
        }
        val unionStarted = System.nanoTime()
        return (if (buffers.isEmpty()) factory.createPolygon() else UnaryUnionOp.union(buffers)).also {
            work.partitionUnionNs += System.nanoTime() - unionStarted
        }
    }

    private fun trimCache() {
        while (cache.size > MAX_CACHE_PARTITIONS || cachedVertices > MAX_CACHE_VERTICES) {
            val iterator = cache.entries.iterator()
            val eldest = iterator.next()
            cachedVertices -= eldest.value.numPoints
            iterator.remove()
        }
    }

    private fun fallback(reason: String): FogNativeGeometry? {
        lastFallback = reason
        description = "raster-fallback:$reason"
        return null
    }

    private fun partitionBounds(x: Int, y: Int) = ViewportBounds(
        south = WebMercator.latitudeAtNormalizedY((y + 1.0) / PARTITIONS),
        north = WebMercator.latitudeAtNormalizedY(y.toDouble() / PARTITIONS),
        west = x.toDouble() / PARTITIONS * 360.0 - 180.0,
        east = (x + 1.0) / PARTITIONS * 360.0 - 180.0,
    )

    private fun rectangle(left: Double, top: Double, right: Double, bottom: Double): Geometry =
        factory.toGeometry(Envelope(left, right, top, bottom))

    private fun geographicRing(points: Array<Coordinate>): List<GeoPoint> = points.map { point ->
        GeoPoint(WebMercator.latitudeAtNormalizedY(point.y / WORLD), point.x / WORLD * 360.0 - 180.0)
    }

    private class GeometryBudgetExceeded : RuntimeException()
    private class BuildWork {
        var rejectedSegments = 0
        var rejectedPoints = 0L
        var bufferNs = 0L
        var clipNs = 0L
        var partitionUnionNs = 0L
        var fingerprintNs = 0L
        var bufferCalls = 0
        var repeatedBufferCalls = 0
        var repeatedBufferNs = 0L
        val seen = HashSet<String>()
        fun fingerprint(points: List<Coordinate>, radius: Double): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = java.nio.ByteBuffer.allocate(16)
            bytes.putLong(points.size.toLong()).putDouble(radius)
            digest.update(bytes.array())
            points.forEach { point ->
                bytes.clear(); bytes.putDouble(point.x).putDouble(point.y); digest.update(bytes.array())
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    companion object {
        const val PARTITION_ZOOM = 12
        private const val PARTITIONS = 1 shl PARTITION_ZOOM
        private const val WORLD = WebMercator.EARTH_CIRCUMFERENCE_METERS
        private const val QUADRANT_SEGMENTS = 32
        private const val MAX_RADIUS_RATIO = 1.001
        private const val MAX_CONNECTOR_PARTS = 128
        private const val MAX_REQUEST_PARTITIONS = 64
        private const val MAX_READ_BATCH_PARTITIONS = 4
        private const val MAX_CACHE_PARTITIONS = 64
        private const val MAX_CACHE_VERTICES = 200_000
        private const val MAX_INPUT_POINTS = 100_000
        private const val MAX_PARTITION_VERTICES = 40_000
        private const val MAX_REQUEST_VERTICES = 160_000
        private const val MAX_OUTPUT_VERTICES = 40_000
        private const val MAX_OUTPUT_POLYGONS = 512
    }
}
