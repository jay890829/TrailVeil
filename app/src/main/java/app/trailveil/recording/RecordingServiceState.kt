package app.trailveil.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped truth published by the foreground service after it has accepted a stop command.
 * Room remains the durable lifecycle authority; this narrow state only covers the in-flight gap
 * before the terminal Room transaction commits.
 */
internal class RecordingServiceState {
    private val mutableStoppingSessionId = MutableStateFlow<Long?>(null)
    private val mutableLatestAcceptedLocation =
        MutableStateFlow<RecordingServiceLocation?>(null)

    val stoppingSessionId: StateFlow<Long?> = mutableStoppingSessionId.asStateFlow()
    val latestAcceptedLocation: StateFlow<RecordingServiceLocation?> =
        mutableLatestAcceptedLocation.asStateFlow()

    fun markStopping(sessionId: Long) {
        require(sessionId > 0L) { "sessionId must be positive" }
        mutableStoppingSessionId.value = sessionId
    }

    fun clearStopping(sessionId: Long? = null) {
        if (sessionId == null || mutableStoppingSessionId.value == sessionId) {
            mutableStoppingSessionId.value = null
        }
    }

    fun publishAcceptedLocation(location: RecordingServiceLocation) {
        mutableLatestAcceptedLocation.value = location
    }

    fun clearLocation(sessionId: Long? = null) {
        if (sessionId == null || mutableLatestAcceptedLocation.value?.sessionId == sessionId) {
            mutableLatestAcceptedLocation.value = null
        }
    }
}

/** Ephemeral location truth emitted only after the service has durably accepted the fix. */
internal data class RecordingServiceLocation(
    val sessionId: Long,
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(sessionId > 0L) { "sessionId must be positive" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude is invalid" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude is invalid" }
    }
}
