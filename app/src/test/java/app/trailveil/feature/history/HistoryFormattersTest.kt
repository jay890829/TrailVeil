package app.trailveil.feature.history

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFormattersTest {
    @Test
    fun readsPersistedInstantsBackInTheGivenZone() {
        // The stored value never moves; only the reading of it does.
        assertEquals("1970-01-01 08:00", HistoryFormatters.instant(0L, TAIPEI))
        assertEquals("1970-01-01 00:00", HistoryFormatters.instant(0L, ZoneId.of("UTC")))
        assertEquals("1969-12-31 19:00", HistoryFormatters.instant(0L, ZoneId.of("-05:00")))
    }

    @Test
    fun formatsDurationsDeterministically() {
        assertEquals("2m 05s", HistoryFormatters.duration(125_000L))
        assertEquals("0s", HistoryFormatters.duration(-1L))
    }

    @Test
    fun formatsDistanceWithoutLocaleDependentCoordinates() {
        assertEquals("850 m", HistoryFormatters.distance(850.0))
        assertEquals("1.25 km", HistoryFormatters.distance(1_250.0))
    }

    private companion object {
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
