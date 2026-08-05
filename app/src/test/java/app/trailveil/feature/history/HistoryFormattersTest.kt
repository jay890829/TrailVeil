package app.trailveil.feature.history

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFormattersTest {
    @Test
    fun readsPersistedInstantsBackInTheGivenZoneAndSaysWhichZone() {
        // The stored value never moves; only the reading of it does — so the reading has to say
        // which zone it is, or the same instant renders as three unrelated unlabelled times.
        assertEquals("1970-01-01 08:00 +08:00", HistoryFormatters.instant(0L, TAIPEI))
        assertEquals("1970-01-01 00:00 Z", HistoryFormatters.instant(0L, ZoneId.of("UTC")))
        assertEquals("1969-12-31 19:00 -05:00", HistoryFormatters.instant(0L, ZoneId.of("-05:00")))
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
