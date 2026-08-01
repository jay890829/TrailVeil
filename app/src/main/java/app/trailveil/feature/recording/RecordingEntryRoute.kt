package app.trailveil.feature.recording

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.trailveil.TrailVeilApplication
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.recording.RecordingForegroundService
import app.trailveil.recording.RecordingStartBlocker
import app.trailveil.recording.RecordingStartOutcome
import app.trailveil.map.fog.FogRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RecordingEntryRoute(activity: ComponentActivity) {
    val scope = rememberCoroutineScope()
    val historyStore = remember(activity.applicationContext) {
        PermissionHistoryStore(activity.applicationContext)
    }
    val appContainer = remember(activity.application) {
        (activity.application as TrailVeilApplication).appContainer
    }
    val controller = remember(activity, appContainer) {
        appContainer.recordingController(activity)
    }

    var history by remember { mutableStateOf<PermissionHistory?>(null) }
    var activityResumed by remember {
        mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var platformRefresh by remember { mutableIntStateOf(0) }
    var locationResultVersion by remember { mutableIntStateOf(0) }
    var consumedLocationResultVersion by remember { mutableIntStateOf(0) }
    var notificationResultVersion by remember { mutableIntStateOf(0) }
    var consumedNotificationResultVersion by remember { mutableIntStateOf(0) }
    var startAfterNotificationResult by remember { mutableStateOf(false) }
    var locationNotice by rememberSaveable { mutableStateOf<LocationNotice?>(null) }
    var startNotice by rememberSaveable { mutableStateOf<RecordingStartNotice?>(null) }
    var activeSessionId by remember { mutableStateOf<Long?>(null) }
    var starting by remember { mutableStateOf(false) }
    var fogRuntime by remember { mutableStateOf<FogRuntime?>(null) }

    LaunchedEffect(historyStore) {
        historyStore.history.collectLatest { history = it }
    }

    LaunchedEffect(appContainer) {
        fogRuntime = withContext(Dispatchers.IO) { appContainer.fogRuntime() }
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    activityResumed = true
                    platformRefresh += 1
                }
                Lifecycle.Event.ON_PAUSE -> activityResumed = false
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    val currentHistory = history
    val snapshot = remember(currentHistory, activityResumed, platformRefresh) {
        currentHistory?.let {
            activity.readRecordingPermissionSnapshot(
                history = it,
                activityResumed = activityResumed,
            )
        }
    }
    LaunchedEffect(currentHistory, platformRefresh, activityResumed) {
        if (currentHistory != null && activityResumed) {
            val repositoryState = appContainer.recordingRepository.state()
            activeSessionId = repositoryState.sessionId.takeIf {
                repositoryState.lifecycle == RecordingLifecycle.STARTING ||
                    repositoryState.lifecycle == RecordingLifecycle.ACTIVE
            }
        }
    }

    val permissionUi = snapshot?.let(RecordingPermissionStateMachine::uiState)
    val notificationNotice = when (permissionUi?.notifications) {
        NotificationAccessState.DENIED_SHOW_RATIONALE -> NotificationNotice.RATIONALE
        NotificationAccessState.DENIED_OPEN_SETTINGS,
        NotificationAccessState.DISABLED_OPEN_SETTINGS,
        -> NotificationNotice.SETTINGS
        else -> null
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        platformRefresh += 1
        locationResultVersion += 1
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        platformRefresh += 1
        notificationResultVersion += 1
    }

    suspend fun startRecording() {
        starting = true
        locationNotice = null
        startNotice = null
        val outcome = controller.startFromVisibleActivity(
            activityVisible = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        startNotice = when (outcome) {
            is RecordingStartOutcome.ServiceRequested -> {
                activeSessionId = outcome.sessionId
                RecordingStartNotice.STARTED
            }
            is RecordingStartOutcome.PersistenceFailure ->
                RecordingStartNotice.PERSISTENCE_FAILURE
            is RecordingStartOutcome.LaunchFailure -> RecordingStartNotice.LAUNCH_FAILURE
            is RecordingStartOutcome.Blocked -> when (outcome.blocker) {
                RecordingStartBlocker.ACTIVITY_NOT_VISIBLE ->
                    RecordingStartNotice.ACTIVITY_NOT_VISIBLE
                RecordingStartBlocker.MISSING_LOCATION_PERMISSION -> {
                    locationNotice = LocationNotice.PERMISSION_SETTINGS
                    null
                }
                RecordingStartBlocker.MISSING_FINE_LOCATION -> {
                    locationNotice = LocationNotice.PRECISE_SETTINGS
                    null
                }
                RecordingStartBlocker.LOCATION_DISABLED -> {
                    locationNotice = LocationNotice.LOCATION_SERVICES
                    null
                }
            }
        }
        starting = false
    }

    fun dispatch(action: RecordingStartAction) {
        when (action) {
            RecordingStartAction.WaitForResumedActivity -> {
                starting = false
                startNotice = RecordingStartNotice.ACTIVITY_NOT_VISIBLE
            }
            RecordingStartAction.RequestPreciseLocation -> scope.launch {
                starting = true
                try {
                    historyStore.markLocationRequested()
                    locationPermissionLauncher.launch(
                        preciseLocationPermissionRequest.toTypedArray(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    starting = false
                    startNotice = RecordingStartNotice.PERSISTENCE_FAILURE
                }
            }
            RecordingStartAction.ShowLocationRationale -> {
                starting = false
                locationNotice = LocationNotice.RATIONALE
            }
            RecordingStartAction.ShowPreciseLocationRationale -> {
                starting = false
                locationNotice = LocationNotice.PRECISE_RATIONALE
            }
            RecordingStartAction.OpenAppLocationPermissionSettings -> {
                starting = false
                locationNotice = when (permissionUi?.location) {
                    LocationAccessState.APPROXIMATE_OPEN_SETTINGS ->
                        LocationNotice.PRECISE_SETTINGS
                    else -> LocationNotice.PERMISSION_SETTINGS
                }
            }
            RecordingStartAction.OpenSystemLocationSettings -> {
                starting = false
                locationNotice = LocationNotice.LOCATION_SERVICES
            }
            RecordingStartAction.RequestNotificationThenStart -> scope.launch {
                starting = true
                startAfterNotificationResult = true
                try {
                    historyStore.markNotificationsRequested()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    startAfterNotificationResult = false
                    starting = false
                    startNotice = RecordingStartNotice.PERSISTENCE_FAILURE
                }
            }
            RecordingStartAction.ShowNotificationRationaleThenStart -> scope.launch {
                startAfterNotificationResult = false
                startRecording()
            }
            RecordingStartAction.StartRecording -> scope.launch {
                startAfterNotificationResult = false
                startRecording()
            }
        }
    }

    LaunchedEffect(locationResultVersion, activityResumed) {
        if (
            !activityResumed ||
            locationResultVersion == 0 ||
            locationResultVersion == consumedLocationResultVersion
        ) {
            return@LaunchedEffect
        }
        consumedLocationResultVersion = locationResultVersion
        val refreshedHistory = historyStore.current()
        val refreshed = activity.readRecordingPermissionSnapshot(
            history = refreshedHistory,
            activityResumed = true,
        )
        dispatch(
            RecordingPermissionStateMachine.actionAfterLocationPermissionResult(refreshed),
        )
    }

    LaunchedEffect(notificationResultVersion, activityResumed) {
        if (
            !activityResumed ||
            notificationResultVersion == 0 ||
            notificationResultVersion == consumedNotificationResultVersion
        ) {
            return@LaunchedEffect
        }
        consumedNotificationResultVersion = notificationResultVersion
        val shouldStart = startAfterNotificationResult
        startAfterNotificationResult = false
        starting = false
        if (shouldStart) {
            dispatch(
                RecordingPermissionStateMachine.actionAfterNotificationPermissionResult(
                    notificationGranted = activity.hasPermission(
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                ),
            )
        }
    }

    LaunchedEffect(snapshot, platformRefresh) {
        if (locationNotice == null || snapshot == null) return@LaunchedEffect
        val refreshedUi = RecordingPermissionStateMachine.uiState(snapshot)
        locationNotice = when {
            refreshedUi.location == LocationAccessState.PRECISE &&
                refreshedUi.systemLocation == SystemLocationState.ENABLED -> null
            refreshedUi.location == LocationAccessState.PRECISE ->
                LocationNotice.LOCATION_SERVICES
            refreshedUi.location == LocationAccessState.APPROXIMATE_CAN_RETRY ->
                LocationNotice.PRECISE_RATIONALE
            refreshedUi.location == LocationAccessState.APPROXIMATE_OPEN_SETTINGS ->
                LocationNotice.PRECISE_SETTINGS
            refreshedUi.location == LocationAccessState.DENIED_SHOW_RATIONALE ->
                LocationNotice.RATIONALE
            else -> LocationNotice.PERMISSION_SETTINGS
        }
    }

    RecordingEntryScreen(
        state = RecordingEntryUiState(
            loading = currentHistory == null,
            firstVisit = currentHistory?.hasSeenIntroduction != true,
            locationNotice = locationNotice,
            notificationNotice = notificationNotice,
            startNotice = startNotice,
            recordingActive = activeSessionId != null,
            starting = starting,
        ),
        onStart = {
            if (!starting) {
                starting = true
                startNotice = null
                locationNotice = null
                scope.launch {
                    try {
                        historyStore.markIntroductionSeen()
                        val refreshedHistory = historyStore.current()
                        val refreshed = activity.readRecordingPermissionSnapshot(
                            history = refreshedHistory,
                            activityResumed = activity.lifecycle.currentState
                                .isAtLeast(Lifecycle.State.RESUMED),
                        )
                        dispatch(
                            RecordingPermissionStateMachine.actionForExplicitStart(refreshed),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        starting = false
                        startNotice = RecordingStartNotice.PERSISTENCE_FAILURE
                    }
                }
            }
        },
        onStop = {
            val sessionId = activeSessionId
            if (!starting && sessionId != null) {
                starting = true
                try {
                    RecordingForegroundService.stopFromVisibleActivity(activity, sessionId)
                    activeSessionId = null
                    startNotice = RecordingStartNotice.STOP_REQUESTED
                } catch (_: RuntimeException) {
                    startNotice = RecordingStartNotice.LAUNCH_FAILURE
                } finally {
                    starting = false
                }
            }
        },
        onLocationAction = {
            when (locationNotice) {
                LocationNotice.RATIONALE,
                LocationNotice.PRECISE_RATIONALE,
                -> if (!starting) {
                    starting = true
                    val preciseUpgrade = locationNotice == LocationNotice.PRECISE_RATIONALE
                    scope.launch {
                        try {
                            if (preciseUpgrade) {
                                historyStore.markPreciseUpgradeRequested()
                            } else {
                                historyStore.markLocationRetried()
                            }
                            locationPermissionLauncher.launch(
                                preciseLocationPermissionRequest.toTypedArray(),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            starting = false
                            startNotice = RecordingStartNotice.PERSISTENCE_FAILURE
                        }
                    }
                }
                LocationNotice.PERMISSION_SETTINGS,
                LocationNotice.PRECISE_SETTINGS,
                -> activity.openAppDetailsSettings()
                LocationNotice.LOCATION_SERVICES ->
                    activity.openSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                null -> Unit
            }
        },
        onDismissLocationNotice = {
            locationNotice = null
            starting = false
        },
        onNotificationAction = {
            when (notificationNotice) {
                NotificationNotice.RATIONALE -> if (!starting) {
                    starting = true
                    startAfterNotificationResult = false
                    scope.launch {
                        try {
                            historyStore.markNotificationsRequested()
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            starting = false
                            startNotice = RecordingStartNotice.PERSISTENCE_FAILURE
                        }
                    }
                }
                NotificationNotice.SETTINGS -> activity.openNotificationSettings()
                null -> Unit
            }
        },
        fogRuntime = fogRuntime,
        fogRequired = true,
    )
}

private fun Activity.readRecordingPermissionSnapshot(
    history: PermissionHistory,
    activityResumed: Boolean,
): RecordingPermissionSnapshot {
    val notificationPermissionGranted =
        hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    return RecordingPermissionSnapshot(
        activityResumed = activityResumed,
        fineLocationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        coarseLocationGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        systemLocationEnabled =
            requireNotNull(getSystemService(LocationManager::class.java)).isLocationEnabled,
        notificationPermissionGranted = notificationPermissionGranted,
        notificationsEnabled =
            NotificationManagerCompat.from(this).areNotificationsEnabled(),
        fineLocationRationaleRequired = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ),
        coarseLocationRationaleRequired = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
        notificationRationaleRequired = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ),
        locationRequestPreviouslyLaunched = history.hasRequestedLocation,
        locationRetryPreviouslyLaunched = history.hasRetriedLocation,
        preciseUpgradeRequestPreviouslyLaunched = history.hasRequestedPreciseUpgrade,
        notificationRequestPreviouslyLaunched = history.hasRequestedNotifications,
    )
}

private fun Activity.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Activity.openAppDetailsSettings() {
    openSafely(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

private fun Activity.openNotificationSettings() {
    openSafely(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
}

private fun Activity.openSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
