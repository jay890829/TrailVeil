package app.trailveil.feature.history

import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.map.MapTrackOverlay
import app.trailveil.map.fog.GeoPoint

/** Keeps persisted segment gaps separate so the map never draws a false bridge across a gap. */
internal fun RecordingHistoryDetail.toMapTrackOverlay(): MapTrackOverlay? {
    val segments = acceptedPointSegments.mapNotNull { segment ->
        segment.points
            .map { point -> GeoPoint(point.latitude, point.longitude) }
            .takeIf(List<GeoPoint>::isNotEmpty)
    }
    return segments.takeIf(List<List<GeoPoint>>::isNotEmpty)?.let {
        MapTrackOverlay(requestId = session.id, segments = it)
    }
}
