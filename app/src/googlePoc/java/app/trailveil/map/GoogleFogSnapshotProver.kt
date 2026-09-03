package app.trailveil.map

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.get
import app.trailveil.map.fog.FogProofBlockSample
import app.trailveil.map.fog.FogSnapshotProofBudget
import app.trailveil.map.fog.FogSnapshotVisualProbe
import app.trailveil.map.fog.FogSnapshotVisualProbePlan
import app.trailveil.map.fog.FogTileColor
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.prepareFogProofPlan
import app.trailveil.map.fog.reduceFogProofBlocks
import app.trailveil.map.fog.tallyFogProof
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.Projection
import com.google.android.gms.maps.model.LatLng

/** Final screen-pixel proof for the production surface; every retry obtains a fresh plan. */
internal class GoogleFogSnapshotProver(
    private val map: GoogleMap,
    private val planForAttempt: (Long) -> FogSnapshotVisualProbePlan?,
    private val cameraEpoch: () -> Long,
    private val onProofObserved: (GoogleFogProofObservation) -> Unit = {},
    private val hostStopped: () -> Boolean = { false },
    /** Hides visible map overlays when their exclusion zones make the plan undecidable. */
    private val onUnprovablePlan: () -> Boolean = { false },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var epoch = 0L
    @Volatile private var lifecycleEpoch = 0L
    private class ProofRun(
        val generation: Long,
        val proofEpoch: Long,
        val budget: FogSnapshotProofBudget,
        val onResult: (Boolean) -> Unit,
    )

    private var activeRun: ProofRun? = null
    private var resumeAfterStop = false

    fun prove(generation: Long, onResult: (Boolean) -> Unit) {
        val proofEpoch = ++epoch
        val run = ProofRun(
            generation = generation,
            proofEpoch = proofEpoch,
            budget = FogSnapshotProofBudget(MAX_ATTEMPTS),
            onResult = onResult,
        )
        activeRun = run
        resumeAfterStop = hostStopped()
        attempt(run)
    }

    fun release() {
        epoch += 1L
        activeRun = null
        resumeAfterStop = false
        handler.removeCallbacksAndMessages(null)
    }

    /** Invalidates any snapshot callback captured before the host stopped. */
    fun onHostStopped() {
        lifecycleEpoch += 1L
        if (activeRun != null) {
            // Remove delayed retries as well as abandoning a callback in flight. The foreground
            // callback below starts the same attempt once, so a stale delayed runnable cannot race
            // it and consume the budget twice.
            resumeAfterStop = true
            activeRun?.budget?.abandonActive()
            handler.removeCallbacksAndMessages(null)
        }
    }

    /** Lets an in-flight proof continue with its preserved attempt budget. */
    fun onHostStarted(expectedGeneration: Long? = null): Boolean {
        lifecycleEpoch += 1L
        if (!resumeAfterStop) return false
        resumeAfterStop = false
        val run = activeRun ?: return false
        if (expectedGeneration == null || run.generation != expectedGeneration) {
            // The coordinator has moved on to another generation while this proof was paused.
            // Drop the stale run; its eventual SDK callback is still recycled by the run identity
            // check and must not consume the replacement generation's proof budget.
            activeRun = null
            return false
        }
        // A proof created after the host was already stopped may have a stopped-poll runnable even
        // though onHostStopped() had no active run to clear. Remove it before starting the one
        // foreground attempt, or that stale poll can race a later retry and shorten the budget's
        // deliberate pacing.
        handler.removeCallbacksAndMessages(null)
        attempt(run)
        return true
    }

    private fun attempt(
        run: ProofRun,
    ) {
        if (activeRun !== run || run.proofEpoch != epoch) return
        // The attempt budget is the third bounded budget a stopped host cannot satisfy: a
        // non-rendering surface cannot produce generation-coloured pixels, so every attempt spent
        // while stopped is spent against a surface with no way to pass, and exhausting them
        // classifies as a hard proof failure — terminal when nothing is installed yet. Wait
        // without consuming the budget; the cover stays up throughout, so this is fail-closed.
        if (hostStopped()) {
            handler.postDelayed(
                { attempt(run) },
                STOPPED_POLL_MILLIS,
            )
            return
        }
        val attemptToken = run.budget.begin(lifecycleEpoch, cameraEpoch()) ?: return
        // Re-plan on every pass. A plan captured before a tilt/pan can demand probes that are no
        // longer on screen and can never become true (F0/F2).
        val plan = try {
            planForAttempt(run.generation)
        } catch (_: Exception) {
            null
        }
        if (plan == null) {
            retryOrFinish(run, attemptToken)
            return
        }
        val preparation = try {
            prepareFogProofPlan(plan, onUnprovablePlan)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        if (preparation == null) {
            retryOrFinish(run, attemptToken)
            return
        }
        if (!preparation.canProve) {
            if (preparation.overlaysHidden && run.budget.abandon(attemptToken)) {
                // Hiding is a state transition, not a proof failure. Re-plan after the map has
                // applied the visibility change; consuming an attempt here would make the proof
                // budget depend on marker/track occlusion rather than renderer evidence.
                handler.postDelayed({ attempt(run) }, RETRY_MILLIS)
            } else {
                // A caller that could not hide the overlays leaves the plan undecidable. Keep the
                // bounded retry/fail-closed behavior; never turn a zone-blocked plan into a pass.
                retryOrFinish(run, attemptToken)
            }
            return
        }
        try {
            map.snapshot { snapshot ->
                if (activeRun !== run || run.proofEpoch != epoch) {
                    snapshot?.recycle()
                    return@snapshot
                }
                if (!isLive(run, attemptToken)) {
                    snapshot?.recycle()
                    retrySameAttempt(run, attemptToken)
                    return@snapshot
                }
                val observation = snapshot?.let { bitmap ->
                    try {
                        evaluate(bitmap, plan, run.generation, attemptToken.number)
                    } catch (_: Exception) {
                        null
                    } catch (_: LinkageError) {
                        null
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (observation != null) {
                    try {
                        onProofObserved(observation)
                    } catch (_: Exception) {
                        // A diagnostic observer is not part of the proof oracle.
                    } catch (_: LinkageError) {
                        // Same isolation for test-only/provider linkage callbacks.
                    }
                }
                if (observation?.passed == true) {
                    if (run.budget.recordSuccess(attemptToken)) {
                        activeRun = null
                        run.onResult(true)
                    }
                } else {
                    retryOrFinish(run, attemptToken)
                }
            }
        } catch (_: Exception) {
            if (!isLive(run, attemptToken)) {
                retrySameAttempt(run, attemptToken)
            } else {
                retryOrFinish(run, attemptToken)
            }
        } catch (_: LinkageError) {
            if (!isLive(run, attemptToken)) {
                retrySameAttempt(run, attemptToken)
            } else {
                retryOrFinish(run, attemptToken)
            }
        }
    }

    private fun retrySameAttempt(
        run: ProofRun,
        attempt: FogSnapshotProofBudget.Attempt,
    ) {
        if (activeRun !== run || run.proofEpoch != epoch || !run.budget.abandon(attempt)) return
        handler.postDelayed(
            { attempt(run) },
            if (hostStopped()) STOPPED_POLL_MILLIS else RETRY_MILLIS,
        )
    }

    private fun retryOrFinish(
        run: ProofRun,
        attempt: FogSnapshotProofBudget.Attempt,
    ) {
        if (activeRun !== run || run.proofEpoch != epoch) return
        when (run.budget.recordFailure(attempt)) {
            null -> Unit
            false -> {
                activeRun = null
                run.onResult(false)
            }
            true -> handler.postDelayed({ attempt(run) }, RETRY_MILLIS)
        }
    }

    private fun isLive(
        run: ProofRun,
        attempt: FogSnapshotProofBudget.Attempt,
    ): Boolean = activeRun === run && run.proofEpoch == epoch && !hostStopped() &&
        run.budget.isCurrent(attempt, lifecycleEpoch, cameraEpoch())

    private fun evaluate(
        snapshot: Bitmap,
        plan: FogSnapshotVisualProbePlan,
        generation: Long,
        attempt: Int,
    ): GoogleFogProofObservation {
        if (plan.probesByKey.isEmpty()) {
            return GoogleFogProofObservation(
                generation = generation,
                attempt = attempt,
                requiredTileCount = 0,
                verifiedTileCount = 0,
                offScreenTileCount = 0,
                passed = true,
            )
        }
        val projection = map.projection
        val samples = plan.probesByKey.keys.map { key ->
            // Carry-forward F: the unit the per-tile threshold counts is the BLOCK, and a block's
            // candidates are interchangeable. Stopping at the first match keeps the unoccluded case
            // at exactly one projection per block, the cost before the fallbacks existed; only a
            // block whose leading candidate is hidden pays for the rest.
            val blocks = plan.probeBlocks(key).map { candidates ->
                var anyOnScreen = false
                var anyMatched = false
                for (candidate in candidates) {
                    when (observe(snapshot, projection, candidate, generation)) {
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
            reduceFogProofBlocks(blocks)
        }
        // The verdict rule is provider-neutral and lives in src/main so it can be unit tested; this
        // file cannot be reached from any JVM test source set. See tallyFogProof for why an
        // off-screen-only tile is exempt yet a plan can never pass on zero verified tiles.
        val tally = tallyFogProof(samples, MINIMUM_MATCHING_BLOCKS_PER_TILE)
        return GoogleFogProofObservation(
            generation = generation,
            attempt = attempt,
            requiredTileCount = tally.requiredTiles,
            verifiedTileCount = tally.verifiedTiles,
            offScreenTileCount = tally.offScreenTiles,
            passed = tally.passed,
        )
    }

    private fun observe(
        snapshot: Bitmap,
        projection: Projection,
        probe: FogSnapshotVisualProbe,
        generation: Long,
    ): ProbeObservation {
        val point = try {
            projection.toScreenLocation(LatLng(probe.latitude, probe.longitude))
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
        var samples = 0
        for (offsetY in -radius..radius) {
            for (offsetX in -radius..radius) {
                samples += 1
                if (matchesGeneration(snapshot[point.x + offsetX, point.y + offsetY], generation)) {
                    matches += 1
                }
            }
        }
        val passed = if (probe.strongNeighbourhood) matches >= STRONG_MATCHES else matches == samples
        return if (passed) ProbeObservation.MATCH else ProbeObservation.MISMATCH
    }

    private fun matchesGeneration(pixel: Int, generation: Long): Boolean =
        Color.alpha(pixel) == 255 && FogTilePngCodec.matchesGenerationColor(
            actual = FogTileColor(
                red = Color.red(pixel),
                green = Color.green(pixel),
                blue = Color.blue(pixel),
            ),
            generation = generation,
        )

    private enum class ProbeObservation { MATCH, MISMATCH, OFF_SCREEN }

    private companion object {
        const val MAX_ATTEMPTS = 10
        const val RETRY_MILLIS = 250L

        /** Slower than [RETRY_MILLIS]: while stopped there is nothing to observe, only to wait for. */
        const val STOPPED_POLL_MILLIS = 1_000L
        const val STRONG_MATCHES = 5

        /**
         * Distinct planner BLOCKS that must show fog before a visible tile counts as verified —
         * never individual pixels. A block is proven by any one of its interchangeable candidates,
         * which is what stops one Google label from making a sparse tile unverifiable forever.
         */
        const val MINIMUM_MATCHING_BLOCKS_PER_TILE = 3
    }
}

internal data class GoogleFogProofObservation(
    val generation: Long,
    val attempt: Int,
    val requiredTileCount: Int,
    val verifiedTileCount: Int,
    val offScreenTileCount: Int,
    val passed: Boolean,
)
