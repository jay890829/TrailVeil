package app.trailveil.map.fog

import java.util.LinkedHashMap

/**
 * Splits one canonical viewport mosaic back into its row-major source tiles.
 *
 * The coordinator is the canonical renderer and already preserves Room segment boundaries.  This
 * helper only reverses the deterministic composition operation; it never creates a clear mask or
 * joins points.  A malformed/order-mismatched mosaic fails closed by throwing before publication.
 */
object FogTileMosaicSplitter {
    fun split(render: FogViewportRender): Map<FogTileKey, FogPixelMask> {
        val keys = render.keys
        require(keys.isNotEmpty()) { "viewport render must contain tiles" }
        require(render.mosaic.tileCount == keys.size) {
            "mosaic tile count must match viewport keys"
        }

        val firstRowY = keys.first().y
        val columnCount = keys.indexOfFirst { key -> key.y != firstRowY }
            .takeUnless { it < 0 }
            ?: keys.size
        require(columnCount > 0 && keys.size % columnCount == 0) {
            "viewport keys must form a row-major rectangle"
        }
        val rowCount = keys.size / columnCount
        require(keys.all { key -> key.zoom == keys.first().zoom }) {
            "viewport keys must share zoom"
        }
        require(keys.all { key -> key.renderVersion == keys.first().renderVersion }) {
            "viewport keys must share render version"
        }

        val tileCount = 1 shl keys.first().zoom
        val expectedXSequence = keys.take(columnCount).map(FogTileKey::x)
        for (row in 0 until rowCount) {
            val rowStart = row * columnCount
            val rowKeys = keys.subList(rowStart, rowStart + columnCount)
            require(rowKeys.all { key -> key.y == firstRowY + row }) {
                "viewport rows must be vertically consecutive"
            }
            require(rowKeys.map(FogTileKey::x) == expectedXSequence) {
                "every viewport row must use the same horizontal order"
            }
            rowKeys.zipWithNext().forEach { (prior, next) ->
                require(next.x == Math.floorMod(prior.x + 1, tileCount)) {
                    "viewport columns must be consecutive with dateline wrapping"
                }
            }
        }

        val mosaicMask = render.mosaic.mask
        require(mosaicMask.width % columnCount == 0 && mosaicMask.height % rowCount == 0) {
            "mosaic dimensions must divide evenly into tile dimensions"
        }
        val tileWidth = mosaicMask.width / columnCount
        val tileHeight = mosaicMask.height / rowCount
        require(tileWidth == FogTilePngCodec.TILE_SIZE && tileHeight == FogTilePngCodec.TILE_SIZE) {
            "Google fog tiles must be exactly ${FogTilePngCodec.TILE_SIZE}x${FogTilePngCodec.TILE_SIZE}"
        }

        val sourceAlpha = mosaicMask.copyAlpha()
        val result = LinkedHashMap<FogTileKey, FogPixelMask>(keys.size)
        keys.forEachIndexed { index, key ->
            val row = index / columnCount
            val column = index % columnCount
            val tileAlpha = ByteArray(tileWidth * tileHeight)
            repeat(tileHeight) { sourceY ->
                val sourceOffset =
                    (row * tileHeight + sourceY) * mosaicMask.width + column * tileWidth
                val targetOffset = sourceY * tileWidth
                sourceAlpha.copyInto(
                    destination = tileAlpha,
                    destinationOffset = targetOffset,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + tileWidth,
                )
            }
            check(result.put(key, FogPixelMask(tileWidth, tileHeight, tileAlpha)) == null) {
                "viewport keys must be unique"
            }
        }
        return result
    }
}
