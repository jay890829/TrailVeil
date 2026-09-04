package app.trailveil.map

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.FogRuntime
import app.trailveil.ui.theme.TrailVeilTheme
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Unexported googlePoc-only host for real Activity recreation and forced terminal paths. */
internal class GoogleMapSurfaceTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Stage 9: a test may replay a bundle captured from a previous instance's
        // onSaveInstanceState as if the framework had restored it. This is the only seam through
        // which the provider-tag envelope filter can be exercised with a REAL envelope; the
        // framework offers no way to inject one into a launch.
        val planted = GoogleMapSurfaceTestHooks.plantedSavedInstanceState
        GoogleMapSurfaceTestHooks.plantedSavedInstanceState = null
        super.onCreate(planted ?: savedInstanceState)
        setContent {
            TrailVeilTheme {
                val screen = GoogleMapSurfaceTestHooks.content.get()
                if (screen != null) {
                    screen()
                    return@TrailVeilTheme
                }
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    fogRequired = GoogleMapSurfaceTestHooks.fogRequired,
                    fogRuntime = GoogleMapSurfaceTestHooks.fogRuntime,
                    fallbackTimeoutMillis = GoogleMapSurfaceTestHooks.fallbackTimeoutMillis,
                    fogCoverTimeoutMillisForTesting =
                        GoogleMapSurfaceTestHooks.fogCoverTimeoutMillis,
                    providerStartupDecisionForTesting = GoogleMapSurfaceTestHooks.decision.get(),
                    cameraRequest = GoogleMapSurfaceTestHooks.cameraRequestState.value
                        ?: GoogleMapSurfaceTestHooks.cameraRequest,
                    currentLocation = GoogleMapSurfaceTestHooks.currentLocationState.value
                        ?: GoogleMapSurfaceTestHooks.currentLocation,
                    followLocation = GoogleMapSurfaceTestHooks.followLocationState.value
                        ?: GoogleMapSurfaceTestHooks.followLocation,
                    trackOverlay = GoogleMapSurfaceTestHooks.trackOverlay,
                    onUserMovedCamera = {
                        GoogleMapSurfaceTestHooks.userMovedCount.incrementAndGet()
                    },
                    // Consulted per attach rather than captured, so a case can arm the fault after
                    // a generation has already been proven and release it again without disturbing
                    // the composition. Nothing is thrown while the hook is null.
                    fogInstallFaultForTesting = {
                        GoogleMapSurfaceTestHooks.fogInstallFault?.invoke()
                    },
                    onTerminalFailureForTesting = { reason ->
                        GoogleMapSurfaceTestHooks.onTerminalFailure.get()?.invoke(reason)
                    },
                    onMapReadyForTesting = { map ->
                        GoogleMapSurfaceTestHooks.onMapReady.get()?.invoke(map)
                    },
                    onMapViewCreatedForTesting = { view ->
                        GoogleMapSurfaceTestHooks.onMapViewCreated.get()?.invoke(view)
                    },
                    onMapLoadStateForTesting = { state ->
                        GoogleMapSurfaceTestHooks.onMapLoadState.get()?.invoke(state)
                    },
                    onFogStateForTesting = { state ->
                        GoogleMapSurfaceTestHooks.onFogState.get()?.invoke(state)
                    },
                    onFogProofForTesting = { observation ->
                        GoogleMapSurfaceTestHooks.onFogProof.get()?.invoke(observation)
                    },
                    onOverlayVisibilityForTesting = { visible ->
                        GoogleMapSurfaceTestHooks.onOverlayVisibility.get()?.invoke(visible)
                    },
                    onOverlayObservationForTesting = { observation ->
                        GoogleMapSurfaceTestHooks.onOverlayObservation.get()?.invoke(observation)
                    },
                    cameraRequestDurationMillisForTesting =
                        GoogleMapSurfaceTestHooks.cameraRequestDurationMillis,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        GoogleMapSurfaceTestHooks.onSaveInstanceState.get()?.invoke(outState)
    }
}

internal object GoogleMapSurfaceTestHooks {
    val decision = AtomicReference<ProviderStartupDecision?>(null)
    val onMapReady = AtomicReference<((GoogleMap) -> Unit)?>(null)
    val onTerminalFailure = AtomicReference<((ProviderFallbackReason) -> Unit)?>(null)
    val onMapViewCreated = AtomicReference<((MapView) -> Unit)?>(null)
    val onMapLoadState = AtomicReference<((BasemapLoadState) -> Unit)?>(null)
    val onFogState = AtomicReference<((GoogleCanonicalFogState) -> Unit)?>(null)
    val onFogProof = AtomicReference<((GoogleFogProofObservation) -> Unit)?>(null)
    val onOverlayVisibility = AtomicReference<((Boolean) -> Unit)?>(null)
    val onOverlayObservation =
        AtomicReference<((GoogleMapOverlayObservation) -> Unit)?>(null)

    /**
     * Replaces this activity's whole content, themed as the app themes it.
     *
     * `V02-007`. `createComposeRule` launches `androidx.activity.ComponentActivity` from the test
     * apk, and instrumentation targeting `app.trailveil` cannot start an activity that resolves in
     * `app.trailveil.test`. The artifact that declares it, `compose-ui-test-manifest`, would have
     * to be a dependency of the APP for that activity to exist app-side - and this build type
     * becomes the shipped Google variant in `V02-008`, so it does not get one. This activity is
     * already the variant's unexported harness host; letting a case supply the composable it wants
     * hosted costs one branch here and adds no new surface. Cleared by [reset], because a
     * composable left behind would silently replace the map for every case after it.
     */
    val content = AtomicReference<(@Composable () -> Unit)?>(null)
    val userMovedCount = AtomicInteger(0)
    @Volatile var fogRequired: Boolean = false
    @Volatile var fogRuntime: FogRuntime? = null
    @Volatile var fogCoverTimeoutMillis: Long = 20_000L
    /**
     * The host's basemap load deadline. Generous by default so ordinary cases are never raced by
     * it; a case that drives a MISSED deadline shortens it so its own window stays bounded and
     * stays clearly inside [fogCoverTimeoutMillis], which would otherwise terminate the surface
     * for an unrelated reason.
     */
    @Volatile var fallbackTimeoutMillis: Long = 30_000L
    @Volatile var cameraRequest: MapCameraRequest? = null
    @Volatile var currentLocation: GeoPoint? = null
    @Volatile var followLocation: GeoPoint? = null
    @Volatile var trackOverlay: MapTrackOverlay? = null
    @Volatile var cameraRequestDurationMillis: Int? = null

    /**
     * Rejects the next canonical overlay installs by throwing; `null` lets every install through.
     *
     * Read on the main thread inside the overlay attach, written from the instrumentation thread,
     * hence `@Volatile`. [reset] releases it, so a case that fails an assertion with a fault still
     * armed cannot leave later cases faulting installs for no stated reason.
     */
    @Volatile var fogInstallFault: (() -> Unit)? = null
    val currentLocationState = mutableStateOf<GeoPoint?>(null)
    val followLocationState = mutableStateOf<GeoPoint?>(null)
    val cameraRequestState = mutableStateOf<MapCameraRequest?>(null)

    /** Replayed exactly once by the next `GoogleMapSurfaceTestActivity.onCreate`, then cleared. */
    @Volatile var plantedSavedInstanceState: Bundle? = null
    val onSaveInstanceState = AtomicReference<((Bundle) -> Unit)?>(null)

    fun reset() {
        decision.set(null)
        onMapReady.set(null)
        onTerminalFailure.set(null)
        onMapViewCreated.set(null)
        onMapLoadState.set(null)
        onFogState.set(null)
        onFogProof.set(null)
        onOverlayVisibility.set(null)
        onOverlayObservation.set(null)
        content.set(null)
        userMovedCount.set(0)
        fogRequired = false
        fogRuntime = null
        fogCoverTimeoutMillis = 20_000L
        fallbackTimeoutMillis = 30_000L
        cameraRequest = null
        currentLocation = null
        followLocation = null
        trackOverlay = null
        cameraRequestDurationMillis = null
        fogInstallFault = null
        currentLocationState.value = null
        followLocationState.value = null
        cameraRequestState.value = null
        plantedSavedInstanceState = null
        onSaveInstanceState.set(null)
    }
}
