package app.trailveil.feature.history

import app.trailveil.data.history.RecordingHistoryAcceptedPoint
import app.trailveil.data.history.RecordingHistoryAcceptedPointSegment
import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.data.history.RecordingHistorySession
import app.trailveil.data.history.RecordingHistoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HistoryTrackPresentationTest {
    @Test
    fun mapOverlayPreservesSegmentGapInsteadOfConnectingIt() {
        val detail = RecordingHistoryDetail(
            session = RecordingHistorySession(
                id = 9L,
                startedAt = 100L,
                endedAt = 500L,
                status = RecordingHistoryStatus.COMPLETED,
                stopReason = null,
                distanceMeters = 20.0,
                acceptedPointCount = 3L,
                rejectedPointCount = 0L,
            ),
            segments = emptyList(),
            latestOperationOutcome = null,
            latestAcceptedPoint = null,
            acceptedPointSegments = listOf(
                acceptedSegment(1L, 0L, 25.0, 121.0, 25.1, 121.1),
                acceptedSegment(2L, 1L, 26.0, 122.0),
            ),
        )

        val overlay = detail.toMapTrackOverlay()
        assertNotNull(overlay)
        val actual = requireNotNull(overlay)

        assertEquals(2, actual.segments.size)
        assertEquals(2, actual.segments[0].size)
        assertEquals(1, actual.segments[1].size)
        assertEquals(26.0, actual.segments[1].single().latitude, 0.0)
    }

    private fun acceptedSegment(
        id: Long,
        sequence: Long,
        vararg coordinates: Double,
    ) = RecordingHistoryAcceptedPointSegment(
        segmentId = id,
        segmentSequence = sequence,
        points = coordinates.toList().chunked(2).mapIndexed { index, pair ->
            RecordingHistoryAcceptedPoint(
                id = id * 10L + index + 1L,
                timestamp = 100L + index,
                latitude = pair[0],
                longitude = pair[1],
                sequence = index.toLong(),
            )
        },
    )
}
