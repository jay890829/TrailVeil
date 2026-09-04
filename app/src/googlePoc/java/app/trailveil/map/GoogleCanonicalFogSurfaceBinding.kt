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
import app.trailveil.map.fog.FogMosaicTile
import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogProbeExclusionZone
import app.trailveil.map.fog.FogRequestedTileWindowRenderer
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogSnapshotPort
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
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
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = FogTileProviderAdapter()
    private val actualRequests = FogActualTileRequestSet()
    private val coveragePlanner = FogViewportCoveragePlanner()
    private val requestedRenderer = FogRequestedTileWindowRenderer(
        subrenderer = FogViewportBatchSubrenderer { request, keys ->
            runtime.viewportCoordinator.renderTiles(request, keys)
        },
    )
    private val probePlanner = FogSnapshotVisualProbePlanner()
    private val synchronizationPolicy = FogSynchronizationRenderPolicy()
    private val generations = LinkedHashMap<Long, FogTileGeneration>()
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
    private var bootstrapProvider: GoogleFogTileProvider? = null
    private var pendingCoverageKeys: Set<FogTileKey>? = null
    private var installedCoverageKeys: Set<FogTileKey>? = null
    private var baselineReady = false
    private var mapLoaded = false
    @Volatile private var hostStopped = false
    @Volatile private var released = false
    private var terminalPublished = false
    private var retryPosted = false
    private var installTimeout: Runnable? = null
    private var installTimeoutGeneration: Long? = null
    private var pausedInstallTimeoutGeneration: Long? = null
    private var coverDeadline: Runnable? = null
    private var coverRaisedAtNanos: Long? = null
    private var lastCoverIntervalMillis: Long? = null
    private var maximumCoverIntervalMillis = 0L
    private var lastPublishedCoverUp = false
    private var lastGenerationId = 0L
    private var cameraEpoch = 0L
    /** Restarts through [restartStrandedGeneration]; a count for the failure messages. */
    private var strandedRestarts = 0
    @Volatile private var renderWork: RenderWork? = null

    /** One generation-owned render budget, retained across a host stop. */
    private class RenderWork(
        val generation: FogTileGeneration,
        val coverage: FogViewportCoverageRequest,
        val requested: Set<FogTileKey>,
        val cameraEpoch: Long,
        val budget: FogLifecycleBudget,
    ) {
        @Volatile var activeLease: FogLifecycleBudget.Lease? = null
        @Volatile var pausedLease: FogLifecycleBudget.Lease? = null
        @Volatile var job: Job? = null
    }

    private lateinit var coordinator: FogOverlaySurfaceCoordinator
    private val snapshotProver = GoogleFogSnapshotProver(
        map = map,
        planForAttempt = ::freshProofPlan,
        cameraEpoch = { cameraEpoch },
        onProofObserved = onProofObserved,
        hostStopped = { hostStopped },
        onUnprovablePlan = onUnprovableProofPlan,
    )

    private val overlayPort = object : FogOverlayPort {
        override fun beginRebuild(handover: Boolean, paletteRotation: Boolean): Long {
            assertMainThread()
            val generation = if (handover) {
                adapter.beginHandoverGeneration()
            } else {
                adapter.beginGeneration()
            }
            lastGenerationId = generation.id
            generations[generation.id] = generation
            handler.post { if (!released) startRender(generation) }
            return generation.id
        }

        override fun attachOverlay(generationId: Long) {
            assertMainThread()
            if (released || generationId !in generations) return
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
            val overlay = addOverlay(targetProvider, NEW_OVERLAY_Z)
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

        override fun removeOverlay(generationId: Long): Boolean {
            assertMainThread()
            val overlay = overlays[generationId]
            if (overlay != null && !overlay.removeSafely()) return false
            overlays.remove(generationId)
            providers.remove(generationId)?.releaseObservers()
            generations.remove(generationId)
            masksByGeneration.remove(generationId)
            coverageByGeneration.remove(generationId)
            return true
        }

        override fun clearTileCache() {
            error("production fog refresh must never call clearTileCache")
        }

        override fun cancelRebuild(generationId: Long) {
            assertMainThread()
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
            snapshotProver.prove(generationId) { passed ->
                if (released) return@prove
                onResult(passed)
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
            return insideCoverage(installedCoverageKeys)
        }

        override fun insidePendingSurround(): Boolean {
            // Completion asks about the generation it is just proving, before the coordinator
            // swaps the installed-generation identity. Movement never uses this pending read.
            return insideCoverage(pendingCoverageKeys ?: installedCoverageKeys)
        }
    }

    init {
        coordinator = FogOverlaySurfaceCoordinator(overlayPort, snapshotPort, cameraPort)
        bootstrapProvider = createProvider(targetGeneration = null)
        bootstrapOverlay = addOverlay(requireNotNull(bootstrapProvider), OLD_OVERLAY_Z)
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
            // Re-arm a full window rather than resuming a partly-elapsed one: the time spent
            // stopped was time the surface had no way to make progress, so charging it against
            // the deadline would punish the user for backgrounding the app.
            if (coordinator.coverUp && !coordinator.terminal) armCoverDeadline()
            val pausedInstall = pausedInstallTimeoutGeneration
            pausedInstallTimeoutGeneration = null
            pausedInstall?.let(::scheduleInstallTimeout)
        }
        if (!baselineReady) return
        resumePendingRenderIfNeeded()
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
        clearRecentRequests()
        armPaletteRotationIfNeeded()
        coordinator.onCameraMoveStarted(reason.toFogReason())
        afterCoordinatorMutation()
    }

    fun onCameraMoveFrame() {
        assertMainThread()
        if (released) return
        cameraEpoch += 1L
        coordinator.onCameraMoveFrame()
        afterCoordinatorMutation()
    }

    fun onCameraIdle() {
        assertMainThread()
        if (released || !baselineReady || !mapLoaded) return
        cameraEpoch += 1L
        armPaletteRotationIfNeeded()
        coordinator.onCameraIdle()
        afterCoordinatorMutation()
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
    }

    fun release() {
        assertMainThread()
        if (released) return
        released = true
        cancelInstallTimeout()
        coverDeadline?.let(handler::removeCallbacks)
        coverDeadline = null
        handler.removeCallbacksAndMessages(null)
        snapshotProver.release()
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
        overlays.values.forEach { overlay -> overlay.removeSafely() }
        overlays.clear()
        bootstrapOverlay?.removeSafely()
        bootstrapOverlay = null
    }

    private fun startSynchronization() {
        scope.launch {
            try {
                val baseline = withTimeout(SYNCHRONIZATION_TIMEOUT_MILLIS) {
                    withContext(Dispatchers.IO) { runtime.changeSynchronizer.synchronizeTo() }
                }
                ensureActive()
                if (released) return@launch
                synchronizationPolicy.onBaselineSynchronized(baseline)
                baselineReady = true
                requestCurrentViewportIfReady()
                runtime.pointChanges.revisionsAfter(baseline.cursor).collect { revision ->
                    val update = withTimeout(SYNCHRONIZATION_TIMEOUT_MILLIS) {
                        withContext(Dispatchers.IO) {
                            runtime.changeSynchronizer.synchronizeTo(revision.latestCursor)
                        }
                    }
                    ensureActive()
                    if (
                        synchronizationPolicy.onRevisionSynchronized(update) ==
                        FogSynchronizationRenderDecision.REFRESH_CURRENT_CAMERA
                    ) {
                        coordinator.onCanonicalRefreshRequired()
                        armPaletteRotationIfNeeded()
                        coordinator.onCameraIdle()
                        afterCoordinatorMutation()
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
        return "baselineReady=$baselineReady mapLoaded=$mapLoaded hostStopped=$hostStopped " +
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
            "coordinator[pending=${coordinator.pendingGenerationId} " +
            "installed=${coordinator.installedGenerationId} coverUp=${coordinator.coverUp} " +
            "reason=${coordinator.coverReason} terminal=${coordinator.terminal} " +
            "retry=${coordinator.retryScheduled}] overlays=${overlays.keys} " +
            "target=$targetOverlayGeneration bootstrapOverlay=${bootstrapOverlay != null} " +
            "masks=${masksByGeneration.keys} pendingKeys=${pendingCoverageKeys?.size} " +
            "installedKeys=${installedCoverageKeys?.size} " +
            "recentRequests=${recentRequestedKeysOrNull()?.size} " +
            "provenRequested=${lastProvenRequestedKeys.size} " +
            "sinceLastRequestMs=${(SystemClock.elapsedRealtimeNanos() - lastRequestAtNanos) / 1_000_000L}"
    }

    private fun requestCurrentViewportIfReady() {
        if (released || !baselineReady || !mapLoaded) return
        armPaletteRotationIfNeeded()
        coordinator.onCameraIdle()
        afterCoordinatorMutation()
    }

    private fun startRender(generation: FogTileGeneration) {
        assertMainThread()
        if (released || hostStopped || !adapter.isCurrent(generation)) return
        if (renderWork?.generation?.id == generation.id || generation.id in masksByGeneration) return
        val renderInput = try {
            val coverage = currentCoverageRequest()
                ?: throw IllegalStateException("map projection unavailable")
            val actual = requestedKeysForRender()
            val requested = LinkedHashSet<FogTileKey>().apply {
                addAll(coveragePlanner.plan(coverage).keySet)
                addAll(actual)
            }
            if (requested.size > MAX_REQUESTED_KEYS) {
                throw IllegalStateException("actual request union exceeded bound")
            }
            coverage to requested.toSet()
        } catch (failure: Exception) {
            failGeneration(generation.id, failure)
            return
        } catch (failure: LinkageError) {
            failGeneration(generation.id, failure)
            return
        }
        val (coverage, requested) = renderInput
        val work = RenderWork(
            generation = generation,
            coverage = coverage,
            requested = requested,
            cameraEpoch = cameraEpoch,
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
                val masks = withTimeout(lease.remainingMillis.coerceAtLeast(1L)) {
                    withContext(Dispatchers.IO) {
                        requestedRenderer.render(work.coverage.center, work.requested)
                    }
                }
                ensureActive()
                if (!isCurrentRender(work, lease)) return@launch

                // The render budget ends before the adapter commit. ON_STOP can therefore pause
                // only the canonical read; it cannot cancel a commit after the generation's result
                // is ready and leave the adapter/coordinator ownership split. Adapter generation
                // identity still rejects a commit whose generation was cancelled in the meantime.
                if (!work.budget.complete(lease)) return@launch
                work.activeLease = null
                commitStarted = true
                val published = withContext(Dispatchers.Default) {
                    if (!isCurrentRenderWork(work)) {
                        null
                    } else {
                        adapter.publishMasks(work.generation, masks)
                    }
                }
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
            adapter.isCurrent(work.generation) && work.budget.isCurrent(lease)

    private fun isCurrentRenderWork(work: RenderWork): Boolean =
        !released && renderWork === work && adapter.isCurrent(work.generation)

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
        val completed = actualRequests.consumeCompleted(generationId) ?: run {
            scheduleDeliveryQuietCheck(generationId)
            return
        }
        lastProvenRequestedKeys = completed
        providers[generationId]?.setCanonicalDeliveryObserver(null)
        coordinator.onDeliveryBarrierDrained(generationId)
        afterCoordinatorMutation()
    }

    private fun freshProofPlan(generationId: Long) = coverageByGeneration[generationId]?.let { published ->
        val coverage = currentCoverageRequest() ?: published
        val allMasks = masksByGeneration[generationId].orEmpty()
        val requiredFloorKeys = try {
            coveragePlanner.plan(coverage).keySet
        } catch (_: IllegalArgumentException) {
            return@let null
        }
        val actual = recentRequestedKeysOrNull() ?: return@let null
        if (!allMasks.keys.containsAll(requiredFloorKeys) || !allMasks.keys.containsAll(actual)) {
            return@let null
        }
        val masks = allMasks.filterKeys { key -> key.zoom == coverage.floorZoom }
        if (masks.isEmpty()) {
            null
        } else {
            val zones = try {
                exclusionZonesForProof()
            } catch (_: Exception) {
                listOf(wholeWorldFogProbeExclusionZone())
            } catch (_: LinkageError) {
                listOf(wholeWorldFogProbeExclusionZone())
            }
            probePlanner.plan(coverage, masks, exclusionZones = zones)
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
        publishCoverInterval(coordinator.coverUp)
        onStateChanged(state())
        if (coordinator.terminal && !terminalPublished) {
            terminalPublished = true
            onTerminalFailure()
        }
        if (coordinator.retryScheduled && !retryPosted) {
            retryPosted = true
            handler.postDelayed(
                {
                    retryPosted = false
                    if (!released) {
                        armPaletteRotationIfNeeded()
                        coordinator.onRetryFogOperation()
                        afterCoordinatorMutation()
                    }
                },
                RETRY_FOG_MILLIS,
            )
        }
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
            planner = coveragePlanner,
        )
    }

    private fun armCoverDeadline() {
        cancelCoverDeadline()
        val deadline = Runnable {
            coverDeadline = null
            if (!released && coordinator.coverUp && !coordinator.terminal) {
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

    private fun publishCoverInterval(coverUp: Boolean) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (coverUp && !lastPublishedCoverUp) {
            coverRaisedAtNanos = now
            if (!hostStopped) armCoverDeadline()
        } else if (!coverUp && lastPublishedCoverUp) {
            cancelCoverDeadline()
            val raised = coverRaisedAtNanos
            if (raised != null) {
                lastCoverIntervalMillis = (now - raised) / NANOS_PER_MILLISECOND
                maximumCoverIntervalMillis = maxOf(
                    maximumCoverIntervalMillis,
                    requireNotNull(lastCoverIntervalMillis),
                )
            }
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
    )

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
        if (key !in recentRequests && recentRequests.size >= MAX_REQUESTED_KEYS) {
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
    ): TileOverlay? = try {
        map.addTileOverlay(
            TileOverlayOptions()
                .tileProvider(tileProvider)
                .fadeIn(false)
                .transparency(0F)
                .zIndex(zIndex),
        )
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
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

    private fun armPaletteRotationIfNeeded() {
        val nextId = lastGenerationId + 1L
        if (FogTilePngCodec.generationStartsNewPaletteCycle(nextId)) {
            coordinator.onPaletteRotationDue()
        }
    }

    private fun publishFogRenderForCompatibility(
        coverage: FogViewportCoverageRequest,
        masks: Map<FogTileKey, FogPixelMask>,
    ) {
        val callback = onFogRendered ?: return
        val floorKeys = coveragePlanner.plan(coverage).keys
        if (!masks.keys.containsAll(floorKeys)) return
        val tiles = floorKeys.map { key -> FogMosaicTile(key, requireNotNull(masks[key])) }
        callback(
            FogViewportRender(
                request = FogViewportRequest(coverage.center, coverage.floorZoom.toDouble()),
                keys = floorKeys,
                queryBounds = null,
                mosaic = FogPocMosaic.compose(tiles),
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
        const val MAX_REQUESTED_KEYS = 256
        const val DELIVERY_POLL_MILLIS = 50L
        const val DELIVERY_QUIET_NANOS = 100L * 1_000_000L
        const val RENDER_TIMEOUT_MILLIS = 15_000L
        const val INSTALL_TIMEOUT_MILLIS = 15_000L
        const val SYNCHRONIZATION_TIMEOUT_MILLIS = 15_000L
        const val SYNCHRONIZATION_RETRY_MILLIS = 1_000L
        const val RETRY_FOG_MILLIS = 1_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_COVER_MILLIS = 20_000L
        const val OLD_OVERLAY_Z = 10F
        const val NEW_OVERLAY_Z = 20F
        const val BOOTSTRAP_PLACEHOLDER_GENERATION = Long.MIN_VALUE
    }
}
