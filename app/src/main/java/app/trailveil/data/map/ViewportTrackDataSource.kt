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

    /**
     * `P4-037`: the same box as coarse cell centres, or null when this reader has no coarse route.
     *
     * Null rather than an empty list, because the two mean opposite things: empty is "that box holds
     * nothing", which is an answer, and null is "ask me the other way", which is not. A reader
     * without a summary table returns null and the caller reads points, which is what every
     * lambda-shaped test reader does by inheriting this default.
     */
    suspend fun readCoarseCells(
        south: Double,
        north: Double,
        interval: LongitudeInterval,
    ): List<GeoPoint>? = null
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
    /**
     * [coarse] asks for the cell route: one sub-pixel dot per occupied cell instead of every point.
     *
     * The DECISION is not made here on purpose. Whether a cell is small enough to substitute is a
     * fact about the mask raster, and this class "deliberately knows nothing about Room or a map
     * SDK" — so the caller that knows the render zoom decides, and this only honours it. A reader
     * with no coarse route answers null and the point route runs, so the flag can never silently
     * produce a worse answer than the one it replaced.
     */
    suspend fun read(bounds: ViewportBounds, coarse: Boolean = false): ViewportTrackReadModel {
        if (coarse) {
            val cells = bounds.longitudeIntervals().map { interval ->
                reader.readCoarseCells(bounds.south, bounds.north, interval)
                    ?: return@read readPoints(bounds)
            }
            return coarseReadModel(cells.flatten())
        }
        return readPoints(bounds)
    }

    /**
     * One single-point segment per cell.
     *
     * Never a [ViewportTrackPoint]: that type requires a positive `pointId`, `sessionId` and
     * `segmentId`, so a cell would need fabricated ids, and a fabricated id can collide with a real
     * one in the `distinctBy(pointId)` merge below. Cells carry no identity at all and never enter
     * that merge.
     *
     * One point per segment is also what stops cells being joined to each other. The point route
     * only joins consecutive sequences — "a bbox can omit middle points from one persisted segment;
     * never draw across that gap" — and cells have no sequences to be consecutive in. So the
     * renderer draws dots, and the ground between two cells is never presented as explored.
     */
    private fun coarseReadModel(cells: List<GeoPoint>): ViewportTrackReadModel =
        ViewportTrackReadModel(
            segments = cells.distinct().map { cell ->
                ViewportTrackSegment(
                    sessionId = COARSE_CELL_NO_SESSION,
                    segmentId = COARSE_CELL_NO_SEGMENT,
                    segmentSequence = 0L,
                    points = listOf(cell),
                )
            },
        )

    private suspend fun readPoints(bounds: ViewportBounds): ViewportTrackReadModel {
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

    private companion object {
        /**
         * A cell belongs to no recording, and nothing reads these back: `toFogTrackSegments()`
         * assigns render-local ids by position and ignores both fields. They are named rather than
         * left as bare zeroes so the absence is legible instead of looking like a missing lookup.
         */
        const val COARSE_CELL_NO_SESSION = 0L
        const val COARSE_CELL_NO_SEGMENT = 0L
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
