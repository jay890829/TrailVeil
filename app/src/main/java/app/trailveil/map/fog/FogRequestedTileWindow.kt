package app.trailveil.map.fog

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Immutable rectangular render windows derived from provider-observed XYZ requests. */
class FogRequestedTileWindowPlan internal constructor(
    windows: List<List<FogTileKey>>,
) {
    val windows: List<List<FogTileKey>> = Collections.unmodifiableList(
        windows.map { window -> Collections.unmodifiableList(ArrayList(window)) },
    )
    val keys: Set<FogTileKey> = Collections.unmodifiableSet(
        LinkedHashSet(windows.flatten()),
    )

    init {
        require(this.windows.isNotEmpty()) { "requested tile windows must not be empty" }
        require(this.windows.all(List<FogTileKey>::isNotEmpty)) {
            "requested tile windows must not contain an empty window"
        }
        require(this.keys.size == this.windows.sumOf(List<FogTileKey>::size)) {
            "requested tile windows must not overlap"
        }
    }
}

/**
 * Expands an arbitrary, bounded set of actual provider requests into one minimal cyclic-X,
 * row-major rectangle per zoom. The renderer can therefore reuse its exact rectangular batch seam
 * without predicting which LOD zooms the SDK selected for a tilted camera.
 */
class FogRequestedTileWindowPlanner(
    private val maxTiles: Int = DEFAULT_MAX_TILES,
) {
    init {
        require(maxTiles > 0) { "maxTiles must be positive" }
    }

    fun plan(requestedKeys: Set<FogTileKey>): FogRequestedTileWindowPlan {
        require(requestedKeys.isNotEmpty()) { "actual provider request set must not be empty" }
        require(requestedKeys.size <= maxTiles) {
            "actual provider request set exceeds $maxTiles tiles"
        }
        val renderVersions = requestedKeys.map(FogTileKey::renderVersion).toSet()
        require(renderVersions.size == 1) { "actual provider requests must share render version" }

        var plannedCount = 0L
        val windows = requestedKeys
            .groupBy(FogTileKey::zoom)
            .toSortedMap()
            .map { (zoom, requestedAtZoom) ->
                require(zoom in 0..22) { "actual provider request zoom must be in 0..22" }
                val tileCount = 1 shl zoom
                require(requestedAtZoom.all { key ->
                    key.x in 0 until tileCount && key.y in 0 until tileCount
                }) { "actual provider request coordinates must be canonical" }

                val distinctX = requestedAtZoom.map(FogTileKey::x).distinct().sorted()
                val largestGap = distinctX.indices
                    .map { index ->
                        val current = distinctX[index]
                        val next = distinctX[(index + 1) % distinctX.size]
                        val distance = Math.floorMod(next - current, tileCount)
                            .let { value -> if (value == 0) tileCount else value }
                        CyclicGap(current = current, next = next, distance = distance)
                    }
                    .maxWith(compareBy<CyclicGap> { it.distance }.thenBy { it.current })
                val columnCount = tileCount - largestGap.distance + 1
                val firstY = requestedAtZoom.minOf(FogTileKey::y)
                val lastY = requestedAtZoom.maxOf(FogTileKey::y)
                val rowCount = lastY - firstY + 1
                val windowCount = Math.multiplyExact(columnCount.toLong(), rowCount.toLong())
                plannedCount = Math.addExact(plannedCount, windowCount)
                require(plannedCount <= maxTiles.toLong()) {
                    "actual provider request windows exceed $maxTiles tiles"
                }

                buildList(windowCount.toInt()) {
                    for (y in firstY..lastY) {
                        repeat(columnCount) { offset ->
                            add(
                                FogTileKey(
                                    zoom = zoom,
                                    x = Math.floorMod(largestGap.next + offset, tileCount),
                                    y = y,
                                    renderVersion = renderVersions.single(),
                                ),
                            )
                        }
                    }
                }.also { window ->
                    require(window.containsAll(requestedAtZoom)) {
                        "planned window omitted an actual provider request"
                    }
                    FogViewportTileGrid.queryBounds(window, marginMeters = 0.0)
                }
            }
        return FogRequestedTileWindowPlan(windows)
    }

    private data class CyclicGap(
        val current: Int,
        val next: Int,
        val distance: Int,
    )

    companion object {
        const val DEFAULT_MAX_TILES: Int = 256
    }
}

/** Renders every planned LOD window and rejects shifted, partial or duplicate responses. */
class FogRequestedTileWindowRenderer(
    private val subrenderer: FogViewportBatchSubrenderer,
    maxTiles: Int = FogRequestedTileWindowPlanner.DEFAULT_MAX_TILES,
) {
    private val planner = FogRequestedTileWindowPlanner(maxTiles)

    suspend fun render(
        center: GeoPoint,
        requestedKeys: Set<FogTileKey>,
    ): Map<FogTileKey, FogPixelMask> {
        currentCoroutineContext().ensureActive()
        val plan = planner.plan(requestedKeys)
        val rendered = LinkedHashMap<FogTileKey, FogPixelMask>()
        plan.windows.forEach { window ->
            currentCoroutineContext().ensureActive()
            val request = FogViewportRequest(center = center, mapZoom = window.first().zoom.toDouble())
            val response = subrenderer.render(request, window)
            require(response.request == request) {
                "actual-request render returned a response for a different request"
            }
            require(response.keys == window) {
                "actual-request render returned shifted, missing, or reordered keys"
            }
            response.masksByKey().forEach { (key, mask) ->
                require(rendered.put(key, mask) == null) {
                    "actual-request render windows returned a duplicate key"
                }
            }
        }
        require(rendered.keys == plan.keys) {
            "actual-request render masks do not exactly match the planned windows"
        }
        currentCoroutineContext().ensureActive()
        return Collections.unmodifiableMap(rendered)
    }
}
