package app.trailveil.feature.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionHistoryTest {
    @Test
    fun unreadableHistoryFallsBackWithoutReRequestingPermissions() {
        val fallback = PermissionHistory.ConservativeFallback

        assertTrue(fallback.hasRequestedLocation)
        assertTrue(fallback.hasRetriedLocation)
        assertTrue(fallback.hasRequestedPreciseUpgrade)
        assertTrue(fallback.hasRequestedNotifications)
    }

    @Test
    fun unreadableHistoryStillShowsTheFirstRunDisclosure() {
        // Every other marker is safe when true, because true means "already asked". This one is
        // safe when false, because true means "do not show the disclosure" — the same value, the
        // opposite direction. Failing closed here costs a repeated dialog; failing open would let
        // someone start recording having never been told where their trail goes.
        assertFalse(PermissionHistory.ConservativeFallback.hasSeenIntroduction)
    }
}
