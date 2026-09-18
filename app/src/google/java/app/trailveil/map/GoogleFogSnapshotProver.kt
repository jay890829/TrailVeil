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
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.prepareFogProofPlan
import app.trailveil.map.fog.reduceFogProofBlocks
import app.trailveil.map.fog.tallyFogProof
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.Projection
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Final screen-pixel proof for the production surface; every retry obtains a fresh plan. */
internal class GoogleFogSnapshotProver(
    private val map: GoogleMap,
    private val scope: CoroutineScope,
    private val planForAttempt: suspend (Long, Int) -> FogSnapshotVisualProbePlan?,
    private val cameraEpoch: () -> Long,
    private val onProofObserved: (GoogleFogProofObservation) -> Unit = {},
    private val hostStopped: () -> Boolean = { false },
    /** Hides visible map overlays when their exclusion zones make the plan undecidable. */
    private val onUnprovablePlan: () -> Boolean = { false },
    /** Milliseconds for the event trace, on the binding's clock so both traces align. */
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var epoch = 0L
    @Volatile private var lifecycleEpoch = 0L
    private class ProofRun(
        val generation: Long,
        val proofEpoch: Long,
        val budget: FogSnapshotProofBudget,
        val snapshotNotBeforeMillis: Long,
        val onResult: (Boolean) -> Unit,
    )

    private var activeRun: ProofRun? = null
    private var planningJob: Job? = null
    private var resumeAfterStop = false

    /** Bounded event history (names, numbers, booleans) for device failure messages. */
    private val events = ArrayDeque<String>()
    val recentEvents: List<String> get() = events.toList()

    private fun note(event: String) {
        if (events.size >= EVENT_TRACE_LIMIT) events.removeFirst()
        events.addLast("$event@${nowMillis()}")
    }

    fun prove(generation: Long, snapshotNotBeforeMillis: Long = 0L, onResult: (Boolean) -> Unit) {
        planningJob?.cancel()
        val proofEpoch = ++epoch
        note("prove:$generation")
        val run = ProofRun(
            generation = generation,
            proofEpoch = proofEpoch,
            budget = FogSnapshotProofBudget(MAX_ATTEMPTS),
            snapshotNotBeforeMillis = snapshotNotBeforeMillis,
            onResult = onResult,
        )
        activeRun = run
        resumeAfterStop = hostStopped()
        // V03-013: the coordinator reveals the generation and calls this in one main-thread turn,
        // and the SDK applies that reveal only once the thread returns to its looper. A first
        // attempt taken inline asked for a frame the polygons could not be in yet: on the API 36
        // AVD every native generation read attempt,snapshot,eval:false,attempt,snapshot,eval:true
        // (a 250 ms retry plus a wasted snapshot and plan per cover), and waiting INSIDE the turn
        // did not help - plans of 60-210 ms before the snapshot still read false (b21). So the
        // first attempt is posted after a settle; its own planning then runs inside the window
        // the SDK is given. Later attempts, retries and the restart path are unchanged. A stale
        // post no-ops on the epoch/run guards at the top of attempt(); release() and a host stop
        // clear it exactly as they clear a stopped poll. Fail-closed: what moves later is a
        // verdict, never a lowering on weaker evidence - a held cover stays held until a passed
        // verdict; a PASS that would have come inline (the ON_START re-proof, a tile swap the
        // renderer had already applied) lowers its cover [FIRST_SNAPSHOT_SETTLE_MILLIS] later,
        // and a visible-at-rest reveal learns a FAILING verdict that much later. Every caller of
        // prove() pays it, not only the reveal it was measured for; see the constant.
        note("settle:$FIRST_SNAPSHOT_SETTLE_MILLIS")
        handler.postDelayed({ attempt(run) }, FIRST_SNAPSHOT_SETTLE_MILLIS)
    }

    fun release() {
        planningJob?.cancel()
        epoch += 1L
        activeRun = null
        resumeAfterStop = false
        handler.removeCallbacksAndMessages(null)
    }

    /** The coordinator discarded this generation; do not cancel a newer run or synthesize a verdict. */
    fun cancelGeneration(generation: Long) {
        if (activeRun?.generation != generation) return
        note("cancel:$generation")
        release()
    }

    /** Camera/overlay inputs changed while CPU planning or its warmup was still in flight. */
    fun onInputsChanged() {
        val run = activeRun ?: return
        val job = planningJob?.takeIf { it.isActive } ?: return
        job.cancel()
        run.budget.abandonActive()
        handler.removeCallbacksAndMessages(null)
        note("inputs:cancelPlan")
        if (hostStopped()) {
            resumeAfterStop = true
        } else {
            // Keep one paced retry; a late callback from the abandoned attempt cannot add another.
            handler.postDelayed({ attempt(run) }, RETRY_MILLIS)
        }
    }

    /** Invalidates any snapshot callback captured before the host stopped. */
    fun onHostStopped() {
        planningJob?.cancel()
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
            note("stopped")
            handler.postDelayed(
                { attempt(run) },
                STOPPED_POLL_MILLIS,
            )
            return
        }
        val attemptToken = run.budget.begin(lifecycleEpoch, cameraEpoch())
        if (attemptToken == null) {
            note("begin:null")
            return
        }
        note("attempt:${attemptToken.number}")
        planningJob = scope.launch { planAndSnapshot(run, attemptToken) }
    }

    private suspend fun planAndSnapshot(
        run: ProofRun,
        attemptToken: FogSnapshotProofBudget.Attempt,
    ) {
        // Re-plan on every pass. A plan captured before a tilt/pan can demand probes that are no
        // longer on screen and can never become true (F0/F2).
        val plan = try {
            planForAttempt(run.generation, attemptToken.number)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        val warmupRemaining = run.snapshotNotBeforeMillis - android.os.SystemClock.elapsedRealtime()
        if (warmupRemaining > 0L) {
            note("warmup:$warmupRemaining")
            delay(warmupRemaining)
        }
        // Planning yields the main thread. Never prepare overlays or ask for a snapshot using
        // geometry captured before a camera move, host stop or replacement proof.
        if (!isLive(run, attemptToken)) {
            note("plan:notLive")
            retrySameAttempt(run, attemptToken)
            return
        }
        if (plan == null) {
            note("plan:null")
            retryOrFinish(run, attemptToken)
            return
        }
        note("bank:${plan.candidateBank.name}")
        val preparation = try {
            prepareFogProofPlan(plan, onUnprovablePlan)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        if (preparation == null) {
            note("prep:null")
            retryOrFinish(run, attemptToken)
            return
        }
        if (!preparation.canProve) {
            note("unprovable:hidden=${preparation.overlaysHidden}")
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
                    note("snapshot:stale")
                    snapshot?.recycle()
                    return@snapshot
                }
                if (!isLive(run, attemptToken)) {
                    note("snapshot:notLive")
                    snapshot?.recycle()
                    retrySameAttempt(run, attemptToken)
                    return@snapshot
                }
                note(if (snapshot == null) "snapshot:null" else "snapshot:${snapshot.width}x${snapshot.height}")
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
                    note(
                        "eval:${observation.passed}:" +
                            "${observation.verifiedTileCount}/${observation.requiredTileCount}" +
                            "/off${observation.offScreenTileCount}/bank=${plan.candidateBank.name}",
                    )
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
            note("snapshot:exception")
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
        if (activeRun !== run || run.proofEpoch != epoch || !run.budget.abandon(attempt)) {
            note("retrySame:dropped")
            return
        }
        note("retrySame")
        handler.postDelayed(
            { attempt(run) },
            if (hostStopped()) STOPPED_POLL_MILLIS else RETRY_MILLIS,
        )
    }

    private fun retryOrFinish(
        run: ProofRun,
        attempt: FogSnapshotProofBudget.Attempt,
    ) {
        if (activeRun !== run || run.proofEpoch != epoch) {
            note("retryOrFinish:stale")
            return
        }
        when (run.budget.recordFailure(attempt)) {
            null -> note("retryOrFinish:null")
            false -> {
                note("result:false")
                activeRun = null
                run.onResult(false)
            }
            true -> {
                note("retry")
                handler.postDelayed({ attempt(run) }, RETRY_MILLIS)
            }
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
        val pixels = ProofPixels(generation)
        val tileNotes = StringBuilder()
        val samples = plan.probesByKey.keys.mapIndexed { index, key ->
            var firstMismatch: String? = null
            // Carry-forward F: the unit the per-tile threshold counts is the BLOCK, and a block's
            // candidates are interchangeable. Stopping at the first match keeps the unoccluded case
            // at exactly one projection per block, the cost before the fallbacks existed; only a
            // block whose leading candidate is hidden pays for the rest.
            val blocks = ArrayList<FogProofBlockSample>()
            var matchingBlocks = 0
            for (candidates in plan.probeBlocks(key)) {
                var anyOnScreen = false
                var anyMatched = false
                for (candidate in candidates) {
                    when (observe(snapshot, projection, candidate, pixels)) {
                        ProbeObservation.MATCH -> {
                            anyOnScreen = true
                            anyMatched = true
                        }
                        ProbeObservation.MISMATCH -> {
                            anyOnScreen = true
                            if (firstMismatch == null) firstMismatch = describeMismatch(snapshot, projection, candidate)
                        }
                        ProbeObservation.OFF_SCREEN -> Unit
                    }
                    if (anyMatched) break
                }
                blocks += FogProofBlockSample(anyOnScreen = anyOnScreen, anyMatched = anyMatched)
                // After three matching blocks, onScreen >= 3 and min(3, onScreen) is satisfied.
                // Further blocks cannot change this tile's verdict. Tiles below quorum still
                // scan every block, preserving both the off-screen exemption and failure rule.
                if (anyMatched && ++matchingBlocks >= MINIMUM_MATCHING_BLOCKS_PER_TILE) break
            }
            val sample = reduceFogProofBlocks(blocks)
            val required = minOf(MINIMUM_MATCHING_BLOCKS_PER_TILE, sample.onScreenBlocks)
            if (sample.onScreenBlocks > 0 && sample.matchingBlocks < required && tileNotes.length < 400) {
                tileNotes.append("t$index:on${sample.onScreenBlocks}/m${sample.matchingBlocks}:${firstMismatch ?: "?"} ")
            }
            sample
        }
        // The verdict rule is provider-neutral and lives in src/main so it can be unit tested; this
        // file cannot be reached from any JVM test source set. See tallyFogProof for why an
        // off-screen-only tile is exempt yet a plan can never pass on zero verified tiles.
        val tally = tallyFogProof(samples, MINIMUM_MATCHING_BLOCKS_PER_TILE)
        if (!tally.passed) {
            val expected = FogTilePngCodec.colorForGeneration(generation)
            note(
                "tiles:[${tileNotes.toString().trim()}] window=" +
                    "r${FogTilePngCodec.revealedFogChannelRange(expected.red)}" +
                    "g${FogTilePngCodec.revealedFogChannelRange(expected.green)}" +
                    "b${FogTilePngCodec.revealedFogChannelRange(expected.blue)}",
            )
        }
        return GoogleFogProofObservation(
            generation = generation,
            attempt = attempt,
            requiredTileCount = tally.requiredTiles,
            verifiedTileCount = tally.verifiedTiles,
            offScreenTileCount = tally.offScreenTiles,
            passed = tally.passed,
        )
    }

    /** The probe's centre pixel and its 3x3 neighbourhood's channel ranges, as hex (colours only). */
    private fun describeMismatch(snapshot: Bitmap, projection: Projection, probe: FogSnapshotVisualProbe): String {
        val point = try {
            projection.toScreenLocation(LatLng(probe.latitude, probe.longitude))
        } catch (_: Exception) {
            return "noPoint"
        }
        if (point.x !in 1 until snapshot.width - 1 || point.y !in 1 until snapshot.height - 1) return "edge"
        val centre = snapshot[point.x, point.y]
        var minRed = 255; var maxRed = 0; var minGreen = 255; var maxGreen = 0; var minBlue = 255; var maxBlue = 0
        for (dy in -1..1) for (dx in -1..1) {
            val pixel = snapshot[point.x + dx, point.y + dy]
            minRed = minOf(minRed, Color.red(pixel)); maxRed = maxOf(maxRed, Color.red(pixel))
            minGreen = minOf(minGreen, Color.green(pixel)); maxGreen = maxOf(maxGreen, Color.green(pixel))
            minBlue = minOf(minBlue, Color.blue(pixel)); maxBlue = maxOf(maxBlue, Color.blue(pixel))
        }
        return String.format("%06X", centre and 0xFFFFFF) +
            "(r$minRed-$maxRed,g$minGreen-$maxGreen,b$minBlue-$maxBlue,a${Color.alpha(centre)})" +
            if (probe.strongNeighbourhood) "s" else "e"
    }

    private fun observe(
        snapshot: Bitmap,
        projection: Projection,
        probe: FogSnapshotVisualProbe,
        pixels: ProofPixels,
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
        if (radius == 0) {
            return if (pixels.matches(snapshot[point.x, point.y])) ProbeObservation.MATCH else ProbeObservation.MISMATCH
        }
        snapshot.getPixels(pixels.neighbourhood, 0, 3, point.x - 1, point.y - 1, 3, 3)
        var matches = 0
        for (index in 0 until 9) {
            if (pixels.matches(pixels.neighbourhood[index])) matches++
            if (matches >= STRONG_MATCHES) return ProbeObservation.MATCH
            if (matches + 8 - index < STRONG_MATCHES) return ProbeObservation.MISMATCH
        }
        return ProbeObservation.MISMATCH
    }

    /**
     * V02-012 design 2: the overlay under verification is revealed at the fog display opacity, so
     * a fog pixel is the generation's colour blended over the basemap. The window assumes the
     * basemap is at least [FogTilePngCodec.BASEMAP_MINIMUM_CHANNEL] per channel where probed -
     * the light default style everywhere except labels and icons, which the SDK draws above
     * the overlay and which the probe plan's exclusion zones and the 5-of-9 rule tolerate.
     */
    private class ProofPixels(private val generation: Long) {
        val neighbourhood = IntArray(9)
        // Lazy keeps an off-screen-only attempt from evaluating a colour it never samples.
        private val windows: IntArray by lazy(LazyThreadSafetyMode.NONE) {
            val colour = FogTilePngCodec.colorForGeneration(generation)
            val red = FogTilePngCodec.revealedFogChannelRange(colour.red)
            val green = FogTilePngCodec.revealedFogChannelRange(colour.green)
            val blue = FogTilePngCodec.revealedFogChannelRange(colour.blue)
            intArrayOf(red.first, red.last, green.first, green.last, blue.first, blue.last)
        }
        fun matches(pixel: Int): Boolean {
            val range = windows
            val red = Color.red(pixel); val green = Color.green(pixel); val blue = Color.blue(pixel)
            return red >= range[0] && red <= range[1] && green >= range[2] && green <= range[3] &&
                blue >= range[4] && blue <= range[5]
        }
    }

    private enum class ProbeObservation { MATCH, MISMATCH, OFF_SCREEN }

    private companion object {
        const val MAX_ATTEMPTS = 10
        const val EVENT_TRACE_LIMIT = 40
        const val RETRY_MILLIS = 250L

        /**
         * How long `prove()` lets the main thread return to its looper before the FIRST attempt
         * of a proof plans and snapshots.
         *
         * The NEED is measured: with the attempt inline (b20), and with it delayed inside the same
         * turn by its own 60-210 ms plan (b21), the first snapshot never carried the just-revealed
         * polygons on the API 36 AVD - every native generation read
         * `attempt,snapshot,eval:false,attempt,snapshot,eval:true`, a wasted 250 ms retry, snapshot
         * and plan per cover. The VALUE is not measured: it matches the safety cover's lowering
         * settle (`GoogleFogSafetyOverlay.LOWER_SETTLE_MILLIS`) against the same renderer, and is
         * accepted by the ledger's digests rather than by argument - b22 read
         * `prove,settle,attempt,snapshot,eval:true`, one attempt, on native generations up to four
         * partitions and on raster fallbacks, while reveals attaching 12-16 partitions (6.7-9.2k
         * vertices, attach 56-70 ms) still read `eval:false` once first. So it is a floor for small
         * reveals, not a bound for large ones (V03-013 track-native evidence, E round).
         *
         * Every caller of `prove()` pays it - the reveal it was measured for, the ON_START re-proof
         * whose turn revealed nothing, and the tile path - and the badge's `proof=` clock, which
         * starts at `prove()`, contains it while `plan=` does not; the `settle` event in the digest
         * is what names it.
         */
        const val FIRST_SNAPSHOT_SETTLE_MILLIS = 50L

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
