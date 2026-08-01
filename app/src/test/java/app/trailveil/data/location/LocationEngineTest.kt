package app.trailveil.data.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationEngineTest {
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

    private fun rawFix(elapsedNanos: Long) = RawLocationFix(
        latitude = 25.0,
        longitude = 121.0,
        horizontalAccuracyMeters = 5.0,
        capturedAtElapsedRealtimeNanos = elapsedNanos,
        epochMillis = 1_000,
    )
}
