package app.trailveil.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

internal enum class BasemapLoadState {
    LOADING,
    ONLINE,
    LOCAL_FALLBACK,
}

internal object MapSurfaceTestTags {
    const val Map = "trailveil_map"
    const val Status = "trailveil_map_status"
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
    var fallbackRequested by remember(mapView, provider) { mutableStateOf(false) }
    val compositionActive = remember(mapView) { AtomicBoolean(true) }
    val styleGenerationActive = remember(mapView, provider) { AtomicBoolean(true) }

    fun useLocalFallback() {
        val map = readyMap ?: return
        if (!compositionActive.get() || !styleGenerationActive.get()) return
        if (fallbackRequested) return
        fallbackRequested = true
        map.setStyle(Style.Builder().fromJson(fallbackStyleJson)) {
            if (compositionActive.get() && styleGenerationActive.get()) {
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
            map.setStyle(Style.Builder().fromUri(provider.styleUri)) {
                if (
                    compositionActive.get() &&
                    styleGenerationActive.get() &&
                    !fallbackRequested
                ) {
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

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .semantics { this.contentDescription = contentDescription }
                .testTag(MapSurfaceTestTags.Map),
        )
        when (loadState) {
            BasemapLoadState.LOADING -> MapStatusBadge(
                text = stringResource(R.string.map_loading),
            )
            BasemapLoadState.LOCAL_FALLBACK -> MapStatusBadge(
                text = stringResource(R.string.map_unavailable),
            )
            BasemapLoadState.ONLINE -> Unit
        }
    }
}

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
