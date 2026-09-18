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
class GoogleRequiredRasterCostTest {
    private data class CapturedProof(
        val generation: Long,
        val request: app.trailveil.map.fog.FogViewportCoverageRequest,
        val masks: Map<app.trailveil.map.fog.FogTileKey, app.trailveil.map.fog.FogPixelMask>,
        val plan: app.trailveil.map.fog.FogSnapshotVisualProbePlan,
    )
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
        val processCpuMillis: Long,
    )
    private class Step(val previous: Long?, val zoom: Float, val requireNewGeneration: Boolean) {
        val startedAtMillis = SystemClock.elapsedRealtime()
        val startedCpuMillis = android.os.Process.getElapsedCpuTime()
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

    @Test fun crossFloorPanRecordsProofAndStages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val traceCameraSignals = arguments.getString("trailveilTraceCameraSignals") == "true"
        val epochField by lazy { GoogleCanonicalFogSurfaceBinding::class.java.getDeclaredField("cameraEpoch").apply { isAccessible = true } }
        val coverField by lazy { GoogleCanonicalFogSurfaceBinding::class.java.getDeclaredField("coverRaisedAtNanos").apply { isAccessible = true } }
        val idleField by lazy { GoogleCanonicalFogSurfaceBinding::class.java.getDeclaredField("lastCameraIdleAtMillis").apply { isAccessible = true } }
        val traceBinding = AtomicReference<GoogleCanonicalFogSurfaceBinding>()
        var signalSequence = 0L
        assumeTrue(arguments.getString("x3Cost") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assumeTrue(BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED)
        val apkHash = fileHash(instrumentation.targetContext.applicationInfo.sourceDir)
        check(apkHash == arguments.getString("x3ExpectedApkHash")) { "Installed APK is not the sealed target" }
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
        var expectedCenter = centre
        val testZoom = checkNotNull(arguments.getString("x3Zoom")).toInt().also { check(it in listOf(8, 12, 14)) }
        val testArm = checkNotNull(arguments.getString("x3Arm")).also { check(it in listOf("ring", "floor", "raster", "fallback")) }
        if (testArm == "fallback") {
            // Test-only disabled engine, through the same production Track raster fallback.
            app.trailveil.map.fog.FogViewportCoordinator::class.java.getDeclaredField("nativeGeometryEngine")
                .apply { isAccessible = true }.set(runtime.viewportCoordinator, null)
        }
        val map = AtomicReference<GoogleMap?>()
        val view = AtomicReference<MapView?>()
        val state = AtomicReference<GoogleCanonicalFogState?>()
        val activeStep = AtomicReference<Step?>()
        val provenGeneration = AtomicLong(-1L)
        val proofViewport = AtomicReference<String?>()
        val capturedProof = AtomicReference<CapturedProof?>()
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
                kotlin.math.abs(p.target.latitude - expectedCenter.latitude) < 0.000001 &&
                kotlin.math.abs(p.target.longitude - expectedCenter.longitude) < 0.000001
        }
        GoogleMapSurfaceTestHooks.reset()
        when (testArm) {
            "floor" -> FogContinuityArm.TRACK_NATIVE_FLOOR
            "raster" -> FogContinuityArm.PADDING_RING_2
            else -> FogContinuityArm.TRACK_NATIVE
        }.install()
        GoogleMapSurfaceTestHooks.fogRuntime = runtime
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(1L, centre, zoom = testZoom.toDouble())
        GoogleMapSurfaceTestHooks.cameraRequestDurationMillis = 1
        GoogleMapSurfaceTestHooks.onMapReady.set(map::set)
        GoogleMapSurfaceTestHooks.onMapViewCreated.set(view::set)
        GoogleMapSurfaceTestHooks.onFogProof.set { observation ->
            observe {
            if (!observation.passed) {
                activeStep.get()?.takeIf { it.sample.get() == null }?.let { it.failedProofs++ }
            }
            if (observation.passed) {
                val liveBinding = checkNotNull(view.get()?.getTag(R.id.map_fog_binding_instance) as? GoogleCanonicalFogSurfaceBinding)
                // Capture references only; hashing after cover down is outside the sample clock.
                capturedProof.set(captureProof(liveBinding, observation.generation))
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
                    if (testArm == "raster") check(s.surfaceDescription == null)
                    else checkNotNull(s.surfaceDescription) { "Cover surface identity missing" }
                    step.sample.compareAndSet(null,
                        Sample(s, hash, checkNotNull(step.upAt), now,
                            now - step.startedAtMillis, now - checkNotNull(step.firstUpAt),
                            step.accumulatedCoverMillis, step.coverIntervals,
                            step.failedProofs, step.passedProofs, step.sawTerminal,
                            System.nanoTime() - step.callbackAtNanos, android.os.Process.getElapsedCpuTime() - step.startedCpuMillis))
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
                awaitReady(null, testZoom.toFloat())
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
                // Derived-cache cold for the first measured move, then sequential warm pan/revisit.
                runBlocking(Dispatchers.IO) { runtime.viewportCoordinator.clearDerivedCache() }
                val tileOffsets = listOf(1.25, 2.5, 3.75, 2.5, 1.25, 0.0)
                // Tile ring can remain proven through these small pans. Its control alternates
                // integer zooms to require a new raster generation on every recorded step.
                val zooms = List(tileOffsets.size) {
                    (if (testArm == "raster" && it % 2 == 0) testZoom - 1 else testZoom).toFloat()
                }
                for ((stepIndex, zoom) in zooms.withIndex()) {
                    lateinit var step: Step
                    onMain {
                        check(state.get()?.coverUp == false && state.get()?.pendingGeneration == null)
                        expectedCenter = GeoPoint(centre.latitude, centre.longitude + tileOffsets[stepIndex] * 360.0 / (1 shl testZoom))
                        step = Step(state.get()?.installedGeneration, zoom, requireNewGeneration = true)
                        activeStep.set(step)
                        checkNotNull(map.get()).moveCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition(LatLng(expectedCenter.latitude, expectedCenter.longitude), zoom, 0f, 0f)))
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
                    lateinit var payload: JSONObject
                    onMain {
                        callbackGaps = step.mainCallbackGapsNanos.toList()
                        check(cameraMatches(zoom) && sample.viewportHash ==
                            viewportHash(checkNotNull(map.get()), checkNotNull(view.get())))
                        val current = checkNotNull(state.get())
                        val liveBinding = checkNotNull(view.get()?.getTag(R.id.map_fog_binding_instance) as? GoogleCanonicalFogSurfaceBinding)
                        payload = payloadOracle(liveBinding, checkNotNull(current.installedGeneration), testArm, checkNotNull(capturedProof.get()))
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
                        .put("step", stepIndex + 1).put("revisit", stepIndex >= 3)
                        .put("case", "$testArm-z$testZoom").put("tileOffset", tileOffsets[stepIndex])
                        .put("derivedCacheCold", stepIndex == 0).put("processCpuMs", sample.processCpuMillis)
                        .put("payload", payload)
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
                        .put("surface", s.surfaceDescription ?: "tile-provider").put("proofTimeline", proofTimeline)
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
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "X3_PAN $record\n") })
                }
            }
        } finally {
            onMain { Choreographer.getInstance().removeFrameCallback(heartbeat) }
            GoogleMapSurfaceTestHooks.reset()
            GoogleFogCoverageArm.reset()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun captureProof(binding: GoogleCanonicalFogSurfaceBinding, generation: Long): CapturedProof {
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        val entry = checkNotNull(field(checkNotNull(field(binding, "proofPlanMemo")), "entry"))
        check(field(entry, "generation") == generation)
        return CapturedProof(generation,
            field(entry, "request") as app.trailveil.map.fog.FogViewportCoverageRequest,
            field(entry, "masks") as Map<app.trailveil.map.fog.FogTileKey, app.trailveil.map.fog.FogPixelMask>,
            field(entry, "plan") as app.trailveil.map.fog.FogSnapshotVisualProbePlan)
    }

    @Suppress("UNCHECKED_CAST")
    private fun payloadOracle(binding: GoogleCanonicalFogSurfaceBinding, generation: Long, arm: String, captured: CapturedProof): JSONObject {
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        check(captured.generation == generation)
        val request = captured.request
        val masks = captured.masks
        val plan = captured.plan
        val allMasks = (field(binding, "masksByGeneration") as Map<Long, Map<app.trailveil.map.fog.FogTileKey, app.trailveil.map.fog.FogPixelMask>>)[generation]!!
        val floor = app.trailveil.map.fog.FogViewportCoveragePlanner(paddingTiles = 0).plan(request).keys
        check(masks.keys.containsAll(floor))
        check(plan.isProvable())
        val maskDigest = MessageDigest.getInstance("SHA-256")
        floor.forEach { key ->
            maskDigest.update(key.toString().toByteArray())
            maskDigest.update(checkNotNull(masks[key]).copyAlpha())
        }
        val proofDigest = MessageDigest.getInstance("SHA-256")
        // Coverage-only padding is intentionally excluded. Every actual decision input is included.
        proofDigest.update(plan.zoneBlockedKeys.toString().toByteArray())
        plan.probesByKey.forEach { (key, probes) ->
            proofDigest.update(key.toString().toByteArray())
            probes.forEach { p -> proofDigest.update("${p.latitude.toRawBits()};${p.longitude.toRawBits()};${p.blockIndex};${p.strongNeighbourhood};".toByteArray()) }
        }
        val result = JSONObject().put("completedMasks", allMasks.size).put("floorMasks", floor.size)
            .put("oracle", "actualSuccessfulProofInputs")
            .put("floorMaskSha256", hex(maskDigest.digest())).put("proofDecisionSha256", hex(proofDigest.digest()))
            .put("probeCount", plan.probesByKey.values.sumOf { it.size }).put("provable", plan.isProvable())
        if (arm == "fallback") {
            val installer = checkNotNull(field(binding, "overlayInstaller")) as GoogleTrackFogOverlayInstaller
            check(installer.requiresRasterResolution(generation))
            check(installer.describe().contains("raster-fallback"))
            result.put("native", false).put("forcedFallbackVerified", true)
        }
        if (arm == "raster") {
            check(field(binding, "overlayInstaller") == null)
            result.put("native", false).put("tileInstallerVerified", true)
        }
        if (arm == "ring" || arm == "floor") {
            val installer = checkNotNull(field(binding, "overlayInstaller"))
            check(installer is GoogleTrackFogOverlayInstaller)
            val native = !installer.requiresRasterResolution(generation)
            result.put("native", native)
            check(native) { "native-success case unexpectedly used raster fallback" }
            val vector = checkNotNull(field(installer, "vector"))
            val installed = (field(vector, "installed") as Map<*, *>)[generation]!!
            val polygons = field(installed, "polygons") as List<com.google.android.gms.maps.model.Polygon>
            val geometryDigest = MessageDigest.getInstance("SHA-256")
            polygons.forEach { polygon ->
                check(polygon.isVisible)
                geometryDigest.update("polygon;${polygon.isGeodesic};${polygon.isClickable};".toByteArray())
                (listOf(polygon.points) + polygon.holes).forEach { ring ->
                    geometryDigest.update("ring;${ring.size};".toByteArray())
                    ring.forEach { p -> geometryDigest.update("${p.latitude.toRawBits()};${p.longitude.toRawBits()};".toByteArray()) }
                }
            }
            result.put("nativeGeometrySha256", hex(geometryDigest.digest())).put("polygonCount", polygons.size)
        }
        return result
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
