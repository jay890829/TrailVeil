package app.trailveil.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingHistoryDataSourceTest {
    @Test
    fun detailKeepsPersistedSegmentsInSequenceOrder() {
        val detail = RecordingHistoryDetail(
            session = session(),
            segments = listOf(segment(id = 10, sequence = 0), segment(id = 20, sequence = 1)),
            latestOperationOutcome = RecordingHistoryOperationOutcome("LOCATION_REJECTED_ACCURACY"),
            latestAcceptedPoint = RecordingHistoryAcceptedPoint(4, 120, 25.0, 121.0, sequence = 0),
        )

        assertEquals(listOf(0L, 1L), detail.segments.map(RecordingHistorySegment::sequence))
        assertEquals("LOCATION_REJECTED_ACCURACY", detail.latestOperationOutcome?.value)
    }

    @Test
    fun detailRejectsUnorderedSegmentSummaries() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordingHistoryDetail(
                session = session(),
                segments = listOf(segment(id = 20, sequence = 1), segment(id = 10, sequence = 0)),
                latestOperationOutcome = null,
                latestAcceptedPoint = null,
            )
        }
    }

    @Test
    fun acceptedPointSegmentRejectsOutOfOrderCanonicalPoints() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordingHistoryAcceptedPointSegment(
                segmentId = 10,
                segmentSequence = 0,
                points = listOf(
                    RecordingHistoryAcceptedPoint(2, 101, 25.1, 121.1, sequence = 1),
                    RecordingHistoryAcceptedPoint(1, 100, 25.0, 121.0, sequence = 0),
                ),
            )
        }
    }

    private fun session() = RecordingHistorySession(
        id = 1,
        startedAt = 100,
        endedAt = null,
        status = RecordingHistoryStatus.ACTIVE,
        stopReason = null,
        distanceMeters = 0.0,
        acceptedPointCount = 0,
        rejectedPointCount = 0,
    )

    private fun segment(id: Long, sequence: Long) = RecordingHistorySegment(
        id = id,
        sequence = sequence,
        startedAt = 100 + sequence,
        endedAt = null,
        startReason = "TEST",
        endReason = null,
    )
}
