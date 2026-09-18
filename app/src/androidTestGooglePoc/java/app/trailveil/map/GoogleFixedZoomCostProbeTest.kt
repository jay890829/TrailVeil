package app.trailveil.map

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.googlepoc.FogContinuityArm
import app.trailveil.harness.DemoExplorationTrack
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoTaiwanAnchors
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.security.MessageDigest
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in diagnostic, not a latency gate. Reads the dedicated emulator's synthetic fixture only. */
@RunWith(AndroidJUnit4::class)
class GoogleFixedZoomCostProbeTest {
    private data class Sample(
        val state: GoogleCanonicalFogState,
        val viewportHash: String,
        val upAt: Long,
        val downAt: Long,
        val stepElapsedMillis: Long,
        val firstCoverToDownMillis: Long,
        val accumulatedCoverMillis: Long,
        val coverIntervals: Int,
        val failedProofs: Int,
        val passedProofs: Int,
        val sawTerminal: Boolean,
        val tailGapNanos: Long,
    )
    private class Step(val previous: Long?, val zoom: Float, val requireNewGeneration: Boolean) {
        val startedAtMillis = SystemClock.elapsedRealtime()
        var wasUp = false // main-thread state callback owns these fields
        var upAt: Long? = null
        var firstUpAt: Long? = null
        var accumulatedCoverMillis = 0L
        var coverIntervals = 0
        var failedProofs = 0
        var passedProofs = 0
        var sawTerminal = false
        val sample = AtomicReference<Sample?>()
        var callbackAtNanos = System.nanoTime()
        val mainCallbackGapsNanos = ArrayList<Long>()
        val cameraSignals = ArrayList<JSONObject>()
        var coverEpochNanos: Long? = null
    }

    @Test fun fixedFloorZoomOutRecordsStages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val traceCameraSignals = arguments.getString("trailveilTraceCameraSignals") == "true"
        val epochField by lazy { GoogleCanonicalFogSurfaceBinding::class.java.getDeclaredField("cameraEpoch").apply { isAccessible = true } }
        val coverField by lazy { GoogleCanonicalFogSurfaceBinding::class.java.getDeclaredField("coverRaisedAtNanos").apply { isAccessible = true } }
        val idleField by lazy { GoogleCanonicalFogSurfaceBinding::class.java.getDeclaredField("lastCameraIdleAtMillis").apply { isAccessible = true } }
        val traceBinding = AtomicReference<GoogleCanonicalFogSurfaceBinding>()
        var signalSequence = 0L
        assumeTrue(arguments.getString("trailveilFixedZoomBenchmark") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assumeTrue(BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED)
        val apkHash = fileHash(instrumentation.targetContext.applicationInfo.sourceDir)
        check(apkHash == arguments.getString("trailveilExpectedApkHash")) { "Installed APK is not the sealed target" }
        val probeHash = fileHash(instrumentation.context.applicationInfo.sourceDir)
        val container = (instrumentation.targetContext.applicationContext as TrailVeilApplication).appContainer
        val (runtime, fixtureHash) = runBlocking(Dispatchers.IO) {
            val db = container.databaseForTesting().openHelper.readableDatabase
            val count = db.query("SELECT COUNT(*) FROM track_points").use { check(it.moveToFirst()); it.getLong(0) }
            check(count == 204_800L) { "Requires the preverified 200-session synthetic fixture" }
            val demoCount = db.query(
                "SELECT COUNT(*) FROM track_points WHERE is_mock = 1 AND session_id IN " +
                    "(SELECT id FROM recording_sessions WHERE stop_reason = ?)",
                arrayOf<Any>(DemoExplorationSeeder.DEMO_STOP_REASON),
            ).use { check(it.moveToFirst()); it.getLong(0) }
            val sessions = db.query("SELECT COUNT(*) FROM recording_sessions").use { check(it.moveToFirst()); it.getLong(0) }
            check(demoCount == count && sessions == 200L) { "Reject mixed or non-demo history" }
            val digest = MessageDigest.getInstance("SHA-256")
            val row = ByteBuffer.allocate(72)
            db.query("SELECT id, session_id, segment_id, sequence, timestamp, latitude, longitude, " +
                "horizontal_accuracy, is_mock FROM track_points ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    row.clear()
                    for (column in 0..4) row.putLong(cursor.getLong(column))
                    for (column in 5..7) row.putDouble(cursor.getDouble(column))
                    row.putLong(cursor.getLong(8))
                    digest.update(row.array())
                }
            }
            container.fogRuntime() to hex(digest.digest())
        }
        val anchor = DemoTaiwanAnchors.anchors().first()
        val last = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).last().last()
        val centre = GeoPoint(last.latitude, last.longitude)
        val map = AtomicReference<GoogleMap?>()
        val view = AtomicReference<MapView?>()
        val state = AtomicReference<GoogleCanonicalFogState?>()
        val activeStep = AtomicReference<Step?>()
        val provenGeneration = AtomicLong(-1L)
        val proofViewport = AtomicReference<String?>()
        val observationFailure = AtomicReference<Throwable?>()
        fun observe(action: () -> Unit) {
            runCatching(action).onFailure { observationFailure.compareAndSet(null, it) }
        }
        fun checkObservation() {
            observationFailure.get()?.let { throw AssertionError("Invalid probe observation", it) }
        }
        fun cameraMatches(zoom: Float): Boolean {
            val p = map.get()?.cameraPosition ?: return false
            return kotlin.math.abs(p.zoom - zoom) < 0.0001f &&
                kotlin.math.abs(p.target.latitude - centre.latitude) < 0.000001 &&
                kotlin.math.abs(p.target.longitude - centre.longitude) < 0.000001
        }
        GoogleMapSurfaceTestHooks.reset()
        FogContinuityArm.TRACK_NATIVE_FLOOR.install()
        GoogleMapSurfaceTestHooks.fogRuntime = runtime
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(1L, centre, zoom = 16.0)
        GoogleMapSurfaceTestHooks.cameraRequestDurationMillis = 1
        GoogleMapSurfaceTestHooks.onMapReady.set(map::set)
        GoogleMapSurfaceTestHooks.onMapViewCreated.set(view::set)
        GoogleMapSurfaceTestHooks.onFogProof.set { observation ->
            observe {
            if (!observation.passed) {
                activeStep.get()?.takeIf { it.sample.get() == null }?.let { it.failedProofs++ }
            }
            if (observation.passed) {
                activeStep.get()?.takeIf { it.sample.get() == null && cameraMatches(it.zoom) }
                    ?.let { it.passedProofs++ }
                provenGeneration.set(observation.generation)
                proofViewport.set(viewportHash(checkNotNull(map.get()), checkNotNull(view.get())))
            }
            }
        }
        GoogleMapSurfaceTestHooks.onFogState.set { s ->
            observe {
            state.set(s)
            val step = activeStep.get()
            if (step != null && step.sample.get() == null) {
                val now = SystemClock.elapsedRealtime()
                if (s.terminal) step.sawTerminal = true
                val falling = step.wasUp && !s.coverUp
                if (s.coverUp && !step.wasUp) {
                    step.upAt = now
                    if (traceCameraSignals) step.coverEpochNanos = coverField.get(checkNotNull(traceBinding.get())) as? Long
                    if (step.firstUpAt == null) step.firstUpAt = now
                    step.coverIntervals++
                }
                if (falling) step.accumulatedCoverMillis += now - checkNotNull(step.upAt)
                step.wasUp = s.coverUp
                if (falling && !s.terminal && !s.retryScheduled && s.pendingGeneration == null &&
                    s.installedGeneration != null &&
                    (!step.requireNewGeneration || s.installedGeneration != step.previous) &&
                    step.passedProofs > 0 &&
                    provenGeneration.get() == s.installedGeneration && cameraMatches(step.zoom)) {
                    val hash = viewportHash(checkNotNull(map.get()), checkNotNull(view.get()))
                    check(hash == proofViewport.get()) { "Viewport changed between proof and cover lowering" }
                    checkNotNull(s.lastCoverIntervalMillis) { "Cover interval missing" }
                    checkNotNull(s.stageSummary) { "Cover stages missing" }
                    checkNotNull(s.surfaceDescription) { "Cover surface identity missing" }
                    step.sample.compareAndSet(null,
                        Sample(s, hash, checkNotNull(step.upAt), now,
                            now - step.startedAtMillis, now - checkNotNull(step.firstUpAt),
                            step.accumulatedCoverMillis, step.coverIntervals,
                            step.failedProofs, step.passedProofs, step.sawTerminal,
                            System.nanoTime() - step.callbackAtNanos))
                }
                if (falling) step.upAt = null
            }
            }
        }
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
        fun awaitReady(previous: Long?, zoom: Float) {
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                checkObservation()
                val s = state.get()
                check(s?.terminal != true) { "Fog became terminal; sample rejected" }
                var matches = false
                onMain {
                    matches = cameraMatches(zoom)
                }
                if (matches && s?.installedGeneration != null && s.installedGeneration != previous &&
                    !s.coverUp && s.pendingGeneration == null && provenGeneration.get() == s.installedGeneration) return
                Thread.sleep(50L)
            }
            error("Fixed camera did not reach a new proven generation; sample rejected")
        }
        // A main-loop responsiveness indicator, not GL frame timing or a natural-gesture gate.
        val heartbeat = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val step = activeStep.get()
                if (step != null && step.sample.get() == null) {
                    val now = System.nanoTime()
                    step.mainCallbackGapsNanos += now - step.callbackAtNanos
                    step.callbackAtNanos = now
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        onMain { Choreographer.getInstance().postFrameCallback(heartbeat) }
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                awaitReady(null, 16f)
                Thread.sleep(1_000L)
                if (traceCameraSignals) onMain {
                    val liveBinding = checkNotNull(view.get()?.getTag(R.id.map_fog_binding_instance) as? GoogleCanonicalFogSurfaceBinding)
                    traceBinding.set(liveBinding)
                    // GoogleMapSurfaceBinding's original listeners only assert main-thread and
                    // forward these same calls. The activity's normal release clears both again.
                    fun forward(kind: String, action: () -> Unit) {
                        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                        val step = activeStep.get()?.takeIf { it.sample.get() == null }
                        var signal: JSONObject? = null
                        observe {
                            signalSequence++
                            if (step != null) {
                                signal = JSONObject().put("seq", signalSequence).put("kind", kind)
                                    .put("sdkEntryMs", SystemClock.elapsedRealtime())
                                    .put("cameraEpoch", epochField.getLong(liveBinding))
                                    .put("coverEpochNanos", coverField.get(liveBinding) ?: JSONObject.NULL)
                                    .put("installed", state.get()?.installedGeneration ?: JSONObject.NULL)
                                    .put("pending", state.get()?.pendingGeneration ?: JSONObject.NULL)
                                step.cameraSignals += checkNotNull(signal)
                            }
                        }
                        action() // always forwards production behavior, even if observation failed
                        observe { signal?.put("forwardedEntryMs", idleField.get(liveBinding) ?: JSONObject.NULL) }
                    }
                    checkNotNull(map.get()).setOnCameraIdleListener { forward("idle", liveBinding::onCameraIdle) }
                    checkNotNull(map.get()).setOnCameraMoveCanceledListener { forward("cancel", liveBinding::onCameraMoveCancelled) }
                }
                val revisit = arguments.getString("trailveilRevisitBenchmark") == "true"
                val zooms = if (revisit) listOf(14f, 13f, 14f, 13f, 14f, 13f)
                    else listOf(15f, 14f, 13f, 12f)
                for ((stepIndex, zoom) in zooms.withIndex()) {
                    lateinit var step: Step
                    onMain {
                        check(state.get()?.coverUp == false && state.get()?.pendingGeneration == null)
                        step = Step(state.get()?.installedGeneration, zoom, requireNewGeneration = !revisit)
                        activeStep.set(step)
                        checkNotNull(map.get()).moveCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition(LatLng(centre.latitude, centre.longitude), zoom, 0f, 0f)))
                    }
                    val deadline = SystemClock.elapsedRealtime() + 45_000L
                    while (step.sample.get() == null && SystemClock.elapsedRealtime() < deadline) {
                        checkObservation()
                        check(state.get()?.terminal != true) { "Fog became terminal; sample rejected" }
                        Thread.sleep(50L)
                    }
                    val sample = checkNotNull(step.sample.get()) { "No fresh, proven cover up/down pair for this move" }
                    Thread.sleep(250L)
                    checkObservation()
                    var proofTimeline: String? = null
                    var coordinatorTimeline: String? = null
                    var callbackGaps = emptyList<Long>()
                    onMain {
                        callbackGaps = step.mainCallbackGapsNanos.toList()
                        check(cameraMatches(zoom) && sample.viewportHash ==
                            viewportHash(checkNotNull(map.get()), checkNotNull(view.get())))
                        val current = checkNotNull(state.get())
                        check(current.installedGeneration == sample.state.installedGeneration &&
                            !current.coverUp && current.pendingGeneration == null &&
                            !current.terminal && !current.retryScheduled) { "Surface did not remain healthy after lowering" }
                        activeStep.set(null)
                        if (arguments.getString("trailveilProofTimeline") == "true") {
                            val binding = checkNotNull(view.get()?.getTag(R.id.map_fog_binding_instance)
                                as? GoogleCanonicalFogSurfaceBinding)
                            val description = binding.describeForTesting()
                            proofTimeline = description.substringAfter("prover=")
                                .substringBefore(" overlays=")
                            if (traceCameraSignals) coordinatorTimeline = description.substringAfter("coordinator[").substringBefore(" render=[")
                        }
                    }
                    val s = sample.state
                    assertTrue("Unsettled sample rejected", !s.coverUp && !s.terminal && s.pendingGeneration == null)
                    val record = JSONObject().put("build", arguments.getString("trailveilBuildLabel"))
                        .put("step", stepIndex + 1).put("revisit", revisit)
                        .put("mainCallbackCount", callbackGaps.size)
                        .put("mainCallbackMaxGapMs", callbackGaps.maxOrNull()?.div(1_000_000.0))
                        .put("mainCallbackOver50ms", callbackGaps.count { it > 50_000_000L })
                        .put("zoom", zoom.toDouble()).put("viewportHash", sample.viewportHash)
                        .put("generation", s.installedGeneration).put("observedCoverMs", sample.downAt - sample.upAt)
                        .put("stepElapsedMs", sample.stepElapsedMillis)
                        .put("firstCoverToDownMs", sample.firstCoverToDownMillis)
                        .put("accumulatedCoverMs", sample.accumulatedCoverMillis)
                        .put("coverIntervals", sample.coverIntervals)
                        .put("failedProofsDuringStep", sample.failedProofs)
                        .put("passedProofsDuringStep", sample.passedProofs)
                        .put("terminalSeenDuringStep", sample.sawTerminal)
                        .put("healthyAt250ms", true)
                        .put("apkSha256", apkHash).put("probeSha256", probeHash).put("pointFixtureSha256", fixtureHash)
                        .put("deviceFingerprint", Build.FINGERPRINT).put("sampleUnixMs", System.currentTimeMillis())
                        .put("coverMs", s.lastCoverIntervalMillis).put("stages", s.stageSummary)
                        .put("surface", s.surfaceDescription).put("proofTimeline", proofTimeline)
                    if (traceCameraSignals) {
                        val riseMs = checkNotNull(step.coverEpochNanos) / 1_000_000L
                        val clocks = checkNotNull(Regex("stages\\[idle=(\\d+) gesture=(\\d+)").find(checkNotNull(s.stageSummary)))
                        val renderStartMs = riseMs + clocks.groupValues[1].toLong()
                        val lastSettleMs = riseMs + clocks.groupValues[2].toLong()
                        check(step.cameraSignals.any { it.optLong("forwardedEntryMs", -1) == lastSettleMs }) {
                            "No typed SDK signal matches the published settle clock"
                        }
                        val beforeRender = step.cameraSignals.filter { it.getLong("forwardedEntryMs") <= renderStartMs }
                        check(beforeRender.isNotEmpty())
                        record.put("cameraSignals", JSONArray(step.cameraSignals))
                            .put("coverRiseEpochNanos", step.coverEpochNanos)
                            .put("commandMs", step.startedAtMillis).put("coverRiseMs", riseMs)
                            .put("reconstructedRenderStartMs", renderStartMs).put("publishedSettleMs", lastSettleMs)
                            .put("firstSignalSeqBeforeRender", beforeRender.first().getLong("seq"))
                            .put("lastSignalSeqBeforeRender", beforeRender.last().getLong("seq"))
                            .put("heartbeatTailGapMs", sample.tailGapNanos / 1_000_000.0)
                            .put("heartbeatCompleteMaxGapMs", maxOf(callbackGaps.maxOrNull() ?: 0L, sample.tailGapNanos) / 1_000_000.0)
                            .put("coordinatorTimeline", coordinatorTimeline)
                    }
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "FIXED_ZOOM $record\n") })
                }
            }
        } finally {
            onMain { Choreographer.getInstance().removeFrameCallback(heartbeat) }
            GoogleMapSurfaceTestHooks.reset()
            GoogleFogCoverageArm.reset()
        }
    }

    private fun viewportHash(map: GoogleMap, view: MapView): String {
        val v = map.projection.visibleRegion
        val identity = listOf(v.nearLeft, v.nearRight, v.farLeft, v.farRight).joinToString(";") {
            String.format(Locale.ROOT, "%.7f,%.7f", it.latitude, it.longitude)
        } + ";${map.cameraPosition.zoom};${view.width};${view.height}"
        return hex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))
    }

    private fun fileHash(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(16_384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
