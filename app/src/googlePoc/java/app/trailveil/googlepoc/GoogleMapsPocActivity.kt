package app.trailveil.googlepoc

import app.trailveil.BuildConfig
import app.trailveil.R
import app.trailveil.map.ProviderFallbackReason
import app.trailveil.map.ProviderRuntimeGate
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PointOfInterest

/** Camera state deliberately contains no TrailVeil recording or history state. */
data class GoogleMapsPocCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Float,
    val bearing: Float,
    val tilt: Float,
)

/** The PoC exposes only the standard SDK POI fields and performs no Places lookup. */
data class GoogleMapsPocPointOfInterest(
    val name: String,
    val placeId: String,
    val latLng: LatLng,
)

interface GoogleMapsPocCallbacks {
    fun onCameraChanged(camera: GoogleMapsPocCamera) = Unit
    fun onCameraIdle(camera: GoogleMapsPocCamera) = Unit
    fun onCameraMoveStarted(reason: Int) = Unit
    fun onMapClick(latitude: Double, longitude: Double) = Unit
    fun onMapLongClick(latitude: Double, longitude: Double) = Unit
    fun onPointOfInterestClick(pointOfInterest: GoogleMapsPocPointOfInterest) = Unit
}

/**
 * Non-release Google Maps provider surface. This Activity intentionally does not construct the
 * recording/history graph; all failure paths therefore stay local to this screen.
 */
class GoogleMapsPocActivity : ComponentActivity(), OnMapReadyCallback {
    /** Assign before the map loads to observe camera, gesture and basic POI callbacks. */
    var callbacks: GoogleMapsPocCallbacks? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var fallback: TextView
    private lateinit var callbackStatus: TextView
    private var mapView: MapView? = null
    private var mapLoaded = false
    private var terminalFallback: ProviderFallbackReason? = null

    private val mapLoadTimeout = Runnable {
        if (!mapLoaded) {
            showFallback(ProviderFallbackReason.MAP_LOAD_TIMEOUT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createLocalSurface()

        val decision = ProviderRuntimeGate.startupDecision(
            keyConfigured = BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
            keyReason = BuildConfig.GOOGLE_MAPS_POC_KEY_REASON,
            hasValidatedNetwork = hasValidatedInternet(this),
            hasCompatibleServices = hasCompatiblePlayServices(this),
        )
        if (!decision.initializeMap) {
            showFallback(requireNotNull(decision.fallbackReason))
            return
        }

        try {
            val candidate = MapView(this)
            mapView = candidate
            root.addView(
                candidate,
                0,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            candidate.onCreate(savedInstanceState)
            candidate.getMapAsync(this)
            mainHandler.postDelayed(mapLoadTimeout, MAP_LOAD_TIMEOUT_MILLIS)
        } catch (_: Exception) {
            showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
        } catch (_: LinkageError) {
            showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
        }
    }

    override fun onStart() {
        super.onStart()
        forwardLifecycleCall { onStart() }
    }

    override fun onResume() {
        super.onResume()
        forwardLifecycleCall { onResume() }
    }

    override fun onPause() {
        forwardLifecycleCall { onPause() }
        super.onPause()
    }

    override fun onStop() {
        forwardLifecycleCall { onStop() }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        forwardLifecycleCall { onSaveInstanceState(outState) }
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        forwardLifecycleCall { onLowMemory() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        forwardLifecycleCall { onLowMemory() }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(mapLoadTimeout)
        disposeMapView()
        super.onDestroy()
    }

    override fun onMapReady(map: GoogleMap) {
        if (terminalFallback != null) return
        map.setOnCameraMoveListener {
            dispatchCameraChanged(map.cameraPosition.toPocCamera())
        }
        map.setOnCameraIdleListener {
            dispatchCameraIdle(map.cameraPosition.toPocCamera())
        }
        map.setOnCameraMoveStartedListener { reason ->
            callbackStatus.text = getString(R.string.google_poc_camera_gesture_started, reason)
            callbacks?.onCameraMoveStarted(reason)
        }
        map.setOnMapClickListener { point ->
            callbackStatus.text = getString(
                R.string.google_poc_map_click,
                point.latitude,
                point.longitude,
            )
            callbacks?.onMapClick(point.latitude, point.longitude)
        }
        map.setOnMapLongClickListener { point ->
            callbackStatus.text = getString(
                R.string.google_poc_map_long_click,
                point.latitude,
                point.longitude,
            )
            callbacks?.onMapLongClick(point.latitude, point.longitude)
        }
        map.setOnPoiClickListener { pointOfInterest ->
            dispatchPointOfInterestClick(pointOfInterest.toPocPointOfInterest())
        }
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(POC_START, POC_START_ZOOM))
        map.setOnMapLoadedCallback {
            if (terminalFallback != null) return@setOnMapLoadedCallback
            mapLoaded = true
            mainHandler.removeCallbacks(mapLoadTimeout)
            mapView?.visibility = View.VISIBLE
            fallback.visibility = View.GONE
            callbackStatus.visibility = View.VISIBLE
            callbackStatus.setText(R.string.google_poc_map_loaded)
            root.bringChildToFront(callbackStatus)
        }
    }

    private fun createLocalSurface() {
        root = FrameLayout(this).apply {
            setBackgroundColor(FOG_COLOR)
        }
        fallback = TextView(this).apply {
            tag = FALLBACK_TAG
            contentDescription = FALLBACK_TAG
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(FOG_COLOR)
            setPadding(FALLBACK_HORIZONTAL_PADDING, FALLBACK_VERTICAL_PADDING, FALLBACK_HORIZONTAL_PADDING, FALLBACK_VERTICAL_PADDING)
            setText(R.string.google_poc_loading)
        }
        root.addView(
            fallback,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        callbackStatus = TextView(this).apply {
            tag = CALLBACK_STATUS_TAG
            setTextColor(Color.WHITE)
            setBackgroundColor(STATUS_COLOR)
            setPadding(STATUS_HORIZONTAL_PADDING, STATUS_VERTICAL_PADDING, STATUS_HORIZONTAL_PADDING, STATUS_VERTICAL_PADDING)
            visibility = View.GONE
        }
        root.addView(
            callbackStatus,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
        setContentView(root)
    }

    private fun showFallback(reason: ProviderFallbackReason) {
        terminalFallback = reason
        mapLoaded = false
        mainHandler.removeCallbacks(mapLoadTimeout)
        disposeMapView()
        fallback.visibility = View.VISIBLE
        callbackStatus.visibility = View.GONE
        fallback.text = buildFallbackText(reason)
        root.bringChildToFront(fallback)
    }

    private fun forwardLifecycleCall(action: MapView.() -> Unit) {
        val view = mapView ?: return
        try {
            view.action()
        } catch (_: Exception) {
            showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
        } catch (_: LinkageError) {
            showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
        }
    }

    private fun disposeMapView() {
        val view = mapView ?: return
        mapView = null
        view.visibility = View.INVISIBLE
        try {
            view.onDestroy()
        } catch (_: Exception) {
            // A terminal local fallback must survive provider cleanup failure.
        } catch (_: LinkageError) {
            // A terminal local fallback must survive provider cleanup failure.
        }
        root.removeView(view)
    }

    private fun dispatchCameraChanged(camera: GoogleMapsPocCamera) {
        callbackStatus.text = getString(
            R.string.google_poc_camera_changed,
            camera.latitude,
            camera.longitude,
            camera.zoom,
        )
        callbacks?.onCameraChanged(camera)
    }

    private fun dispatchCameraIdle(camera: GoogleMapsPocCamera) {
        callbackStatus.text = getString(
            R.string.google_poc_camera_idle,
            camera.latitude,
            camera.longitude,
            camera.zoom,
        )
        callbacks?.onCameraIdle(camera)
    }

    private fun dispatchPointOfInterestClick(pointOfInterest: GoogleMapsPocPointOfInterest) {
        callbackStatus.text = getString(
            R.string.google_poc_poi_click,
            pointOfInterest.name,
            pointOfInterest.placeId,
            pointOfInterest.latLng.latitude,
            pointOfInterest.latLng.longitude,
        )
        callbacks?.onPointOfInterestClick(pointOfInterest)
    }

    private fun buildFallbackText(reason: ProviderFallbackReason): String {
        val reasonText = getString(reason.messageResource())
        val guidance = BuildConfig.GOOGLE_MAPS_POC_KEY_GUIDANCE
        return if (guidance.isBlank()) {
            getString(R.string.google_poc_fallback_without_guidance, reasonText)
        } else {
            getString(R.string.google_poc_fallback_with_guidance, reasonText, guidance)
        }
    }

    private fun hasCompatiblePlayServices(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private fun hasValidatedInternet(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val MAP_LOAD_TIMEOUT_MILLIS = 30_000L
        val POC_START = LatLng(25.033_964, 121.564_468)
        const val POC_START_ZOOM = 16F
        const val FALLBACK_TAG = "trailveil_google_poc_fallback"
        const val CALLBACK_STATUS_TAG = "trailveil_google_poc_callback_status"
        const val FALLBACK_HORIZONTAL_PADDING = 48
        const val FALLBACK_VERTICAL_PADDING = 32
        val FOG_COLOR: Int = Color.rgb(31, 38, 43)
        val STATUS_COLOR: Int = Color.argb(220, 31, 38, 43)
        const val STATUS_HORIZONTAL_PADDING = 24
        const val STATUS_VERTICAL_PADDING = 16
    }
}

@StringRes
private fun ProviderFallbackReason.messageResource(): Int = when (this) {
    ProviderFallbackReason.MISSING_KEY -> R.string.google_poc_reason_missing_key
    ProviderFallbackReason.STRUCTURALLY_INVALID_KEY -> R.string.google_poc_reason_invalid_key
    ProviderFallbackReason.NO_VALIDATED_NETWORK -> R.string.google_poc_reason_no_network
    ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE -> R.string.google_poc_reason_no_play_services
    ProviderFallbackReason.INITIALIZATION_FAILURE -> R.string.google_poc_reason_initialization_failure
    ProviderFallbackReason.MAP_LOAD_TIMEOUT -> R.string.google_poc_reason_load_timeout
}

private fun CameraPosition.toPocCamera(): GoogleMapsPocCamera = GoogleMapsPocCamera(
    latitude = target.latitude,
    longitude = target.longitude,
    zoom = zoom,
    bearing = bearing,
    tilt = tilt,
)

private fun PointOfInterest.toPocPointOfInterest(): GoogleMapsPocPointOfInterest =
    GoogleMapsPocPointOfInterest(
        name = name,
        placeId = placeId,
        latLng = latLng,
    )
