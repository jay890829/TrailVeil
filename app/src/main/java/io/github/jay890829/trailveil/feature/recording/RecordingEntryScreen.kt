package io.github.jay890829.trailveil.feature.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jay890829.trailveil.R

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

internal data class RecordingEntryUiState(
    val loading: Boolean = false,
    val firstVisit: Boolean = true,
    val locationNotice: LocationNotice? = null,
    val notificationNotice: NotificationNotice? = null,
    val startNotice: RecordingStartNotice? = null,
    val recordingActive: Boolean = false,
    val starting: Boolean = false,
)

internal object RecordingEntryTestTags {
    const val Start = "recording_entry_start"
    const val Stop = "recording_entry_stop"
    const val LocationNotice = "recording_entry_location_notice"
    const val LocationAction = "recording_entry_location_action"
    const val NotificationNotice = "recording_entry_notification_notice"
    const val NotificationAction = "recording_entry_notification_action"
    const val StartNotice = "recording_entry_start_notice"
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
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.recording_entry_brand),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.recording_entry_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.recording_entry_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PrivacyCard(firstVisit = state.firstVisit)

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
                StartNoticeCard(notice)
            }

            Spacer(modifier = Modifier.height(2.dp))
            if (state.recordingActive) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = !state.starting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag(RecordingEntryTestTags.Stop),
                ) {
                    Text(text = stringResource(R.string.recording_entry_stop))
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = !state.loading && !state.starting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag(RecordingEntryTestTags.Start),
                ) {
                    Text(
                        text = stringResource(
                            if (state.starting) {
                                R.string.recording_entry_starting
                            } else {
                                R.string.recording_entry_start
                            },
                        ),
                    )
                }
            }
            Text(
                text = stringResource(R.string.recording_entry_consent_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrivacyCard(firstVisit: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (firstVisit) {
                        R.string.recording_entry_privacy_title_first
                    } else {
                        R.string.recording_entry_privacy_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.recording_entry_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.recording_entry_permissions_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            modifier = Modifier.padding(18.dp),
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
            modifier = Modifier.padding(18.dp),
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
private fun StartNoticeCard(notice: RecordingStartNotice) {
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
        Text(
            text = stringResource(text),
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
