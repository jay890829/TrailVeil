package app.trailveil.map

import android.os.Looper
import app.trailveil.BuildConfig
import app.trailveil.map.fog.FogProbeExclusionZone
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView

/** Stage-5 listener ownership and SDK UI hardening; fog ports join this binding in stage 6. */
internal class GoogleMapSurfaceBinding(
    private val map: GoogleMap,
    mapView: MapView,
    density: Float,
    private val onUserMovedCamera: () -> Unit,
    private val onMapLoaded: () -> Unit,
    private val onCameraMoveStarted: (Int) -> Unit = {},
    private val onCameraMoveFrame: () -> Unit = {},
    private val onCameraIdle: () -> Unit = {},
    private val onCameraMoveCancelled: () -> Unit = {},
    private val onOverlayVisibilityForTesting: ((Boolean) -> Unit)? = null,
    private val onOverlayObservationForTesting: ((GoogleMapOverlayObservation) -> Unit)? = null,
    /** Detail surfaces register their callback before this binding is constructed. */
    private val installMapLoadedListener: Boolean = true,
) {
    private val overlays = GoogleMapOverlays(
        map = map,
        mapView = mapView,
        density = density,
        onVisibilityChanged = onOverlayVisibilityForTesting,
        onObservationChanged = onOverlayObservationForTesting,
    )

    init {
        assertMainThread()
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        map.isIndoorEnabled = false
        map.isBuildingsEnabled = false
        map.uiSettings.apply {
            isMapToolbarEnabled = false
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
            isCompassEnabled = true
            isScrollGesturesEnabled = true
            isZoomGesturesEnabled = true
            isTiltGesturesEnabled = true
            isRotateGesturesEnabled = true
        }
        map.setOnMarkerClickListener { true }
        map.setOnCameraMoveStartedListener { reason ->
            assertMainThread()
            onCameraMoveStarted(reason)
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                onUserMovedCamera()
            }
        }
        map.setOnCameraMoveListener {
            assertMainThread()
            onCameraMoveFrame()
        }
        map.setOnCameraIdleListener {
            assertMainThread()
            onCameraIdle()
        }
        map.setOnCameraMoveCanceledListener {
            assertMainThread()
            onCameraMoveCancelled()
        }
        if (installMapLoadedListener) {
            map.setOnMapLoadedCallback {
                assertMainThread()
                onMapLoaded()
            }
        }
    }

    fun release() {
        assertMainThread()
        overlays.release()
        map.setOnMarkerClickListener(null)
        map.setOnCameraMoveStartedListener(null)
        map.setOnCameraMoveListener(null)
        map.setOnCameraIdleListener(null)
        map.setOnCameraMoveCanceledListener(null)
        if (installMapLoadedListener) map.setOnMapLoadedCallback(null)
    }

    /** Updates marker/track geometry without weakening the proof visibility gate. */
    fun updateOverlays(
        currentLocation: GeoPoint?,
        trackOverlay: MapTrackOverlay?,
    ): Boolean {
        assertMainThread()
        return overlays.update(currentLocation, trackOverlay)
    }

    /** Hides overlays while a generation is pending or the synchronous cover is raised. */
    fun hideOverlaysUntilProof(): Boolean {
        assertMainThread()
        return overlays.hideUntilProof()
    }

    /** Reveals overlays only after a matching installed generation has passed screen proof. */
    fun revealOverlaysForGeneration(generation: Long) {
        assertMainThread()
        overlays.revealForGeneration(generation)
    }

    /** Detail maps intentionally have no fog and preserve the existing visible track behavior. */
    fun showOverlaysWithoutFogProof() {
        assertMainThread()
        overlays.showWithoutFogProof()
    }

    /** Current overlay footprints, already expanded for the strong 3x3 proof sample. */
    fun exclusionZonesForProof(): List<FogProbeExclusionZone> {
        assertMainThread()
        return overlays.exclusionZonesForProof()
    }

    private fun assertMainThread() {
        if (BuildConfig.DEBUG) {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "map surface binding must run on the main thread"
            }
        }
    }
}
