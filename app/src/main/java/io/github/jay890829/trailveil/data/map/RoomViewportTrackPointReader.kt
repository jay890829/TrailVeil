package io.github.jay890829.trailveil.data.map

import io.github.jay890829.trailveil.data.db.RecordingDao

/** Room-backed viewport projection that exposes no map-provider-specific types. */
internal class RoomViewportTrackPointReader(
    private val dao: RecordingDao,
) : ViewportTrackPointReader {
    override suspend fun read(
        south: Double,
        north: Double,
        interval: LongitudeInterval,
    ): List<ViewportTrackPoint> =
        dao.fogPointsInLongitudeInterval(
            south = south,
            west = interval.west,
            north = north,
            east = interval.east,
        ).map { row ->
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
}
