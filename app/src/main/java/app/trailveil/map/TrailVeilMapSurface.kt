package app.trailveil.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    val Layers: List<String> = listOf(NorthLayer, SouthLayer, WestLayer, EastLayer)
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
    val zoom: Double = 16.0,
) {
    init {
        require(requestId >= 0L) { "requestId must be non-negative" }
        require(zoom.isFinite() && zoom in 0.0..22.0) { "zoom must be in 0..22" }
    }
}

internal data class MapTrackOverlay(
    val requestId: Long,
    val segments: List<List<GeoPoint>>,
) {
    init {
        require(requestId >= 0L) { "requestId must be non-negative" }
    }
}

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
    trackOverlay: MapTrackOverlay? = null,
    onFogRendered: ((FogViewportRender) -> Unit)? = null,
    onFogFailure: (Throwable) -> Unit = {},
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
    var canonicalFogLoaded by remember(mapView, fogRuntime, fogRequired) {
        mutableStateOf(!fogRequired)
    }
    var fogRenderFailed by remember(mapView, fogRuntime) { mutableStateOf(false) }
    var fogSyncFailed by remember(mapView, fogRuntime) { mutableStateOf(false) }
    var fogBaselineReady by remember(mapView, fogRuntime) {
        mutableStateOf(fogRuntime == null)
    }
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
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(request.point.latitude, request.point.longitude),
                request.zoom,
            ),
        )
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
            fun requestViewport() {
                val request = map.fogViewportRequest()
                canonicalFogLoaded = false
                fogRenderFailed = false
                fogPlaceholderReadyGeneration = -1L
                fogViewportRequest = request
                fogViewportGeneration += 1L
            }

            val idleListener = MapLibreMap.OnCameraIdleListener(::requestViewport)
            val moveStartedListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                // A programmed camera move can jump anywhere at once, including past the bands,
                // so it still hides the overlay until the rebuild lands. Gestures cannot: their
                // reach is bounded and the bands already cover it.
                if (reason != MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    fogCoverageInstalled = false
                    canonicalFogLoaded = false
                    fogRenderFailed = false
                    fogPlaceholderReadyGeneration = -1L
                    fogViewportRequest = null
                    fogViewportGeneration += 1L
                }
            }
            val moveCanceledListener = MapLibreMap.OnCameraMoveCanceledListener(::requestViewport)
            map.addOnCameraIdleListener(idleListener)
            map.addOnCameraMoveStartedListener(moveStartedListener)
            map.addOnCameraMoveCancelListener(moveCanceledListener)
            requestViewport()
            onDispose {
                map.removeOnCameraIdleListener(idleListener)
                map.removeOnCameraMoveStartedListener(moveStartedListener)
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
            }
            if (
                generation != fogViewportGeneration ||
                request != fogViewportRequest ||
                style !== readyStyle
            ) {
                return@LaunchedEffect
            }
            fogCoverageInstalled = true
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
                )
            },
            onFailure = { failure ->
                if (
                    generation == fogViewportGeneration &&
                    request == fogViewportRequest &&
                    style === readyStyle
                ) {
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
        fogCoverageInstalled = true
        canonicalFogLoaded = true
        fogRenderFailed = false
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
 * Installs the mosaic and the bands that close around it in one call stack, so no rendered frame
 * can pair one of them with the other's previous geometry.
 */
private fun Style.installFogOverlay(mosaic: FogTileMosaic, fogAlpha: Int) {
    installFogMosaic(mosaic)
    installFogBackdrop(mosaic, fogAlpha)
}

private fun Style.installFogMosaic(mosaic: FogTileMosaic) {
    val coordinates = mosaic.bounds.toQuad()
    val bitmap = mosaic.mask.toBitmap()
    val source = getSourceAs<ImageSource>(FogOverlayIds.Source)
    if (source == null) {
        addSource(ImageSource(FogOverlayIds.Source, coordinates, bitmap))
    } else {
        source.setCoordinates(coordinates)
        source.setImage(bitmap)
    }
    if (getLayer(FogOverlayIds.Layer) == null) {
        val layer = RasterLayer(FogOverlayIds.Layer, FogOverlayIds.Source).withProperties(
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
        )
        if (getLayer(CurrentLocationOverlayIds.Layer) == null) {
            addLayer(layer)
        } else {
            addLayerBelow(layer, CurrentLocationOverlayIds.Layer)
        }
    }
}

private fun Style.installFogBackdrop(mosaic: FogTileMosaic, fogAlpha: Int) {
    val bands = FogBackdropGeometry.bands(mosaic)
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
    )
    installFogBackdropBand(
        FogBackdropIds.EastSource,
        FogBackdropIds.EastLayer,
        bands.east,
        fogAlpha,
    )
}

private fun Style.installFogBackdropBand(
    sourceId: String,
    layerId: String,
    bounds: FogTileBounds,
    fogAlpha: Int,
) {
    val coordinates = bounds.toQuad()
    val source = getSourceAs<ImageSource>(sourceId)
    if (source == null) {
        addSource(ImageSource(sourceId, coordinates, fogBandBitmap(fogAlpha)))
    } else {
        source.setCoordinates(coordinates)
    }
    if (getLayer(layerId) == null) {
        val layer = RasterLayer(layerId, sourceId).withProperties(
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
        )
        // Above the mosaic, so the half-pixel the bands deliberately overlap it stays one flat
        // layer of fog instead of a darker seam, and still below the location and track overlays.
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
        addLayer(
            CircleLayer(
                CurrentLocationOverlayIds.Layer,
                CurrentLocationOverlayIds.Source,
            ).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor("#1565C0"),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
            ),
        )
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
        addLayer(
            LineLayer(TrackOverlayIds.LineLayer, TrackOverlayIds.LineSource).withProperties(
                PropertyFactory.lineColor("#6A1B9A"),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.9f),
            ),
        )
    }
    if (getLayer(TrackOverlayIds.PointLayer) == null) {
        addLayer(
            CircleLayer(TrackOverlayIds.PointLayer, TrackOverlayIds.PointSource).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor("#6A1B9A"),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
            ),
        )
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
) {
    val rendered = withTimeoutOrNull(FOG_FRAME_TIMEOUT_MILLIS) {
        awaitFullyRenderedFrameAfter { style.installFogOverlay(mosaic, fogAlpha) }
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

private const val FOG_RETRY_DELAY_MILLIS = 1_000L
private const val FOG_FRAME_TIMEOUT_MILLIS = 5_000L
private const val TRACK_CAMERA_PADDING_PX = 72

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
