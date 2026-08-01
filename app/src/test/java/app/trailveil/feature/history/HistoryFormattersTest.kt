package app.trailveil.feature.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFormattersTest {
    @Test
    fun formatsPersistedInstantsAndDurationsDeterministicallyInUtc() {
        assertEquals("1970-01-01 00:00 UTC", HistoryFormatters.instant(0L))
        assertEquals("2m 05s", HistoryFormatters.duration(125_000L))
        assertEquals("0s", HistoryFormatters.duration(-1L))
    }

    @Test
    fun formatsDistanceWithoutLocaleDependentCoordinates() {
        assertEquals("850 m", HistoryFormatters.distance(850.0))
        assertEquals("1.25 km", HistoryFormatters.distance(1_250.0))
    }
}
