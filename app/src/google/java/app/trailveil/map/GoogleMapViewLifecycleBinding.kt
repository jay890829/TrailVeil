package app.trailveil.map

import androidx.lifecycle.Lifecycle
import com.google.android.gms.maps.MapView

/** The small lifecycle surface needed by [GoogleMapViewLifecycleBinding]. */
internal interface GoogleMapViewLifecycleCallbacks {
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
}

private class MapViewLifecycleCallbacks(
    private val mapView: MapView,
) : GoogleMapViewLifecycleCallbacks {
    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()
    override fun onDestroy() = mapView.onDestroy()
}

/** Idempotent lifecycle forwarding for one composition-owned map view. */
internal class GoogleMapViewLifecycleBinding(
    private val mapViewLifecycle: GoogleMapViewLifecycleCallbacks,
    private val onHostStarted: () -> Unit = {},
    private val onHostStopped: () -> Unit = {},
) {
    internal constructor(
        mapView: MapView,
        onHostStarted: () -> Unit = {},
        onHostStopped: () -> Unit = {},
    ) : this(
        mapViewLifecycle = MapViewLifecycleCallbacks(mapView),
        onHostStarted = onHostStarted,
        onHostStopped = onHostStopped,
    )

    private var started = false
    private var resumed = false
    private var destroyed = false
    private var stopping = false
    private var releaseRequested = false

    /** Read by the saved-state provider so a save after destroy can name itself. */
    val isDestroyed: Boolean get() = destroyed
    val isStarted: Boolean get() = started

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
        if (stopping) {
            // A host callback may synchronously request disposal while the SDK is still inside
            // onStop(). Defer destruction until the SDK stop has returned, preserving the normal
            // stop-before-destroy ordering without allowing a second stop to re-enter.
            releaseRequested = true
            return
        }
        pause()
        stop()
        destroyMapView()
    }

    private fun start() {
        if (!started && !destroyed && !stopping) {
            mapViewLifecycle.onStart()
            started = true
            onHostStarted()
        }
    }

    private fun resume() {
        if (!resumed && !destroyed && !stopping) {
            start()
            mapViewLifecycle.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (resumed && !destroyed) {
            mapViewLifecycle.onPause()
            resumed = false
        }
    }

    private fun stop() {
        if (started && !destroyed && !stopping) {
            stopping = true
            pause()
            started = false
            try {
                // The fog binding owns bounded deadlines that a stopped renderer can never
                // satisfy, so invalidate its callback/token before the SDK stop. The SDK does
                // not guarantee that callbacks cannot be inline/reentrant during onStop().
                try {
                    onHostStopped()
                } finally {
                    mapViewLifecycle.onStop()
                }
            } finally {
                stopping = false
                if (releaseRequested) {
                    releaseRequested = false
                    release()
                }
            }
        }
    }

    private fun destroyMapView() {
        if (destroyed) return
        mapViewLifecycle.onDestroy()
        destroyed = true
    }
}
