package app.trailveil.map

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-007`: what a basemap that never reports loaded presents on the Google variant, end to end.
 *
 * The shipped MapLibre variant answers an unreachable basemap by swapping to a bundled one and
 * carrying on behind an unavailable badge, and two of its cases assert exactly that: the surface is
 * not torn down, and a canonical fog generation is still published while that local basemap is
 * active. Neither behaviour exists here, and both absences are accepted product decisions, not
 * gaps: this variant ships no local basemap and no mid-session retry, and a latched
 * `MAP_LOAD_TIMEOUT` is terminal for the composition. That contract is settled in
 * `docs/0.2.0/evidence/V02-005-design.md` ("Mid-session SDK failure ... map-load timeout ...
 * terminal for THIS composition ... MapView torn down first"), and
 * `docs/0.2.0/evidence/V02-007-gates.md` records this file as the overturned-PARTIAL closure for
 * `unavailableProviderFallsBackWithoutRemovingTheMapSurface` and row 82 as an accepted replacement
 * rather than a twin. So this is the same question with the opposite expected answer, asserted here
 * rather than left untested:
 *
 *  - a MapView really is constructed AND its canonical fog binding really is built before the
 *    deadline elapses, so a surface that never got that far cannot discharge the claims below by
 *    having published nothing at all;
 *  - the opaque fog safety cover is up at every sampled instant the MapView is on screen, so
 *    nothing unexplored is ever shown while the basemap is failing;
 *  - the terminal provider-unavailable surface arrives NO EARLIER than the host's
 *    `fallbackTimeoutMillis` and well inside the fog-cover deadline, so it is the basemap deadline
 *    that produced it and not a fog fault (which renders the identical string) or a stuck cover;
 *  - and NO canonical generation is ever published - the inverse of the shipped variant's claim,
 *    asserted from the fog-state stream as well as the view tag so a polling gap cannot hide one.
 *
 * ### How the deadline is missed, and why it is no longer missed with airplane mode
 *
 * This case used to drive the deadline by putting the whole device into airplane mode. That worked,
 * but the Maps SDK fetches its client parameters once per process, and an offline window landing on
 * that fetch backs the SDK off PROCESS-WIDE for tens of seconds; the recorded API 36 run shows the
 * damage reaching at least three later map classes, whose own deadlines then elapsed for a reason
 * with no visible connection to this file. So the stimulus is now applied at the exact seam the
 * host's deadline reads, with the device left alone:
 * `GoogleHostedMapSurface` leaves `loadState` at `LOADING` until the SDK delivers
 * `OnMapLoadedCallback`, and that one callback is registered exactly once for a fog-required
 * surface (`GoogleMapSurfaceBinding`, `installMapLoadedListener = fogRequired`, with no
 * re-registration retry on that arm). Clearing it - the same `setOnMapLoadedCallback(null)` call
 * the production binding itself makes in `release()` - withholds the loaded signal for good, so the
 * deadline is missed deterministically against a fully working network.
 *
 * The clear is made from the FIRST fog-state publication, which the canonical binding emits
 * synchronously from its own `init` inside the `getMapAsync` callback, immediately after the map
 * binding registered the listener. Nothing runs on the main looper in between, so the signal cannot
 * be delivered in the gap; `loadStates` is asserted never to contain `ONLINE` so a map that had
 * already finished loading before the binding existed fails the case loudly instead of passing it.
 *
 * The deadline itself is shortened through `GoogleMapSurfaceTestHooks.fallbackTimeoutMillis` so the
 * case stays bounded and stays clearly inside the untouched fog-cover deadline, which is what makes
 * the terminal surface attributable to the basemap deadline rather than to the cover.
 *
 * Because the device is never taken offline, the recovery this case used to owe the rest of the
 * suite is now an asserted postcondition rather than a repair: the Maps SDK must still load a
 * basemap immediately afterwards.
 */
@RunWith(AndroidJUnit4::class)
class GoogleDegradedBasemapTerminalSurfaceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun setUp() = GoogleMapSurfaceTestHooks.reset()

    @After
    fun tearDown() = GoogleMapSurfaceTestHooks.reset()

    @Test
    fun aMissedBasemapDeadlineEndsOnTheTerminalSurfaceWithoutEverInstallingCanonicalFog() {
        assumeTrue(
            "driving a real basemap load deadline requires the keyed googlePoc runtime",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val loadStates = CopyOnWriteArrayList<BasemapLoadState>()
        val fogStates = CopyOnWriteArrayList<GoogleCanonicalFogState>()
        val created = CountDownLatch(1)
        val mapViewRef = AtomicReference<MapView>()
        val createdAt = AtomicLong(UNSET_MILLIS)
        val firstFogStateAt = AtomicLong(UNSET_MILLIS)
        val withheldAt = AtomicLong(UNSET_MILLIS)
        val withholdFailure = AtomicReference<Throwable>()
        val terminalReasons = CopyOnWriteArrayList<String>()
        // Withholds the SDK's one loaded signal. Idempotent, so the two seams below can both try.
        val withholdLoadedSignal: (GoogleMap) -> Unit = { map ->
            if (withheldAt.get() == UNSET_MILLIS) {
                runCatching { map.setOnMapLoadedCallback(null) }
                    .onSuccess {
                        withheldAt.compareAndSet(UNSET_MILLIS, SystemClock.elapsedRealtime())
                    }
                    .onFailure { failure -> withholdFailure.compareAndSet(null, failure) }
            }
        }

        // Pin the startup gate open. Production policy already constructs a MapView without a
        // validated network, and `actualProductionPolicyConstructsMapViewWithoutValidatedNetwork`
        // owns that claim; pinning it here keeps THIS case about the runtime load deadline.
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.fogRuntime = application.appContainer.fogRuntime()
        GoogleMapSurfaceTestHooks.fallbackTimeoutMillis = FALLBACK_TIMEOUT_MILLIS
        // Left at the shipped cover deadline. The terminal surface below must arrive well
        // inside it, so the failure cannot be attributed to a stuck cover instead.
        GoogleMapSurfaceTestHooks.fogCoverTimeoutMillis = COVER_TIMEOUT_MILLIS
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { view ->
            mapViewRef.set(view)
            createdAt.compareAndSet(UNSET_MILLIS, SystemClock.elapsedRealtime())
            created.countDown()
        }
        GoogleMapSurfaceTestHooks.onMapLoadState.set { state -> loadStates += state }
        GoogleMapSurfaceTestHooks.onTerminalFailure.set { reason ->
            terminalReasons += reason.name + "@" + (SystemClock.elapsedRealtime() - createdAt.get())
        }
        GoogleMapSurfaceTestHooks.onFogState.set { state ->
            fogStates += state
            firstFogStateAt.compareAndSet(UNSET_MILLIS, SystemClock.elapsedRealtime())
            // The map handle the host publishes at the top of its own `getMapAsync` callback, so
            // this runs in the same looper turn the loaded listener was registered in.
            (mapViewRef.get()?.getTag(R.id.map_detail_map_instance) as? GoogleMap)
                ?.let(withholdLoadedSignal)
        }
        // Second, later seam: harmless if the first already landed, and the only one left if the
        // fog binding is never built (which the assertions below then report as the real failure).
        GoogleMapSurfaceTestHooks.onMapReady.set(withholdLoadedSignal)

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue(
                "a degraded basemap must still construct its guarded MapView; without one " +
                    "this case would be about the startup gate, not the load deadline",
                composeRule.awaitPumping(MAP_VIEW_TIMEOUT_SECONDS * 1_000L) {
                    created.count == 0L
                },
            )
            val mapView = requireNotNull(mapViewRef.get())
            val startedAt = createdAt.get()

            var opaqueCoverSeen = false
            var composedCoverSeen = false
            var loadingBadgeSeen = false
            var generationSeen: Any? = null
            var bindingStateSeen: Any? = null
            var bindingBuiltAt = UNSET_MILLIS
            var polls = 0
            var coverDownPolls = 0
            var terminalAt = UNSET_MILLIS
            // The host counts both of its deadlines with `delay` inside a `LaunchedEffect`, so
            // under this rule they are counted on the COMPOSITION clock, not on wall time: see
            // `ComposeClockPump`. Attribution below is therefore measured on `mainClock`, which is
            // the clock the deadline actually runs on, and wall time is only a liveness bound so a
            // pump that stops winding cannot hang the case.
            val compositionStartedAt = composeRule.mainClock.currentTime
            var compositionTerminalAt = UNSET_MILLIS
            val compositionDeadline =
                compositionStartedAt + FALLBACK_TIMEOUT_MILLIS + TERMINAL_SLACK_MILLIS
            val wallDeadline = startedAt + WALL_LIVENESS_MILLIS
            while (
                composeRule.mainClock.currentTime < compositionDeadline &&
                SystemClock.elapsedRealtime() < wallDeadline
            ) {
                composeRule.pumpComposition(POLL_MILLIS)
                // Sampled first and the loop left immediately: past this point the composition has
                // been replaced, the cover released and the MapView detached, so a sample taken
                // after it would be measuring the terminal surface, not the failing one.
                if (nodeCount(MapSurfaceTestTags.ProviderUnavailable) > 0) {
                    terminalAt = SystemClock.elapsedRealtime()
                    compositionTerminalAt = composeRule.mainClock.currentTime
                    break
                }
                polls += 1
                if (mapView.getTag(R.id.map_fog_synchronous_cover_up) == true) {
                    opaqueCoverSeen = true
                } else {
                    coverDownPolls += 1
                }
                if (nodeCount(MapSurfaceTestTags.FogSafetyCover) > 0) composedCoverSeen = true
                if (nodeCount(MapSurfaceTestTags.Status) > 0) loadingBadgeSeen = true
                if (generationSeen == null) {
                    generationSeen = mapView.getTag(R.id.map_fog_canonical_generation)
                }
                if (bindingBuiltAt == UNSET_MILLIS) {
                    val state = mapView.getTag(R.id.map_fog_binding_state)
                    if (state?.toString()?.contains(BINDING_BUILT_MARKER) == true) {
                        bindingStateSeen = state
                        bindingBuiltAt = SystemClock.elapsedRealtime()
                    }
                }
            }

            assertNull(
                "withholding the SDK's map-loaded signal threw, so the stimulus this case " +
                    "depends on was never applied: " + withholdFailure.get(),
                withholdFailure.get(),
            )
            assertTrue(
                "the SDK's map-loaded signal was never withheld, so this run drove no missed " +
                    "deadline at all: " + describe(mapView),
                withheldAt.get() != UNSET_MILLIS,
            )
            assertFalse(
                "the basemap reported ONLINE before its loaded signal could be withheld, so no " +
                    "load deadline was missed and this run proved nothing about the terminal " +
                    "path: loadStates=$loadStates withheldAfterMs=${withheldAt.get() - startedAt}",
                loadStates.contains(BasemapLoadState.ONLINE),
            )
            assertTrue(
                "the surface never showed the loading badge, so it never sat in the state " +
                    "this case claims it leaves: " + describe(mapView),
                loadingBadgeSeen,
            )
            // `V02-007` M1: every claim below is about what a LIVE canonical fog binding did not
            // do. An empty world satisfies all of them, and a `getMapAsync` landing after the
            // shortened deadline would produce exactly that empty world, so the binding's own
            // existence is asserted first. It publishes its first state synchronously from `init`,
            // so this witness cannot lag the thing it witnesses.
            assertTrue(
                "no canonical fog binding was ever built, so 'no generation was published' holds " +
                    "vacuously and this run proves nothing: " + describe(mapView),
                fogStates.isNotEmpty(),
            )
            assertTrue(
                "the fog binding was never reported built on the MapView, so the surface never " +
                    "reached the state whose behaviour this case asserts: " + describe(mapView),
                bindingBuiltAt != UNSET_MILLIS,
            )
            assertTrue(
                "the fog binding was only built ${bindingBuiltAt - startedAt}ms after the MapView " +
                    "appeared, at or after the ${FALLBACK_TIMEOUT_MILLIS}ms basemap deadline, so " +
                    "the deadline elapsed over a surface that had not finished starting: " +
                    "bindingState=$bindingStateSeen " + describe(mapView),
                bindingBuiltAt - startedAt < FALLBACK_TIMEOUT_MILLIS,
            )
            assertTrue(
                "the opaque fog cover was never raised over the degraded basemap, so raw " +
                    "basemap frames could have been presented: " + describe(mapView),
                opaqueCoverSeen,
            )
            assertTrue(
                "no FogSafetyCover node was ever composed while the basemap was failing, so " +
                    "a guard that composed nothing at all would have passed: " + describe(mapView),
                composedCoverSeen,
            )
            assertEquals(
                "the opaque fog cover was DOWN on $coverDownPolls of $polls samples taken while " +
                    "the MapView was on screen and no generation had been proven, so unexplored " +
                    "ground was presented: " + describe(mapView),
                0,
                coverDownPolls,
            )
            assertTrue(
                "the surface never reached the terminal provider-unavailable surface within " +
                    "${composeRule.mainClock.currentTime - compositionStartedAt}ms of " +
                    "composition clock (${SystemClock.elapsedRealtime() - startedAt}ms wall) " +
                    "after its MapView appeared; it is still on the loading badge: " +
                    "terminalReasons=$terminalReasons loadStates=$loadStates " +
                    describe(mapView),
                terminalAt != UNSET_MILLIS,
            )
            val elapsed = compositionTerminalAt - compositionStartedAt
            val wallElapsed = terminalAt - startedAt
            assertTrue(
                "[terminalReasons=$terminalReasons wallMs=$wallElapsed] the terminal surface " +
                    "arrived ${elapsed}ms of composition clock after the MapView " +
                    "appeared, at or " +
                    "past the ${COVER_TIMEOUT_MILLIS}ms fog-cover deadline, so it cannot be " +
                    "attributed to the ${FALLBACK_TIMEOUT_MILLIS}ms basemap deadline",
                elapsed < COVER_TIMEOUT_MILLIS,
            )
            // `V02-007` M2: MAP_LOAD_TIMEOUT and INITIALIZATION_FAILURE render the SAME string, so
            // the surface on screen cannot say which one produced it. A fog fault with nothing
            // proven, or a throwing `getMapAsync` body, terminates within a few hundred ms of the
            // MapView appearing; only the basemap deadline can terminate at its own deadline.
            assertTrue(
                "[terminalReasons=$terminalReasons wallMs=$wallElapsed] the terminal surface " +
                    "arrived ${elapsed}ms of composition clock after the MapView " +
                    "appeared, far short " +
                    "of the ${FALLBACK_TIMEOUT_MILLIS}ms basemap deadline, so it was produced by " +
                    "something else that renders the same copy - a fog initialisation failure, or " +
                    "a MapView that could not be constructed: " + describe(mapView),
                elapsed >= FALLBACK_TIMEOUT_MILLIS - TERMINAL_LOWER_SLACK_MILLIS,
            )
            assertEquals(
                "the terminal surface was produced by a reason other than the basemap load " +
                    "deadline, so it says nothing about the deadline this case drives: " +
                    terminalReasons.toString(),
                listOf(ProviderFallbackReason.MAP_LOAD_TIMEOUT.name),
                terminalReasons.map { reason -> reason.substringBefore('@') },
            )
            assertTrue(
                "the canonical fog binding went terminal on its own, so the unavailable surface " +
                    "is a fog failure wearing the basemap deadline's copy: " +
                    fogStates.count { state -> state.terminal } + " terminal fog states",
                fogStates.none { state -> state.terminal },
            )
            assertEquals(
                "the loading badge is still displayed on the terminal surface",
                0,
                nodeCount(MapSurfaceTestTags.Status),
            )
            scenario.onActivity { activity ->
                assertFalse(
                    "the terminal surface kept a MapView on screen",
                    activity.window.decorView.containsMapView(),
                )
            }
            assertNull(
                "a canonical fog generation was published on a degraded basemap; this " +
                    "variant has no local basemap to publish one behind: " + describe(mapView),
                generationSeen,
            )
            assertTrue(
                "the fog-state stream published an installed generation while the basemap " +
                    "was degraded: " + fogStates.mapNotNull { it.installedGeneration },
                fogStates.none { state -> state.installedGeneration != null },
            )
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "degraded_basemap_terminal_ms=$elapsed " +
                            "degraded_basemap_terminal_wall_ms=$wallElapsed " +
                            "fallbackTimeoutMs=$FALLBACK_TIMEOUT_MILLIS " +
                            "coverTimeoutMs=$COVER_TIMEOUT_MILLIS " +
                            "loadedSignalWithheldMs=${withheldAt.get() - startedAt} " +
                            "firstFogStateMs=${firstFogStateAt.get() - startedAt} " +
                            "bindingBuiltMs=${bindingBuiltAt - startedAt} " +
                            "fogStates=${fogStates.size} coverSamples=$polls " +
                            "coverDownSamples=$coverDownPolls\n",
                    )
                },
            )
        }

        assertBasemapStillLoadable()
    }

    /**
     * The postcondition that used to be a repair.
     *
     * While this case took the device offline it owed the rest of the suite a recovery wait: the
     * Maps SDK fetches its client parameters once per process, and an offline window landing on
     * that fetch backs it off for tens of seconds, so later cases hosting the production surface
     * missed their own deadlines for a reason invisible from where they failed. Nothing here
     * touches device networking any more and the withheld callback dies with this activity's map,
     * so the SDK must still be able to load a basemap immediately - asserted rather than assumed,
     * because "this case wedged the device" is exactly the failure mode being retired.
     */
    private fun assertBasemapStillLoadable() {
        val online = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.reset()
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.onMapLoadState.set { state ->
            if (state == BasemapLoadState.ONLINE) online.countDown()
        }
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                assertTrue(
                    "the Maps SDK never loaded a basemap again after this case, so it left the " +
                        "device in a state later production-deadline cases would fail in",
                    // ONLINE reaches this hook from a recomposition, so the wait has to wind the
                    // composition clock as well as pass wall time (`ComposeClockPump`). Waiting on
                    // the latch alone reported a wedged Maps SDK when nothing was wedged: the
                    // surface simply had no scheduled recomposition in which to publish the state.
                    composeRule.awaitPumping(RELOAD_TIMEOUT_SECONDS * 1_000L) {
                        online.count == 0L
                    },
                )
            }
        } finally {
            GoogleMapSurfaceTestHooks.reset()
        }
    }

    private fun nodeCount(tag: String): Int =
        runCatching { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size }
            .getOrDefault(-1)

    /** Booleans, names and counts only; nothing positional. */
    private fun describe(mapView: MapView): String =
        "[basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
            "binding=${mapView.getTag(R.id.map_fog_binding_state)} " +
            "gates=${mapView.getTag(R.id.map_fog_binding_gates)} " +
            "runtimePresent=${mapView.getTag(R.id.map_fog_runtime_present)} " +
            "phase=${mapView.getTag(R.id.map_fog_phase)} " +
            "lastFogFailure=${mapView.getTag(R.id.map_fog_last_failure)} " +
            "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
            "cover=${mapView.getTag(R.id.map_fog_cover_up)} " +
            "syncCover=${mapView.getTag(R.id.map_fog_synchronous_cover_up)} " +
            "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown} " +
            "unavailableNodes=${nodeCount(MapSurfaceTestTags.ProviderUnavailable)} " +
            "statusNodes=${nodeCount(MapSurfaceTestTags.Status)}]"

    private fun View.containsMapView(): Boolean {
        if (this is MapView) return true
        if (this !is ViewGroup) return false
        return (0 until childCount).any { index -> getChildAt(index).containsMapView() }
    }

    private companion object {
        const val UNSET_MILLIS = -1L

        /** Short enough to bound the case, far enough inside [COVER_TIMEOUT_MILLIS] to attribute. */
        const val FALLBACK_TIMEOUT_MILLIS = 6_000L
        const val TERMINAL_SLACK_MILLIS = 6_000L

        /**
         * Wall-clock liveness bound on the pumped window.
         *
         * Generous, because one composition millisecond costs more than one wall millisecond to
         * wind: the pump advances [POLL_MILLIS] and then sleeps [POLL_MILLIS], and the sampling in
         * between is not free. It exists only so a pump that stops winding fails the case instead
         * of hanging it; every attribution below is measured on the composition clock.
         */
        const val WALL_LIVENESS_MILLIS = 90_000L

        /**
         * How far BELOW its own deadline the terminal surface may be observed.
         *
         * The host's `LaunchedEffect` starts its delay no earlier than the composition that
         * publishes the MapView to [GoogleMapSurfaceTestHooks.onMapViewCreated], and
         * `repeatOnLifecycle(STARTED)` can only start it later, so the measured elapsed time is a
         * lower bound on the delay itself. This covers the sampling lag alone.
         */
        const val TERMINAL_LOWER_SLACK_MILLIS = 750L

        /** The shipped fog-cover deadline; deliberately not shortened. */
        const val COVER_TIMEOUT_MILLIS = 20_000L
        const val MAP_VIEW_TIMEOUT_SECONDS = 20L
        const val RELOAD_TIMEOUT_SECONDS = 60L
        const val POLL_MILLIS = 100L

        /** `GoogleHostedMapSurface` writes `built=<bool>` into `map_fog_binding_state`. */
        const val BINDING_BUILT_MARKER = "built=true"
    }
}
