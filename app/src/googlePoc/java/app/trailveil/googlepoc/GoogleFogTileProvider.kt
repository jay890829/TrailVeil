package app.trailveil.googlepoc

import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.FogTileProviderAdapter
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
) : TileProvider {
    private val canonicalDeliveryObserver =
        AtomicReference<((generation: Long, key: FogTileKey) -> Unit)?>(null)

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val response = adapter.tileResponse(x = x, y = y, zoom = zoom)
        val generation = response.publishedGeneration
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
            response.bytes,
        )
    }

    fun setCanonicalDeliveryObserver(
        observer: ((generation: Long, key: FogTileKey) -> Unit)?,
    ) {
        canonicalDeliveryObserver.set(observer)
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
    private val refreshPhases = FogOverlayRefreshPhaseCoordinator {
        try {
            lastClearAttemptSucceeded = false
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

    init {
        provider.setCanonicalDeliveryObserver { generation, key ->
            deliveryBarrier?.record(generation, key)
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
            if (!rotateOverlay()) return false
            refreshPhases.reset()
        }
        return refreshPhases.onGenerationStarted(generation)
    }

    /** Removes all SDK/native tile state before a visual signature is ever reused. */
    private fun rotateOverlay(): Boolean {
        val current = overlay ?: return false
        overlay = null
        try {
            current.remove()
        } catch (_: Exception) {
            return false
        } catch (_: LinkageError) {
            return false
        }
        return attach() != null
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
        val verified = plan.probesByKey.values.count { probes ->
            val observations = probes.map { probe ->
                snapshotProbeObservation(snapshot, projection, probe, generation)
            }
            val onScreen = observations.filter { observation ->
                observation != ProbeObservation.OFF_SCREEN
            }
            minimumOnScreen = minOf(minimumOnScreen, onScreen.size)
            val requiredMatches = minOf(MINIMUM_MATCHING_PROBES_PER_TILE, onScreen.size)
            val passed = requiredMatches > 0 &&
                onScreen.count { observation -> observation == ProbeObservation.MATCH } >=
                requiredMatches
            if (!passed) {
                if (onScreen.isEmpty()) offScreenOnly += 1 else mismatched += 1
            }
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

    fun attachedOverlay(): TileOverlay? = overlay

    /** Coordinate-free engineering diagnostic; never exposes tile identities or map positions. */
    fun pendingCanonicalTileCount(): Int? = deliveryBarrier?.remainingCount()

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
        const val MINIMUM_MATCHING_PROBES_PER_TILE = 3
    }
}
