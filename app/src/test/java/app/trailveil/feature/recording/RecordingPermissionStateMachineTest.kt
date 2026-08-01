package app.trailveil.feature.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPermissionStateMachineTest {
    @Test
    fun `first explicit start requests fine and coarse together`() {
        assertEquals(
            RecordingStartAction.RequestPreciseLocation,
            RecordingPermissionStateMachine.actionForExplicitStart(snapshot()),
        )
        assertEquals(
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
            ),
            preciseLocationPermissionRequest,
        )
    }

    @Test
    fun `location denial with rationale explains before retrying joint request`() {
        val denied = snapshot(
            fineLocationRationaleRequired = true,
            locationRequestPreviouslyLaunched = true,
        )

        assertEquals(
            RecordingStartAction.ShowLocationRationale,
            RecordingPermissionStateMachine.actionForExplicitStart(denied),
        )
        assertEquals(
            RecordingStartAction.RequestPreciseLocation,
            RecordingPermissionStateMachine.actionAfterLocationRationaleAccepted(),
        )
        assertEquals(
            LocationAccessState.DENIED_SHOW_RATIONALE,
            RecordingPermissionStateMachine.uiState(denied).location,
        )
    }

    @Test
    fun `location permanent denial routes to app settings after the one retry`() {
        val denied = snapshot(
            locationRequestPreviouslyLaunched = true,
            locationRetryPreviouslyLaunched = true,
        )

        assertEquals(
            RecordingStartAction.OpenAppLocationPermissionSettings,
            RecordingPermissionStateMachine.actionForExplicitStart(denied),
        )
        assertEquals(
            LocationAccessState.DENIED_OPEN_SETTINGS,
            RecordingPermissionStateMachine.uiState(denied).location,
        )
    }

    @Test
    fun `coarse only is never sufficient to start recording after precise upgrade retry`() {
        val approximate = snapshot(
            coarseLocationGranted = true,
            preciseUpgradeRequestPreviouslyLaunched = true,
        )

        assertEquals(
            RecordingStartAction.OpenAppLocationPermissionSettings,
            RecordingPermissionStateMachine.actionForExplicitStart(approximate),
        )
        assertEquals(
            LocationAccessState.APPROXIMATE_OPEN_SETTINGS,
            RecordingPermissionStateMachine.uiState(approximate).location,
        )
    }

    @Test
    fun `coarse only gets an explanation before its one precise upgrade request`() {
        val approximate = snapshot(coarseLocationGranted = true)

        assertEquals(
            RecordingStartAction.ShowPreciseLocationRationale,
            RecordingPermissionStateMachine.actionForExplicitStart(approximate),
        )
        assertEquals(
            RecordingStartAction.RequestPreciseLocation,
            RecordingPermissionStateMachine.actionAfterPreciseLocationRationaleAccepted(),
        )
        assertEquals(
            LocationAccessState.APPROXIMATE_CAN_RETRY,
            RecordingPermissionStateMachine.uiState(approximate).location,
        )
    }

    @Test
    fun `dismissed first location dialog is not mistaken for permanent denial`() {
        val dismissed = snapshot(locationRequestPreviouslyLaunched = true)

        assertEquals(
            RecordingStartAction.ShowLocationRationale,
            RecordingPermissionStateMachine.actionForExplicitStart(dismissed),
        )
        assertEquals(
            LocationAccessState.DENIED_SHOW_RATIONALE,
            RecordingPermissionStateMachine.uiState(dismissed).location,
        )
    }

    @Test
    fun `system location setting is an actionable gate after precise grant`() {
        val disabled = snapshot(fineLocationGranted = true)

        assertEquals(
            RecordingStartAction.OpenSystemLocationSettings,
            RecordingPermissionStateMachine.actionForExplicitStart(disabled),
        )
        assertEquals(
            SystemLocationState.DISABLED,
            RecordingPermissionStateMachine.uiState(disabled).systemLocation,
        )
    }

    @Test
    fun `precise enabled location requests notification before starting`() {
        assertEquals(
            RecordingStartAction.RequestNotificationThenStart,
            RecordingPermissionStateMachine.actionForExplicitStart(readyForNotification()),
        )
    }

    @Test
    fun `notification rationale is shown only after required location gates pass`() {
        val denied = readyForNotification(
            notificationRationaleRequired = true,
            notificationRequestPreviouslyLaunched = true,
        )

        assertEquals(
            RecordingStartAction.ShowNotificationRationaleThenStart,
            RecordingPermissionStateMachine.actionForExplicitStart(denied),
        )
        assertEquals(
            RecordingStartAction.RequestNotificationThenStart,
            RecordingPermissionStateMachine.actionAfterNotificationRationaleAccepted(),
        )
        assertEquals(
            NotificationAccessState.DENIED_SHOW_RATIONALE,
            RecordingPermissionStateMachine.uiState(denied).notifications,
        )
    }

    @Test
    fun `notification permanent denial never blocks a ready recording`() {
        val denied = readyForNotification(notificationRequestPreviouslyLaunched = true)

        assertEquals(
            RecordingStartAction.StartRecording,
            RecordingPermissionStateMachine.actionForExplicitStart(denied),
        )
        assertEquals(
            NotificationAccessState.DENIED_OPEN_SETTINGS,
            RecordingPermissionStateMachine.uiState(denied).notifications,
        )
    }

    @Test
    fun `runtime notification grant skips launcher even when notification delivery is disabled`() {
        val deliveryDisabled = readyForNotification(
            notificationPermissionGranted = true,
            notificationsEnabled = false,
        )

        assertEquals(
            RecordingStartAction.StartRecording,
            RecordingPermissionStateMachine.actionForExplicitStart(deliveryDisabled),
        )
        assertEquals(
            NotificationAccessState.DISABLED_OPEN_SETTINGS,
            RecordingPermissionStateMachine.uiState(deliveryDisabled).notifications,
        )
    }
    @Test
    fun `every notification result starts recording without inspecting grant value`() {
        assertEquals(
            RecordingStartAction.StartRecording,
            RecordingPermissionStateMachine.actionAfterNotificationPermissionResult(true),
        )
        assertEquals(
            RecordingStartAction.StartRecording,
            RecordingPermissionStateMachine.actionAfterNotificationPermissionResult(false),
        )
    }

    @Test
    fun `location result is refreshed through all gates rather than trusting callback map`() {
        assertEquals(
            RecordingStartAction.RequestNotificationThenStart,
            RecordingPermissionStateMachine.actionAfterLocationPermissionResult(readyForNotification()),
        )
        assertEquals(
            RecordingStartAction.OpenSystemLocationSettings,
            RecordingPermissionStateMachine.actionAfterLocationPermissionResult(
                readyForNotification(systemLocationEnabled = false),
            ),
        )
    }

    @Test
    fun `background activity never emits a permission request or recording start`() {
        assertEquals(
            RecordingStartAction.WaitForResumedActivity,
            RecordingPermissionStateMachine.actionForExplicitStart(
                readyForNotification(
                    activityResumed = false,
                    notificationPermissionGranted = true,
                ),
            ),
        )
    }

    @Test
    fun `granted precise location and notifications is ready to start`() {
        val ready = readyForNotification(notificationPermissionGranted = true)

        assertEquals(
            RecordingStartAction.StartRecording,
            RecordingPermissionStateMachine.actionForExplicitStart(ready),
        )
        assertEquals(LocationAccessState.PRECISE, RecordingPermissionStateMachine.uiState(ready).location)
        assertEquals(NotificationAccessState.GRANTED, RecordingPermissionStateMachine.uiState(ready).notifications)
        assertEquals(SystemLocationState.ENABLED, RecordingPermissionStateMachine.uiState(ready).systemLocation)
    }

    private fun readyForNotification(
        activityResumed: Boolean = true,
        systemLocationEnabled: Boolean = true,
        notificationPermissionGranted: Boolean = false,
        notificationsEnabled: Boolean = true,
        notificationRationaleRequired: Boolean = false,
        notificationRequestPreviouslyLaunched: Boolean = false,
    ) = snapshot(
        activityResumed = activityResumed,
        fineLocationGranted = true,
        coarseLocationGranted = true,
        systemLocationEnabled = systemLocationEnabled,
        notificationPermissionGranted = notificationPermissionGranted,
        notificationsEnabled = notificationsEnabled,
        notificationRationaleRequired = notificationRationaleRequired,
        notificationRequestPreviouslyLaunched = notificationRequestPreviouslyLaunched,
    )

    private fun snapshot(
        activityResumed: Boolean = true,
        fineLocationGranted: Boolean = false,
        coarseLocationGranted: Boolean = false,
        systemLocationEnabled: Boolean = false,
        notificationPermissionGranted: Boolean = false,
        notificationsEnabled: Boolean = true,
        fineLocationRationaleRequired: Boolean = false,
        coarseLocationRationaleRequired: Boolean = false,
        notificationRationaleRequired: Boolean = false,
        locationRequestPreviouslyLaunched: Boolean = false,
        locationRetryPreviouslyLaunched: Boolean = false,
        preciseUpgradeRequestPreviouslyLaunched: Boolean = false,
        notificationRequestPreviouslyLaunched: Boolean = false,
    ) = RecordingPermissionSnapshot(
        activityResumed = activityResumed,
        fineLocationGranted = fineLocationGranted,
        coarseLocationGranted = coarseLocationGranted,
        systemLocationEnabled = systemLocationEnabled,
        notificationPermissionGranted = notificationPermissionGranted,
        notificationsEnabled = notificationsEnabled,
        fineLocationRationaleRequired = fineLocationRationaleRequired,
        coarseLocationRationaleRequired = coarseLocationRationaleRequired,
        notificationRationaleRequired = notificationRationaleRequired,
        locationRequestPreviouslyLaunched = locationRequestPreviouslyLaunched,
        locationRetryPreviouslyLaunched = locationRetryPreviouslyLaunched,
        preciseUpgradeRequestPreviouslyLaunched = preciseUpgradeRequestPreviouslyLaunched,
        notificationRequestPreviouslyLaunched = notificationRequestPreviouslyLaunched,
    )
}
