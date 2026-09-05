package app.trailveil.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.trailveil.R
import app.trailveil.map.fog.reconcileFogOverlayCoordinatorState
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogViewportRender
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.planTrackCameraBounds
import app.trailveil.map.fog.WebMercator
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.CancelableCallback
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

private const val MAP_STATE_PROVIDER = "google"
private const val DETAIL_SINGLE_POINT_ZOOM = 16.0f
private const val DETAIL_BOUNDS_PADDING_PX = 72
private const val MAP_LOADED_CALLBACK_RETRY_MILLIS = 100L

/** One host claim plus the optional provider-policy claim owned by the binding at launch time. */
private class CameraFlightClaim(
    private val host: MapCameraFlightController,
    private val hostTicket: Long,
    private val releaseOwner: (() -> Unit)?,
    private val onHostActiveChanged: (Boolean) -> Unit,
) {
    fun release() {
        // Both releases are idempotent CAS operations. A superseded callback therefore cannot
        // clear either the newer host flight or the newer provider-policy ticket.
        releaseOwner?.invoke()
        if (host.release(hostTicket)) onHostActiveChanged(false)
    }
}

private fun claimCameraFlight(
    host: MapCameraFlightController,
    owner: GoogleCanonicalFogSurfaceBinding?,
    beginOwner: (GoogleCanonicalFogSurfaceBinding) -> Long,
    endOwner: (GoogleCanonicalFogSurfaceBinding, Long) -> Boolean,
    onHostActiveChanged: (Boolean) -> Unit,
): CameraFlightClaim {
    val hostTicket = host.claim()
    onHostActiveChanged(true)
    val ownerTicket = if (owner == null) {
        null
    } else {
        try {
            beginOwner(owner)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
    return CameraFlightClaim(
        host = host,
        hostTicket = hostTicket,
        releaseOwner = if (owner != null && ownerTicket != null) {
            {
                try {
                    endOwner(owner, ownerTicket)
                } catch (_: Exception) {
                    // Binding replacement/release may race the SDK terminal callback.
                } catch (_: LinkageError) {
                    // Keep the host CAS release independent of provider teardown.
                }
            }
        } else {
            null
        },
        onHostActiveChanged = onHostActiveChanged,
    )
}

/** Logcat tag of the googlePoc-only map-ready breadcrumb read by the process-death driver. */
private const val MAP_READY_LOG_TAG = "TrailVeilMapReady"

// LogNotTimber: the breadcrumb stays on android.util.Log deliberately. TrailVeil plants no Timber
// tree (Timber only arrives transitively through a map SDK), so a Timber call would log nothing, and
// `.github/scripts/verify-process-death-restoration.sh` reads this exact tag with
// `adb logcat -s TrailVeilMapReady:I`. Counts and booleans only; never a coordinate.
@Suppress("LogNotTimber")
private fun logMapReady(mapView: View, restoredState: Bundle?) {
    Log.i(
        MAP_READY_LOG_TAG,
        "map-ready restored=${restoredState != null}" +
            " keys=${restoredState?.keySet()?.size ?: 0}" +
            " cameraDefault=${mapView.getTag(R.id.map_camera_default_at_ready)}",
    )
}

/** Composition-owned hosted map with the production canonical-fog binding. */
@Composable
internal fun GoogleHostedMapSurface(
    modifier: Modifier,
    savedStateKey: String,
    fallbackTimeoutMillis: Long,
    fogRequired: Boolean,
    fogRuntime: FogRuntime?,
    cameraRequest: MapCameraRequest?,
    currentLocation: GeoPoint?,
    followLocation: GeoPoint?,
    trackOverlay: MapTrackOverlay?,
    compassTopInset: Dp,
    compassEndInset: Dp,
    onUserMovedCamera: () -> Unit,
    onFogRendered: ((FogViewportRender) -> Unit)?,
    onFogFailure: (Throwable) -> Unit,
    onTerminalFailure: (ProviderFallbackReason) -> Unit,
    fogInstallFaultForTesting: (() -> Unit)?,
    onMapReadyForTesting: ((GoogleMap) -> Unit)?,
    onMapViewCreatedForTesting: ((MapView) -> Unit)?,
    onMapLoadStateForTesting: ((BasemapLoadState) -> Unit)?,
    onFogStateForTesting: ((GoogleCanonicalFogState) -> Unit)?,
    onFogProofForTesting: ((GoogleFogProofObservation) -> Unit)?,
    onOverlayVisibilityForTesting: ((Boolean) -> Unit)?,
    onOverlayObservationForTesting: ((GoogleMapOverlayObservation) -> Unit)?,
    cameraRequestDurationMillisForTesting: Int?,
    fogCoverTimeoutMillis: Long,
) {
    val contentDescription = stringResource(R.string.map_content_description)
    require(fogCoverTimeoutMillis > 0L) { "fogCoverTimeoutMillis must be positive" }
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier
                .semantics { this.contentDescription = contentDescription }
                .testTag(MapSurfaceTestTags.Map),
        )
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val registry = LocalSavedStateRegistryOwner.current.savedStateRegistry
    val restoredState = remember(registry, savedStateKey) {
        registry.consumeRestoredStateForKey(savedStateKey)
            ?.takeIf { envelope ->
                envelope.getString(MAP_SAVED_STATE_PROVIDER_KEY) == MAP_STATE_PROVIDER
            }
            ?.getBundle(MAP_SAVED_STATE_PAYLOAD_KEY)
    }
    val mapViewResult = remember(context, lifecycle, registry, savedStateKey) {
        runCatching {
            GestureOwningGoogleMapView(context).apply { onCreate(restoredState) }
        }
    }
    val mapView = mapViewResult.getOrNull()
    val currentOnUserMovedCamera by rememberUpdatedState(onUserMovedCamera)
    val currentOnFogRendered by rememberUpdatedState(onFogRendered)
    val currentOnFogFailure by rememberUpdatedState(onFogFailure)
    val currentOnTerminalFailure by rememberUpdatedState(onTerminalFailure)
    val currentOnMapReadyForTesting by rememberUpdatedState(onMapReadyForTesting)
    val currentOnMapViewCreatedForTesting by rememberUpdatedState(onMapViewCreatedForTesting)
    val currentOnMapLoadStateForTesting by rememberUpdatedState(onMapLoadStateForTesting)
    val currentOnFogStateForTesting by rememberUpdatedState(onFogStateForTesting)
    val currentOnFogProofForTesting by rememberUpdatedState(onFogProofForTesting)
    val currentTrackOverlay by rememberUpdatedState(trackOverlay)

    if (mapView == null) {
        LaunchedEffect(mapViewResult.exceptionOrNull()) {
            currentOnTerminalFailure(ProviderFallbackReason.INITIALIZATION_FAILURE)
        }
        Box(modifier = modifier.fillMaxSize().background(FogSurfaceColor))
        return
    }

    var loadState by remember(mapView) { mutableStateOf(BasemapLoadState.LOADING) }
    var fogState by remember(mapView, fogRequired) {
        mutableStateOf<GoogleCanonicalFogState?>(null)
    }
    var fogCoverUp by remember(mapView, fogRequired) { mutableStateOf(fogRequired) }
    var readyMap by remember(mapView) { mutableStateOf<GoogleMap?>(null) }
    var detailMapLoaded by remember(mapView, fogRequired) { mutableStateOf(false) }
    val compositionActive = remember(mapView) { AtomicBoolean(true) }
    val mapCallbackEpoch = remember(mapView) { AtomicLong(0L) }
    var fogFailureCount by remember(mapView, fogRequired) { mutableIntStateOf(0) }
    // This claim belongs to the composition, not the provider binding. The fog owner may be
    // replaced when its runtime changes from null to ready while a camera animation is airborne.
    val cameraFlightController = remember(mapView) { MapCameraFlightController() }
    // Detail framing is one-shot per persisted track request. This epoch fences a delayed layout
    // wait from an older detail composition/track so it cannot fit a stale track after the route
    // supplies a newer request id.
    val detailFitEpoch = remember(mapView) { AtomicLong(0L) }
    val detailFitRequestId = remember(mapView) { AtomicLong(Long.MIN_VALUE) }
    val synchronousFogCover = remember(mapView) { GoogleFogSafetyOverlay(mapView) }
    val compassPlacement = remember(mapView) { GoogleCompassPlacement(mapView) }
    var binding by remember(mapView) { mutableStateOf<GoogleMapSurfaceBinding?>(null) }
    var fogBinding by remember(mapView) {
        mutableStateOf<GoogleCanonicalFogSurfaceBinding?>(null)
    }

    // Re-stated on every composition rather than launched once: the SDK gives its compass no
    // size until the camera carries a bearing or a tilt, so the first placement usually lands on a
    // zero-size view. `GoogleCompassPlacement` also watches layout passes for that reason; this is
    // what carries a CHANGED inset to it.
    val compassTopInsetPx = with(LocalDensity.current) { compassTopInset.roundToPx() }
    val compassEndInsetPx = with(LocalDensity.current) { compassEndInset.roundToPx() }
    // The composition's direction, which is what resolves `Alignment.End` for the screen's own
    // controls, rather than the SDK view's - so the compass lands on the same edge as the button
    // it is placed beneath even if the SDK lays its decorations out unmirrored.
    val compassRightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl

    SideEffect {
        compassPlacement.setInsets(compassTopInsetPx, compassEndInsetPx, compassRightToLeft)
        synchronousFogCover.setVisible(fogRequired && fogCoverUp)
        val overlayGeometryChanged = binding?.updateOverlays(
            currentLocation = currentLocation,
            trackOverlay = trackOverlay,
        ) == true
        if (overlayGeometryChanged && fogBinding != null) {
            // Geometry does not change canonical fog. Keep an already-proven overlay visible for
            // existing behavior parity; if a proof is already running, its camera epoch forces a
            // fresh attempt to use the latest screen footprint before that proof can be accepted.
            fogBinding?.onOverlayDataChanged()
        }
        if (!fogRequired) {
            // Detail maps are intentionally fog-free (the existing behavior). The main map's
            // overlays remain hidden until the canonical binding reports a proven generation.
            binding?.showOverlaysWithoutFogProof()
        }
        mapView.setTag(
            R.id.map_fog_canonical_generation,
            fogState?.installedGeneration?.toString(),
        )
        mapView.setTag(
            R.id.map_fog_active_slot,
            fogState?.pendingGeneration?.toString(),
        )
        mapView.setTag(R.id.map_fog_cover_up, fogCoverUp)
        // The binding's whole published state in one label: what it is waiting for, whether it
        // has given up, and whether a retry is armed. Names and booleans only.
        mapView.setTag(
            R.id.map_fog_phase,
            fogState?.let { state ->
                "pending=${state.pendingGeneration} reason=${state.coverReason} " +
                    "terminal=${state.terminal} retry=${state.retryScheduled}"
            } ?: "no-state",
        )
        // The binding's private render gates (booleans and ids), read by the same failure messages.
        mapView.setTag(R.id.map_fog_binding_gates, fogBinding?.describeForTesting() ?: "no-binding")
        mapView.setTag(
            R.id.map_fog_last_cover_interval_ms,
            fogState?.lastCoverIntervalMillis,
        )
        mapView.setTag(R.id.map_camera_flight_active, cameraFlightController.isActive)
        mapView.setTag(R.id.map_basemap_load_state, loadState.name)
        // Whether this MapView was created from a provider-tagged envelope, and which SDK keys the
        // payload carried (names only). A camera that comes back as the default after recreation
        // has two opposite causes - no envelope arrived, or one arrived and was overridden - and
        // only this tag tells them apart.
        mapView.setTag(
            R.id.map_saved_state_restored,
            restoredState?.let { payload -> "true keys=" + payload.keySet().sorted().joinToString("|") }
                ?: "false",
        )
        // Per-composition view of the fog inputs, independent of any SDK callback: whether a runtime
        // has reached this surface at all, and how many times the fog effect has run for this view.
        mapView.setTag(R.id.map_fog_runtime_present, fogRuntime != null)
        mapView.setTag(R.id.map_fog_effect_epoch, mapCallbackEpoch.get())
        if (fogRequired) {
            mapView.setTag(R.id.map_detail_map_loaded, null)
        } else if (mapView.getTag(R.id.map_detail_map_loaded) != true) {
            // Do not overwrite a callback-owned true value during the following recomposition.
            mapView.setTag(R.id.map_detail_map_loaded, detailMapLoaded)
        }
        currentOnMapViewCreatedForTesting?.invoke(mapView)
        currentOnMapLoadStateForTesting?.invoke(loadState)
    }

    /** Fits one loaded detail map on the main looper, retrying layout only within a finite budget. */
    fun fitLoadedDetailMap(
        map: GoogleMap,
        effectEpoch: Long,
        layoutDeadlineMillis: Long = SystemClock.uptimeMillis() + fallbackTimeoutMillis,
    ) {
        if (
            fogRequired ||
                !compositionActive.get() ||
                mapCallbackEpoch.get() != effectEpoch ||
                mapView.getTag(R.id.map_detail_map_loaded) != true
        ) return
        if (!mapView.isLaidOut || mapView.width <= 0 || mapView.height <= 0) {
            if (SystemClock.uptimeMillis() < layoutDeadlineMillis) {
                mapView.postDelayed(
                    { fitLoadedDetailMap(map, effectEpoch, layoutDeadlineMillis) },
                    MAP_LOADED_CALLBACK_RETRY_MILLIS,
                )
            }
            return
        }
        val overlay = currentTrackOverlay ?: return
        val points = overlay.segments
            .flatten()
            .filter { point -> point.latitude in -90.0..90.0 }
            .distinctBy { point -> point.latitude to WebMercator.wrapLongitude(point.longitude) }
        val plan = planTrackCameraBounds(points) ?: return
        if (detailFitRequestId.get() == overlay.requestId) return
        // The same persisted request is framed once. A changed request id replaces the marker and
        // is allowed through below; all camera work remains on the MapView's main handler.
        detailFitRequestId.set(overlay.requestId)
        val fitEpoch = detailFitEpoch.incrementAndGet()
        val update = if (points.size == 1) {
            CameraUpdateFactory.newLatLngZoom(
                LatLng(plan.southLatitude, plan.westLongitude),
                DETAIL_SINGLE_POINT_ZOOM,
            )
        } else {
            CameraUpdateFactory.newLatLngBounds(
                com.google.android.gms.maps.model.LatLngBounds(
                    LatLng(plan.southLatitude, plan.westLongitude),
                    LatLng(plan.northLatitude, plan.eastLongitude),
                ),
                DETAIL_BOUNDS_PADDING_PX,
            )
        }
        mapView.post {
            if (
                compositionActive.get() &&
                    mapCallbackEpoch.get() == effectEpoch &&
                    detailFitEpoch.get() == fitEpoch
            ) {
                runCatching {
                    map.moveCamera(update)
                }
            }
        }
    }

    SideEffect {
        if (!fogRequired && detailMapLoaded) {
            val map = mapView.getTag(R.id.map_detail_map_instance) as? GoogleMap
            if (map != null) fitLoadedDetailMap(map, mapCallbackEpoch.get())
        }
    }

    DisposableEffect(mapView, lifecycle, registry, savedStateKey) {
        compositionActive.set(true)
        // Under a NavHost the back-stack entry's lifecycle can reach DESTROYED - and this binding
        // forwards that to MapView.onDestroy() - BEFORE the framework collects saved state for a
        // recreation (measured on API 36: entry ON_DESTROY, then the save 35 ms later). The Google
        // SDK writes nothing after destroy, so a live save at that point persists an EMPTY payload
        // and the restored map comes back at the default camera. Every path to destroy passes
        // through ON_STOP first, while the SDK view is still alive, so the state is snapshotted
        // there (in the lifecycle observer below) and the provider serves the snapshot once the
        // view is gone. The shipped provider's surface never needed this: its camera lives in
        // fields that survive its own destroy.
        var stoppedStateSnapshot: Bundle? = null
        val lifecycleBinding = GoogleMapViewLifecycleBinding(
            mapView = mapView,
            onHostStarted = { fogBinding?.onHostStarted() },
            onHostStopped = { fogBinding?.onHostStopped() },
        )
        registry.registerSavedStateProvider(savedStateKey) {
            val payload = if (lifecycleBinding.isDestroyed) {
                stoppedStateSnapshot ?: Bundle()
            } else {
                Bundle().also(mapView::onSaveInstanceState)
            }
            // What the SDK handed back at save time, on the view that was saved: an empty payload
            // after recreation has two opposite causes - the SDK view was already destroyed, or
            // it was alive and wrote nothing - and only this tells them apart. Counts and
            // booleans only; it survives on the old view for a test that kept the reference.
            mapView.setTag(
                R.id.map_saved_state_last_save,
                "keys=${payload.keySet().size} destroyed=${lifecycleBinding.isDestroyed} " +
                    "started=${lifecycleBinding.isStarted} attached=${mapView.isAttachedToWindow} " +
                    "entryState=${lifecycle.currentState} at=${SystemClock.uptimeMillis()}",
            )
            Bundle().apply {
                putString(MAP_SAVED_STATE_PROVIDER_KEY, MAP_STATE_PROVIDER)
                putBundle(MAP_SAVED_STATE_PAYLOAD_KEY, payload)
            }
        }
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() {
                if (compositionActive.get()) mapView.onLowMemory()
            }
            override fun onTrimMemory(level: Int) = Unit
        }
        context.applicationContext.registerComponentCallbacks(callbacks)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !lifecycleBinding.isDestroyed) {
                // Before the binding forwards the stop, while the SDK view still answers.
                stoppedStateSnapshot = Bundle().also(mapView::onSaveInstanceState)
            }
            if (event == Lifecycle.Event.ON_DESTROY) {
                mapView.setTag(R.id.map_entry_destroyed_at, SystemClock.uptimeMillis())
                // Frames only: which host path destroyed the entry. Nothing positional.
                mapView.setTag(
                    R.id.map_entry_destroy_stack,
                    Throwable().stackTrace.drop(1).take(14).joinToString("<-") { frame ->
                        frame.className.substringAfterLast('.') + "." + frame.methodName
                    },
                )
            }
            lifecycleBinding.onEvent(event)
        }
        lifecycle.addObserver(observer)
        lifecycleBinding.synchronize(lifecycle.currentState)

        onDispose {
            mapView.setTag(R.id.map_disposed_at, SystemClock.uptimeMillis())
            compositionActive.set(false)
            binding?.release()
            binding = null
            fogBinding?.release()
            fogBinding = null
            synchronousFogCover.release()
            compassPlacement.release()
            lifecycle.removeObserver(observer)
            registry.unregisterSavedStateProvider(savedStateKey)
            context.applicationContext.unregisterComponentCallbacks(callbacks)
            lifecycleBinding.release()
        }
    }

    DisposableEffect(mapView, fogRuntime, fogRequired) {
        val effectEpoch = mapCallbackEpoch.incrementAndGet()
        var effectBinding: GoogleMapSurfaceBinding? = null
        var effectFogBinding: GoogleCanonicalFogSurfaceBinding? = null
        var earlyLoadedMap: GoogleMap? = null
        var loadedCallbackRetry: Runnable? = null
        try {
            mapView.getMapAsync { map ->
                if (
                    !compositionActive.get() || mapCallbackEpoch.get() != effectEpoch
                ) return@getMapAsync
                // Keep the SDK handle on the same MapView that owns the callback signal. The
                // detail fit reads this handle from the view after callback/layout gating instead
                // of depending on a renderer callback to synchronously recompose Compose.
                mapView.setTag(R.id.map_detail_map_instance, map)
                // First delivery only: was the camera still the SDK default when the map came
                // back? True with a restored envelope means the restore was never applied; false
                // followed by a later default means something overrode it. Boolean only.
                if (mapView.getTag(R.id.map_camera_default_at_ready) == null) {
                    val camera = map.cameraPosition
                    mapView.setTag(
                        R.id.map_camera_default_at_ready,
                        camera.target.latitude == 0.0 &&
                            camera.target.longitude == 0.0 &&
                            camera.zoom <= 2.5f,
                    )
                }
                // Host-observable, coordinate-free breadcrumb for the process-death driver script:
                // whether a provider-tagged envelope was restored, how many SDK keys its payload
                // carried, and whether the camera is still the SDK default. Counts and booleans.
                logMapReady(mapView, restoredState)
                try {
                    // Register the detail load callback before constructing the binding. A Google
                    // map can finish its first frame before getMapAsync returns; early registration
                    // preserves the real OnMapLoadedCallback signal instead of treating onMapReady
                    // as an equivalent.
                    val onGoogleMapLoaded: () -> Unit = {
                        val publishLoaded: () -> Unit = {
                            if (
                                compositionActive.get() &&
                                    mapCallbackEpoch.get() == effectEpoch
                            ) {
                                loadState = BasemapLoadState.ONLINE
                                mapView.setTag(
                                    R.id.map_basemap_load_state,
                                    BasemapLoadState.ONLINE.name,
                                )
                                synchronousFogCover.positionAttributionAboveSystemBars()
                                if (!fogRequired) {
                                    detailMapLoaded = true
                                    mapView.setTag(R.id.map_detail_map_loaded, true)
                                    fitLoadedDetailMap(map, effectEpoch)
                                }
                            }
                        }
                        // The SDK contract does not make the callback thread an ownership seam;
                        // publish Compose state on the map view's main looper. The callback itself
                        // remains the only source of ONLINE/detail-loaded state (never onMapReady).
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            publishLoaded()
                        } else {
                            mapView.post(publishLoaded)
                        }
                    }
                    if (!fogRequired) {
                        // Detail must claim the SDK callback before constructing the binding: the
                        // binding's other listener setup must not overwrite the one-shot callback
                        // before the real renderer reports its first loaded frame. Main surfaces
                        // use the binding callback below because they also need to notify fog.
                        map.setOnMapLoadedCallback(onGoogleMapLoaded)
                        earlyLoadedMap = map
                        // A map can be ready while its first render callback is already queued.
                        // Re-register on the same generation until the real callback is observed;
                        // this is still callback-driven and bounded by the existing load timeout,
                        // never an onMapReady substitute.
                        val retryDeadline = SystemClock.uptimeMillis() + fallbackTimeoutMillis
                        lateinit var retry: Runnable
                        retry = Runnable {
                            if (
                                    compositionActive.get() &&
                                    mapCallbackEpoch.get() == effectEpoch &&
                                    !detailMapLoaded &&
                                    SystemClock.uptimeMillis() < retryDeadline
                            ) {
                                map.setOnMapLoadedCallback(onGoogleMapLoaded)
                                mapView.postDelayed(retry, MAP_LOADED_CALLBACK_RETRY_MILLIS)
                            }
                        }
                        loadedCallbackRetry = retry
                        mapView.postDelayed(retry, MAP_LOADED_CALLBACK_RETRY_MILLIS)
                    }
                    // The map binding owns overlay geometry and can therefore provide the
                    // proof planner with screen footprints. Construct it before the fog binding;
                    // camera callbacks capture the nullable binding until the latter exists.
                    var newFogBinding: GoogleCanonicalFogSurfaceBinding? = null
                    val newBinding = GoogleMapSurfaceBinding(
                        map = map,
                        mapView = mapView,
                        density = density,
                        onOverlayVisibilityForTesting = onOverlayVisibilityForTesting,
                        onOverlayObservationForTesting = onOverlayObservationForTesting,
                        installMapLoadedListener = fogRequired,
                        onUserMovedCamera = { currentOnUserMovedCamera() },
                        onMapLoaded = {
                            if (compositionActive.get()) {
                                onGoogleMapLoaded()
                                newFogBinding?.onMapLoaded()
                            }
                        },
                        onCameraMoveStarted = { reason ->
                            newFogBinding?.onCameraMoveStarted(reason)
                        },
                        onCameraMoveFrame = { newFogBinding?.onCameraMoveFrame() },
                        onCameraIdle = { newFogBinding?.onCameraIdle() },
                        onCameraMoveCancelled = { newFogBinding?.onCameraMoveCancelled() },
                    )
                    newFogBinding = if (fogRequired && fogRuntime != null) {
                        GoogleCanonicalFogSurfaceBinding(
                            map = map,
                            runtime = fogRuntime,
                            onStateChanged = { state ->
                                if (
                                    compositionActive.get() &&
                                    mapCallbackEpoch.get() == effectEpoch
                                ) {
                                    // Synchronous ViewOverlay first; Compose state follows for
                                    // semantics and diagnostics without an exposed renderer frame.
                                    synchronousFogCover.setVisible(state.coverUp)
                                    // State publication is authoritative only for the unsafe
                                    // direction. A healthy snapshot can be emitted by a camera
                                    // callback while an installed-generation re-proof is still in
                                    // flight; only onProofAccepted may reopen hidden overlays.
                                    reconcileFogOverlayCoordinatorState(
                                        installedGeneration = state.installedGeneration,
                                        pendingGeneration = state.pendingGeneration,
                                        coverUp = state.coverUp,
                                        retryScheduled = state.retryScheduled,
                                        terminal = state.terminal,
                                    ) { newBinding.hideOverlaysUntilProof() }
                                    fogState = state
                                    fogCoverUp = state.coverUp
                                    currentOnFogStateForTesting?.invoke(state)
                                }
                            },
                            onTerminalFailure = {
                                if (
                                    compositionActive.get() &&
                                    mapCallbackEpoch.get() == effectEpoch
                                ) {
                                    newBinding.hideOverlaysUntilProof()
                                    currentOnTerminalFailure(
                                        ProviderFallbackReason.INITIALIZATION_FAILURE,
                                    )
                                }
                            },
                            onFogFailure = { failure ->
                                // Coordinate-free failure breadcrumb. A surface that retries a
                                // failing stage at 1 Hz looks identical from outside to one that is
                                // simply idle, and the two have opposite causes. Class name and a
                                // count only; no message, no stack, nothing positional.
                                fogFailureCount += 1
                                mapView.setTag(
                                    R.id.map_fog_last_failure,
                                    "${failure.javaClass.simpleName} n=$fogFailureCount",
                                )
                                currentOnFogFailure(failure)
                            },
                            onFogRendered = { rendered ->
                                currentOnFogRendered?.invoke(rendered)
                            },
                            onProofObserved = { observation ->
                                currentOnFogProofForTesting?.invoke(observation)
                            },
                            // Read once, when this binding is built. Deliberately not a
                            // DisposableEffect key: re-keying would tear down and rebuild the whole
                            // map binding, which is not what arming an install fault should mean. A
                            // host that needs to arm and release one mid-composition therefore
                            // passes a stable lambda that consults its own switch.
                            installFaultForTesting = fogInstallFaultForTesting,
                            exclusionZonesForProof = newBinding::exclusionZonesForProof,
                            onUnprovableProofPlan = newBinding::hideOverlaysUntilProof,
                            onProofAccepted = { generation ->
                                if (
                                    compositionActive.get() &&
                                    mapCallbackEpoch.get() == effectEpoch
                                ) {
                                    newBinding.revealOverlaysForGeneration(generation)
                                }
                            },
                        )
                    } else {
                        null
                    }
                    // Why the fog binding does or does not exist on this surface. Without this, a
                    // surface with the cover permanently up and no generation is indistinguishable
                    // from one whose binding was never constructed, and the two have opposite
                    // causes. Cheap, coordinate-free, and read by GoogleAttributionVisibleTest.
                    mapView.setTag(
                        R.id.map_fog_binding_state,
                        "epoch=$effectEpoch required=$fogRequired " +
                            "runtime=${fogRuntime != null} built=${newFogBinding != null}",
                    )
                    effectFogBinding = newFogBinding
                    fogBinding = newFogBinding
                    // The binding object itself, for the stage-9 tests' failure messages: read
                    // live at failure time (booleans, ids and counts only), and deliberately not
                    // cleared on dispose so a surface that already fell back can still explain
                    // the install phase it never finished.
                    mapView.setTag(R.id.map_fog_binding_instance, newFogBinding)
                    newBinding.updateOverlays(currentLocation, trackOverlay)
                    if (!fogRequired) {
                        newBinding.showOverlaysWithoutFogProof()
                    } else {
                        newBinding.hideOverlaysUntilProof()
                    }
                    if (loadState == BasemapLoadState.ONLINE) {
                        newFogBinding?.onMapLoaded()
                    }
                    // Replay BOTH lifecycle cases. getMapAsync lands whenever Play services chooses,
                    // so a binding can be born after an ON_STOP that `fogBinding?.onHostStopped()`
                    // silently dropped against a null receiver. Replaying only STARTED left such a
                    // binding believing the host was running, arming bounded deadlines a stopped
                    // renderer can never satisfy.
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        newFogBinding?.onHostStarted()
                    } else {
                        newFogBinding?.onHostStopped()
                    }
                    effectBinding = newBinding
                    binding = newBinding
                    // Set this after both bindings have been created so the camera effects never
                    // issue an unowned flight while the fog coordinator is still being wired.
                    // getMapAsync/renderer callbacks are not an application-owned Compose
                    // dispatcher. Publish the ready map on the MapView's main handler so the
                    // detail LaunchedEffect can observe it and issue its loaded/layout-gated fit.
                    mapView.post {
                        if (
                            compositionActive.get() &&
                                mapCallbackEpoch.get() == effectEpoch
                        ) {
                            readyMap = map
                            currentOnMapReadyForTesting?.invoke(map)
                        }
                    }
                } catch (_: Exception) {
                    currentOnTerminalFailure(ProviderFallbackReason.INITIALIZATION_FAILURE)
                } catch (_: LinkageError) {
                    currentOnTerminalFailure(ProviderFallbackReason.INITIALIZATION_FAILURE)
                }
            }
        } catch (_: Exception) {
            currentOnTerminalFailure(ProviderFallbackReason.INITIALIZATION_FAILURE)
        } catch (_: LinkageError) {
            currentOnTerminalFailure(ProviderFallbackReason.INITIALIZATION_FAILURE)
        }
        onDispose {
            mapCallbackEpoch.compareAndSet(effectEpoch, effectEpoch + 1L)
            effectBinding?.release()
            effectFogBinding?.release()
            loadedCallbackRetry?.let(mapView::removeCallbacks)
            loadedCallbackRetry = null
            if (earlyLoadedMap != null) {
                // The detail binding deliberately did not own this callback. Clear it only after
                // invalidating this effect so a late SDK callback cannot publish into Compose.
                runCatching { earlyLoadedMap?.setOnMapLoadedCallback(null) }
                earlyLoadedMap = null
            }
            if (binding === effectBinding) binding = null
            if (fogBinding === effectFogBinding) fogBinding = null
            mapView.setTag(R.id.map_detail_map_instance, null)
        }
    }

    LaunchedEffect(mapView, readyMap, cameraRequest) {
        val map = readyMap ?: return@LaunchedEffect
        val request = cameraRequest ?: return@LaunchedEffect
        // Every flight claims the composition-owned ticket. A fog owner additionally receives its
        // provider-policy ticket, but the host claim is what survives binding replacement and
        // keeps follow effects out of this flight.
        val owner = fogBinding
        val flight = claimCameraFlight(
            host = cameraFlightController,
            owner = owner,
            beginOwner = { it.beginProgrammedFlight() },
            endOwner = { binding, ticket -> binding.endProgrammedFlight(ticket) },
            onHostActiveChanged = { active -> mapView.setTag(R.id.map_camera_flight_active, active) },
        )
        try {
            val target = LatLng(
                request.point.latitude,
                app.trailveil.map.fog.WebMercator.wrapLongitude(request.point.longitude),
            )
            val update = if (request.zoom == null) {
                CameraUpdateFactory.newLatLng(target)
            } else {
                CameraUpdateFactory.newLatLngZoom(target, request.zoom.toFloat())
            }
            val callback = object : CancelableCallback {
                override fun onFinish() = flight.release()
                override fun onCancel() = flight.release()
            }
            val duration = cameraRequestDurationMillisForTesting
            if (duration == null) {
                map.animateCamera(update, callback)
            } else {
                require(duration > 0) { "cameraRequestDurationMillisForTesting must be positive" }
                map.animateCamera(update, duration, callback)
            }
        } catch (_: Exception) {
            flight.release()
        } catch (_: LinkageError) {
            flight.release()
        }
    }

    // Keeping the camera on a walking user mirrors the neutral map contract. HOLD avoids jitter,
    // EASE is a short programmed move that remains exempt from the move-start cover, and JUMP is
    // an ordinary ticketed programmed move whose outside-surround path is fail-closed by fog.
    LaunchedEffect(mapView, readyMap, followLocation, cameraRequest) {
        val map = readyMap ?: return@LaunchedEffect
        val target = followLocation ?: return@LaunchedEffect
        // A programmed move to this exact point already carries the requested zoom. Starting a
        // zoom-less follow move here would consume that zoom and require a second press to repair.
        if (cameraRequest?.point == target) return@LaunchedEffect
        val owner = fogBinding
        // This check deliberately uses only the host claim. The provider ticket is policy state;
        // the host claim remains authoritative while a null-runtime binding is replaced by the
        // ready-runtime binding.
        if (cameraFlightController.isActive) {
            return@LaunchedEffect
        }
        val screen = try {
            map.projection.toScreenLocation(
                LatLng(
                    target.latitude,
                    app.trailveil.map.fog.WebMercator.wrapLongitude(target.longitude),
                ),
            )
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        val movement = followCameraMove(
            offsetX = (screen?.x?.toDouble() ?: Double.NaN) - mapView.width / 2.0,
            offsetY = (screen?.y?.toDouble() ?: Double.NaN) - mapView.height / 2.0,
            viewportWidth = mapView.width,
            viewportHeight = mapView.height,
        )
        val destination = try {
            LatLng(
                target.latitude,
                app.trailveil.map.fog.WebMercator.wrapLongitude(target.longitude),
            )
        } catch (_: Exception) {
            return@LaunchedEffect
        } catch (_: LinkageError) {
            return@LaunchedEffect
        }
        when (movement) {
            FollowCameraMove.HOLD -> Unit
            FollowCameraMove.EASE -> {
                val flight = claimCameraFlight(
                    host = cameraFlightController,
                    owner = owner,
                    beginOwner = { it.beginFollowEase() },
                    endOwner = { binding, ticket -> binding.endFollowEase(ticket) },
                    onHostActiveChanged = { active -> mapView.setTag(R.id.map_camera_flight_active, active) },
                )
                try {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLng(destination),
                        FOLLOW_EASE_MILLIS,
                        object : CancelableCallback {
                            override fun onFinish() = flight.release()
                            override fun onCancel() = flight.release()
                        },
                    )
                } catch (_: Exception) {
                    flight.release()
                } catch (_: LinkageError) {
                    flight.release()
                }
            }
            FollowCameraMove.JUMP -> {
                val flight = claimCameraFlight(
                    host = cameraFlightController,
                    owner = owner,
                    beginOwner = { it.beginProgrammedFlight() },
                    endOwner = { binding, ticket -> binding.endProgrammedFlight(ticket) },
                    onHostActiveChanged = { active -> mapView.setTag(R.id.map_camera_flight_active, active) },
                )
                try {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLng(destination),
                        object : CancelableCallback {
                            override fun onFinish() = flight.release()
                            override fun onCancel() = flight.release()
                        },
                    )
                } catch (_: Exception) {
                    flight.release()
                } catch (_: LinkageError) {
                    flight.release()
                }
            }
        }
    }

    LaunchedEffect(mapView, fallbackTimeoutMillis, loadState, lifecycle) {
        if (loadState != BasemapLoadState.LOADING) return@LaunchedEffect
        // The fourth bounded budget that a stopped host cannot satisfy: a stopped MapView halts its
        // renderer, so OnMapLoadedCallback cannot fire and loadState cannot leave LOADING, yet this
        // delay resumes through AndroidUiDispatcher's Handler and is unaffected by the paused frame
        // clock. Ungated, backgrounding within fallbackTimeoutMillis of the surface appearing was
        // terminal on its own.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(fallbackTimeoutMillis)
            if (loadState == BasemapLoadState.LOADING && compositionActive.get()) {
                currentOnTerminalFailure(ProviderFallbackReason.MAP_LOAD_TIMEOUT)
            }
        }
    }

    // Hosted-surface deadline starts with the first visible cover, before the asynchronously
    // loaded FogRuntime exists. `fogRuntime` is intentionally NOT a key: null -> ready at 19 s
    // retains the original deadline instead of granting another full window.
    LaunchedEffect(mapView, fogRequired, fogCoverUp, fogCoverTimeoutMillis, lifecycle) {
        if (!fogRequired || !fogCoverUp) return@LaunchedEffect
        // Gated on STARTED for the same reason the binding's own deadline is: a stopped renderer
        // issues no tile requests and cannot serve a snapshot, so the cover has no way to lower and
        // this would terminate the surface purely for being backgrounded. repeatOnLifecycle
        // cancels the wait on stop and starts a fresh full window on return.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(fogCoverTimeoutMillis)
            if (fogCoverUp && compositionActive.get()) {
                currentOnTerminalFailure(ProviderFallbackReason.INITIALIZATION_FAILURE)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .semantics { this.contentDescription = contentDescription }
                .testTag(MapSurfaceTestTags.Map),
            update = { view ->
                // Compose hands an AndroidView to the accessibility tree as the hosted View
                // itself (the semantics modifier above serves Compose tests and previews, never
                // a screen reader), so the one TalkBack target is the MapView: important, with
                // the localized description. NO_HIDE_DESCENDANTS goes on the SDK's own children,
                // never on the MapView - that mode hides the view it is set on as well ("not
                // important, nor are any of its descendants") - so the SDK's map TextureView and
                // marker nodes stay out of traversal. GestureOwningGoogleMapView applies the same
                // to children the SDK adds later.
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                view.contentDescription = contentDescription
                for (index in 0 until view.childCount) {
                    view.getChildAt(index).importantForAccessibility =
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            },
        )
        if (fogRequired && fogCoverUp) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(MapSurfaceTestTags.FogSafetyCover),
            )
        }
        if (loadState == BasemapLoadState.LOADING) {
            MapStatusBadge(stringResource(R.string.map_loading))
        } else if (fogState?.retryScheduled == true && !fogCoverUp) {
            MapStatusBadge(stringResource(R.string.map_fog_unavailable))
        }
    }
}
