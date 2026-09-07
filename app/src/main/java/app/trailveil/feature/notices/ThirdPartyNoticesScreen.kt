package app.trailveil.feature.notices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.trailveil.R

internal object ThirdPartyNoticesTestTags {
    const val Screen = "third_party_notices"
    const val Back = "third_party_notices_back"
    const val Body = "third_party_notices_body"
    const val Loading = "third_party_notices_loading"
    const val Unavailable = "third_party_notices_unavailable"
}

/** Lines per block. Bounded so neither the block count nor a single block gets out of hand. */
internal const val NoticeLinesPerBlock = 40

/**
 * Split notice text into bounded blocks for a lazy list.
 *
 * `V02-016`: MapLibre's notices are 76 KB. One `Text` of that size measures the whole document
 * before the first frame; one `Text` per line makes thousands of composables out of a document
 * nobody reads line by line. Fixed blocks are neither, and they do not depend on the document
 * having any particular structure - which matters because the two providers' texts are formatted
 * nothing alike and one of them arrives from another process at runtime.
 *
 * Blank blocks at the end are dropped, so a trailing newline does not add an empty row.
 */
internal fun noticeBlocks(
    notices: String,
    linesPerBlock: Int = NoticeLinesPerBlock,
): List<String> {
    require(linesPerBlock > 0) { "linesPerBlock must be positive" }
    return notices.lineSequence()
        .chunked(linesPerBlock)
        .map { lines -> lines.joinToString(separator = "\n") }
        .toList()
        .dropLastWhile { block -> block.isBlank() }
}

/**
 * The open-source notices for whichever provider this build was compiled with.
 *
 * The provider is named on the screen rather than assumed, because the two published APKs are the
 * same application with different map providers, and the notices a reader is looking at are only
 * meaningful next to the name of what they cover.
 */
@Composable
internal fun ThirdPartyNoticesScreen(
    providerName: String,
    notices: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .testTag(ThirdPartyNoticesTestTags.Screen),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.testTag(ThirdPartyNoticesTestTags.Back),
            ) {
                Text(stringResource(R.string.notices_back))
            }
            Text(
                text = stringResource(R.string.notices_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.notices_provider, providerName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) {
                Text(
                    text = stringResource(R.string.notices_loading),
                    modifier = Modifier.testTag(ThirdPartyNoticesTestTags.Loading),
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }
            val blocks = remember(notices) { notices?.let { noticeBlocks(it) }.orEmpty() }
            if (blocks.isEmpty()) {
                Text(
                    text = stringResource(R.string.notices_unavailable),
                    modifier = Modifier.testTag(ThirdPartyNoticesTestTags.Unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ThirdPartyNoticesTestTags.Body),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(blocks.size) { index ->
                    Text(
                        text = blocks[index],
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
