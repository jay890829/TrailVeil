package app.trailveil.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.BuildConfig
import com.google.android.gms.maps.MapView
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleProviderUnavailableSurfaceTest {
    @Before fun setUp() = GoogleMapSurfaceTestHooks.reset()
    @After fun tearDown() = GoogleMapSurfaceTestHooks.reset()

    @Test
    fun everyTerminalReasonBuildsNoMapViewAndTheNextCompositionRetries() {
        GoogleMapSurfaceTestHooks.decision.set(
            ProviderStartupDecision(false, ProviderFallbackReason.MISSING_KEY),
        )
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            ProviderFallbackReason.entries.forEach { reason ->
                GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(false, reason))
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertFalse(
                        "terminal reason $reason constructed a map",
                        activity.window.decorView.containsMapView(),
                    )
                }
            }

            val ready = CountDownLatch(1)
            GoogleMapSurfaceTestHooks.onMapReady.set { ready.countDown() }
            GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
            scenario.recreate()
            assertTrue("new composition did not retry", ready.await(30, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.containsMapView())
            }
        }
    }

    @Test
    fun actualKeylessBuildDecisionConstructsNoMapView() {
        assumeFalse(
            "actual keyless path requires a build without the external key",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        GoogleMapSurfaceTestHooks.decision.set(null)
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.window.decorView.containsMapView())
            }
        }
    }

    @Test
    fun fogRuntimeMissingHasBoundedTerminalCover() {
        val created = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.fogCoverTimeoutMillis = 750L
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { created.countDown() }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue(
                "fog-required composition never constructed its guarded map",
                created.await(2, TimeUnit.SECONDS),
            )
            val deadline = SystemClock.elapsedRealtime() + 4_000L
            var mapStillPresent = true
            do {
                scenario.onActivity { activity ->
                    mapStillPresent = activity.window.decorView.containsMapView()
                }
                if (!mapStillPresent) break
                SystemClock.sleep(50L)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertFalse("missing FogRuntime left the safety cover unbounded", mapStillPresent)
        }
    }

    @Test
    fun actualProductionPolicyConstructsMapViewWithoutValidatedNetwork() {
        assumeTrue(
            "offline production path requires a build with the external key",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        val initiallyEnabled = readAirplaneMode()
        try {
            setAirplaneMode(true)
            assertTrue(
                "emulator retained a validated network after airplane mode was enabled",
                waitForValidatedNetwork(expected = false),
            )
            val created = CountDownLatch(1)
            GoogleMapSurfaceTestHooks.onMapViewCreated.set { created.countDown() }
            GoogleMapSurfaceTestHooks.decision.set(null)

            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
                assertTrue(
                    "production startup treated missing validated network as terminal",
                    created.await(15, TimeUnit.SECONDS),
                )
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.containsMapView())
                }
            }
        } finally {
            setAirplaneMode(initiallyEnabled)
            if (!initiallyEnabled) {
                waitForValidatedNetwork(expected = true)
                awaitLoadableBasemap()
            }
        }
    }

    /**
     * Restoring connectivity is not enough to undo this test's damage. The Maps SDK fetches its
     * client parameters once per process through a shared manager; if that first fetch is in
     * flight when airplane mode lands, the SDK backs off and the retry has been measured landing
     * about 16 s after the original request. Any later test hosting the *production* surface runs
     * under `fallbackTimeoutMillis = 5_000L`, so it never receives `OnMapLoadedCallback`, latches
     * `MAP_LOAD_TIMEOUT` and can never install canonical fog — a failure with no visible
     * connection to this test. Block here, on the 30 s test host, until the SDK proves it can
     * load a basemap again, so the leak is asserted at its source instead of surfacing as an
     * order-dependent failure elsewhere.
     */
    private fun awaitLoadableBasemap() {
        val online = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.reset()
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.onMapLoadState.set { state ->
            if (state == BasemapLoadState.ONLINE) online.countDown()
        }
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                assertTrue(
                    "the Maps SDK never loaded a basemap again after the offline window; " +
                        "later production-deadline tests would fail spuriously",
                    online.await(60, TimeUnit.SECONDS),
                )
            }
        } finally {
            GoogleMapSurfaceTestHooks.reset()
        }
    }

    private fun readAirplaneMode(): Boolean =
        shell("settings get global airplane_mode_on").trim() == "1"

    private fun setAirplaneMode(enabled: Boolean) {
        shell("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
        SystemClock.sleep(1_000L)
    }

    private fun waitForValidatedNetwork(expected: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        do {
            if (hasValidatedNetwork() == expected) return true
            SystemClock.sleep(250L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return hasValidatedNetwork() == expected
    }

    private fun hasValidatedNetwork(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return try {
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } finally {
            descriptor.close()
        }
    }

    private fun View.containsMapView(): Boolean {
        if (this is MapView) return true
        if (this !is ViewGroup) return false
        return (0 until childCount).any { index -> getChildAt(index).containsMapView() }
    }
}
