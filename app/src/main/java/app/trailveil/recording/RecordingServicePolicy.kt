package app.trailveil.recording

import app.trailveil.data.location.LocationBackpressureException
import app.trailveil.data.recording.RecordingLifecycle

/** Android-free decisions used by the service command loop and covered by JVM tests. */
internal object RecordingServicePolicy {
    /** A full fix queue is a named, durable interruption rather than a generic stream failure. */
    fun locationBackpressureTerminalReason(failure: Throwable): String? =
        LOCATION_BACKPRESSURE_TERMINAL_REASON.takeIf {
            failure is LocationBackpressureException
        }

    /** A notification action is bound to one durable session and may never stop its replacement. */
    fun notificationStopDecision(
        requestedSessionId: Long?,
        currentSessionId: Long?,
        lifecycle: RecordingLifecycle?,
    ): NotificationStopDecision = when {
        currentSessionId == null ||
            lifecycle !in setOf(RecordingLifecycle.STARTING, RecordingLifecycle.ACTIVE) ->
            NotificationStopDecision.NO_ACTIVE_SESSION
        requestedSessionId != null && requestedSessionId != currentSessionId ->
            NotificationStopDecision.IGNORE_STALE_ACTION
        else -> NotificationStopDecision.STOP_CURRENT_SESSION
    }
}

internal const val LOCATION_BACKPRESSURE_TERMINAL_REASON = "location_backpressure"

internal enum class NotificationStopDecision {
    STOP_CURRENT_SESSION,
    IGNORE_STALE_ACTION,
    NO_ACTIVE_SESSION,
}
