package app.trailveil.map

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.trailveil.map.fog.FogViewportRender
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The provider-neutral map surface contract.
 *
 * `V02-005`: every type here is shared between the per-variant `TrailVeilMapSurface` actuals -
 * one per map provider, each wired to its own build types - and by the two production call
 * sites and the JVM unit tests. Nothing in this file may name a provider, not even inside a
 * comment: `MapSurfaceNeutralSignatureTest` scans this file for provider marker substrings
 * and fails on any hit. Provider-specific test seams live with their actual; only the
 * neutral surface parameters and the helpers every actual genuinely shares belong here.
 */

internal enum class BasemapLoadState {
    LOADING,
    ONLINE,
    LOCAL_FALLBACK,
}

internal object MapSurfaceTestTags {
    const val Map = "trailveil_map"
    const val Status = "trailveil_map_status"
    const val FogSafetyCover = "trailveil_fog_safety_cover"
    const val ProviderUnavailable = "trailveil_map_provider_unavailable"
}

/** Provider-tagged saved-state envelope shared by every map-surface actual. */
internal const val MAP_SAVED_STATE_PROVIDER_KEY = "provider"
internal const val MAP_SAVED_STATE_PAYLOAD_KEY = "state"

internal data class MapCameraRequest(
    val requestId: Long,
    val point: GeoPoint,
    /** `null` moves the camera without touching the zoom the user chose. */
    val zoom: Double? = 16.0,
) {
    init {
        require(requestId >= 0L) { "requestId must be non-negative" }
        require(zoom == null || (zoom.isFinite() && zoom in 0.0..22.0)) {
            "zoom must be in 0..22"
        }
    }
}

/**
 * What a new location should do to a camera that is following it.
 *
 * Following is not the same kind of camera move as being sent somewhere. A follow step is bounded
 * by how far a person walked between two fixes, which is why it can be made without hiding the map
 * first; a jump to somewhere off screen is not, and goes through the ordinary programmed path with
 * everything that protects.
 */
internal enum class FollowCameraMove {
    /** Close enough to centred already; moving would only jitter the map under the user. */
    HOLD,
    EASE,
    JUMP,
}

/**
 * The dead zone is a fraction of the shorter viewport edge, so it means the same thing in portrait
 * and landscape: about a finger's width of drift before the map re-centres. Without one, a 5 m
 * location update would nudge the camera every few seconds and rebuild the fog with it.
 */
internal const val FOLLOW_DEAD_ZONE_FRACTION: Double = 0.12

internal fun followCameraMove(
    offsetX: Double,
    offsetY: Double,
    viewportWidth: Int,
    viewportHeight: Int,
): FollowCameraMove {
    if (viewportWidth <= 0 || viewportHeight <= 0) return FollowCameraMove.HOLD
    if (!offsetX.isFinite() || !offsetY.isFinite()) return FollowCameraMove.JUMP
    val halfWidth = viewportWidth / 2.0
    val halfHeight = viewportHeight / 2.0
    if (kotlin.math.abs(offsetX) > halfWidth || kotlin.math.abs(offsetY) > halfHeight) {
        return FollowCameraMove.JUMP
    }
    val deadZone = minOf(viewportWidth, viewportHeight) * FOLLOW_DEAD_ZONE_FRACTION
    val distance = kotlin.math.hypot(offsetX, offsetY)
    return if (distance <= deadZone) FollowCameraMove.HOLD else FollowCameraMove.EASE
}

internal data class MapTrackOverlay(
    val requestId: Long,
    val segments: List<List<GeoPoint>>,
) {
    init {
        require(requestId >= 0L) { "requestId must be non-negative" }
    }
}

internal suspend fun renderCanonicalFogWithRetry(
    request: FogViewportRequest,
    retryDelayMillis: Long,
    render: suspend (FogViewportRequest) -> FogViewportRender,
    installAndAwait: suspend (FogViewportRender) -> Unit,
    onFailure: (Exception) -> Unit,
): FogViewportRender = retryFogOperation(retryDelayMillis, onFailure) {
    render(request).also { rendered -> installAndAwait(rendered) }
}

internal suspend fun <T> retryFogOperation(
    retryDelayMillis: Long,
    onFailure: (Exception) -> Unit,
    operation: suspend () -> T,
): T {
    require(retryDelayMillis >= 0L) { "retryDelayMillis must be non-negative" }
    while (true) {
        try {
            return operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
            delay(retryDelayMillis)
        }
    }
}

/** No programmed camera flight is in the air. */
internal const val IDLE_CAMERA_FLIGHT = 0L

/**
 * Short enough that the user stays with the map rather than watching it catch up, long enough that
 * the step reads as the map following them rather than as the map jumping.
 */
internal const val FOLLOW_EASE_MILLIS = 450

/** Where the map's own controls sit when the host does not stack anything of its own on top. */
internal val MAP_CONTROL_INSET: Dp = 12.dp

@Composable
internal fun MapStatusBadge(text: String) {
    Surface(
        modifier = Modifier
            .padding(12.dp)
            .testTag(MapSurfaceTestTags.Status),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.small,
        shadowElevation = 2.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
