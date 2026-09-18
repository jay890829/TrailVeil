package app.trailveil.map

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.map.fog.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic CPU-work control on Android; this is not a phone/gesture latency benchmark. */
@RunWith(AndroidJUnit4::class)
class GooglePlannerCpuCancellationTest {
    @Test fun cancelledWorkerStopsItsPixelSearchInsteadOfOnlyDiscardingTheResult() = runBlocking {
        val request = FogViewportCoverageRequest(GeoPoint(0.0, 0.0), 2,
            GeoPoint(-70.0, -180.0), GeoPoint(70.0, -180.0),
            GeoPoint(70.0, 180.0), GeoPoint(-70.0, 180.0))
        val masks = (1..2).flatMap { y -> (0..3).map { x ->
            FogTileKey(2, x, y, FogRenderVersions.CURRENT) to
                FogPixelMask(128, 128, ByteArray(128 * 128) { 0xff.toByte() })
        } }.toMap()
        val zones = listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0))
        data class Result(val checkpoints: Int, val completedPlan: Boolean)
        suspend fun trial(cooperative: Boolean): Result {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val checkpoints = AtomicInteger()
            val completed = AtomicBoolean()
            val job = launch(Dispatchers.Default) {
                val context = currentCoroutineContext()
                try {
                    FogSnapshotVisualProbePlanner().plan(request, masks, zones) {
                        val checkpoint = checkpoints.incrementAndGet()
                        if (cooperative) context.ensureActive()
                        // Request cancellation after this check succeeded, requiring the worker
                        // to reach its next checkpoint before it can observe cancellation.
                        if (checkpoint == 200) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                    completed.set(true)
                } finally { exited.countDown() }
            }
            try {
                assertTrue("worker reached real pixel search", entered.await(10, TimeUnit.SECONDS))
                job.cancel()
                val releasedAt = System.nanoTime()
                release.countDown()
                assertTrue("worker exited", exited.await(10, TimeUnit.SECONDS))
                val exitMillis = (System.nanoTime() - releasedAt) / 1_000_000
                job.join()
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "CPU_CANCEL cooperative=$cooperative checkpoints=${checkpoints.get()} " +
                        "completed=${completed.get()} exitAfterReleaseMs=$exitMillis\n")
                })
                return Result(checkpoints.get(), completed.get())
            } finally { release.countDown(); job.cancel() }
        }
        val control = trial(false)
        val cancelled = trial(true)
        assertTrue(control.checkpoints > 200)
        assertTrue(control.completedPlan)
        assertEquals(201, cancelled.checkpoints)
        assertFalse(cancelled.completedPlan)
    }
}
