package app.trailveil.data.map

import app.trailveil.map.fog.GeoPoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ViewportTrackDataSourceB44Reference(
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
                group.map { window ->
                    currentCoroutineContext().ensureActive()
                    val members = window.longitudeIntervals()
                    readModel(ordered.filterIndexed { index, point ->
                        if (index % 256 == 0) currentCoroutineContext().ensureActive()
                        point.latitude in window.south..window.north &&
                            members.any { point.longitude in it.west..it.east }
                    })
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

    private fun orderPoints(points: List<ViewportTrackPoint>): List<ViewportTrackPoint> =
        points.distinctBy(ViewportTrackPoint::pointId)
            .sortedWith(
                compareBy<ViewportTrackPoint>(ViewportTrackPoint::sessionId)
                    .thenBy(ViewportTrackPoint::segmentSequence)
                    .thenBy(ViewportTrackPoint::segmentId)
                    .thenBy(ViewportTrackPoint::pointSequence),
            )

    private fun readModel(merged: List<ViewportTrackPoint>): ViewportTrackReadModel = ViewportTrackReadModel(
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
