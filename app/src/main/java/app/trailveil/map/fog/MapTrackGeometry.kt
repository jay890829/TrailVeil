package app.trailveil.map.fog

import kotlin.math.abs

/**
 * Splits one continuous track at the antimeridian so a renderer never draws its long way around
 * the world. Input segment boundaries are preserved; no points are joined across a gap.
 */
fun splitTrackAtAntimeridian(segment: List<GeoPoint>): List<List<GeoPoint>> {
    if (segment.isEmpty()) return emptyList()
    val paths = ArrayList<List<GeoPoint>>()
    var path = ArrayList<GeoPoint>()
    fun flush() {
        if (path.isNotEmpty()) paths += path
        path = ArrayList()
    }

    segment.forEach { rawPoint ->
        val point = rawPoint.copy(longitude = WebMercator.wrapLongitude(rawPoint.longitude))
        val previous = path.lastOrNull()
        if (previous == null) {
            path += point
            return@forEach
        }
        val delta = point.longitude - previous.longitude
        if (abs(delta) <= ANTIMERIDIAN_SPLIT_DEGREES) {
            path += point
            return@forEach
        }
        val adjustedLongitude = if (delta > 0.0) {
            point.longitude - WORLD_LONGITUDE_DEGREES
        } else {
            point.longitude + WORLD_LONGITUDE_DEGREES
        }
        val boundary = if (adjustedLongitude > 180.0) 180.0 else -180.0
        val denominator = adjustedLongitude - previous.longitude
        val fraction = if (denominator == 0.0) 0.0 else
            ((boundary - previous.longitude) / denominator).coerceIn(0.0, 1.0)
        val crossing = GeoPoint(
            latitude = previous.latitude + (point.latitude - previous.latitude) * fraction,
            longitude = boundary,
        )
        path += crossing
        flush()
        val oppositeBoundary = crossing.copy(longitude = -boundary)
        path += oppositeBoundary
        if (oppositeBoundary != point) path += point
    }
    flush()
    return paths
}

private const val ANTIMERIDIAN_SPLIT_DEGREES = 180.0
private const val WORLD_LONGITUDE_DEGREES = 360.0
