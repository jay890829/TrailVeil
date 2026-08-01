package app.trailveil.data.map

import org.junit.Assert.assertThrows
import org.junit.Test

class PersistedTrackPointChangeFeedTest {
    @Test
    fun changeModelRejectsAPredecessorFromAnotherPersistedSegment() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedTrackPointChange(
                point = point(pointId = 2, segmentId = 11, pointSequence = 0),
                previousPoint = point(pointId = 1, segmentId = 10, pointSequence = 0),
            )
        }
    }

    @Test
    fun cursorRejectsNegativePointIds() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedPointCursor(-1)
        }
    }

    private fun point(
        pointId: Long,
        segmentId: Long,
        pointSequence: Long,
    ) = PersistedTrackPoint(
        pointId = pointId,
        sessionId = 1,
        segmentId = segmentId,
        segmentSequence = segmentId - 10,
        pointSequence = pointSequence,
        timestamp = pointId,
        latitude = 25.0,
        longitude = 121.0,
    )
}
