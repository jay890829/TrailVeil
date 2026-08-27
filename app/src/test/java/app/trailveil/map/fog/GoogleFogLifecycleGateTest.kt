package app.trailveil.map.fog

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleFogLifecycleGateTest {
    @Test
    fun destroyInvalidatesLateMapAndAsyncCallbacks() {
        val gate = GoogleFogLifecycleGate()
        val lease = requireNotNull(gate.acquire())

        gate.markDestroyed()

        assertFalse(gate.callbacksAllowed())
        assertFalse(gate.isCurrent(lease))
        assertNull(gate.acquire())
    }

    @Test
    fun terminalFallbackInvalidatesAttachAndRenderLeases() {
        val gate = GoogleFogLifecycleGate()
        val lease = requireNotNull(gate.acquire())

        assertTrue(gate.enterTerminalFallback())
        assertFalse(gate.enterTerminalFallback())
        assertFalse(gate.callbacksAllowed())
        assertFalse(gate.isCurrent(lease))
        assertNull(gate.acquire())
    }

    @Test
    fun destroyWinsAgainstAConcurrentAsyncCompletionCheck() {
        val gate = GoogleFogLifecycleGate()
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                val lease = gate.acquire()
                assertNotNull(lease)
                acquired.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                gate.isCurrent(requireNotNull(lease))
            }

            assertTrue(acquired.await(2, TimeUnit.SECONDS))
            gate.markDestroyed()
            release.countDown()

            assertFalse(result.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
