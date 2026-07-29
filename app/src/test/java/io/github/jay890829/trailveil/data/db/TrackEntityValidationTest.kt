package io.github.jay890829.trailveil.data.db

import org.junit.Assert.assertThrows
import org.junit.Test

class TrackEntityValidationTest {
    @Test
    fun activeSessionRequiresSingletonSlotAndNoEnd() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordingSessionEntity(
                startedAt = 1,
                status = RecordingStatus.ACTIVE,
                createdAppVersion = "test",
                activeSlot = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordingSessionEntity(
                startedAt = 1,
                endedAt = 2,
                status = RecordingStatus.ACTIVE,
                createdAppVersion = "test",
            )
        }
    }

    @Test
    fun terminalSessionRequiresEndAndNoSingletonSlot() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordingSessionEntity(
                startedAt = 1,
                status = RecordingStatus.COMPLETED,
                createdAppVersion = "test",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordingSessionEntity(
                startedAt = 1,
                endedAt = 2,
                status = RecordingStatus.INTERRUPTED,
                createdAppVersion = "test",
                activeSlot = ACTIVE_SESSION_SLOT,
            )
        }
    }

    @Test
    fun pointRejectsNonCanonicalCoordinatesAndAccuracy() {
        assertThrows(IllegalArgumentException::class.java) {
            point(latitude = 91.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            point(longitude = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            point(horizontalAccuracy = -1.0)
        }
    }

    private fun point(
        latitude: Double = 25.0,
        longitude: Double = 121.0,
        horizontalAccuracy: Double = 5.0,
    ) = TrackPointEntity(
        sessionId = 1,
        segmentId = 1,
        sequence = 0,
        timestamp = 1,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracy = horizontalAccuracy,
    )
}