package app.trailveil.data.map

import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.TrackSegment

/** A non-wrapping longitude predicate suitable for a database query. */
data class LongitudeInterval(
    val west: Double,
    val east: Double,
) {
    init {
        require(west.isFinite() && west in -180.0..180.0) {
            "west must be finite and in [-180, 180]"
        }
        require(east.isFinite() && east in -180.0..180.0) {
            "east must be finite and in [-180, 180]"
        }
        require(west <= east) { "a longitude interval must not wrap the dateline" }
    }
}

/**
 * Geographic viewport bounds. A west value greater than east intentionally represents a
 * dateline-crossing viewport and is split into two [LongitudeInterval]s for storage readers.
 */
data class ViewportBounds(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
) {
    init {
        require(south.isFinite() && south in -90.0..90.0) {
            "south must be finite and in [-90, 90]"
        }
        require(north.isFinite() && north in -90.0..90.0) {
            "north must be finite and in [-90, 90]"
        }
        require(south <= north) { "south must not exceed north" }
        require(west.isFinite() && west in -180.0..180.0) {
            "west must be finite and in [-180, 180]"
        }
        require(east.isFinite() && east in -180.0..180.0) {
            "east must be finite and in [-180, 180]"
        }
    }

    fun longitudeIntervals(): List<LongitudeInterval> =
        if (west <= east) {
            listOf(LongitudeInterval(west = west, east = east))
        } else {
            listOf(
                LongitudeInterval(west = west, east = 180.0),
                LongitudeInterval(west = -180.0, east = east),
            )
        }
}

/**
 * Storage-neutral projection of one persisted point. It deliberately retains segment identity
 * and ordering fields so a viewport renderer cannot accidentally join separate recordings.
 */
data class ViewportTrackPoint(
    val pointId: Long,
    val sessionId: Long,
    val segmentId: Long,
    val segmentSequence: Long,
    val pointSequence: Long,
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(pointId > 0) { "pointId must be positive" }
        require(sessionId > 0) { "sessionId must be positive" }
        require(segmentId > 0) { "segmentId must be positive" }
        require(segmentSequence >= 0) { "segmentSequence must be non-negative" }
        require(pointSequence >= 0) { "pointSequence must be non-negative" }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be finite and in [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be finite and in [-180, 180]"
        }
    }
}

/** A provider supplies points for exactly one non-dateline-wrapping longitude interval. */
fun interface ViewportTrackPointReader {
    suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint>
}

/** A persisted segment identity and its ordered viewport points. */
data class ViewportTrackSegment(
    val sessionId: Long,
    val segmentId: Long,
    val segmentSequence: Long,
    val points: List<GeoPoint>,
)

data class ViewportTrackReadModel(
    val segments: List<ViewportTrackSegment>,
) {
    /**
     * Fog's ids are render-local only. Assigning them from this already stable result makes ids
     * deterministic without treating a database id as an Int or merging distinct segments.
     */
    fun toFogTrackSegments(): List<TrackSegment> =
        segments.mapIndexed { index, segment ->
            TrackSegment(id = index, points = segment.points)
        }
}

/**
 * Queries one or two storage intervals, removes overlap duplicates, and reconstructs only
 * persisted segment boundaries and point-sequence continuity. This core deliberately knows
 * nothing about Room or a map SDK.
 */
class ViewportTrackDataSource(
    private val reader: ViewportTrackPointReader,
) {
    suspend fun read(bounds: ViewportBounds): ViewportTrackReadModel {
        val merged = bounds.longitudeIntervals()
            .flatMap { interval -> reader.read(bounds.south, bounds.north, interval) }
            .distinctBy(ViewportTrackPoint::pointId)
            .sortedWith(
                compareBy<ViewportTrackPoint>(ViewportTrackPoint::sessionId)
                    .thenBy(ViewportTrackPoint::segmentSequence)
                    .thenBy(ViewportTrackPoint::segmentId)
                    .thenBy(ViewportTrackPoint::pointSequence),
            )

        return ViewportTrackReadModel(
            segments = merged
                .groupBy { point -> SegmentKey(point.sessionId, point.segmentId, point.segmentSequence) }
                .flatMap { (key, points) ->
                    points.contiguousSequenceRuns().map { run ->
                        ViewportTrackSegment(
                            sessionId = key.sessionId,
                            segmentId = key.segmentId,
                            segmentSequence = key.segmentSequence,
                            points = run.map { point ->
                                GeoPoint(latitude = point.latitude, longitude = point.longitude)
                            },
                        )
                    }
                },
        )
    }

    /** A bbox can omit middle points from one persisted segment; never draw across that gap. */
    private fun List<ViewportTrackPoint>.contiguousSequenceRuns(): List<List<ViewportTrackPoint>> {
        val runs = mutableListOf<MutableList<ViewportTrackPoint>>()
        forEach { point ->
            val previous = runs.lastOrNull()?.lastOrNull()
            if (
                previous != null &&
                previous.pointSequence != Long.MAX_VALUE &&
                point.pointSequence == previous.pointSequence + 1
            ) {
                runs.last().add(point)
            } else {
                runs += mutableListOf(point)
            }
        }
        return runs
    }

    private data class SegmentKey(
        val sessionId: Long,
        val segmentId: Long,
        val segmentSequence: Long,
    )
}
