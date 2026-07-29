package io.github.jay890829.trailveil.recording

import io.github.jay890829.trailveil.data.recording.RecordingLifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingServicePolicyTest {
    @Test fun `matching notification stop may finish active session`() {
        assertEquals(NotificationStopDecision.STOP_CURRENT_SESSION, RecordingServicePolicy.notificationStopDecision(42L, 42L, RecordingLifecycle.ACTIVE))
    }

    @Test fun `old notification cannot stop replacement session`() {
        assertEquals(NotificationStopDecision.IGNORE_STALE_ACTION, RecordingServicePolicy.notificationStopDecision(42L, 43L, RecordingLifecycle.ACTIVE))
    }

    @Test fun `generic foreground notification may stop pending session`() {
        assertEquals(NotificationStopDecision.STOP_CURRENT_SESSION, RecordingServicePolicy.notificationStopDecision(null, 42L, RecordingLifecycle.STARTING))
    }

    @Test fun `terminal session is never stopped again`() {
        assertEquals(NotificationStopDecision.NO_ACTIVE_SESSION, RecordingServicePolicy.notificationStopDecision(42L, 42L, RecordingLifecycle.STOPPED))
    }
}