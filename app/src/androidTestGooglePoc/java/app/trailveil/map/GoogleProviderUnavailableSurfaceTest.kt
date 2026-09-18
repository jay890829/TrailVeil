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
import app.trailveil.R
import com.google.android.gms.maps.MapView
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
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

    private companion object {
        const val UNSET_MILLIS = -1L

        /**
         * The cover deadline this case arms, short so the whole case stays bounded. It is a
         * measurement parameter, not the shipped 20 s default.
         */
        const val COVER_TIMEOUT_MILLIS = 750L

        /** Liveness only. Every assertion below is reached by a callback, not by this expiring. */
        const val POLL_DEADLINE_MILLIS = 4_000L

        /** About one frame, so the bounded window yields tens of samples rather than a handful. */
        const val SAMPLE_INTERVAL_MILLIS = 16L

        /**
         * The deadline is counted with `delay` inside a `LaunchedEffect`, so it can report a few
         * milliseconds early relative to `elapsedRealtime`. Wide enough to absorb that and far
         * narrower than the gap to an immediate `getMapAsync` throw, which is what this separates
         * the deadline from.
         */
        const val TERMINAL_STAMP_TOLERANCE_MILLIS = 250L
    }

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
        val terminal = CountDownLatch(1)
        val mapViewRef = AtomicReference<MapView?>(null)
        val createdAt = AtomicLong(UNSET_MILLIS)
        val terminalAfterCreationMillis = AtomicLong(UNSET_MILLIS)
        // Whether the construction stamp existed at the instant the FIRST terminal was reported.
        // It has to be captured there rather than read back after the run: the terminal and the
        // stamp are written from two different phases of the same Compose apply pass, and the later
        // one still lands within milliseconds - long before these assertions execute - so by then
        // `createdAt` is set on both paths and the ordering that tells them apart is gone.
        val mapBuiltBeforeTerminal = AtomicBoolean(false)
        val terminalReasons = CopyOnWriteArrayList<String>()
        // Written by the main-thread sampler below, read by this thread after the terminal latch.
        val opaqueCoverSeen = AtomicBoolean(false)
        val polls = AtomicInteger(0)
        val coverDownPolls = AtomicInteger(0)
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.fogRequired = true
        // Left at 750 ms deliberately. An earlier repair proposal widened this to 3 s and that was
        // its only load-bearing step - the case would have gone green by being given more room to
        // win the same race. The race is removed below instead, so the armed deadline is still the
        // thing under measurement.
        GoogleMapSurfaceTestHooks.fogCoverTimeoutMillis = COVER_TIMEOUT_MILLIS
        // Arm the witness from inside the process, at the seam, and let it sample on the main
        // thread for the whole window. The case used to read the tag through
        // `scenario.onActivity` from the instrumentation thread, and on the owner's phone that
        // dispatch did not get through even once before the surface terminated - the run failed
        // claiming the cover was never raised when what actually happened is that nothing ever
        // looked. Posting is not the same as reading the tag inside the hook: this Runnable
        // executes in later main-thread messages, after the `SideEffect` that raises the cover has
        // returned, so it can still observe the cover going DOWN and is not tautological.
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { view ->
            mapViewRef.set(view)
            createdAt.compareAndSet(UNSET_MILLIS, SystemClock.elapsedRealtime())
            view.post(
                object : Runnable {
                    override fun run() {
                        // Instants strictly before the terminal report are the only ones that are
                        // evidence about the cover; the teardown itself may clear the tag. The
                        // elapsed bound is what stops this chain re-posting onto the main looper
                        // for the life of the process if no terminal surface ever arrives.
                        if (terminal.count == 0L) return
                        if (SystemClock.elapsedRealtime() - createdAt.get() > POLL_DEADLINE_MILLIS) {
                            return
                        }
                        // A sample taken while the view is off the window says nothing about what
                        // the SDK was showing, so it is skipped rather than counted - but the chain
                        // stays alive, because `View.post` before attachment defers to the run
                        // queue and an early detach must not silently end the measurement.
                        if (view.isAttachedToWindow) {
                            polls.incrementAndGet()
                            if (view.getTag(R.id.map_fog_synchronous_cover_up) == true) {
                                opaqueCoverSeen.set(true)
                            } else {
                                coverDownPolls.incrementAndGet()
                            }
                        }
                        view.postDelayed(this, SAMPLE_INTERVAL_MILLIS)
                    }
                },
            )
            created.countDown()
        }
        // The seam the whole case now hangs on. `TrailVeilMapSurface` cannot tear the guarded
        // MapView down without reporting a terminal reason first, so this callback is guaranteed to
        // PRECEDE the disappearance the case used to poll for - and unlike that poll it cannot be
        // missed by arriving late. Stamped relative to construction, because when it arrives is what
        // separates the armed cover deadline from a getMapAsync throw, which reports the same reason
        // immediately.
        GoogleMapSurfaceTestHooks.onTerminalFailure.set { reason ->
            terminalReasons += reason.name
            val createdStamp = createdAt.get()
            // The compareAndSet returns true exactly on the first terminal, which is the one the
            // elapsed figure describes, so the ordering witness is written under the same guard and
            // by the same single writer - no second atomic to keep in step with it.
            if (
                terminalAfterCreationMillis.compareAndSet(
                    UNSET_MILLIS,
                    SystemClock.elapsedRealtime() - createdStamp,
                )
            ) {
                mapBuiltBeforeTerminal.set(createdStamp != UNSET_MILLIS)
            }
            terminal.countDown()
        }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue(
                "fog-required composition never constructed its guarded map",
                created.await(2, TimeUnit.SECONDS),
            )
            // `V02-007`: watching only for the map to disappear would be satisfied just as well by
            // a guard that composed NOTHING over the unknown ground, which is the half MapLibre's
            // `requiredFogKeepsUnknownAreaCoveredUntilRuntimeIsReady` owns. The witness is the view
            // tag the surface publishes for the synchronous opaque ViewOverlay drawable - the thing
            // that actually hides SDK pixels within a frame - and it is sampled by the Runnable the
            // creation hook armed, not from here.
            assertTrue(
                "missing FogRuntime left the safety cover unbounded: no terminal surface was " +
                    "reported within " + POLL_DEADLINE_MILLIS + " ms of the guarded map being built",
                terminal.await(POLL_DEADLINE_MILLIS, TimeUnit.MILLISECONDS),
            )
            assertEquals(
                "the surface terminated for a reason other than its own cover deadline",
                listOf(ProviderFallbackReason.INITIALIZATION_FAILURE.name),
                terminalReasons.distinct(),
            )
            // Which deadline ended it. A getMapAsync throw reports INITIALIZATION_FAILURE too, and
            // arrives at once; without this the case cannot tell the two apart and a throwing SDK
            // would pass it silently.
            // That discrimination is only possible if there was a construction stamp to measure
            // from when the terminal arrived. On the earliest throw path there was not:
            // `onMapViewCreated` stamps `createdAt` from the `SideEffect` this surface opens, while
            // a synchronous `getMapAsync` throw reports its terminal failure from the body of a
            // `DisposableEffect`, and Compose dispatches remember observers before side effects
            // within a single apply pass. So on a first composition the terminal is reported while
            // `createdAt` is still `UNSET_MILLIS`, the stamp above subtracts `-1`, and the deadline
            // assertion below reads an elapsed time in the millions of ms - passing trivially, for
            // the one throw it exists to catch. Reading `createdAt` here would not catch it either,
            // because the `SideEffect` stamps it immediately afterwards and `created` has already
            // been awaited by this point; only the witness taken at the report distinguishes them.
            assertTrue(
                "the surface reported terminal before its map was constructed, so the elapsed " +
                    "time below was measured against an unset stamp rather than being a late " +
                    "terminal - a synchronous getMapAsync throw, not the armed cover deadline",
                mapBuiltBeforeTerminal.get(),
            )
            val terminalAt = terminalAfterCreationMillis.get()
            assertTrue(
                "the surface terminated " + terminalAt + " ms after its map was built, far short of the " +
                    "armed " + COVER_TIMEOUT_MILLIS + " ms cover deadline, so something other than that " +
                    "deadline ended it",
                terminalAt >= COVER_TIMEOUT_MILLIS - TERMINAL_STAMP_TOLERANCE_MILLIS,
            )
            assertTrue(
                "the fixture never sampled the guarded map before the surface terminated, so " +
                    "this run measured nothing about the cover",
                polls.get() > 0,
            )
            assertTrue(
                "the guarded map was torn down without the opaque cover ever being raised over " +
                    "the unknown ground it was hiding",
                opaqueCoverSeen.get(),
            )
            assertEquals(
                "the opaque cover was down at " + coverDownPolls.get() + " of " +
                    polls.get() + " sampled instants while the guarded map was still on screen",
                0,
                coverDownPolls.get(),
            )
            // Now a consequence being confirmed rather than a race being run: the terminal report
            // above already happened, so the removal it causes is merely awaited.
            var mapStillPresent = true
            val goneBy = SystemClock.elapsedRealtime() + POLL_DEADLINE_MILLIS
            while (mapStillPresent && SystemClock.elapsedRealtime() < goneBy) {
                scenario.onActivity { activity ->
                    mapStillPresent = activity.window.decorView.findMapView() != null
                }
                if (!mapStillPresent) break
                SystemClock.sleep(50L)
            }
            assertFalse(
                "the surface reported its terminal reason but never removed the guarded map",
                mapStillPresent,
            )
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

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        return (0 until childCount).firstNotNullOfOrNull { index ->
            getChildAt(index).findMapView()
        }
    }
}
