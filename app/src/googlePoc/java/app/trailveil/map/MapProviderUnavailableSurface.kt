package app.trailveil.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trailveil.R

/**
 * The colour of TrailVeil's own fog: `FogTileRenderer`'s `fogAlpha = 184` black composited over
 * the committed local ground `#D6DBD2`. Both failure surfaces paint it so a map slot that cannot
 * show a map reads as fog - never as a blank of some other product, and never as revealed ground.
 */
internal val FogSurfaceColor = Color(0xFF3C3D3A)

/**
 * The terminal failure surface for the Google-variant map slot (design §9).
 *
 * Styleless and provider-free by construction: no Google view and no other provider's view is
 * ever composed on this path, which satisfies the "no non-Google basemap near Google content"
 * clause structurally. The reason is real localized [Text] so TalkBack reads the copy itself -
 * deliberately no machine-string `contentDescription` (the PoC harness's tag-as-description
 * mistake is recorded in the survey). Operator guidance such as `GOOGLE_MAPS_POC_KEY_GUIDANCE`
 * must never appear here; it stays in the de-launchered engineering harness.
 *
 * Terminal means terminal for THIS composition only. The caller re-evaluates the provider gate on
 * every composition, so a user who fixes the underlying condition recovers on the next history
 * round trip, recreation, or relaunch without TrailVeil holding a latch.
 */
@Composable
internal fun MapProviderUnavailableSurface(
    reason: ProviderFallbackReason,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FogSurfaceColor)
            .padding(24.dp)
            .testTag(MapSurfaceTestTags.ProviderUnavailable),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(reason.unavailableMessageResource()),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.map_provider_unavailable_reassurance),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Production user copy per reason. Distinct from the PoC harness's diagnostic strings: these are
 * variant resources (the reasons only exist on this variant until `V02-008`), localized, and free
 * of build-configuration guidance.
 */
internal fun ProviderFallbackReason.unavailableMessageResource(): Int = when (this) {
    ProviderFallbackReason.MISSING_KEY -> R.string.map_provider_unavailable_missing_key
    ProviderFallbackReason.STRUCTURALLY_INVALID_KEY -> R.string.map_provider_unavailable_invalid_key
    ProviderFallbackReason.NO_VALIDATED_NETWORK -> R.string.map_provider_unavailable_no_network
    ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE ->
        R.string.map_provider_unavailable_no_play_services
    ProviderFallbackReason.INITIALIZATION_FAILURE ->
        R.string.map_provider_unavailable_initialization_failure
    ProviderFallbackReason.MAP_LOAD_TIMEOUT ->
        R.string.map_provider_unavailable_initialization_failure
}
