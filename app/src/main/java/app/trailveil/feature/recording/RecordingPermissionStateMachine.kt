package app.trailveil.feature.recording

/**
 * Pure decision model for the user-initiated recording permission flow.
 *
 * Platform reads and side effects stay outside this class. In particular, callers must refresh
 * [RecordingPermissionSnapshot] after an activity-result callback or a return from Settings;
 * callback booleans alone can be stale after a process recreation.
 */
internal object RecordingPermissionStateMachine {
    fun uiState(snapshot: RecordingPermissionSnapshot): RecordingPermissionUiState =
        RecordingPermissionUiState(
            location = locationState(snapshot),
            systemLocation = if (snapshot.systemLocationEnabled) {
                SystemLocationState.ENABLED
            } else {
                SystemLocationState.DISABLED
            },
            notifications = notificationState(snapshot),
        )

    /** Returns exactly one next effect for a deliberate tap on "Start exploration". */
    fun actionForExplicitStart(snapshot: RecordingPermissionSnapshot): RecordingStartAction = when {
        !snapshot.activityResumed -> RecordingStartAction.WaitForResumedActivity
        !snapshot.fineLocationGranted -> locationAction(snapshot)
        !snapshot.systemLocationEnabled -> RecordingStartAction.OpenSystemLocationSettings
        snapshot.notificationPermissionGranted -> RecordingStartAction.StartRecording
        snapshot.notificationRationaleRequired ->
            RecordingStartAction.ShowNotificationRationaleThenStart
        snapshot.notificationRequestPreviouslyLaunched -> RecordingStartAction.StartRecording
        else -> RecordingStartAction.RequestNotificationThenStart
    }

    /** Re-evaluates the location gate after the combined location request completes. */
    fun actionAfterLocationPermissionResult(
        refreshedSnapshot: RecordingPermissionSnapshot,
    ): RecordingStartAction = actionForExplicitStart(refreshedSnapshot)

    /**
     * Notification permission is deliberately non-gating for a location foreground service.
     * The UI may record the response for its explanatory state, but must start regardless of it.
     */
    fun actionAfterNotificationPermissionResult(
        notificationGranted: Boolean,
    ): RecordingStartAction = RecordingStartAction.StartRecording

    /** The confirmation button in a location rationale surface always repeats the joint request. */
    fun actionAfterLocationRationaleAccepted(): RecordingStartAction =
        RecordingStartAction.RequestPreciseLocation

    /** The precise-location explanation precedes the single allowed approximate-to-precise upgrade. */
    fun actionAfterPreciseLocationRationaleAccepted(): RecordingStartAction =
        RecordingStartAction.RequestPreciseLocation

    /** A notification rationale is informational; accepting it proceeds to its permission prompt. */
    fun actionAfterNotificationRationaleAccepted(): RecordingStartAction =
        RecordingStartAction.RequestNotificationThenStart

    private fun locationState(snapshot: RecordingPermissionSnapshot): LocationAccessState = when {
        snapshot.fineLocationGranted -> LocationAccessState.PRECISE
        snapshot.coarseLocationGranted -> when {
            snapshot.fineLocationRationaleRequired -> LocationAccessState.APPROXIMATE_CAN_RETRY
            !snapshot.preciseUpgradeRequestPreviouslyLaunched ->
                LocationAccessState.APPROXIMATE_CAN_RETRY
            else -> LocationAccessState.APPROXIMATE_OPEN_SETTINGS
        }
        snapshot.locationRationaleRequired -> LocationAccessState.DENIED_SHOW_RATIONALE
        snapshot.locationRequestPreviouslyLaunched && !snapshot.locationRetryPreviouslyLaunched ->
            LocationAccessState.DENIED_SHOW_RATIONALE
        snapshot.locationRequestPreviouslyLaunched -> LocationAccessState.DENIED_OPEN_SETTINGS
        else -> LocationAccessState.NOT_REQUESTED
    }

    private fun notificationState(snapshot: RecordingPermissionSnapshot): NotificationAccessState = when {
        snapshot.notificationPermissionGranted && snapshot.notificationsEnabled ->
            NotificationAccessState.GRANTED
        snapshot.notificationPermissionGranted -> NotificationAccessState.DISABLED_OPEN_SETTINGS
        snapshot.notificationRationaleRequired -> NotificationAccessState.DENIED_SHOW_RATIONALE
        snapshot.notificationRequestPreviouslyLaunched -> NotificationAccessState.DENIED_OPEN_SETTINGS
        else -> NotificationAccessState.NOT_REQUESTED
    }

    private fun locationAction(snapshot: RecordingPermissionSnapshot): RecordingStartAction = when (
        locationState(snapshot)
    ) {
        LocationAccessState.NOT_REQUESTED -> RecordingStartAction.RequestPreciseLocation
        LocationAccessState.APPROXIMATE_CAN_RETRY ->
            RecordingStartAction.ShowPreciseLocationRationale
        LocationAccessState.DENIED_SHOW_RATIONALE -> RecordingStartAction.ShowLocationRationale
        LocationAccessState.APPROXIMATE_OPEN_SETTINGS,
        LocationAccessState.DENIED_OPEN_SETTINGS,
        -> RecordingStartAction.OpenAppLocationPermissionSettings

        LocationAccessState.PRECISE -> error("Fine location is handled before locationAction")
    }
}

/** Values are read from the platform and local non-critical preference markers by the UI layer. */
internal data class RecordingPermissionSnapshot(
    val activityResumed: Boolean,
    val fineLocationGranted: Boolean,
    val coarseLocationGranted: Boolean,
    val systemLocationEnabled: Boolean,
    /** Whether the runtime POST_NOTIFICATIONS permission is granted. */
    val notificationPermissionGranted: Boolean,
    /** Whether NotificationManager currently allows app notification delivery. */
    val notificationsEnabled: Boolean,
    val fineLocationRationaleRequired: Boolean,
    val coarseLocationRationaleRequired: Boolean,
    val notificationRationaleRequired: Boolean,
    val locationRequestPreviouslyLaunched: Boolean,
    val locationRetryPreviouslyLaunched: Boolean,
    val preciseUpgradeRequestPreviouslyLaunched: Boolean,
    val notificationRequestPreviouslyLaunched: Boolean,
) {
    val locationRationaleRequired: Boolean
        get() = fineLocationRationaleRequired || coarseLocationRationaleRequired
}

internal data class RecordingPermissionUiState(
    val location: LocationAccessState,
    val systemLocation: SystemLocationState,
    val notifications: NotificationAccessState,
)

internal enum class LocationAccessState {
    NOT_REQUESTED,
    DENIED_SHOW_RATIONALE,
    DENIED_OPEN_SETTINGS,
    APPROXIMATE_CAN_RETRY,
    APPROXIMATE_OPEN_SETTINGS,
    PRECISE,
}

internal enum class SystemLocationState { ENABLED, DISABLED }

internal enum class NotificationAccessState {
    NOT_REQUESTED,
    DENIED_SHOW_RATIONALE,
    DENIED_OPEN_SETTINGS,
    /** Runtime permission is granted, but the app's notification delivery is switched off. */
    DISABLED_OPEN_SETTINGS,
    GRANTED,
}

/**
 * Saveable UI continuation for the one notification request that follows an explicit Start.
 *
 * The pending marker is deliberately not enough to resume recording: only the Activity Result
 * callback can move [AWAITING_RESULT] to [RESULT_OBSERVED]. Keeping [REQUESTING_PERMISSION]
 * separate closes the cancellation window while the request-history marker is being persisted;
 * after recreation that phase retries the marker write and launches the prompt instead of silently
 * losing the user's action.
 */
internal enum class NotificationStartContinuation {
    IDLE,
    REQUESTING_PERMISSION,
    AWAITING_RESULT,
    RESULT_OBSERVED,
    ;

    fun begin(): NotificationStartContinuation = when (this) {
        IDLE -> REQUESTING_PERMISSION
        else -> this
    }

    fun permissionRequestLaunched(): NotificationStartContinuation = when (this) {
        REQUESTING_PERMISSION -> AWAITING_RESULT
        else -> this
    }

    fun resultObserved(): NotificationStartContinuation = when (this) {
        AWAITING_RESULT -> RESULT_OBSERVED
        else -> this
    }

    fun canResumeStart(activityResumed: Boolean): Boolean =
        this == RESULT_OBSERVED && activityResumed

    val keepsStartPending: Boolean
        get() = this != IDLE
}

/** Informational notification actions may not race or mutate an explicit pending Start. */
internal fun canLaunchInformationalNotificationAction(
    starting: Boolean,
    continuation: NotificationStartContinuation,
): Boolean = !starting && !continuation.keepsStartPending

/** Effects interpreted by the Android/Compose coordinator; this file deliberately has no Android dependency. */
internal sealed interface RecordingStartAction {
    data object WaitForResumedActivity : RecordingStartAction
    data object RequestPreciseLocation : RecordingStartAction
    data object ShowLocationRationale : RecordingStartAction
    data object ShowPreciseLocationRationale : RecordingStartAction
    data object OpenAppLocationPermissionSettings : RecordingStartAction
    data object OpenSystemLocationSettings : RecordingStartAction
    data object RequestNotificationThenStart : RecordingStartAction
    data object ShowNotificationRationaleThenStart : RecordingStartAction
    data object StartRecording : RecordingStartAction
}

/** The coordinator must issue this exact, single request on every location request/retry. */
internal val preciseLocationPermissionRequest: List<String> = listOf(
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
)
