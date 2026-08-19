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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.trailveil.TrailVeilApplication
import app.trailveil.R
import app.trailveil.data.history.RecordingLatestSessionSummary
import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.map.MapCameraRequest
import app.trailveil.map.fog.GeoPoint
import app.trailveil.recording.RecordingForegroundService
import app.trailveil.recording.RecordingStartBlocker
import app.trailveil.recording.RecordingResumeOutcome
import app.trailveil.recording.RecordingStartOutcome
import app.trailveil.recording.RecordingServiceLocation
import app.trailveil.map.fog.FogRuntime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RecordingEntryRoute(
    activity: ComponentActivity,
    onOpenHistory: () -> Unit = {},
) {
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
    var notificationStartContinuationName by rememberSaveable {
        mutableStateOf(NotificationStartContinuation.IDLE.name)
    }
    var notificationStartOperationIdValue by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationStartContinuation =
        NotificationStartContinuation.valueOf(notificationStartContinuationName)
    var locationNotice by rememberSaveable { mutableStateOf<LocationNotice?>(null) }
    var startNotice by rememberSaveable { mutableStateOf<RecordingStartNotice?>(null) }
    // When the user's action produced the notice, so an acknowledgement expires on its own age
    // rather than on how long this screen has been drawn.
    var startNoticeRaisedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var latestSessionSummary by remember { mutableStateOf<RecordingLatestSessionSummary?>(null) }
    // A null summary means both "no exploration exists" and "the newest one has not been read yet",
    // and the screen must not act on the second as if it were the first — that is how a live
    // recording could be offered a Start action instead of Stop.
    var latestHistoryLoaded by remember { mutableStateOf(false) }
    var stoppingSessionId by remember(appContainer) { mutableStateOf<Long?>(null) }
    var serviceLocation by remember(appContainer) {
        mutableStateOf<RecordingServiceLocation?>(null)
    }
    var cameraRequestId by remember { mutableLongStateOf(0L) }
    var cameraRequest by remember { mutableStateOf<MapCameraRequest?>(null) }
    var starting by remember { mutableStateOf(false) }
    var fogRuntime by remember { mutableStateOf<FogRuntime?>(null) }
    var startupReconciled by remember(appContainer) { mutableStateOf(false) }

    LaunchedEffect(historyStore) {
        historyStore.history.collectLatest { history = it }
    }

    LaunchedEffect(appContainer) {
        fogRuntime = withContext(Dispatchers.IO) { appContainer.fogRuntime() }
    }

    LaunchedEffect(appContainer) {
        withContext(Dispatchers.IO) { appContainer.reconcileRecordingStartup() }
        startupReconciled = true
    }

    LaunchedEffect(appContainer) {
        appContainer.recordingHistory.latestSessionSummary().collectLatest { summary ->
            latestSessionSummary = summary
            latestHistoryLoaded = true
        }
    }

    LaunchedEffect(appContainer) {
        appContainer.recordingServiceState.stoppingSessionId.collectLatest {
            stoppingSessionId = it
        }
    }

    LaunchedEffect(appContainer) {
        appContainer.recordingServiceState.latestAcceptedLocation.collectLatest {
            serviceLocation = it
        }
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
        val continuation = NotificationStartContinuation.valueOf(
            notificationStartContinuationName,
        )
        notificationStartContinuationName =
            continuation.resultObserved().name
        // The notification card can launch the same platform prompt without a pending Start.
        // Its result must release that card's in-flight UI state, but must not create a Start.
        if (continuation == NotificationStartContinuation.IDLE) {
            starting = false
        }
        platformRefresh += 1
    }

    fun raiseStartNotice(notice: RecordingStartNotice?) {
        startNotice = notice
        startNoticeRaisedAt = notice?.let { System.currentTimeMillis() }
    }

    suspend fun resumeAbandonedRecording(sessionId: Long) {
        // Deliberately quiet: the user pressed nothing, so this raises no start notice. Success shows
        // itself when the row is owned again and the card stops saying the exploration was abandoned;
        // a failure leaves that card in place, which is already the truth. Only a blocker the user can
        // act on is surfaced, and through the same notice the Start button would have raised.
        val outcome = controller.resumeAbandonedFromVisibleActivity(
            activityVisible = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            sessionId = sessionId,
        )
        if (outcome is RecordingResumeOutcome.Blocked) {
            when (outcome.blocker) {
                RecordingStartBlocker.MISSING_LOCATION_PERMISSION ->
                    locationNotice = LocationNotice.PERMISSION_SETTINGS
                RecordingStartBlocker.MISSING_FINE_LOCATION ->
                    locationNotice = LocationNotice.PRECISE_SETTINGS
                RecordingStartBlocker.LOCATION_DISABLED ->
                    locationNotice = LocationNotice.LOCATION_SERVICES
                RecordingStartBlocker.ACTIVITY_NOT_VISIBLE -> Unit
            }
        }
    }

    suspend fun interruptAbandonedRecording(sessionId: Long, stoppedRecordingAt: Long?) {
        // Also quiet, and for a different reason than the resume: the row is being closed, so what
        // the user needs to see is the interrupted exploration itself, which the card already
        // becomes the moment the terminal row lands.
        controller.interruptAbandonedAcrossRestart(
            sessionId = sessionId,
            stoppedRecordingAtEpochMillis = stoppedRecordingAt,
        )
    }

    suspend fun startRecording(beginOperationId: RecordingOperationId? = null) {
        starting = true
        locationNotice = null
        raiseStartNotice(null)
        val outcome = controller.startFromVisibleActivity(
            activityVisible = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            beginOperationId = beginOperationId,
        )
        raiseStartNotice(
            when (outcome) {
                is RecordingStartOutcome.ServiceRequested -> {
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
            },
        )
        starting = false
    }

    fun dispatch(action: RecordingStartAction) {
        when (action) {
            RecordingStartAction.WaitForResumedActivity -> {
                starting = false
                raiseStartNotice(RecordingStartNotice.ACTIVITY_NOT_VISIBLE)
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
                    raiseStartNotice(RecordingStartNotice.PERSISTENCE_FAILURE)
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
            RecordingStartAction.RequestNotificationThenStart -> {
                starting = true
                val continuation = NotificationStartContinuation.valueOf(
                    notificationStartContinuationName,
                )
                if (continuation == NotificationStartContinuation.IDLE) {
                    notificationStartOperationIdValue =
                        "begin-start-notification:${UUID.randomUUID()}"
                    notificationStartContinuationName = continuation.begin().name
                }
            }
            RecordingStartAction.ShowNotificationRationaleThenStart -> scope.launch {
                startRecording()
            }
            RecordingStartAction.StartRecording -> scope.launch {
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

    LaunchedEffect(notificationStartContinuationName) {
        val continuation = NotificationStartContinuation.valueOf(
            notificationStartContinuationName,
        )
        when (continuation) {
            NotificationStartContinuation.REQUESTING_PERMISSION -> {
                snapshotFlow { activityResumed }.first { it }
                starting = true
                try {
                    historyStore.markNotificationsRequested()
                    notificationStartContinuationName =
                        continuation.permissionRequestLaunched().name
                    // The state transition and launch are deliberately in one main-thread call
                    // stack. Recreation can retry the suspending marker write, but cannot strand
                    // AWAITING_RESULT before the launcher has received this request.
                    notificationPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notificationStartContinuationName =
                        NotificationStartContinuation.IDLE.name
                    notificationStartOperationIdValue = null
                    starting = false
                    raiseStartNotice(RecordingStartNotice.PERSISTENCE_FAILURE)
                }
            }
            NotificationStartContinuation.RESULT_OBSERVED -> {
                // A permission result can briefly produce RESUME -> PAUSE -> RESUME. Waiting inside
                // this effect means that transient lifecycle edge cannot cancel and re-enter Start.
                // Destroying the composition still cancels it; the saved operation id below then
                // makes a recreation retry replay the same durable begin command.
                snapshotFlow { activityResumed }.first { resumed ->
                    continuation.canResumeStart(resumed)
                }
                starting = true
                // Keep RESULT_OBSERVED saved until every current preflight and the controller call
                // finish. Recreation cancels this effect and retries; the repository/service start
                // path remains the durable idempotency boundary for that cancellation window.
                val operationId = RecordingOperationId(
                    checkNotNull(notificationStartOperationIdValue) {
                        "pending notification Start has no stable operation id"
                    },
                )
                startRecording(beginOperationId = operationId)
                notificationStartContinuationName = NotificationStartContinuation.IDLE.name
                notificationStartOperationIdValue = null
            }
            NotificationStartContinuation.IDLE,
            NotificationStartContinuation.AWAITING_RESULT,
            -> Unit
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

    val recordingPresentation = latestSessionSummary.toRecordingPresentation(
        stoppingSessionId = stoppingSessionId,
        runtimeToken = appContainer.recordingRuntimeToken,
    )

    LaunchedEffect(
        recordingPresentation.state,
        recordingPresentation.activeSessionId,
        startupReconciled,
        activityResumed,
    ) {
        // Every part of this decision — including the once-per-process claim, whose home is the
        // container rather than this composition because a `remember` here handed out a fresh
        // attempt on each history round trip — is made inside the function, so that the route holds
        // no rule of its own that a test could not reach.
        val action = abandonedExplorationAction(
            state = recordingPresentation.state,
            activeSessionId = recordingPresentation.activeSessionId,
            activeSessionStartedAt = recordingPresentation.activeSessionStartedAt,
            activeSessionLastPointAt = recordingPresentation.activeSessionLastPointAt,
            bootedAtEpochMillis = appContainer.bootedAtEpochMillis(),
            startupReconciled = startupReconciled,
            activityResumed = activityResumed,
            claim = appContainer::claimAbandonedResumeAttempt,
        )
        when (action) {
            null -> Unit
            is AbandonedExplorationAction.Resume -> resumeAbandonedRecording(action.sessionId)
            is AbandonedExplorationAction.Interrupt ->
                // The action carries its own terminal instant; the route computes nothing, so there
                // is no inline expression here for a fixture to miss.
                interruptAbandonedRecording(
                    sessionId = action.sessionId,
                    stoppedRecordingAt = action.stoppedRecordingAt,
                )
        }
    }
    // A view-tree diagnostic makes the production presentation boundary observable to scale and
    // frame tests without exposing canonical coordinates or adding a second data subscription.
    SideEffect {
        activity.window.decorView.setTag(
            R.id.recording_presentation_latest_point_id,
            recordingPresentation.latestAcceptedPoint?.id,
        )
        activity.window.decorView.setTag(
            R.id.recording_presentation_latest_outcome,
            latestSessionSummary?.latestOperationOutcome?.value,
        )
        // The state the user is actually being told, published from the production wiring rather
        // than recomputed by a test. Without it a test can only check the mapping function, and the
        // mapping can be correct while the screen passes it the wrong runtime token.
        activity.window.decorView.setTag(
            R.id.recording_presentation_state,
            recordingPresentation.state.name,
        )
        // Without this, a missing runtime prompt is ambiguous: the request may never have been
        // launched, or it may have launched and the dialog not appeared. Only the first is a
        // continuation defect, and the two need different fixes.
        activity.window.decorView.setTag(
            R.id.recording_notification_start_continuation,
            notificationStartContinuationName,
        )
    }
    val currentLocation = serviceLocation
        ?.takeIf { it.sessionId == recordingPresentation.activeSessionId }
        ?.let { GeoPoint(latitude = it.latitude, longitude = it.longitude) }
        ?: recordingPresentation.latestAcceptedPoint?.let { point ->
            GeoPoint(latitude = point.latitude, longitude = point.longitude)
        }
    // Open where the user is, not in the middle of the ocean. This fires once per screen instance
    // and is remembered across navigation, so it never fights a camera the user has moved
    // themselves — and returning from history restores the map's own saved camera instead.
    var openedAtKnownLocation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentLocation, openedAtKnownLocation) {
        if (openedAtKnownLocation) return@LaunchedEffect
        val point = currentLocation ?: return@LaunchedEffect
        openedAtKnownLocation = true
        cameraRequestId += 1L
        cameraRequest = MapCameraRequest(
            requestId = cameraRequestId,
            point = point,
            zoom = EXPLORATION_ZOOM,
        )
    }
    // Centring once and then standing still is what the button used to do, and it left a walking
    // user drifting off their own map. Following is a mode: the button turns it on, the user's own
    // hand on the map turns it off, and nothing else does either.
    var requestedFollowing by rememberSaveable { mutableStateOf(false) }
    val following = requestedFollowing && currentLocation != null

    RecordingEntryScreen(
        state = RecordingEntryUiState(
            loading = currentHistory == null || !latestHistoryLoaded || !startupReconciled,
            // Not knowing yet is not the same as knowing this is a first visit. Deriving the two
            // from the same snapshot keeps them from disagreeing for a frame.
            firstVisit = currentHistory != null && !currentHistory.hasSeenIntroduction,
            locationNotice = locationNotice,
            notificationNotice = notificationNotice,
            startNotice = startNotice,
            startNoticeRaisedAt = startNoticeRaisedAt,
            stopOffered = stopControlOffered(
                state = recordingPresentation.state,
                activeSessionId = recordingPresentation.activeSessionId,
            ),
            startOffered = startControlOffered(
                state = recordingPresentation.state,
                activeSessionId = recordingPresentation.activeSessionId,
            ),
            starting = starting || notificationStartContinuation.keepsStartPending,
            recordingState = recordingPresentation.state,
            latestSessionId = recordingPresentation.latestSessionId,
            latestEndedAt = recordingPresentation.latestEndedAt,
            canRecenter = currentLocation != null,
            followingLocation = following,
        ),
        onStart = {
            if (!starting && !notificationStartContinuation.keepsStartPending) {
                starting = true
                raiseStartNotice(null)
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
                        raiseStartNotice(RecordingStartNotice.PERSISTENCE_FAILURE)
                    }
                }
            }
        },
        onStop = {
            val sessionId = recordingPresentation.activeSessionId
            if (!starting && sessionId != null) {
                starting = true
                try {
                    RecordingForegroundService.stopFromVisibleActivity(activity, sessionId)
                    raiseStartNotice(RecordingStartNotice.STOP_REQUESTED)
                } catch (_: RuntimeException) {
                    raiseStartNotice(RecordingStartNotice.LAUNCH_FAILURE)
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
                            raiseStartNotice(RecordingStartNotice.PERSISTENCE_FAILURE)
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
                NotificationNotice.RATIONALE -> if (
                    canLaunchInformationalNotificationAction(
                        starting = starting,
                        continuation = notificationStartContinuation,
                    )
                ) {
                    starting = true
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
                            raiseStartNotice(RecordingStartNotice.PERSISTENCE_FAILURE)
                        }
                    }
                }
                NotificationNotice.SETTINGS -> if (
                    canLaunchInformationalNotificationAction(
                        starting = starting,
                        continuation = notificationStartContinuation,
                    )
                ) {
                    activity.openNotificationSettings()
                }
                null -> Unit
            }
        },
        // One press, one thing: back to where I am, at the zoom I explore at, and stay with me.
        // It used to double as a switch that turned following off, which meant a press did
        // different things depending on a state the user could not see, and the way back in took
        // two presses. Following still ends the moment a hand touches the map, which is the only
        // place anyone looked for it.
        onRecenter = {
            currentLocation?.let { point ->
                requestedFollowing = true
                cameraRequestId += 1L
                cameraRequest = MapCameraRequest(
                    requestId = cameraRequestId,
                    point = point,
                    // Back to the zoom exploration happens at. Keeping whatever zoom the user
                    // had was tried and was wrong: after zooming out to look around, the whole
                    // point of pressing this is to be taken back in, and a button that only
                    // slides a distant view sideways reads as broken.
                    zoom = EXPLORATION_ZOOM,
                )
            }
        },
        onUserMovedCamera = { requestedFollowing = false },
        onOpenHistory = onOpenHistory,
        fogRuntime = fogRuntime,
        fogRequired = true,
        cameraRequest = cameraRequest,
        currentLocation = currentLocation,
        followLocation = currentLocation.takeIf { following },
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

/**
 * The zoom the recentre button returns to, and the one the map opens at.
 *
 * Same value both times on purpose: "take me back to where I am" should land somewhere a walk is
 * legible, whether it is the first thing the screen does or the thing the user asks for after
 * looking around.
 */
private const val EXPLORATION_ZOOM = 16.0
