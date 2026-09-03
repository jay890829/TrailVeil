package app.trailveil.map

import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.WebMercator

/**
 * Provider-neutral camera bounds for a persisted track.
 *
 * Longitudes are represented as the shortest circular arc containing all valid points. When that
 * arc crosses +/-180 degrees, [westLongitude] is greater than [eastLongitude], which is the bounds
 * representation understood by the map SDKs. Keeping this calculation outside either provider
 * actual makes the antimeridian/world-wrap rule testable without loading an SDK renderer.
 */
internal data class TrackCameraBounds(
    val southLatitude: Double,
    val northLatitude: Double,
    val westLongitude: Double,
    val eastLongitude: Double,
) {
    init {
        require(southLatitude.isFinite() && northLatitude.isFinite())
        require(southLatitude in -90.0..90.0 && northLatitude in -90.0..90.0)
        require(southLatitude <= northLatitude)
        require(westLongitude.isFinite() && eastLongitude.isFinite())
        require(westLongitude in -180.0..180.0 && eastLongitude in -180.0..180.0)
    }

    val crossesAntimeridian: Boolean
        get() = westLongitude > eastLongitude

    /** Width of the selected arc, never the complementary long way around the world. */
    val longitudeSpanDegrees: Double
        get() = if (crossesAntimeridian) {
            eastLongitude + 360.0 - westLongitude
        } else {
            eastLongitude - westLongitude
        }
}

/**
 * Returns bounds for the valid points in [points], or null when there is no camera target.
 *
 * A circular longitude sweep has one largest empty gap. The shortest enclosing arc is the
 * complement of that gap; this is what keeps a track at 179/-179 degrees near the dateline
 * instead of making a bounds object spanning almost 360 degrees. Invalid latitudes are skipped at
 * this provider boundary because both SDK LatLng implementations reject them.
 */
internal fun planTrackCameraBounds(points: List<GeoPoint>): TrackCameraBounds? {
    val valid = points.filter { point -> point.latitude in -90.0..90.0 }
    if (valid.isEmpty()) return null

    val longitudes = valid
        .map { point -> WebMercator.wrapLongitude(point.longitude) }
        .sorted()
    var largestGap = Double.NEGATIVE_INFINITY
    var largestGapIndex = 0
    longitudes.indices.forEach { index ->
        val next = longitudes[(index + 1) % longitudes.size] +
            if (index == longitudes.lastIndex) 360.0 else 0.0
        val gap = next - longitudes[index]
        if (gap > largestGap) {
            largestGap = gap
            largestGapIndex = index
        }
    }
    val west = longitudes[(largestGapIndex + 1) % longitudes.size]
    val east = WebMercator.wrapLongitude(
        west + (360.0 - largestGap).coerceAtLeast(0.0),
    )
    return TrackCameraBounds(
        southLatitude = valid.minOf(GeoPoint::latitude),
        northLatitude = valid.maxOf(GeoPoint::latitude),
        westLongitude = west,
        eastLongitude = east,
    )
}
