package app.trailveil.map.fog

import app.trailveil.data.db.LatitudeBuckets
import app.trailveil.data.db.RecordingDao
import app.trailveil.data.map.*

/** Test-only copy of the production point-read route; timing includes Room scheduling/row mapping. */
internal class DetailedRoomReader(private val dao: RecordingDao) : ViewportTrackPointReader by RoomViewportTrackPointReader(dao) {
    var daoNanos = 0L
    var projectionNanos = 0L
    override suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> {
        val buckets = LatitudeBuckets.covering(south, north)
        val start = System.nanoTime()
        val rows = if (buckets == null) dao.fogPointsInLongitudeInterval(south, interval.west, north, interval.east)
            else dao.fogPointsInBucketedBox(buckets, south, interval.west, north, interval.east)
        daoNanos += System.nanoTime() - start
        val projectionStart = System.nanoTime()
        return rows.map { row ->
            ViewportTrackPoint(row.pointId, row.sessionId, row.segmentId, row.segmentSequence,
                row.pointSequence, row.latitude, row.longitude)
        }.also { projectionNanos += System.nanoTime() - projectionStart }
    }
}
