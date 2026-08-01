package app.trailveil.map.fog

data class FogMosaicTile(
    val key: FogTileKey,
    val mask: FogPixelMask,
)

data class FogTileMosaic(
    val mask: FogPixelMask,
    val bounds: FogTileBounds,
    val tileCount: Int,
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
        require(tiles.isNotEmpty()) { "tiles must not be empty" }
        val first = tiles.first()
        val zoom = first.key.zoom
        val renderVersion = first.key.renderVersion
        val tileWidth = first.mask.width
        val tileHeight = first.mask.height
        require(tileWidth > 0 && tileHeight > 0) { "tile masks must not be empty" }
        require(tiles.all { it.key.zoom == zoom }) { "all tiles must use the same zoom" }
        require(tiles.all { it.key.renderVersion == renderVersion }) {
            "all tiles must use the same render version"
        }
        require(tiles.all { it.mask.width == tileWidth && it.mask.height == tileHeight }) {
            "all tile masks must have the same dimensions"
        }

        val rows = tiles.fold(mutableListOf<MutableList<FogMosaicTile>>()) { result, tile ->
            val current = result.lastOrNull()
            if (current == null || current.first().key.y != tile.key.y) {
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
            require(next.first().key.y == prior.first().key.y + 1) {
                "tile rows must be vertically consecutive"
            }
        }
        val tileCountAtZoom = 1 shl zoom
        val expectedX = rows.first().map { it.key.x }
        expectedX.zipWithNext().forEach { (prior, next) ->
            require(next == Math.floorMod(prior + 1, tileCountAtZoom)) {
                "tile columns must be horizontally consecutive with dateline wrapping"
            }
        }
        require(rows.all { row -> row.map { it.key.x } == expectedX }) {
            "every tile row must use the same horizontal order"
        }

        val mosaicWidth = Math.multiplyExact(tileWidth, columnCount)
        val mosaicHeight = Math.multiplyExact(tileHeight, rows.size)
        val alpha = ByteArray(Math.multiplyExact(mosaicWidth, mosaicHeight))
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, tile ->
                val source = tile.mask.copyAlpha()
                repeat(tileHeight) { sourceY ->
                    val sourceOffset = sourceY * tileWidth
                    val targetOffset =
                        (rowIndex * tileHeight + sourceY) * mosaicWidth +
                            columnIndex * tileWidth
                    source.copyInto(
                        destination = alpha,
                        destinationOffset = targetOffset,
                        startIndex = sourceOffset,
                        endIndex = sourceOffset + tileWidth,
                    )
                }
            }
        }

        val firstBounds = FogPocTileGrid.bounds(rows.first().first().key)
        val lastBounds = FogPocTileGrid.bounds(rows.last().last().key)
        val longitudeSpan = 360.0 / tileCountAtZoom
        return FogTileMosaic(
            mask = FogPixelMask(mosaicWidth, mosaicHeight, alpha),
            bounds = FogTileBounds(
                westLongitude = firstBounds.westLongitude,
                southLatitude = lastBounds.southLatitude,
                eastLongitude = firstBounds.westLongitude + columnCount * longitudeSpan,
                northLatitude = firstBounds.northLatitude,
            ),
            tileCount = tiles.size,
        )
    }
}
