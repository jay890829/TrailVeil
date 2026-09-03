package app.trailveil.map

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Behavioural lifecycle-order regression tests for the Google surface binding. */
class GoogleMapViewLifecycleBindingTest {

    private class FakeMapViewLifecycle(
        private val events: MutableList<String>,
        private val onStopInline: () -> Unit,
    ) : GoogleMapViewLifecycleCallbacks {
        override fun onStart() {
            events += "map.start"
        }

        override fun onResume() {
            events += "map.resume"
        }

        override fun onPause() {
            events += "map.pause"
        }

        override fun onStop() {
            events += "map.stop"
            // Model an SDK callback delivered inline/reentrantly by onStop().
            onStopInline()
        }

        override fun onDestroy() {
            events += "map.destroy"
        }
    }

    @Test
    fun hostStopInvalidatesCallbacksBeforeSdkStopAndRemainsIdempotent() {
        val events = mutableListOf<String>()
        var hostStopped = false
        val callbacks = FakeMapViewLifecycle(events) {
            events += "snapshot(hostStopped=$hostStopped)"
        }
        val binding = GoogleMapViewLifecycleBinding(
            mapViewLifecycle = callbacks,
            onHostStarted = { events += "host.start" },
            onHostStopped = {
                hostStopped = true
                events += "host.stop"
            },
        )

        binding.onEvent(Lifecycle.Event.ON_START)
        binding.onEvent(Lifecycle.Event.ON_RESUME)
        binding.onEvent(Lifecycle.Event.ON_STOP)
        binding.onEvent(Lifecycle.Event.ON_STOP)
        binding.release()
        binding.release()

        assertEquals(
            listOf(
                "map.start",
                "host.start",
                "map.resume",
                "map.pause",
                "host.stop",
                "map.stop",
                "snapshot(hostStopped=true)",
                "map.destroy",
            ),
            events,
        )
    }

    @Test
    fun releaseRequestedReentrantlyDuringSdkStopDestroysOnlyAfterStopReturns() {
        val events = mutableListOf<String>()
        lateinit var binding: GoogleMapViewLifecycleBinding
        val callbacks = FakeMapViewLifecycle(events) {
            events += "release.requested"
            binding.release()
        }
        binding = GoogleMapViewLifecycleBinding(
            mapViewLifecycle = callbacks,
            onHostStarted = { events += "host.start" },
            onHostStopped = { events += "host.stop" },
        )

        binding.onEvent(Lifecycle.Event.ON_START)
        binding.onEvent(Lifecycle.Event.ON_RESUME)
        binding.release()
        binding.release()

        assertEquals(
            listOf(
                "map.start",
                "host.start",
                "map.resume",
                "map.pause",
                "host.stop",
                "map.stop",
                "release.requested",
                "map.destroy",
            ),
            events,
        )
    }
}
