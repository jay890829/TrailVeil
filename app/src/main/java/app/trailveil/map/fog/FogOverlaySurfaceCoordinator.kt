package app.trailveil.map.fog

import java.util.concurrent.atomic.AtomicLong

/** Why the camera started moving, as reported by the provider SDK's move-started callback. */
enum class FogCameraMoveReason { GESTURE, API_ANIMATION, DEVELOPER }

/** The only three reactions a camera move may provoke from the fog machinery. */
enum class FogCameraReaction { LEAVE_PUBLISHED_COVERAGE, REBUILD_AT_IDLE, RAISE_OPAQUE_COVER }

/**
 * The pure decision core of [FogOverlaySurfaceCoordinator] (design §2.4/§5), enumerable and
 * JVM-pinned over its full input cross-product independent of the surrounding machinery.
 *
 * Absolute rules, in priority order:
 * 1. A GESTURE never raises the cover and never begins fog work at move-start. The per-frame
 *    surround check raises an input-transparent cover only after the camera actually leaves
 *    proven coverage (owner-approved SP5 mitigation).
 * 2. A follow ease step has the same move-start exemption; a real mid-flight surround exit still
 *    raises the safety cover.
 * 3. A programmed move already outside the published surround raises the opaque cover (the
 *    instant-jump case).
 * 4. A programmed move while a palette rotation is due raises the cover until re-proof —
 *    design §4(c) belt-and-braces on top of the dual-overlay rotation. `[refinable]` per SP9
 *    evidence once attach-before-remove rotation is measured gapless on-device.
 * 5. Anything else invalidates the canonical claim quietly: rebuild at the next idle.
 *
 * [programmedFlightActive] deliberately never changes the reaction: the flight ticket's job is
 * follow-effect stand-down and stale-cancel rejection (SP10), not cover policy. It stays an
 * input so the pinned table documents that equivalence rather than assuming it.
 */
fun fogCameraReaction(
    moveReason: FogCameraMoveReason,
    followStepInFlight: Boolean,
    @Suppress("UNUSED_PARAMETER") programmedFlightActive: Boolean,
    insidePublishedSurround: Boolean,
    paletteRotationRequired: Boolean,
): FogCameraReaction = when {
    moveReason == FogCameraMoveReason.GESTURE -> FogCameraReaction.LEAVE_PUBLISHED_COVERAGE
    followStepInFlight -> FogCameraReaction.LEAVE_PUBLISHED_COVERAGE
    !insidePublishedSurround -> FogCameraReaction.RAISE_OPAQUE_COVER
    paletteRotationRequired -> FogCameraReaction.RAISE_OPAQUE_COVER
    else -> FogCameraReaction.REBUILD_AT_IDLE
}

/** Why the first-install guard is up. The coordinator is the cover's single writer. */
enum class FogCoverReason {
    FIRST_COMPOSITION,
    VIEWPORT_EXIT,
    PROGRAMMED_EXIT,
    PALETTE_ROTATION,
    RUNTIME_FAILURE,
}

/** Terminal-vs-retry classification for fog-machinery failures (design §9). */
enum class FogInstallFailureClassification { TERMINAL_FOR_COMPOSITION, RETRY_BEHIND_PLACEHOLDERS }

/**
 * Pure §9 rule: a failure with NOTHING proven is terminal for this composition; a failure while
 * a proven generation stands retains the published tiles and retries behind adapter-guaranteed
 * placeholders — never terminal, never a bare basemap.
 */
fun classifyFogInstallFailure(hasProvenGeneration: Boolean): FogInstallFailureClassification =
    if (hasProvenGeneration) {
        FogInstallFailureClassification.RETRY_BEHIND_PLACEHOLDERS
    } else {
        FogInstallFailureClassification.TERMINAL_FOR_COMPOSITION
    }

/**
 * Overlay and generation side effects, executed by the provider binding.
 *
 * The add-before-remove dual-overlay mechanism lives behind this port: the coordinator attaches
 * the NEW generation's overlay first and removes the OLD one only after the new proof passes.
 * SP9 (V02-005-spikes.md) measured in-place [clearTileCache] presenting full-basemap frames for
 * ~40% of the repaint window, so the refresh path NEVER calls it; it remains for exceptional
 * binding use only.
 */
interface FogOverlayPort {
    /**
     * Starts rendering a new generation and returns its id. [handover] keeps the currently
     * published adapter set serving until the new one publishes (adapter handover); otherwise
     * the revoke path runs (first install / failure recovery, cover already up). The binding
     * reports back via [FogOverlaySurfaceCoordinator.onGenerationPublished] or
     * [FogOverlaySurfaceCoordinator.onGenerationRenderFailed].
     */
    fun beginRebuild(handover: Boolean, paletteRotation: Boolean): Long

    fun attachOverlay(generationId: Long)

    /** False means stale/native content may still be visible and the composition must terminate. */
    fun removeOverlay(generationId: Long): Boolean

    /** In-place repaint. NEVER called on the refresh path (SP9); see interface KDoc. */
    fun clearTileCache()

    /** Abandons a pending rebuild; a handover cancel must leave the published set serving. */
    fun cancelRebuild(generationId: Long)
}

/**
 * Visual-proof side effects. The implementation owns the SP8-measured retry cadence
 * (250 ms x 10); [prove]'s callback delivers the FINAL verdict on the main thread.
 */
interface FogSnapshotPort {
    fun prove(generationId: Long, onResult: (Boolean) -> Unit)
}

/** Camera reads. */
interface FogCameraPort {
    /**
     * True when the camera centre sits inside the installed/proven coverage surround.
     *
     * Movement safety must use this read only. A pending generation is not yet visible as
     * proven coverage, even when its render has completed and its overlay is attached.
     */
    fun insidePublishedSurround(): Boolean

    /**
     * True when the camera centre sits inside the generation currently being completed.
     *
     * This is intentionally separate from [insidePublishedSurround]: completion must decide
     * whether the just-proven generation is stale against its own pending coverage before the
     * coordinator replaces the installed-generation identity. Implementations that have no
     * pending-generation distinction retain the old behaviour by delegating to the installed
     * read.
     */
    fun insidePendingSurround(): Boolean = insidePublishedSurround()
}

/**
 * SDK-free state machine owning cover state, generation lifecycle (handover vs revoke, reuse
 * across rapid idles), the follow-ease exemption, the programmed-flight ticket, surround holds,
 * the ON_START re-proof hook and terminal-vs-retry classification (design §2.4/§5/§9).
 *
 * THREADING CONTRACT: every entry point is invoked on the main thread by the binding — camera
 * callbacks and snapshot callbacks already arrive there. Tile-provider worker threads touch only
 * [FogTileProviderAdapter], never this class. The binding asserts this in debug builds.
 *
 * LIFETIME CONTRACT: one instance per surface composition. Terminal classification is therefore
 * per-composition by construction — a recreated surface constructs a fresh coordinator and
 * retries from scratch (design §9's no-latch rule).
 *
 * DELIVERY-BARRIER CONTRACT (F0, V02-005-spikes.md): [onDeliveryBarrierDrained] must be driven
 * by the set of tiles the RENDERER actually requested for the new generation, never by a
 * predicted floor-zoom coverage set — a tilted camera renders its far plane from lower-zoom LOD
 * tiles and a predicted-coverage barrier deadlocks waiting on tiles that are never requested.
 */
class FogOverlaySurfaceCoordinator(
    private val overlayPort: FogOverlayPort,
    private val snapshotPort: FogSnapshotPort,
    private val cameraPort: FogCameraPort,
) {

    /** One pending (rendering or proving) generation. */
    private data class PendingRebuild(
        val generationId: Long,
        val handover: Boolean,
        val paletteRotation: Boolean,
        val overlayAttached: Boolean,
        val oldOverlayRemoved: Boolean = false,
    )

    var coverUp: Boolean = false
        private set
    var coverReason: FogCoverReason? = null
        private set
    var terminal: Boolean = false
        private set
    var retryScheduled: Boolean = false
        private set
    var installedGenerationId: Long? = null
        private set
    val pendingGenerationId: Long?
        get() = pending?.generationId

    private var pending: PendingRebuild? = null
    private var canonicalDirty = false
    private var paletteRotationDue = false
    private var viewportDirty = false
    private var movingOutsideCoverReason = FogCoverReason.VIEWPORT_EXIT

    private val programmedFlight = AtomicLong(IDLE_FOG_CAMERA_FLIGHT)
    var followStepInFlight: Boolean = false
        private set

    val programmedFlightActive: Boolean
        get() = programmedFlight.get() != IDLE_FOG_CAMERA_FLIGHT

    // ---- composition and lifecycle -----------------------------------------------------------

    /** First composition of the surface: cover up until the first proof completes. */
    fun onFirstComposition() {
        raiseCover(FogCoverReason.FIRST_COMPOSITION)
        viewportDirty = true
    }

    /**
     * ON_START re-proof (design §2.4; SP6 measured re-proof at ~140 ms with zero re-requests).
     * A failed re-proof fails closed: cover up and a rebuild begins.
     */
    fun onStart() {
        if (terminal) return
        val installed = installedGenerationId ?: return
        if (pending != null) return
        snapshotPort.prove(installed) { passed ->
            // The verdict is asynchronous (SP8: up to 250 ms x 10). A rebuild may legitimately
            // have begun meanwhile, or the installed generation may have moved on; acting on a
            // stale verdict would clobber that pending rebuild — orphaning its attached overlay
            // — or raise a spurious cover over freshly proven coverage.
            if (passed || terminal) return@prove
            if (pending != null || installedGenerationId != installed) return@prove
            raiseCover(FogCoverReason.RUNTIME_FAILURE)
            beginRebuild(handover = true, paletteRotation = paletteRotationDue)
        }
    }

    // ---- camera ------------------------------------------------------------------------------

    fun onCameraMoveStarted(reason: FogCameraMoveReason) {
        if (terminal) return
        movingOutsideCoverReason = if (
            reason == FogCameraMoveReason.GESTURE || followStepInFlight
        ) {
            FogCoverReason.VIEWPORT_EXIT
        } else {
            FogCoverReason.PROGRAMMED_EXIT
        }
        val reaction = fogCameraReaction(
            moveReason = reason,
            followStepInFlight = followStepInFlight,
            programmedFlightActive = programmedFlightActive,
            insidePublishedSurround = cameraPort.insidePublishedSurround(),
            paletteRotationRequired = paletteRotationDue,
        )
        when (reaction) {
            FogCameraReaction.LEAVE_PUBLISHED_COVERAGE -> Unit
            FogCameraReaction.REBUILD_AT_IDLE -> viewportDirty = true
            FogCameraReaction.RAISE_OPAQUE_COVER -> {
                viewportDirty = true
                raiseCover(
                    if (paletteRotationDue) {
                        FogCoverReason.PALETTE_ROTATION
                    } else {
                        FogCoverReason.PROGRAMMED_EXIT
                    },
                )
            }
        }
    }

    /**
     * Per-frame reactive safety check. Move-start remains side-effect-light and never begins fog
     * work, so the first gesture is accepted normally. Once any movement actually exits the
     * published surround, however, the owner-approved SP5 mitigation raises an input-transparent
     * cover until the new viewport is rendered and proven. This closes the measured raw-basemap
     * fling window without cancelling or intercepting the gesture.
     */
    fun onCameraMoveFrame() {
        if (terminal || coverUp) return
        if (!cameraPort.insidePublishedSurround()) {
            viewportDirty = true
            raiseCover(movingOutsideCoverReason)
        }
    }

    /**
     * Idle asks three questions in order (design §5): canonical content changed or palette
     * cycled? camera outside the published surround? neither -> no work at all. A pending
     * rebuild is reused across rapid idles ([FogGenerationReusePolicy]); staleness is re-checked
     * when it installs.
     */
    fun onCameraIdle() {
        if (terminal) return
        val pendingNow = pending
        if (pendingNow != null &&
            FogGenerationReusePolicy.canReuse(
                activeGenerationId = pendingNow.generationId,
                installedGenerationId = installedGenerationId,
                adapterIsCurrent = true,
            )
        ) {
            return
        }
        val outsideSurround = !cameraPort.insidePublishedSurround()
        val firstInstall = installedGenerationId == null
        if (outsideSurround && !firstInstall && !coverUp) {
            // A very short movement may dispatch idle before a sampled move-frame. Preserve the
            // same safety rule at the terminal callback so that path cannot expose raw basemap.
            raiseCover(movingOutsideCoverReason)
        }
        if (!canonicalDirty && !paletteRotationDue && !outsideSurround &&
            !firstInstall && !viewportDirty
        ) {
            return
        }
        beginRebuild(handover = !firstInstall, paletteRotation = paletteRotationDue)
    }

    // ---- canonical inputs --------------------------------------------------------------------

    /** Canonical content changed since the installed generation (synchronizer REFRESH). */
    fun onCanonicalRefreshRequired() {
        canonicalDirty = true
    }

    /** The palette generation counter reached a rotation boundary. */
    fun onPaletteRotationDue() {
        paletteRotationDue = true
    }

    // ---- rebuild pipeline --------------------------------------------------------------------

    /** The adapter atomically published [generationId]'s tiles: attach the NEW overlay first. */
    fun onGenerationPublished(generationId: Long) {
        val pendingNow = pending ?: return
        if (pendingNow.generationId != generationId) return
        overlayPort.attachOverlay(generationId)
        pending = pendingNow.copy(overlayAttached = true)
    }

    fun onGenerationRenderFailed(generationId: Long) = failPending(generationId)

    /**
     * Every tile the renderer actually requested for [generationId] has been delivered (see the
     * class KDoc's F0 contract). The visual proof starts here.
     */
    fun onDeliveryBarrierDrained(generationId: Long) {
        var pendingNow = pending ?: return
        if (pendingNow.generationId != generationId || !pendingNow.overlayAttached) return
        if (pendingNow.paletteRotation && !pendingNow.oldOverlayRemoved) {
            val previous = installedGenerationId
            if (previous != null && previous != generationId) {
                // Signature colours repeat every 63 generations. Under the already-raised local
                // cover, remove the cached old colour AFTER target delivery but BEFORE snapshot;
                // otherwise old G1 pixels can falsely prove target G64.
                if (!overlayPort.removeOverlay(previous)) {
                    enterTerminalFailure()
                    return
                }
                installedGenerationId = null
                pendingNow = pendingNow.copy(oldOverlayRemoved = true)
                pending = pendingNow
            }
        }
        snapshotPort.prove(generationId) { passed ->
            if (passed) completeInstall(generationId) else failPending(generationId)
        }
    }

    fun onInstallTimeout(generationId: Long) = failPending(generationId)

    /** A cover may never remain indefinitely through retries; the binding owns the wall-clock. */
    fun onCoverDeadlineExceeded() = enterTerminalFailure()

    /** Native overlay cleanup failed, so successful presentation can no longer be proven. */
    fun onOverlayCleanupFailure() = enterTerminalFailure()

    /** Fog-machinery failure outside the rebuild pipeline (design §9). */
    fun onFogRuntimeFailure() {
        pending?.let { failPending(it.generationId) } ?: run {
            when (classifyFogInstallFailure(hasProvenGeneration = installedGenerationId != null)) {
                FogInstallFailureClassification.RETRY_BEHIND_PLACEHOLDERS -> retryScheduled = true
                FogInstallFailureClassification.TERMINAL_FOR_COMPOSITION -> {
                    raiseCover(FogCoverReason.RUNTIME_FAILURE)
                    terminal = true
                }
            }
        }
    }

    /** The binding's 1 s `retryFogOperation` cadence tick. */
    fun onRetryFogOperation() {
        if (terminal || !retryScheduled || pending != null) return
        retryScheduled = false
        beginRebuild(handover = installedGenerationId != null, paletteRotation = paletteRotationDue)
    }

    // ---- programmed flights and follow eases (SP10: CAS, never a boolean) --------------------

    /**
     * Claims the flight ticket for a programmed camera flight; returns the ticket id.
     *
     * A programmed flight SUPERSEDES any airborne follow ease, so the ease exemption is dropped
     * here rather than waiting for the ease's own terminal: SP10 measured the superseded
     * flight's cancel arriving after the replacement's claim, so that terminal's CAS fails and
     * would leave the exemption armed forever — disarming the §4(b)/§5 programmed-exit cover on
     * every later jump.
     */
    fun beginProgrammedFlight(): Long {
        followStepInFlight = false
        return nextFlightTicket()
    }

    /**
     * Releases [ticketId] via CAS. SP10 measured a superseded flight's cancel dispatching AFTER
     * the replacement's claim in 60/60 pairs — a stale release must be rejected, never clear the
     * live claim.
     */
    fun endProgrammedFlight(ticketId: Long): Boolean =
        programmedFlight.compareAndSet(ticketId, IDLE_FOG_CAMERA_FLIGHT)

    /** Claims the ticket for a follow EASE and arms the follow exemption. */
    fun beginFollowEase(): Long {
        followStepInFlight = true
        return nextFlightTicket()
    }

    /**
     * Follow-ease terminal. The CAS release is the claim's authority (a stale terminal must
     * never clear a live claim), but the exemption is dropped on ANY ease terminal: a CAS
     * failure means this ease was superseded, and whatever superseded it owns the exemption
     * state — a live ease re-armed it in [beginFollowEase], a programmed flight cleared it in
     * [beginProgrammedFlight]. Leaving it armed on CAS failure is what stuck it forever.
     */
    fun endFollowEase(ticketId: Long): Boolean {
        val released = programmedFlight.compareAndSet(ticketId, IDLE_FOG_CAMERA_FLIGHT)
        if (released) followStepInFlight = false
        return released
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun nextFlightTicket(): Long {
        var ticket: Long
        do {
            ticket = programmedFlight.incrementAndGet()
            // Skip the idle sentinel on wraparound; in practice unreachable.
        } while (ticket == IDLE_FOG_CAMERA_FLIGHT)
        return ticket
    }

    private fun beginRebuild(handover: Boolean, paletteRotation: Boolean) {
        if (paletteRotation) raiseCover(FogCoverReason.PALETTE_ROTATION)
        canonicalDirty = false
        viewportDirty = false
        val generationId = overlayPort.beginRebuild(
            handover = handover,
            paletteRotation = paletteRotation,
        )
        pending = PendingRebuild(
            generationId = generationId,
            handover = handover,
            paletteRotation = paletteRotation,
            overlayAttached = false,
        )
    }

    private fun completeInstall(generationId: Long) {
        val pendingNow = pending ?: return
        if (pendingNow.generationId != generationId) return
        val previous = installedGenerationId
        if (
            previous != null && previous != generationId &&
            !pendingNow.oldOverlayRemoved
        ) {
            // Add-before-remove completes: the old overlay leaves only after the new proof.
            if (!overlayPort.removeOverlay(previous)) {
                installedGenerationId = generationId
                pending = null
                enterTerminalFailure()
                return
            }
        }
        // Read the pending generation before replacing the installed-generation identity. The
        // movement path deliberately reads only installed/proven coverage; completion instead
        // must judge whether the generation it just proved covers the camera at this instant.
        val insidePendingSurround = cameraPort.insidePendingSurround()
        installedGenerationId = generationId
        pending = null
        if (pendingNow.paletteRotation) paletteRotationDue = false
        // Staleness re-check: inputs that changed while this rebuild was in flight trigger an
        // immediate follow-up handover, because a stationary camera produces no further idle.
        // viewportDirty is part of the check — an idle swallowed by generation reuse would
        // otherwise be lost until the next camera move.
        val outsideSurround = !insidePendingSurround
        val stale = canonicalDirty || paletteRotationDue || viewportDirty || outsideSurround
        // The cover may only be lowered once the camera is actually over proven coverage: an
        // install that completes while the camera sits outside the published surround must keep
        // the §4(b) programmed-exit cover up through its follow-up rebuild.
        retryScheduled = false
        if (!outsideSurround) lowerCover()
        if (stale) beginRebuild(handover = true, paletteRotation = paletteRotationDue)
    }

    /**
     * The renderer asked the pending generation's overlay for tiles its render never produced:
     * the camera left the rendered viewport while the install was still draining. Measured in
     * stage 9 on the launcher with history: a warm runtime rendered the first generation for the
     * world view, the entry route then flew to the last recorded point, and the renderer's
     * zoom-16 requests could never be delivered by a render that only held zoom-3 masks, so no
     * barrier over them drains and the install timeout would have made a FIRST install terminal.
     *
     * Neither a failure nor a retry (design §9 classifies failures; this is a restart with better
     * inputs): the pending rebuild is abandoned and a new one begins over the request set that
     * was actually observed, with the cover exactly as it was and the same handover mode. Bounded
     * like every rebuild by the binding's cover deadline.
     */
    fun onPendingRenderStranded(generationId: Long) {
        if (terminal) return
        val pendingNow = pending ?: return
        if (pendingNow.generationId != generationId) return
        pending = null
        overlayPort.cancelRebuild(generationId)
        if (pendingNow.overlayAttached && !overlayPort.removeOverlay(generationId)) {
            enterTerminalFailure()
            return
        }
        beginRebuild(handover = pendingNow.handover, paletteRotation = pendingNow.paletteRotation)
    }

    private fun failPending(generationId: Long) {
        val pendingNow = pending ?: return
        if (pendingNow.generationId != generationId) return
        pending = null
        overlayPort.cancelRebuild(generationId)
        if (pendingNow.overlayAttached && !overlayPort.removeOverlay(generationId)) {
            enterTerminalFailure()
            return
        }
        when (classifyFogInstallFailure(hasProvenGeneration = installedGenerationId != null)) {
            FogInstallFailureClassification.RETRY_BEHIND_PLACEHOLDERS -> {
                // The old proven set keeps serving (adapter handover); no cover change.
                retryScheduled = true
            }
            FogInstallFailureClassification.TERMINAL_FOR_COMPOSITION -> {
                raiseCover(FogCoverReason.RUNTIME_FAILURE)
                terminal = true
            }
        }
    }

    private fun raiseCover(reason: FogCoverReason) {
        coverUp = true
        coverReason = reason
    }

    private fun lowerCover() {
        coverUp = false
        coverReason = null
    }

    private fun enterTerminalFailure() {
        if (terminal) return
        val pendingNow = pending
        pending = null
        retryScheduled = false
        pendingNow?.let { rebuilding ->
            overlayPort.cancelRebuild(rebuilding.generationId)
            if (rebuilding.overlayAttached) {
                // Best effort only: the local/Compose cover is already raised and the host will
                // tear down the MapView when terminal state is published.
                overlayPort.removeOverlay(rebuilding.generationId)
            }
        }
        raiseCover(FogCoverReason.RUNTIME_FAILURE)
        terminal = true
    }

    companion object {
        /** Idle sentinel for the programmed-flight ticket. */
        const val IDLE_FOG_CAMERA_FLIGHT = 0L
    }
}
