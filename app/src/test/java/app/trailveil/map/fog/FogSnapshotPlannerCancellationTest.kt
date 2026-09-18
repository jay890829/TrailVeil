package app.trailveil.map.fog

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FogSnapshotPlannerCancellationTest {
    private val request = FogViewportCoverageRequest(GeoPoint(0.0, 0.0), 2,
        GeoPoint(-70.0, -180.0), GeoPoint(70.0, -180.0),
        GeoPoint(70.0, 180.0), GeoPoint(-70.0, 180.0))
    private val masks = (1..2).flatMap { y -> (0..3).map { x ->
        FogTileKey(2, x, y, FogRenderVersions.CURRENT) to
            FogPixelMask(128, 128, ByteArray(128 * 128) { 0xff.toByte() })
    } }.toMap()
    private val wholeWorld = listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0))

    @Test fun cancellationStopsTheCpuLoopWhereTheNonCooperativeControlKeepsWorking() = runBlocking {
        data class Result(val checkpoints: Int, val finishedPlan: Boolean)
        suspend fun trial(cooperative: Boolean): Result {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val checks = AtomicInteger()
            val finished = AtomicBoolean()
            val job = launch(Dispatchers.Default) {
                val context = currentCoroutineContext()
                try {
                    FogSnapshotVisualProbePlanner().plan(request, masks, wholeWorld) {
                        val checkpoint = checks.incrementAndGet()
                        if (cooperative) context.ensureActive()
                        // Cancel just AFTER an active checkpoint: the worker must stop at the
                        // next one, rather than obtaining a best-case exit inside this gate.
                        if (checkpoint == 200) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                    finished.set(true)
                } finally { exited.countDown() }
            }
            try {
                assertTrue("actual pixel search reached its checkpoint", entered.await(10, TimeUnit.SECONDS))
                job.cancel()
                release.countDown()
                assertTrue("worker exited", exited.await(10, TimeUnit.SECONDS))
                job.join()
                println("PLANNER_CANCEL cooperative=$cooperative checkpoints=${checks.get()} finished=${finished.get()}")
                return Result(checks.get(), finished.get())
            } finally { release.countDown(); job.cancel() }
        }
        val control = trial(false)
        val cancelled = trial(true)
        assertTrue("control must demonstrate actual post-cancel CPU work", control.checkpoints > 200)
        assertTrue(control.finishedPlan)
        assertEquals(201, cancelled.checkpoints)
        assertFalse(cancelled.finishedPlan)
    }

    @Test fun activeChecksPreserveTheWholePlanIncludingExclusions() = runBlocking {
        val context = currentCoroutineContext()
        for (zones in listOf(emptyList(), wholeWorld,
            listOf(FogProbeExclusionZone(-10.0, 10.0, -5.0, 5.0)))) {
            val expected = FogSnapshotVisualProbePlanner().plan(request, masks, zones)
            val actual = FogSnapshotVisualProbePlanner().plan(request, masks, zones) { context.ensureActive() }
            assertEquals(expected.coverageKeys, actual.coverageKeys)
            assertEquals(expected.probesByKey, actual.probesByKey)
            assertEquals(expected.zoneBlockedKeys, actual.zoneBlockedKeys)
        }
    }

    @Test fun aCancelledEntryThrowsInsteadOfReturningAPartialPlan() {
        val cancelled = CancellationException("cancelled before planning")
        val thrown = assertThrows(CancellationException::class.java) {
            FogSnapshotVisualProbePlanner().plan(request, masks) { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }
}
