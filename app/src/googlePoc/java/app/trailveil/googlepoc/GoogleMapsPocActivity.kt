package app.trailveil.googlepoc

import app.trailveil.BuildConfig
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.map.ProviderFallbackReason
import app.trailveil.map.ProviderRuntimeGate
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogGenerationReusePolicy
import app.trailveil.map.fog.FogSnapshotVisualProbePlan
import app.trailveil.map.fog.FogSynchronizationRenderDecision
import app.trailveil.map.fog.FogSnapshotVisualProbePlanner
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.FogTileGeneration
import app.trailveil.map.fog.FogTileProviderAdapter
import app.trailveil.map.fog.FogViewportBatchCoverageRenderer
import app.trailveil.map.fog.FogViewportBatchSubrenderer
import app.trailveil.map.fog.FogViewportCoverageRequest
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.GoogleFogLifecycleGate
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlin.math.floor

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

enum class GoogleFogInstallPhase {
    RUNTIME,
    SYNCHRONIZATION,
    RENDER,
    TILE_DELIVERY,
    SNAPSHOT,
    REFRESH_REJECTED,
    INSTALLED,
}

data class GoogleFogInstallDiagnostic(
    val phase: GoogleFogInstallPhase,
    val pendingTileCount: Int?,
    val refreshFailure: GoogleFogRefreshFailure?,
    val clearFailureClass: String?,
    val refreshGeneration: Long?,
    val refreshStarted: Boolean,
    val refreshPublished: Boolean,
    val visualRequiredTileCount: Int,
    val visualVerifiedTileCount: Int,
    val snapshotAttempt: Int,
    val visualOffScreenOnlyTileCount: Int,
    val visualMismatchedTileCount: Int,
    val visualMinimumOnScreenProbeCount: Int,
)

/** `V02-005` stage 3 (SP7): coordinate-free saved-state harness facts. */
data class GoogleSavedStateDiagnostic(
    val mode: String?,
    val restoredMapStatePresent: Boolean,
    val providerTagMatched: Boolean?,
    val initialCameraMoveSkipped: Boolean,
    val parcelRoundTripBytes: Int?,
    val parcelFailureClass: String?,
)

interface GoogleMapsPocCallbacks {
    fun onCameraChanged(camera: GoogleMapsPocCamera) = Unit
    fun onCameraIdle(camera: GoogleMapsPocCamera) = Unit
    fun onCameraMoveStarted(reason: Int) = Unit
    fun onMapClick(latitude: Double, longitude: Double) = Unit
    fun onMapLongClick(latitude: Double, longitude: Double) = Unit
    fun onPointOfInterestClick(pointOfInterest: GoogleMapsPocPointOfInterest) = Unit
    /** Coordinate-free test/diagnostic signal emitted only after canonical fog is installed. */
    fun onCanonicalFogInstalled(generation: Long) = Unit
}

/**
 * Non-release Google Maps provider surface. The screen reads the shared canonical fog runtime but
 * intentionally does not construct a second recording/history graph; all provider failures stay
 * local to this screen.
 */
class GoogleMapsPocActivity : ComponentActivity(), OnMapReadyCallback {
    /** Assign before the map loads to observe camera, gesture and basic POI callbacks. */
    var callbacks: GoogleMapsPocCallbacks? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var fallback: TextView
    private lateinit var callbackStatus: TextView
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var mapLoaded = false
    private var terminalFallback: ProviderFallbackReason? = null
    private val lifecycleGate = GoogleFogLifecycleGate()
    private val fogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fogRuntime: FogRuntime? = null
    private var fogRuntimeLoadStarted = false
    private var fogRuntimeLoadJob: Job? = null
    private var fogSyncJob: Job? = null
    private val fogSyncPolicy = app.trailveil.map.fog.FogSynchronizationRenderPolicy()
    private val fogVisualProbePlanner = FogSnapshotVisualProbePlanner()
    private var fogCoverageRenderer: FogViewportBatchCoverageRenderer? = null
    private var fogAdapter: FogTileProviderAdapter? = null
    private var fogOverlay: GoogleFogTileOverlayController? = null
    private var fogGeneration: FogTileGeneration? = null
    private var fogRenderJob: Job? = null
    private var canonicalFogInstalledGeneration: Long? = null
    private var canonicalFogPublishedGeneration: Long? = null
    private var fogInstallTimeout: Runnable? = null
    private var fogInstallPhase = GoogleFogInstallPhase.RUNTIME
    private var failedFogInstallDiagnostic: GoogleFogInstallDiagnostic? = null

    // `V02-005` stage 3 spike seams. All default-off: an ordinary launch (no saved-state-mode
    // extra, no *ForTesting call) behaves byte-identically to the pre-spike Activity.
    @Volatile
    private var statusOverlaySuppressed = false

    /** SP5: when true, gesture-driven generation begins do NOT raise the opaque cover. */
    @Volatile
    var gestureCoverSuppressedForTesting: Boolean = false
    private var syncMarker: View? = null
    private var fogProvider: GoogleFogTileProvider? = null
    private var lastInstalledVisualProbePlan: FogSnapshotVisualProbePlan? = null
    private var savedStateMode: String? = null
    private var restoredMapStateBundle: Bundle? = null
    private var restoredMapStatePresent = false
    private var providerTagMatched: Boolean? = null
    private var initialCameraMoveSkipped = false
    private var parcelRoundTripBytes: Int? = null
    private var parcelFailureClass: String? = null

    private val mapLoadTimeout = Runnable {
        if (lifecycleGate.callbacksAllowed() && !mapLoaded) {
            showFallback(ProviderFallbackReason.MAP_LOAD_TIMEOUT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createLocalSurface()

        // `V02-005` stage 3 (SP7): opt-in saved-state arms. Extra absent => the historical plain
        // forwarding path, byte-identical behavior. The nested arms exercise the design's §6
        // per-key SavedStateRegistry shape: consume-once on create, provider registered for save.
        savedStateMode = intent.getStringExtra(EXTRA_SAVED_STATE_MODE)
        val mode = savedStateMode
        if (mode == SAVED_STATE_MODE_NESTED || mode == SAVED_STATE_MODE_NESTED_PARCELED) {
            val wrapped = savedStateRegistry.consumeRestoredStateForKey(NESTED_MAP_STATE_KEY)
            if (wrapped != null) {
                val tagMatched = wrapped.getString(NESTED_PROVIDER_KEY) == NESTED_PROVIDER_VALUE
                providerTagMatched = tagMatched
                if (tagMatched) {
                    val inner = wrapped.getBundle(NESTED_STATE_KEY)
                    restoredMapStateBundle = if (mode == SAVED_STATE_MODE_NESTED_PARCELED) {
                        inner?.let(::parcelRoundTrip)
                    } else {
                        inner
                    }
                }
                // A mismatched or untagged entry is discarded: the map starts clean (§6's
                // foreign-provider discard) and providerTagMatched records the fact.
            }
            restoredMapStatePresent = restoredMapStateBundle != null
            savedStateRegistry.registerSavedStateProvider(
                NESTED_MAP_STATE_KEY,
                androidx.savedstate.SavedStateRegistry.SavedStateProvider {
                    Bundle().apply {
                        putString(NESTED_PROVIDER_KEY, NESTED_PROVIDER_VALUE)
                        putBundle(
                            NESTED_STATE_KEY,
                            Bundle().also { inner -> mapView?.onSaveInstanceState(inner) },
                        )
                    }
                },
            )
        } else {
            restoredMapStatePresent = savedInstanceState != null
        }

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
            val mapCreationState =
                if (mode == SAVED_STATE_MODE_NESTED || mode == SAVED_STATE_MODE_NESTED_PARCELED) {
                    restoredMapStateBundle
                } else {
                    savedInstanceState
                }
            candidate.onCreate(mapCreationState)
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
        // SP7 nested arms save through the registry provider registered in onCreate instead of
        // the top-level Activity bundle; plain/absent modes keep the historical forwarding.
        if (
            savedStateMode != SAVED_STATE_MODE_NESTED &&
            savedStateMode != SAVED_STATE_MODE_NESTED_PARCELED
        ) {
            forwardLifecycleCall { onSaveInstanceState(outState) }
        }
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
        lifecycleGate.markDestroyed()
        mainHandler.removeCallbacks(mapLoadTimeout)
        cancelFogInstallTimeout()
        fogRuntimeLoadJob?.cancel()
        fogRuntimeLoadJob = null
        fogSyncJob?.cancel()
        fogSyncJob = null
        fogRenderJob?.cancel()
        fogRenderJob = null
        fogGeneration?.cancel()
        fogGeneration = null
        canonicalFogPublishedGeneration = null
        lastInstalledVisualProbePlan = null
        fogOverlay?.detach()
        fogOverlay = null
        fogAdapter = null
        fogProvider = null
        fogRuntime = null
        fogCoverageRenderer = null
        fogSyncPolicy.reset()
        fogScope.cancel()
        googleMap = null
        if (savedStateMode != null) {
            savedStateRegistry.unregisterSavedStateProvider(NESTED_MAP_STATE_KEY)
        }
        disposeMapView()
        super.onDestroy()
    }

    override fun onMapReady(map: GoogleMap) {
        val lease = lifecycleGate.acquire() ?: return
        if (!installFogOverlay(map, lease)) {
            if (lifecycleGate.isCurrent(lease)) {
                showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
            }
            return
        }
        if (!lifecycleGate.isCurrent(lease)) {
            fogOverlay?.detach()
            fogOverlay = null
            fogAdapter = null
            return
        }
        googleMap = map
        try {
            map.setOnCameraMoveListener {
                if (!lifecycleGate.isCurrent(lease)) return@setOnCameraMoveListener
                dispatchCameraChanged(map.cameraPosition.toPocCamera())
            }
            map.setOnCameraIdleListener {
                if (!lifecycleGate.isCurrent(lease)) return@setOnCameraIdleListener
                val camera = map.cameraPosition
                dispatchCameraIdle(camera.toPocCamera())
                requestCanonicalFog(camera, lease)
            }
            map.setOnCameraMoveStartedListener { reason ->
                if (!lifecycleGate.isCurrent(lease)) return@setOnCameraMoveStartedListener
                beginFogGeneration(lease)
                if (!lifecycleGate.isCurrent(lease)) return@setOnCameraMoveStartedListener
                callbackStatus.text = getString(R.string.google_poc_camera_gesture_started, reason)
                callbacks?.onCameraMoveStarted(reason)
            }
            map.setOnMapClickListener { point ->
                if (!lifecycleGate.isCurrent(lease)) return@setOnMapClickListener
                callbackStatus.text = getString(
                    R.string.google_poc_map_click,
                    point.latitude,
                    point.longitude,
                )
                callbacks?.onMapClick(point.latitude, point.longitude)
            }
            map.setOnMapLongClickListener { point ->
                if (!lifecycleGate.isCurrent(lease)) return@setOnMapLongClickListener
                callbackStatus.text = getString(
                    R.string.google_poc_map_long_click,
                    point.latitude,
                    point.longitude,
                )
                callbacks?.onMapLongClick(point.latitude, point.longitude)
            }
            map.setOnPoiClickListener { pointOfInterest ->
                if (!lifecycleGate.isCurrent(lease)) return@setOnPoiClickListener
                dispatchPointOfInterestClick(pointOfInterest.toPocPointOfInterest())
            }
            map.setOnMapLoadedCallback {
                if (!lifecycleGate.isCurrent(lease)) return@setOnMapLoadedCallback
                mapLoaded = true
                mainHandler.removeCallbacks(mapLoadTimeout)
                callbackStatus.setText(R.string.google_poc_map_loaded)
                revealMapWhenCanonicalFogIsReady()
            }
            if (savedStateMode != null && restoredMapStatePresent) {
                // SP7: mirrors §6's "no initial camera on restore" deletion. Without this the
                // unconditional POC_START move clobbers whatever the MapView restored.
                initialCameraMoveSkipped = true
            } else {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(POC_START, POC_START_ZOOM))
            }
            initializeCanonicalFogRuntime(lease)
            // The first canonical render is intentionally requested only by the completed baseline.
        } catch (_: Exception) {
            if (lifecycleGate.isCurrent(lease)) {
                showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
            }
        } catch (_: LinkageError) {
            if (lifecycleGate.isCurrent(lease)) {
                showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
            }
        }
    }

    private fun installFogOverlay(
        map: GoogleMap,
        lease: GoogleFogLifecycleGate.Lease,
    ): Boolean {
        if (!lifecycleGate.isCurrent(lease)) return false
        val adapter = FogTileProviderAdapter()
        val provider = GoogleFogTileProvider(adapter)
        val controller = GoogleFogTileOverlayController(
            map = map,
            provider = provider,
        )
        if (controller.attach() == null) {
            controller.detach()
            return false
        }
        if (!lifecycleGate.isCurrent(lease)) {
            controller.detach()
            return false
        }
        fogAdapter = adapter
        fogProvider = provider
        fogOverlay = controller
        return true
    }

    private fun initializeCanonicalFogRuntime(lease: GoogleFogLifecycleGate.Lease) {
        if (!lifecycleGate.isCurrent(lease) || fogRuntimeLoadStarted) return
        fogRuntimeLoadStarted = true
        fogInstallPhase = GoogleFogInstallPhase.RUNTIME
        val application = application as? TrailVeilApplication
        if (application == null) {
            showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
            return
        }
        fogRuntimeLoadJob = fogScope.launch {
            try {
                val runtime = withTimeout(FOG_RUNTIME_TIMEOUT_MILLIS) {
                    withContext(Dispatchers.IO) {
                        application.appContainer.fogRuntime()
                    }
                }
                ensureActive()
                if (!lifecycleGate.isCurrent(lease)) return@launch
                fogRuntimeLoadJob = null
                fogRuntime = runtime
                fogCoverageRenderer = FogViewportBatchCoverageRenderer(
                    subrenderer = FogViewportBatchSubrenderer { request, keys ->
                        runtime.viewportCoordinator.renderTiles(request, keys)
                    },
                    maxTiles = MAX_VIEWPORT_FOG_TILES,
                )
                startCanonicalFogSynchronization(runtime, lease)
            } catch (_: TimeoutCancellationException) {
                if (lifecycleGate.isCurrent(lease)) {
                    showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (lifecycleGate.isCurrent(lease)) {
                    showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                }
            }
        }
    }

    private fun startCanonicalFogSynchronization(
        runtime: FogRuntime,
        lease: GoogleFogLifecycleGate.Lease,
    ) {
        fogSyncJob?.cancel()
        fogSyncPolicy.reset()
        fogInstallPhase = GoogleFogInstallPhase.SYNCHRONIZATION
        fogSyncJob = fogScope.launch {
            try {
                val baseline = withTimeout(FOG_SYNC_TIMEOUT_MILLIS) {
                    withContext(Dispatchers.IO) {
                        runtime.changeSynchronizer.synchronizeTo()
                    }
                }
                ensureActive()
                if (!lifecycleGate.isCurrent(lease)) return@launch
                when (fogSyncPolicy.onBaselineSynchronized(baseline)) {
                    FogSynchronizationRenderDecision.RENDER_CURRENT_CAMERA -> {
                        googleMap?.let { map -> requestCanonicalFog(map.cameraPosition, lease) }
                    }
                    else -> Unit
                }
                runtime.pointChanges.revisionsAfter(baseline.cursor).collect { revision ->
                    ensureActive()
                    val synchronization = withTimeout(FOG_SYNC_TIMEOUT_MILLIS) {
                        withContext(Dispatchers.IO) {
                            runtime.changeSynchronizer.synchronizeTo(revision.latestCursor)
                        }
                    }
                    ensureActive()
                    if (!lifecycleGate.isCurrent(lease)) return@collect
                    when (fogSyncPolicy.onRevisionSynchronized(synchronization)) {
                        FogSynchronizationRenderDecision.REFRESH_CURRENT_CAMERA -> {
                            val map = googleMap ?: return@collect
                            if (!lifecycleGate.isCurrent(lease)) return@collect
                            beginFogGeneration(lease)
                            requestCanonicalFog(map.cameraPosition, lease)
                        }
                        FogSynchronizationRenderDecision.WAIT_FOR_BASELINE,
                        FogSynchronizationRenderDecision.NO_REFRESH,
                        FogSynchronizationRenderDecision.RENDER_CURRENT_CAMERA
                        -> Unit
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (lifecycleGate.isCurrent(lease)) {
                    keepCanonicalCoverVisible()
                    showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (lifecycleGate.isCurrent(lease)) {
                    keepCanonicalCoverVisible()
                    showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                }
            }
        }
    }

    /** Revokes old provider coverage before any movement-triggered canonical work starts. */
    private fun beginFogGeneration(
        lease: GoogleFogLifecycleGate.Lease? = null,
    ): FogTileGeneration? {
        if (!lifecycleGate.callbacksAllowed()) return null
        if (lease != null && !lifecycleGate.isCurrent(lease)) return null
        val adapter = fogAdapter ?: return null
        fogRenderJob?.cancel()
        fogRenderJob = null
        fogGeneration?.cancel()
        val generation = adapter.beginGeneration()
        fogGeneration = generation
        canonicalFogInstalledGeneration = null
        canonicalFogPublishedGeneration = null
        lastInstalledVisualProbePlan = null
        cancelFogInstallTimeout()
        if (!gestureCoverSuppressedForTesting) {
            // SP5 suppression models the §4.2 steady-state design (gestures never raise the
            // cover; placeholder tiles anchor coverage). Everything else — generation
            // revocation, phase coordination — stays exactly as production.
            keepCanonicalCoverVisible()
        }
        if (fogOverlay?.onGenerationStarted(generation.id) != true) {
            generation.cancel()
            fogGeneration = null
            showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
            return null
        }
        fogInstallPhase = GoogleFogInstallPhase.RENDER
        return generation
    }

    private fun requestCanonicalFog(
        camera: CameraPosition,
        lease: GoogleFogLifecycleGate.Lease? = null,
    ) {
        if (!lifecycleGate.callbacksAllowed()) return
        if (lease != null && !lifecycleGate.isCurrent(lease)) return
        if (!fogSyncPolicy.canRender()) return
        val coverageRenderer = fogCoverageRenderer ?: return
        val adapter = fogAdapter ?: return
        val coverageRequest = currentCoverageRequest(camera) ?: run {
            showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
            return
        }
        val generation = fogGeneration?.takeIf { pending ->
            FogGenerationReusePolicy.canReuse(
                activeGenerationId = pending.id,
                installedGenerationId = canonicalFogInstalledGeneration,
                adapterIsCurrent = adapter.isCurrent(pending),
            ) && canonicalFogPublishedGeneration != pending.id
        } ?: beginFogGeneration() ?: return
        fogRenderJob?.cancel()
        val renderLease = lease ?: lifecycleGate.acquire() ?: return
        fogRenderJob = fogScope.launch {
            try {
                val rendered = withTimeout(FOG_RENDER_TIMEOUT_MILLIS) {
                    withContext(Dispatchers.IO) {
                        coverageRenderer.render(coverageRequest)
                    }
                }
                ensureActive()
                if (!lifecycleGate.isCurrent(renderLease)) return@launch
                val publication = withContext(Dispatchers.Default) {
                    val published = adapter.publishMasks(generation, rendered)
                    published to if (published) {
                        fogVisualProbePlanner.plan(coverageRequest, rendered)
                    } else {
                        null
                    }
                }
                val published = publication.first
                val visualProbePlan = publication.second
                ensureActive()
                if (
                    !lifecycleGate.isCurrent(renderLease) ||
                    !published ||
                    visualProbePlan == null ||
                    fogGeneration !== generation ||
                    !adapter.isCurrent(generation)
                ) {
                    return@launch
                }
                canonicalFogPublishedGeneration = generation.id
                val controller = fogOverlay
                if (controller == null) {
                    showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                    return@launch
                }
                scheduleFogInstallTimeout(generation, adapter, renderLease)
                fogInstallPhase = GoogleFogInstallPhase.TILE_DELIVERY
                val refreshStarted = controller.onCanonicalPublished(
                    generation = generation.id,
                    visualProbePlan = visualProbePlan,
                ) { installed ->
                    if (
                        !lifecycleGate.isCurrent(renderLease) ||
                        fogGeneration !== generation ||
                        !adapter.isCurrent(generation)
                    ) {
                        return@onCanonicalPublished
                    }
                    cancelFogInstallTimeout()
                    if (!installed) {
                        fogInstallPhase = GoogleFogInstallPhase.SNAPSHOT
                        showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                        return@onCanonicalPublished
                    }
                    canonicalFogInstalledGeneration = generation.id
                    canonicalFogPublishedGeneration = generation.id
                    lastInstalledVisualProbePlan = visualProbePlan
                    fogInstallPhase = GoogleFogInstallPhase.INSTALLED
                    revealMapWhenCanonicalFogIsReady()
                    callbacks?.onCanonicalFogInstalled(generation.id)
                }
                if (!refreshStarted) {
                    cancelFogInstallTimeout()
                    fogInstallPhase = GoogleFogInstallPhase.REFRESH_REJECTED
                    showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                }
            } catch (_: TimeoutCancellationException) {
                if (
                    lifecycleGate.isCurrent(renderLease) &&
                    fogGeneration === generation &&
                    adapter.isCurrent(generation)
                ) {
                    showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (
                    lifecycleGate.isCurrent(renderLease) &&
                    fogGeneration === generation &&
                    adapter.isCurrent(generation)
                ) {
                    showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
                }
            }
        }
    }

    /** Captures SDK projection state on the main thread before any background canonical work. */
    private fun currentCoverageRequest(camera: CameraPosition): FogViewportCoverageRequest? {
        val map = googleMap ?: return null
        return try {
            val visible = map.projection.visibleRegion
            FogViewportCoverageRequest(
                center = camera.target.toFogGeoPoint(),
                floorZoom = floor(camera.zoom.toDouble()).toInt().coerceIn(0, 22),
                nearLeft = visible.nearLeft.toFogGeoPoint(),
                farLeft = visible.farLeft.toFogGeoPoint(),
                farRight = visible.farRight.toFogGeoPoint(),
                nearRight = visible.nearRight.toFogGeoPoint(),
            )
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun revealMapWhenCanonicalFogIsReady() {
        if (
            lifecycleGate.callbacksAllowed() &&
            mapLoaded &&
            canonicalFogInstalledGeneration != null
        ) {
            mapView?.visibility = View.VISIBLE
            fallback.visibility = View.GONE
            // The status TextView is a permanent non-fog rect over the map; pixel-probing spikes
            // suppress it so their captures measure the map, not the harness chrome.
            callbackStatus.visibility = if (statusOverlaySuppressed) View.GONE else View.VISIBLE
            root.bringChildToFront(callbackStatus)
        }
    }

    private fun keepCanonicalCoverVisible() {
        if (!lifecycleGate.callbacksAllowed()) return
        fallback.visibility = View.VISIBLE
        callbackStatus.visibility = View.GONE
        root.bringChildToFront(fallback)
    }

    private fun scheduleFogInstallTimeout(
        generation: FogTileGeneration,
        adapter: FogTileProviderAdapter,
        lease: GoogleFogLifecycleGate.Lease,
    ) {
        cancelFogInstallTimeout()
        val timeout = Runnable {
            fogInstallTimeout = null
            if (
                lifecycleGate.isCurrent(lease) &&
                fogGeneration === generation &&
                adapter.isCurrent(generation) &&
                canonicalFogInstalledGeneration != generation.id
            ) {
                showFallback(ProviderFallbackReason.INITIALIZATION_FAILURE)
            }
        }
        fogInstallTimeout = timeout
        mainHandler.postDelayed(timeout, FOG_INSTALL_TIMEOUT_MILLIS)
    }

    private fun cancelFogInstallTimeout() {
        fogInstallTimeout?.let(mainHandler::removeCallbacks)
        fogInstallTimeout = null
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
        // SP5's in-band video clapper: a pure-magenta square whose on/off pulses delimit the
        // fling window inside a screenrecord without any host/device clock synchronization.
        val marker = View(this).apply {
            tag = SYNC_MARKER_TAG
            setBackgroundColor(Color.rgb(255, 0, 255))
            visibility = View.GONE
        }
        syncMarker = marker
        // SYNC_MARKER_SIZE is dp — the video analyzer thresholds on marker AREA, and a raw-px
        // square at this density is too small to ever cross half-area detection.
        val markerSizePx = (SYNC_MARKER_SIZE * resources.displayMetrics.density).toInt()
        root.addView(
            marker,
            FrameLayout.LayoutParams(markerSizePx, markerSizePx).apply {
                gravity = Gravity.TOP or Gravity.END
            },
        )
        setContentView(root)
    }

    private fun showFallback(reason: ProviderFallbackReason) {
        if (!lifecycleGate.enterTerminalFallback()) return
        failedFogInstallDiagnostic = fogInstallDiagnosticForTesting()
        terminalFallback = reason
        mapLoaded = false
        googleMap = null
        fogRuntimeLoadJob?.cancel()
        fogRuntimeLoadJob = null
        fogSyncJob?.cancel()
        fogSyncJob = null
        fogRenderJob?.cancel()
        fogRenderJob = null
        fogGeneration?.cancel()
        fogGeneration = null
        canonicalFogInstalledGeneration = null
        canonicalFogPublishedGeneration = null
        lastInstalledVisualProbePlan = null
        cancelFogInstallTimeout()
        fogOverlay?.detach()
        fogOverlay = null
        fogAdapter = null
        fogProvider = null
        fogRuntime = null
        fogCoverageRenderer = null
        fogSyncPolicy.reset()
        mainHandler.removeCallbacks(mapLoadTimeout)
        disposeMapView()
        fallback.visibility = View.VISIBLE
        callbackStatus.visibility = View.GONE
        fallback.text = buildFallbackText(reason)
        root.bringChildToFront(fallback)
    }

    /** Coordinate-free state used by the opt-in AVD engineering harness. */
    fun fogInstallDiagnosticForTesting(): GoogleFogInstallDiagnostic =
        failedFogInstallDiagnostic ?: fogOverlay?.let { controller ->
            val refresh = controller.refreshSnapshot()
            val visual = controller.visualProof()
            GoogleFogInstallDiagnostic(
                phase = fogInstallPhase,
                pendingTileCount = controller.pendingCanonicalTileCount(),
                refreshFailure = controller.refreshFailure(),
                clearFailureClass = controller.clearFailureClass(),
                refreshGeneration = refresh.generation,
                refreshStarted = refresh.generationStarted,
                refreshPublished = refresh.canonicalPublished,
                visualRequiredTileCount = visual.requiredTileCount,
                visualVerifiedTileCount = visual.verifiedTileCount,
                snapshotAttempt = visual.snapshotAttempt,
                visualOffScreenOnlyTileCount = visual.offScreenOnlyTileCount,
                visualMismatchedTileCount = visual.mismatchedTileCount,
                visualMinimumOnScreenProbeCount = visual.minimumOnScreenProbeCount,
            )
        } ?: GoogleFogInstallDiagnostic(
            phase = fogInstallPhase,
            pendingTileCount = null,
            refreshFailure = GoogleFogRefreshFailure.NOT_ATTACHED,
            clearFailureClass = null,
            refreshGeneration = null,
            refreshStarted = false,
            refreshPublished = false,
            visualRequiredTileCount = 0,
            visualVerifiedTileCount = 0,
            snapshotAttempt = 0,
            visualOffScreenOnlyTileCount = 0,
            visualMismatchedTileCount = 0,
            visualMinimumOnScreenProbeCount = 0,
        )

    // ------- `V02-005` stage 3 spike seams (engineering harness only; coordinate-free) -------

    /** SP1/SP2/SP5: keeps the status TextView out of pixel-probed captures. Main thread only. */
    fun setStatusOverlaySuppressedForTesting(suppressed: Boolean) {
        statusOverlaySuppressed = suppressed
        if (suppressed) callbackStatus.visibility = View.GONE
    }

    /** SP5's in-band video clapper toggle. Main thread only. */
    fun setSyncMarkerVisibleForTesting(visible: Boolean) {
        syncMarker?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) syncMarker?.let(root::bringChildToFront)
    }

    fun fogOverlayControllerForTesting(): GoogleFogTileOverlayController? = fogOverlay

    fun fogTileProviderForTesting(): GoogleFogTileProvider? = fogProvider

    fun installedFogGenerationForTesting(): Long? = canonicalFogInstalledGeneration

    /**
     * SP6/SP8: one strictly read-only snapshot probe of the INSTALLED generation against the
     * plan it was proven with. Returns false when nothing is installed. Main thread only.
     */
    fun probeInstalledFogForTesting(onResult: (GoogleFogSpikeProbeResult?) -> Unit): Boolean {
        val controller = fogOverlay ?: return false
        val generation = canonicalFogInstalledGeneration ?: return false
        val plan = lastInstalledVisualProbePlan ?: return false
        controller.probeCanonicalSnapshotForTesting(generation, plan, onResult)
        return true
    }

    /**
     * SP9: burns generation ids until the NEXT one starts a palette cycle, so the next
     * seam-driven refresh exercises the rotation path. Burning only revokes the adapter's
     * published masks — clearTileCache is never called, so already-installed native tiles keep
     * the screen fogged. Returns the burn count, 0 when the next id is already a boundary, or
     * -1 when the adapter is absent. Main thread only.
     */
    fun advanceToNextPaletteCycleBoundaryForTesting(): Int {
        val adapter = fogAdapter ?: return -1
        val lastKnown = fogGeneration?.id
        if (lastKnown != null && FogTilePngCodec.generationStartsNewPaletteCycle(lastKnown + 1L)) {
            return 0
        }
        var burns = 0
        while (burns <= FogTilePngCodec.SIGNATURE_COLOUR_COUNT.toInt() + 1) {
            val burned = adapter.beginGeneration()
            burned.cancel()
            burns += 1
            if (FogTilePngCodec.generationStartsNewPaletteCycle(burned.id + 1L)) return burns
        }
        return -1
    }

    /**
     * SP9: a deterministic refresh trigger — no DB write, no camera move. Returns false when the
     * fog pipeline is not ready to render. Main thread only.
     */
    fun requestCanonicalFogRefreshForTesting(): Boolean {
        val map = googleMap ?: return false
        if (!fogSyncPolicy.canRender()) return false
        beginFogGeneration() ?: return false
        requestCanonicalFog(map.cameraPosition)
        return true
    }

    /** SP7/SP10: the applied camera, read back from the SDK. Main thread only. */
    fun cameraFieldsForTesting(): GoogleMapsPocCamera? = googleMap?.cameraPosition?.toPocCamera()

    fun savedStateDiagnosticForTesting(): GoogleSavedStateDiagnostic = GoogleSavedStateDiagnostic(
        mode = savedStateMode,
        restoredMapStatePresent = restoredMapStatePresent,
        providerTagMatched = providerTagMatched,
        initialCameraMoveSkipped = initialCameraMoveSkipped,
        parcelRoundTripBytes = parcelRoundTripBytes,
        parcelFailureClass = parcelFailureClass,
    )

    /**
     * SP7 `nested_parceled` arm: forces the marshal/unmarshal an in-process `recreate()` may
     * skip. A marshal failure (e.g. a live Binder inside the SDK bundle) is itself §6-relevant
     * evidence — it is recorded, and the arm proceeds restore-less so the failure stays
     * attributable. Note: Bundle contents unparcel lazily, so a corrupt inner Parcelable may
     * still only surface inside `MapView.onCreate` as INITIALIZATION_FAILURE.
     */
    private fun parcelRoundTrip(bundle: Bundle): Bundle? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            val bytes = parcel.marshall()
            parcelRoundTripBytes = bytes.size
            val reread = Parcel.obtain()
            try {
                reread.unmarshall(bytes, 0, bytes.size)
                reread.setDataPosition(0)
                reread.readBundle(javaClass.classLoader)
            } finally {
                reread.recycle()
            }
        } catch (failure: RuntimeException) {
            parcelFailureClass = failure.javaClass.name
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun forwardLifecycleCall(action: MapView.() -> Unit) {
        if (!lifecycleGate.callbacksAllowed()) return
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
        if (!lifecycleGate.callbacksAllowed()) return
        callbackStatus.text = getString(
            R.string.google_poc_camera_changed,
            camera.latitude,
            camera.longitude,
            camera.zoom,
        )
        callbacks?.onCameraChanged(camera)
    }

    private fun dispatchCameraIdle(camera: GoogleMapsPocCamera) {
        if (!lifecycleGate.callbacksAllowed()) return
        callbackStatus.text = getString(
            R.string.google_poc_camera_idle,
            camera.latitude,
            camera.longitude,
            camera.zoom,
        )
        callbacks?.onCameraIdle(camera)
    }

    private fun dispatchPointOfInterestClick(pointOfInterest: GoogleMapsPocPointOfInterest) {
        if (!lifecycleGate.callbacksAllowed()) return
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

    companion object {
        // `V02-005` stage 3 spike constants (public: the androidTestGooglePoc drivers use them).
        const val EXTRA_SAVED_STATE_MODE = "app.trailveil.googlepoc.extra.SAVED_STATE_MODE"
        const val SAVED_STATE_MODE_PLAIN = "plain"
        const val SAVED_STATE_MODE_NESTED = "nested"
        const val SAVED_STATE_MODE_NESTED_PARCELED = "nested_parceled"
        const val NESTED_MAP_STATE_KEY = "trailveil.map.primary"
        const val NESTED_PROVIDER_KEY = "provider"
        const val NESTED_STATE_KEY = "state"
        const val NESTED_PROVIDER_VALUE = "google"
        const val SYNC_MARKER_TAG = "trailveil_sp5_sync_marker"
        const val SYNC_MARKER_SIZE = 48

        const val MAP_LOAD_TIMEOUT_MILLIS = 30_000L
        const val FOG_RUNTIME_TIMEOUT_MILLIS = 10_000L
        const val FOG_SYNC_TIMEOUT_MILLIS = 15_000L
        const val FOG_RENDER_TIMEOUT_MILLIS = 30_000L
        const val FOG_INSTALL_TIMEOUT_MILLIS = 15_000L
        const val MAX_VIEWPORT_FOG_TILES = 256
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

private fun LatLng.toFogGeoPoint(): GeoPoint = GeoPoint(
    latitude = latitude,
    longitude = longitude,
)
