package io.github.jay890829.trailveil.map.fog

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh

data class FogTileBounds(
    val westLongitude: Double,
    val southLatitude: Double,
    val eastLongitude: Double,
    val northLatitude: Double,
)

object FogPocTileGrid {
    /**
     * Returns the valid tiles in the 3x3 neighborhood around [center].
     *
     * The horizontal axis wraps across the antimeridian. The vertical axis does not wrap,
     * so a center tile at a Web Mercator pole has six valid neighbors instead of duplicates.
     */
    fun around(
        center: GeoPoint,
        zoom: Int,
        renderVersion: Int,
    ): List<FogTileKey> {
        val centerTile = WebMercator.tile(center, zoom)
        val tileCount = 1 shl zoom
        return buildList {
            for (yOffset in -1..1) {
                val y = centerTile.y + yOffset
                if (y !in 0 until tileCount) continue
                for (xOffset in -1..1) {
                    add(
                        FogTileKey(
                            zoom = zoom,
                            x = Math.floorMod(centerTile.x + xOffset, tileCount),
                            y = y,
                            renderVersion = renderVersion,
                        ),
                    )
                }
            }
        }
    }

    fun bounds(key: FogTileKey): FogTileBounds {
        val tileCount = (1 shl key.zoom).toDouble()
        return FogTileBounds(
            westLongitude = key.x / tileCount * 360.0 - 180.0,
            southLatitude = latitudeAtTileEdge((key.y + 1) / tileCount),
            eastLongitude = (key.x + 1) / tileCount * 360.0 - 180.0,
            northLatitude = latitudeAtTileEdge(key.y / tileCount),
        )
    }

    private fun latitudeAtTileEdge(normalizedY: Double): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * normalizedY))))
}

enum class FogPocTimingStage(val logValue: String) {
    STYLE_LOAD("style_load"),
    INITIAL_RENDER("initial_render"),
    UPDATE_RENDER("update_render"),
    NEXT_RENDERED_FRAME("next_rendered_frame"),
}

data class FogPocTiming(
    val stage: FogPocTimingStage,
    val durationMillis: Long,
    val pointCount: Int,
    val tileCount: Int,
) {
    init {
        require(durationMillis >= 0) { "durationMillis must not be negative" }
        require(pointCount >= 0) { "pointCount must not be negative" }
        require(tileCount >= 0) { "tileCount must not be negative" }
    }

    /**
     * Deliberately excludes positions and identifiers so diagnostic timing logs cannot expose
     * trail coordinates.
     */
    fun asStructuredLog(): String =
        "event=trailveil_maplibre_poc_timing " +
            "stage=${stage.logValue} " +
            "duration_ms=$durationMillis " +
            "point_count=$pointCount " +
            "tile_count=$tileCount"
}
