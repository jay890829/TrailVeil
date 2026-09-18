package app.trailveil.data.map

import androidx.collection.MutableLongSet
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.TrackSegment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
     * Reads the exact union of one latitude band's longitude predicates, then reconstructs each
     * original window from raw point identities/sequences. Filtering already-built segments would
     * join across points omitted by a narrower window. Different bands retain independent reads.
     * Bounded batches also bound the amount of speculative input held by a native preparation.
     */
    suspend fun readBatch(bounds: List<ViewportBounds>): List<ViewportTrackReadModel> {
        if (bounds.isEmpty()) return emptyList()
        return bounds.chunked(4).flatMap { group ->
            currentCoroutineContext().ensureActive()
            val first = group.first()
            if (group.size == 1 || group.any { it.south != first.south || it.north != first.north }) {
                group.map { currentCoroutineContext().ensureActive(); readPoints(it) }
            } else {
                val intervals = group.flatMap { it.longitudeIntervals() }.sortedBy { it.west }
                val union = ArrayList<LongitudeInterval>()
                intervals.forEach { interval ->
                    val previous = union.lastOrNull()
                    if (previous == null || interval.west > previous.east) union += interval
                    else union[union.lastIndex] = LongitudeInterval(previous.west, maxOf(previous.east, interval.east))
                }
                val raw = union.flatMap { interval ->
                    currentCoroutineContext().ensureActive()
                    reader.read(first.south, first.north, interval)
                }
                currentCoroutineContext().ensureActive()
                val ordered = orderPoints(raw)
                // A point belongs to several overlapping windows, but its immutable geographic
                // value only needs to be constructed once within this bounded (<=4 windows) batch.
                val projected = arrayOfNulls<GeoPoint>(ordered.size)
                group.map { window ->
                    currentCoroutineContext().ensureActive()
                    val members = window.longitudeIntervals()
                    val indices = IntArray(ordered.size)
                    var size = 0
                    ordered.forEachIndexed { index, point ->
                        if (index % 256 == 0) currentCoroutineContext().ensureActive()
                        if (point.latitude >= window.south && point.latitude <= window.north &&
                            members.any { point.longitude >= it.west && point.longitude <= it.east }) {
                            indices[size++] = index
                        }
                    }
                    readIndexedModel(ordered, indices, size, projected)
                }
            }
        }
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
        val merged = orderPoints(bounds.longitudeIntervals()
            .flatMap { interval -> reader.read(bounds.south, bounds.north, interval) })
        return readModel(merged)
    }

    private fun orderPoints(points: List<ViewportTrackPoint>): List<ViewportTrackPoint> {
        val seen = MutableLongSet()
        val unique = ArrayList<ViewportTrackPoint>()
        for (point in points) if (seen.add(point.pointId)) unique += point
        unique.sortWith(POINT_ORDER)
        return unique
    }

    /**
     * Both callers use orderPoints first; a batch window only filters that ordered list. Thus each
     * (session, segment sequence, segment id) group is contiguous already. Find sequence runs in
     * that order and allocate only their final point lists, avoiding per-point keys and temporary
     * grouping/run lists. Duplicate sequences, gaps and Long.MAX_VALUE still end a run.
     */
    private fun readModel(merged: List<ViewportTrackPoint>): ViewportTrackReadModel {
        val segments = ArrayList<ViewportTrackSegment>()
        var start = 0
        while (start < merged.size) {
            val first = merged[start]
            var end = start + 1
            while (end < merged.size) {
                val previous = merged[end - 1]
                val next = merged[end]
                if (next.sessionId != first.sessionId || next.segmentSequence != first.segmentSequence ||
                    next.segmentId != first.segmentId || previous.pointSequence == Long.MAX_VALUE ||
                    next.pointSequence != previous.pointSequence + 1
                ) break
                end++
            }
            val points = ArrayList<GeoPoint>(end - start)
            for (index in start until end) {
                val point = merged[index]
                points += GeoPoint(point.latitude, point.longitude)
            }
            segments += ViewportTrackSegment(first.sessionId, first.segmentId, first.segmentSequence, points)
            start = end
        }
        return ViewportTrackReadModel(segments)
    }

    /** Same run boundaries as readModel; ordered indices preserve filtering without row-list copies. */
    private fun readIndexedModel(ordered: List<ViewportTrackPoint>, indices: IntArray, size: Int,
        projected: Array<GeoPoint?>): ViewportTrackReadModel {
        val segments = ArrayList<ViewportTrackSegment>()
        var start = 0
        while (start < size) {
            val first = ordered[indices[start]]
            var end = start + 1
            while (end < size) {
                val previous = ordered[indices[end - 1]]
                val next = ordered[indices[end]]
                if (next.sessionId != first.sessionId || next.segmentSequence != first.segmentSequence ||
                    next.segmentId != first.segmentId || previous.pointSequence == Long.MAX_VALUE ||
                    next.pointSequence != previous.pointSequence + 1
                ) break
                end++
            }
            val points = ArrayList<GeoPoint>(end - start)
            for (position in start until end) {
                val index = indices[position]
                val point = projected[index] ?: ordered[index].let { raw ->
                    GeoPoint(raw.latitude, raw.longitude).also { projected[index] = it }
                }
                points += point
            }
            segments += ViewportTrackSegment(first.sessionId, first.segmentId, first.segmentSequence, points)
            start = end
        }
        return ViewportTrackReadModel(segments)
    }

    private companion object {
        // Compare primitive longs, preserving the former stable four-field ordering and ties.
        val POINT_ORDER = Comparator<ViewportTrackPoint> { left, right ->
            var result = left.sessionId.compareTo(right.sessionId)
            if (result == 0) result = left.segmentSequence.compareTo(right.segmentSequence)
            if (result == 0) result = left.segmentId.compareTo(right.segmentId)
            if (result == 0) result = left.pointSequence.compareTo(right.pointSequence)
            result
        }
        /**
         * A cell belongs to no recording, and nothing reads these back: `toFogTrackSegments()`
         * assigns render-local ids by position and ignores both fields. They are named rather than
         * left as bare zeroes so the absence is legible instead of looking like a missing lookup.
         */
        const val COARSE_CELL_NO_SESSION = 0L
        const val COARSE_CELL_NO_SEGMENT = 0L
    }

}
