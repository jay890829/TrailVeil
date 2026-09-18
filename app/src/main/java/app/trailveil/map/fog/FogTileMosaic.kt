package app.trailveil.map.fog

data class FogMosaicTile(
    val key: FogTileKey,
    val mask: FogPixelMask,
)

/** Geometry shared by the backdrop/extent policy; only the raster representation has pixels. */
sealed interface FogViewportPresentation {
    val bounds: FogTileBounds
    val tileCount: Int
    val samplingGridWidth: Int
    val samplingGridHeight: Int
}

data class FogTileMosaic(
    val mask: FogPixelMask,
    override val bounds: FogTileBounds,
    override val tileCount: Int,
) : FogViewportPresentation {
    override val samplingGridWidth: Int get() = mask.width
    override val samplingGridHeight: Int get() = mask.height
}

data class FogNativePresentation(
    val geometry: FogNativeGeometry,
    val layout: FogMosaicLayout,
) : FogViewportPresentation {
    init { require(geometry.bounds == layout.bounds) { "native geometry must cover its layout" } }
    override val bounds: FogTileBounds get() = layout.bounds
    override val tileCount: Int get() = layout.tileCount
    override val samplingGridWidth: Int get() = layout.samplingGridWidth
    override val samplingGridHeight: Int get() = layout.samplingGridHeight
}

/** The exact compose footprint, calculated without allocating a mask. */
data class FogMosaicLayout(
    val bounds: FogTileBounds,
    val tileCount: Int,
    val columns: Int,
    val rows: Int,
    val samplingGridWidth: Int,
    val samplingGridHeight: Int,
)

object FogPocMosaic {
    /**
     * Combines a row-major rectangular tile grid into one mask and one continuous map quad.
     *
     * Horizontal tile order may wrap from the last x tile to zero. The returned longitude bounds
     * deliberately remain unwrapped so a dateline mosaic is continuous instead of spanning the
     * long way around the world.
     */
    fun compose(tiles: List<FogMosaicTile>): FogTileMosaic {
        val layout = layout(tiles)
        val tileWidth = tiles.first().mask.width
        val tileHeight = tiles.first().mask.height
        val mosaicWidth = layout.samplingGridWidth
        val mosaicHeight = layout.samplingGridHeight
        val alpha = ByteArray(Math.multiplyExact(mosaicWidth, mosaicHeight))
        tiles.forEachIndexed { index, tile ->
            val rowIndex = index / layout.columns
            val columnIndex = index % layout.columns
            val source = tile.mask.copyAlpha()
            repeat(tileHeight) { sourceY ->
                val sourceOffset = sourceY * tileWidth
                val targetOffset = (rowIndex * tileHeight + sourceY) * mosaicWidth + columnIndex * tileWidth
                source.copyInto(alpha, targetOffset, sourceOffset, sourceOffset + tileWidth)
            }
        }
        return FogTileMosaic(FogPixelMask(mosaicWidth, mosaicHeight, alpha), layout.bounds, layout.tileCount)
    }

    /** Validates the same tile batch as [compose], without copying or allocating pixel arrays. */
    fun layout(tiles: List<FogMosaicTile>): FogMosaicLayout {
        require(tiles.isNotEmpty()) { "tiles must not be empty" }
        val first = tiles.first()
        val tileWidth = first.mask.width
        val tileHeight = first.mask.height
        require(tiles.all { it.mask.width == tileWidth && it.mask.height == tileHeight }) {
            "all tile masks must have the same dimensions"
        }
        val layout = layout(tiles.map(FogMosaicTile::key), tileWidth, tileHeight)
        tiles.forEach { it.mask.requireCompleteRasterData() }
        return layout
    }

    fun layout(keys: List<FogTileKey>, tileWidth: Int, tileHeight: Int = tileWidth): FogMosaicLayout {
        require(keys.isNotEmpty()) { "tiles must not be empty" }
        require(tileWidth > 0 && tileHeight > 0) { "tile masks must not be empty" }
        val zoom = keys.first().zoom
        val renderVersion = keys.first().renderVersion
        require(keys.all { it.zoom == zoom }) { "all tiles must use the same zoom" }
        require(keys.all { it.renderVersion == renderVersion }) { "all tiles must use the same render version" }

        val rows = keys.fold(mutableListOf<MutableList<FogTileKey>>()) { result, tile ->
            val current = result.lastOrNull()
            if (current == null || current.first().y != tile.y) {
                result += mutableListOf(tile)
            } else {
                current += tile
            }
            result
        }
        val columnCount = rows.first().size
        require(columnCount > 0 && rows.all { it.size == columnCount }) {
            "tiles must form a complete row-major rectangle"
        }
        rows.zipWithNext().forEach { (prior, next) ->
            require(next.first().y == prior.first().y + 1) {
                "tile rows must be vertically consecutive"
            }
        }
        val tileCountAtZoom = 1 shl zoom
        val expectedX = rows.first().map { it.x }
        expectedX.zipWithNext().forEach { (prior, next) ->
            require(next == Math.floorMod(prior + 1, tileCountAtZoom)) {
                "tile columns must be horizontally consecutive with dateline wrapping"
            }
        }
        require(rows.all { row -> row.map { it.x } == expectedX }) {
            "every tile row must use the same horizontal order"
        }

        val mosaicWidth = Math.multiplyExact(tileWidth, columnCount)
        val mosaicHeight = Math.multiplyExact(tileHeight, rows.size)
        Math.multiplyExact(mosaicWidth, mosaicHeight)
        val firstBounds = FogPocTileGrid.bounds(rows.first().first())
        val lastBounds = FogPocTileGrid.bounds(rows.last().last())
        val longitudeSpan = 360.0 / tileCountAtZoom
        return FogMosaicLayout(
            bounds = FogTileBounds(
                westLongitude = firstBounds.westLongitude,
                southLatitude = lastBounds.southLatitude,
                eastLongitude = firstBounds.westLongitude + columnCount * longitudeSpan,
                northLatitude = firstBounds.northLatitude,
            ),
            tileCount = keys.size,
            columns = columnCount,
            rows = rows.size,
            samplingGridWidth = mosaicWidth,
            samplingGridHeight = mosaicHeight,
        )
    }
}
