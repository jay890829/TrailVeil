package app.trailveil.googlepoc

import app.trailveil.map.fog.FogProofBlockSample
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.FogTileProviderAdapter
import app.trailveil.map.fog.reduceFogProofBlocks
import app.trailveil.map.fog.FogOverlayRefreshPhaseCoordinator
import app.trailveil.map.fog.FogOverlayRefreshSnapshot
import app.trailveil.map.fog.FogTileKey
import app.trailveil.map.fog.FogTileRequestBarrier
import app.trailveil.map.fog.FogSnapshotVisualProbe
import app.trailveil.map.fog.FogSnapshotVisualProbePlan
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get
import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import java.util.concurrent.atomic.AtomicReference

enum class GoogleFogRefreshFailure {
    NOT_ATTACHED,
    PHASE_REJECTED,
    SDK_CLEAR_FAILURE,
}

/** `V02-005` stage 3 (SP6): coordinate-free tile-request tallies since the last reset. */
data class GoogleFogTileRequestCounters(
    val total: Long,
    val canonical: Long,
    val placeholder: Long,
)

/**
 * `V02-005` stage 3 (SP9): timestamps of the refresh-transient lifecycle, emitted only when a
 * spike installs an observer. Every callback carries `SystemClock.elapsedRealtimeNanos()` taken
 * at the emit site; no tile identity or coordinate ever crosses this interface.
 */
interface GoogleFogRefreshTransientObserver {
    fun onClearTileCacheIssued(generation: Long, atNanos: Long)
    fun onRotationOverlayRemoved(generation: Long, atNanos: Long)
    fun onRotationOverlayAttached(generation: Long, atNanos: Long)
    fun onCanonicalTileDelivered(generation: Long, atNanos: Long)
    fun onDeliveryBarrierDrained(generation: Long, atNanos: Long)
    fun onInstallProven(generation: Long, atNanos: Long, snapshotAttempt: Int)
}

data class GoogleFogVisualProof(
    val requiredTileCount: Int,
    val verifiedTileCount: Int,
    val snapshotAttempt: Int,
    val offScreenOnlyTileCount: Int = 0,
    val mismatchedTileCount: Int = 0,
    val minimumOnScreenProbeCount: Int = 0,
)

/**
 * Thin googlePoc-only bridge.  All canonical rendering, validation and fail-closed behavior lives
 * in [FogTileProviderAdapter]; this class only converts bytes to the SDK's [Tile] value.
 */
class GoogleFogTileProvider(
    private val adapter: FogTileProviderAdapter,
    /** Null keeps the retained PoC behavior; production overlays bind to exactly one generation. */
    private val acceptedGeneration: Long? = null,
) : TileProvider {
    private val canonicalDeliveryObserver =
        AtomicReference<((generation: Long, key: FogTileKey) -> Unit)?>(null)

    // `V02-005` stage 3 spike seams. The counters are independent of the single-slot
    // canonicalDeliveryObserver (which the overlay controller owns for its install barrier and
    // which never fires for placeholder responses); getTile runs on SDK worker threads, hence
    // atomics and a lock-free observer slot.
    private val totalRequests = java.util.concurrent.atomic.AtomicLong(0L)
    private val canonicalRequests = java.util.concurrent.atomic.AtomicLong(0L)
    private val placeholderRequests = java.util.concurrent.atomic.AtomicLong(0L)
    private val tileRequestObserver =
        AtomicReference<((x: Int, y: Int, zoom: Int, elapsedRealtimeNanos: Long) -> Unit)?>(null)

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        try {
            tileRequestObserver.get()?.invoke(
                x,
                y,
                zoom,
                android.os.SystemClock.elapsedRealtimeNanos(),
            )
        } catch (_: Exception) {
            // A broken spike observer must never affect tile delivery.
        } catch (_: LinkageError) {
            // Same fail-closed discipline as the canonical delivery observer.
        }
        val response = adapter.tileResponse(x = x, y = y, zoom = zoom)
        val generation = response.publishedGeneration?.takeIf { published ->
            acceptedGeneration == null || acceptedGeneration == published
        }
        val bytes = if (
            response.publishedGeneration != null && generation == null
        ) {
            // An old production overlay must never start serving the newly published signature.
            // Keeping it opaque makes the new-overlay snapshot proof causally attributable.
            FogTilePngCodec.opaquePlaceholder()
        } else {
            response.bytes
        }
        totalRequests.incrementAndGet()
        if (generation != null) {
            canonicalRequests.incrementAndGet()
        } else {
            placeholderRequests.incrementAndGet()
        }
        val key = response.key
        if (generation != null && key != null) {
            try {
                canonicalDeliveryObserver.get()?.invoke(generation, key)
            } catch (_: Exception) {
                // The tile remains safe to return. A broken observer cannot reveal the basemap;
                // its barrier will time out while the Activity keeps the opaque cover visible.
            } catch (_: LinkageError) {
                // Treat provider/SDK linkage failure identically: fail closed at the owner.
            }
        }
        return Tile(
            FogTilePngCodec.TILE_SIZE,
            FogTilePngCodec.TILE_SIZE,
            bytes,
        )
    }

    fun setCanonicalDeliveryObserver(
        observer: ((generation: Long, key: FogTileKey) -> Unit)?,
    ) {
        canonicalDeliveryObserver.set(observer)
    }

    fun setTileRequestObserver(
        observer: ((x: Int, y: Int, zoom: Int, elapsedRealtimeNanos: Long) -> Unit)?,
    ) {
        tileRequestObserver.set(observer)
    }

    fun requestCountersForTesting(): GoogleFogTileRequestCounters = GoogleFogTileRequestCounters(
        total = totalRequests.get(),
        canonical = canonicalRequests.get(),
        placeholder = placeholderRequests.get(),
    )

    fun resetRequestCountersForTesting() {
        totalRequests.set(0L)
        canonicalRequests.set(0L)
        placeholderRequests.set(0L)
    }
}

/**
 * Explicit, generation-coalesced refresh seam for a TileOverlay.
 *
 * A tile request never clears the overlay. Generation start is protected by the Activity's opaque
 * local cover; callers clear once after a complete canonical generation is committed. Repeated
 * requests for the same generation are ignored.
 */
class GoogleFogTileOverlayController(
    private val map: GoogleMap,
    private val provider: GoogleFogTileProvider,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: TileOverlay? = null
    @Volatile
    private var deliveryBarrier: FogTileRequestBarrier? = null
    @Volatile
    private var lastRefreshFailure: GoogleFogRefreshFailure? = null
    @Volatile
    private var lastClearFailureClass: String? = null
    @Volatile
    private var lastClearAttemptSucceeded: Boolean? = null
    @Volatile
    private var lastVisualProof = GoogleFogVisualProof(0, 0, 0)
    // SP5: the first clearTileCache attempt after a fling's idle marks where its analysis window
    // must end (the repaint transient beyond it is SP9's phenomenon, not fling exposure).
    @Volatile
    private var lastClearAttemptAtNanos: Long = 0L
    private val refreshPhases = FogOverlayRefreshPhaseCoordinator {
        try {
            lastClearAttemptSucceeded = false
            lastClearAttemptAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
            requireNotNull(overlay) { "fog overlay is not attached" }.clearTileCache()
            lastClearAttemptSucceeded = true
        } catch (failure: RuntimeException) {
            lastClearFailureClass = failure.javaClass.name
            throw failure
        } catch (failure: LinkageError) {
            lastClearFailureClass = failure.javaClass.name
            throw failure
        }
    }

    // `V02-005` stage 3 (SP9): a null-by-default observer of refresh-transient timestamps.
    private val transientObserver = AtomicReference<GoogleFogRefreshTransientObserver?>(null)

    fun setRefreshTransientObserverForTesting(observer: GoogleFogRefreshTransientObserver?) {
        transientObserver.set(observer)
    }

    private inline fun emitTransient(action: (GoogleFogRefreshTransientObserver) -> Unit) {
        val observer = transientObserver.get() ?: return
        try {
            action(observer)
        } catch (_: Exception) {
            // A broken spike observer must never weaken fail-closed refresh behavior.
        } catch (_: LinkageError) {
            // Same discipline as the canonical delivery observer.
        }
    }

    init {
        provider.setCanonicalDeliveryObserver { generation, key ->
            deliveryBarrier?.record(generation, key)
            emitTransient { observer ->
                observer.onCanonicalTileDelivered(
                    generation,
                    android.os.SystemClock.elapsedRealtimeNanos(),
                )
            }
        }
    }

    fun attach(): TileOverlay? {
        if (overlay == null) {
            overlay = try {
                map.addTileOverlay(
                    TileOverlayOptions()
                        .tileProvider(provider)
                        // Unknown coverage must never fade through to an exposed basemap.
                        .fadeIn(false)
                        .transparency(0F)
                        .zIndex(Float.MAX_VALUE),
                )
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
        }
        return overlay
    }

    /** Records a covered generation start without forcing a redundant placeholder reload. */
    fun onGenerationStarted(generation: Long): Boolean {
        deliveryBarrier = null
        lastRefreshFailure = null
        lastClearFailureClass = null
        lastClearAttemptSucceeded = null
        lastVisualProof = GoogleFogVisualProof(0, 0, 0)
        if (overlay == null) return false
        if (FogTilePngCodec.generationStartsNewPaletteCycle(generation)) {
            if (!rotateOverlay(generation)) return false
            refreshPhases.reset()
        }
        return refreshPhases.onGenerationStarted(generation)
    }

    /** Removes all SDK/native tile state before a visual signature is ever reused. */
    private fun rotateOverlay(generation: Long): Boolean {
        val current = overlay ?: return false
        overlay = null
        try {
            current.remove()
        } catch (_: Exception) {
            return false
        } catch (_: LinkageError) {
            return false
        }
        emitTransient { observer ->
            observer.onRotationOverlayRemoved(
                generation,
                android.os.SystemClock.elapsedRealtimeNanos(),
            )
        }
        val attached = attach() != null
        if (attached) {
            emitTransient { observer ->
                observer.onRotationOverlayAttached(
                    generation,
                    android.os.SystemClock.elapsedRealtimeNanos(),
                )
            }
        }
        return attached
    }

    /**
     * Invalidates once after canonical masks are committed, then waits until every expected tile
     * has actually left [GoogleFogTileProvider] and an SDK map snapshot completes.
     *
     * Google Maps exposes no TileOverlay-installed callback. This barrier deliberately does not
     * claim one: the Activity must keep its independent opaque cover visible until [onInstalled]
     * reports success, and must fail closed if its own bounded timeout expires first.
     */
    fun onCanonicalPublished(
        generation: Long,
        visualProbePlan: FogSnapshotVisualProbePlan,
        onInstalled: (Boolean) -> Unit,
    ): Boolean {
        if (overlay == null) {
            lastRefreshFailure = GoogleFogRefreshFailure.NOT_ATTACHED
            return false
        }
        lateinit var candidate: FogTileRequestBarrier
        candidate = FogTileRequestBarrier(generation, visualProbePlan.coverageKeys) {
            mainHandler.post {
                if (deliveryBarrier !== candidate || overlay == null) return@post
                emitTransient { observer ->
                    observer.onDeliveryBarrierDrained(
                        generation,
                        android.os.SystemClock.elapsedRealtimeNanos(),
                    )
                }
                requestInstalledSnapshot(candidate, visualProbePlan, onInstalled, attempt = 1)
            }
        }
        deliveryBarrier = candidate
        if (!refreshPhases.onCanonicalPublished(generation)) {
            if (deliveryBarrier === candidate) deliveryBarrier = null
            lastRefreshFailure = if (lastClearAttemptSucceeded == false) {
                GoogleFogRefreshFailure.SDK_CLEAR_FAILURE
            } else {
                GoogleFogRefreshFailure.PHASE_REJECTED
            }
            return false
        }
        // The phase coordinator invoked clearTileCache synchronously inside the call above, so
        // this timestamp is taken immediately after the SDK clear returned.
        emitTransient { observer ->
            observer.onClearTileCacheIssued(
                generation,
                android.os.SystemClock.elapsedRealtimeNanos(),
            )
        }
        lastRefreshFailure = null
        return true
    }

    private fun requestInstalledSnapshot(
        candidate: FogTileRequestBarrier,
        visualProbePlan: FogSnapshotVisualProbePlan,
        onInstalled: (Boolean) -> Unit,
        attempt: Int,
    ) {
        if (deliveryBarrier !== candidate || overlay == null) return
        lastVisualProof = lastVisualProof.copy(snapshotAttempt = attempt)
        try {
            map.snapshot { snapshot ->
                if (deliveryBarrier !== candidate || overlay == null) {
                    snapshot?.recycle()
                    return@snapshot
                }
                if (
                    snapshot != null &&
                    snapshotProvesCanonicalFog(
                        snapshot,
                        visualProbePlan,
                        candidate.generation,
                    )
                ) {
                    deliveryBarrier = null
                    // The bitmap is only a renderer-completion signal. Release its native pixel
                    // storage immediately instead of retaining repeated full-screen snapshots
                    // until a later GC during pan/zoom benchmarks.
                    snapshot.recycle()
                    emitTransient { observer ->
                        observer.onInstallProven(
                            candidate.generation,
                            android.os.SystemClock.elapsedRealtimeNanos(),
                            attempt,
                        )
                    }
                    onInstalled(true)
                } else {
                    snapshot?.recycle()
                    retryInstalledSnapshot(candidate, visualProbePlan, onInstalled, attempt)
                }
            }
        } catch (_: Exception) {
            retryInstalledSnapshot(candidate, visualProbePlan, onInstalled, attempt)
        } catch (_: LinkageError) {
            retryInstalledSnapshot(candidate, visualProbePlan, onInstalled, attempt)
        }
    }

    private fun retryInstalledSnapshot(
        candidate: FogTileRequestBarrier,
        visualProbePlan: FogSnapshotVisualProbePlan,
        onInstalled: (Boolean) -> Unit,
        attempt: Int,
    ) {
        if (deliveryBarrier !== candidate || overlay == null) return
        if (attempt >= MAX_SNAPSHOT_ATTEMPTS) {
            deliveryBarrier = null
            onInstalled(false)
            return
        }
        mainHandler.postDelayed(
            { requestInstalledSnapshot(candidate, visualProbePlan, onInstalled, attempt + 1) },
            SNAPSHOT_RETRY_MILLIS,
        )
    }

    /**
     * Requires rendered fog-colour pixels from every tile with visible canonical unknown area.
     * Merely receiving a non-null snapshot is insufficient because outstanding TileProvider calls
     * and TileOverlay installation have no public completion callback.
     */
    private fun snapshotProvesCanonicalFog(
        snapshot: Bitmap,
        plan: FogSnapshotVisualProbePlan,
        generation: Long,
    ): Boolean {
        if (plan.probesByKey.isEmpty()) return true
        val projection = try {
            map.projection
        } catch (_: Exception) {
            return false
        } catch (_: LinkageError) {
            return false
        }
        var offScreenOnly = 0
        var mismatched = 0
        var minimumOnScreen = Int.MAX_VALUE
        val verified = plan.probesByKey.keys.count { key ->
            val sample = reduceFogProofBlocks(observeBlocks(snapshot, projection, plan, key, generation))
            minimumOnScreen = minOf(minimumOnScreen, sample.onScreenBlocks)
            // A tile whose probes ALL project off-screen has no on-screen pixels to prove —
            // the delivery barrier already owns its byte truth. Requiring it here deadlocks the
            // install (SP8: dense reveal plans put edge tiles' probes off-screen and the proof
            // could never complete). It stays counted for diagnostics but passes.
            if (sample.onScreenBlocks == 0) {
                offScreenOnly += 1
                return@count true
            }
            val requiredMatches = minOf(MINIMUM_MATCHING_BLOCKS_PER_TILE, sample.onScreenBlocks)
            val passed = sample.matchingBlocks >= requiredMatches
            if (!passed) mismatched += 1
            passed
        }
        lastVisualProof = GoogleFogVisualProof(
            requiredTileCount = plan.probesByKey.size,
            verifiedTileCount = verified,
            snapshotAttempt = lastVisualProof.snapshotAttempt,
            offScreenOnlyTileCount = offScreenOnly,
            mismatchedTileCount = mismatched,
            minimumOnScreenProbeCount = if (minimumOnScreen == Int.MAX_VALUE) 0 else minimumOnScreen,
        )
        return verified == plan.probesByKey.size
    }

    /**
     * Mirrors the production prover exactly: the unit the per-tile threshold counts is the planner
     * BLOCK, and a block's candidates are interchangeable, so the first match ends that block
     * (carry-forward F). Keeping this identical to `GoogleFogSnapshotProver` is the point of the
     * harness — a spike that proves under a looser oracle than production proves nothing.
     */
    private fun observeBlocks(
        snapshot: Bitmap,
        projection: com.google.android.gms.maps.Projection,
        plan: FogSnapshotVisualProbePlan,
        key: FogTileKey,
        generation: Long,
    ): List<FogProofBlockSample> = plan.probeBlocks(key).map { candidates ->
        var anyOnScreen = false
        var anyMatched = false
        for (candidate in candidates) {
            when (snapshotProbeObservation(snapshot, projection, candidate, generation)) {
                ProbeObservation.MATCH -> {
                    anyOnScreen = true
                    anyMatched = true
                }
                ProbeObservation.MISMATCH -> anyOnScreen = true
                ProbeObservation.OFF_SCREEN -> Unit
            }
            if (anyMatched) break
        }
        FogProofBlockSample(anyOnScreen = anyOnScreen, anyMatched = anyMatched)
    }

    private fun snapshotProbeObservation(
        snapshot: Bitmap,
        projection: com.google.android.gms.maps.Projection,
        probe: FogSnapshotVisualProbe,
        generation: Long,
    ): ProbeObservation {
        val point = try {
            projection.toScreenLocation(
                com.google.android.gms.maps.model.LatLng(probe.latitude, probe.longitude),
            )
        } catch (_: Exception) {
            return ProbeObservation.MISMATCH
        } catch (_: LinkageError) {
            return ProbeObservation.MISMATCH
        }
        val radius = if (probe.strongNeighbourhood) 1 else 0
        if (
            point.x - radius !in 0 until snapshot.width ||
            point.x + radius !in 0 until snapshot.width ||
            point.y - radius !in 0 until snapshot.height ||
            point.y + radius !in 0 until snapshot.height
        ) {
            return ProbeObservation.OFF_SCREEN
        }
        var matches = 0
        var sampled = 0
        for (offsetY in -radius..radius) {
            for (offsetX in -radius..radius) {
                sampled += 1
                if (
                    isFogColour(
                        snapshot[point.x + offsetX, point.y + offsetY],
                        generation,
                    )
                ) {
                    matches += 1
                }
            }
        }
        val matched = if (probe.strongNeighbourhood) {
            matches >= STRONG_PROBE_MATCHES
        } else {
            matches == sampled
        }
        return if (matched) ProbeObservation.MATCH else ProbeObservation.MISMATCH
    }

    private fun isFogColour(pixel: Int, generation: Long): Boolean {
        if (Color.alpha(pixel) != 255) return false
        return FogTilePngCodec.matchesGenerationColor(
            actual = app.trailveil.map.fog.FogTileColor(
                red = Color.red(pixel),
                green = Color.green(pixel),
                blue = Color.blue(pixel),
            ),
            generation = generation,
        )
    }

    /** Compatibility seam retained only for source-level boundary checks and tests. */
    fun refresh(
        generation: Long,
        visualProbePlan: FogSnapshotVisualProbePlan,
        onInstalled: (Boolean) -> Unit,
    ): Boolean = onCanonicalPublished(generation, visualProbePlan, onInstalled)

    fun detach() {
        val current = overlay
        overlay = null
        deliveryBarrier = null
        provider.setCanonicalDeliveryObserver(null)
        refreshPhases.reset()
        try {
            current?.remove()
        } catch (_: Exception) {
            // Provider cleanup failure must not escape Activity teardown or terminal fallback.
        } catch (_: LinkageError) {
            // Provider cleanup failure must not escape Activity teardown or terminal fallback.
        }
    }

    /**
     * `V02-005` stage 3 (SP6/SP8): a strictly READ-ONLY spike oracle. Issues one snapshot and
     * classifies every probe pixel of [plan] against [generation]'s palette without touching
     * [deliveryBarrier], [refreshPhases], [lastVisualProof], or the overlay, so a concurrent
     * canonical install can never be corrupted by a spike probe. Main-thread only.
     * `onResult(null)` on snapshot or projection failure.
     */
    fun probeCanonicalSnapshotForTesting(
        generation: Long,
        plan: FogSnapshotVisualProbePlan,
        onResult: (GoogleFogSpikeProbeResult?) -> Unit,
    ) {
        try {
            map.snapshot { snapshot ->
                if (snapshot == null) {
                    onResult(null)
                    return@snapshot
                }
                val result = try {
                    classifyProbePixels(snapshot, plan, generation)
                } catch (_: Exception) {
                    null
                } catch (_: LinkageError) {
                    null
                }
                snapshot.recycle()
                onResult(result)
            }
        } catch (_: Exception) {
            onResult(null)
        } catch (_: LinkageError) {
            onResult(null)
        }
    }

    private fun classifyProbePixels(
        snapshot: Bitmap,
        plan: FogSnapshotVisualProbePlan,
        generation: Long,
    ): GoogleFogSpikeProbeResult? {
        val projection = try {
            map.projection
        } catch (_: Exception) {
            return null
        } catch (_: LinkageError) {
            return null
        }
        var match = 0
        var placeholder = 0
        var stale = 0
        var other = 0
        var offScreen = 0
        var provenTiles = 0
        plan.probesByKey.keys.forEach { key ->
            var onScreenBlocks = 0
            var matchedBlocks = 0
            plan.probeBlocks(key).forEach { candidates ->
                var blockOnScreen = false
                var blockMatched = false
                for (probe in candidates) {
                    val point = try {
                        projection.toScreenLocation(
                            com.google.android.gms.maps.model.LatLng(
                                probe.latitude,
                                probe.longitude,
                            ),
                        )
                    } catch (_: Exception) {
                        null
                    } catch (_: LinkageError) {
                        null
                    }
                    if (
                        point == null ||
                        point.x !in 0 until snapshot.width ||
                        point.y !in 0 until snapshot.height
                    ) {
                        offScreen += 1
                        continue
                    }
                    blockOnScreen = true
                    val pixelClass =
                        GoogleFogSpikePixelClassifier.classify(
                            snapshot[point.x, point.y],
                            generation,
                        )
                    when (pixelClass) {
                        GoogleFogSpikePixelClass.MATCH -> {
                            match += 1
                            blockMatched = true
                        }
                        GoogleFogSpikePixelClass.PLACEHOLDER -> placeholder += 1
                        GoogleFogSpikePixelClass.STALE_PALETTE -> stale += 1
                        GoogleFogSpikePixelClass.OTHER -> other += 1
                    }
                    // The class counters describe the probes actually examined; a proven block
                    // stops here, exactly as the production prover does.
                    if (blockMatched) break
                }
                if (blockOnScreen) onScreenBlocks += 1
                if (blockMatched) matchedBlocks += 1
            }
            // Mirrors the production oracle's per-tile threshold shape: a tile with visible
            // canonical area needs min(3, onScreenBlocks) matching BLOCKS, and at least one on
            // screen. Blocks, never pixels — see observeBlocks.
            val required = minOf(MINIMUM_MATCHING_BLOCKS_PER_TILE, onScreenBlocks)
            if (required > 0 && matchedBlocks >= required) provenTiles += 1
        }
        val requiredTiles = plan.probesByKey.count { (_, probes) ->
            probes.any { probe ->
                val point = try {
                    projection.toScreenLocation(
                        com.google.android.gms.maps.model.LatLng(probe.latitude, probe.longitude),
                    )
                } catch (_: Exception) {
                    null
                } catch (_: LinkageError) {
                    null
                }
                point != null &&
                    point.x in 0 until snapshot.width &&
                    point.y in 0 until snapshot.height
            }
        }
        return GoogleFogSpikeProbeResult(
            matchProbes = match,
            placeholderProbes = placeholder,
            stalePaletteProbes = stale,
            otherProbes = other,
            offScreenProbes = offScreen,
            proven = provenTiles >= requiredTiles && requiredTiles > 0,
        )
    }

    fun attachedOverlay(): TileOverlay? = overlay

    /** Coordinate-free engineering diagnostic; never exposes tile identities or map positions. */
    fun pendingCanonicalTileCount(): Int? = deliveryBarrier?.remainingCount()

    /** SP5: elapsedRealtimeNanos of the most recent clearTileCache attempt; 0 when none yet. */
    fun lastClearAttemptAtNanosForTesting(): Long = lastClearAttemptAtNanos

    fun refreshFailure(): GoogleFogRefreshFailure? = lastRefreshFailure

    fun clearFailureClass(): String? = lastClearFailureClass

    fun visualProof(): GoogleFogVisualProof = lastVisualProof

    fun refreshSnapshot(): FogOverlayRefreshSnapshot = refreshPhases.snapshot()

    private enum class ProbeObservation {
        MATCH,
        MISMATCH,
        OFF_SCREEN,
    }

    private companion object {
        const val MAX_SNAPSHOT_ATTEMPTS = 10
        const val SNAPSHOT_RETRY_MILLIS = 250L
        const val STRONG_PROBE_MATCHES = 5

        /** Distinct planner BLOCKS, not pixels; must stay equal to the production prover's. */
        const val MINIMUM_MATCHING_BLOCKS_PER_TILE = 3
    }
}
