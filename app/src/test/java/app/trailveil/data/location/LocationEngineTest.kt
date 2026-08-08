package app.trailveil.data.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationEngineTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun callbackRacingCollectorCancellationIsCleanAndUnregisters() = runTest {
        val callback = CompletableDeferred<SendChannel<RawLocationFix>>()
        val unregistered = CompletableDeferred<Unit>()
        val flow = callbackFlow {
            callback.complete(this)
            awaitClose { unregistered.complete(Unit) }
        }.withLocationFixBuffer()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect()
        }
        runCurrent()
        val providerCallback = callback.await()

        collection.cancelAndJoin()
        unregistered.await()

        assertEquals(
            LocationFixOfferResult.ALREADY_CLOSED,
            providerCallback.offerLocationFix(rawFix(elapsedNanos = 1L)),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fakeCallbackProviderFailsAfterSixtyFourQueuedFixesBehindABlockedConsumer() = runTest {
        val firstFixReachedConsumer = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val overflowResult = CompletableDeferred<LocationFixOfferResult>()
        val collected = mutableListOf<Long>()
        val flow = callbackFlow {
            assertEquals(
                LocationFixOfferResult.DELIVERED,
                offerLocationFix(rawFix(elapsedNanos = 0L)),
            )
            firstFixReachedConsumer.await()
            repeat(LOCATION_FIX_BUFFER_CAPACITY) { index ->
                assertEquals(
                    LocationFixOfferResult.DELIVERED,
                    offerLocationFix(rawFix(elapsedNanos = index.toLong() + 1L)),
                )
            }
            overflowResult.complete(
                offerLocationFix(
                    rawFix(elapsedNanos = LOCATION_FIX_BUFFER_CAPACITY.toLong() + 1L),
                ),
            )
            awaitClose()
        }.withLocationFixBuffer()
        val collection = async {
            runCatching {
                flow.collect { fix ->
                    collected += fix.capturedAtElapsedRealtimeNanos
                    if (collected.size == 1) {
                        firstFixReachedConsumer.complete(Unit)
                        releaseConsumer.await()
                    }
                }
            }.exceptionOrNull()
        }

        firstFixReachedConsumer.await()
        assertEquals(LocationFixOfferResult.OVERFLOW_TERMINATED, overflowResult.await())
        releaseConsumer.complete(Unit)
        assertTrue(collection.await() is LocationBackpressureException)
        assertEquals(
            (0L..LOCATION_FIX_BUFFER_CAPACITY.toLong()).toList(),
            collected,
        )
    }

    @Test
    fun boundedQueueTerminatesExactlyAtTheFirstUnqueueableFix() {
        val queue = Channel<RawLocationFix>(capacity = LOCATION_FIX_BUFFER_CAPACITY)
        repeat(LOCATION_FIX_BUFFER_CAPACITY) { index ->
            assertEquals(
                LocationFixOfferResult.DELIVERED,
                queue.offerLocationFix(rawFix(elapsedNanos = index.toLong())),
            )
        }

        assertEquals(
            LocationFixOfferResult.OVERFLOW_TERMINATED,
            queue.offerLocationFix(rawFix(elapsedNanos = LOCATION_FIX_BUFFER_CAPACITY.toLong())),
        )
        repeat(LOCATION_FIX_BUFFER_CAPACITY) { index ->
            assertEquals(index.toLong(), queue.tryReceive().getOrThrow().capturedAtElapsedRealtimeNanos)
        }
        val terminal = queue.tryReceive()
        assertTrue(terminal.isClosed)
        assertTrue(terminal.exceptionOrNull() is LocationBackpressureException)
    }

    @Test
    fun downstreamCancellationIsClassifiedWithoutInventingBackpressure() {
        val queue = Channel<RawLocationFix>(capacity = LOCATION_FIX_BUFFER_CAPACITY)
        queue.cancel()

        assertEquals(
            LocationFixOfferResult.ALREADY_CLOSED,
            queue.offerLocationFix(rawFix(elapsedNanos = 1L)),
        )
        assertTrue(queue.tryReceive().exceptionOrNull() !is LocationBackpressureException)
    }

    @Test
    fun simultaneousCancellationAndOverflowHaveOneConsistentTerminalOwner() = runBlocking {
        repeat(1_000) { iteration ->
            val queue = Channel<RawLocationFix>(capacity = LOCATION_FIX_BUFFER_CAPACITY)
            repeat(LOCATION_FIX_BUFFER_CAPACITY) { index ->
                assertEquals(
                    LocationFixOfferResult.DELIVERED,
                    queue.offerLocationFix(rawFix(elapsedNanos = index.toLong())),
                )
            }
            val start = CompletableDeferred<Unit>()
            val cancellation = async(Dispatchers.Default) {
                start.await()
                queue.cancel()
            }
            val overflow = async(Dispatchers.Default) {
                start.await()
                queue.offerLocationFix(rawFix(elapsedNanos = Long.MAX_VALUE))
            }

            start.complete(Unit)
            val offerResult = overflow.await()
            cancellation.await()

            assertTrue(
                "race $iteration returned $offerResult",
                offerResult == LocationFixOfferResult.ALREADY_CLOSED ||
                    offerResult == LocationFixOfferResult.OVERFLOW_TERMINATED,
            )
            assertTrue(queue.trySend(rawFix(elapsedNanos = Long.MAX_VALUE - 1)).isClosed)
            var terminal = queue.tryReceive()
            while (terminal.isSuccess) terminal = queue.tryReceive()
            when (offerResult) {
                LocationFixOfferResult.OVERFLOW_TERMINATED ->
                    assertTrue(terminal.exceptionOrNull() is LocationBackpressureException)
                LocationFixOfferResult.ALREADY_CLOSED ->
                    assertFalse(terminal.exceptionOrNull() is LocationBackpressureException)
                LocationFixOfferResult.DELIVERED -> error("a full queue cannot accept the race fix")
            }
        }
    }

    @Test
    fun oneHundredThousandFixesRemainLosslessWhenTheConsumerKeepsUp() {
        val queue = Channel<RawLocationFix>(capacity = LOCATION_FIX_BUFFER_CAPACITY)
        val qualityFilter = LocationQualityFilter()
        var delivered = 0
        var accepted = 0
        var rejected = 0
        val coalesced = 0
        var failed = 0
        repeat(100_000) { index ->
            val offered = rawFix(
                elapsedNanos = index.toLong(),
                latitude = if (index % 2 == 0) 25.0 else 91.0,
            )
            when (queue.offerLocationFix(offered)) {
                LocationFixOfferResult.DELIVERED -> delivered += 1
                LocationFixOfferResult.OVERFLOW_TERMINATED -> failed += 1
                LocationFixOfferResult.ALREADY_CLOSED -> error("queue closed at fix $index")
            }
            val received = queue.tryReceive().getOrThrow()
            assertEquals(index.toLong(), received.capturedAtElapsedRealtimeNanos)
            when (qualityFilter.evaluate(received, nowElapsedRealtimeNanos = index.toLong())) {
                is LocationQualityDecision.Accepted -> accepted += 1
                is LocationQualityDecision.Rejected -> rejected += 1
            }
        }

        assertEquals(100_000, delivered)
        assertEquals(50_000, accepted)
        assertEquals(50_000, rejected)
        assertEquals(0, coalesced)
        assertEquals(0, failed)
        assertEquals(100_000, accepted + rejected + coalesced + failed)
        assertTrue(queue.tryReceive().isFailure)
    }

    @Test
    fun requestDefaultsMatchTheDocumentedAndroidCadence() {
        val request = LocationUpdateRequest()

        assertEquals(5_000L, request.intervalMillis)
        assertEquals(2_000L, request.minIntervalMillis)
        assertEquals(5.0, request.minDisplacementMeters, 0.0)
    }

    @Test
    fun requestRejectsInvalidCadenceAndDisplacement() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationUpdateRequest(intervalMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocationUpdateRequest(minIntervalMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocationUpdateRequest(intervalMillis = 1_000, minIntervalMillis = 1_001)
        }
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                LocationUpdateRequest(minDisplacementMeters = invalid)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fakeEngineEmitsDeterministicallyAndCapturesTheRequest() = runTest {
        val engine = FakeLocationEngine()
        val request = LocationUpdateRequest(intervalMillis = 10_000, minIntervalMillis = 1_000)
        val collected = mutableListOf<RawLocationFix>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.fixes(request).take(2).toList(collected)
        }
        runCurrent()

        val first = rawFix(elapsedNanos = 1)
        val second = rawFix(elapsedNanos = 2)
        engine.emit(first)
        engine.emit(second)
        runCurrent()
        collector.join()

        assertEquals(listOf(request), engine.capturedRequests)
        assertEquals(listOf(first, second), collected)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingFakeCollectionStopsDelivery() = runTest {
        val engine = FakeLocationEngine()
        val collected = mutableListOf<RawLocationFix>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.fixes().collect(collected::add)
        }
        runCurrent()

        engine.emit(rawFix(elapsedNanos = 1))
        runCurrent()
        collector.cancelAndJoin()
        engine.emit(rawFix(elapsedNanos = 2))
        runCurrent()

        assertEquals(listOf(rawFix(elapsedNanos = 1)), collected)
        assertEquals(listOf(LocationUpdateRequest()), engine.capturedRequests)
        engine.close()
    }

    private fun rawFix(elapsedNanos: Long, latitude: Double = 25.0) = RawLocationFix(
        latitude = latitude,
        longitude = 121.0,
        horizontalAccuracyMeters = 5.0,
        capturedAtElapsedRealtimeNanos = elapsedNanos,
        epochMillis = 1_000,
    )
}
