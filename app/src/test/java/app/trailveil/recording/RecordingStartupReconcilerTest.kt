package app.trailveil.recording

import app.trailveil.data.recording.ReconcileStartingDisposition
import app.trailveil.data.recording.ReconcileStartingResult
import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.data.recording.RecordingRepositoryState
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecordingStartupReconcilerTest {
    @Test
    fun concurrentAndRepeatedCallersShareOneSuccessfulResult() = runTest {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val expected = result("success")
        val reconciler = RecordingStartupReconciler {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            expected
        }

        val callers = List(8) { async { reconciler.reconcileOnce() } }
        entered.await()
        assertEquals(1, calls.get())
        release.complete(Unit)
        callers.awaitAll().forEach { assertSame(expected, it) }
        assertSame(expected, reconciler.reconcileOnce())
        assertEquals(1, calls.get())
    }

    @Test
    fun failedAttemptIsNotCachedAndCanBeRetried() = runTest {
        var calls = 0
        val expected = result("retry")
        val reconciler = RecordingStartupReconciler {
            calls += 1
            if (calls == 1) throw IOException("injected")
            expected
        }

        try {
            reconciler.reconcileOnce()
            throw AssertionError("expected failure")
        } catch (_: IOException) {
            // Expected: failure must leave the once gate unresolved.
        }
        assertSame(expected, reconciler.reconcileOnce())
        assertEquals(2, calls)
    }

    @Test
    fun cancelledAttemptIsNotCachedAndCanBeRetried() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<ReconcileStartingResult>()
        val expected = result("after-cancel")
        val reconciler = RecordingStartupReconciler {
            calls += 1
            if (calls == 1) {
                entered.complete(Unit)
                never.await()
            } else {
                expected
            }
        }

        val first = async { reconciler.reconcileOnce() }
        entered.await()
        first.cancelAndJoin()

        assertSame(expected, reconciler.reconcileOnce())
        assertEquals(2, calls)
    }

    private fun result(id: String) = ReconcileStartingResult(
        operationId = RecordingOperationId(id),
        disposition = ReconcileStartingDisposition.NOTHING_TO_RECONCILE,
        state = RecordingRepositoryState(),
    )
}
