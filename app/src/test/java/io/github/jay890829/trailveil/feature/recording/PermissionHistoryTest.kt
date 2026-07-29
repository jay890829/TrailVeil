package io.github.jay890829.trailveil.feature.recording

import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionHistoryTest {
    @Test
    fun unreadableHistoryFallsBackWithoutReRequestingPermissions() {
        val fallback = PermissionHistory.ConservativeFallback

        assertTrue(fallback.hasSeenIntroduction)
        assertTrue(fallback.hasRequestedLocation)
        assertTrue(fallback.hasRetriedLocation)
        assertTrue(fallback.hasRequestedPreciseUpgrade)
        assertTrue(fallback.hasRequestedNotifications)
    }
}
