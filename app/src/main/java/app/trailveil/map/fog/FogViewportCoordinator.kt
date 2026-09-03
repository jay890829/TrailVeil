package app.trailveil.map.fog

import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.PersistedTrackPointChangeFeed
import app.trailveil.data.db.TrackPointCells
import app.trailveil.data.map.ViewportTrackDataSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FogViewportRequest(
    val center: GeoPoint,
    val mapZoom: Double,
) {
    init {
        require(mapZoom.isFinite()) { "mapZoom must be finite" }
    }
}

data class FogViewportRender(
    val request: FogViewportRequest,
    val keys: List<FogTileKey>,
    /** What was actually read from canonical storage, or null when nothing had to be. */
    val queryBounds: ViewportBounds?,
    val mosaic: FogTileMosaic,
)

/** One complete rectangular tile batch before provider-specific encoding or mosaic composition. */
data class FogViewportTileRender(
    val request: FogViewportRequest,
    val keys: List<FogTileKey>,
    val queryBounds: ViewportBounds?,
    val tiles: List<FogMosaicTile>,
) {
    init {
        require(keys.isNotEmpty()) { "viewport tile render must not be empty" }
        require(keys.toSet().size == keys.size) {
            "viewport tile render keys must be unique"
        }
        require(tiles.map(FogMosaicTile::key) == keys) {
            "viewport tile masks must exactly match the requested key order"
        }
    }

    fun masksByKey(): Map<FogTileKey, FogPixelMask> =
        tiles.associate { tile -> tile.key to tile.mask }
}

class FogRuntime(
    val viewportCoordinator: FogViewportCoordinator,
    val pointChanges: PersistedTrackPointChangeFeed,
) {
    internal val changeSynchronizer = FogChangeSynchronizer(
        pointChanges = pointChanges,
        clearDerivedCache = { viewportCoordinator.clearDerivedCache() },
        mergePersistedReveals = { updates ->
            viewportCoordinator.mergePersistedReveals(updates)
            Unit
        },
    )
}

/**
 * Serializes canonical Room reads, derived-cache access, and persisted reveal merges.
 *
 * The query includes the maximum accepted continuous-segment distance plus the reveal radius, so
 * clipping a viewport read does not drop the predecessor needed to render a capsule at its edge.
 */
class FogViewportCoordinator(
    private val trackDataSource: ViewportTrackDataSource,
    private val pipeline: FogTilePipeline,
    // Exposed so the map surface can paint out-of-mosaic map with the renderer's own fog.
    val style: FogRenderStyle = FogRenderStyle(),
    private val renderVersion: Int = FogRenderVersions.CURRENT,
    private val queryMarginMeters: Double = DEFAULT_QUERY_MARGIN_METERS,
) {
    private val mutex = Mutex()
    private val placeholderRenderer = FogTileRenderer(style)
    private val invalidator = FogTileInvalidator(0..22, style)

    /**
     * The tiles the surfaces are showing right now, as far as the coordinator can know it: the
     * last successfully rendered viewport. One `render` publishes its 3x3 mosaic. The provider
     * batch seam renders one window per LOD for the same camera, all around one centre, so a
     * window rendered at the centre already published EXTENDS the set instead of replacing it;
     * otherwise every LOD but the last would be read as off-screen and invalidated by the next
     * reveal merge (found by the 2026-09-03 review of this change). A window at a new centre
     * replaces the set. The set is capped at [MAX_ACTIVE_VIEWPORT_KEYS], newest keys kept, so a
     * camera that zooms in place cannot grow it without bound.
     */
    private var activeViewportCenter: GeoPoint? = null
    private var activeViewportKeys: Set<FogTileKey> = emptySet()

    /**
     * Whether the process-scoped lock is held right now. A device test that finds a map with a
     * runtime, a built binding and no generation cannot otherwise tell a surface waiting on this
     * lock from one that never asked; a suspended holder shows in no thread dump.
     */
    internal val isLockedForTesting: Boolean get() = mutex.isLocked

    init {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        require(queryMarginMeters.isFinite() && queryMarginMeters >= style.revealRadiusMeters) {
            "queryMarginMeters must be finite and include the reveal radius"
        }
    }

    suspend fun render(request: FogViewportRequest): FogViewportRender = mutex.withLock {
        val keys = FogViewportTileGrid.around(
            center = request.center,
            zoom = renderZoom(request.mapZoom),
            renderVersion = renderVersion,
        )
        val rendered = renderTilesLocked(request, keys)
        FogViewportRender(
            request = request,
            keys = rendered.keys,
            queryBounds = rendered.queryBounds,
            mosaic = FogPocMosaic.compose(rendered.tiles).anchoredNear(request.center.longitude),
        )
    }

    /**
     * Renders one complete provider viewport with a single bounded Room selection.
     *
     * [keys] must be one row-major rectangular XYZ window at this request's render zoom. This is
     * the batch seam used by providers whose visible screen needs more than the legacy 3x3 mosaic;
     * it deliberately reuses the same mutex, caches, spatial selection and canonical renderer.
     */
    suspend fun renderTiles(
        request: FogViewportRequest,
        keys: List<FogTileKey>,
    ): FogViewportTileRender = mutex.withLock {
        require(keys.size <= MAX_PROVIDER_VIEWPORT_TILES) {
            "provider viewport exceeds $MAX_PROVIDER_VIEWPORT_TILES tiles"
        }
        require(keys.toSet().size == keys.size) {
            "provider viewport keys must be unique"
        }
        require(keys.all { key ->
            key.zoom == renderZoom(request.mapZoom) && key.renderVersion == renderVersion
        }) {
            "provider viewport keys must match request zoom and render version"
        }
        // Validates non-empty, shared identity and a complete row-major rectangle even on a
        // completely warm cache where no query bounds would otherwise be constructed.
        FogViewportTileGrid.queryBounds(keys, marginMeters = 0.0)
        renderTilesLocked(request, keys)
    }

    private suspend fun renderTilesLocked(
        request: FogViewportRequest,
        keys: List<FogTileKey>,
    ): FogViewportTileRender {
        // Ask the caches what is actually missing before reading anything. A tile window at low
        // zoom is the whole world, so reading for tiles that were already cached made every
        // settle out there a full-table read - invisible against a test database of forty points,
        // and the reason zooming all the way out on a real one is a problem. Masks taken here are
        // held rather than looked up again, so nothing can be evicted between the two decisions.
        val cachedMasks = keys.associateWith { key -> pipeline.loadCached(key)?.mask }
        val missing = keys.filter { key -> cachedMasks[key] == null }
        val queryBounds = if (missing.isEmpty()) {
            null
        } else {
            // The smallest complete rectangle covering the misses, not the whole window: one
            // uncached tile beside eight cached ones should cost one tile's worth of reading.
            FogViewportTileGrid.queryBounds(
                keys = FogViewportTileGrid.enclosingSubWindow(keys, missing.toSet()),
                marginMeters = queryMarginMeters,
            )
        }
        val selected = if (queryBounds == null) {
            emptyMap()
        } else {
            FogPocSpatialSelection.select(
                missing,
                // P4-037. At render zoom 0-1 the tile window IS the world, so this read has no
                // bound to narrow and visits every point to draw a few sub-pixel dots. The decision
                // is made here because it is a fact about the mask raster, which the data source
                // deliberately knows nothing about.
                trackDataSource.read(
                    bounds = queryBounds,
                    coarse = TrackPointCells.coarseReadIsSubPixel(renderZoom(request.mapZoom)),
                ).toFogTrackSegments(),
                style,
            )
        }
        val tiles = keys.map { key ->
            FogMosaicTile(
                key = key,
                mask = cachedMasks[key]
                    ?: pipeline.load(key, selected[key].orEmpty()).mask,
            )
        }
        val rendered = FogViewportTileRender(
            request = request,
            keys = keys,
            queryBounds = queryBounds,
            tiles = tiles,
        )
        // Publish the viewport only after every read/render/cache operation succeeded. A failed
        // replacement render must not make later reveal merges stop maintaining the last mosaic.
        activeViewportKeys = if (activeViewportCenter == request.center) {
            LinkedHashSet<FogTileKey>(activeViewportKeys).apply {
                removeAll(keys.toSet())
                addAll(keys)
                while (size > MAX_ACTIVE_VIEWPORT_KEYS) remove(first())
            }
        } else {
            keys.toSet()
        }
        activeViewportCenter = request.center
        return rendered
    }

    /** Builds a safe opaque-loading mosaic without reading or populating derived caches. */
    fun placeholder(request: FogViewportRequest): FogViewportRender {
        val keys = FogViewportTileGrid.around(
            center = request.center,
            zoom = renderZoom(request.mapZoom),
            renderVersion = renderVersion,
        )
        val tiles = keys.map { key ->
            FogMosaicTile(
                key = key,
                mask = placeholderRenderer.render(key, emptyList()),
            )
        }
        return FogViewportRender(
            request = request,
            keys = keys,
            // Nothing was read; a placeholder reports no bounds rather than bounds it never used.
            queryBounds = null,
            mosaic = FogPocMosaic.compose(tiles).anchoredNear(request.center.longitude),
        )
    }

    /**
     * Immediately unions a persisted reveal into the active viewport and invalidates every other
     * possibly affected derived tile.
     *
     * The conservative candidate calculation is cheap even for a long high-speed step. Rendering
     * is bounded by the active viewport (the 3x3 mosaic, or every LOD window a provider batch
     * rendered for one camera) instead of eagerly comparing masks at zooms 0..22. An off-screen
     * tile can therefore become a cache miss, never a stale displayed mask: its next load
     * rebuilds the complete mask from canonical Room data.
     */
    suspend fun mergePersistedReveals(updates: List<FogRevealUpdate>): FogRevealMerge =
        mutex.withLock {
            if (updates.isEmpty()) {
                return@withLock FogRevealMerge(emptySet(), emptySet())
            }
            // Candidates come only from keys that exist: the byte-bounded memory and disk caches
            // plus the last successfully rendered viewport. The invalidator never materialises the
            // region between two points, so a far-apart same-segment pair costs a bounds test per
            // held key, not one allocation per tile of a rectangle at every zoom (the 2026-09-03
            // heap dump held 3.5 million such keys).
            val maintainedKeys = pipeline.cachedKeys() + activeViewportKeys
            val candidateKeys = buildSet {
                updates.forEach { update ->
                    // A page carries up to 256 points and the lock is held for the whole page, so
                    // a caller's timeout still has to be able to reach the loop: keep the
                    // cancellation point first, or a wedged holder starves every other surface.
                    currentCoroutineContext().ensureActive()
                    addAll(invalidator.candidateKeysAmong(update, renderVersion, maintainedKeys))
                }
            }
            val activeKeys = candidateKeys.intersect(activeViewportKeys)
            val offscreenKeys = candidateKeys - activeKeys
            val segments = updates.mapIndexed { index, update ->
                TrackSegment(
                    id = index,
                    points = update.previousInSegment
                        ?.let { previous -> listOf(previous, update.current) }
                        ?: listOf(update.current),
                )
            }
            pipeline.invalidate(offscreenKeys)
            val activeMerge = pipeline.mergeReveal(activeKeys, segments)
            FogRevealMerge(
                updatedKeys = activeMerge.updatedKeys,
                missingKeys = activeMerge.missingKeys + offscreenKeys,
            )
        }

    suspend fun clearDerivedCache() = mutex.withLock { pipeline.clear() }

    private fun renderZoom(mapZoom: Double): Int =
        floor(mapZoom).toInt().coerceIn(0, 22)

    companion object {
        // 100 m/s * 60 s accepted continuity ceiling + 25 m reveal radius + rounding allowance.
        const val DEFAULT_QUERY_MARGIN_METERS = 6_100.0
        const val MAX_PROVIDER_VIEWPORT_TILES = 256

        /** Four provider windows' worth; a plan of LOD windows for one camera is capped at one. */
        const val MAX_ACTIVE_VIEWPORT_KEYS = 4 * MAX_PROVIDER_VIEWPORT_TILES
    }
}

/**
 * Moves a mosaic into the same copy of the world the camera is looking at.
 *
 * Tile longitudes are canonical, so a tile window that wraps the antimeridian is expressed starting
 * from the far side: a camera just west of the seam gets a mosaic built a whole world to its east.
 * The renderer draws an image at the coordinates it is given and does not repeat it across world
 * copies, so that mosaic is drawn somewhere the camera cannot see, leaving the repeated basemap
 * under it with no fog at all. Shifting by whole worlds preserves the geometry exactly — the same
 * ground, named in the camera's own copy.
 */
internal fun FogTileMosaic.anchoredNear(centerLongitude: Double): FogTileMosaic {
    val span = bounds.eastLongitude - bounds.westLongitude
    if (span <= 0.0 || !centerLongitude.isFinite()) return this
    // The centre of the mosaic is what should sit near the camera; anchoring on an edge would
    // leave a mosaic that merely touches the camera counted as correctly placed.
    val currentCenter = bounds.westLongitude + span / 2.0
    val worlds = Math.round((centerLongitude - currentCenter) / 360.0)
    if (worlds == 0L) return this
    val shift = worlds * 360.0
    return copy(
        bounds = bounds.copy(
            westLongitude = bounds.westLongitude + shift,
            eastLongitude = bounds.eastLongitude + shift,
        ),
    )
}

object FogViewportTileGrid {
    fun around(
        center: GeoPoint,
        zoom: Int,
        renderVersion: Int,
        paddingTiles: Int = 1,
    ): List<FogTileKey> {
        require(zoom in 0..22) { "zoom must be in 0..22" }
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        require(paddingTiles >= 0) { "paddingTiles must be non-negative" }
        val centerTile = WebMercator.tile(center, zoom)
        val tileCount = 1 shl zoom
        val desiredColumns = Math.addExact(Math.multiplyExact(paddingTiles, 2), 1)
        val columnCount = minOf(tileCount, desiredColumns)
        val startX = if (columnCount == tileCount) {
            0
        } else {
            Math.floorMod(centerTile.x - paddingTiles, tileCount)
        }
        val firstY = max(0, centerTile.y - paddingTiles)
        val lastY = minOf(tileCount - 1, centerTile.y + paddingTiles)

        return buildList {
            for (y in firstY..lastY) {
                repeat(columnCount) { offset ->
                    add(
                        FogTileKey(
                            zoom = zoom,
                            x = Math.floorMod(startX + offset, tileCount),
                            y = y,
                            renderVersion = renderVersion,
                        ),
                    )
                }
            }
        }
    }

    /**
     * The smallest complete sub-rectangle of [window] that contains every key in [wanted].
     *
     * [queryBounds] needs a complete row-major rectangle, so a caller holding an arbitrary subset
     * cannot narrow its read by handing that subset over. Positions are taken within the window
     * rather than from raw tile x, because a window that crosses the antimeridian has columns
     * whose numbers wrap while their order does not.
     */
    fun enclosingSubWindow(
        window: List<FogTileKey>,
        wanted: Set<FogTileKey>,
    ): List<FogTileKey> {
        require(window.isNotEmpty()) { "window must not be empty" }
        require(wanted.isNotEmpty()) { "wanted must not be empty" }
        require(window.containsAll(wanted)) { "wanted must be a subset of the window" }
        val rows = window.map(FogTileKey::y).distinct()
        require(window.size % rows.size == 0) { "window must be row-major rectangular" }
        val columnCount = window.size / rows.size
        val positions = window.withIndex().associate { (index, key) -> key to index }
        val indices = wanted.map { key -> positions.getValue(key) }
        val rowRange = indices.minOf { it / columnCount }..indices.maxOf { it / columnCount }
        val columnRange = indices.minOf { it % columnCount }..indices.maxOf { it % columnCount }
        return buildList {
            for (row in rowRange) {
                for (column in columnRange) {
                    add(window[row * columnCount + column])
                }
            }
        }
    }

    fun queryBounds(keys: List<FogTileKey>, marginMeters: Double): ViewportBounds {
        require(keys.isNotEmpty()) { "keys must not be empty" }
        require(marginMeters.isFinite() && marginMeters >= 0.0) {
            "marginMeters must be finite and non-negative"
        }
        val zoom = keys.first().zoom
        val renderVersion = keys.first().renderVersion
        require(keys.all { it.zoom == zoom && it.renderVersion == renderVersion }) {
            "keys must share zoom and render version"
        }
        val rows = keys.groupBy(FogTileKey::y).toSortedMap()
        val firstRow = rows.values.first()
        val expectedX = firstRow.map(FogTileKey::x)
        require(rows.values.all { row -> row.map(FogTileKey::x) == expectedX }) {
            "keys must form a complete row-major rectangle"
        }
        val tileCount = 1 shl zoom
        expectedX.zipWithNext().forEach { (prior, next) ->
            require(next == Math.floorMod(prior + 1, tileCount)) {
                "tile columns must be consecutive"
            }
        }
        rows.keys.zipWithNext().forEach { (prior, next) ->
            require(next == prior + 1) { "tile rows must be consecutive" }
        }

        val north = FogPocTileGrid.bounds(firstRow.first()).northLatitude
        val south = FogPocTileGrid.bounds(rows.values.last().last()).southLatitude
        val unexpanded = if (expectedX.size == tileCount) {
            ViewportBounds(south = south, north = north, west = -180.0, east = 180.0)
        } else {
            ViewportBounds(
                south = south,
                north = north,
                west = FogPocTileGrid.bounds(firstRow.first()).westLongitude,
                east = FogPocTileGrid.bounds(firstRow.last()).eastLongitude,
            )
        }
        return unexpanded.expandByMeters(marginMeters)
    }

    private fun ViewportBounds.expandByMeters(meters: Double): ViewportBounds {
        if (meters == 0.0) return this
        val latitudeMargin = meters / METERS_PER_LATITUDE_DEGREE
        val expandedSouth = (south - latitudeMargin).coerceAtLeast(-90.0)
        val expandedNorth = (north + latitudeMargin).coerceAtMost(90.0)
        val limitingLatitude = max(abs(expandedSouth), abs(expandedNorth))
            .coerceAtMost(WebMercator.MAX_LATITUDE)
        val longitudeMargin = meters / (
            METERS_PER_LATITUDE_DEGREE *
                cos(Math.toRadians(limitingLatitude))
            )
        val longitudeSpan = if (west <= east) east - west else 360.0 - west + east
        if (longitudeSpan + longitudeMargin * 2.0 >= 360.0) {
            return ViewportBounds(
                south = expandedSouth,
                north = expandedNorth,
                west = -180.0,
                east = 180.0,
            )
        }
        return ViewportBounds(
            south = expandedSouth,
            north = expandedNorth,
            west = wrapBoundaryLongitude(west - longitudeMargin),
            east = wrapBoundaryLongitude(east + longitudeMargin),
        )
    }

    private fun wrapBoundaryLongitude(longitude: Double): Double {
        var wrapped = longitude
        while (wrapped < -180.0) wrapped += 360.0
        while (wrapped > 180.0) wrapped -= 360.0
        return if (wrapped == -0.0) 0.0 else wrapped
    }

    private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
}
