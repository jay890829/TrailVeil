package app.trailveil.map.fog

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogTileRequestBarrierTest {
    @Test
    fun completesExactlyOnceAfterEveryCanonicalKeyIsDelivered() {
        val first = FogTileKey(2, 3, 1, FogRenderVersions.CURRENT)
        val second = FogTileKey(2, 0, 1, FogRenderVersions.CURRENT)
        val completions = AtomicInteger()
        val barrier = FogTileRequestBarrier(7L, setOf(first, second)) {
            completions.incrementAndGet()
        }

        assertTrue(barrier.record(7L, first))
        assertFalse(barrier.record(7L, first))
        assertFalse(barrier.record(6L, second))
        assertEquals(1, barrier.remainingCount())
        assertTrue(barrier.record(7L, second))
        assertTrue(barrier.isComplete())
        assertEquals(1, completions.get())
        assertFalse(barrier.record(7L, second))
        assertEquals(1, completions.get())
    }

    @Test
    fun concurrentWorldCopyDeliveriesCannotDoubleComplete() {
        val keys = (0 until 16).map { x ->
            FogTileKey(4, x, 7, FogRenderVersions.CURRENT)
        }.toSet()
        val completions = AtomicInteger()
        val completed = CountDownLatch(1)
        val barrier = FogTileRequestBarrier(11L, keys) {
            completions.incrementAndGet()
            completed.countDown()
        }
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(4) {
                executor.submit {
                    keys.forEach { key -> barrier.record(11L, key) }
                }
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, barrier.remainingCount())
            assertEquals(1, completions.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
