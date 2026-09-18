package app.trailveil.harness

import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.WebMercator
import kotlin.math.abs
import kotlin.math.cos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A sufficient rejection only. A small-radius-variation, unwrapped segment makes one buffer run
 * in the engine. Its complete centreline and round buffer fit inside this expanded envelope.
 * Ambiguous world copies and radius splits use the original path, including its budget checks.
 * Input-point budgets must be checked by the caller before invoking this helper.
 */
internal suspend fun trackOutsidePartition(
    points: List<GeoPoint>, radius: Double, maxRadiusRatio: Double,
    left: Double, top: Double, right: Double, bottom: Double,
): Boolean {
    if (points.isEmpty()) return false
    var south = Double.POSITIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    var west = Double.POSITIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    var minimumAbsLatitude = Double.POSITIVE_INFINITY
    points.forEachIndexed { index, point ->
        if (index % 256 == 0) currentCoroutineContext().ensureActive()
        // Outside this ordinary-coordinate subset, keep all existing wrap/clamp semantics.
        if (point.latitude !in -80.0..80.0 || point.longitude < -180.0 || point.longitude >= 180.0) return false
        south = minOf(south, point.latitude); north = maxOf(north, point.latitude)
        west = minOf(west, point.longitude); east = maxOf(east, point.longitude)
        minimumAbsLatitude = minOf(minimumAbsLatitude, abs(point.latitude))
    }
    val maximumAbsLatitude = maxOf(abs(south), abs(north))
    val minimumRadius = radius / cos(Math.toRadians(minimumAbsLatitude))
    val maximumRadius = radius / cos(Math.toRadians(maximumAbsLatitude))
    // Stay strictly below the splitter threshold, including floating-point boundary cases.
    if (maximumRadius / minimumRadius >= maxRadiusRatio - 1e-12) return false
    val world = WebMercator.EARTH_CIRCUMFERENCE_METERS
    val minX = WebMercator.normalizedX(west) * world
    val maxX = WebMercator.normalizedX(east) * world
    val reference = (left + right) / 2.0
    // Every endpoint must use the unshifted world copy, with no ambiguous half-world edge.
    if (maxX < minX || maxX - minX >= world / 2.0 - 1e-6 ||
        minX <= reference - world / 2.0 + 1e-6 || maxX >= reference + world / 2.0 - 1e-6
    ) return false
    val minY = WebMercator.normalizedY(north) * world
    val maxY = WebMercator.normalizedY(south) * world
    // A small outward guard avoids accepting a rejection at a floating-point touch boundary.
    val margin = maximumRadius + 1e-6
    return maxX + margin < left || minX - margin > right ||
        maxY + margin < top || minY - margin > bottom
}
