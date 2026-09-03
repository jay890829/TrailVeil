package app.trailveil.googlepoc

import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 3, SP10: does the superseded flight's `onCancel` dispatch AFTER the
 * replacement's ticket claim and launch (the MapLibre hazard shape a boolean claim dies on), does
 * the AtomicLong CAS release leave the live claim standing in 100% of valid supersede pairs, and
 * what move-started reasons do animateCamera vs a real injected swipe report?
 *
 * The probe replicates the production sequence exactly (mapLibre actual lines 549–562):
 * increment-ticket THEN animate; CAS(myId, IDLE) release inside both terminal callbacks.
 *
 * Opt-in: `trailveilCameraFlightSpike=true`; knobs `trailveilRendererRequest` (default legacy),
 * `trailveilFlightPairCount` (default 60), `trailveilFlightDurationMs` (600),
 * `trailveilFlightSupersedeDelayMs` (120).
 */
@RunWith(AndroidJUnit4::class)
class GoogleCameraFlightOrderingTest {

    private enum class TerminalKind { CANCEL, FINISH, DROPPED_WATCHDOG, PENDING }

    private class PairSample {
        var firstId = 0L
        var replacementId = 0L
        var tFirstLaunch = 0L
        var tReplacementLaunch = 0L
        var tReplacementLaunchReturn = 0L
        @Volatile var firstTerminal = TerminalKind.PENDING
        @Volatile var tFirstTerminal = 0L
        @Volatile var firstTerminalOnMain = true
        @Volatile var ticketSeenAtFirstTerminal = 0L
        @Volatile var firstCasRejected = false
        @Volatile var booleanSeenAtFirstTerminal = true
        @Volatile var replacementTerminal = TerminalKind.PENDING
        @Volatile var replacementReleasedToIdle = false
        @Volatile var booleanClearedWhileReplacementAirborne = false

        /** Ground truth read at replacement-launch instant: was the first flight already
         *  terminal? Replaces any wall-clock airborne proxy, which install churn defeats. */
        @Volatile var firstWasTerminalAtReplacementLaunch = false
    }

    @Test
    fun flightTicketOrderingAndReasonCodes() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilCameraFlightSpike")
        SpikeScenarioSupport.assumeKeyConfigured()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val requestedRenderer = arguments.getString("trailveilRendererRequest") ?: "legacy"
        val pairTarget = (arguments.getString("trailveilFlightPairCount")?.toIntOrNull() ?: 60)
            .coerceAtLeast(50)
        val durationMs = arguments.getString("trailveilFlightDurationMs")?.toIntOrNull() ?: 600
        val delayMs = arguments.getString("trailveilFlightSupersedeDelayMs")?.toLongOrNull() ?: 120L

        val renderer = GoogleRendererPin.initialize(context, requestedRenderer)

        val scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
        try {
            val map = SpikeScenarioSupport.awaitGoogleMap(
                scenario,
                SpikeScenarioSupport.awaitMapView(scenario),
            )
            SpikeScenarioSupport.awaitFallbackGone(scenario)

            // Single-slot reason/idle multiplex through the activity's own callbacks field, so
            // the PoC's fog listeners keep running untouched.
            val firstReason = AtomicReference<AtomicInteger?>(null)
            val idleLatch = AtomicReference<CountDownLatch?>(null)
            scenario.onActivity { activity ->
                activity.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCameraMoveStarted(reason: Int) {
                        firstReason.get()?.compareAndSet(Int.MIN_VALUE, reason)
                    }

                    override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                        idleLatch.get()?.countDown()
                    }
                }
            }

            fun settle(tag: String) {
                if (SpikeScenarioSupport.isTerminalFallback(scenario)) {
                    val diagnostic = AtomicReference<GoogleFogInstallDiagnostic>()
                    scenario.onActivity { activity ->
                        diagnostic.set(activity.fogInstallDiagnosticForTesting())
                    }
                    error("SP10 INVALID at $tag: terminal fallback entered — ${diagnostic.get()}")
                }
                SpikeScenarioSupport.awaitFallbackGone(scenario)
            }

            // ---- Phase R: reason codes ----
            val animHistogram = mutableMapOf<Int, Int>()
            val gestureHistogram = mutableMapOf<Int, Int>()
            var gestureRetries = 0
            repeat(REASON_SAMPLES) { index ->
                val reasonSlot = AtomicInteger(Int.MIN_VALUE)
                firstReason.set(reasonSlot)
                val idle = CountDownLatch(1)
                idleLatch.set(idle)
                val target = if (index % 2 == 0) TARGET_A else TARGET_B
                scenario.onActivity {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, PAIR_ZOOM), 250, null)
                }
                assertTrue("SP10 anim reason sample $index never idled", idle.await(10, TimeUnit.SECONDS))
                val reason = reasonSlot.get()
                if (reason != Int.MIN_VALUE) animHistogram.merge(reason, 1, Int::plus)
                settle("anim-reason-$index")
            }
            val mapView = SpikeScenarioSupport.awaitMapView(scenario)
            repeat(REASON_SAMPLES) { index ->
                var attempts = 0
                var recorded = false
                while (attempts < 3 && !recorded) {
                    attempts += 1
                    val reasonSlot = AtomicInteger(Int.MIN_VALUE)
                    firstReason.set(reasonSlot)
                    val idle = CountDownLatch(1)
                    idleLatch.set(idle)
                    val location = IntArray(2)
                    instrumentation.runOnMainSync { mapView.getLocationOnScreen(location) }
                    val centerX = location[0] + mapView.width / 2
                    val centerY = location[1] + mapView.height / 2
                    val dx = mapView.width / 4
                    shell("input swipe ${centerX - dx} $centerY ${centerX + dx} $centerY 200")
                    val sawMove = waitFor(2_000L) { reasonSlot.get() != Int.MIN_VALUE }
                    if (!sawMove) {
                        gestureRetries += 1
                        continue
                    }
                    idle.await(10, TimeUnit.SECONDS)
                    gestureHistogram.merge(reasonSlot.get(), 1, Int::plus)
                    recorded = true
                    settle("gesture-reason-$index")
                }
                assertTrue("SP10 INVALID: gesture injection exhausted retries", recorded)
            }
            firstReason.set(null)

            // ---- Phase S: supersede storm ----
            val ticket = AtomicLong(IDLE_FLIGHT)
            val booleanClaim = AtomicBoolean(false)
            val samples = mutableListOf<PairSample>()
            val zeroDelaySamples = mutableListOf<PairSample>()
            var reruns = 0
            var droppedOnce = 0
            var droppedTwice = 0
            var spontaneous = 0

            fun runPair(pairIndex: Int, supersedeDelay: Long, record: MutableList<PairSample>?): PairSample {
                val sample = PairSample()
                val done = CountDownLatch(2)
                scenario.onActivity {
                    // The first flight must be REAL: pick whichever target is farther from the
                    // current camera. Parity alternation deadlocks — each pair parks the camera
                    // exactly on the next pair's first target, so the 600 ms animation
                    // instant-finishes before the supersede fires and every pair is invalid.
                    val current = map.cameraPosition.target
                    val aDistance = Math.abs(current.latitude - TARGET_A.latitude) +
                        Math.abs(current.longitude - TARGET_A.longitude)
                    val bDistance = Math.abs(current.latitude - TARGET_B.latitude) +
                        Math.abs(current.longitude - TARGET_B.longitude)
                    val firstTarget = if (aDistance >= bDistance) TARGET_A else TARGET_B
                    val secondTarget = if (firstTarget === TARGET_A) TARGET_B else TARGET_A
                    sample.firstId = ticket.incrementAndGet()
                    booleanClaim.set(true)
                    sample.tFirstLaunch = SystemClock.elapsedRealtimeNanos()
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(firstTarget, PAIR_ZOOM),
                        durationMs,
                        object : GoogleMap.CancelableCallback {
                            fun terminal(kind: TerminalKind) {
                                sample.tFirstTerminal = SystemClock.elapsedRealtimeNanos()
                                sample.firstTerminalOnMain = Looper.myLooper() == Looper.getMainLooper()
                                sample.ticketSeenAtFirstTerminal = ticket.get()
                                sample.booleanSeenAtFirstTerminal = booleanClaim.get()
                                sample.firstCasRejected = !ticket.compareAndSet(sample.firstId, IDLE_FLIGHT)
                                // The boolean hazard control: an unconditional clear, exactly
                                // what a boolean claim scheme would do here. If the replacement
                                // is still airborne at this instant, the boolean just lost the
                                // live flight's claim — the hazard the AtomicLong ticket exists
                                // to prevent.
                                booleanClaim.set(false)
                                sample.booleanClearedWhileReplacementAirborne =
                                    sample.tReplacementLaunch != 0L &&
                                    sample.replacementTerminal == TerminalKind.PENDING
                                sample.firstTerminal = kind
                                done.countDown()
                            }

                            override fun onCancel() = terminal(TerminalKind.CANCEL)
                            override fun onFinish() = terminal(TerminalKind.FINISH)
                        },
                    )
                    android.os.Handler(Looper.getMainLooper()).postDelayed({
                        sample.firstWasTerminalAtReplacementLaunch =
                            sample.firstTerminal != TerminalKind.PENDING
                        sample.replacementId = ticket.incrementAndGet()
                        booleanClaim.set(true)
                        sample.tReplacementLaunch = SystemClock.elapsedRealtimeNanos()
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(secondTarget, PAIR_ZOOM),
                            durationMs,
                            object : GoogleMap.CancelableCallback {
                                fun terminal(kind: TerminalKind) {
                                    sample.replacementReleasedToIdle =
                                        ticket.compareAndSet(sample.replacementId, IDLE_FLIGHT)
                                    booleanClaim.set(false)
                                    sample.replacementTerminal = kind
                                    done.countDown()
                                }

                                override fun onCancel() = terminal(TerminalKind.CANCEL)
                                override fun onFinish() = terminal(TerminalKind.FINISH)
                            },
                        )
                        sample.tReplacementLaunchReturn = SystemClock.elapsedRealtimeNanos()
                    }, supersedeDelay)
                }
                done.await(15, TimeUnit.SECONDS)
                if (sample.firstTerminal == TerminalKind.PENDING) sample.firstTerminal = TerminalKind.DROPPED_WATCHDOG
                if (sample.replacementTerminal == TerminalKind.PENDING) {
                    sample.replacementTerminal = TerminalKind.DROPPED_WATCHDOG
                }
                val idle = CountDownLatch(1)
                idleLatch.set(idle)
                idle.await(10, TimeUnit.SECONDS)
                settle("pair-$pairIndex")
                record?.add(sample)
                return sample
            }

            repeat(WARMUP_PAIRS) { runPair(it, delayMs, record = null) }

            var attempts = 0
            val maxAttempts = pairTarget + (pairTarget / 5)
            while (samples.count { isValidPair(it, durationMs) } < pairTarget && attempts < maxAttempts) {
                val sample = runPair(attempts, delayMs, record = null)
                attempts += 1
                if (sample.firstTerminal == TerminalKind.DROPPED_WATCHDOG ||
                    sample.replacementTerminal == TerminalKind.DROPPED_WATCHDOG
                ) {
                    // Re-run once; a reproducible drop is shape evidence, not an artifact.
                    droppedOnce += 1
                    val retry = runPair(attempts, delayMs, record = null)
                    attempts += 1
                    if (retry.firstTerminal == TerminalKind.DROPPED_WATCHDOG ||
                        retry.replacementTerminal == TerminalKind.DROPPED_WATCHDOG
                    ) {
                        droppedTwice += 1
                        samples.add(retry)
                    } else if (isValidPair(retry, durationMs)) {
                        samples.add(retry)
                    } else {
                        reruns += 1
                    }
                    continue
                }
                if (isValidPair(sample, durationMs) || isPreLaunchCancel(sample)) {
                    // A genuine pre-launch cancel is the divergent shape itself — counted, never
                    // discarded as a timing artifact.
                    samples.add(sample)
                } else {
                    reruns += 1
                    if (sample.firstTerminal == TerminalKind.CANCEL && sample.tReplacementLaunch == 0L) {
                        spontaneous += 1
                    }
                }
            }
            repeat(ZERO_DELAY_PAIRS) { zeroDelaySamples.add(runPair(it, 0L, record = null)) }

            // ---- Scoring ----
            val valid = samples.filter { isValidPair(it, durationMs) || isPreLaunchCancel(it) }
            val nValid = valid.size
            val cancels = valid.count { it.firstTerminal == TerminalKind.CANCEL }
            val finishes = valid.count { it.firstTerminal == TerminalKind.FINISH }
            val dropped = valid.count { it.firstTerminal == TerminalKind.DROPPED_WATCHDOG }
            val cancelAfterLaunch = valid.count {
                it.firstTerminal == TerminalKind.CANCEL && it.tFirstTerminal >= it.tReplacementLaunch
            }
            val cancelBeforeLaunch = valid.count {
                it.firstTerminal == TerminalKind.CANCEL &&
                    it.tReplacementLaunch != 0L && it.tFirstTerminal < it.tReplacementLaunch
            }
            val syncInCall = valid.count {
                it.firstTerminal == TerminalKind.CANCEL &&
                    it.tFirstTerminal in it.tReplacementLaunch..it.tReplacementLaunchReturn
            }
            val casStaleRejected = valid.count { it.firstCasRejected }
            val ticketIntact = valid.count { it.ticketSeenAtFirstTerminal == it.replacementId }
            val released = valid.count { it.replacementReleasedToIdle }
            val booleanLost = valid.count { it.booleanClearedWhileReplacementAirborne }
            val offMain = valid.count { !it.firstTerminalOnMain }
            val deltasUs = valid.filter { it.firstTerminal == TerminalKind.CANCEL && it.tReplacementLaunch != 0L }
                .map { (it.tFirstTerminal - it.tReplacementLaunch) / 1_000L }
                .sorted()
            val zeroCancel = zeroDelaySamples.count { it.firstTerminal == TerminalKind.CANCEL }
            val zeroFinish = zeroDelaySamples.count { it.firstTerminal == TerminalKind.FINISH }
            val zeroDropped = zeroDelaySamples.count { it.firstTerminal == TerminalKind.DROPPED_WATCHDOG }

            val orderingPass = nValid >= 50 && cancels == nValid && cancelBeforeLaunch == 0 &&
                cancelAfterLaunch == nValid && droppedTwice == 0 && finishes == 0
            val ticketPass = casStaleRejected == nValid && ticketIntact == nValid && released == nValid
            val reasonsPass = animHistogram.keys.all { it == 3 || it == 2 } &&
                gestureHistogram.keys.all { it == 1 } &&
                animHistogram.values.sum() == REASON_SAMPLES &&
                gestureHistogram.values.sum() == REASON_SAMPLES
            val verdict = if (orderingPass && ticketPass && offMain == 0 && reasonsPass) "PASS" else "FAIL"

            val line = "TrailVeil SP10 flight-ticket ${renderer.asEvidenceTokens()} " +
                "api=${android.os.Build.VERSION.SDK_INT} image=${android.os.Build.PRODUCT} " +
                "durMs=$durationMs delayMs=$delayMs pairs=${samples.size} validPairs=$nValid " +
                "reruns=$reruns zeroDelayPairs=${zeroDelaySamples.size} " +
                "cancelDispatched=$cancels finishOnSupersede=$finishes " +
                "droppedWatchdog=$dropped droppedReproduced=$droppedTwice " +
                "cancelAfterLaunch=$cancelAfterLaunch cancelBeforeLaunch=$cancelBeforeLaunch " +
                "dispatchSyncInCall=$syncInCall dispatchPosted=${cancels - syncInCall} " +
                "cancelToLaunchUsP50=${percentile(deltasUs, 50)} " +
                "cancelToLaunchUsP95=${percentile(deltasUs, 95)} " +
                "casStaleRejected=$casStaleRejected ticketClaimIntact=$ticketIntact " +
                "ticketReleasedIdle=$released booleanClaimLost=$booleanLost " +
                "callbackOffMain=$offMain spontaneousCancel=$spontaneous " +
                "reasonsAnimHist=${histogram(animHistogram)} " +
                "reasonsGestureHist=${histogram(gestureHistogram)} gestureMechanism=shell " +
                "gestureRetries=$gestureRetries " +
                "zeroDelayCancel=$zeroCancel zeroDelayFinish=$zeroFinish zeroDelayDropped=$zeroDropped " +
                "verdict=$verdict engineeringEvidenceOnly"
            SpikeEvidence.emit(context, "sp10-flight-ticket.txt", line)
            assertTrue("SP10 FAIL: $line", verdict == "PASS")
        } finally {
            scenario.close()
        }
    }

    private fun isValidPair(sample: PairSample, @Suppress("UNUSED_PARAMETER") durationMs: Int): Boolean =
        sample.tReplacementLaunch != 0L &&
            !sample.firstWasTerminalAtReplacementLaunch &&
            sample.firstTerminal != TerminalKind.PENDING &&
            sample.firstTerminal != TerminalKind.DROPPED_WATCHDOG &&
            sample.replacementTerminal != TerminalKind.DROPPED_WATCHDOG

    /** A first flight already terminal at replacement launch is a spontaneous/natural end, not a
     *  supersede — always an invalid pair, counted separately when it was a CANCEL. */
    private fun isPreLaunchCancel(sample: PairSample): Boolean =
        sample.firstTerminal == TerminalKind.CANCEL &&
            sample.firstWasTerminalAtReplacementLaunch

    private fun waitFor(boundMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + boundMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50L)
        }
        return condition()
    }

    private fun percentile(sorted: List<Long>, percent: Int): Long {
        if (sorted.isEmpty()) return -1L
        val index = ((sorted.size * percent) / 100).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun histogram(values: Map<Int, Int>): String =
        values.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
            .ifEmpty { "none" }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).use { stream -> stream.readBytes() }
        descriptor.close()
    }

    private companion object {
        const val IDLE_FLIGHT = 0L
        const val REASON_SAMPLES = 10
        const val WARMUP_PAIRS = 5
        const val ZERO_DELAY_PAIRS = 10
        const val PAIR_ZOOM = 16f
        val TARGET_A = LatLng(25.0330, 121.5645)
        val TARGET_B = LatLng(25.0357, 121.5645)
    }
}
