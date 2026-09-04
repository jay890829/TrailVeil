package app.trailveil.feature.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.trailveil.R
import app.trailveil.map.TrailVeilMapSurface
import app.trailveil.map.MapCameraRequest
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.FogRuntime
import kotlinx.coroutines.delay

internal enum class LocationNotice {
    RATIONALE,
    PERMISSION_SETTINGS,
    PRECISE_RATIONALE,
    PRECISE_SETTINGS,
    LOCATION_SERVICES,
}

internal enum class NotificationNotice {
    RATIONALE,
    SETTINGS,
}

internal enum class RecordingStartNotice {
    STARTED,
    PERSISTENCE_FAILURE,
    LAUNCH_FAILURE,
    ACTIVITY_NOT_VISIBLE,
    STOP_REQUESTED,
}

internal enum class RecordingDisplayState {
    IDLE,
    STARTING,
    RECORDING,
    POOR_SIGNAL,
    STOPPING,
    COMPLETED,
    INTERRUPTED,
    FAILED_TO_START,

    /**
     * An `ACTIVE` row whose durable owner is not this process: no runtime is collecting for it.
     *
     * Distinct from [INTERRUPTED], which is a persisted terminal status. This one is a live
     * contradiction — the database still says the exploration is running while the runtime that
     * claimed it is gone — and the user must not be shown [RECORDING] for it. Ownership proves the
     * owning process still exists, not that its collector is still subscribed, so this catches
     * process death rather than every possible way a runtime can stop delivering.
     */
    ABANDONED,
}

internal data class RecordingEntryUiState(
    val loading: Boolean = false,
    val firstVisit: Boolean = true,
    val locationNotice: LocationNotice? = null,
    val notificationNotice: NotificationNotice? = null,
    /**
     * The app itself just re-armed an exploration the platform had left abandoned - evidence this
     * device does not restart the recording service on its own, which some systems gate behind a
     * background-start permission the app can neither read nor request.
     */
    val backgroundStartNotice: Boolean = false,
    val startNotice: RecordingStartNotice? = null,
    val startNoticeRaisedAt: Long? = null,
    /** Whether the open exploration can be ended from here. */
    val stopOffered: Boolean = false,
    /** Whether an exploration can be begun - or an abandoned one continued - from here. */
    val startOffered: Boolean = true,
    val starting: Boolean = false,
    val recordingState: RecordingDisplayState = RecordingDisplayState.IDLE,
    val latestSessionId: Long? = null,
    val latestEndedAt: Long? = null,
    val canRecenter: Boolean = false,
    val followingLocation: Boolean = false,
)

internal object RecordingEntryTestTags {
    const val Start = "recording_entry_start"
    const val Stop = "recording_entry_stop"
    const val LocationNotice = "recording_entry_location_notice"
    const val LocationAction = "recording_entry_location_action"
    const val NotificationNotice = "recording_entry_notification_notice"
    const val NotificationAction = "recording_entry_notification_action"
    const val StartNotice = "recording_entry_start_notice"
    const val StartNoticeDismiss = "recording_entry_start_notice_dismiss"
    const val RecordingState = "recording_entry_recording_state"
    const val Recenter = "recording_entry_recenter"

    /**
     * The map's own start/stop control, distinct from the menu items above.
     *
     * Deliberately NOT [Start] / [Stop]: those tags are on the menu items, and a `onNodeWithTag`
     * that matched two nodes would fail every existing test that uses them. Same state, same
     * callbacks, different tag.
     */
    const val MapExploration = "recording_entry_map_exploration"
    const val History = "recording_entry_history"
    const val Menu = "recording_entry_menu"
    const val Privacy = "recording_entry_privacy"
    const val PrivacySheet = "recording_entry_privacy_sheet"
    const val PrivacyDismiss = "recording_entry_privacy_dismiss"
    const val RecordingStateDismiss = "recording_entry_recording_state_dismiss"
    const val BackgroundStartNotice = "recording_entry_background_start_notice"
    const val BackgroundStartAction = "recording_entry_background_start_action"
    const val BackgroundStartDismiss = "recording_entry_background_start_dismiss"
}

@Composable
internal fun RecordingEntryScreen(
    state: RecordingEntryUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLocationAction: () -> Unit,
    onDismissLocationNotice: () -> Unit,
    onNotificationAction: () -> Unit,
    modifier: Modifier = Modifier,
    onRecenter: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onUserMovedCamera: () -> Unit = {},
    onBackgroundStartAction: () -> Unit = {},
    onDismissBackgroundStartNotice: () -> Unit = {},
    fogRuntime: FogRuntime? = null,
    fogRequired: Boolean = false,
    cameraRequest: MapCameraRequest? = null,
    currentLocation: GeoPoint? = null,
    followLocation: GeoPoint? = null,
    clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var privacyRequested by rememberSaveable { mutableStateOf(false) }
    var privacyAcknowledged by rememberSaveable { mutableStateOf(false) }
    // Never auto-open the disclosure on `firstVisit` alone: until the stored history has been read
    // the route cannot know whether this is a first visit, and showing it on that guess made the
    // sheet flash on every launch and every return from history for people who had long since
    // seen it.
    val privacyVisible = privacyRequested ||
        (!state.loading && state.firstVisit && !privacyAcknowledged)
    // An acknowledgement belongs to one exploration, not to a kind of outcome. Keying it on the
    // session means a later outcome is visible by construction rather than by a rule that has to
    // guess when the previous one stopped applying.
    var acknowledgedSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Re-read the clock only when something being timed changes, and once more when the nearest
    // window is due to close. Recomposition alone must not move it, or a card's lifetime would
    // depend on unrelated redraws.
    var noticeNowMillis by remember { mutableLongStateOf(clockMillis()) }
    LaunchedEffect(
        state.latestSessionId,
        state.latestEndedAt,
        state.startNotice,
        state.startNoticeRaisedAt,
    ) {
        val now = clockMillis()
        noticeNowMillis = now
        val deadlines = listOfNotNull(
            state.latestEndedAt.takeIf { state.recordingState in TerminalRecordingStates },
            state.startNoticeRaisedAt.takeIf { state.startNotice in ExpiringStartNotices },
        ).map { it + TRANSIENT_NOTICE_WINDOW_MILLIS }
        var remaining = deadlines.filter { it > now }.minOrNull()?.minus(now)
        while (remaining != null) {
            delay(remaining)
            val awake = clockMillis()
            noticeNowMillis = awake
            remaining = deadlines.filter { it > awake }.minOrNull()?.minus(awake)
        }
    }
    var dismissedStartNotice by rememberSaveable { mutableStateOf<RecordingStartNotice?>(null) }
    // Start notices are one-shot acknowledgements of a user action, so the same dismissal rule
    // applies: a dismissal is bound to the notice it was made for and never hides a later one.
    LaunchedEffect(state.startNotice) {
        if (dismissedStartNotice != null && dismissedStartNotice != state.startNotice) {
            dismissedStartNotice = null
        }
    }
    val recordingStateDismissible = state.recordingState in TerminalRecordingStates
    val recordingStateVisible = if (recordingStateDismissible) {
        terminalNoticeVisible(
            state = state.recordingState,
            sessionId = state.latestSessionId,
            endedAt = state.latestEndedAt,
            nowMillis = noticeNowMillis,
            acknowledgedSessionId = acknowledgedSessionId,
        )
    } else {
        state.recordingState != RecordingDisplayState.IDLE
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TrailVeilMapSurface(
                modifier = Modifier.fillMaxSize(),
                fogRuntime = fogRuntime,
                fogRequired = fogRequired,
                cameraRequest = cameraRequest,
                currentLocation = currentLocation,
                followLocation = followLocation,
                onUserMovedCamera = onUserMovedCamera,
                // The map draws its compass inside itself and edge to edge, so left alone it lands
                // under the menu button. Stack it below instead, in the same column of controls.
                compassTopInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() +
                    ControlEdgeInset + MenuButtonSize + ControlSpacing,
                compassEndInset = ControlEdgeInset,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = ControlEdgeInset, vertical = ControlEdgeInset),
                verticalArrangement = Arrangement.spacedBy(ControlSpacing),
                horizontalAlignment = Alignment.End,
            ) {
                RecordingEntryMenu(
                    state = state,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                    onStart = onStart,
                    onStop = onStop,
                    onOpenHistory = onOpenHistory,
                    onOpenPrivacy = { privacyRequested = true },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The compass sits directly above this column's leading edge, so notices
                        // keep clear of it rather than growing underneath it. Reserved whether or
                        // not the compass is currently showing, so a card's width never depends on
                        // which way the map happens to be facing.
                        .padding(end = CompassReserve)
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ControlSpacing),
                ) {
                    state.locationNotice?.let { notice ->
                        LocationNoticeCard(
                            notice = notice,
                            onAction = onLocationAction,
                            onDismiss = onDismissLocationNotice,
                        )
                    }

                    state.notificationNotice?.let { notice ->
                        NotificationNoticeCard(
                            notice = notice,
                            onAction = onNotificationAction,
                        )
                    }

                    state.startNotice?.let { notice ->
                        val visible = startNoticeVisible(
                            notice = notice,
                            raisedAt = state.startNoticeRaisedAt,
                            nowMillis = noticeNowMillis,
                            dismissedNotice = dismissedStartNotice,
                        )
                        if (visible) {
                            StartNoticeCard(
                                notice = notice,
                                onDismiss = { dismissedStartNotice = notice },
                            )
                        }
                    }

                    if (recordingStateVisible) {
                        RecordingStateCard(
                            state = state.recordingState,
                            onDismiss = if (recordingStateDismissible) {
                                { acknowledgedSessionId = state.latestSessionId }
                            } else {
                                null
                            },
                        )
                    }

                    // Last on purpose: it is advice beside a recording that is running again, so it
                    // must never outrank a blocker above it - and once the state card retires, the
                    // guidance stands alone next to the live recording, which is the intended read.
                    if (state.backgroundStartNotice) {
                        BackgroundStartNoticeCard(
                            onAction = onBackgroundStartAction,
                            onDismiss = onDismissBackgroundStartNotice,
                        )
                    }
                }
            }
            // Recentre above, exploration below: the exploration control is the screen's primary
            // action, and Material puts the primary action closest to the thumb.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ControlSpacing),
            ) {
                RecenterButton(
                    enabled = state.canRecenter,
                    following = state.followingLocation,
                    onClick = onRecenter,
                )
                MapExplorationButton(
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                )
            }
        }
    }

    if (privacyVisible) {
        PrivacySheet(
            firstVisit = state.firstVisit,
            onDismiss = {
                privacyRequested = false
                privacyAcknowledged = true
            },
        )
    }
}

@Composable
private fun RecordingEntryMenu(
    state: RecordingEntryUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    Box {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 3.dp,
        ) {
            IconButton(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.testTag(RecordingEntryTestTags.Menu),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_menu_overflow),
                    contentDescription = stringResource(R.string.recording_entry_menu),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            // An abandoned exploration is the one state that offers both: nothing is recording, so
            // Start is what continues it, while the row is still open, so Stop is what ends it.
            // Offering only one of them strands the user - which is what shipped in each direction
            // before this - because the foreground notification died with the process that owned it.
            if (state.stopOffered) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.recording_entry_stop)) },
                    onClick = {
                        onExpandedChange(false)
                        onStop()
                    },
                    modifier = Modifier.testTag(RecordingEntryTestTags.Stop),
                    enabled = !state.starting &&
                        state.recordingState != RecordingDisplayState.STOPPING,
                )
            }
            if (state.startOffered) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                if (state.starting) {
                                    R.string.recording_entry_starting
                                } else {
                                    R.string.recording_entry_start
                                },
                            ),
                        )
                    },
                    onClick = {
                        onExpandedChange(false)
                        onStart()
                    },
                    modifier = Modifier.testTag(RecordingEntryTestTags.Start),
                    enabled = !state.loading && !state.starting,
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.history_open)) },
                onClick = {
                    onExpandedChange(false)
                    onOpenHistory()
                },
                modifier = Modifier.testTag(RecordingEntryTestTags.History),
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.recording_entry_privacy_menu)) },
                onClick = {
                    onExpandedChange(false)
                    onOpenPrivacy()
                },
                modifier = Modifier.testTag(RecordingEntryTestTags.Privacy),
            )
        }
    }
}

/**
 * The map's own exploration control: the menu's Start/Stop, put where the thumb is.
 *
 * It shares [RecordingEntryUiState] and the same `onStart`/`onEnd` callbacks with the menu, so the
 * two cannot disagree - starting from either place moves the same state and both controls follow it
 * on the next composition. That is the whole of "keep them in sync"; there is no second source of
 * truth to reconcile, and adding one is how they would drift.
 *
 * One state offers both actions: an abandoned exploration, where nothing is recording (so Start
 * continues it) but the row is still open (so Stop ends it). A single control cannot show both, so
 * it shows **Start**, which is the action that keeps the user's data, and Stop stays reachable in
 * the menu. That asymmetry is deliberate and is why this is not a plain boolean toggle.
 *
 * Hidden, rather than disabled, when neither action is offered - a control that can never be pressed
 * is furniture. It is disabled, and stays visible, only while an action it IS offering is
 * momentarily unavailable (mid-start, mid-stop), because that is a state the user is waiting out.
 */
@Composable
private fun MapExplorationButton(
    state: RecordingEntryUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val starting = state.starting
    val showStart = state.startOffered
    val showStop = !showStart && state.stopOffered
    if (!showStart && !showStop) return

    val enabled = if (showStart) {
        !state.loading && !starting
    } else {
        !starting && state.recordingState != RecordingDisplayState.STOPPING
    }
    val labelResource = when {
        showStart && starting -> R.string.recording_entry_starting
        showStart -> R.string.recording_entry_start
        else -> R.string.recording_entry_stop
    }
    val icon = if (showStart) {
        R.drawable.ic_exploration_start
    } else {
        R.drawable.ic_exploration_stop
    }
    val label = stringResource(labelResource)
    ExtendedFloatingActionButton(
        onClick = { if (enabled) if (showStart) onStart() else onStop() },
        modifier = modifier
            .semantics {
                // Stated, not inherited. This button's own label does not reach the merged
                // semantics node the way a plain Button's does - measured on API 36, where the
                // node carries Role and OnClick but no Text at all - so a screen reader would
                // announce an unlabelled button. Read from the same string resource the button
                // draws, so the two cannot drift.
                contentDescription = label
                if (!enabled) disabled()
            }
            .testTag(RecordingEntryTestTags.MapExploration),
        containerColor = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        icon = {
            Icon(
                painter = painterResource(icon),
                // The label already names the action; a second reading of it would make a screen
                // reader say the same words twice.
                contentDescription = null,
            )
        },
        text = { Text(text = label) },
    )
}

@Composable
private fun RecenterButton(
    enabled: Boolean,
    following: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .semantics { if (!enabled) disabled() }
            .testTag(RecordingEntryTestTags.Recenter),
        containerColor = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
            // Following is a mode the map is in, not an action that just happened, so the button
            // has to keep saying so until it stops.
            following -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            following -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_map_recenter),
            contentDescription = stringResource(
                // The press does the same thing either way; this says which state it is in, so a
                // screen reader is not left announcing an action as though it were a switch.
                if (following) {
                    R.string.map_following_latest_location
                } else {
                    R.string.map_center_latest_location
                },
            ),
        )
    }
}

@Composable
private fun RecordingStateCard(
    state: RecordingDisplayState,
    onDismiss: (() -> Unit)?,
) {
    val text = when (state) {
        RecordingDisplayState.IDLE -> return
        RecordingDisplayState.STARTING -> R.string.recording_state_starting
        RecordingDisplayState.RECORDING -> R.string.recording_state_recording
        RecordingDisplayState.POOR_SIGNAL -> R.string.recording_state_poor_signal
        RecordingDisplayState.STOPPING -> R.string.recording_state_stopping
        RecordingDisplayState.COMPLETED -> R.string.recording_state_completed
        RecordingDisplayState.INTERRUPTED -> R.string.recording_state_interrupted
        RecordingDisplayState.FAILED_TO_START -> R.string.recording_state_failed
        RecordingDisplayState.ABANDONED -> R.string.recording_state_abandoned
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingEntryTestTags.RecordingState),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                RecordingDisplayState.POOR_SIGNAL,
                RecordingDisplayState.INTERRUPTED,
                RecordingDisplayState.FAILED_TO_START,
                -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        NoticeCardContent(
            text = stringResource(text),
            fontWeight = FontWeight.SemiBold,
            dismissTestTag = RecordingEntryTestTags.RecordingStateDismiss,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Shared body for the transient cards floating over the map. A dismissible card keeps its label
 * and its dismiss control on one row so the card never grows taller than the text it carries.
 */
@Composable
private fun NoticeCardContent(
    text: String,
    fontWeight: FontWeight?,
    dismissTestTag: String,
    onDismiss: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp,
                    end = if (onDismiss == null) 16.dp else 4.dp,
                ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
        )
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .testTag(dismissTestTag),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_dismiss),
                    contentDescription = stringResource(R.string.recording_state_dismiss),
                )
            }
        }
    }
}

@Composable
private fun PrivacySheet(
    firstVisit: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(RecordingEntryTestTags.PrivacyDismiss),
            ) {
                Text(text = stringResource(R.string.recording_entry_privacy_acknowledge))
            }
        },
        modifier = Modifier.testTag(RecordingEntryTestTags.PrivacySheet),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.recording_entry_brand),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        if (firstVisit) {
                            R.string.recording_entry_privacy_title_first
                        } else {
                            R.string.recording_entry_privacy_title
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.recording_entry_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.recording_entry_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.recording_entry_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // `V02-006`: the active basemap provider names itself. Each provider source set
                // supplies these three strings, so the sheet stays provider-neutral while the
                // build states which third party receives basemap requests and under which terms.
                Text(
                    text = stringResource(
                        R.string.recording_entry_privacy_provider_label,
                        stringResource(R.string.map_provider_disclosure_name),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.map_provider_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.map_provider_terms_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.recording_entry_privacy_retention_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.recording_entry_permissions_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.recording_entry_consent_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun BackgroundStartNoticeCard(
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    // tertiaryContainer, not errorContainer: nothing has failed - the recording is running again
    // and this is advice, the same register as the notification notice. The action is outlined and
    // the dismiss plain so the guidance never outranks a blocker card sharing the column. The
    // action deliberately does not clear the card: the app cannot confirm the user reached the
    // switch, so clearing on the press would assert a success it cannot know. Only the dismiss
    // clears it.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingEntryTestTags.BackgroundStartNotice),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.background_start_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = stringResource(R.string.background_start_body))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.testTag(RecordingEntryTestTags.BackgroundStartAction),
                ) {
                    Text(text = stringResource(R.string.permission_open_app_settings))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(RecordingEntryTestTags.BackgroundStartDismiss),
                ) {
                    Text(text = stringResource(R.string.recording_entry_privacy_acknowledge))
                }
            }
        }
    }
}

@Composable
private fun LocationNoticeCard(
    notice: LocationNotice,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (notice) {
        LocationNotice.RATIONALE -> R.string.location_rationale_title
        LocationNotice.PERMISSION_SETTINGS -> R.string.location_permission_settings_title
        LocationNotice.PRECISE_RATIONALE -> R.string.precise_location_title
        LocationNotice.PRECISE_SETTINGS -> R.string.precise_location_settings_title
        LocationNotice.LOCATION_SERVICES -> R.string.location_services_title
    }
    val body = when (notice) {
        LocationNotice.RATIONALE -> R.string.location_rationale_body
        LocationNotice.PERMISSION_SETTINGS -> R.string.location_permission_settings_body
        LocationNotice.PRECISE_RATIONALE -> R.string.precise_location_body
        LocationNotice.PRECISE_SETTINGS -> R.string.precise_location_settings_body
        LocationNotice.LOCATION_SERVICES -> R.string.location_services_body
    }
    val action = when (notice) {
        LocationNotice.RATIONALE -> R.string.permission_try_again
        LocationNotice.PERMISSION_SETTINGS,
        LocationNotice.PRECISE_SETTINGS,
        -> R.string.permission_open_app_settings
        LocationNotice.PRECISE_RATIONALE -> R.string.permission_request_precise
        LocationNotice.LOCATION_SERVICES -> R.string.permission_open_location_settings
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingEntryTestTags.LocationNotice),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = stringResource(body))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.testTag(RecordingEntryTestTags.LocationAction),
                ) {
                    Text(text = stringResource(action))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.permission_not_now))
                }
            }
        }
    }
}

@Composable
private fun NotificationNoticeCard(
    notice: NotificationNotice,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingEntryTestTags.NotificationNotice),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.notification_permission_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = stringResource(R.string.notification_permission_body))
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.testTag(RecordingEntryTestTags.NotificationAction),
            ) {
                Text(
                    text = stringResource(
                        if (notice == NotificationNotice.RATIONALE) {
                            R.string.permission_try_again
                        } else {
                            R.string.permission_open_notification_settings
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun StartNoticeCard(
    notice: RecordingStartNotice,
    onDismiss: () -> Unit,
) {
    val text = when (notice) {
        RecordingStartNotice.STARTED -> R.string.recording_started
        RecordingStartNotice.PERSISTENCE_FAILURE -> R.string.recording_persistence_failure
        RecordingStartNotice.LAUNCH_FAILURE -> R.string.recording_launch_failure
        RecordingStartNotice.ACTIVITY_NOT_VISIBLE -> R.string.recording_activity_not_visible
        RecordingStartNotice.STOP_REQUESTED -> R.string.recording_stop_requested
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingEntryTestTags.StartNotice),
    ) {
        NoticeCardContent(
            text = stringResource(text),
            fontWeight = null,
            dismissTestTag = RecordingEntryTestTags.StartNoticeDismiss,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The one column of controls this screen stacks over the map, from the top edge down: the menu
 * button, then the map's own compass, then any notices.
 *
 * The compass belongs to the map view rather than to this layout, so it cannot take part in the
 * column and has to be placed by arithmetic instead. Keeping every number that arithmetic depends
 * on here is what stops the two drifting apart.
 */
private val ControlEdgeInset = 12.dp
private val ControlSpacing = 10.dp
private val MenuButtonSize = 48.dp
private val CompassSize = 48.dp

/** Width a notice gives up so it can never grow underneath the compass. */
private val CompassReserve = CompassSize + 8.dp
