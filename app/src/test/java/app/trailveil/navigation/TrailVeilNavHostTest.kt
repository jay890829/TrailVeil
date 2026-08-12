package app.trailveil.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrailVeilNavHostTest {
    @Test
    fun historyBackTransitionsUseTheApprovedDuration() {
        assertEquals(250, HistoryBackTransitionDurationMillis)
    }

    @Test
    fun historyDetailRouteUsesPositivePersistedSessionId() {
        assertEquals("history/42", historyDetailRoute(42L))
        assertThrows(IllegalArgumentException::class.java) {
            historyDetailRoute(0L)
        }
    }
}
