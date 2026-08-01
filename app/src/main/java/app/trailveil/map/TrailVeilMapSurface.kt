package app.trailveil.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogRevealUpdate
import app.trailveil.map.fog.FogRuntime
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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
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
 * Provider failure is deliberately contained inside this surface. It never owns recording,
 * location permissions, canonical points, or fog state.
 */
@Composable
internal fun TrailVeilMapSurface(
    modifier: Modifier = Modifier,
    provider: MapProviderConfiguration = ProductionMapProvider,
    fallbackTimeoutMillis: Long = 5_000L,
    savedStateKey: String = "trailveil.map.primary",
    fogRuntime: FogRuntime? = null,
    fogRequired: Boolean = false,
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
    val mapView = remember(context, lifecycle, savedStateRegistry, savedStateKey) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(restoredMapState ?: Bundle()) }
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
    var fogCoverageInstalled by remember(mapView, fogRuntime, fogRequired) {
        mutableStateOf(!fogRequired)
    }
    var canonicalFogLoaded by remember(mapView, fogRuntime, fogRequired) {
        mutableStateOf(!fogRequired)
    }
    var fogRenderFailed by remember(mapView, fogRuntime) { mutableStateOf(false) }
    var fogBaselineReady by remember(mapView, fogRuntime) {
        mutableStateOf(fogRuntime == null)
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

    LaunchedEffect(fogRuntime) {
        val runtime = fogRuntime ?: return@LaunchedEffect
        fogBaselineReady = false
        var cursor = establishFogBaselineWithRetry(
            retryDelayMillis = FOG_RETRY_DELAY_MILLIS,
            latestCursor = {
                withContext(Dispatchers.IO) { runtime.pointChanges.latestCursor() }
            },
            clearDerivedCache = {
                withContext(Dispatchers.Default) {
                    // Derived masks have no persistent data revision; a process baseline therefore
                    // starts from canonical Room instead of a possibly stale prior-process cache.
                    runtime.viewportCoordinator.clearDerivedCache()
                }
            },
            onFailure = currentOnFogFailure,
        )
        fogBaselineReady = true
        fogRevision += 1L

        while (true) {
            try {
                runtime.pointChanges.revisionsAfter(cursor).collect { revision ->
                    val changes = withContext(Dispatchers.IO) {
                        runtime.pointChanges.readChangesAfter(cursor)
                    }
                    if (changes.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            runtime.viewportCoordinator.mergePersistedReveals(
                                changes.map(PersistedTrackPointChange::toFogRevealUpdate),
                            )
                        }
                        cursor = changes.last().point.let { point ->
                            app.trailveil.data.map.PersistedPointCursor(point.pointId)
                        }
                        fogRevision += 1L
                    } else {
                        cursor = revision.latestCursor
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
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
            fun requestViewport() {
                val request = map.fogViewportRequest()
                fogCoverageInstalled = false
                canonicalFogLoaded = false
                fogRenderFailed = false
                fogPlaceholderReadyGeneration = -1L
                fogViewportRequest = request
                fogViewportGeneration += 1L
            }

            val idleListener = MapLibreMap.OnCameraIdleListener(::requestViewport)
            val moveStartedListener = MapLibreMap.OnCameraMoveStartedListener {
                // The previous mosaic has finite bounds. Hide it during camera motion so a fast
                // pan cannot expose unknown map outside those bounds before the idle rebuild.
                fogCoverageInstalled = false
                canonicalFogLoaded = false
                fogRenderFailed = false
                fogPlaceholderReadyGeneration = -1L
                fogViewportRequest = null
                fogViewportGeneration += 1L
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
            mapView.installFogMosaicAndAwait(style, placeholder.mosaic)
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
                mapView.installFogMosaicAndAwait(style, viewport.mosaic)
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
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(MapSurfaceTestTags.FogSafetyCover),
                color = Color.Black.copy(alpha = 0.72f),
            ) {}
        }
        val statusText = when {
            loadState == BasemapLoadState.LOADING -> stringResource(R.string.map_loading)
            loadState == BasemapLoadState.LOCAL_FALLBACK ->
                stringResource(R.string.map_unavailable)
            fogRenderFailed -> stringResource(R.string.map_fog_unavailable)
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

private fun Style.installFogMosaic(mosaic: FogTileMosaic) {
    val bounds = mosaic.bounds
    val coordinates = LatLngQuad(
        LatLng(bounds.northLatitude, bounds.westLongitude),
        LatLng(bounds.northLatitude, bounds.eastLongitude),
        LatLng(bounds.southLatitude, bounds.eastLongitude),
        LatLng(bounds.southLatitude, bounds.westLongitude),
    )
    val bitmap = mosaic.mask.toBitmap()
    val source = getSourceAs<ImageSource>(FogOverlayIds.Source)
    if (source == null) {
        addSource(ImageSource(FogOverlayIds.Source, coordinates, bitmap))
    } else {
        source.setCoordinates(coordinates)
        source.setImage(bitmap)
    }
    if (getLayer(FogOverlayIds.Layer) == null) {
        addLayer(
            RasterLayer(FogOverlayIds.Layer, FogOverlayIds.Source).withProperties(
                PropertyFactory.rasterFadeDuration(0f),
                PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
            ),
        )
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

internal suspend fun establishFogBaselineWithRetry(
    retryDelayMillis: Long,
    latestCursor: suspend () -> PersistedPointCursor,
    clearDerivedCache: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
): PersistedPointCursor = retryFogOperation(retryDelayMillis, onFailure) {
    latestCursor().also { clearDerivedCache() }
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

private suspend fun MapView.installFogMosaicAndAwait(style: Style, mosaic: FogTileMosaic) {
    val rendered = withTimeoutOrNull(FOG_FRAME_TIMEOUT_MILLIS) {
        awaitFullyRenderedFrameAfter { style.installFogMosaic(mosaic) }
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

private fun PersistedTrackPointChange.toFogRevealUpdate(): FogRevealUpdate =
    FogRevealUpdate(
        current = GeoPoint(point.latitude, point.longitude),
        previousInSegment = previousPoint?.let { previous ->
            GeoPoint(previous.latitude, previous.longitude)
        },
    )

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
