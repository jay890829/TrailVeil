package app.trailveil.map.fog

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogActualTileRequestSetTest {
    @Test
    fun `barrier expects only renderer-observed keys and replays early deliveries`() {
        val tracker = FogActualTileRequestSet(maxKeys = 4)
        val first = key(5, 10, 11)
        val second = key(4, 4, 5)
        var completed = 0
        tracker.begin(7)
        tracker.recordRequested(7, first)
        tracker.recordDelivered(7, first)
        tracker.recordRequested(7, second)

        assertTrue(tracker.armBarrier { completed += 1 })
        assertEquals(0, completed)
        assertFalse(tracker.recordDelivered(6, second))
        assertTrue(tracker.recordDelivered(7, second))
        assertEquals(1, completed)
        assertEquals(setOf(first, second), tracker.consumeCompleted(7))
        assertFalse(tracker.recordRequested(7, key(5, 12, 11)))
    }

    @Test
    fun `late actual request invalidates an armed barrier until it is rebuilt`() {
        val tracker = FogActualTileRequestSet(maxKeys = 4)
        val first = key(5, 1, 1)
        val late = key(5, 2, 1)
        var completed = 0
        tracker.begin(8)
        tracker.recordRequested(8, first)
        assertTrue(tracker.armBarrier { completed += 1 })
        tracker.recordRequested(8, late)
        tracker.recordDelivered(8, first)
        assertEquals(0, completed)
        assertFalse(tracker.snapshot().barrierArmed)

        assertTrue(tracker.armBarrier { completed += 1 })
        tracker.recordDelivered(8, late)
        assertEquals(1, completed)
    }

    @Test
    fun `overflow fails closed and cannot arm`() {
        val tracker = FogActualTileRequestSet(maxKeys = 1)
        tracker.begin(9)
        assertTrue(tracker.recordRequested(9, key(5, 1, 1)))
        assertFalse(tracker.recordRequested(9, key(5, 2, 1)))
        assertTrue(tracker.snapshot().overflowed)
        assertFalse(tracker.armBarrier {})
    }

    @Test
    fun `cancel rotates an overflowed session so the next generation can complete`() {
        val tracker = FogActualTileRequestSet(maxKeys = 1)
        val stale = key(5, 1, 1)
        val recovered = key(6, 2, 2)
        tracker.begin(9)
        assertTrue(tracker.recordRequested(9, stale))
        assertFalse(tracker.recordRequested(9, key(5, 2, 1)))

        tracker.cancel(9)
        tracker.begin(10)
        assertTrue(tracker.recordRequested(10, recovered))
        assertTrue(tracker.armBarrier {})
        assertTrue(tracker.recordDelivered(10, recovered))
        assertEquals(setOf(recovered), tracker.consumeCompleted(10))
        assertFalse(tracker.snapshot().overflowed)
    }

    @Test
    fun `concurrent provider deliveries complete exactly once and freeze atomically`() {
        val tracker = FogActualTileRequestSet(maxKeys = 64)
        val keys = (0 until 32).map { index -> key(6, index, 20) }
        tracker.begin(11)
        keys.forEach { candidate -> tracker.recordRequested(11, candidate) }
        val completed = AtomicInteger(0)
        assertTrue(tracker.armBarrier { completed.incrementAndGet() })
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(keys.size)
        try {
            keys.forEach { candidate ->
                pool.execute {
                    tracker.recordDelivered(11, candidate)
                    done.countDown()
                }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, completed.get())
        assertEquals(keys.toSet(), tracker.consumeCompleted(11))
        assertFalse(tracker.recordDelivered(11, keys.first()))
    }

    private fun key(zoom: Int, x: Int, y: Int) = FogTileKey(
        zoom = zoom,
        x = x,
        y = y,
        renderVersion = FogRenderVersions.CURRENT,
    )
}
