package app.trailveil.map

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.trailveil.BuildConfig
import app.trailveil.googlepoc.GoogleFogTileProvider
import app.trailveil.map.fog.FogActualTileRequestSet
import app.trailveil.map.fog.FogCameraMoveReason
import app.trailveil.map.fog.FogCameraPort
import app.trailveil.map.fog.FogCoverReason
import app.trailveil.map.fog.FogLifecycleBudget
import app.trailveil.map.fog.FogOverlayPort
import app.trailveil.map.fog.FogOverlaySurfaceCoordinator
import app.trailveil.map.fog.FogPocMosaic
import app.trailveil.map.fog.anchoredNear
import app.trailveil.map.fog.FogMosaicTile
import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogProbeExclusionZone
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRequestedTileWindowRenderer
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogSnapshotPort
import app.trailveil.map.fog.FogSnapshotVisualProbePlan
import app.trailveil.map.fog.FogSnapshotVisualProbePlanMemo
import app.trailveil.map.fog.FogRasterWorkProbe
import app.trailveil.map.fog.FogSnapshotVisualProbePlanner
import app.trailveil.map.fog.FogSynchronizationRenderDecision
import app.trailveil.map.fog.FogSynchronizationRenderPolicy
import app.trailveil.map.fog.FogTileGeneration
import app.trailveil.map.fog.FogTileKey
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.FogTileProviderAdapter
import app.trailveil.map.fog.FogViewportBatchSubrenderer
import app.trailveil.map.fog.FogViewportCoveragePlanner
import app.trailveil.map.fog.FogViewportCoverageRequest
import app.trailveil.map.fog.fogViewportCoveredByPublishedTiles
import app.trailveil.map.fog.FogViewportRender
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.wholeWorldFogProbeExclusionZone
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Coordinate-free state exported only to tests and Compose cover publication. */
internal data class GoogleCanonicalFogState(
    val coverUp: Boolean,
    val coverReason: FogCoverReason?,
    val installedGeneration: Long?,
    val pendingGeneration: Long?,
    val terminal: Boolean,
    val retryScheduled: Boolean,
    val lastCoverIntervalMillis: Long?,
    val maximumCoverIntervalMillis: Long,
    val surfaceDescription: String? = null,
    /** V02-012: reveal beneath a raised cover to that cover's lowering on the passed verdict. */
    val lastVerificationHoldMillis: Long? = null,
    val maximumVerificationHoldMillis: Long = 0L,
    /**
     * `V03-013`: where the wall time of the generation that lowered the last cover went, stage by
     * stage, as one label (the live generation until a cover has lowered).
     *
     * Null until a generation has rendered. Names, milliseconds and counts only - no coordinates, no
     * identifiers - so it can sit on the harness badge and in the gates tag. The cover interval
     * the badge already shows is the sum a person feels; this is what it is made of, and without
     * it no change to the rebuild path can claim to have moved anything.
     */
    val stageSummary: String? = null,
    /**
     * A person's gesture holds the camera and the binding's cover deadline is stopped for it
     * (`REASON_GESTURE` move started after the first cover has lowered on a passed proof, not yet
     * idle). The host's net waits longer than one window while this is true.
     */
    val gestureHeld: Boolean = false,
    /**
     * Counts the binding's re-arms at a gesture's settling idle. The host restarts its net only
     * on these - never on the binding's first arm or a resume - so a runtime that arrives late
     * still keeps the window that started with the first visible cover (V02-007).
     */
    val gestureSettleClock: Long = 0L,
    /** Harness data replacement is waiting for canonical transactions, not the renderer. */
    val canonicalReplacementInProgress: Boolean = false,
)

/**
 * Production-only binding from canonical fog to Google TileOverlay.
 *
 * One placeholder bootstrap overlay obtains the renderer's real request set under the first-install
 * cover. Every committed generation renders those observed LODs plus the current floor-zoom safety
 * rectangle, publishes atomically, attaches a new overlay before removing the old, and arms a
 * [FogActualTileRequestSet] barrier from only the requests made after that attachment. No refresh
 * path calls clearTileCache.
 */
internal class GoogleCanonicalFogSurfaceBinding(
    private val map: GoogleMap,
    private val runtime: FogRuntime,
    private val onStateChanged: (GoogleCanonicalFogState) -> Unit,
    private val onTerminalFailure: () -> Unit,
    private val onFogFailure: (Throwable) -> Unit,
    private val onFogRendered: ((FogViewportRender) -> Unit)? = null,
    private val onProofObserved: (GoogleFogProofObservation) -> Unit = {},
    private val exclusionZonesForProof: () -> List<FogProbeExclusionZone> = { emptyList() },
    private val onUnprovableProofPlan: () -> Boolean = { false },
    private val onProofAccepted: (Long) -> Unit = {},
    /**
     * Rejects one canonical overlay install by throwing; `null` in every production composition.
     *
     * The neutral surface's own `fogInstallFaultForTesting` parameter reaches the binding here, so
     * the injection point is a constructor argument rather than process state: a surface whose
     * host passed nothing cannot observe that this seam exists.
     */
    private val installFaultForTesting: (() -> Unit)? = null,
    /**
     * How a published generation is put on the map. Null - every shipped build - means tiles.
     *
     * `V03-011` arm 2 rasterises the same masks into one anchored image instead. Everything
     * before that point is identical, so the prototype is an installer rather than a second
     * binding; see [GoogleFogOverlayInstaller]. Only a per-build-type seam can supply one, and
     * the `googleRelease` twin returns null unconditionally.
     */
    private val overlayInstaller: GoogleFogOverlayInstaller? = null,
    private val awaitCoverCommitted: suspend () -> Boolean = { false },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var nativeCoverCommitted = false
    private var nativeCoverEpoch = 0L
    private var nativeCoverCommitJob: Job? = null
    private var nativeRetirementOwner: Long? = null
    private var deferredDeliveryGeneration: Long? = null
    /**
     * Read once, here, so one surface cannot straddle two `V03-011` arms. A constant in every
     * published build; see [googleFogCoverageProfile].
     */
    private val coverageProfile = googleFogCoverageProfile()
    private val adapter = FogTileProviderAdapter(cacheBudget = coverageProfile.cacheBudget())
    private val actualRequests = FogActualTileRequestSet()

    /**
     * What this surface RENDERS: the visible rectangle, plus the arm's ring if it has one.
     *
     * Distinct from [surroundPlanner] on purpose, and identical to it in every shipped build. See
     * [GoogleFogCoverageProfile]: padding both the published set and the predicted set cancels
     * exactly, so a ring is only worth rendering if the surround test does NOT pad its prediction.
     */
    private val renderPlanner = coverageProfile.renderPlanner()

    /**
     * What this surface REASONS about: the visible rectangle, never the ring.
     *
     * Used by the surround test that decides whether the safety cover rises, by the proof plan and
     * by the compatibility render callback - all three of which ask "what must be fogged for what
     * the user can see now", a question a pre-rendered ring is not part of.
     */
    private val surroundPlanner = coverageProfile.surroundPlanner()
    private val requestedRenderer = FogRequestedTileWindowRenderer(
        subrenderer = FogViewportBatchSubrenderer { request, keys ->
            runtime.viewportCoordinator.renderTiles(request, keys).also { rendered ->
                // `V03-013` stage clocks: queryBounds is non-null only when this batch read Room,
                // which separates a raster-cold generation from a raster-warm one. The counter is
                // the render job's own (carried in its coroutine context), so a cancelled
                // predecessor finishing its mutex section late cannot count against its successor.
                if (rendered.queryBounds != null) {
                    currentCoroutineContext()[RoomWindowCounter]?.count?.incrementAndGet()
                }
            }
        },
        // The union of the render plan with the SDK's observed requests reaches this renderer, so
        // its own 256 is one of the budgets a ring pushes on - not, as first committed, one that
        // stays put because padding does not change what the SDK asks for. It does not, but the
        // padded PLAN travels the same path, and the window planner refuses a set larger than its
        // bound before a single tile is rendered.
        maxTiles = coverageProfile.maxRequestedKeys,
    )
    private val probePlanner = FogSnapshotVisualProbePlanner()
    private val proofPlanMemo = FogSnapshotVisualProbePlanMemo()
    private val synchronizationPolicy = FogSynchronizationRenderPolicy()
    private val generations = LinkedHashMap<Long, FogTileGeneration>()
    private val generationEpochs = LinkedHashMap<Long, Long>()
    private val renderJobs = LinkedHashMap<Long, Job>()
    private val overlays = LinkedHashMap<Long, TileOverlay>()
    private val providers = LinkedHashMap<Long, GoogleFogTileProvider>()
    private val masksByGeneration = LinkedHashMap<Long, Map<FogTileKey, FogPixelMask>>()
    private val coverageByGeneration = LinkedHashMap<Long, FogViewportCoverageRequest>()
    private val recentRequestLock = Any()
    private val recentRequests = LinkedHashSet<FogTileKey>()
    private var recentRequestsOverflowed = false
    @Volatile private var lastRequestAtNanos = 0L
    @Volatile private var targetOverlayGeneration: Long? = null
    private var lastProvenRequestedKeys: Set<FogTileKey> = emptySet()
    private var bootstrapOverlay: TileOverlay? = null

    /** V02-012 design 2: the cover state last seen, for its rising edge (see afterCoordinatorMutation). */
    private var coverWasUp = false
    private var revealedBeneathCoverAtNanos: Long? = null
    private var lastVerificationHoldMillis: Long? = null
    private var maximumVerificationHoldMillis: Long = 0L
    private var bootstrapProvider: GoogleFogTileProvider? = null
    private var pendingCoverageKeys: Set<FogTileKey>? = null
    private var installedCoverageKeys: Set<FogTileKey>? = null
    private var baselineReady = false
    private var appliedCanonicalEpoch = runtime.canonicalEpoch.value
    private var mapLoaded = false
    @Volatile private var hostStopped = false
    @Volatile private var released = false
    private var terminalPublished = false
    private var retryPosted = false
    private var installTimeout: Runnable? = null
    private var installTimeoutGeneration: Long? = null
    private var pausedInstallTimeoutGeneration: Long? = null
    private var coverDeadline: Runnable? = null
    /**
     * A person's gesture holds the camera: `REASON_GESTURE` move started after the first cover has
     * lowered, no idle yet (a fling counts until it settles). The cover deadline does not run
     * while this is true; see [armCoverDeadline].
     */
    private var gestureHeld = false
    /**
     * The first cover has lowered on a passed proof. Until then the gesture rule is off and the
     * first-composition cover keeps V02-007's bound. `coordinator.installedGenerationId` cannot
     * serve as this gate: it is set at reveal, before the proof, so a first install whose proof
     * keeps failing carries an installed id under the same first cover.
     */
    private var firstCoverLowered = false
    /**
     * Bumped when a gesture's settling idle re-arms the deadline; published so the host's net
     * restarts with exactly those arms and no other (a late runtime keeps its original window).
     */
    private var gestureSettleClock = 0L
    private var coverRaisedAtNanos: Long? = null
    private var lastCoverIntervalMillis: Long? = null
    private var maximumCoverIntervalMillis = 0L
    private var lastPublishedCoverUp = false
    private var lastGenerationId = 0L
    private var cameraEpoch = 0L
    /** Restarts through [restartStrandedGeneration]; a count for the failure messages. */
    private var strandedRestarts = 0
    @Volatile private var renderWork: RenderWork? = null

    /** V02-012 diagnostics: the last render's key count and durations, for the gates string. */
    private var lastRenderKeys: Int? = null
    // `V03-013` stage clocks. lastRenderMillis stays the raster+prepare sum every existing
    // reader expects; these split it and add the stages around it. All main-thread.
    private var lastCoverToRenderMillis: Long? = null
    private var lastRasterMillis: Long? = null
    private var lastRasterRoomWindows: Int? = null

    /** Per-render-job count of raster batches that read Room; lives in the job's coroutine context. */
    private class RoomWindowCounter : AbstractCoroutineContextElement(RoomWindowCounter) {
        val count = AtomicInteger(0)

        companion object Key : CoroutineContext.Key<RoomWindowCounter>
    }
    private var lastPrepareMillis: Long? = null
    private var lastAttachMillis: Long? = null
    private var lastProofMillis: Long? = null
    private var lastProofPlanMillis: Long? = null
    private var lastProofPlanAttempts: Int = 0
    private var proofPlanMillisAccumulated: Long = 0L
    private var proofPlanAttemptsAccumulated: Int = 0
    private var lastRasterWork: String? = null
    // The stage line of the generation that lowered the last cover, captured on that lowering.
    // The live clocks keep moving (a stale handover rebuild renders again beneath no cover), and
    // the number a hand reads beside the cover interval must describe the same event.
    private var coveredStageSummary: String? = null
    private var coveredSurfaceDescription: String? = null
    // `V03-013` per-cover-interval totals. Several generations can render beneath ONE cover (a
    // stale handover rebuild, a refuted proof, a restart) and the stage line names only the last;
    // these sum every generation since the cover rose, and split the wait into the gesture
    // (cover rise -> the last camera idle) and the remainder.
    private var lastCameraIdleAtMillis: Long? = null
    private var lastCoverGestureMillis: Long? = null
    private var intervalGenerations: Int = 0
    private var intervalRasterMillis: Long = 0L
    private var intervalPrepareMillis: Long = 0L
    private var intervalProofMillis: Long = 0L
    private var intervalProofAttempts: Int = 0
    /** The ring the last render's plan actually got, which a narrowed plan makes differ. */
    private var lastAppliedPaddingTiles: Int = 0
    private var lastRenderMillis: Long? = null
    private var lastPublishMillis: Long? = null

    /** Origin of the timed traces (coordinator transitions, prover events), so they align. */
    private val traceOriginMillis = SystemClock.elapsedRealtime()
    private fun traceMillis(): Long = SystemClock.elapsedRealtime() - traceOriginMillis

    /** One generation-owned render budget, retained across a host stop. */
    private class RenderWork(
        val generation: FogTileGeneration,
        val coverage: FogViewportCoverageRequest,
        val requested: Set<FogTileKey>,
        val required: Set<FogTileKey>,
        val preferRequiredMasks: Boolean,
        val cameraEpoch: Long,
        val canonicalEpoch: Long,
        val budget: FogLifecycleBudget,
    ) {
        @Volatile var activeLease: FogLifecycleBudget.Lease? = null
        @Volatile var pausedLease: FogLifecycleBudget.Lease? = null
        @Volatile var job: Job? = null
    }

    private lateinit var coordinator: FogOverlaySurfaceCoordinator
    private val snapshotProver = GoogleFogSnapshotProver(
        map = map,
        scope = scope,
        planForAttempt = ::freshProofPlan,
        cameraEpoch = { cameraEpoch },
        onProofObserved = onProofObserved,
        hostStopped = { hostStopped },
        onUnprovablePlan = onUnprovableProofPlan,
        nowMillis = ::traceMillis,
    )

    private val overlayPort = object : FogOverlayPort {
        override fun requiresCoverForHandover(installedGenerationId: Long): Boolean =
            overlayInstaller?.requiresCoverForHandover(installedGenerationId) == true

        override fun beginRebuild(handover: Boolean, paletteRotation: Boolean): Long {
            assertMainThread()
            if (handover) latchNativeRetirement()
            val generation = if (handover) {
                adapter.beginHandoverGeneration()
            } else {
                adapter.beginGeneration()
            }
            lastGenerationId = generation.id
            generations[generation.id] = generation
            generationEpochs[generation.id] = runtime.canonicalEpoch.value
            handler.post { if (!released) startRender(generation) }
            return generation.id
        }

        override fun attachOverlay(generationId: Long) {
            assertMainThread()
            if (released || generationId !in generations) return
            if (!isCanonicalGenerationCurrent(generationId)) return
            overlayInstaller?.let { installer ->
                attachThroughInstaller(installer, generationId)
                return
            }
            if (!demoteExistingOverlays()) {
                handler.post {
                    failGeneration(generationId, IllegalStateException("old overlay z-order failed"))
                }
                return
            }
            targetOverlayGeneration = generationId
            actualRequests.begin(generationId)
            clearRecentRequests()
            val targetProvider = createProvider(targetGeneration = generationId)
            val overlay = addOverlay(targetProvider, NEW_OVERLAY_Z, HIDDEN_FOG_TRANSPARENCY)
            if (overlay == null) {
                targetProvider.releaseObservers()
                handler.post { failGeneration(generationId, IllegalStateException("overlay attach failed")) }
                return
            }
            providers[generationId] = targetProvider
            overlays[generationId] = overlay
            // Null on every production attach. A host that did pass a fault fails the generation
            // from HERE, after the replacement overlay is attached and recorded, so the failure
            // takes the whole route a post-attach install failure takes: the coordinator sees
            // `overlayAttached`, so `failPending` runs `cancelRebuild` AND `removeOverlay`, which
            // is what releases this provider's observers again. The old generation is already
            // demoted and this generation's delivery barrier has begun, exactly as they would be
            // when a real install fails part way through, so the proven generation underneath
            // stays complete and presented while the retry runs.
            val injectedRejection = installFaultRejectionOrNull()
            if (injectedRejection != null) {
                handler.post { failGeneration(generationId, injectedRejection) }
                return
            }
            lastRequestAtNanos = SystemClock.elapsedRealtimeNanos()
            scheduleDeliveryQuietCheck(generationId)
        }

        override fun revealOverlay(generationId: Long, previousGenerationId: Long?) {
            assertMainThread()
            if (!isCanonicalGenerationCurrent(generationId)) return
            overlayInstaller?.let { installer ->
                if (!installer.reveal(generationId, previousGenerationId)) {
                    handler.post {
                        if (released) return@post
                        snapshotProver.cancelGeneration(generationId)
                        coordinator.onRevealFailed(generationId)
                        afterCoordinatorMutation()
                    }
                    return
                }
                revealedBeneathCoverAtNanos =
                    if (coordinator.coverUp) SystemClock.elapsedRealtimeNanos() else null
                return
            }
            // V02-012 design 2: one turn, no other renderer work in between, so the SDK can apply
            // the show and the hide in the same frame. The bootstrap placeholder is already hidden
            // and leaves in afterCoordinatorMutation once a generation is installed.
            //
            // The hide is conditional on the show, and that is a safety rule, not tidiness: the
            // overlay can be absent (a post-attach failure is posted, so the coordinator already
            // counts it attached) and `setTransparencySafely` swallows the SDK's own failures, so
            // an unconditional hide can leave BOTH layers hidden with the cover down - bare
            // basemap until a verification round trip notices. Keeping the predecessor visible is
            // the fail-closed choice; `onRevealFailed` then raises the cover and rebuilds.
            val shown = overlays[generationId]?.setTransparencySafely(VISIBLE_FOG_TRANSPARENCY) == true
            if (!shown) {
                handler.post {
                    if (released) return@post
                    snapshotProver.cancelGeneration(generationId)
                    coordinator.onRevealFailed(generationId)
                    afterCoordinatorMutation()
                }
                return
            }
            previousGenerationId?.let { previous -> overlays[previous]?.setTransparencySafely(HIDDEN_FOG_TRANSPARENCY) }
            // Beneath a raised cover the reveal is invisible until the verdict lowers the cover;
            // the hold is measured from here to that lowering (reported in the state).
            revealedBeneathCoverAtNanos = if (coordinator.coverUp) SystemClock.elapsedRealtimeNanos() else null
        }

        override fun removeOverlay(generationId: Long): Boolean {
            assertMainThread()
            snapshotProver.cancelGeneration(generationId)
            overlayInstaller?.let { installer ->
                // A stale/reset callback must not retire polygons before the cover's buffer.
                if (!nativeCoverCommitted && nativeRetirementOwner == generationId) return false
                if (!installer.remove(generationId)) return false
                generations.remove(generationId)
                generationEpochs.remove(generationId)
                masksByGeneration.remove(generationId)
                coverageByGeneration.remove(generationId)
                return true
            }
            val overlay = overlays[generationId]
            if (overlay != null && !overlay.removeSafely()) return false
            overlays.remove(generationId)
            providers.remove(generationId)?.releaseObservers()
            generations.remove(generationId)
            generationEpochs.remove(generationId)
            masksByGeneration.remove(generationId)
            coverageByGeneration.remove(generationId)
            return true
        }

        override fun clearTileCache() {
            error("production fog refresh must never call clearTileCache")
        }

        override fun cancelRebuild(generationId: Long) {
            assertMainThread()
            if (deferredDeliveryGeneration == generationId) deferredDeliveryGeneration = null
            snapshotProver.cancelGeneration(generationId)
            val work = renderWork?.takeIf { it.generation.id == generationId }
            if (work != null) {
                work.budget.cancel()
                work.activeLease = null
                work.pausedLease = null
                work.job?.cancel()
                work.job = null
                renderWork = null
            }
            renderJobs.remove(generationId)?.cancel()
            generations.remove(generationId)?.cancel()
            generationEpochs.remove(generationId)
            actualRequests.cancel(generationId)
            // A bounded overflow belongs to the failed attempt. Rotating the request log here
            // lets the coordinator's retry fall back to the last proven set instead of repeatedly
            // failing on stale overflow state.
            clearRecentRequests()
            if (targetOverlayGeneration == generationId) targetOverlayGeneration = null
            cancelInstallTimeout(generationId)
            pendingCoverageKeys = null
        }
    }

    private val snapshotPort = object : FogSnapshotPort {
        override fun prove(generationId: Long, onResult: (Boolean) -> Unit) {
            assertMainThread()
            if (!isCanonicalGenerationCurrent(generationId)) return
            val proofStartedAtMillis = SystemClock.elapsedRealtime()
            proofPlanMillisAccumulated = 0L
            proofPlanAttemptsAccumulated = 0
            proofPlanMemo.clear()
            // V02-012 design 2: this is a VERIFICATION of an overlay already revealed at the fog
            // display opacity; the prover reads it through the revealed-fog window.
            snapshotProver.prove(generationId,
                snapshotNotBeforeMillis = overlayInstaller?.snapshotNotBeforeMillis(generationId) ?: 0L,
            ) { passed ->
                proofPlanMemo.clear()
                if (released) return@prove
                if (!isCanonicalGenerationCurrent(generationId)) return@prove
                lastProofMillis = SystemClock.elapsedRealtime() - proofStartedAtMillis
                lastProofPlanMillis = proofPlanMillisAccumulated
                lastProofPlanAttempts = proofPlanAttemptsAccumulated
                intervalProofMillis += SystemClock.elapsedRealtime() - proofStartedAtMillis
                intervalProofAttempts += proofPlanAttemptsAccumulated
                onResult(passed)
                // A refuted generation that is still the installed one stays hidden with the rest
                // beneath the cover the coordinator has just raised or kept (no rising edge
                // happens while the cover is already up); its successor is revealed anew.
                if (!passed && coordinator.coverUp && coordinator.installedGenerationId == generationId) {
                    hideOverlaysBeneathCover()
                }
                afterCoordinatorMutation()
                // Complete-install staleness checks run inside onResult. Only notify the overlay
                // renderer after the coordinator still owns this generation as installed and
                // proven; a late snapshot for an older generation must not resurrect its marker
                // or polyline.
                if (
                    passed &&
                    coordinator.installedGenerationId == generationId &&
                    coordinator.pendingGenerationId == null &&
                    !coordinator.coverUp &&
                    !coordinator.retryScheduled &&
                    !coordinator.terminal
                ) {
                    onProofAccepted(generationId)
                }
            }
        }
    }

    private val cameraPort = object : FogCameraPort {
        override fun insidePublishedSurround(): Boolean {
            overlayInstaller?.let { installer ->
                val generation = coordinator.installedGenerationId
                if (!installerStillResolves(generation)) return false
                return installer.covers(generation, visibleCornersOrEmpty())
            }
            return insideCoverage(installedCoverageKeys)
        }

        override fun insidePendingSurround(): Boolean {
            // Completion asks about the generation it is just proving, before the coordinator
            // swaps the installed-generation identity. Movement never uses this pending read.
            overlayInstaller?.let { installer ->
                val generation = coordinator.pendingGenerationId
                    ?: coordinator.installedGenerationId
                if (!installerStillResolves(generation)) return false
                return installer.covers(generation, visibleCornersOrEmpty())
            }
            return insideCoverage(pendingCoverageKeys ?: installedCoverageKeys)
        }
    }

    init {
        coordinator = FogOverlaySurfaceCoordinator(overlayPort, snapshotPort, cameraPort, nowMillis = ::traceMillis)
        bootstrapProvider = createProvider(targetGeneration = null)
        // V02-012 design 2: the fog-coloured cover is what hides the map before the first
        // generation; the placeholder overlay exists for the adapter handover and stays hidden.
        bootstrapOverlay = addOverlay(requireNotNull(bootstrapProvider), OLD_OVERLAY_Z, HIDDEN_FOG_TRANSPARENCY)
        if (bootstrapOverlay == null) {
            coordinator.onFirstComposition()
            coordinator.onFogRuntimeFailure()
            afterCoordinatorMutation()
        } else {
            coordinator.onFirstComposition()
            afterCoordinatorMutation()
            startSynchronization()
        }
    }

    fun onMapLoaded() {
        assertMainThread()
        mapLoaded = true
        requestCurrentViewportIfReady()
    }

    fun onHostStarted() {
        assertMainThread()
        if (released) return
        val resuming = hostStopped
        hostStopped = false
        val proofGeneration = coordinator.pendingGenerationId ?: coordinator.installedGenerationId
        val proofResumed = if (resuming) {
            snapshotProver.onHostStarted(proofGeneration)
        } else {
            false
        }
        if (resuming) {
            // A stop ends any touch, and the SDK owes no idle for it.
            gestureHeld = false
            // Re-arm a full window rather than resuming a partly-elapsed one: the time spent
            // stopped was time the surface had no way to make progress, so charging it against
            // the deadline would punish the user for backgrounding the app.
            if (coordinator.coverUp && !coordinator.terminal) armCoverDeadline()
            val pausedInstall = pausedInstallTimeoutGeneration
            pausedInstallTimeoutGeneration = null
            pausedInstall?.let(::scheduleInstallTimeout)
            onStateChanged(state())
        }
        if (!baselineReady) return
        resumePendingRenderIfNeeded()
        deferredDeliveryGeneration?.let(::deliverAfterNativeCoverCommit)
        // A proof that was paused across ON_STOP keeps its attempt budget. Starting a second
        // re-proof here would silently replace it with a fresh ten-attempt budget.
        if (!proofResumed) coordinator.onStart()
        afterCoordinatorMutation()
    }

    /**
     * A stopped host cannot satisfy either bounded deadline, so neither may run against it.
     *
     * The SDK renderer issues no `getTile` calls while stopped, so an actual-request barrier can
     * never drain, and `map.snapshot()` on a non-rendering surface cannot produce
     * generation-coloured pixels, so the prover cannot pass. The cover therefore cannot lower.
     * Leaving the deadlines armed turned pocketing the phone with the cover up into a permanent
     * terminal failure on the recording screen — reproduced on API 36 before this guard existed.
     * Both are re-armed fresh by [onHostStarted].
     */
    fun onHostStopped() {
        assertMainThread()
        if (released || hostStopped) return
        hostStopped = true
        // Cancel an outstanding acknowledgement, but do not retire a successor already shown
        // for proof when that same proof resumes. Its predecessor fence has finished.
        invalidateNativeCoverCommit(preserveCommittedCover = true)
        snapshotProver.onHostStopped()
        // A render that is still reading canonical data is paused. Once its budget is complete,
        // the short adapter commit is allowed to finish; cancelling that phase would leave a
        // generation with a published adapter set but no coordinator callback to own it.
        pauseActiveRender()
        cancelCoverDeadline()
        val activeInstall = installTimeoutGeneration
        activeInstall?.let(::cancelInstallTimeout)
        pausedInstallTimeoutGeneration = activeInstall
    }

    fun onCameraMoveStarted(reason: Int) {
        assertMainThread()
        if (released) return
        cameraEpoch += 1L
        snapshotProver.onInputsChanged()
        clearRecentRequests()
        val fogReason = reason.toFogReason()
        if (fogReason == FogCameraMoveReason.GESTURE && firstCoverLowered) {
            // The person has the camera; the surface is not the one being waited for. Set before
            // the coordinator runs so a cover it raises now waits for the idle, and so the state
            // published below already carries the flag to the host's net. Only once the first
            // cover has lowered on a passed proof: the first-composition cover keeps V02-007's
            // bound, 20 s from the first visible cover whatever the finger does, because until
            // something has proven the fallback provider is the better map.
            gestureHeld = true
            cancelCoverDeadline()
        }
        coordinator.onCameraMoveStarted(fogReason)
        afterCoordinatorMutation()
    }

    fun onCameraMoveFrame() {
        assertMainThread()
        if (released) return
        cameraEpoch += 1L
        snapshotProver.onInputsChanged()
        coordinator.onCameraMoveFrame()
        afterCoordinatorMutation()
    }

    fun onCameraIdle() {
        assertMainThread()
        lastCameraIdleAtMillis = SystemClock.elapsedRealtime()
        val settledFromGesture = gestureHeld
        gestureHeld = false
        if (released) return
        val ready = baselineReady && mapLoaded
        if (ready) {
            cameraEpoch += 1L
            snapshotProver.onInputsChanged()
            coordinator.onCameraIdle()
        }
        // The gesture that cancelled the window has settled: from here on only the surface's own
        // rebuild, proof and retries can keep the cover up, and they get the full window. Armed
        // before the publish below, so the host's net restarts with this clock. The not-ready
        // path still arms and publishes: the synchronizer's refresh path drives the coordinator's
        // idle without the mapLoaded gate, so a whole cycle - and the first lowering - can happen
        // before onMapLoaded, and a settle that neither armed nor published would leave the
        // binding without its bound and the host waiting its long net on a stale flag.
        if (settledFromGesture && coordinator.coverUp && !coordinator.terminal && !hostStopped) {
            gestureSettleClock += 1L
            armCoverDeadline()
        }
        if (ready) afterCoordinatorMutation() else if (settledFromGesture) onStateChanged(state())
    }

    fun onCameraMoveCancelled() = onCameraIdle()

    /** Claims the coordinator's SP10-verified ticket for an ordinary programmed camera move. */
    fun beginProgrammedFlight(): Long {
        assertMainThread()
        return coordinator.beginProgrammedFlight()
    }

    /** Releases a programmed flight only when its ticket is still current. */
    fun endProgrammedFlight(ticket: Long): Boolean {
        assertMainThread()
        return coordinator.endProgrammedFlight(ticket)
    }

    /** Claims the follow-ease ticket and marks the move as exempt from the move-start cover. */
    fun beginFollowEase(): Long {
        assertMainThread()
        return coordinator.beginFollowEase()
    }

    /** Releases a follow-ease ticket without letting a stale cancel clear a newer flight. */
    fun endFollowEase(ticket: Long): Boolean {
        assertMainThread()
        return coordinator.endFollowEase(ticket)
    }

    fun programmedFlightActive(): Boolean {
        assertMainThread()
        return coordinator.programmedFlightActive
    }

    /** Invalidates a proof that predates a newly published marker/track payload. */
    fun onOverlayDataChanged() {
        assertMainThread()
        cameraEpoch += 1L
        snapshotProver.onInputsChanged()
    }

    fun release() {
        proofPlanMemo.clear()
        assertMainThread()
        if (released) return
        released = true
        invalidateNativeCoverCommit()
        cancelInstallTimeout()
        coverDeadline?.let(handler::removeCallbacks)
        coverDeadline = null
        handler.removeCallbacksAndMessages(null)
        snapshotProver.release()
        overlayInstaller?.release()
        providers.values.forEach { tileProvider -> tileProvider.releaseObservers() }
        providers.clear()
        bootstrapProvider?.releaseObservers()
        bootstrapProvider = null
        scope.cancel()
        renderWork?.budget?.cancel()
        renderWork?.activeLease = null
        renderWork?.pausedLease = null
        renderWork = null
        renderJobs.values.forEach(Job::cancel)
        renderJobs.clear()
        generations.values.forEach(FogTileGeneration::cancel)
        generations.clear()
        generationEpochs.clear()
        overlays.values.forEach { overlay -> overlay.removeSafely() }
        overlays.clear()
        bootstrapOverlay?.removeSafely()
        bootstrapOverlay = null
    }

    private fun startSynchronization() {
        scope.launch {
            try {
                runtime.canonicalEpoch.collectLatest { epoch ->
                    if (epoch != runtime.canonicalEpoch.value) return@collectLatest
                    applyCanonicalEpoch(epoch)
                    // Fixture replacement may span many transactions. Synchronization begins after
                    // its completion publication, so fixture duration is not a render timeout.
                    if (epoch % 2L != 0L) return@collectLatest
                    val baseline = withTimeout(SYNCHRONIZATION_TIMEOUT_MILLIS) {
                        withContext(Dispatchers.IO) {
                            runtime.changeSynchronizer.synchronizeTo(expectedCanonicalEpoch = epoch)
                        }
                    }
                    ensureActive()
                    if (released || baseline.superseded || epoch != runtime.canonicalEpoch.value) return@collectLatest
                    synchronizationPolicy.onBaselineSynchronized(baseline)
                    baselineReady = true
                    requestCurrentViewportIfReady()
                    runtime.pointChanges.revisionsAfter(baseline.cursor).collect { revision ->
                        val update = withTimeout(SYNCHRONIZATION_TIMEOUT_MILLIS) {
                            withContext(Dispatchers.IO) {
                                runtime.changeSynchronizer.synchronizeTo(revision.latestCursor, epoch)
                            }
                        }
                        ensureActive()
                        if (
                            !update.superseded && epoch == runtime.canonicalEpoch.value &&
                            synchronizationPolicy.onRevisionSynchronized(update) ==
                            FogSynchronizationRenderDecision.REFRESH_CURRENT_CAMERA
                        ) {
                            coordinator.onCanonicalRefreshRequired()
                            coordinator.onCameraIdle()
                            afterCoordinatorMutation()
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                // MUST precede the CancellationException clause, same reason as startRender.
                failSynchronization(timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failSynchronization(failure)
            }
        }
    }

    private fun isCanonicalGenerationCurrent(generationId: Long): Boolean {
        val epoch = generationEpochs[generationId] ?: return false
        val currentEpoch = runtime.canonicalEpoch.value
        if (epoch == currentEpoch && currentEpoch % 2L == 0L && baselineReady) return true
        // Ports execute within coordinator transitions. Re-entering reset here would let the
        // outer attach/reveal transition restore its stale local pending state after the reset.
        handler.post { if (!released) applyCanonicalEpoch(runtime.canonicalEpoch.value) }
        return false
    }

    private fun applyCanonicalEpoch(epoch: Long) {
        if (appliedCanonicalEpoch != epoch) {
            invalidateNativeCoverCommit()
            baselineReady = false
            appliedCanonicalEpoch = epoch
            snapshotProver.release()
            proofPlanMemo.clear()
            coordinator.onCanonicalResetRequired()
            afterCoordinatorMutation()
            hideOverlaysBeneathCover()
            if (epoch % 2L == 0L && coordinator.coverUp && !hostStopped && !gestureHeld) {
                armCoverDeadline()
            }
        }
    }

    /**
     * Reporting a synchronization failure is not enough to recover from one.
     *
     * [startSynchronization] has a single call site in `init`, and the revisions collector lives
     * inside its `try`, so any failure escaping the collector ends the coroutine for good. The
     * generation retry path cannot stand in for it: renders are cache-first
     * (`FogViewportCoordinator.renderTilesLocked` serves `pipeline.loadCached` and only reads Room
     * on a miss), and `FogChangeSynchronizer` is the sole driver of the reveal merges that
     * invalidate those cached masks. A dead collector therefore leaves the walker's own tiles stale
     * indefinitely while the surface publishes a healthy state — cover down, not terminal, and the
     * retry badge cleared as soon as the next generation installs. Restart it instead.
     */
    private fun failSynchronization(failure: Throwable) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { failSynchronization(failure) }
            return
        }
        if (released) return
        baselineReady = false
        snapshotProver.release()
        proofPlanMemo.clear()
        // A failed append/revision read does not revoke previously proven canonical pixels.
        // Real replacement epochs still enter applyCanonicalEpoch and retire beneath the cover.
        // Preserve that cover if already raised; otherwise failRuntime can report/retry behind
        // the last proven generation instead of treating a transient read error as a deletion.
        failRuntime(failure)
        handler.postDelayed(
            { if (!released) startSynchronization() },
            SYNCHRONIZATION_RETRY_MILLIS,
        )
    }

    /**
     * The gates a render must pass, as booleans and ids, for the stage-9 launcher tests' failure
     * messages: a generation that stays pending with every worker idle has bailed out of
     * [startRender] or never been requested, and only these flags say which.
     */
    fun describeForTesting(): String {
        val actual = actualRequests.snapshot()
        val recent = recentRequestedKeysOrNull()
        val pendingKeys = pendingCoverageKeys
        // Empty in every shipped build. Present so that an arm-2 run cannot be mistaken for
        // a tile run whose barrier happens to read zero.
        val installer = overlayInstaller?.let { " installer[" + it.describe() + "]" }.orEmpty()
        return "baselineReady=$baselineReady mapLoaded=$mapLoaded hostStopped=$hostStopped " +
            installer +
            "actual[gen=${actual.generation} requested=${actual.requestedCount} " +
            "delivered=${actual.deliveredCount} overflowed=${actual.overflowed} " +
            "barrier=${actual.barrierArmed}] " +
            "pendingZooms=${pendingKeys?.map { it.zoom }?.toSortedSet()} " +
            "recentZooms=${recent?.map { it.zoom }?.toSortedSet()} " +
            "recentUnmasked=${recent?.count { key -> pendingKeys?.contains(key) == false }} " +
            "strandedRestarts=$strandedRestarts " +
            "released=$released renderWork=${renderWork?.generation?.id} " +
            "lastGeneration=$lastGenerationId cameraEpoch=$cameraEpoch " +
            "installTimeout=$installTimeoutGeneration pausedInstallTimeout=$pausedInstallTimeoutGeneration " +
            "retryPosted=$retryPosted terminalPublished=$terminalPublished " +
            "gestureHeld=$gestureHeld gestureSettles=$gestureSettleClock firstCoverLowered=$firstCoverLowered " +
            "coverDeadlineArmed=${coverDeadline != null} " +
            "coordinator[pending=${coordinator.pendingGenerationId} " +
            "installed=${coordinator.installedGenerationId} coverUp=${coordinator.coverUp} " +
            "reason=${coordinator.coverReason} terminal=${coordinator.terminal} " +
            "retry=${coordinator.retryScheduled} trace=${coordinator.recentTransitionsTimed}] " +
            "render=[keys=$lastRenderKeys renderMs=$lastRenderMillis publishMs=$lastPublishMillis " +
            "askedPadding=${coverageProfile.paddingTiles} appliedPadding=$lastAppliedPaddingTiles] " +
            "${stageSummary() ?: "stages=none"} " +
            "prover=${snapshotProver.recentEvents} " +
            "overlays=${overlays.keys} " +
            "target=$targetOverlayGeneration bootstrapOverlay=${bootstrapOverlay != null} " +
            "masks=${masksByGeneration.keys} pendingKeys=${pendingCoverageKeys?.size} " +
            "installedKeys=${installedCoverageKeys?.size} " +
            "recentRequests=${recentRequestedKeysOrNull()?.size} " +
            "provenRequested=${lastProvenRequestedKeys.size} " +
            "proofPlanRefusals=$proofPlanRefusals " +
            "sinceLastRequestMs=${(SystemClock.elapsedRealtimeNanos() - lastRequestAtNanos) / 1_000_000L}"
    }

    private fun requestCurrentViewportIfReady() {
        if (released || !baselineReady || !mapLoaded) return
        coordinator.onCameraIdle()
        afterCoordinatorMutation()
    }

    private fun startRender(generation: FogTileGeneration) {
        if (!isCanonicalGenerationCurrent(generation.id)) return
        assertMainThread()
        if (released || hostStopped || !adapter.isCurrent(generation)) return
        if (nativeCoverNeedsCommit()) {
            ensureNativeCoverCommit()
            return
        }
        if (renderWork?.generation?.id == generation.id || generation.id in masksByGeneration) return
        val preferRequiredMasks = overlayInstaller?.canPrepareFromRequiredMasks == true
        val renderInput = try {
            val coverage = currentCoverageRequest()
                ?: throw IllegalStateException("map projection unavailable")
            val actual = requestedKeysForRender()
            val renderPlan = renderPlanner.plan(coverage)
            // `V03-011` arm 1: the ring a plan ASKED for and the ring it GOT are not always the
            // same, because an unaffordable ring is narrowed rather than refused. Recorded so a
            // measurement can tell a narrowed arm from the arm it was labelled as; without it,
            // ring(1) and ring(2) can collapse onto the same applied width on a large viewport and
            // still be reported as two arms.
            lastAppliedPaddingTiles = renderPlan.appliedPaddingTiles
            val requested = LinkedHashSet<FogTileKey>().apply {
                addAll(renderPlan.keySet)
                addAll(actual)
            }
            if (requested.size > coverageProfile.maxRequestedKeys) {
                throw IllegalStateException("actual request union exceeded bound")
            }
            val required = if (preferRequiredMasks) LinkedHashSet<FogTileKey>().apply {
                addAll(surroundPlanner.plan(coverage).keys)
                addAll(actual)
            } else requested
            val frozenRequested = requested.toSet()
            Triple(coverage, frozenRequested, if (preferRequiredMasks) required.toSet() else frozenRequested)
        } catch (failure: Exception) {
            failGeneration(generation.id, failure)
            return
        } catch (failure: LinkageError) {
            failGeneration(generation.id, failure)
            return
        }
        val (coverage, requested, required) = renderInput
        val work = RenderWork(
            generation = generation,
            coverage = coverage,
            requested = requested,
            required = required,
            preferRequiredMasks = preferRequiredMasks,
            cameraEpoch = cameraEpoch,
            canonicalEpoch = checkNotNull(generationEpochs[generation.id]),
            budget = FogLifecycleBudget(RENDER_TIMEOUT_MILLIS),
        )
        renderWork = work
        launchRender(work)
    }

    /** Runs or resumes one generation-owned render without resetting its active-time budget. */
    private fun launchRender(
        work: RenderWork,
        resumedLease: FogLifecycleBudget.Lease? = null,
    ) {
        assertMainThread()
        if (released || hostStopped || !adapter.isCurrent(work.generation)) return
        val lease = resumedLease ?: work.budget.start(
            owner = work.generation.id,
            cameraEpoch = work.cameraEpoch,
        )
        if (lease == null) {
            if (renderWork === work) renderWork = null
            failGeneration(
                work.generation.id,
                IllegalStateException("canonical render budget expired"),
            )
            return
        }
        work.activeLease = lease
        val job = scope.launch {
            var commitStarted = false
            try {
                val renderStartedAtMillis = SystemClock.elapsedRealtime()
                // Sampled at render start, so a cover the camera raises DURING the render is not
                // read back as a negative idle.
                val coverRaisedBeforeRenderAtNanos = coverRaisedAtNanos
                // Captured by the IO block and read after it returns; withContext orders the two.
                var rasterMillis = 0L
                var prepareMillis = 0L
                val roomWindows = RoomWindowCounter()
                val rasterWork = FogRasterWorkProbe()
                val masks = withTimeout(lease.remainingMillis.coerceAtLeast(1L)) {
                    withContext(Dispatchers.IO + roomWindows + rasterWork) {
                        val rawEnvelope = if (overlayInstaller?.reusesCanonicalPoints == true) {
                            val floorKeys = surroundPlanner.plan(work.coverage).keys
                            val bounds = FogPocMosaic.layout(floorKeys, 256).anchoredNear(work.coverage.center.longitude).bounds
                            runtime.viewportCoordinator.nativeRawReadEnvelope(bounds)
                        } else null
                        val rawPoints = rawEnvelope?.let { envelope ->
                            app.trailveil.data.map.ViewportRawPointMemo(isCurrent = {
                                work.canonicalEpoch % 2L == 0L && work.canonicalEpoch == runtime.canonicalEpoch.value
                            }, requiredCaptureBounds = envelope)
                        }
                        try {
                            withContext(rawPoints ?: kotlin.coroutines.EmptyCoroutineContext) {
                                app.trailveil.map.fog.renderFogWithRequiredMasks(
                                    requested = work.requested,
                                    required = work.required,
                                    preferRequired = work.preferRequiredMasks,
                                    maxTiles = coverageProfile.maxRequestedKeys,
                                    render = { keys ->
                                        val started = SystemClock.elapsedRealtime()
                                        requestedRenderer.render(work.coverage.center, keys).also {
                                            rasterMillis += SystemClock.elapsedRealtime() - started
                                        }
                                    },
                                    prepare = { masks ->
                                      overlayInstaller?.let { installer ->
                                        rawPoints?.seal()
                                        val prepareStartedAtMillis = SystemClock.elapsedRealtime()
                                        val tiles = surroundPlanner.plan(work.coverage).keys.map { key ->
                                            FogMosaicTile(key, checkNotNull(masks[key]))
                                        }
                                        installer.prepare(work.generation.id, work.coverage, tiles)
                                        prepareMillis = SystemClock.elapsedRealtime() - prepareStartedAtMillis
                                        installer.hasPreparedNativeGeometry(work.generation.id)
                                      } ?: false
                                    },
                                )
                            }
                        } finally {
                            if (rawPoints != null) withContext(NonCancellable) { rawPoints.close() }
                        }
                    }
                }
                lastRenderKeys = work.requested.size
                lastRasterWork = "${rasterWork.readNanos.get() / 1_000_000}/" +
                    "${rasterWork.selectionNanos.get() / 1_000_000}/${rasterWork.paintNanos.get() / 1_000_000}"
                lastRenderMillis = SystemClock.elapsedRealtime() - renderStartedAtMillis
                lastRasterMillis = rasterMillis
                lastRasterRoomWindows = roomWindows.count.get()
                lastPrepareMillis = prepareMillis
                intervalGenerations += 1
                intervalRasterMillis += rasterMillis
                intervalPrepareMillis += prepareMillis
                lastCoverGestureMillis = coverRaisedBeforeRenderAtNanos?.let { raised ->
                    lastCameraIdleAtMillis?.let { idleAt -> idleAt - raised / NANOS_PER_MILLISECOND }
                }?.takeIf { it >= 0L }
                // From the cover rising on the move frame to the first byte of render work: the
                // gesture's own remainder, the idle wait and two handler hops. It is inside every
                // cover reading and is not render cost, so it is named apart from it.
                lastCoverToRenderMillis = coverRaisedBeforeRenderAtNanos?.let { raised ->
                    renderStartedAtMillis - raised / NANOS_PER_MILLISECOND
                }?.takeIf { it >= 0L }
                ensureActive()
                if (!isCurrentRender(work, lease)) return@launch

                // A move can raise the native cover while this generation is doing CPU work.
                // Do not publish/attach/reveal through that later fence either.
                if (nativeCoverNeedsCommit()) {
                    ensureNativeCoverCommit()
                    nativeCoverCommitJob?.join()
                    ensureActive()
                    if (!isCurrentRender(work, lease) || nativeCoverNeedsCommit() || coordinator.terminal) return@launch
                }

                // The render budget ends before the adapter commit. ON_STOP can therefore pause
                // only the canonical read; it cannot cancel a commit after the generation's result
                // is ready and leave the adapter/coordinator ownership split. Adapter generation
                // identity still rejects a commit whose generation was cancelled in the meantime.
                if (!work.budget.complete(lease)) return@launch
                work.activeLease = null
                commitStarted = true
                val publishStartedAtMillis = SystemClock.elapsedRealtime()
                val published = withContext(Dispatchers.Default) {
                    if (!isCurrentRenderWork(work)) {
                        null
                    } else {
                        // An installer that never reads PNG tiles gets the currency gate alone: an
                        // empty publish still runs isCurrent, stamps the generation and returns
                        // false for a superseded one, without encoding a byte. Null installer
                        // (every shipped build) keeps the full publish.
                        adapter.publishMasks(
                            work.generation,
                            if (overlayInstaller?.publishesMaskTiles == false) emptyMap() else masks,
                        )
                    }
                }
                lastPublishMillis = SystemClock.elapsedRealtime() - publishStartedAtMillis
                ensureActive()
                if (!isCurrentRenderWork(work)) return@launch
                if (published != true) {
                    failGeneration(
                        work.generation.id,
                        IllegalStateException("canonical publish rejected"),
                    )
                    return@launch
                }
                renderJobs.remove(work.generation.id)
                renderWork = null
                masksByGeneration[work.generation.id] = masks
                coverageByGeneration[work.generation.id] = work.coverage
                pendingCoverageKeys = masks.keys
                publishFogRenderForCompatibility(work.coverage, masks)
                coordinator.onGenerationPublished(work.generation.id)
                scheduleInstallTimeout(work.generation.id)
                afterCoordinatorMutation()
            } catch (timeout: TimeoutCancellationException) {
                // MUST precede the CancellationException clause: TimeoutCancellationException is a
                // CancellationException, but only an active lease may classify expiry as failure.
                // ON_STOP invalidates the lease and preserves its remaining budget instead.
                if (isCurrentRender(work, lease)) {
                    failGeneration(work.generation.id, timeout)
                }
            } catch (_: CancellationException) {
                // Cancellation is lifecycle/release control flow, never a fog-render failure.
            } catch (failure: Throwable) {
                if ((commitStarted && isCurrentRenderWork(work)) || isCurrentRender(work, lease)) {
                    failGeneration(work.generation.id, failure)
                }
            } finally {
                if (work.activeLease == lease) {
                    work.activeLease = null
                    renderJobs.remove(work.generation.id)
                }
            }
        }
        work.job = job
        renderJobs[work.generation.id] = job
    }

    private fun isCurrentRender(
        work: RenderWork,
        lease: FogLifecycleBudget.Lease,
    ): Boolean =
        !released && !hostStopped && renderWork === work && work.activeLease == lease &&
            work.canonicalEpoch == runtime.canonicalEpoch.value &&
            adapter.isCurrent(work.generation) && work.budget.isCurrent(lease)

    private fun isCurrentRenderWork(work: RenderWork): Boolean =
        !released && renderWork === work && adapter.isCurrent(work.generation) &&
            work.canonicalEpoch == runtime.canonicalEpoch.value

    private fun pauseActiveRender() {
        val work = renderWork ?: return
        val lease = work.activeLease ?: return
        val resumeToken = work.budget.pause(lease) ?: return
        work.activeLease = null
        work.pausedLease = resumeToken
        renderJobs.remove(work.generation.id)?.cancel()
        work.job = null
    }

    private fun resumePendingRenderIfNeeded() {
        if (released || hostStopped) return
        val active = renderWork
        if (active != null) {
            val paused = active.pausedLease
            if (paused != null) {
                active.pausedLease = null
                if (!adapter.isCurrent(active.generation)) {
                    active.budget.cancel()
                    renderWork = null
                    return
                }
                val lease = active.budget.resume(paused)
                if (lease == null) {
                    renderWork = null
                    failGeneration(
                        active.generation.id,
                        IllegalStateException("canonical render budget expired while stopped"),
                    )
                } else {
                    launchRender(active, lease)
                }
            }
            return
        }
        val pending = coordinator.pendingGenerationId ?: return
        if (pending in masksByGeneration) return
        generations[pending]?.let(::startRender)
    }

    private fun scheduleDeliveryQuietCheck(generationId: Long) {
        handler.postDelayed(
            {
                if (released || coordinator.pendingGenerationId != generationId) return@postDelayed
                val snapshot = actualRequests.snapshot()
                // The session ends by consumeCompleted, cancel, or a newer begin, each of which
                // clears or replaces the generation. That is this chain's termination condition,
                // now that arming no longer stops it.
                if (snapshot.generation != generationId) return@postDelayed
                if (snapshot.overflowed) {
                    failGeneration(generationId, IllegalStateException("actual request barrier overflow"))
                    return@postDelayed
                }
                val quietForNanos = SystemClock.elapsedRealtimeNanos() - lastRequestAtNanos
                if (snapshot.requestedCount == 0 || quietForNanos < DELIVERY_QUIET_NANOS) {
                    scheduleDeliveryQuietCheck(generationId)
                    return@postDelayed
                }
                // Requests the render never produced masks for can never be delivered, so no
                // barrier over them drains: the camera left the rendered viewport while this
                // install was still draining (stage 9: the launcher's flight to the last recorded
                // point). Restart over the observed request set now that the burst is quiet,
                // instead of waiting for the install timeout, which is terminal on a first install.
                if (strandedRequestCount(generationId) > 0) {
                    restartStrandedGeneration(generationId)
                    return@postDelayed
                }
                if (snapshot.barrierArmed) {
                    // A late actual request invalidates the armed barrier. Keep one bounded poll
                    // alive so that invalidation is observed and the expanded set re-arms.
                    scheduleDeliveryQuietCheck(generationId)
                    return@postDelayed
                }
                actualRequests.armBarrier {
                    handler.post { onActualDeliveryBarrierDrained(generationId) }
                }
                // Poll on regardless of whether this arm succeeded. A later actual request nulls
                // the barrier (FogActualTileRequestSet.recordRequested); stopping here on success
                // left nothing alive to observe that invalidation, so the orphaned barrier could
                // never complete and the generation stranded until the install timeout — which is
                // terminal on a first install. While a barrier stands the branch above simply
                // re-polls; once it is invalidated this arms again over the expanded set.
                scheduleDeliveryQuietCheck(generationId)
            },
            DELIVERY_POLL_MILLIS,
        )
    }

    /** Requests logged since the target overlay attached that the pending render holds no mask for. */
    private fun strandedRequestCount(generationId: Long): Int {
        val masks = masksByGeneration[generationId] ?: return 0
        val recent = recentRequestedKeysOrNull() ?: return 0
        return recent.count { key -> key !in masks }
    }

    /**
     * Abandons the pending generation through the coordinator and carries the observed request
     * set into the restart: `cancelRebuild` rotates the request log, and the log is exactly what
     * the next [startRender] must cover. The rebuild's render is posted, so the carry lands first.
     */
    private fun restartStrandedGeneration(generationId: Long) {
        assertMainThread()
        if (released || coordinator.pendingGenerationId != generationId) return
        val carried = recentRequestedKeysOrNull().orEmpty()
        strandedRestarts += 1
        coordinator.onPendingRenderStranded(generationId)
        synchronized(recentRequestLock) { carried.forEach { key -> recentRequests += key } }
        afterCoordinatorMutation()
    }

    private fun onActualDeliveryBarrierDrained(generationId: Long) {
        assertMainThread()
        if (released || coordinator.pendingGenerationId != generationId) return
        if (!isCanonicalGenerationCurrent(generationId)) return
        val completed = actualRequests.consumeCompleted(generationId) ?: run {
            scheduleDeliveryQuietCheck(generationId)
            return
        }
        lastProvenRequestedKeys = completed
        providers[generationId]?.setCanonicalDeliveryObserver(null)
        deliverAfterNativeCoverCommit(generationId)
        afterCoordinatorMutation()
    }

    /**
     * `V03-011` arm 2: install a generation through the seam, and synthesize the drain it has no
     * way to observe.
     *
     * A hidden `TileOverlay` still requests and draws its tiles - that is the whole pre-render
     * mechanism the delivery barrier watches (see HIDDEN_FOG_TRANSPARENCY). An anchored image has
     * nothing to fetch, so no request is ever logged and no barrier can arm. The gate that
     * replaces it is the one that already follows: the coordinator reveals BEFORE it proves, and
     * holds the cover until the snapshot proof passes.
     *
     * The post is load-bearing, not tidiness. `onGenerationPublished` sets `overlayAttached` only
     * AFTER `attachOverlay` returns, and `onDeliveryBarrierDrained` returns immediately while that
     * flag is false - so a drain synthesized on this turn would be a silent no-op, the install
     * timeout would fire, and a first install classifies that terminal. A strictly later turn is
     * the whole difference between this working and a permanently dead map.
     */
    private fun attachThroughInstaller(
        installer: GoogleFogOverlayInstaller,
        generationId: Long,
    ) {
        if (!installer.demoteExisting()) {
            handler.post {
                failGeneration(generationId, IllegalStateException("old overlay z-order failed"))
            }
            return
        }
        targetOverlayGeneration = generationId
        val coverage = coverageByGeneration[generationId]
        val masks = masksByGeneration[generationId]
        // The floor rectangle, in row-major order, exactly as the compatibility publish
        // builds it: `FogPocMosaic.compose` requires a complete rectangle, and the render
        // key set is the plan UNIONED with the SDK's observed requests, which is not one.
        val floorKeys = coverage?.let {
            try {
                surroundPlanner.plan(it).keys
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (coverage == null || masks == null || floorKeys == null ||
            !masks.keys.containsAll(floorKeys)
        ) {
            handler.post {
                failGeneration(
                    generationId,
                    IllegalStateException("installer attach without a completed render"),
                )
            }
            return
        }
        val tiles = floorKeys.map { key -> FogMosaicTile(key, requireNotNull(masks[key])) }
        val attachStartedAtMillis = SystemClock.elapsedRealtime()
        val attached = installer.attach(generationId, coverage, tiles)
        lastAttachMillis = SystemClock.elapsedRealtime() - attachStartedAtMillis
        if (!attached) {
            handler.post {
                failGeneration(generationId, IllegalStateException("overlay attach failed"))
            }
            return
        }
        // Same position as the tile path: after the replacement is attached and recorded, so an
        // injected fault takes the route a real post-attach failure takes.
        val injectedRejection = installFaultRejectionOrNull()
        if (injectedRejection != null) {
            handler.post { failGeneration(generationId, injectedRejection) }
            return
        }
        handler.post {
            if (released || coordinator.pendingGenerationId != generationId) return@post
            if (!isCanonicalGenerationCurrent(generationId)) return@post
            deliverAfterNativeCoverCommit(generationId)
            afterCoordinatorMutation()
        }
    }

    /**
     * Why the last proof plan came back null. Diagnostic only, read by [describeForTesting].
     *
     * `V03-011` section 15n: on prototype A the prover's retry is the whole of a generation's
     * second chance, because a `GroundOverlay` gives the delivery barrier nothing to observe and
     * the reveal is what starts the proof. When that retry meets a null plan the generation's proof
     * ends rather than re-attempting, and `plan:null` in the prover's trace cannot say which of the
     * five refusals below fired - a camera that moved between attempts and a mask set that is short
     * are different problems with different fixes, and they looked identical.
     */
    private val proofPlanRefusals = LinkedHashMap<String, Int>()

    private fun refuseProofPlan(reason: String): FogSnapshotVisualProbePlan? {
        // A tally rather than the latest, because a refusal that is later followed by a success is
        // exactly the case worth seeing: the generation whose retry died still lost its proof. The
        // reasons carry zoom values, so cap the distinct keys rather than trusting them to be few.
        if (reason in proofPlanRefusals || proofPlanRefusals.size < MAX_PROOF_PLAN_REFUSAL_KINDS) {
            proofPlanRefusals[reason] = (proofPlanRefusals[reason] ?: 0) + 1
        }
        return null
    }

    private suspend fun freshProofPlan(generationId: Long, attempt: Int): FogSnapshotVisualProbePlan? {
        val published = coverageByGeneration[generationId] ?: return refuseProofPlan("noCoverage")
        val coverage = currentCoverageRequest() ?: published
        val allMasks = masksByGeneration[generationId].orEmpty()
        val requiredFloorKeys = try {
            surroundPlanner.plan(coverage).keySet
        } catch (_: IllegalArgumentException) {
            return refuseProofPlan("surroundPlan")
        }
        val actual = recentRequestedKeysOrNull() ?: return refuseProofPlan("requestsOverflowed")
        if (!allMasks.keys.containsAll(requiredFloorKeys)) {
            return refuseProofPlan(
                "floorShort:${requiredFloorKeys.count { key -> key !in allMasks.keys }}" +
                    "/${requiredFloorKeys.size}@z${coverage.floorZoom}",
            )
        }
        if (!allMasks.keys.containsAll(actual)) {
            return refuseProofPlan("actualShort:${actual.count { key -> key !in allMasks.keys }}")
        }
        val masks = allMasks.filterKeys { key -> key.zoom == coverage.floorZoom }
        if (masks.isEmpty()) {
            return refuseProofPlan(
                "noMasksAtZoom:z${coverage.floorZoom}have${allMasks.keys.map { it.zoom }.toSortedSet()}",
            )
        }
        val zones = try {
            exclusionZonesForProof()
        } catch (_: Exception) {
            listOf(wholeWorldFogProbeExclusionZone())
        } catch (_: LinkageError) {
            listOf(wholeWorldFogProbeExclusionZone())
        }
        val planStartedAtMillis = SystemClock.elapsedRealtime()
        // SDK state and generation maps are captured on main. The copied map, value objects and
        // published read-only masks are the only worker inputs; no Google API runs on Default.
        val capturedZones = zones.toList()
        // Every attempt still validates current SDK coverage/requests/zones above. Only the pure
        // computation can be reused, with exact viewport values and identical mask objects.
        val bank = app.trailveil.map.fog.FogProbeCandidateBank.forAttempt(attempt)
        val cached = proofPlanMemo.find(generationId, coverage, masks, capturedZones, bank)
        val plan = cached ?: withContext(Dispatchers.Default) {
            val workerJob = checkNotNull(currentCoroutineContext()[Job])
            probePlanner.plan(coverage, masks, exclusionZones = capturedZones,
                candidateBank = bank,
                checkActive = { workerJob.ensureActive() })
        }.also { computed ->
            proofPlanMemo.remember(generationId, coverage, masks, capturedZones, computed, bank)
        }
        return plan.also {
            proofPlanMillisAccumulated += SystemClock.elapsedRealtime() - planStartedAtMillis
            proofPlanAttemptsAccumulated += 1
        }
    }

    private fun scheduleInstallTimeout(generationId: Long) {
        cancelInstallTimeout()
        // Same rule as armCoverDeadline: a stopped renderer issues no tile requests, so a barrier
        // armed now can never drain and this deadline would have no way to succeed. Cancelling an
        // already-armed timeout in onHostStopped is not sufficient on its own — a render that was
        // in flight at ON_STOP still completes on Dispatchers.IO and publishes while stopped, which
        // used to arm a fresh unsatisfiable 15 s deadline and terminate the first install. The
        // coordinator keeps the generation pending, so onHostStarted arms it on return. Remember
        // that this generation reached the post-publish install phase: re-arming every pending
        // generation would incorrectly start this clock while a paused canonical render still has
        // its own remaining active-time budget.
        if (hostStopped) {
            pausedInstallTimeoutGeneration = generationId
            return
        }
        val timeout = Runnable {
            if (installTimeoutGeneration != generationId) return@Runnable
            installTimeout = null
            installTimeoutGeneration = null
            if (!released && coordinator.pendingGenerationId == generationId) {
                coordinator.onInstallTimeout(generationId)
                afterCoordinatorMutation()
            }
        }
        installTimeout = timeout
        installTimeoutGeneration = generationId
        handler.postDelayed(timeout, INSTALL_TIMEOUT_MILLIS)
    }

    private fun cancelInstallTimeout(generationId: Long? = null) {
        if (generationId != null) {
            val ownsActive = installTimeoutGeneration == generationId
            val ownsPaused = pausedInstallTimeoutGeneration == generationId
            if (!ownsActive && !ownsPaused) return
            if (ownsPaused) pausedInstallTimeoutGeneration = null
            if (!ownsActive) return
        } else {
            pausedInstallTimeoutGeneration = null
        }
        installTimeout?.let(handler::removeCallbacks)
        installTimeout = null
        installTimeoutGeneration = null
    }

    /**
     * The failure this attach must report, or `null` when no fault was installed or the installed
     * fault let this attach through.
     *
     * `Exception`, never `Throwable`: a fault is test code, and a JUnit `AssertionError` thrown out
     * of it is a broken test, not a fog install failure. Swallowing one into the retry path would
     * bury the assertion that actually failed behind a fog-failure breadcrumb.
     */
    private fun installFaultRejectionOrNull(): Throwable? {
        val fault = installFaultForTesting ?: return null
        return try {
            fault()
            null
        } catch (rejection: Exception) {
            rejection
        }
    }

    private fun failGeneration(generationId: Long, failure: Throwable) {
        if (released) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { failGeneration(generationId, failure) }
            return
        }
        onFogFailure(failure)
        coordinator.onGenerationRenderFailed(generationId)
        afterCoordinatorMutation()
    }

    private fun failRuntime(failure: Throwable) {
        if (released) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { failRuntime(failure) }
            return
        }
        onFogFailure(failure)
        coordinator.onFogRuntimeFailure()
        afterCoordinatorMutation()
    }

    private fun afterCoordinatorMutation() {
        if (released) return
        val installed = coordinator.installedGenerationId
        installedCoverageKeys = installed?.let(masksByGeneration::get)?.keys
        pendingCoverageKeys = coordinator.pendingGenerationId
            ?.let(masksByGeneration::get)
            ?.keys
        if (installed != null) {
            if (bootstrapOverlay != null) {
                val removed = bootstrapOverlay?.removeSafely() == true
                if (!removed) {
                    coordinator.onOverlayCleanupFailure()
                } else {
                    bootstrapOverlay = null
                    bootstrapProvider?.releaseObservers()
                    bootstrapProvider = null
                }
            }
        }
        val pending = coordinator.pendingGenerationId
        if (targetOverlayGeneration != null && targetOverlayGeneration != pending) {
            targetOverlayGeneration = null
        }
        installTimeoutGeneration?.let { owner ->
            if (pending != owner) cancelInstallTimeout(owner)
        }
        pausedInstallTimeoutGeneration?.let { owner ->
            if (pending != owner) cancelInstallTimeout(owner)
        }
        // V02-012 design 2: on the cover's rising edge every overlay is hidden beneath it, so the
        // interval reads as uniform fog rather than fog stacked on fog. A generation revealed
        // beneath the raised cover for its verification stays visible: that edge has passed.
        val coverRose = coordinator.coverUp && !coverWasUp
        if (coverRose) latchNativeRetirement()
        if (!coordinator.coverUp) invalidateNativeCoverCommit()
        coverWasUp = coordinator.coverUp
        val publishTerminal = coordinator.terminal && !terminalPublished
        if (publishTerminal) {
            // Stop before either host callback; reserving the notification also prevents a
            // synchronous state observer from publishing the same terminal transition twice.
            snapshotProver.release()
            proofPlanMemo.clear()
            terminalPublished = true
        }
        publishCoverInterval(coordinator.coverUp)
        onStateChanged(state())
        // The host adds its ViewOverlay synchronously; only now may retirement be requested.
        if (coverRose || nativeCoverNeedsCommit()) hideOverlaysBeneathCover()
        if (publishTerminal) onTerminalFailure()
        if (coordinator.retryScheduled && !retryPosted) {
            retryPosted = true
            handler.postDelayed(
                {
                    retryPosted = false
                    if (!released && baselineReady) {
                        coordinator.onRetryFogOperation()
                        afterCoordinatorMutation()
                    }
                },
                RETRY_FOG_MILLIS,
            )
        }
    }

    /**
     * Whether an installer arm's anchored image is still FINE ENOUGH for the camera, not just big
     * enough for it.
     *
     * **The owner found this by zooming out and back in: revealed segments collapsed to a few dots
     * on the way out and stayed dots on the way back.** An installer arm publishes one bitmap
     * anchored to a ground rectangle, so zooming in does not resample it - the SDK simply stretches
     * what is there. A walk that rendered to two mask pixels at the zoom the image was built at is
     * two mask pixels for ever, magnified into blobs, and the explored ground around it stays
     * fogged.
     *
     * [GoogleFogOverlayInstaller.covers] cannot answer this and should not have to: it is handed
     * the visible corners and nothing else, so it can only test containment. Containment is the
     * whole answer on the extent axis and no answer at all on the resolution axis, and zooming in
     * is the one move that changes the second without changing the first.
     *
     * The tile path never had this defect because it does not need this method - a coverage key
     * carries the zoom it was planned at, so no tile ring survives an integer zoom step and a
     * zoom-in re-renders by construction. This restores that same rule, at that same granularity,
     * for the arms that answer through an installer instead: an image built at floor zoom 12 covers
     * anything at 12 or below, and nothing above it.
     *
     * Zooming OUT is deliberately not refused here. A finer image than the camera needs is correct
     * to keep, and a viewport that has grown past the rectangle is exactly what `covers` already
     * catches.
     *
     * A generation with no recorded coverage yields to `covers` rather than inventing a refusal.
     * This method exists to add the zoom term; where there is no planned zoom to compare against
     * there is no zoom term to add, and refusing would raise the cover on a question nobody asked.
     */
    private fun installerStillResolves(generationId: Long?): Boolean {
        if (overlayInstaller?.requiresRasterResolution(generationId) == false) return true
        val planned = coverageByGeneration[generationId ?: return false] ?: return true
        val current = currentCoverageRequest() ?: return true
        return current.floorZoom <= planned.floorZoom
    }

    private fun insideCoverage(coverageKeys: Set<FogTileKey>?): Boolean {
        if (released) return false
        val current = currentCoverageRequest() ?: return false
        val available = coverageKeys ?: return false
        val actual = recentRequestedKeysOrNull() ?: return false
        return fogViewportCoveredByPublishedTiles(
            viewport = current,
            recentActualRequests = actual,
            publishedKeys = available,
            planner = surroundPlanner,
        )
    }

    /**
     * The cover's wall-clock bound: [MAXIMUM_COVER_MILLIS] from arming to terminal failure.
     *
     * It bounds the surface's own work - rebuild, proof, retries - and not the person's. Armed on
     * the cover's rising edge; once the first cover has lowered on a passed proof, cancelled when a
     * gesture takes the camera and re-armed in full when that gesture settles ([onCameraIdle],
     * which is the only arm the host's own net restarts on); re-armed in full when the host
     * resumes. A cover that
     * rises under a held gesture is armed by the idle instead, and the runnable refuses to fire
     * under a gesture the SDK never reported settling. Programmed flights keep the running window:
     * nothing a person does is being waited for there. The first-composition cover is outside the
     * rule on purpose: nothing has proven yet, so 20 s from the first visible cover stands.
     *
     * Before this rule (owner decision 2026-09-10) the window ran from the rising edge whatever the
     * finger did. Pauses shorter than one render+proof cycle never lower the cover, so a repeated
     * pan kept ONE cover up for the whole exploration and a 19.6 s phone reading came within
     * 424 ms of tearing the map down with the person's own hand as the only delay.
     */
    private fun armCoverDeadline() {
        cancelCoverDeadline()
        if (runtime.canonicalEpoch.value % 2L != 0L) return
        val deadline = Runnable {
            coverDeadline = null
            if (!released && runtime.canonicalEpoch.value % 2L == 0L &&
                !gestureHeld && coordinator.coverUp && !coordinator.terminal) {
                coordinator.onCoverDeadlineExceeded()
                afterCoordinatorMutation()
            }
        }
        coverDeadline = deadline
        handler.postDelayed(deadline, MAXIMUM_COVER_MILLIS)
    }

    private fun cancelCoverDeadline() {
        coverDeadline?.let(handler::removeCallbacks)
        coverDeadline = null
    }

    private fun hideOverlaysBeneathCover() {
        // Unlike a state publication, this call explicitly requests retirement (including a
        // canonical reset while the cover was already up). Capture its native owner too.
        latchNativeRetirement()
        if (nativeCoverNeedsCommit()) {
            ensureNativeCoverCommit()
            return
        }
        (overlays.values + listOfNotNull(bootstrapOverlay)).forEach { overlay ->
            overlay.setTransparencySafely(HIDDEN_FOG_TRANSPARENCY)
        }
        // The installer arms' layers as well: left beneath the cover they read as a second coat
        // of fog ((41,52,58) against the fog's (68,88,97) on the AVD), which the owner saw as a
        // darker cover. See GoogleFogOverlayInstaller.hideBeneathCover.
        overlayInstaller?.hideBeneathCover()
    }

    private fun nativeCoverNeedsCommit(): Boolean = coordinator.coverUp && !nativeCoverCommitted && nativeRetirementOwner != null

    private fun latchNativeRetirement() {
        if (!coordinator.coverUp || nativeCoverCommitted || nativeRetirementOwner != null) return
        val installed = coordinator.installedGenerationId ?: return
        if (overlayInstaller?.requiresCoverForHandover(installed) == true) nativeRetirementOwner = installed
    }

    private fun deliverAfterNativeCoverCommit(generationId: Long) {
        if (released || coordinator.pendingGenerationId != generationId || !isCanonicalGenerationCurrent(generationId)) {
            if (deferredDeliveryGeneration == generationId) deferredDeliveryGeneration = null
            return
        }
        if (hostStopped || nativeCoverNeedsCommit()) {
            deferredDeliveryGeneration = generationId
            ensureNativeCoverCommit()
            return
        }
        deferredDeliveryGeneration = null
        coordinator.onDeliveryBarrierDrained(generationId)
    }

    private fun invalidateNativeCoverCommit(preserveCommittedCover: Boolean = false) {
        nativeCoverEpoch += 1L
        nativeCoverCommitJob?.cancel()
        nativeCoverCommitJob = null
        if (!preserveCommittedCover) {
            nativeCoverCommitted = false
            nativeRetirementOwner = null
        }
    }

    private fun ensureNativeCoverCommit() {
        if (released || hostStopped || coordinator.terminal || !nativeCoverNeedsCommit() || nativeCoverCommitJob != null) return
        val epoch = nativeCoverEpoch
        val canonicalEpoch = runtime.canonicalEpoch.value
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val committed = try {
                withTimeout(1_000L) { awaitCoverCommitted() }
            } catch (_: TimeoutCancellationException) {
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (released || hostStopped || nativeCoverEpoch != epoch || runtime.canonicalEpoch.value != canonicalEpoch ||
                !coordinator.coverUp || coordinator.terminal) return@launch
            if (!committed) {
                // Unsupported rendering or a missing frame is bounded failure. Keep the old
                // native polygons, keep the cover requested, and let the host replace the map.
                onFogFailure(IllegalStateException("native handover cover frame not committed"))
                coordinator.onCoverDeadlineExceeded()
                afterCoordinatorMutation()
                return@launch
            }
            nativeCoverCommitted = true
            hideOverlaysBeneathCover()
            deferredDeliveryGeneration?.let(::deliverAfterNativeCoverCommit)
            afterCoordinatorMutation()
            resumePendingRenderIfNeeded()
        }
        nativeCoverCommitJob = job
        job.invokeOnCompletion {
            if (nativeCoverCommitJob === job) nativeCoverCommitJob = null
        }
        // An installed-generation re-proof can request hiding directly from its callback,
        // before afterCoordinatorMutation. Every entry must publish the real host cover first.
        publishCoverInterval(coordinator.coverUp)
        onStateChanged(state())
        job.start()
    }

    private fun publishCoverInterval(coverUp: Boolean) {
        if (runtime.canonicalEpoch.value % 2L != 0L) cancelCoverDeadline()
        val now = SystemClock.elapsedRealtimeNanos()
        if (coverUp && !lastPublishedCoverUp) {
            coverRaisedAtNanos = now
            intervalGenerations = 0
            intervalRasterMillis = 0L
            intervalPrepareMillis = 0L
            intervalProofMillis = 0L
            intervalProofAttempts = 0
            // A cover that rises under a held gesture is armed by the idle that ends the gesture.
            if (!hostStopped && !gestureHeld) armCoverDeadline()
        } else if (!coverUp && lastPublishedCoverUp) {
            cancelCoverDeadline()
            // A cover lowers on a passed proof and nothing else; the gesture rule waits for this.
            firstCoverLowered = true
            revealedBeneathCoverAtNanos?.let { revealed ->
                lastVerificationHoldMillis = (now - revealed) / NANOS_PER_MILLISECOND
                maximumVerificationHoldMillis = maxOf(maximumVerificationHoldMillis, requireNotNull(lastVerificationHoldMillis))
            }
            revealedBeneathCoverAtNanos = null
            val raised = coverRaisedAtNanos
            if (raised != null) {
                lastCoverIntervalMillis = (now - raised) / NANOS_PER_MILLISECOND
                maximumCoverIntervalMillis = maxOf(
                    maximumCoverIntervalMillis,
                    requireNotNull(lastCoverIntervalMillis),
                )
            }
            coveredStageSummary = stageSummary()
            coveredSurfaceDescription = overlayInstaller?.describe()
            coverRaisedAtNanos = null
        }
        lastPublishedCoverUp = coverUp
    }

    private fun state() = GoogleCanonicalFogState(
        coverUp = coordinator.coverUp,
        coverReason = coordinator.coverReason,
        installedGeneration = coordinator.installedGenerationId,
        pendingGeneration = coordinator.pendingGenerationId,
        terminal = coordinator.terminal,
        retryScheduled = coordinator.retryScheduled,
        lastCoverIntervalMillis = lastCoverIntervalMillis,
        maximumCoverIntervalMillis = maximumCoverIntervalMillis,
        // Same rule as stageSummary: the installer line beside a cover interval describes the
        // generation that lowered that cover, not whichever generation attached last.
        surfaceDescription = coveredSurfaceDescription ?: overlayInstaller?.describe(),
        lastVerificationHoldMillis = lastVerificationHoldMillis,
        maximumVerificationHoldMillis = maximumVerificationHoldMillis,
        stageSummary = coveredStageSummary ?: stageSummary(),
        gestureHeld = gestureHeld,
        gestureSettleClock = gestureSettleClock,
        canonicalReplacementInProgress = runtime.canonicalEpoch.value % 2L != 0L,
    )

    /**
     * One label for the last generation's stages, or null before any render. Milliseconds and
     * counts only. `idle` is cover-rise to render start; `raster` and `prepare` split renderMs;
     * `room` is how many raster batches had to read Room (0 = every tile came from a cache);
     * `hold` (reveal-beneath-cover to verdict) equalled `proof` on every reading and is not printed;
     * `plan` is the proof planner's total across attempts with the attempt count; `proof` is the
     * whole verification.
     */
    private fun stageSummary(): String? {
        val raster = lastRasterMillis ?: return null
        // The interval totals and the prover digest lead. The badge is capped at ten lines and a
        // phone-width badge ellipsises the tail, and a cover that outlived one generation is
        // explained by `gens`, the attempt total and the digest - never by the last generation's
        // own clocks (a 19.6 s phone reading lost exactly those fields to the cap).
        return "sum[gens=$intervalGenerations raster=$intervalRasterMillis prepare=$intervalPrepareMillis " +
            "proof=$intervalProofMillis/$intervalProofAttempts] " +
            proofNotes() + " " +
            "stages[idle=${lastCoverToRenderMillis ?: "-"} gesture=${lastCoverGestureMillis ?: "-"} " +
            "raster=$raster room=${lastRasterRoomWindows ?: "-"} " +
            "prepare=${lastPrepareMillis ?: "-"} publish=${lastPublishMillis ?: "-"} " +
            "attach=${lastAttachMillis ?: "-"} plan=${lastProofPlanMillis ?: "-"}/$lastProofPlanAttempts " +
            "proof=${lastProofMillis ?: "-"} keys=${lastRenderKeys ?: "-"} rasterWork=${lastRasterWork ?: "-"}]"
    }

    /**
     * Why the proofs went the way they did: the plan-refusal tally (reason -> count, reasons carry
     * a zoom and a key count, never a coordinate) and the prover's recent event names with their
     * timestamps stripped. Diagnostic text for the harness badge; nothing reads it back.
     */
    private fun proofNotes(): String {
        val refusals = proofPlanRefusals.entries.joinToString(",") { (reason, count) -> "$reason=$count" }
        // Run-length digest of the prover's recent event NAMES: `eval:false` keeps its verdict,
        // everything else is cut at the first ':'; consecutive repeats collapse to `name×n`. Per-tile
        // colour dumps and the colour window are gates-tag material, not badge text.
        val names = snapshotProver.recentEvents.asSequence()
            .map { event -> event.substringBefore('@') }
            .filterNot { event -> event.startsWith("tiles:") || event.startsWith("window=") || event == "retry" }
            .map { event -> if (event.startsWith("eval:")) event.split(':').take(2).joinToString(":") else event.substringBefore(':') }
            .toList().takeLast(PROOF_NOTE_EVENTS)
        val digest = StringBuilder()
        var index = 0
        while (index < names.size) {
            var run = 1
            while (index + run < names.size && names[index + run] == names[index]) run += 1
            if (digest.isNotEmpty()) digest.append(',')
            digest.append(names[index])
            if (run > 1) digest.append('\u00d7').append(run)
            index += run
        }
        return (if (refusals.isEmpty()) "" else "refusals[$refusals] ") + "prover[$digest]"
    }

    private fun requestedKeysForRender(): Set<FogTileKey> = synchronized(recentRequestLock) {
        if (recentRequestsOverflowed) {
            throw IllegalStateException("renderer request log exceeded bound")
        }
        if (recentRequests.isNotEmpty()) LinkedHashSet(recentRequests) else lastProvenRequestedKeys
    }

    private fun recentRequestedKeysOrNull(): Set<FogTileKey>? = synchronized(recentRequestLock) {
        if (recentRequestsOverflowed) null else LinkedHashSet(recentRequests)
    }

    private fun clearRecentRequests() = synchronized(recentRequestLock) {
        recentRequests.clear()
        recentRequestsOverflowed = false
        lastRequestAtNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun currentCoverageRequest(): FogViewportCoverageRequest? = try {
        val camera = map.cameraPosition
        val visible = map.projection.visibleRegion
        FogViewportCoverageRequest(
            center = camera.target.toFogPoint(),
            floorZoom = kotlin.math.floor(camera.zoom.toDouble()).toInt().coerceIn(0, 22),
            nearLeft = visible.nearLeft.toFogPoint(),
            farLeft = visible.farLeft.toFogPoint(),
            farRight = visible.farRight.toFogPoint(),
            nearRight = visible.nearRight.toFogPoint(),
        )
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    /** The camera's visible quad, for an installer answering the surround geometrically. */
    private fun visibleCornersOrEmpty(): List<app.trailveil.map.fog.GeoPoint> =
        currentCoverageRequest()?.visibleCorners().orEmpty()

    private fun createProvider(targetGeneration: Long?): GoogleFogTileProvider =
        GoogleFogTileProvider(
            adapter = adapter,
            acceptedGeneration = targetGeneration ?: BOOTSTRAP_PLACEHOLDER_GENERATION,
        ).also { tileProvider ->
            tileProvider.setTileRequestObserver { x, y, zoom, atNanos ->
                val key = adapter.normalizeKey(x, y, zoom)
                    ?: return@setTileRequestObserver
                val providerGeneration = targetGeneration
                if (
                    providerGeneration != null &&
                    providerGeneration == targetOverlayGeneration
                ) {
                    val tracked = actualRequests.recordRequested(providerGeneration, key)
                    recordRecentRequest(key)
                    if (tracked) lastRequestAtNanos = atNanos
                } else if (targetOverlayGeneration == null) {
                    // With no target session, the installed/bootstrap provider supplies the
                    // movement request log for the next render. During a handover, old/bootstrap
                    // callbacks cannot inflate the target proof inputs or overflow its bound.
                    recordRecentRequest(key)
                }
            }
            if (targetGeneration != null) {
                tileProvider.setCanonicalDeliveryObserver { generation, key ->
                    if (generation == targetGeneration) {
                        actualRequests.recordDelivered(targetGeneration, key)
                    }
                }
            }
        }

    private fun recordRecentRequest(key: FogTileKey) = synchronized(recentRequestLock) {
        if (key !in recentRequests &&
            recentRequests.size >= coverageProfile.maxRequestedKeys
        ) {
            recentRequestsOverflowed = true
        } else {
            recentRequests += key
        }
    }

    private fun GoogleFogTileProvider.releaseObservers() {
        setCanonicalDeliveryObserver(null)
        setTileRequestObserver(null)
    }

    private fun demoteExistingOverlays(): Boolean {
        val existing = overlays.values + listOfNotNull(bootstrapOverlay)
        return existing.all { overlay ->
            try {
                overlay.zIndex = OLD_OVERLAY_Z
                true
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
        }
    }

    private fun addOverlay(
        tileProvider: GoogleFogTileProvider,
        zIndex: Float,
        transparency: Float,
    ): TileOverlay? = try {
        map.addTileOverlay(
            TileOverlayOptions()
                .tileProvider(tileProvider)
                .fadeIn(false)
                .transparency(transparency)
                .zIndex(zIndex),
        )
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    /**
     * V02-012: display opacity of a fog overlay. Tiles stay fully opaque PNGs; the SDK-side
     * transparency hides a pre-rendering overlay or shows a revealed one at the shared fog
     * opacity. A thrown SDK error leaves the overlay as it was; the screen verification that
     * follows every reveal is what catches a reveal that did not take.
     */
    private fun TileOverlay.setTransparencySafely(value: Float): Boolean =
        try {
            transparency = value
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }

    private fun TileOverlay.removeSafely(): Boolean =
        try {
            remove()
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }

    private fun publishFogRenderForCompatibility(
        coverage: FogViewportCoverageRequest,
        masks: Map<FogTileKey, FogPixelMask>,
    ) {
        val callback = onFogRendered ?: return
        val floorKeys = surroundPlanner.plan(coverage).keys
        if (!masks.keys.containsAll(floorKeys)) return
        val tiles = floorKeys.map { key -> FogMosaicTile(key, requireNotNull(masks[key])) }
        callback(
            FogViewportRender(
                request = FogViewportRequest(coverage.center, coverage.floorZoom.toDouble()),
                keys = floorKeys,
                queryBounds = null,
                presentation = FogPocMosaic.compose(tiles),
            ),
        )
    }

    private fun Int.toFogReason(): FogCameraMoveReason = when (this) {
        GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE -> FogCameraMoveReason.GESTURE
        GoogleMap.OnCameraMoveStartedListener.REASON_API_ANIMATION -> FogCameraMoveReason.API_ANIMATION
        else -> FogCameraMoveReason.DEVELOPER
    }

    private fun LatLng.toFogPoint() = app.trailveil.map.fog.GeoPoint(latitude, longitude)

    private fun assertMainThread() {
        if (BuildConfig.DEBUG) {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "canonical fog binding must run on the main thread"
            }
        }
    }

    private companion object {
        /** Distinct [refuseProofPlan] reasons kept; the reasons carry zoom values. */
        const val MAX_PROOF_PLAN_REFUSAL_KINDS = 8

        const val DELIVERY_POLL_MILLIS = 50L
        const val DELIVERY_QUIET_NANOS = 100L * 1_000_000L
        const val RENDER_TIMEOUT_MILLIS = 15_000L
        const val INSTALL_TIMEOUT_MILLIS = 15_000L
        const val SYNCHRONIZATION_TIMEOUT_MILLIS = 15_000L
        const val SYNCHRONIZATION_RETRY_MILLIS = 1_000L
        const val RETRY_FOG_MILLIS = 1_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_COVER_MILLIS = 20_000L

        /** How many of the prover's most recent events the badge's stage line quotes. */
        private const val PROOF_NOTE_EVENTS = 40

        const val OLD_OVERLAY_Z = 10F
        const val NEW_OVERLAY_Z = 20F
        const val BOOTSTRAP_PLACEHOLDER_GENERATION = Long.MIN_VALUE

        /**
         * V02-012 design 2: an overlay that pre-renders (attached, delivering) or waits beneath
         * the cover is fully transparent; the SDK still requests and draws its tiles.
         */
        const val HIDDEN_FOG_TRANSPARENCY = 1F

        /**
         * V02-012 (owner decision 2026-09-06): a revealed overlay is shown at the same opacity as
         * the other provider's fog, `FogRenderStyle.fogAlpha` / 255 (184 / 255), so the basemap
         * reads faintly through unexplored ground on both providers.
         */
        val VISIBLE_FOG_TRANSPARENCY: Float = 1F - FogRenderStyle().fogAlpha / 255F
    }
}
