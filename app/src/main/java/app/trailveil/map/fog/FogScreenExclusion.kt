package app.trailveil.map.fog

import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor

/** A local screen-space rectangle occupied by a map-owned overlay. */
data class FogScreenRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "screen rectangle coordinates must be finite"
        }
        require(left <= right && top <= bottom) {
            "screen rectangle edges must be ordered"
        }
    }

    fun inflate(radiusPx: Double): FogScreenRect {
        require(radiusPx.isFinite() && radiusPx >= 0.0) {
            "screen rectangle inflation must be finite and non-negative"
        }
        return copy(
            left = left - radiusPx,
            top = top - radiusPx,
            right = right + radiusPx,
            bottom = bottom + radiusPx,
        )
    }
}

/**
 * Conservative geographic conversion for one overlay footprint.
 *
 * The projection callback receives coordinates in the map-local pixel space. The rectangle is
 * clipped to the finite viewport before projection, so a marker or line at a screen edge cannot
 * make the proof planner inspect an invalid projection coordinate. A failed projection returns a
 * whole-world zone: hiding the overlay and retrying is safe, while silently omitting a zone would
 * let an overlay-covered probe be mistaken for a valid fog sample. The clipped rectangle is
 * quantized outward (floor left/top, ceil right/bottom) to preserve fractional physical pixels.
 *
 * Longitudes are unwrapped relative to the left edge. A small rectangle whose two sides project
 * to +179 and -179 therefore becomes a two-degree wrapped zone rather than a 358-degree zone.
 */
fun fogProbeExclusionZoneForScreenRect(
    rectangle: FogScreenRect,
    viewportWidth: Int,
    viewportHeight: Int,
    project: (x: Double, y: Double) -> GeoPoint?,
): FogProbeExclusionZone? {
    if (viewportWidth <= 0 || viewportHeight <= 0) return wholeWorldFogProbeExclusionZone()
    val width = viewportWidth.toDouble()
    val height = viewportHeight.toDouble()
    val left = rectangle.left.coerceIn(0.0, width)
    val right = rectangle.right.coerceIn(0.0, width)
    val top = rectangle.top.coerceIn(0.0, height)
    val bottom = rectangle.bottom.coerceIn(0.0, height)
    // The strong probe is a physical 3x3 sample. Preserve the one-pixel footprint on every edge
    // when fractional screen coordinates reach an integer projection API: floor the left/top edge
    // and ceil the right/bottom edge. Toward-zero truncation on a positive right/bottom edge would
    // silently undo part of H's inflation.
    val conservativeLeft = floor(left)
    val conservativeRight = ceil(right)
    val conservativeTop = floor(top)
    val conservativeBottom = ceil(bottom)
    if (
        conservativeLeft >= conservativeRight ||
            conservativeTop >= conservativeBottom
    ) return null

    // A footprint spanning half the map can represent either a genuinely wide overlay or a
    // world-copy seam. Treat it as whole-world rather than choosing the shorter longitude arc;
    // the caller will hide the overlay and re-plan, which is conservative and bounded.
    if (
        conservativeLeft <= 0.0 && conservativeRight >= width ||
            conservativeRight - conservativeLeft >= width / 2.0
    ) {
        return wholeWorldFogProbeExclusionZone()
    }

    val projected = try {
        listOf(
            project(conservativeLeft, conservativeTop),
            project(conservativeLeft, conservativeBottom),
            project(conservativeRight, conservativeTop),
            project(conservativeRight, conservativeBottom),
        )
    } catch (_: Exception) {
        return wholeWorldFogProbeExclusionZone()
    } catch (_: LinkageError) {
        return wholeWorldFogProbeExclusionZone()
    }
    if (projected.any { point ->
            point == null ||
                !point.latitude.isFinite() ||
                !point.longitude.isFinite() ||
                point.latitude !in -90.0..90.0
        }
    ) {
        return wholeWorldFogProbeExclusionZone()
    }

    val points = projected.filterNotNull()
    val south = points.minOf(GeoPoint::latitude).coerceIn(-90.0, 90.0)
    val north = points.maxOf(GeoPoint::latitude).coerceIn(-90.0, 90.0)
    val leftLongitude = WebMercator.wrapLongitude(points[0].longitude)
    val unwrappedLongitudes = points.map { point ->
        val normalized = WebMercator.wrapLongitude(point.longitude)
        leftLongitude + WebMercator.wrapLongitude(normalized - leftLongitude)
    }
    val west = unwrappedLongitudes.minOrNull() ?: return wholeWorldFogProbeExclusionZone()
    val east = unwrappedLongitudes.maxOrNull() ?: return wholeWorldFogProbeExclusionZone()
    if (!west.isFinite() || !east.isFinite() || east - west >= WORLD_LONGITUDE_DEGREES) {
        return wholeWorldFogProbeExclusionZone()
    }
    return FogProbeExclusionZone(
        southLatitude = min(south, north),
        northLatitude = max(south, north),
        westLongitude = west,
        eastLongitude = east,
    )
}

/** A conservative fallback when an overlay's geographic footprint cannot be projected. */
fun wholeWorldFogProbeExclusionZone(): FogProbeExclusionZone = FogProbeExclusionZone(
    southLatitude = -90.0,
    northLatitude = 90.0,
    westLongitude = -180.0,
    eastLongitude = 180.0,
)

private const val WORLD_LONGITUDE_DEGREES = 360.0
