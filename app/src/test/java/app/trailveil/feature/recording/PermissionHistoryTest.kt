package app.trailveil.feature.recording

import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun anInjectedIoReadFailureActuallyUsesTheConservativeFallback() = runTest {
        val unreadable: Flow<Preferences> = flow {
            throw IOException("injected unreadable permission history")
        }

        assertEquals(
            PermissionHistory.ConservativeFallback,
            unreadable.toPermissionHistory().first(),
        )
    }

    @Test
    fun aNonIoReadFailureIsNeverHiddenAsPermissionHistory() = runTest {
        val broken: Flow<Preferences> = flow {
            throw IllegalStateException("injected programming failure")
        }

        try {
            broken.toPermissionHistory().first()
            fail("Expected the non-I/O failure to remain visible")
        } catch (failure: IllegalStateException) {
            assertEquals("injected programming failure", failure.message)
        }
    }
}
