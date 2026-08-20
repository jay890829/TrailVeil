package app.trailveil.data.map

import app.trailveil.data.db.LatitudeBuckets
import app.trailveil.data.db.RecordingDao
import app.trailveil.data.db.ViewportTrackPointRow

/** Room-backed viewport projection that exposes no map-provider-specific types. */
internal class RoomViewportTrackPointReader(
    private val dao: RecordingDao,
) : ViewportTrackPointReader {
    override suspend fun read(
        south: Double,
        north: Double,
        interval: LongitudeInterval,
    ): List<ViewportTrackPoint> =
        rowsFor(south, north, interval).map { row ->
            ViewportTrackPoint(
                pointId = row.pointId,
                sessionId = row.sessionId,
                segmentId = row.segmentId,
                segmentSequence = row.segmentSequence,
                pointSequence = row.pointSequence,
                latitude = row.latitude,
                longitude = row.longitude,
            )
        }

    /**
     * `P4-036`: bound the scan in both dimensions when the band can be spelled as equalities, and
     * fall back to the plain range predicate when it cannot.
     *
     * The fallback is not a lesser answer, it is the same answer by a slower route — both queries
     * carry the identical `latitude BETWEEN` and `longitude BETWEEN` predicates, so the rows are
     * the same set either way and only the plan differs. A band too tall for the equality list is
     * one spanning more than about 1.3 degrees, which is coarser than any zoom where an index could
     * help: the box already contains everything the table holds.
     */
    private suspend fun rowsFor(
        south: Double,
        north: Double,
        interval: LongitudeInterval,
    ): List<ViewportTrackPointRow> {
        val buckets = LatitudeBuckets.covering(south, north)
            ?: return dao.fogPointsInLongitudeInterval(
                south = south,
                west = interval.west,
                north = north,
                east = interval.east,
            )
        return dao.fogPointsInBucketedBox(
            latitudeBuckets = buckets,
            south = south,
            west = interval.west,
            north = north,
            east = interval.east,
        )
    }
}
