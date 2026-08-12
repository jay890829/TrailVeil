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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class BasemapLoadState {
    LOADING,
    ONLINE,
    LOCAL_FALLBACK,
}

internal object MapSurfaceTestTags {
    const val Map = "trailveil_map"
    const val Status = "trailveil_map_status"
    const val FogSafetyCover = "trailveil_fog_safety_cover"
}

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
}

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
}

internal object FogSeamGuardIds {
    const val Source = "trailveil-fog-seam-guard-source"
    const val Layer = "trailveil-fog-seam-guard-layer"
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

/** Immutable test evidence for the canonical fog geometry that actually reached the renderer. */
internal data class InstalledFogCoverageSnapshot(
    val generation: Long,
    val extent: FogSurroundExtent,
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
    // Set only around a follow step, whose reach is bounded by how far a person walked since the
    // last fix. Every other programmed move keeps hiding the overlay until its rebuild lands.
    val followingCameraMove = remember(mapView) { AtomicBoolean(false) }
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
    SideEffect {
        mapView.setTag(R.id.map_fog_canonical_generation, publishedFogGeneration)
        mapView.setTag(R.id.map_basemap_load_state, publishedLoadState)
    }

    fun useLocalFallback() {
        val map = readyMap ?: return
        if (!compositionActive.get() || !styleGenerationActive.get()) return
        if (fallbackRequested) return
        fallbackRequested = true
        map.setStyle(Style.Builder().fromJson(fallbackStyleJson)) { style ->
            if (compositionActive.get() && styleGenerationActive.get()) {
                readyStyle = style
                loadState = BasemapLoadState.LOCAL_FALLBACK
            }
        }
    }

    DisposableEffect(mapView, lifecycle, savedStateRegistry, savedStateKey) {
        compositionActive.set(true)

        savedStateRegistry.registerSavedStateProvider(savedStateKey) {
            Bundle().also(mapView::onSaveInstanceState)
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
            map.setStyle(Style.Builder().fromUri(provider.styleUri)) { style ->
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
        map.animateCamera(
            if (request.zoom == null) {
                CameraUpdateFactory.newLatLng(target)
            } else {
                CameraUpdateFactory.newLatLngZoom(target, request.zoom)
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
            // Off screen is not a step, it is a move — so it is made like any other one, with the
            // cover raised until fog has been rebuilt around wherever the user turned out to be.
            FollowCameraMove.JUMP ->
                map.animateCamera(CameraUpdateFactory.newLatLng(destination))
        }
    }

    // Asked both by the camera-move listener and by the two install paths. Coverage becoming
    // installed is not the same as coverage being enough: a re-render can land while a gesture has
    // already carried the camera past what the installed surround holds, and lowering the cover on
    // the strength of a successful install alone would uncover a map that is leaking.
    fun requestViewport() {
        val map = readyMap ?: return
        followingCameraMove.set(false)
        val request = map.fogViewportRequest()
        canonicalFogLoaded = false
        fogRenderFailed = false
        fogPlaceholderReadyGeneration = -1L
        fogViewportRequest = request
        fogViewportGeneration += 1L
    }

    fun surroundHoldsForCamera(): Boolean {
        val extent = installedSurround ?: return true
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
        return extent.covers(
            corners.map { corner -> GeoPoint(corner.latitude, corner.longitude) },
        )
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
                fogCoverageInstalled = false
                canonicalFogLoaded = false
                fogRenderFailed = true
                fogSyncFailed = true
                fogPlaceholderReadyGeneration = -1L
                fogViewportGeneration += 1L
                currentOnFogFailure(failure)
                delay(1_000L)
            }
        }
    }

    DisposableEffect(readyMap, readyStyle, fogRuntime, fogRequired) {
        val map = readyMap
        val style = readyStyle
        val runtime = fogRuntime
        if (!fogRequired || map == null || style == null || runtime == null) {
            onDispose { }
        } else {
            // Gesture motion deliberately leaves the installed overlay alone. The mosaic is
            // anchored to the map, so it stays truthful wherever a gesture takes the camera, and
            // the backdrop bands keep everything around it fogged in the same rendered frame.
            val idleListener = MapLibreMap.OnCameraIdleListener(::requestViewport)
            val moveStartedListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                val gesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                // A programmed camera move can jump anywhere at once, so it still hides the
                // overlay until its rebuild lands. Gestures and follow steps do not: both are
                // bounded, and both would black the map out under a user who is only walking or
                // panning. The geographic surround is what keeps those moves safe in the renderer;
                // the listener below is only the eventual fail-closed reaction if they leave it.
                if (!gesture && !followingCameraMove.get()) {
                    fogCoverageInstalled = false
                    installedSurround = null
                    canonicalFogLoaded = false
                    fogRenderFailed = false
                    fogPlaceholderReadyGeneration = -1L
                    fogViewportRequest = null
                    fogViewportGeneration += 1L
                }
            }
            // The surround is large but finite, because a quad past the renderer's precision is
            // drawn over the whole map instead of being clipped. Everything else here argues that
            // no gesture travels far enough to matter; this checks each dispatched move and requests
            // the Compose cover when the camera leaves. The callback and recomposition are not
            // renderer-atomic. The required upright four-level gesture stays inside the surround and
            // therefore does not depend on this reaction; long tilted gestures can request the cover
            // at their final audited state.
            val moveListener = MapLibreMap.OnCameraMoveListener {
                if (fogCoverageInstalled && !surroundHoldsForCamera()) {
                    fogCoverageInstalled = false
                }
            }
            val moveCanceledListener = MapLibreMap.OnCameraMoveCanceledListener(::requestViewport)
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
                        fogCoverageInstalled = false
                        installedSurround = null
                        canonicalFogLoaded = false
                        fogRenderFailed = true
                        currentOnFogFailure(failure)
                    }
                },
            ) {
                val placeholder = runtime.viewportCoordinator.placeholder(request)
                mapView.installFogOverlayAndAwait(
                    style = style,
                    mosaic = placeholder.mosaic,
                    fogAlpha = runtime.viewportCoordinator.style.fogAlpha,
                )
                placeholder.mosaic
            }.let { installed ->
                if (
                    generation != fogViewportGeneration ||
                    request != fogViewportRequest ||
                    style !== readyStyle
                ) {
                    return@LaunchedEffect
                }
                installedSurround = FogBackdropGeometry.extent(installed)
            }
            // Installing coverage is not the same as coverage being enough: the camera may have
            // moved on while this was being built. Asking for another rebuild — rather than only
            // declining to lower the cover — is what stops the map staying black with nothing
            // scheduled to lift it.
            if (surroundHoldsForCamera()) {
                fogCoverageInstalled = true
            } else {
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
        val rendered = renderCanonicalFogWithRetry(
            request = request,
            retryDelayMillis = FOG_RETRY_DELAY_MILLIS,
            render = { viewport ->
                withContext(Dispatchers.Default) {
                    runtime.viewportCoordinator.render(viewport)
                }
            },
            installAndAwait = { viewport ->
                mapView.installFogOverlayAndAwait(
                    style = style,
                    mosaic = viewport.mosaic,
                    fogAlpha = runtime.viewportCoordinator.style.fogAlpha,
                    installFaultForTesting = fogInstallFaultForTesting,
                )
            },
            onFailure = { failure ->
                if (
                    generation == fogViewportGeneration &&
                    request == fogViewportRequest &&
                    style === readyStyle
                ) {
                    // The install writes a mosaic, its repeats and six bands in one call stack,
                    // so a throw part way through leaves a shape nobody can name. Treat installed
                    // coverage as lost until a whole install has succeeded again, or that
                    // half-applied state is presented as fog.
                    fogCoverageInstalled = false
                    installedSurround = null
                    canonicalFogLoaded = false
                    fogRenderFailed = true
                    currentOnFogFailure(failure)
                }
            },
        )
        if (
            generation != fogViewportGeneration ||
            request != fogViewportRequest ||
            style !== readyStyle
        ) {
            return@LaunchedEffect
        }
        val installedExtent = FogBackdropGeometry.extent(rendered.mosaic)
        installedSurround = installedExtent
        if (!surroundHoldsForCamera()) {
            // The new geometry is already in the renderer and provably does not reach the camera.
            // Declining to *set* the flag is not enough: a `true` left over from the previous
            // install would keep the cover down over an overlay just proved insufficient.
            //
            // Following is exempt for the same reason the move-started listener exempts it: an
            // install landing a step behind a walking user is routine, and blacking the map out
            // under someone who is only walking is a worse outcome than the transient it fixes.
            // That is not a hole — the camera-move listener still raises the cover on the next move
            // if a followed camera has genuinely left the installed surround.
            if (!followingCameraMove.get()) {
                fogCoverageInstalled = false
            }
            requestViewport()
            return@LaunchedEffect
        }
        fogCoverageInstalled = true
        canonicalFogLoaded = true
        fogRenderFailed = false
        currentOnFogCoverageInstalledForTesting?.invoke(
            InstalledFogCoverageSnapshot(generation = generation, extent = installedExtent),
        )
        currentOnFogRendered?.invoke(rendered)
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .semantics { this.contentDescription = contentDescription }
                .testTag(MapSurfaceTestTags.Map),
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

/**
 * Installs the mosaic and the bands that close around it in one main-thread call stack, without an
 * explicit renderer wait between their mutations. The renderer-owned guard is made visible first
 * and hidden last in this same call stack. MapLibre 13.4.1 coalesces the image and style updates;
 * a synchronous throw therefore leaves the guard visible, which the failure-injection gate checks
 * directly. The GeoJSON seam source tiles asynchronously, so this function does not claim that a
 * successful rebuild is renderer-atomic before the later fully-rendered callback; that broader
 * residual remains tracked separately. The Compose cover is a second line of defence.
 */
private fun Style.installFogOverlay(
    mosaic: FogTileMosaic,
    fogAlpha: Int,
    installFaultForTesting: (() -> Unit)? = null,
) {
    val installGuard = ensureFogInstallGuard()
    installGuard.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    installFogSeamGuard(mosaic, fogAlpha)
    val spansWorld = FogBackdropGeometry.spansWorld(mosaic)
    // The copies are always installed when there is a world to copy. A camera-zoom opacity step is
    // attached before each layer enters the style, so the renderer — not a Handler-dispatched
    // camera callback — decides which mutually exclusive arrangement is drawn for the frame.
    installFogMosaic(mosaic, spansWorld)
    installFaultForTesting?.invoke()
    installFogBackdrop(
        mosaic,
        fogAlpha,
        // Only when the surround reaches all the way round is there a neighbouring world copy the
        // camera can see, and only then is a world-wide quad small enough to be drawn rather than
        // smeared over the map.
        repeatWorlds = FogBackdropGeometry.surroundSpansWorld(mosaic) && !spansWorld,
    )
    installGuard.setProperties(PropertyFactory.visibility(Property.NONE))
}

/** A screen-pixel bridge over the four independently quantized ImageSource edges. */
private fun Style.installFogSeamGuard(mosaic: FogTileMosaic, fogAlpha: Int) {
    val bands = FogBackdropGeometry.bands(mosaic)
    val lines = if (FogBackdropGeometry.surroundSpansWorld(mosaic)) {
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
    installGeoJsonSource(
        FogSeamGuardIds.Source,
        FeatureCollection.fromFeatures(
            lines.map { points -> Feature.fromGeometry(LineString.fromLngLats(points)) },
        ),
    )
    val existing = getLayer(FogSeamGuardIds.Layer)
    val layer = if (existing == null) {
        LineLayer(FogSeamGuardIds.Layer, FogSeamGuardIds.Source).withProperties(
            PropertyFactory.lineColor("#000000"),
            PropertyFactory.lineWidth(FOG_SEAM_GUARD_WIDTH_PIXELS),
        )
    } else {
        require(existing is LineLayer) { "${FogSeamGuardIds.Layer} is not a line layer" }
        existing
    }
    layer.setProperties(
        PropertyFactory.lineOpacity(fogAlpha / 255.0f),
        PropertyFactory.visibility(if (lines.isEmpty()) Property.NONE else Property.VISIBLE),
    )
    if (existing == null) addLayerBelow(layer, FogOverlayIds.InstallGuardLayer)
}

private fun Style.ensureFogInstallGuard(): BackgroundLayer {
    val existing = getLayer(FogOverlayIds.InstallGuardLayer)
    if (existing != null) {
        require(existing is BackgroundLayer) {
            "${FogOverlayIds.InstallGuardLayer} is not a background layer"
        }
        return existing
    }
    return BackgroundLayer(FogOverlayIds.InstallGuardLayer).withProperties(
        PropertyFactory.backgroundColor("#000000"),
        PropertyFactory.backgroundOpacity(1.0f),
        PropertyFactory.visibility(Property.NONE),
    ).also(::addLayer)
}

private fun Style.installFogMosaic(mosaic: FogTileMosaic, spansWorld: Boolean) {
    val bitmap = mosaic.mask.toBitmap()
    installFogMosaicQuad(FogOverlayIds.Source, FogOverlayIds.Layer, mosaic.bounds, bitmap)
    if (spansWorld) {
        installFogMosaicQuad(
            FogOverlayIds.WestRepeatSource,
            FogOverlayIds.WestRepeatLayer,
            mosaic.bounds.shiftedByWorlds(-1),
            bitmap,
            zoomOpacity = visibleAtAndAboveWorldCopyZoom(),
        )
        installFogMosaicQuad(
            FogOverlayIds.EastRepeatSource,
            FogOverlayIds.EastRepeatLayer,
            mosaic.bounds.shiftedByWorlds(1),
            bitmap,
            zoomOpacity = visibleAtAndAboveWorldCopyZoom(),
        )
    } else {
        // A narrower mosaic is placed around the camera already, so repeats would be dead weight
        // carrying a copy of the mask for nothing.
        removeFogMosaicQuad(FogOverlayIds.WestRepeatSource, FogOverlayIds.WestRepeatLayer)
        removeFogMosaicQuad(FogOverlayIds.EastRepeatSource, FogOverlayIds.EastRepeatLayer)
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
    val source = getSourceAs<ImageSource>(sourceId)
    if (source == null) {
        addSource(ImageSource(sourceId, coordinates, bitmap))
    } else {
        source.setCoordinates(coordinates)
        source.setImage(bitmap)
    }
    val existingLayer = getLayer(layerId)
    val layer = if (existingLayer == null) {
        RasterLayer(layerId, sourceId).withProperties(
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
        )
    } else {
        require(existingLayer is RasterLayer) { "$layerId is not a raster layer" }
        existingLayer
    }
    layer.setProperties(
        if (zoomOpacity == null) {
            PropertyFactory.rasterOpacity(1.0f)
        } else {
            PropertyFactory.rasterOpacity(zoomOpacity)
        },
    )
    if (existingLayer == null) {
        if (getLayer(CurrentLocationOverlayIds.Layer) == null) {
            addLayerBelow(layer, FogOverlayIds.InstallGuardLayer)
        } else {
            addLayerBelow(layer, CurrentLocationOverlayIds.Layer)
        }
    }
}

private fun Style.removeFogMosaicQuad(sourceId: String, layerId: String) {
    if (getLayer(layerId) != null) removeLayer(layerId)
    if (getSource(sourceId) != null) removeSource(sourceId)
}

private fun FogTileBounds.shiftedByWorlds(worlds: Int): FogTileBounds = copy(
    westLongitude = westLongitude + worlds * WORLD_LONGITUDE_SPAN,
    eastLongitude = eastLongitude + worlds * WORLD_LONGITUDE_SPAN,
)

private fun Style.installFogBackdrop(
    mosaic: FogTileMosaic,
    fogAlpha: Int,
    repeatWorlds: Boolean,
) {
    val bands = FogBackdropGeometry.bands(mosaic)
    val wrappedSide = FogBackdropGeometry.wrappedSideBand(mosaic)
    installFogBackdropBand(
        FogBackdropIds.NorthSource,
        FogBackdropIds.NorthLayer,
        bands.north,
        fogAlpha,
    )
    installFogBackdropBand(
        FogBackdropIds.SouthSource,
        FogBackdropIds.SouthLayer,
        bands.south,
        fogAlpha,
    )
    installFogBackdropBand(
        FogBackdropIds.WestSource,
        FogBackdropIds.WestLayer,
        bands.west,
        fogAlpha,
        zoomOpacity = if (wrappedSide == null) null else visibleAtAndAboveWorldCopyZoom(),
    )
    installFogBackdropBand(
        FogBackdropIds.EastSource,
        FogBackdropIds.EastLayer,
        bands.east,
        fogAlpha,
        zoomOpacity = if (wrappedSide == null) null else visibleAtAndAboveWorldCopyZoom(),
    )
    if (wrappedSide == null) {
        removeFogMosaicQuad(FogBackdropIds.WrappedSideSource, FogBackdropIds.WrappedSideLayer)
    } else {
        installFogBackdropBand(
            FogBackdropIds.WrappedSideSource,
            FogBackdropIds.WrappedSideLayer,
            wrappedSide,
            fogAlpha,
            zoomOpacity = visibleBelowWorldCopyZoom(),
        )
    }
    if (repeatWorlds) {
        val (west, east) = FogBackdropGeometry.worldRepeats(mosaic)
        installFogBackdropBand(
            FogBackdropIds.WestWorldSource,
            FogBackdropIds.WestWorldLayer,
            west,
            fogAlpha,
            zoomOpacity = visibleAtAndAboveWorldCopyZoom(),
        )
        installFogBackdropBand(
            FogBackdropIds.EastWorldSource,
            FogBackdropIds.EastWorldLayer,
            east,
            fogAlpha,
            zoomOpacity = visibleAtAndAboveWorldCopyZoom(),
        )
    } else {
        // A mosaic that spans the world is repeated itself, and flat fog over those copies would
        // bury the explored area they are there to show.
        removeFogMosaicQuad(FogBackdropIds.WestWorldSource, FogBackdropIds.WestWorldLayer)
        removeFogMosaicQuad(FogBackdropIds.EastWorldSource, FogBackdropIds.EastWorldLayer)
    }
}

private fun Style.installFogBackdropBand(
    sourceId: String,
    layerId: String,
    bounds: FogTileBounds,
    fogAlpha: Int,
    zoomOpacity: Expression? = null,
) {
    val coordinates = bounds.toQuad()
    val source = getSourceAs<ImageSource>(sourceId)
    if (source == null) {
        addSource(ImageSource(sourceId, coordinates, fogBandBitmap(fogAlpha)))
    } else {
        source.setCoordinates(coordinates)
    }
    val existingLayer = getLayer(layerId)
    val layer = if (existingLayer == null) {
        RasterLayer(layerId, sourceId).withProperties(
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
        )
    } else {
        require(existingLayer is RasterLayer) { "$layerId is not a raster layer" }
        existingLayer
    }
    layer.setProperties(
        if (zoomOpacity == null) {
            PropertyFactory.rasterOpacity(1.0f)
        } else {
            PropertyFactory.rasterOpacity(zoomOpacity)
        },
    )
    if (existingLayer == null) {
        // Above the mosaic so any geometric overlap stays on the safe, over-fogged side. The
        // screen-pixel seam guard separately covers the independent ImageSource vertex grids.
        if (getLayer(FogOverlayIds.Layer) == null) {
            addLayer(layer)
        } else {
            addLayerAbove(layer, FogOverlayIds.Layer)
        }
    }
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

internal suspend fun renderCanonicalFogWithRetry(
    request: FogViewportRequest,
    retryDelayMillis: Long,
    render: suspend (FogViewportRequest) -> FogViewportRender,
    installAndAwait: suspend (FogViewportRender) -> Unit,
    onFailure: (Exception) -> Unit,
): FogViewportRender = retryFogOperation(retryDelayMillis, onFailure) {
    render(request).also { rendered -> installAndAwait(rendered) }
}

private suspend fun <T> retryFogOperation(
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

private suspend fun MapView.installFogOverlayAndAwait(
    style: Style,
    mosaic: FogTileMosaic,
    fogAlpha: Int,
    installFaultForTesting: (() -> Unit)? = null,
) {
    val rendered = withTimeoutOrNull(FOG_FRAME_TIMEOUT_MILLIS) {
        awaitFullyRenderedFrameAfter {
            style.installFogOverlay(mosaic, fogAlpha, installFaultForTesting)
        }
        true
    }
    if (rendered != true) error("MapLibre did not fully render the fog frame in time")
}

private suspend fun MapView.awaitFullyRenderedFrameAfter(action: () -> Unit) {
    suspendCancellableCoroutine { continuation ->
        lateinit var listener: MapView.OnDidFinishRenderingFrameListener
        listener = MapView.OnDidFinishRenderingFrameListener { fullyRendered, _, _ ->
            if (fullyRendered && continuation.isActive) {
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
private const val TRACK_CAMERA_PADDING_PX = 72

/**
 * Short enough that the user stays with the map rather than watching it catch up, long enough that
 * the step reads as the map following them rather than as the map jumping.
 */
private const val FOLLOW_EASE_MILLIS = 450

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

/** Where the map's own controls sit when the host does not stack anything of its own on top. */
internal val MAP_CONTROL_INSET: Dp = 12.dp

@Composable
private fun MapStatusBadge(text: String) {
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
