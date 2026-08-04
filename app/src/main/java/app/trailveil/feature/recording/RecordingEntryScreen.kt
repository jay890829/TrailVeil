package app.trailveil.feature.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
}

internal data class RecordingEntryUiState(
    val loading: Boolean = false,
    val firstVisit: Boolean = true,
    val locationNotice: LocationNotice? = null,
    val notificationNotice: NotificationNotice? = null,
    val startNotice: RecordingStartNotice? = null,
    val startNoticeRaisedAt: Long? = null,
    val recordingActive: Boolean = false,
    val starting: Boolean = false,
    val recordingState: RecordingDisplayState = RecordingDisplayState.IDLE,
    val latestSessionId: Long? = null,
    val latestEndedAt: Long? = null,
    val canRecenter: Boolean = false,
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
    const val History = "recording_entry_history"
    const val Menu = "recording_entry_menu"
    const val Privacy = "recording_entry_privacy"
    const val PrivacySheet = "recording_entry_privacy_sheet"
    const val PrivacyDismiss = "recording_entry_privacy_dismiss"
    const val RecordingStateDismiss = "recording_entry_recording_state_dismiss"
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
    fogRuntime: FogRuntime? = null,
    fogRequired: Boolean = false,
    cameraRequest: MapCameraRequest? = null,
    currentLocation: GeoPoint? = null,
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
    var noticeNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(
        state.latestSessionId,
        state.latestEndedAt,
        state.startNotice,
        state.startNoticeRaisedAt,
    ) {
        val now = System.currentTimeMillis()
        noticeNowMillis = now
        val deadlines = listOfNotNull(
            state.latestEndedAt.takeIf { state.recordingState in TerminalRecordingStates },
            state.startNoticeRaisedAt.takeIf { state.startNotice in ExpiringStartNotices },
        ).map { it + TRANSIENT_NOTICE_WINDOW_MILLIS }
        var remaining = deadlines.filter { it > now }.minOrNull()?.minus(now)
        while (remaining != null) {
            delay(remaining)
            val awake = System.currentTimeMillis()
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
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                }
            }
            RecenterButton(
                enabled = state.canRecenter,
                onClick = onRecenter,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp),
            )
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
            if (state.recordingActive) {
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
            } else {
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

@Composable
private fun RecenterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .semantics { if (!enabled) disabled() }
            .testTag(RecordingEntryTestTags.Recenter),
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
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_map_recenter),
            contentDescription = stringResource(R.string.map_center_latest_location),
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
