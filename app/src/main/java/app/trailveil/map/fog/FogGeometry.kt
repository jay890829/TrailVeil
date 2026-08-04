package app.trailveil.map.fog

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite()) { "latitude must be finite" }
        require(longitude.isFinite()) { "longitude must be finite" }
    }
}

/** One ordered, continuous track segment; renderers never bridge separate instances. */
data class TrackSegment(
    val id: Int,
    val points: List<GeoPoint>,
)

data class FogTileKey(
    val zoom: Int,
    val x: Int,
    val y: Int,
    val renderVersion: Int,
) {
    init {
        require(zoom in 0..22) { "zoom must be in 0..22" }
        val tileCount = 1 shl zoom
        require(x in 0 until tileCount) { "x must be within the zoom level" }
        require(y in 0 until tileCount) { "y must be within the zoom level" }
        require(renderVersion >= 0) { "renderVersion must not be negative" }
    }
}

data class TileCoordinate(
    val x: Int,
    val y: Int,
)

data class WorldPixel(
    val x: Double,
    val y: Double,
)

object WebMercator {
    const val MAX_LATITUDE = 85.0511287798066
    const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.68557849

    fun clampLatitude(latitude: Double): Double =
        latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)

    fun wrapLongitude(longitude: Double): Double {
        val wrapped = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (wrapped == -0.0) 0.0 else wrapped
    }

    fun normalizedX(longitude: Double): Double =
        (wrapLongitude(longitude) + 180.0) / 360.0

    fun normalizedY(latitude: Double): Double {
        val radians = Math.toRadians(clampLatitude(latitude))
        val mercator = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
        return mercator.coerceIn(0.0, 1.0)
    }

    fun latitudeAtNormalizedY(normalizedY: Double): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * normalizedY))))

    fun worldPixel(point: GeoPoint, zoom: Int, tileSize: Int = 256): WorldPixel {
        require(zoom in 0..22) { "zoom must be in 0..22" }
        require(tileSize > 0) { "tileSize must be positive" }
        val worldSize = tileSize.toDouble() * (1 shl zoom)
        return WorldPixel(
            x = normalizedX(point.longitude) * worldSize,
            y = normalizedY(point.latitude) * worldSize,
        )
    }

    fun tile(point: GeoPoint, zoom: Int): TileCoordinate {
        require(zoom in 0..22) { "zoom must be in 0..22" }
        val tileCount = 1 shl zoom
        return TileCoordinate(
            x = floor(normalizedX(point.longitude) * tileCount).toInt()
                .coerceIn(0, tileCount - 1),
            y = floor(normalizedY(point.latitude) * tileCount).toInt()
                .coerceIn(0, tileCount - 1),
        )
    }

    fun pointAtWorldPixel(pixel: WorldPixel, zoom: Int, tileSize: Int = 256): GeoPoint {
        require(zoom in 0..22) { "zoom must be in 0..22" }
        require(tileSize > 0) { "tileSize must be positive" }
        val worldSize = tileSize.toDouble() * (1 shl zoom)
        val normalizedX = ((pixel.x / worldSize) % 1.0 + 1.0) % 1.0
        val normalizedY = (pixel.y / worldSize).coerceIn(0.0, 1.0)
        val longitude = normalizedX * 360.0 - 180.0
        val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * normalizedY))))
        return GeoPoint(latitude, longitude)
    }

    fun metersPerPixel(latitude: Double, zoom: Int, tileSize: Int = 256): Double {
        require(zoom in 0..22) { "zoom must be in 0..22" }
        require(tileSize > 0) { "tileSize must be positive" }
        val worldSize = tileSize.toDouble() * (1 shl zoom)
        return cos(Math.toRadians(clampLatitude(latitude))) *
            EARTH_CIRCUMFERENCE_METERS / worldSize
    }

    internal fun unwrapWorldX(previousX: Double, wrappedX: Double, worldSize: Double): Double {
        val turns = Math.round((previousX - wrappedX) / worldSize)
        return wrappedX + turns * worldSize
    }
}
