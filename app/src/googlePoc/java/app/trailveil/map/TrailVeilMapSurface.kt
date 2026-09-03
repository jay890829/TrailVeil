package app.trailveil.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.trailveil.BuildConfig
import app.trailveil.R
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogViewportRender
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView

/**
 * The Google-variant actual of the neutral map surface contract.
 *
 * Stage-6 hosted surface: canonical local fog is bound to the SDK only when the neutral caller
 * supplies a runtime and requires fog. Fog-free detail maps keep the hardened basemap alone.
 */
@Composable
internal fun TrailVeilMapSurface(
    modifier: Modifier = Modifier,
    provider: MapProviderConfiguration = ProductionMapProvider,
    fallbackTimeoutMillis: Long = 5_000L,
    savedStateKey: String = "trailveil.map.primary",
    fogRuntime: FogRuntime? = null,
    fogRequired: Boolean = false,
    rendersIntoTheWindow: Boolean = false,
    cameraRequest: MapCameraRequest? = null,
    currentLocation: GeoPoint? = null,
    followLocation: GeoPoint? = null,
    // Accepted and unused until the hosted map lands: the neutral signature is shared across
    // actuals, and the production one places its in-map compass with these insets. Recorded
    // deviation, design §8.
    compassTopInset: Dp = MAP_CONTROL_INSET,
    compassEndInset: Dp = MAP_CONTROL_INSET,
    trackOverlay: MapTrackOverlay? = null,
    onUserMovedCamera: () -> Unit = {},
    onFogRendered: ((FogViewportRender) -> Unit)? = null,
    onFogFailure: (Throwable) -> Unit = {},
    providerStartupDecisionForTesting: ProviderStartupDecision? = null,
    onMapReadyForTesting: ((GoogleMap) -> Unit)? = null,
    onMapViewCreatedForTesting: ((MapView) -> Unit)? = null,
    onMapLoadStateForTesting: ((BasemapLoadState) -> Unit)? = null,
    onFogStateForTesting: ((GoogleCanonicalFogState) -> Unit)? = null,
    onFogProofForTesting: ((GoogleFogProofObservation) -> Unit)? = null,
    onOverlayVisibilityForTesting: ((Boolean) -> Unit)? = null,
    onOverlayObservationForTesting: ((GoogleMapOverlayObservation) -> Unit)? = null,
    cameraRequestDurationMillisForTesting: Int? = null,
    fogCoverTimeoutMillisForTesting: Long = 20_000L,
) {
    require(fallbackTimeoutMillis > 0L) { "fallbackTimeoutMillis must be positive" }
    require(savedStateKey.isNotBlank()) { "savedStateKey must not be blank" }
    require(fogCoverTimeoutMillisForTesting > 0L) {
        "fogCoverTimeoutMillisForTesting must be positive"
    }
    val contentDescription = stringResource(R.string.map_content_description)
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier
                .semantics { this.contentDescription = contentDescription }
                .testTag(MapSurfaceTestTags.Map),
        )
        return
    }

    val context = LocalContext.current
    val decision = remember(context, providerStartupDecisionForTesting) {
        providerStartupDecisionForTesting ?: startupDecision(context)
    }
    var runtimeFailure by remember(savedStateKey) {
        mutableStateOf<ProviderFallbackReason?>(null)
    }
    val fallbackReason = decision.fallbackReason ?: runtimeFailure

    if (fallbackReason != null) {
        Box(modifier = modifier) {
            MapProviderUnavailableSurface(reason = fallbackReason)
        }
    } else {
        GoogleHostedMapSurface(
            modifier = modifier,
            savedStateKey = savedStateKey,
            fallbackTimeoutMillis = fallbackTimeoutMillis,
            fogRequired = fogRequired,
            fogRuntime = fogRuntime,
            cameraRequest = cameraRequest,
            currentLocation = currentLocation,
            followLocation = followLocation,
            trackOverlay = trackOverlay,
            onUserMovedCamera = onUserMovedCamera,
            onFogRendered = onFogRendered,
            onFogFailure = onFogFailure,
            onTerminalFailure = { failure -> runtimeFailure = failure },
            onMapReadyForTesting = onMapReadyForTesting,
            onMapViewCreatedForTesting = onMapViewCreatedForTesting,
            onMapLoadStateForTesting = onMapLoadStateForTesting,
            onFogStateForTesting = onFogStateForTesting,
            onFogProofForTesting = onFogProofForTesting,
            onOverlayVisibilityForTesting = onOverlayVisibilityForTesting,
            onOverlayObservationForTesting = onOverlayObservationForTesting,
            cameraRequestDurationMillisForTesting = cameraRequestDurationMillisForTesting,
            fogCoverTimeoutMillis = fogCoverTimeoutMillisForTesting,
        )
    }
}

private fun startupDecision(context: Context): ProviderStartupDecision {
    if (!BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED) {
        return ProviderRuntimeGate.startupDecision(
            keyConfigured = false,
            keyReason = BuildConfig.GOOGLE_MAPS_POC_KEY_REASON,
            hasValidatedNetwork = true,
            hasCompatibleServices = true,
        )
    }
    return ProviderRuntimeGate.startupDecision(
        keyConfigured = true,
        keyReason = BuildConfig.GOOGLE_MAPS_POC_KEY_REASON,
        hasValidatedNetwork = hasValidatedInternet(context),
        hasCompatibleServices = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS,
        initializeWithoutValidatedNetwork = true,
    )
}

private fun hasValidatedInternet(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = connectivity.activeNetwork ?: return false
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
