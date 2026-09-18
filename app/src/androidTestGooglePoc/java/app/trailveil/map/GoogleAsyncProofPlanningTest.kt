package app.trailveil.map

import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.map.fog.*
import com.google.android.gms.maps.GoogleMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real main looper + SDK map; delayed planner controls exercise the new suspension boundary. */
@RunWith(AndroidJUnit4::class)
class GoogleAsyncProofPlanningTest {
    @Test fun budgetConsumingPlanFailuresAdvanceAttemptsAndBanksExactlyTenTimes() = trial { rig ->
        rig.main { rig.gate.complete(Unit); rig.laterGate.complete(Unit) }
        rig.start()
        rig.awaitResults(1)
        rig.main {
            assertEquals((1..10).toList(), rig.attempts)
            assertEquals(FogProbeCandidateBank.entries + FogProbeCandidateBank.entries +
                FogProbeCandidateBank.entries.take(2), rig.attempts.map(FogProbeCandidateBank::forAttempt))
            assertTrue(rig.prover.recentEvents.any { it.startsWith("result:false") })
            assertFalse(rig.prover.recentEvents.any { it.startsWith("snapshot:") })
        }
    }

    @Test fun discardingTheActiveGenerationCancelsItsPlannerWithoutRetryOrVerdict() = trial { rig ->
        rig.start()
        rig.awaitCalls(1)
        rig.main { rig.prover.cancelGeneration(2L) }
        Thread.sleep(100L)
        rig.main {
            assertFalse(rig.prover.recentEvents.any { it.startsWith("cancel:") })
            rig.prover.cancelGeneration(1L)
            rig.gate.complete(Unit)
        }
        Thread.sleep(350L)
        rig.main {
            assertEquals(1, rig.calls)
            assertEquals(0, rig.results)
            assertTrue(rig.prover.recentEvents.any { it.startsWith("cancel:1@") })
            assertFalse(rig.prover.recentEvents.any { it.startsWith("plan:null") || it.startsWith("snapshot:") })
        }
    }

    @Test fun inputChangeCancelsPlanningBeforeItsGateIsReleasedAndQueuesOnlyOneRetry() = trial { rig ->
        rig.start()
        rig.awaitCalls(1)
        rig.main {
            repeat(3) { rig.camera++; rig.prover.onInputsChanged() }
        }
        // The old planner's gate is still closed: reaching a new attempt proves it was cancelled.
        rig.awaitCalls(2)
        rig.main { rig.gate.complete(Unit) }
        Thread.sleep(300L)
        rig.main {
            assertEquals(2, rig.calls)
            assertEquals(listOf(1, 1), rig.attempts)
            assertEquals(2, rig.prover.recentEvents.count { it.startsWith("attempt:1@") })
            assertEquals(1, rig.prover.recentEvents.count { it.startsWith("inputs:cancelPlan") })
            assertFalse(rig.prover.recentEvents.any { it.startsWith("begin:null") || it.startsWith("snapshot:") })
            assertEquals(0, rig.results)
        }
    }

    @Test fun inputChangeCancelsWarmupAndReplansWithoutSpendingAnAttempt() = trial(validPlan = true) { rig ->
        rig.start(SystemClock.elapsedRealtime() + 900L)
        rig.awaitCalls(1)
        rig.main { rig.gate.complete(Unit) }
        rig.awaitEvent("warmup:")
        rig.main { rig.camera++; rig.prover.onInputsChanged() }
        rig.awaitCalls(2)
        rig.main {
            assertEquals(2, rig.prover.recentEvents.count { it.startsWith("attempt:1@") })
            assertFalse(rig.prover.recentEvents.any { it.startsWith("snapshot:") })
            assertEquals(0, rig.results)
        }
    }

    @Test fun warmupDeadlineDelaysSnapshotButNeverSuppliesAProofVerdict() = trial(validPlan = true) { rig ->
        val deadline = SystemClock.elapsedRealtime() + 900L
        rig.start(deadline)
        rig.awaitCalls(1)
        rig.main { rig.gate.complete(Unit) }
        rig.awaitEvent("warmup:")
        rig.main {
            assertEquals(0, rig.results)
            assertFalse(rig.prover.recentEvents.any { it.startsWith("snapshot:") })
        }
        rig.awaitResults(1)
        rig.main {
            assertTrue(rig.passedAt >= deadline)
            assertTrue(rig.prover.recentEvents.any { it.startsWith("snapshot:") })
            assertTrue(rig.prover.recentEvents.any { it.startsWith("eval:true:") })
        }
    }

    @Test fun cameraMoveDuringWarmupDiscardsThePlanBeforeSnapshot() = trial(validPlan = true) { rig ->
        rig.start(SystemClock.elapsedRealtime() + 700L)
        rig.awaitCalls(1)
        rig.main { rig.gate.complete(Unit) }
        rig.awaitEvent("warmup:")
        rig.main { rig.camera++ }
        rig.awaitCalls(2)
        rig.main {
            assertEquals(2, rig.prover.recentEvents.count { it.startsWith("attempt:1@") })
            assertFalse(rig.prover.recentEvents.any { it.startsWith("snapshot:") })
        }
    }

    @Test fun hostStopDuringWarmupCancelsTheWaitAndPreservesAttempt() = trial(validPlan = true) { rig ->
        rig.start(SystemClock.elapsedRealtime() + 700L)
        rig.awaitCalls(1)
        rig.main { rig.gate.complete(Unit) }
        rig.awaitEvent("warmup:")
        rig.main { rig.stopped = true; rig.prover.onHostStopped() }
        Thread.sleep(800L)
        rig.main {
            assertEquals(0, rig.results)
            assertFalse(rig.prover.recentEvents.any { it.startsWith("snapshot:") })
            rig.stopped = false
            assertTrue(rig.prover.onHostStarted(1L))
        }
        rig.awaitCalls(2)
        rig.main { assertEquals(2, rig.prover.recentEvents.count { it.startsWith("attempt:1@") }) }
    }

    @Test fun releaseDuringWarmupCannotSnapshotAfterTheDeadline() = trial(validPlan = true) { rig ->
        rig.start(SystemClock.elapsedRealtime() + 700L)
        rig.awaitCalls(1)
        rig.main { rig.gate.complete(Unit) }
        rig.awaitEvent("warmup:")
        rig.main { rig.prover.release() }
        Thread.sleep(800L)
        rig.main {
            assertEquals(0, rig.results)
            assertFalse(rig.prover.recentEvents.any { it.startsWith("snapshot:") })
        }
    }

    @Test fun cameraMoveDiscardsPendingPlanWithoutSpendingAttempt() = trial { rig ->
        rig.start()
        rig.awaitCalls(1)
        rig.main { rig.camera++; rig.gate.complete(Unit) }
        rig.awaitCalls(2)
        rig.main {
            assertTrue(rig.prover.recentEvents.any { it.startsWith("plan:notLive") })
            assertEquals(2, rig.prover.recentEvents.count { it.startsWith("attempt:1@") })
            assertFalse(rig.prover.recentEvents.any { it.startsWith("snapshot:") })
        }
    }

    @Test fun hostStopCancelsPlanAndResumesTheSameBudget() = trial { rig ->
        rig.start()
        rig.awaitCalls(1)
        rig.main { rig.stopped = true; rig.prover.onHostStopped(); rig.gate.complete(Unit) }
        Thread.sleep(350)
        rig.main {
            assertEquals(1, rig.calls)
            rig.stopped = false
            assertTrue(rig.prover.onHostStarted(1L))
        }
        rig.awaitCalls(2)
        rig.main {
            assertEquals(listOf(1, 1), rig.attempts)
            assertEquals(2, rig.prover.recentEvents.count { it.startsWith("attempt:1@") })
            assertFalse(rig.prover.recentEvents.any { it.startsWith("plan:null") })
        }
    }

    @Test fun replacementProofCancelsOldWorkerResult() = trial { rig ->
        rig.start()
        rig.awaitCalls(1)
        rig.main {
            rig.prover.prove(2L) { rig.results++ }
            rig.prover.cancelGeneration(1L)
            rig.gate.complete(Unit)
        }
        rig.awaitCalls(2)
        rig.main {
            assertEquals(listOf(1L, 2L), rig.generations)
            assertEquals(0, rig.results)
            assertFalse(rig.prover.recentEvents.any { it.startsWith("plan:null") })
        }
    }

    @Test fun releaseCancelsPendingPlanWithoutCallbackOrRetry() = trial { rig ->
        rig.start()
        rig.awaitCalls(1)
        rig.main { rig.prover.release(); rig.gate.complete(Unit) }
        Thread.sleep(400)
        rig.main {
            assertEquals(1, rig.calls)
            assertEquals(0, rig.results)
            assertFalse(rig.prover.recentEvents.any { it.startsWith("plan:null") })
        }
    }

    private fun trial(validPlan: Boolean = false, body: (Rig) -> Unit) {
        assumeTrue("requires keyed Google test build", BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED)
        val map = AtomicReference<GoogleMap?>()
        val ready = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.reset()
        GoogleMapSurfaceTestHooks.fogRequired = false
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.onMapReady.set { map.set(it); ready.countDown() }
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                assertTrue("map ready", ready.await(20, TimeUnit.SECONDS))
                val rig = Rig(checkNotNull(map.get()), validPlan)
                try { body(rig) } finally { rig.main { rig.prover.release(); rig.scope.cancel() } }
            }
        } finally { GoogleMapSurfaceTestHooks.reset() }
    }

    private class Rig(map: GoogleMap, validPlan: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gate = CompletableDeferred<Unit>()
        val laterGate = CompletableDeferred<Unit>()
        var camera = 0L
        var stopped = false
        var calls = 0
        var results = 0
        var passedAt = 0L
        val generations = mutableListOf<Long>()
        val attempts = mutableListOf<Int>()
        lateinit var prover: GoogleFogSnapshotProver
        init {
            main {
                prover = GoogleFogSnapshotProver(map = map, scope = scope,
                    planForAttempt = { generation, attempt ->
                        assertEquals(Looper.getMainLooper(), Looper.myLooper())
                        calls++
                        generations += generation
                        attempts += attempt
                        val selectedGate = if (calls == 1) gate else laterGate
                        withContext(Dispatchers.Default) {
                            assertFalse(Looper.getMainLooper() == Looper.myLooper())
                            selectedGate.await()
                            if (validPlan) FogSnapshotVisualProbePlanner().plan(
                                FogViewportCoverageRequest(GeoPoint(30.0, -45.0), 2,
                                    GeoPoint(1.0, -89.0), GeoPoint(60.0, -89.0),
                                    GeoPoint(60.0, -1.0), GeoPoint(1.0, -1.0)),
                                mapOf(FogTileKey(2, 1, 1, FogRenderVersions.CURRENT) to
                                    FogPixelMask(16, 16, ByteArray(256))),
                                candidateBank = FogProbeCandidateBank.forAttempt(attempt),
                            ) else null
                        }
                    }, cameraEpoch = { camera }, hostStopped = { stopped })
            }
        }
        fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        fun start(deadline: Long = 0L) = main {
            prover.prove(1L, snapshotNotBeforeMillis = deadline) { passed ->
                results++
                if (passed) passedAt = SystemClock.elapsedRealtime()
            }
        }
        fun awaitEvent(prefix: String) = awaitCondition("event $prefix") {
            prover.recentEvents.any { it.startsWith(prefix) }
        }
        fun awaitResults(expected: Int) = awaitCondition("result $expected") { results >= expected }
        private fun awaitCondition(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                var matched = false
                main { matched = condition() }
                if (matched) return
                Thread.sleep(20)
            }
            throw AssertionError("Timed out: $label")
        }
        fun awaitCalls(expected: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var observed = 0
            while (System.nanoTime() < deadline) {
                main { observed = calls }
                if (observed >= expected) return
                Thread.sleep(20)
            }
            assertEquals("planner entered while main remains responsive", expected, observed)
        }
    }
}
