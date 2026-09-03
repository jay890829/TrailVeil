package app.trailveil.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.trailveil.R
import app.trailveil.map.fog.FogBackdropGeometry
import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogSurroundExtent
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileMosaic
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.FogViewportRender
import app.trailveil.map.fog.GeoPoint
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object FogOverlayIds {
    const val Source = "trailveil-cumulative-fog-source"
    const val Layer = "trailveil-cumulative-fog-layer"
    const val InstallGuardLayer = "trailveil-fog-install-guard-layer"

    /**
     * The basemap repeats across copies of the world; an image source does not. When the mosaic
     * spans a whole world its western edge is a canonical tile boundary, so it cannot be centred on
     * the camera by shifting it — the shift lands it back where it started. These carry the same
     * fog one world either side, so a camera looking past the mosaic's edge finds fog there too
     * rather than a bare repeat of the map.
     */
    const val WestRepeatSource = "trailveil-cumulative-fog-west-repeat-source"
    const val WestRepeatLayer = "trailveil-cumulative-fog-west-repeat-layer"
    const val EastRepeatSource = "trailveil-cumulative-fog-east-repeat-source"
    const val EastRepeatLayer = "trailveil-cumulative-fog-east-repeat-layer"

    fun source(slot: FogGenerationSlot): String = Source.forSlot(slot)
    fun layer(slot: FogGenerationSlot): String = Layer.forSlot(slot)
    fun westRepeatSource(slot: FogGenerationSlot): String = WestRepeatSource.forSlot(slot)
    fun westRepeatLayer(slot: FogGenerationSlot): String = WestRepeatLayer.forSlot(slot)
    fun eastRepeatSource(slot: FogGenerationSlot): String = EastRepeatSource.forSlot(slot)
    fun eastRepeatLayer(slot: FogGenerationSlot): String = EastRepeatLayer.forSlot(slot)

    fun generationLayers(slot: FogGenerationSlot): List<String> = listOf(
        layer(slot),
        westRepeatLayer(slot),
        eastRepeatLayer(slot),
    ) + FogBackdropIds.layers(slot) + FogSeamGuardIds.layers(slot)

    val AllGenerationLayers: List<String> = FogGenerationSlot.entries.flatMap(::generationLayers)
}

internal enum class FogGenerationSlot {
    A,
    B,
    ;

    fun other(): FogGenerationSlot = if (this == A) B else A

    companion object {
        /** Slot B holds the initial opaque placeholder, leaving legacy slot A for first canonical. */
        fun next(active: FogGenerationSlot?): FogGenerationSlot = active?.other() ?: B
    }
}

private fun String.forSlot(slot: FogGenerationSlot): String =
    if (slot == FogGenerationSlot.A) this else "$this-b"

/**
 * The mosaic has finite bounds, so the map outside them carries its own fog in map coordinates.
 * MapLibre transforms these bands with the camera in the frame that draws the mosaic, which is
 * what makes coverage immune to the dispatch lag of any camera callback.
 */
internal object FogBackdropIds {
    const val NorthSource = "trailveil-fog-backdrop-north-source"
    const val NorthLayer = "trailveil-fog-backdrop-north-layer"
    const val SouthSource = "trailveil-fog-backdrop-south-source"
    const val SouthLayer = "trailveil-fog-backdrop-south-layer"
    const val WestSource = "trailveil-fog-backdrop-west-source"
    const val WestLayer = "trailveil-fog-backdrop-west-layer"
    const val EastSource = "trailveil-fog-backdrop-east-source"
    const val EastLayer = "trailveil-fog-backdrop-east-layer"

    /** Flat fog over the world copies beside the surround, for a viewport wider than one world. */
    const val WestWorldSource = "trailveil-fog-backdrop-west-world-source"
    const val WestWorldLayer = "trailveil-fog-backdrop-west-world-layer"
    const val EastWorldSource = "trailveil-fog-backdrop-east-world-source"
    const val EastWorldLayer = "trailveil-fog-backdrop-east-world-layer"

    /**
     * What [WestLayer] and [EastLayer] become where the renderer repeats an image source by itself:
     * one quad from the mosaic's east edge round to its west, kept inside the canonical world.
     */
    const val WrappedSideSource = "trailveil-fog-backdrop-wrapped-side-source"
    const val WrappedSideLayer = "trailveil-fog-backdrop-wrapped-side-layer"

    val Layers: List<String> = listOf(
        NorthLayer,
        SouthLayer,
        WestLayer,
        EastLayer,
        WestWorldLayer,
        EastWorldLayer,
        WrappedSideLayer,
    )

    fun northSource(slot: FogGenerationSlot): String = NorthSource.forSlot(slot)
    fun northLayer(slot: FogGenerationSlot): String = NorthLayer.forSlot(slot)
    fun southSource(slot: FogGenerationSlot): String = SouthSource.forSlot(slot)
    fun southLayer(slot: FogGenerationSlot): String = SouthLayer.forSlot(slot)
    fun westSource(slot: FogGenerationSlot): String = WestSource.forSlot(slot)
    fun westLayer(slot: FogGenerationSlot): String = WestLayer.forSlot(slot)
    fun eastSource(slot: FogGenerationSlot): String = EastSource.forSlot(slot)
    fun eastLayer(slot: FogGenerationSlot): String = EastLayer.forSlot(slot)
    fun westWorldSource(slot: FogGenerationSlot): String = WestWorldSource.forSlot(slot)
    fun westWorldLayer(slot: FogGenerationSlot): String = WestWorldLayer.forSlot(slot)
    fun eastWorldSource(slot: FogGenerationSlot): String = EastWorldSource.forSlot(slot)
    fun eastWorldLayer(slot: FogGenerationSlot): String = EastWorldLayer.forSlot(slot)
    fun wrappedSideSource(slot: FogGenerationSlot): String = WrappedSideSource.forSlot(slot)
    fun wrappedSideLayer(slot: FogGenerationSlot): String = WrappedSideLayer.forSlot(slot)
    fun layers(slot: FogGenerationSlot): List<String> = Layers.map { it.forSlot(slot) }
}

internal object FogSeamGuardIds {
    const val Source = "trailveil-fog-seam-guard-source"
    const val Layer = "trailveil-fog-seam-guard-layer"
    const val ExtentSource = "trailveil-fog-extent-guard-source"
    const val ExtentFillLayer = "trailveil-fog-extent-guard-fill-layer"
    const val ExtentBoundaryLayer = "trailveil-fog-extent-guard-boundary-layer"

    fun source(slot: FogGenerationSlot): String = Source.forSlot(slot)
    fun layer(slot: FogGenerationSlot): String = Layer.forSlot(slot)
    fun extentSource(slot: FogGenerationSlot): String = ExtentSource.forSlot(slot)
    fun extentFillLayer(slot: FogGenerationSlot): String = ExtentFillLayer.forSlot(slot)
    fun extentBoundaryLayer(slot: FogGenerationSlot): String = ExtentBoundaryLayer.forSlot(slot)
    fun layers(slot: FogGenerationSlot): List<String> = listOf(
        layer(slot),
        extentFillLayer(slot),
        extentBoundaryLayer(slot),
    )

    val ExtentGuardLayers: List<String> = FogGenerationSlot.entries.flatMap { slot ->
        listOf(extentFillLayer(slot), extentBoundaryLayer(slot))
    }
}

internal object CurrentLocationOverlayIds {
    const val Source = "trailveil-current-location-source"
    const val Layer = "trailveil-current-location-layer"
}

internal object TrackOverlayIds {
    const val LineSource = "trailveil-session-track-line-source"
    const val LineLayer = "trailveil-session-track-line-layer"
    const val PointSource = "trailveil-session-track-point-source"
    const val PointLayer = "trailveil-session-track-point-layer"
}

/** Immutable test evidence for the canonical fog geometry that actually reached the renderer. */
internal data class InstalledFogCoverageSnapshot(
    val generation: Long,
    val extent: FogSurroundExtent,
    val slot: FogGenerationSlot,
)

private data class PreparedFogGeneration(
    val mosaic: FogTileMosaic,
    val previousSlot: FogGenerationSlot?,
    val installedSlot: FogGenerationSlot,
)

/** Compose-applied state exposed only to renderer-ordering instrumentation. */
internal data class ComposedFogCoverageSnapshot(
    val generation: Long,
    val coverageInstalled: Boolean,
    val installedExtent: FogSurroundExtent?,
    val activeSlot: FogGenerationSlot?,
    val canonicalLoaded: Boolean,
)

internal enum class CanonicalFogInstallCheckpointPhase {
    BEFORE_STYLE_INSTALL,
    AFTER_STYLE_INSTALL_BEFORE_RECONCILE,
}

/** A suspendable instrumentation seam; absent in every production call site. */
internal data class CanonicalFogInstallCheckpoint(
    val phase: CanonicalFogInstallCheckpointPhase,
    val generation: Long,
    val fogRevision: Long,
    val render: FogViewportRender,
    val installedExtent: FogSurroundExtent?,
    val installedSlot: FogGenerationSlot?,
)

internal data class CanonicalFogInstallDecision(
    val generation: Long,
    val render: FogViewportRender,
    val installedExtent: FogSurroundExtent,
    val installedSlot: FogGenerationSlot?,
    val rejectedBeforeStyleMutation: Boolean,
    val coverageInstalledAtDecision: Boolean,
)

/**
 * Provider failure is deliberately contained inside this surface. It never owns recording,
 * location permissions, canonical points, or fog state.
 *
 * `rendersIntoTheWindow` decides where MapLibre draws. By default it draws into its own compositor
 * layer, which is the faster path and the right one for a map that occupies the whole screen. A map
 * embedded in a screen the user navigates away from should set this: a separate layer neither fades
 * with an exit transition nor stops presenting when the composition around it does, so it keeps
 * painting over whatever screen comes next until the view is finally detached. Drawing into the
 * window costs a copy per frame and buys a map that disappears exactly when its screen does.
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
    // MapLibre draws the compass inside the map view, which is full-bleed, so it knows nothing
    // about the controls a host stacks on top of it. The host that puts them there says where the
    // compass may sit.
    compassTopInset: Dp = MAP_CONTROL_INSET,
    compassEndInset: Dp = MAP_CONTROL_INSET,
    trackOverlay: MapTrackOverlay? = null,
    onUserMovedCamera: () -> Unit = {},
    onFogRendered: ((FogViewportRender) -> Unit)? = null,
    onFogFailure: (Throwable) -> Unit = {},
    fogInstallFaultForTesting: (() -> Unit)? = null,
    onMapViewCreatedForTesting: ((MapView) -> Unit)? = null,
    onFogCoverageInstalledForTesting: ((InstalledFogCoverageSnapshot) -> Unit)? = null,
    onFogCoverageStateComposedForTesting: ((ComposedFogCoverageSnapshot) -> Unit)? = null,
    canonicalFogInstallCheckpointForTesting:
        (suspend (CanonicalFogInstallCheckpoint) -> Unit)? = null,
    onCanonicalFogInstallDecisionForTesting: ((CanonicalFogInstallDecision) -> Unit)? = null,
    canonicalViewportRequestForTesting: FogViewportRequest? = null,
    fogSurroundCoverageForTesting: ((FogSurroundExtent) -> Boolean)? = null,
    suppressFogCameraReactionsForTesting: Boolean = false,
) {
    require(fallbackTimeoutMillis > 0L) { "fallbackTimeoutMillis must be positive" }
    require(savedStateKey.isNotBlank()) { "savedStateKey must not be blank" }
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
    val resources = LocalResources.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val savedStateRegistry = LocalSavedStateRegistryOwner.current.savedStateRegistry
    val fallbackStyleJson = remember(resources) {
        resources.openRawResource(R.raw.maplibre_fallback_style)
            .bufferedReader()
            .use { it.readText() }
    }
    val restoredMapState = remember(savedStateRegistry, savedStateKey) {
        savedStateRegistry.consumeRestoredStateForKey(savedStateKey)
            ?.takeIf { envelope ->
                envelope.getString(MAP_SAVED_STATE_PROVIDER_KEY) == MAP_STATE_PROVIDER
            }
            ?.getBundle(MAP_SAVED_STATE_PAYLOAD_KEY)
    }
    val mapView = remember(
        context,
        lifecycle,
        savedStateRegistry,
        savedStateKey,
        rendersIntoTheWindow,
    ) {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(rendersIntoTheWindow)
        GestureOwningMapView(context, options).apply { onCreate(restoredMapState ?: Bundle()) }
    }
    var loadState by remember(mapView, provider) { mutableStateOf(BasemapLoadState.LOADING) }
    var readyMap by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var readyStyle by remember(mapView, provider) { mutableStateOf<Style?>(null) }
    var fallbackRequested by remember(mapView, provider) { mutableStateOf(false) }
    val compositionActive = remember(mapView) { AtomicBoolean(true) }
    val styleGenerationActive = remember(mapView, provider) { AtomicBoolean(true) }
    val currentOnFogRendered by rememberUpdatedState(onFogRendered)
    val currentOnFogFailure by rememberUpdatedState(onFogFailure)
    val currentOnUserMovedCamera by rememberUpdatedState(onUserMovedCamera)
    val currentOnMapViewCreatedForTesting by rememberUpdatedState(onMapViewCreatedForTesting)
    val currentOnFogCoverageInstalledForTesting by rememberUpdatedState(
        onFogCoverageInstalledForTesting,
    )
    val currentOnFogCoverageStateComposedForTesting by rememberUpdatedState(
        onFogCoverageStateComposedForTesting,
    )
    val currentCanonicalFogInstallCheckpointForTesting by rememberUpdatedState(
        canonicalFogInstallCheckpointForTesting,
    )
    val currentOnCanonicalFogInstallDecisionForTesting by rememberUpdatedState(
        onCanonicalFogInstallDecisionForTesting,
    )
    val currentCanonicalViewportRequestForTesting by rememberUpdatedState(
        canonicalViewportRequestForTesting,
    )
    val currentFogSurroundCoverageForTesting by rememberUpdatedState(
        fogSurroundCoverageForTesting,
    )
    // Set only around a follow step, whose reach is bounded by how far a person walked since the
    // last fix. Every other programmed move keeps hiding the overlay until its rebuild lands.
    val followingCameraMove = remember(mapView) { AtomicBoolean(false) }
    // A flight ticket, not a flag: MapLibre's `cancelTransitions` POSTS the superseded flight's
    // `onCancel`, so it runs while the replacing flight is already in the air. A boolean would be
    // cleared underneath the live flight and reopen the very race this closes; a ticket lets the
    // stale callback fail its compare-and-set and leave the current flight's claim standing.
    val programmedCameraFlight = remember(mapView) { AtomicLong(IDLE_CAMERA_FLIGHT) }
    var fogViewportRequest by remember(mapView, fogRuntime) {
        mutableStateOf<FogViewportRequest?>(null)
    }
    var fogRevision by remember(mapView, fogRuntime) { mutableLongStateOf(0L) }
    var fogViewportGeneration by remember(mapView, fogRuntime) { mutableLongStateOf(0L) }
    var fogPlaceholderReadyGeneration by remember(mapView, fogRuntime) {
        mutableLongStateOf(-1L)
    }
    // A new style starts with no fog sources or layers at all, so installed coverage cannot
    // survive one.
    var fogCoverageInstalled by remember(mapView, fogRuntime, fogRequired, readyStyle) {
        mutableStateOf(!fogRequired)
    }
    // What the last completed install actually covers, so a live camera can be checked against it
    // rather than against an argument about how far a gesture can travel.
    var installedSurround by remember(mapView, fogRuntime, readyStyle) {
        mutableStateOf<FogSurroundExtent?>(null)
    }
    var activeFogSlot by remember(mapView, fogRuntime, readyStyle) {
        mutableStateOf<FogGenerationSlot?>(null)
    }
    var canonicalFogLoaded by remember(mapView, fogRuntime, fogRequired) {
        mutableStateOf(!fogRequired)
    }
    var fogRenderFailed by remember(mapView, fogRuntime) { mutableStateOf(false) }
    var fogSyncFailed by remember(mapView, fogRuntime) { mutableStateOf(false) }
    var fogBaselineReady by remember(mapView, fogRuntime) {
        mutableStateOf(fogRuntime == null)
    }
    SideEffect { currentOnMapViewCreatedForTesting?.invoke(mapView) }
    // Read in composition, not inside the effect: a state read performed only while applying a
    // side effect is not observed, so publication would silently stop tracking the state it
    // reports. The status badge short-circuits on the basemap state, so `canonicalFogLoaded` has
    // no other composition read while the local fallback is active.
    val publishedFogGeneration = if (canonicalFogLoaded) fogViewportGeneration else null
    val publishedLoadState = loadState.name
    val publishedActiveFogSlot = activeFogSlot?.name
    val composedFogCoverageSnapshot = ComposedFogCoverageSnapshot(
        generation = fogViewportGeneration,
        coverageInstalled = fogCoverageInstalled,
        installedExtent = installedSurround,
        activeSlot = activeFogSlot,
        canonicalLoaded = canonicalFogLoaded,
    )
    SideEffect {
        mapView.setTag(R.id.map_fog_canonical_generation, publishedFogGeneration)
        mapView.setTag(R.id.map_basemap_load_state, publishedLoadState)
        mapView.setTag(R.id.map_fog_active_slot, publishedActiveFogSlot)
        currentOnFogCoverageStateComposedForTesting?.invoke(composedFogCoverageSnapshot)
    }

    fun useLocalFallback() {
        val map = readyMap ?: return
        if (!compositionActive.get() || !styleGenerationActive.get()) return
        if (fallbackRequested) return
        fallbackRequested = true
        map.setStyle(
            Style.Builder().fromJson(fallbackStyleJson).withInitialFogGuard(fogRequired),
        ) { style ->
            if (compositionActive.get() && styleGenerationActive.get()) {
                readyStyle = style
                loadState = BasemapLoadState.LOCAL_FALLBACK
            }
        }
    }

    DisposableEffect(mapView, lifecycle, savedStateRegistry, savedStateKey) {
        compositionActive.set(true)

        savedStateRegistry.registerSavedStateProvider(savedStateKey) {
            Bundle().apply {
                putString(MAP_SAVED_STATE_PROVIDER_KEY, MAP_STATE_PROVIDER)
                putBundle(
                    MAP_SAVED_STATE_PAYLOAD_KEY,
                    Bundle().also(mapView::onSaveInstanceState),
                )
            }
        }
        val componentCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                if (compositionActive.get()) mapView.onLowMemory()
            }

            override fun onTrimMemory(level: Int) = Unit
        }
        context.applicationContext.registerComponentCallbacks(componentCallbacks)

        val lifecycleBinding = MapViewLifecycleBinding(mapView)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            lifecycleBinding.onEvent(event)
        }
        lifecycle.addObserver(lifecycleObserver)
        lifecycleBinding.synchronize(lifecycle.currentState)

        onDispose {
            compositionActive.set(false)
            lifecycle.removeObserver(lifecycleObserver)
            savedStateRegistry.unregisterSavedStateProvider(savedStateKey)
            context.applicationContext.unregisterComponentCallbacks(componentCallbacks)
            lifecycleBinding.release()
        }
    }

    DisposableEffect(mapView, provider, fallbackStyleJson) {
        styleGenerationActive.set(true)

        val failureListener = MapView.OnDidFailLoadingMapListener {
            if (
                compositionActive.get() &&
                styleGenerationActive.get() &&
                loadState == BasemapLoadState.LOADING
            ) {
                useLocalFallback()
            }
        }
        mapView.addOnDidFailLoadingMapListener(failureListener)
        mapView.getMapAsync { map ->
            if (!compositionActive.get() || !styleGenerationActive.get()) return@getMapAsync
            readyMap = map
            map.setStyle(
                Style.Builder().fromUri(provider.styleUri).withInitialFogGuard(fogRequired),
            ) { style ->
                if (
                    compositionActive.get() &&
                    styleGenerationActive.get() &&
                    !fallbackRequested
                ) {
                    readyStyle = style
                    loadState = BasemapLoadState.ONLINE
                }
            }
        }

        onDispose {
            styleGenerationActive.set(false)
            mapView.removeOnDidFailLoadingMapListener(failureListener)
        }
    }

    LaunchedEffect(mapView, provider, readyMap, loadState, fallbackTimeoutMillis) {
        if (readyMap != null && loadState == BasemapLoadState.LOADING) {
            delay(fallbackTimeoutMillis)
            if (
                compositionActive.get() &&
                styleGenerationActive.get() &&
                loadState == BasemapLoadState.LOADING
            ) {
                useLocalFallback()
            }
        }
    }

    LaunchedEffect(readyMap, cameraRequest) {
        val map = readyMap ?: return@LaunchedEffect
        val request = cameraRequest ?: return@LaunchedEffect
        val target = LatLng(request.point.latitude, request.point.longitude)
        // The latch stays up for the flight so a follow step cannot cancel the zoom this request
        // carries: a fix landing mid-recentre used to relaunch the follow effect past its
        // equality guard, and its zoom-less ease or jump ate the requested zoom with nothing to
        // repair it. Finish and cancel both clear it; a gesture cancels the flight and turns
        // following off anyway.
        val flight = programmedCameraFlight.incrementAndGet()
        map.animateCamera(
            if (request.zoom == null) {
                CameraUpdateFactory.newLatLng(target)
            } else {
                CameraUpdateFactory.newLatLngZoom(target, request.zoom)
            },
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() {
                    programmedCameraFlight.compareAndSet(flight, IDLE_CAMERA_FLIGHT)
                }

                override fun onCancel() {
                    programmedCameraFlight.compareAndSet(flight, IDLE_CAMERA_FLIGHT)
                }
            },
        )
    }

    // Keeping the camera on a walking user, once they have asked for it. The map is not hidden
    // for this: a follow step crosses at most one viewport and does not change the zoom, which is
    // the axis a clamped surround is sensitive to. If a step ever does carry the camera past what is
    // installed, the dispatched move callback requests the Compose cover; that reaction is not part
    // of the renderer frame and is tracked separately from the renderer-anchored surround.
    LaunchedEffect(readyMap, followLocation, cameraRequest) {
        val map = readyMap ?: return@LaunchedEffect
        val target = followLocation ?: return@LaunchedEffect
        // A programmed move to this very point is already under way, and it carries a zoom. A
        // follow step does not — it is a latitude and longitude only — so making one here would
        // replace the zoom with nothing and land the camera centred but no closer. That is what
        // made the recentre button take two presses to get back in.
        if (cameraRequest?.point == target) return@LaunchedEffect
        // A different fix arriving while a programmed request is still flying must not interrupt
        // it either - the follow effect stands down for the whole flight, and the next fix after
        // landing follows normally.
        if (programmedCameraFlight.get() != IDLE_CAMERA_FLIGHT) return@LaunchedEffect
        val destination = LatLng(target.latitude, target.longitude)
        val screen = map.projection.toScreenLocation(destination)
        when (
            followCameraMove(
                offsetX = screen.x - mapView.width / 2.0,
                offsetY = screen.y - mapView.height / 2.0,
                viewportWidth = mapView.width,
                viewportHeight = mapView.height,
            )
        ) {
            FollowCameraMove.HOLD -> Unit
            FollowCameraMove.EASE -> {
                followingCameraMove.set(true)
                map.easeCamera(CameraUpdateFactory.newLatLng(destination), FOLLOW_EASE_MILLIS)
            }
            // Off screen is not a step, it is a move — so it is made like any other one. Since
            // the A/B generations landed, the cover rises only if that move leaves the committed
            // surround; within it the renderer-native guard already covers the ground.
            FollowCameraMove.JUMP ->
                map.animateCamera(CameraUpdateFactory.newLatLng(destination))
        }
    }

    // Asked both by the camera-move listener and by the two install paths. Coverage becoming
    // installed is not the same as coverage being enough: a re-render can land while a gesture has
    // already carried the camera past what the installed surround holds, and lowering the cover on
    // the strength of a successful install alone would uncover a map that is leaking.
    fun publishViewportRequest(request: FogViewportRequest) {
        followingCameraMove.set(false)
        canonicalFogLoaded = false
        fogRenderFailed = false
        fogPlaceholderReadyGeneration = -1L
        fogViewportRequest = request
        fogViewportGeneration += 1L
    }

    fun requestViewport() {
        val map = readyMap ?: return
        publishViewportRequest(
            currentCanonicalViewportRequestForTesting ?: map.fogViewportRequest(),
        )
    }

    LaunchedEffect(readyMap, canonicalViewportRequestForTesting) {
        if (readyMap != null) {
            canonicalViewportRequestForTesting?.let(::publishViewportRequest)
        }
    }

    // Pure geometry, deliberately: the coverage test seam is consulted only at the canonical
    // install decision below. Routing it through here once let a test's forced decision answer
    // poison the movement raise and the pre-mutation staleness check, which ask a different
    // question of the same extent.
    fun surroundHoldsForCamera(extent: FogSurroundExtent? = installedSurround): Boolean {
        val checkedExtent = extent ?: return true
        val map = readyMap ?: return true
        // Nothing is laid out yet, so there is no viewport to be outside of; the next camera move
        // asks again.
        if (mapView.width <= 0 || mapView.height <= 0) return true
        // The renderer's own answer to "what ground is on screen", rather than arithmetic on the
        // viewport's width and height. Tilt and rotate are both enabled, and a tilted camera sees
        // ground running away to the horizon that no axis-aligned box built from the screen's size
        // contains — measured at 14.75% of the screen shown as explored when this did that sum
        // itself.
        val corners = map.projection.visibleRegion.let { region ->
            listOfNotNull(region.farLeft, region.farRight, region.nearRight, region.nearLeft)
        }
        // Four finite corners or nothing. A region the projection could only partly answer for is
        // not one this can conclude anything from — and `GeoPoint` refuses a non-finite value by
        // throwing, which inside a camera-move listener would be a crash rather than a cover.
        if (corners.size != VISIBLE_REGION_CORNERS) return false
        if (corners.any { !it.latitude.isFinite() || !it.longitude.isFinite() }) return false
        return checkedExtent.covers(
            corners.map { corner -> GeoPoint(corner.latitude, corner.longitude) },
        )
    }

    /**
     * A published A/B generation is globally fail-closed: canonical fog covers its reveal window
     * and the slot-scoped extent guard covers everything outside it. Failures and camera changes
     * may make that reveal window stale, but cannot make the renderer bare. A style with no
     * complete published slot needs the separately composed opaque cover; so does a programmed
     * move that leaves the reveal window (the camera listeners' second cover writer), because a
     * jump can outrun on-demand guard tile extraction.
     */
    fun retainCommittedGenerationOrRaiseCover() {
        canonicalFogLoaded = false
        if (activeFogSlot == null || installedSurround == null) {
            fogCoverageInstalled = false
            installedSurround = null
        }
    }

    LaunchedEffect(readyMap, compassTopInset, compassEndInset, density) {
        val map = readyMap ?: return@LaunchedEffect
        val top = with(density) { compassTopInset.roundToPx() }
        val side = with(density) { compassEndInset.roundToPx() }
        map.uiSettings.compassGravity = Gravity.TOP or Gravity.END
        // Both horizontal margins, so the same call places it correctly when the layout is
        // mirrored and `END` resolves to the left.
        map.uiSettings.setCompassMargins(side, top, side, 0)
    }

    // Reporting the user's own hand is not fog's business, and keeping it here rather than in the
    // fog listeners means a map without fog — or one whose runtime has not loaded yet — still stops
    // following when its owner takes hold of it.
    DisposableEffect(readyMap) {
        val map = readyMap
        if (map == null) {
            onDispose { }
        } else {
            val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                // Only a gesture. A follow step is a programmed move, so it can never switch
                // itself off after one step.
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    currentOnUserMovedCamera()
                }
            }
            map.addOnCameraMoveStartedListener(listener)
            onDispose { map.removeOnCameraMoveStartedListener(listener) }
        }
    }

    LaunchedEffect(readyStyle, currentLocation) {
        readyStyle?.installCurrentLocation(currentLocation)
    }

    LaunchedEffect(readyStyle, trackOverlay) {
        readyStyle?.installTrackOverlay(trackOverlay)
    }

    LaunchedEffect(readyMap, trackOverlay?.requestId) {
        val map = readyMap ?: return@LaunchedEffect
        val points = trackOverlay?.segments?.flatten().orEmpty()
        when (points.size) {
            0 -> Unit
            1 -> map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(points.single().latitude, points.single().longitude),
                    16.0,
                ),
            )
            else -> {
                val bounds = LatLngBounds.Builder()
                    .includes(points.map { LatLng(it.latitude, it.longitude) })
                    .build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, TRACK_CAMERA_PADDING_PX))
            }
        }
    }

    LaunchedEffect(fogRuntime) {
        val runtime = fogRuntime ?: return@LaunchedEffect
        fogBaselineReady = false
        while (true) {
            try {
                val baseline = withContext(Dispatchers.Default) {
                    runtime.changeSynchronizer.synchronizeTo()
                }
                fogBaselineReady = true
                fogRenderFailed = false
                fogSyncFailed = false
                fogRevision += 1L
                runtime.pointChanges.revisionsAfter(baseline.cursor).collect { revision ->
                    val synchronization = withContext(Dispatchers.Default) {
                        runtime.changeSynchronizer.synchronizeTo(revision.latestCursor)
                    }
                    fogSyncFailed = false
                    if (synchronization.mergedChanges > 0) {
                        fogRevision += 1L
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fogBaselineReady = false
                retainCommittedGenerationOrRaiseCover()
                fogRenderFailed = true
                fogSyncFailed = true
                fogPlaceholderReadyGeneration = -1L
                fogViewportGeneration += 1L
                currentOnFogFailure(failure)
                delay(1_000L)
            }
        }
    }

    DisposableEffect(
        readyMap,
        readyStyle,
        fogRuntime,
        fogRequired,
        suppressFogCameraReactionsForTesting,
    ) {
        val map = readyMap
        val style = readyStyle
        val runtime = fogRuntime
        if (!fogRequired || map == null || style == null || runtime == null) {
            onDispose { }
        } else {
            // Gesture motion deliberately leaves the installed overlay alone. The mosaic is
            // anchored to the map, so it stays truthful wherever a gesture takes the camera, and
            // the backdrop bands keep everything around it fogged in the same rendered frame.
            val idleListener = MapLibreMap.OnCameraIdleListener {
                if (!suppressFogCameraReactionsForTesting) requestViewport()
            }
            // A programmed move differs from a gesture in one way that matters here: it can cross
            // any distance in one step, and the exterior guard's GeoJSON tiles for far ground are
            // extracted on demand, so a jump can outrun them. The renderer-native guard therefore
            // owns continuous movement, while a programmed move that leaves the canonical reveal
            // window raises the reactive cover until the rebuilt canonical lands — the deliberate
            // second writer of `fogCoverageInstalled = false` beside
            // `retainCommittedGenerationOrRaiseCover`. In-window programmed moves keep the
            // committed generation and no cover flash.
            var lastMoveWasGesture = false
            fun raiseCoverForProgrammedMoveBeyondSurround() {
                if (installedSurround != null && !surroundHoldsForCamera()) {
                    fogCoverageInstalled = false
                }
            }
            val moveStartedListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                val gesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                lastMoveWasGesture = gesture
                // A complete generation remains renderer-safe across every in-window programmed
                // move: its exterior guard fogs everything beyond the canonical reveal window.
                // Invalidate the canonical claim and rebuild at idle; raise the separately
                // composed cover only when no complete slot exists yet or the camera has already
                // left the reveal window (an instant jump dispatches this listener with the
                // camera already at its target).
                if (
                    !suppressFogCameraReactionsForTesting &&
                    !gesture &&
                    !followingCameraMove.get()
                ) {
                    retainCommittedGenerationOrRaiseCover()
                    fogRenderFailed = false
                    fogPlaceholderReadyGeneration = -1L
                    fogViewportRequest = null
                    fogViewportGeneration += 1L
                    raiseCoverForProgrammedMoveBeyondSurround()
                }
            }
            // Animated programmed moves leave the reveal window mid-flight, after the started
            // dispatch; this per-frame check catches that exit. Reactive by design: the raise is
            // Handler-ordered after the frame that crossed, which is the recorded teleport
            // exception, not the continuous-gesture guarantee.
            val moveListener = MapLibreMap.OnCameraMoveListener {
                if (
                    !suppressFogCameraReactionsForTesting &&
                    !lastMoveWasGesture &&
                    !followingCameraMove.get()
                ) {
                    raiseCoverForProgrammedMoveBeyondSurround()
                }
            }
            val moveCanceledListener = MapLibreMap.OnCameraMoveCanceledListener {
                if (!suppressFogCameraReactionsForTesting) requestViewport()
            }
            map.addOnCameraIdleListener(idleListener)
            map.addOnCameraMoveStartedListener(moveStartedListener)
            map.addOnCameraMoveListener(moveListener)
            map.addOnCameraMoveCancelListener(moveCanceledListener)
            requestViewport()
            onDispose {
                map.removeOnCameraIdleListener(idleListener)
                map.removeOnCameraMoveStartedListener(moveStartedListener)
                map.removeOnCameraMoveListener(moveListener)
                map.removeOnCameraMoveCancelListener(moveCanceledListener)
            }
        }
    }

    LaunchedEffect(
        fogRuntime,
        readyStyle,
        fogViewportRequest,
        fogViewportGeneration,
    ) {
        val runtime = fogRuntime ?: return@LaunchedEffect
        val map = readyMap ?: return@LaunchedEffect
        val style = readyStyle ?: return@LaunchedEffect
        val request = fogViewportRequest ?: return@LaunchedEffect
        val generation = fogViewportGeneration
        // An already installed overlay keeps covering the new viewport truthfully: its mosaic is
        // map-anchored and its bands fog everything else. Only a surface with no installed
        // overlay needs the opaque placeholder before canonical fog arrives.
        if (!fogCoverageInstalled) {
            retryFogOperation(
                retryDelayMillis = FOG_RETRY_DELAY_MILLIS,
                onFailure = { failure ->
                    if (
                        generation == fogViewportGeneration &&
                        request == fogViewportRequest &&
                        style === readyStyle
                    ) {
                        retainCommittedGenerationOrRaiseCover()
                        fogRenderFailed = true
                        currentOnFogFailure(failure)
                    }
                },
            ) {
                val placeholder = runtime.viewportCoordinator.placeholder(request)
                val previousSlot = activeFogSlot
                val installedSlot = mapView.installFogGenerationAndAwait(
                    map = map,
                    style = style,
                    mosaic = placeholder.mosaic,
                    fogAlpha = runtime.viewportCoordinator.style.fogAlpha,
                    activeSlot = previousSlot,
                )
                PreparedFogGeneration(
                    mosaic = placeholder.mosaic,
                    previousSlot = previousSlot,
                    installedSlot = installedSlot,
                )
            }.let { installed ->
                if (
                    generation != fogViewportGeneration ||
                    request != fogViewportRequest ||
                    style !== readyStyle
                ) {
                    return@LaunchedEffect
                }
                activeFogSlot = installed.installedSlot
                installedSurround = FogBackdropGeometry.extent(installed.mosaic)
                if (installed.previousSlot == null) {
                    mapView.hideInitialFogGuardAndAwait(map, style)
                } else {
                    mapView.retireFogGenerationAndAwait(
                        map = map,
                        style = style,
                        retiredSlot = installed.previousSlot,
                    )
                }
            }
            // Installing coverage is not the same as coverage being enough: the camera may have
            // moved on while this was being built. Asking for another rebuild — rather than only
            // declining to lower the cover — is what stops the map staying black with nothing
            // scheduled to lift it.
            if (surroundHoldsForCamera()) {
                fogCoverageInstalled = true
            } else {
                // The complete slot's renderer-native exterior guard is already global coverage.
                // Keep it visible while a request centred on the newer camera replaces the stale
                // canonical reveal window.
                fogCoverageInstalled = true
                requestViewport()
                return@LaunchedEffect
            }
            canonicalFogLoaded = false
            fogRenderFailed = false
        }
        fogPlaceholderReadyGeneration = generation
    }

    LaunchedEffect(
        fogRuntime,
        readyStyle,
        fogViewportRequest,
        fogViewportGeneration,
        fogPlaceholderReadyGeneration,
        fogRevision,
        fogBaselineReady,
        fogInstallFaultForTesting,
    ) {
        val runtime = fogRuntime ?: return@LaunchedEffect
        val style = readyStyle ?: return@LaunchedEffect
        val request = fogViewportRequest ?: return@LaunchedEffect
        val generation = fogViewportGeneration
        if (!fogBaselineReady) return@LaunchedEffect
        if (fogPlaceholderReadyGeneration != generation) return@LaunchedEffect
        var styleMayHaveChanged = false
        var installedStateReconciled = false
        var preparedSlot: FogGenerationSlot? = null
        var previousSlot: FogGenerationSlot? = null
        try {
            val rendered = renderCanonicalFogWithRetry(
                request = request,
                retryDelayMillis = FOG_RETRY_DELAY_MILLIS,
                render = { viewport ->
                    withContext(Dispatchers.Default) {
                        runtime.viewportCoordinator.render(viewport)
                    }
                },
                installAndAwait = { viewport ->
                    val incomingExtent = FogBackdropGeometry.extent(viewport.mosaic)
                    val cameraAlreadyOutsideIncoming = !surroundHoldsForCamera(incomingExtent)
                    if (cameraAlreadyOutsideIncoming && !followingCameraMove.get()) {
                        // Keep the older globally guarded renderer generation in place. Rejecting a
                        // stale landing before style mutation avoids needless work; the post-install
                        // check below still handles movement that happens while a valid S2 renders.
                        retainCommittedGenerationOrRaiseCover()
                        currentOnCanonicalFogInstallDecisionForTesting?.invoke(
                            CanonicalFogInstallDecision(
                                generation = generation,
                                render = viewport,
                                installedExtent = incomingExtent,
                                installedSlot = null,
                                rejectedBeforeStyleMutation = true,
                                coverageInstalledAtDecision = fogCoverageInstalled,
                            ),
                        )
                        requestViewport()
                        throw StaleCanonicalFogInstallException
                    }
                    currentCanonicalFogInstallCheckpointForTesting?.invoke(
                        CanonicalFogInstallCheckpoint(
                            phase = CanonicalFogInstallCheckpointPhase.BEFORE_STYLE_INSTALL,
                            generation = generation,
                            fogRevision = fogRevision,
                            render = viewport,
                            installedExtent = null,
                            installedSlot = null,
                        ),
                    )
                    styleMayHaveChanged = true
                    previousSlot = activeFogSlot
                    preparedSlot = mapView.installFogGenerationAndAwait(
                        map = checkNotNull(readyMap) { "Map disappeared during canonical fog install" },
                        style = style,
                        mosaic = viewport.mosaic,
                        fogAlpha = runtime.viewportCoordinator.style.fogAlpha,
                        activeSlot = previousSlot,
                        installFaultForTesting = fogInstallFaultForTesting,
                    )
                },
                onFailure = { failure ->
                    if (failure !== StaleCanonicalFogInstallException &&
                        generation == fogViewportGeneration &&
                        request == fogViewportRequest &&
                        style === readyStyle
                    ) {
                        // A partial target is never published and only adds fog above the complete
                        // active slot. Preserve that committed generation; the next attempt removes
                        // the abandoned target before reusing its IDs.
                        retainCommittedGenerationOrRaiseCover()
                        fogRenderFailed = true
                        currentOnFogFailure(failure)
                    }
                },
            )
            currentCanonicalFogInstallCheckpointForTesting?.invoke(
                CanonicalFogInstallCheckpoint(
                    phase = CanonicalFogInstallCheckpointPhase.AFTER_STYLE_INSTALL_BEFORE_RECONCILE,
                    generation = generation,
                    fogRevision = fogRevision,
                    render = rendered,
                    installedExtent = FogBackdropGeometry.extent(rendered.mosaic),
                    installedSlot = preparedSlot,
                ),
            )
            if (
                generation != fogViewportGeneration ||
                request != fogViewportRequest ||
                style !== readyStyle
            ) {
                return@LaunchedEffect
            }
            val installedExtent = FogBackdropGeometry.extent(rendered.mosaic)
            val installedSlot = checkNotNull(preparedSlot) {
                "Canonical fog rendered without a prepared renderer generation"
            }
            activeFogSlot = installedSlot
            installedSurround = installedExtent
            // The coverage seam is consulted here and only here: it forces this decision, not the
            // movement raise or the pre-mutation staleness check, so a forced non-covering answer
            // cannot leak into paths that must keep answering from real geometry (a transient
            // install retry once re-ran the pre-check against the forced answer and failed red).
            val decisionCoverageHolds =
                currentFogSurroundCoverageForTesting?.invoke(installedExtent)
                    ?: surroundHoldsForCamera()
            if (!decisionCoverageHolds) {
                // The canonical reveal window is stale, but this complete slot's exterior guard
                // still covers the live camera. Retain renderer coverage and request a better
                // window instead of flashing the separately composed opaque cover.
                fogCoverageInstalled = true
                canonicalFogLoaded = false
                currentOnCanonicalFogInstallDecisionForTesting?.invoke(
                    CanonicalFogInstallDecision(
                        generation = generation,
                        render = rendered,
                        installedExtent = installedExtent,
                        installedSlot = installedSlot,
                        rejectedBeforeStyleMutation = false,
                        coverageInstalledAtDecision = fogCoverageInstalled,
                    ),
                )
                installedStateReconciled = true
                previousSlot?.let { retired ->
                    mapView.retireFogGenerationAndAwait(
                        map = checkNotNull(readyMap) { "Map disappeared while retiring fog" },
                        style = style,
                        retiredSlot = retired,
                    )
                }
                requestViewport()
                return@LaunchedEffect
            }
            fogCoverageInstalled = true
            canonicalFogLoaded = true
            fogRenderFailed = false
            installedStateReconciled = true
            previousSlot?.let { retired ->
                mapView.retireFogGenerationAndAwait(
                    map = checkNotNull(readyMap) { "Map disappeared while retiring fog" },
                    style = style,
                    retiredSlot = retired,
                )
            }
            currentOnFogCoverageInstalledForTesting?.invoke(
                InstalledFogCoverageSnapshot(
                    generation = generation,
                    extent = installedExtent,
                    slot = installedSlot,
                ),
            )
            currentOnFogRendered?.invoke(rendered)
        } finally {
            if (styleMayHaveChanged && !installedStateReconciled) {
                // The target slot is additive: cancellation can leave an uncommitted *new* slot,
                // but it never mutates or removes the published one. Preserve that committed
                // generation while it still belongs to this style and retains its positive claim;
                // its finite-extent guard makes that generation globally fail-closed even beyond
                // the canonical reveal window. Clearing it here caused an avoidable black flash on
                // every follow-location re-key.
                // A later attempt removes the abandoned target before reusing its IDs - it is
                // the ONLY mutation owner for that slot. A synchronous removal here was tried and
                // reverted: this finally can fire arbitrarily late on a cancelled coroutine, and a
                // late removal races the superseding effect's half-installed generation in the
                // same slot, deleting freshly installed layers. Initial install or style
                // replacement still has no committed slot and raises the cover.
                retainCommittedGenerationOrRaiseCover()
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .semantics { this.contentDescription = contentDescription }
                .testTag(MapSurfaceTestTags.Map),
            update = { view ->
                // Compose hands an AndroidView to the accessibility tree as the hosted View itself
                // (the semantics modifier above serves Compose tests and previews, never a screen
                // reader), so the map's one TalkBack target is the MapView and it must carry the
                // localized description rather than the SDK's own "map created with" string.
                // Measured on device in V02-005 stage 9; the attribution control beneath it stays.
                view.contentDescription = contentDescription
            },
        )
        if (fogRequired && !fogCoverageInstalled) {
            // A plain Box, not a Surface: the cover exists to stop unexplored area being shown as
            // explored, which is about what is drawn, not about what the user is allowed to do.
            // Material3's Surface swallows pointer input, and that swallowing was the whole of the
            // dead period after returning from history — the map was ready within a frame, but
            // every drag landed on the cover until fog finished installing.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .testTag(MapSurfaceTestTags.FogSafetyCover),
            )
        }
        val statusText = when {
            loadState == BasemapLoadState.LOADING -> stringResource(R.string.map_loading)
            fogRenderFailed || fogSyncFailed -> stringResource(R.string.map_fog_unavailable)
            loadState == BasemapLoadState.LOCAL_FALLBACK ->
                stringResource(R.string.map_unavailable)
            fogRequired && !canonicalFogLoaded -> stringResource(R.string.map_fog_loading)
            else -> null
        }
        statusText?.let { text -> MapStatusBadge(text) }
    }
}

private fun MapLibreMap.fogViewportRequest(): FogViewportRequest {
    val target = cameraPosition.target ?: LatLng(0.0, 0.0)
    return FogViewportRequest(
        center = GeoPoint(
            latitude = target.latitude,
            longitude = target.longitude,
        ),
        mapZoom = cameraPosition.zoom,
    )
}

private fun Style.Builder.withInitialFogGuard(fogRequired: Boolean): Style.Builder {
    if (fogRequired) withLayer(newFogInstallGuard(Property.VISIBLE))
    return this
}

private fun newFogInstallGuard(visibility: String): BackgroundLayer =
    BackgroundLayer(FogOverlayIds.InstallGuardLayer).withProperties(
        PropertyFactory.backgroundColor("#000000"),
        PropertyFactory.backgroundOpacity(1.0f),
        PropertyFactory.visibility(visibility),
    )

/**
 * Adds one complete immutable fog generation while the previous generation remains attached.
 * Every mutable source/layer has a slot-specific ID. A successful renderer transition therefore
 * sees either the old complete generation, both complete generations, or the new one; a partial
 * target can only add fog over the globally fail-closed old generation.
 */
private fun Style.installFogGeneration(
    mosaic: FogTileMosaic,
    fogAlpha: Int,
    slot: FogGenerationSlot,
    keepInstallGuardVisible: Boolean,
    installFaultForTesting: (() -> Unit)? = null,
) {
    val installGuard = ensureFogInstallGuard(initiallyVisible = true)
    if (keepInstallGuardVisible) {
        installGuard.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    }
    val spansWorld = FogBackdropGeometry.spansWorld(mosaic)
    // The copies are always installed when there is a world to copy. A camera-zoom opacity step is
    // attached before each layer enters the style, so the renderer — not a Handler-dispatched
    // camera callback — decides which mutually exclusive arrangement is drawn for the frame.
    installFogMosaic(mosaic, spansWorld, slot)
    installFaultForTesting?.invoke()
    installFogBackdrop(
        mosaic,
        fogAlpha,
        slot,
        // Only when the surround reaches all the way round is there a neighbouring world copy the
        // camera can see, and only then is a world-wide quad small enough to be drawn rather than
        // smeared over the map.
        repeatWorlds = FogBackdropGeometry.surroundSpansWorld(mosaic) && !spansWorld,
    )
    installFogSeamAndExtentGuard(mosaic, fogAlpha, slot)
    if (!keepInstallGuardVisible) {
        installGuard.setProperties(PropertyFactory.visibility(Property.NONE))
    }
}

/**
 * Separate GeoJSON sources carry the local seam bridge and finite outside guard.
 * Keeping the small seam geometry out of the world-sized complement source preserves the
 * screen-pixel bridge's high-zoom tiling precision and its previously verified default tiling
 * behavior. The extent source alone uses synchronous initial tile extraction in pinned MapLibre
 * 13.4.1, so a future world-copy tile cannot briefly exist without that outside guard while this
 * immutable generation is active.
 */
private fun Style.installFogSeamAndExtentGuard(
    mosaic: FogTileMosaic,
    fogAlpha: Int,
    slot: FogGenerationSlot,
) {
    val bands = FogBackdropGeometry.bands(mosaic)
    val seamLines = if (FogBackdropGeometry.surroundSpansWorld(mosaic)) {
        emptyList()
    } else {
        listOf(
            listOf(
                Point.fromLngLat(bands.north.westLongitude, bands.north.southLatitude),
                Point.fromLngLat(bands.north.eastLongitude, bands.north.southLatitude),
            ),
            listOf(
                Point.fromLngLat(bands.south.westLongitude, bands.south.northLatitude),
                Point.fromLngLat(bands.south.eastLongitude, bands.south.northLatitude),
            ),
            listOf(
                Point.fromLngLat(bands.west.eastLongitude, bands.west.southLatitude),
                Point.fromLngLat(bands.west.eastLongitude, bands.west.northLatitude),
            ),
            listOf(
                Point.fromLngLat(bands.east.westLongitude, bands.east.southLatitude),
                Point.fromLngLat(bands.east.westLongitude, bands.east.northLatitude),
            ),
        )
    }
    val guardRectangles = FogBackdropGeometry.extentGuard(FogBackdropGeometry.extent(mosaic)).rectangles
    val seamSourceId = FogSeamGuardIds.source(slot)
    check(getSource(seamSourceId) == null) { "$seamSourceId was not retired before slot reuse" }
    addSource(
        GeoJsonSource(
            seamSourceId,
            FeatureCollection.fromFeatures(
                seamLines.map { points -> Feature.fromGeometry(LineString.fromLngLats(points)) },
            ),
        ),
    )
    val guardFeatures = buildList {
        guardRectangles.forEach { bounds ->
            val ring = bounds.toCanonicalRing()
            add(
                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                    addStringProperty(FOG_GEOJSON_ROLE_PROPERTY, FOG_GEOJSON_ROLE_EXTENT_FILL)
                },
            )
            add(
                Feature.fromGeometry(LineString.fromLngLats(ring)).apply {
                    addStringProperty(FOG_GEOJSON_ROLE_PROPERTY, FOG_GEOJSON_ROLE_EXTENT_BOUNDARY)
                },
            )
        }
    }
    val extentSourceId = FogSeamGuardIds.extentSource(slot)
    check(getSource(extentSourceId) == null) {
        "$extentSourceId was not retired before slot reuse"
    }
    addSource(
        GeoJsonSource(
            extentSourceId,
            FeatureCollection.fromFeatures(guardFeatures),
            GeoJsonOptions()
                .withSynchronousUpdate(true)
                .withTolerance(0f)
                .withBuffer(FOG_GEOJSON_BUFFER),
        ),
    )
    val seamLayer = LineLayer(FogSeamGuardIds.layer(slot), seamSourceId).withProperties(
        PropertyFactory.lineColor("#000000"),
        PropertyFactory.lineWidth(FOG_SEAM_GUARD_WIDTH_PIXELS),
        PropertyFactory.lineOpacity(fogAlpha / 255.0f),
        PropertyFactory.visibility(if (seamLines.isEmpty()) Property.NONE else Property.VISIBLE),
    )
    val fillLayer = FillLayer(
        FogSeamGuardIds.extentFillLayer(slot),
        extentSourceId,
    ).withProperties(
        PropertyFactory.fillColor("#000000"),
        PropertyFactory.fillOpacity(fogAlpha / 255.0f),
        PropertyFactory.fillAntialias(false),
    ).withFilter(
        Expression.eq(
            Expression.get(FOG_GEOJSON_ROLE_PROPERTY),
            Expression.literal(FOG_GEOJSON_ROLE_EXTENT_FILL),
        ),
    )
    val boundaryLayer = LineLayer(
        FogSeamGuardIds.extentBoundaryLayer(slot),
        extentSourceId,
    ).withProperties(
        PropertyFactory.lineColor("#000000"),
        PropertyFactory.lineWidth(FOG_EXTENT_GUARD_BOUNDARY_WIDTH_PIXELS),
        PropertyFactory.lineOpacity(fogAlpha / 255.0f),
    ).withFilter(
        Expression.eq(
            Expression.get(FOG_GEOJSON_ROLE_PROPERTY),
            Expression.literal(FOG_GEOJSON_ROLE_EXTENT_BOUNDARY),
        ),
    )
    // Keep all renderer-native safety geometry above every raster fog quad, matching the original
    // seam bridge's proven order. Adding the seam last places it closest to the top install guard,
    // so an ImageSource tile boundary cannot composite over the line that bridges that boundary.
    addLayerBelow(fillLayer, FogOverlayIds.InstallGuardLayer)
    addLayerBelow(boundaryLayer, FogOverlayIds.InstallGuardLayer)
    addLayerBelow(seamLayer, FogOverlayIds.InstallGuardLayer)
}

private fun FogTileBounds.toCanonicalRing(): List<Point> = listOf(
    Point.fromLngLat(westLongitude, southLatitude),
    Point.fromLngLat(eastLongitude, southLatitude),
    Point.fromLngLat(eastLongitude, northLatitude),
    Point.fromLngLat(westLongitude, northLatitude),
    Point.fromLngLat(westLongitude, southLatitude),
)

private fun Style.ensureFogInstallGuard(initiallyVisible: Boolean): BackgroundLayer {
    val existing = getLayer(FogOverlayIds.InstallGuardLayer)
    if (existing != null) {
        require(existing is BackgroundLayer) {
            "${FogOverlayIds.InstallGuardLayer} is not a background layer"
        }
        return existing
    }
    return newFogInstallGuard(if (initiallyVisible) Property.VISIBLE else Property.NONE).also(::addLayer)
}

private fun Style.installFogMosaic(
    mosaic: FogTileMosaic,
    spansWorld: Boolean,
    slot: FogGenerationSlot,
) {
    val bitmap = mosaic.mask.toBitmap()
    installFogMosaicQuad(
        FogOverlayIds.source(slot),
        FogOverlayIds.layer(slot),
        mosaic.bounds,
        bitmap,
    )
    if (spansWorld) {
        installFogMosaicQuad(
            FogOverlayIds.westRepeatSource(slot),
            FogOverlayIds.westRepeatLayer(slot),
            mosaic.bounds.shiftedByWorlds(-1),
            bitmap,
            zoomOpacity = visibleAtAndAboveWorldCopyZoom(),
        )
        installFogMosaicQuad(
            FogOverlayIds.eastRepeatSource(slot),
            FogOverlayIds.eastRepeatLayer(slot),
            mosaic.bounds.shiftedByWorlds(1),
            bitmap,
            zoomOpacity = visibleAtAndAboveWorldCopyZoom(),
        )
    }
}

private fun Style.installFogMosaicQuad(
    sourceId: String,
    layerId: String,
    bounds: FogTileBounds,
    bitmap: Bitmap,
    zoomOpacity: Expression? = null,
) {
    val coordinates = bounds.toQuad()
    check(getSource(sourceId) == null) { "$sourceId was not retired before slot reuse" }
    check(getLayer(layerId) == null) { "$layerId was not retired before slot reuse" }
    addSource(ImageSource(sourceId, coordinates, bitmap))
    val layer = RasterLayer(layerId, sourceId).withProperties(
        PropertyFactory.rasterFadeDuration(0f),
        PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
    )
    layer.setProperties(
        if (zoomOpacity == null) {
            PropertyFactory.rasterOpacity(1.0f)
        } else {
            PropertyFactory.rasterOpacity(zoomOpacity)
        },
    )
    if (getLayer(CurrentLocationOverlayIds.Layer) == null) {
        addLayerBelow(layer, FogOverlayIds.InstallGuardLayer)
    } else {
        addLayerBelow(layer, CurrentLocationOverlayIds.Layer)
    }
}

private fun Style.removeFogGenerationInterior(slot: FogGenerationSlot): Boolean {
    var changed = false
    val layers = listOf(
        FogOverlayIds.layer(slot),
        FogOverlayIds.westRepeatLayer(slot),
        FogOverlayIds.eastRepeatLayer(slot),
        FogSeamGuardIds.layer(slot),
    ) + FogBackdropIds.layers(slot)
    layers.forEach { layerId ->
        if (getLayer(layerId) != null) changed = removeLayer(layerId) || changed
    }
    val sources = listOf(
        FogOverlayIds.source(slot),
        FogOverlayIds.westRepeatSource(slot),
        FogOverlayIds.eastRepeatSource(slot),
        FogBackdropIds.northSource(slot),
        FogBackdropIds.southSource(slot),
        FogBackdropIds.westSource(slot),
        FogBackdropIds.eastSource(slot),
        FogBackdropIds.westWorldSource(slot),
        FogBackdropIds.eastWorldSource(slot),
        FogBackdropIds.wrappedSideSource(slot),
        FogSeamGuardIds.source(slot),
    )
    sources.forEach { sourceId ->
        if (getSource(sourceId) != null) changed = removeSource(sourceId) || changed
    }
    return changed
}

private fun Style.removeFogGenerationGuard(slot: FogGenerationSlot): Boolean {
    var changed = false
    listOf(
        FogSeamGuardIds.extentFillLayer(slot),
        FogSeamGuardIds.extentBoundaryLayer(slot),
    ).forEach { layerId ->
        if (getLayer(layerId) != null) changed = removeLayer(layerId) || changed
    }
    val sourceId = FogSeamGuardIds.extentSource(slot)
    if (getSource(sourceId) != null) changed = removeSource(sourceId) || changed
    return changed
}

private fun Style.removeFogGeneration(slot: FogGenerationSlot): Boolean {
    val interior = removeFogGenerationInterior(slot)
    val guard = removeFogGenerationGuard(slot)
    return interior || guard
}

private fun Style.hasFogGeneration(slot: FogGenerationSlot): Boolean =
    FogOverlayIds.generationLayers(slot).any { getLayer(it) != null } ||
        listOf(
            FogOverlayIds.source(slot),
            FogOverlayIds.westRepeatSource(slot),
            FogOverlayIds.eastRepeatSource(slot),
            FogBackdropIds.northSource(slot),
            FogBackdropIds.southSource(slot),
            FogBackdropIds.westSource(slot),
            FogBackdropIds.eastSource(slot),
            FogBackdropIds.westWorldSource(slot),
            FogBackdropIds.eastWorldSource(slot),
            FogBackdropIds.wrappedSideSource(slot),
            FogSeamGuardIds.source(slot),
            FogSeamGuardIds.extentSource(slot),
        ).any { getSource(it) != null }

private fun FogTileBounds.shiftedByWorlds(worlds: Int): FogTileBounds = copy(
    westLongitude = westLongitude + worlds * WORLD_LONGITUDE_SPAN,
    eastLongitude = eastLongitude + worlds * WORLD_LONGITUDE_SPAN,
)

private fun Style.installFogBackdrop(
    mosaic: FogTileMosaic,
    fogAlpha: Int,
    slot: FogGenerationSlot,
    repeatWorlds: Boolean,
) {
    val bands = FogBackdropGeometry.bands(mosaic)
    val wrappedSide = FogBackdropGeometry.wrappedSideBand(mosaic)
    installFogBackdropBand(
        FogBackdropIds.northSource(slot),
        FogBackdropIds.northLayer(slot),
        bands.north,
        fogAlpha,
        slot,
    )
    installFogBackdropBand(
        FogBackdropIds.southSource(slot),
        FogBackdropIds.southLayer(slot),
        bands.south,
        fogAlpha,
        slot,
    )
    installFogBackdropBand(
        FogBackdropIds.westSource(slot),
        FogBackdropIds.westLayer(slot),
        bands.west,
        fogAlpha,
        slot,
        zoomOpacity = if (wrappedSide == null) null else visibleAtAndAboveWorldCopyZoom(),
    )
    installFogBackdropBand(
        FogBackdropIds.eastSource(slot),
        FogBackdropIds.eastLayer(slot),
        bands.east,
        fogAlpha,
        slot,
        zoomOpacity = if (wrappedSide == null) null else visibleAtAndAboveWorldCopyZoom(),
    )
    if (wrappedSide != null) {
        installFogBackdropBand(
            FogBackdropIds.wrappedSideSource(slot),
            FogBackdropIds.wrappedSideLayer(slot),
            wrappedSide,
            fogAlpha,
            slot,
            zoomOpacity = visibleBelowWorldCopyZoom(),
        )
    }
    if (repeatWorlds) {
        val (west, east) = FogBackdropGeometry.worldRepeats(mosaic)
        installFogBackdropBand(
            FogBackdropIds.westWorldSource(slot),
            FogBackdropIds.westWorldLayer(slot),
            west,
            fogAlpha,
            slot,
            zoomOpacity = visibleAtAndAboveWorldCopyZoom(),
        )
        installFogBackdropBand(
            FogBackdropIds.eastWorldSource(slot),
            FogBackdropIds.eastWorldLayer(slot),
            east,
            fogAlpha,
            slot,
            zoomOpacity = visibleAtAndAboveWorldCopyZoom(),
        )
    }
}

private fun Style.installFogBackdropBand(
    sourceId: String,
    layerId: String,
    bounds: FogTileBounds,
    fogAlpha: Int,
    slot: FogGenerationSlot,
    zoomOpacity: Expression? = null,
) {
    val coordinates = bounds.toQuad()
    check(getSource(sourceId) == null) { "$sourceId was not retired before slot reuse" }
    check(getLayer(layerId) == null) { "$layerId was not retired before slot reuse" }
    addSource(ImageSource(sourceId, coordinates, fogBandBitmap(fogAlpha)))
    val layer = RasterLayer(layerId, sourceId).withProperties(
        PropertyFactory.rasterFadeDuration(0f),
        PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
    )
    layer.setProperties(
        if (zoomOpacity == null) {
            PropertyFactory.rasterOpacity(1.0f)
        } else {
            PropertyFactory.rasterOpacity(zoomOpacity)
        },
    )
    // Above the same generation's mosaic so any geometric overlap stays on the safe side.
    addLayerAbove(layer, FogOverlayIds.layer(slot))
}

private fun FogTileBounds.toQuad(): LatLngQuad = LatLngQuad(
    LatLng(northLatitude, westLongitude),
    LatLng(northLatitude, eastLongitude),
    LatLng(southLatitude, eastLongitude),
    LatLng(southLatitude, westLongitude),
)

/** One texel of the renderer's own fog, stretched over a band by the raster layer. */
private fun fogBandBitmap(fogAlpha: Int): Bitmap =
    Bitmap.createBitmap(intArrayOf((fogAlpha and 0xff) shl 24), 1, 1, Bitmap.Config.ARGB_8888)

private fun Style.installCurrentLocation(point: GeoPoint?) {
    val collection = point?.let {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)),
        )
    } ?: FeatureCollection.fromFeatures(emptyList<Feature>())
    val source = getSourceAs<GeoJsonSource>(CurrentLocationOverlayIds.Source)
    if (source == null) {
        addSource(GeoJsonSource(CurrentLocationOverlayIds.Source, collection))
    } else {
        source.setGeoJson(collection)
    }
    if (getLayer(CurrentLocationOverlayIds.Layer) == null) {
        val layer = CircleLayer(
            CurrentLocationOverlayIds.Layer,
            CurrentLocationOverlayIds.Source,
        ).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor("#1565C0"),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
        )
        if (getLayer(FogOverlayIds.InstallGuardLayer) == null) {
            addLayer(layer)
        } else {
            addLayerBelow(layer, FogOverlayIds.InstallGuardLayer)
        }
    }
}

private fun Style.installTrackOverlay(overlay: MapTrackOverlay?) {
    val segments = overlay?.segments.orEmpty()
    val lineFeatures = segments
        .filter { it.size >= 2 }
        .map { segment ->
            Feature.fromGeometry(
                LineString.fromLngLats(
                    segment.map { Point.fromLngLat(it.longitude, it.latitude) },
                ),
            )
        }
    val isolatedPointFeatures = segments
        .filter { it.size == 1 }
        .map { segment ->
            val point = segment.single()
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        }
    installGeoJsonSource(TrackOverlayIds.LineSource, FeatureCollection.fromFeatures(lineFeatures))
    installGeoJsonSource(TrackOverlayIds.PointSource, FeatureCollection.fromFeatures(isolatedPointFeatures))
    if (getLayer(TrackOverlayIds.LineLayer) == null) {
        val layer = LineLayer(TrackOverlayIds.LineLayer, TrackOverlayIds.LineSource).withProperties(
            PropertyFactory.lineColor("#6A1B9A"),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.9f),
        )
        if (getLayer(FogOverlayIds.InstallGuardLayer) == null) {
            addLayer(layer)
        } else {
            addLayerBelow(layer, FogOverlayIds.InstallGuardLayer)
        }
    }
    if (getLayer(TrackOverlayIds.PointLayer) == null) {
        val layer = CircleLayer(TrackOverlayIds.PointLayer, TrackOverlayIds.PointSource).withProperties(
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleColor("#6A1B9A"),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
        )
        if (getLayer(FogOverlayIds.InstallGuardLayer) == null) {
            addLayer(layer)
        } else {
            addLayerBelow(layer, FogOverlayIds.InstallGuardLayer)
        }
    }
}

private fun Style.installGeoJsonSource(id: String, collection: FeatureCollection) {
    val source = getSourceAs<GeoJsonSource>(id)
    if (source == null) {
        addSource(GeoJsonSource(id, collection))
    } else {
        source.setGeoJson(collection)
    }
}

private fun FogPixelMask.toBitmap(): Bitmap {
    val alpha = copyAlpha()
    val pixels = IntArray(alpha.size) { index ->
        (alpha[index].toInt() and 0xff) shl 24
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private suspend fun MapView.installFogGenerationAndAwait(
    map: MapLibreMap,
    style: Style,
    mosaic: FogTileMosaic,
    fogAlpha: Int,
    activeSlot: FogGenerationSlot?,
    installFaultForTesting: (() -> Unit)? = null,
): FogGenerationSlot {
    val targetSlot = FogGenerationSlot.next(activeSlot)
    check(targetSlot != activeSlot) { "A fog generation cannot replace itself in place" }
    val rendered = withTimeoutOrNull(FOG_FRAME_TIMEOUT_MILLIS) {
        if (style.hasFogGeneration(targetSlot)) {
            awaitRendererProgressAfter(map) {
                style.removeFogGeneration(targetSlot)
            }
        }
        awaitRendererProgressAfter(map) {
            style.installFogGeneration(
                mosaic = mosaic,
                fogAlpha = fogAlpha,
                slot = targetSlot,
                keepInstallGuardVisible = activeSlot == null,
                installFaultForTesting = installFaultForTesting,
            )
        }
        true
    }
    if (rendered != true) error("MapLibre did not report renderer progress for the fog frame in time")
    return targetSlot
}

private suspend fun MapView.hideInitialFogGuardAndAwait(map: MapLibreMap, style: Style) {
    retryRendererProgressUntilCancelled {
        awaitRendererProgressAfter(map) {
            style.ensureFogInstallGuard(initiallyVisible = true).setProperties(
                PropertyFactory.visibility(Property.NONE),
            )
        }
    }
}

private suspend fun MapView.retireFogGenerationAndAwait(
    map: MapLibreMap,
    style: Style,
    retiredSlot: FogGenerationSlot,
) {
    retryRendererProgressUntilCancelled {
        awaitRendererProgressAfter(map) {
            style.removeFogGenerationInterior(retiredSlot)
        }
        // The outside guard is deliberately last. Until this second renderer update lands, either
        // it or the newly committed generation still covers every point in every world copy.
        // If a superseding request cancels this mid-retirement, the remains stay until the next
        // install reuses the slot - a synchronous removal in a cancellation handler was tried and
        // reverted, because a late-firing handler races the superseding effect's own install of
        // this very slot and can delete its freshly installed layers.
        awaitRendererProgressAfter(map) {
            style.removeFogGenerationGuard(retiredSlot)
        }
    }
}

/**
 * Repeats an idempotent fail-closed renderer transition after synchronous failures, but suspends
 * without a wall-clock failure while a stopped MapView cannot produce frames. The owning
 * LaunchedEffect is cancelled on disposal or style replacement, which removes the listener. A
 * timeout here used to escape the retry boundary and could crash or strand an opaque guard merely
 * because the Activity remained stopped for five seconds.
 */
private suspend fun retryRendererProgressUntilCancelled(operation: suspend () -> Unit) {
    while (true) {
        try {
            operation()
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            delay(FOG_RETRY_DELAY_MILLIS)
        }
    }
}

/**
 * Waits for renderer progress after [action], ignoring callbacks that arrive before the next view
 * turn and explicitly requesting another repaint. Partial frames count as progress: basemap network
 * work is unrelated to the locally complete fog Style snapshot, and requiring `fullyRendered` can
 * otherwise stall forever while offline. This callback is a liveness fence, not a style mutation
 * receipt: MapLibre does not expose a source-generation token with it, so an already in-flight frame
 * can still finish after the fence is armed.
 *
 * Fog safety therefore never depends on this callback ordering. A complete old generation remains
 * installed while a new slot is built; every partial initial install keeps the full-screen guard;
 * and retirement removes the old outside guard only after the new complete slot is already present
 * in the immutable Style snapshot. The callback merely bounds how quickly that safe state advances.
 */
private suspend fun MapView.awaitRendererProgressAfter(
    map: MapLibreMap,
    action: () -> Unit,
) {
    suspendCancellableCoroutine { continuation ->
        val armed = AtomicBoolean(false)
        lateinit var listener: MapView.OnDidFinishRenderingFrameListener
        listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
            if (armed.get() && continuation.isActive) {
                removeOnDidFinishRenderingFrameListener(listener)
                continuation.resume(Unit)
            }
        }
        addOnDidFinishRenderingFrameListener(listener)
        continuation.invokeOnCancellation {
            removeOnDidFinishRenderingFrameListener(listener)
        }
        try {
            action()
            val posted = post {
                if (continuation.isActive) {
                    armed.set(true)
                    map.triggerRepaint()
                }
            }
            if (!posted) error("MapView rejected the post-install repaint")
        } catch (failure: Exception) {
            removeOnDidFinishRenderingFrameListener(listener)
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}

private const val WORLD_LONGITUDE_SPAN = 360.0

/** A visible region is a quad; anything else means the projection could not answer. */
private const val VISIBLE_REGION_CORNERS = 4

private const val FOG_RETRY_DELAY_MILLIS = 1_000L
private const val FOG_FRAME_TIMEOUT_MILLIS = 5_000L
private const val FOG_SEAM_GUARD_WIDTH_PIXELS = 3.0f
private const val FOG_EXTENT_GUARD_BOUNDARY_WIDTH_PIXELS = 4.0f
private const val FOG_GEOJSON_BUFFER = 512
private const val FOG_GEOJSON_ROLE_PROPERTY = "trailveil-fog-role"
private const val FOG_GEOJSON_ROLE_EXTENT_FILL = "extent-fill"
private const val FOG_GEOJSON_ROLE_EXTENT_BOUNDARY = "extent-boundary"

private object StaleCanonicalFogInstallException : Exception()
private const val TRACK_CAMERA_PADDING_PX = 72

/**
 * Renderer-owned zoom boundary for the two mutually exclusive world-copy arrangements.
 *
 * Below this the renderer repeats an image source across world copies itself and ours are a second
 * coat of fog; at and above it it does not, and without ours the neighbouring copy of the map is
 * drawn with no fog on it at all. Both failures are total, not marginal.
 *
 * The value is measured, not chosen. Swept at the antimeridian on the production style in steps of
 * 0.02 with the copies forced each way: at 0.98 having them on blacks out 50.000% of the screen and
 * having them off costs nothing; at 1.00 having them on costs nothing and having them off leaks
 * 49.722%. The edge is exactly the integer, which is what the renderer's own tile zoom is counted
 * in — so it is a property of the renderer rather than of any one display. Two earlier values were
 * inferred from a couple of readings instead, and both were wrong on a real device. An Android
 * `Handler` callback previously changed `visibility` at this edge; a full-suite run reproduced a
 * settled half-screen leak at exactly zoom 1. A camera-zoom opacity expression makes the renderer
 * choose the arrangement in the same frame as its own repetition rule.
 */
private const val WORLD_COPY_ZOOM = 1.0
private const val MAP_STATE_PROVIDER = "maplibre"

private fun visibleAtAndAboveWorldCopyZoom(): Expression = Expression.step(
    Expression.zoom(),
    0.0,
    Expression.stop(WORLD_COPY_ZOOM, 1.0),
)

private fun visibleBelowWorldCopyZoom(): Expression = Expression.step(
    Expression.zoom(),
    1.0,
    Expression.stop(WORLD_COPY_ZOOM, 0.0),
)

/**
 * MapLibre asks its parent to stop intercepting touches once while the map initialises, and Compose
 * view interop drops that request when a gesture ends, so a scrolling ancestor claims every later
 * drag. Re-asking on each gesture keeps map pans owned by the map wherever the surface is hosted.
 */
private class GestureOwningMapView(
    context: Context,
    options: MapLibreMapOptions,
) : MapView(context, options) {
    constructor(context: Context) : this(context, MapLibreMapOptions.createFromAttributes(context))

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(event)
    }
}

private class MapViewLifecycleBinding(
    private val mapView: MapView,
) {
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun synchronize(state: Lifecycle.State) {
        if (state.isAtLeast(Lifecycle.State.STARTED)) start()
        if (state.isAtLeast(Lifecycle.State.RESUMED)) resume()
    }

    fun onEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> release()
            else -> Unit
        }
    }

    fun release() {
        if (destroyed) return
        pause()
        stop()
        mapView.onDestroy()
        destroyed = true
    }

    private fun start() {
        if (!started && !destroyed) {
            mapView.onStart()
            started = true
        }
    }

    private fun resume() {
        if (!resumed && !destroyed) {
            start()
            mapView.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (resumed && !destroyed) {
            mapView.onPause()
            resumed = false
        }
    }

    private fun stop() {
        if (started && !destroyed) {
            pause()
            mapView.onStop()
            started = false
        }
    }
}
