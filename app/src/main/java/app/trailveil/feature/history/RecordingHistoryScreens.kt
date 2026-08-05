package app.trailveil.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.trailveil.R
import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.data.history.RecordingHistorySegment
import app.trailveil.data.history.RecordingHistorySession
import app.trailveil.data.history.RecordingHistoryStatus
import app.trailveil.map.TrailVeilMapSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/** Stateless, durable-record-only history UI. It never renders raw coordinates as text. */
internal object RecordingHistoryTestTags {
    const val Loading = "recording_history_loading"
    const val List = "recording_history_list"
    const val Empty = "recording_history_empty"
    const val Detail = "recording_history_detail"
    const val DetailMissing = "recording_history_detail_missing"
    const val Back = "recording_history_back"
    const val TrackMap = "recording_history_track_map"
    const val ItemPrefix = "recording_history_item_"
    const val SegmentPrefix = "recording_history_segment_"

    fun item(sessionId: Long) = "$ItemPrefix$sessionId"

    fun segment(sequence: Long) = "$SegmentPrefix$sequence"
}

@Composable
internal fun RecordingHistoryListScreen(
    sessions: List<RecordingHistorySession>,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    nowMillis: Long = System.currentTimeMillis(),
) {
    HistorySurface(modifier) {
        Text(
            text = stringResource(R.string.history_title),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.history_list_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) {
            HistoryLoadingState()
        } else if (sessions.isEmpty()) {
            HistoryEmptyState()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RecordingHistoryTestTags.List),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                sessions.forEach { session ->
                    HistorySessionCard(
                        session = session,
                        nowMillis = nowMillis,
                        onClick = { onOpenSession(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RecordingHistoryDetailScreen(
    detail: RecordingHistoryDetail?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    nowMillis: Long = System.currentTimeMillis(),
) {
    HistorySurface(modifier) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.testTag(RecordingHistoryTestTags.Back),
        ) {
            Text(stringResource(R.string.history_back))
        }
        if (loading) {
            HistoryLoadingState()
            return@HistorySurface
        }
        if (detail == null) {
            Text(
                text = stringResource(R.string.history_missing_title),
                modifier = Modifier.testTag(RecordingHistoryTestTags.DetailMissing),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.history_missing_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@HistorySurface
        }

        val session = detail.session
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(RecordingHistoryTestTags.Detail),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.history_detail_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            HistoryStatusCard(session.status)
            HistoryTimeSummary(session, nowMillis)
            HistoryMetrics(session, nowMillis, includeRejected = true)
            val trackOverlay = detail.toMapTrackOverlay()
            Text(
                text = stringResource(R.string.history_track_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (trackOverlay == null) {
                Text(
                    text = stringResource(R.string.history_track_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TrailVeilMapSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(MaterialTheme.shapes.large)
                        .testTag(RecordingHistoryTestTags.TrackMap),
                    savedStateKey = "trailveil.map.history." + session.id,
                    rendersIntoTheWindow = true,
                    trackOverlay = trackOverlay,
                )
            }
            session.stopReason?.takeIf(String::isNotBlank)?.let { reason ->
                HistoryLabelValue(
                    label = stringResource(R.string.history_stop_reason),
                    value = reason,
                )
            }
            Text(
                text = stringResource(R.string.history_segments_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (detail.segments.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_segments_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                detail.segments.forEach { segment ->
                    HistorySegmentCard(segment, nowMillis)
                }
            }
        }
    }
}

@Composable
private fun HistoryLoadingState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingHistoryTestTags.Loading),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Text(
            text = stringResource(R.string.history_loading),
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun HistorySurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
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
            content = { content() },
        )
    }
}

@Composable
private fun HistoryEmptyState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingHistoryTestTags.Empty),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.history_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.history_empty_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun HistorySessionCard(
    session: RecordingHistorySession,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(RecordingHistoryTestTags.item(session.id)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(historyStatusLabel(session.status)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = HistoryFormatters.instant(session.startedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HistoryMetrics(session, nowMillis)
        }
    }
}

@Composable
private fun HistoryStatusCard(status: RecordingHistoryStatus) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (status.isProblem) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(historyStatusLabel(status)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(historyStatusDescription(status)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun HistoryTimeSummary(session: RecordingHistorySession, nowMillis: Long) {
    HistoryLabelValue(
        label = stringResource(R.string.history_started_at),
        value = HistoryFormatters.instant(session.startedAt),
    )
    HistoryLabelValue(
        label = stringResource(if (session.endedAt == null) R.string.history_ongoing else R.string.history_ended_at),
        value = session.endedAt?.let(HistoryFormatters::instant)
            ?: HistoryFormatters.duration(nowMillis - session.startedAt),
    )
}

@Composable
private fun HistoryMetrics(
    session: RecordingHistorySession,
    nowMillis: Long,
    includeRejected: Boolean = false,
) {
    val acceptedQuantity = session.acceptedPointCount
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val accepted = pluralStringResource(
        R.plurals.history_accepted_points,
        acceptedQuantity,
        session.acceptedPointCount,
    )
    val rejectedQuantity = session.rejectedPointCount
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val rejected = pluralStringResource(
        R.plurals.history_rejected_points,
        rejectedQuantity,
        session.rejectedPointCount,
    )
    val metrics = if (includeRejected) {
        stringResource(
            R.string.history_detail_metrics,
            HistoryFormatters.duration((session.endedAt ?: nowMillis) - session.startedAt),
            HistoryFormatters.distance(session.distanceMeters),
            accepted,
            rejected,
        )
    } else {
        stringResource(
            R.string.history_metrics,
            HistoryFormatters.duration((session.endedAt ?: nowMillis) - session.startedAt),
            HistoryFormatters.distance(session.distanceMeters),
            accepted,
        )
    }
    Text(
        text = metrics,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun HistorySegmentCard(segment: RecordingHistorySegment, nowMillis: Long) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordingHistoryTestTags.segment(segment.sequence)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.history_segment_number, segment.sequence + 1),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.history_segment_started, HistoryFormatters.instant(segment.startedAt)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    if (segment.endedAt == null) R.string.history_segment_ongoing else R.string.history_segment_ended,
                    segment.endedAt?.let(HistoryFormatters::instant)
                        ?: HistoryFormatters.duration(nowMillis - segment.startedAt),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            HistoryLabelValue(stringResource(R.string.history_segment_start_reason), segment.startReason)
            segment.endReason?.takeIf(String::isNotBlank)?.let { reason ->
                HistoryLabelValue(stringResource(R.string.history_segment_end_reason), reason)
            }
        }
    }
}

@Composable
private fun HistoryLabelValue(label: String, value: String) {
    Text(
        text = stringResource(R.string.history_label_value, label, value),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun historyStatusLabel(status: RecordingHistoryStatus) = when (status) {
    RecordingHistoryStatus.STARTING -> R.string.history_status_starting
    RecordingHistoryStatus.ACTIVE -> R.string.history_status_active
    RecordingHistoryStatus.COMPLETED -> R.string.history_status_completed
    RecordingHistoryStatus.INTERRUPTED -> R.string.history_status_interrupted
    RecordingHistoryStatus.FAILED_TO_START -> R.string.history_status_failed_to_start
}

private fun historyStatusDescription(status: RecordingHistoryStatus) = when (status) {
    RecordingHistoryStatus.STARTING -> R.string.history_status_starting_description
    RecordingHistoryStatus.ACTIVE -> R.string.history_status_active_description
    RecordingHistoryStatus.COMPLETED -> R.string.history_status_completed_description
    RecordingHistoryStatus.INTERRUPTED -> R.string.history_status_interrupted_description
    RecordingHistoryStatus.FAILED_TO_START -> R.string.history_status_failed_to_start_description
}

private val RecordingHistoryStatus.isProblem: Boolean
    get() = this == RecordingHistoryStatus.INTERRUPTED || this == RecordingHistoryStatus.FAILED_TO_START

internal object HistoryFormatters {
    /**
     * The offset is part of the reading, not decoration. Without it a walk recorded at 19:30 in
     * Taipei and reviewed in London renders as a bare "12:30" that matches neither the clock the
     * user remembers nor the one in their pocket, and nothing on screen says which it is.
     */
    private val pattern: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm XXX", Locale.ROOT)

    /**
     * Read back in the reader's own time zone. Stored timestamps stay epoch milliseconds, so this
     * is presentation only — but an exploration is something the user did at a time of day they
     * remember, and UTC made an evening walk read as the small hours.
     *
     * The zone is resolved per call rather than cached, so a device that crosses one keeps
     * agreeing with its own clock.
     */
    fun instant(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        pattern.withZone(zone).format(Instant.ofEpochMilli(epochMillis))

    fun duration(durationMillis: Long): String {
        val seconds = max(0L, durationMillis) / 1_000L
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        val remainingSeconds = seconds % 60L
        return when {
            hours > 0L -> String.format(Locale.ROOT, "%dh %02dm", hours, minutes)
            minutes > 0L -> String.format(Locale.ROOT, "%dm %02ds", minutes, remainingSeconds)
            else -> String.format(Locale.ROOT, "%ds", remainingSeconds)
        }
    }

    fun distance(meters: Double): String = when {
        meters >= 1_000.0 -> String.format(Locale.ROOT, "%.2f km", meters / 1_000.0)
        else -> String.format(Locale.ROOT, "%.0f m", meters)
    }
}
