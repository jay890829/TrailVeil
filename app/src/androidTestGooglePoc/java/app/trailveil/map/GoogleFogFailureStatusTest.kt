package app.trailveil.map

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.R
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.GeoPoint
import app.trailveil.ui.theme.TrailVeilTheme
import com.google.android.gms.maps.MapView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Google twin of the MapLibre fog-status cases: when canonical fog cannot be produced, the
 * user must be told so, must be told again once it heals, and must never be shown unexplored ground
 * as explored while the machinery is broken.
 *
 * The three MapLibre cases this class stands in for are
 * `MapSurfaceTest#fogFailureStatusOutranksTheLocalBasemapFallback`,
 * `#changeFeedFailureKeepsTheFogStatusVisibleDuringTheLocalFallback` and
 * `#fogStatusClearsAfterTheChangeFeedRecovers` (parity-inventory rows 83, 85 and 86). Their ranking
 * clause - fog failure outranks the basemap-fallback badge - has no Google referent, because there
 * is no local basemap and no fallback badge, so what is owed here is the status half alone.
 *
 * WHAT EVERY CASE HERE DRIVES. Each brings the real hosted Google surface up HEALTHY on a real
 * DAO-backed `FogRuntime`, waits until a canonical generation has actually been proven and
 * installed with both covers down, and only then switches a fixture to throwing. That order is
 * forced by the product, not chosen for convenience: `FogOverlaySurfaceCoordinator`'s
 * `classifyFogInstallFailure` returns TERMINAL_FOR_COMPOSITION whenever NOTHING has been proven, so
 * a fault injected into a cold surface raises the cover, sets `terminal`, and makes
 * `TrailVeilMapSurface` replace the whole map slot with `MapProviderUnavailableSurface`. No map
 * view is ever built and no status is ever composed. RETRY_BEHIND_PLACEHOLDERS - the only arm that
 * sets `retryScheduled`, and therefore the only route to this status - requires
 * `installedGenerationId != null`.
 *
 * THE DIVERGENCE THE COORDINATOR MUST RECORD (parity-inventory row 85).
 * The MapLibre case asserts the status is displayed CONTINUOUSLY across a window spanning several
 * retries. That cannot be asserted on the Google surface without a product change, and this class
 * deliberately does not assert it:
 *
 *  * MapLibre latches its own failure flag. `mapLibre/TrailVeilMapSurface.kt` sets
 *    `fogSyncFailed = true` in the synchronization loop's catch and clears it only on a SUCCESSFUL
 *    synchronization, and the badge at that file's `statusText` reads
 *    `fogRenderFailed || fogSyncFailed`. The flag therefore stays true for the whole outage.
 *  * Google has no such flag. The badge is composed at
 *    `googlePoc/.../GoogleHostedMapSurface.kt` under `fogState?.retryScheduled == true &&
 *    !fogCoverUp`, and `retryScheduled` is cleared on EVERY retry tick
 *    (`FogOverlaySurfaceCoordinator.onRetryFogOperation`) and again at
 *    `FogOverlaySurfaceCoordinator.completeInstall`. Between a tick and the failure that follows
 *    it - and, for a feed outage, for the whole of every rebuild that succeeds while the feed stays
 *    broken - the badge is legitimately absent.
 *
 * The two product sites are therefore `GoogleHostedMapSurface`'s status branch and
 * `FogOverlaySurfaceCoordinator`'s two `retryScheduled = false` writes
 * (`onRetryFogOperation`, `completeInstall`). Closing the gap needs a latched fog-unavailable state
 * on the Google surface; nothing of the sort is added here, because a test must not invent product
 * behaviour. THE COORDINATOR MUST RECORD ROW 85 EITHER AS AN ACCEPTED REPLACEMENT - the property
 * below - OR AS A PRODUCT DECISION to add the latch. It is not closed as a twin by this class.
 *
 * WHAT IS ASSERTED INSTEAD, as the property the Google surface actually contracts:
 *
 *  * the user is TOLD fog is unavailable while it is - the status is displayed, and is displayed
 *    again after at least one full retry cadence has passed, so a one-shot badge that latched off
 *    fails here even though a merely blinking one does not;
 *  * nothing unexplored is ever presented as revealed while that is true - at every sample either a
 *    cover is up or the proven canonical generation is still installed and presented, and the
 *    generation never regresses below the one proven before the fault;
 *  * the surface never goes terminal, which is the §9 contract for a failure that lands while a
 *    proven generation stands;
 *  * once the canonical feed heals, the status clears AND a NEWER canonical generation installs
 *    with the cover down - a healed surface that merely stopped complaining fails.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogFailureStatusTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        GoogleMapSurfaceTestHooks.reset()
        // The harness activity composes its own surface from these hooks in onCreate, before a
        // test can install a runtime. A terminal startup decision makes that first composition
        // inert, so the only MapView in the window is the one hosted by [hostFogSurface].
        GoogleMapSurfaceTestHooks.decision.set(
            ProviderStartupDecision(false, ProviderFallbackReason.MISSING_KEY),
        )
    }

    @After
    fun tearDown() = GoogleMapSurfaceTestHooks.reset()

    /**
     * A canonical change feed that starts refusing stops fog tracking new points at all, so the
     * user must be told, must keep being told while it lasts, and must not meanwhile be shown
     * ground the app cannot prove is explored.
     *
     * The stimulus is a canonical point appended AFTER the feed is switched off: the binding only
     * reads the feed again when the Room revision flow emits, and that emission is what carries the
     * refusal into `FogChangeSynchronizer.synchronizeTo`.
     *
     * PARITY: parity-inventory row 85,
     * `MapSurfaceTest#changeFeedFailureKeepsTheFogStatusVisibleDuringTheLocalFallback`.
     * This is the case that carries the divergence in the class KDoc: the MapLibre original asserts
     * the badge is displayed CONTINUOUSLY across the window, which the Google surface does not
     * contract. An ACCEPTED REPLACEMENT, not a twin, until the coordinator decides otherwise.
     */
    @Test
    fun aFailingChangeFeedNamesFogUnavailableAndKeepsTheProvenGenerationPresented() {
        assumeKeyConfigured()
        val badgeText = fogUnavailableText()
        val database = inMemoryDatabase()
        try {
            val feed = SwitchableChangeFeed(
                RoomPersistedTrackPointChangeFeed(database.recordingDao()),
            )
            val observation = SurfaceObservation()
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
                hostFogSurface(
                    scenario = scenario,
                    savedStateKey = "trailveil.map.google-feed-failure-test",
                    runtime = fogRuntime(database, feed),
                    observation = observation,
                )
                awaitBasemapOnline(observation)
                val proven = awaitProvenGeneration(observation)
                val appendCanonicalPoint = canonicalPointAppender(database)

                val failuresBeforeFault = observation.fogFailures.size
                feed.failFromNow()
                appendCanonicalPoint(renderedCenter(observation))
                awaitFogFailure(observation, failuresBeforeFault)
                awaitStatusDisplayed(badgeText, observation)

                val window = observeRetryWindow(badgeText, observation, proven)
                assertRetryWindow(window, observation)
                assertTrue(
                    "The window did not span several synchronization retries: the switched-off " +
                        "feed refused ${feed.refusalCount} call(s), and the binding restarts " +
                        "synchronization one second after each failure " +
                        "(GoogleCanonicalFogSurfaceBinding.failSynchronization). " +
                        "${window.summary()} ${observation.summary()}",
                    feed.refusalCount >= MINIMUM_FEED_REFUSALS,
                )
            }
        } finally {
            database.close()
        }
    }

    /**
     * The fog-failure status must be visibility only. Once the feed heals, the status has to
     * disappear AND a newer canonical generation has to install - a status that latched on, or a
     * surface that can never install fog again after one feed failure, both fail here.
     *
     * PARITY: parity-inventory row 86, `MapSurfaceTest#fogStatusClearsAfterTheChangeFeedRecovers`.
     * A twin on everything that has a Google referent. The original's closing assertion is that the
     * LOCAL-BASEMAP fallback badge returns in the fog badge's place; Google has neither, so the
     * healed state asserted here is the stronger available one - a NEWER canonical generation
     * installed with both covers down - rather than the mere absence of a badge.
     */
    @Test
    fun fogStatusClearsAndCanonicalFogInstallsAfterTheChangeFeedRecovers() {
        assumeKeyConfigured()
        val badgeText = fogUnavailableText()
        val database = inMemoryDatabase()
        try {
            val feed = SwitchableChangeFeed(
                RoomPersistedTrackPointChangeFeed(database.recordingDao()),
            )
            val observation = SurfaceObservation()
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
                hostFogSurface(
                    scenario = scenario,
                    savedStateKey = "trailveil.map.google-feed-recovery-test",
                    runtime = fogRuntime(database, feed),
                    observation = observation,
                )
                awaitBasemapOnline(observation)
                awaitProvenGeneration(observation)
                val appendCanonicalPoint = canonicalPointAppender(database)

                val failuresBeforeFault = observation.fogFailures.size
                feed.failFromNow()
                appendCanonicalPoint(renderedCenter(observation))
                awaitFogFailure(observation, failuresBeforeFault)
                // The anchor. Without an OBSERVED presence, every wait for the status to be absent
                // below is satisfied by a status that was never displayed in the first place, which
                // is exactly how this case passed vacuously before.
                awaitStatusDisplayed(badgeText, observation)
                val generationAtFailure = requireInstalledGeneration(observation)
                val subscriptionsBeforeRestore = feed.subscriptionCount

                feed.restore()
                awaitFeedResubscribed(feed, subscriptionsBeforeRestore, observation)
                appendCanonicalPoint(renderedCenter(observation))

                val healed = awaitStatusClearedWithANewGeneration(
                    badgeText = badgeText,
                    observation = observation,
                    previousGeneration = generationAtFailure,
                )
                assertStatusStaysCleared(badgeText, observation, healed)
            }
        } finally {
            database.close()
        }
    }

    /**
     * The other half of the MapLibre ranking case: a tile render that throws is a fog failure the
     * user is entitled to see named, with the ground still covered by the proven generation while
     * it lasts.
     *
     * Two things have to be arranged before the fault can be reached at all. The renderer is armed
     * only after a generation is proven, for the terminal-classification reason in the class KDoc;
     * and the derived mask cache is dropped first, because `FogTilePipeline` serves cache hits
     * without calling the renderer, so an armed fault over a warm cache is simply never invoked.
     * Dropping the cache also keeps the fault on the generation-render path
     * (`GoogleCanonicalFogSurfaceBinding.failGeneration`) rather than inside
     * `FogTilePipeline.mergeReveal`, which would surface as the other case's synchronization
     * failure instead.
     *
     * PARITY: parity-inventory row 83,
     * `MapSurfaceTest#fogFailureStatusOutranksTheLocalBasemapFallback`. A twin on the status half.
     * The ranking half - fog failure outranks the basemap-fallback badge - is NA on Google, which
     * has no local basemap and no fallback badge to outrank (an accepted stage-9 delta).
     */
    @Test
    fun aFailingFogTileRenderNamesFogUnavailableAndNeverPresentsBareGround() {
        assumeKeyConfigured()
        val badgeText = fogUnavailableText()
        val database = inMemoryDatabase()
        try {
            val fixture = SwitchableRenderFogRuntime(
                database = database,
                pointChanges = RoomPersistedTrackPointChangeFeed(database.recordingDao()),
            )
            val observation = SurfaceObservation()
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
                hostFogSurface(
                    scenario = scenario,
                    savedStateKey = "trailveil.map.google-fog-render-failure-test",
                    runtime = fixture.runtime,
                    observation = observation,
                )
                // No render is attempted before the SDK reports a loaded basemap: the binding gates
                // `requestCurrentViewportIfReady` on `mapLoaded`, which only `OnMapLoadedCallback`
                // sets. Without this wait a failure to load would be reported as a fog defect.
                awaitBasemapOnline(observation)
                val proven = awaitProvenGeneration(observation)
                val appendCanonicalPoint = canonicalPointAppender(database)

                val failuresBeforeFault = observation.fogFailures.size
                fixture.failFromNow()
                clearDerivedFogCache(fixture.runtime)
                appendCanonicalPoint(renderedCenter(observation))
                awaitFogFailure(observation, failuresBeforeFault)
                awaitStatusDisplayed(badgeText, observation)

                val window = observeRetryWindow(badgeText, observation, proven)
                assertRetryWindow(window, observation)
                assertTrue(
                    "The window did not span several canonical rebuild retries: the switched-off " +
                        "renderer refused ${fixture.renderRefusalCount} complete-mask render(s), " +
                        "and the binding re-arms a rebuild one second after each failure " +
                        "(FogOverlaySurfaceCoordinator.onRetryFogOperation). " +
                        "${window.summary()} ${observation.summary()}",
                    fixture.renderRefusalCount >= MINIMUM_RENDER_REFUSALS,
                )
            }
        } finally {
            database.close()
        }
    }

    private fun assumeKeyConfigured() = assumeTrue(
        "the hosted Google fog surface requires a configured Maps key; a keyless build SKIPS this " +
            "case rather than failing it, so acceptance evidence must cite the executed count",
        BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
    )

    private fun fogUnavailableText(): String = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .getString(R.string.map_fog_unavailable)

    /**
     * Replaces the harness activity's own composition with the production Google surface under
     * test.
     *
     * The activity composes `TrailVeilMapSurface` from [GoogleMapSurfaceTestHooks], which carry no
     * `onFogRendered`/`onFogFailure` seam, and its content is set before a test can install a
     * runtime. Setting the content again here is the only way to host the real surface with the
     * fog callbacks this class asserts on, and it costs nothing: [GoogleMapSurfaceTestHooks]
     * is left at a terminal startup decision so the first composition builds no MapView at all.
     */
    private fun hostFogSurface(
        scenario: ActivityScenario<GoogleMapSurfaceTestActivity>,
        savedStateKey: String,
        runtime: FogRuntime,
        observation: SurfaceObservation,
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                TrailVeilTheme {
                    TrailVeilMapSurface(
                        modifier = Modifier.fillMaxSize(),
                        fallbackTimeoutMillis = BASEMAP_LOAD_TIMEOUT_MILLIS,
                        savedStateKey = savedStateKey,
                        fogRuntime = runtime,
                        fogRequired = true,
                        providerStartupDecisionForTesting = ProviderStartupDecision(true, null),
                        onFogRendered = { rendered ->
                            observation.renderCount.incrementAndGet()
                            // The first rendered viewport's centre is where this class puts its
                            // canonical points, so the change it appends is one the camera is
                            // actually looking at and no coordinate is written into the case.
                            observation.renderedCenter.compareAndSet(null, rendered.request.center)
                        },
                        onFogFailure = { failure ->
                            observation.fogFailures += failure.javaClass.simpleName
                        },
                        onMapViewCreatedForTesting = { view -> observation.mapView.set(view) },
                        onMapLoadStateForTesting = observation::recordLoadState,
                        onFogStateForTesting = observation::recordFogState,
                    )
                }
            }
        }
    }

    /**
     * Starts one recording session in [database] and returns a sink that appends accepted points
     * around a supplied centre.
     *
     * Every canonical change these cases need goes through this. The Room revision flow is what
     * makes the binding read the change feed again, and a MERGED change is what makes
     * `FogSynchronizationRenderPolicy` return REFRESH_CURRENT_CAMERA and the coordinator ask for a
     * new generation. The database is the case's own in-memory one, so nothing here touches the
     * app's real recording state.
     */
    private fun canonicalPointAppender(database: TrailVeilDatabase): (GeoPoint) -> Unit {
        val dao = database.recordingDao()
        val recording = runBlocking {
            dao.startSession(
                session = RecordingSessionEntity(
                    startedAt = FIRST_POINT_TIMESTAMP_MILLIS,
                    status = RecordingStatus.ACTIVE,
                    createdAppVersion = "google-fog-status-test",
                ),
                initialSegment = TrackSegmentEntity(
                    sessionId = 0,
                    sequence = 0,
                    startedAt = FIRST_POINT_TIMESTAMP_MILLIS,
                    startReason = "SESSION_START",
                ),
            )
        }
        val appended = AtomicInteger(0)
        return { center ->
            val index = appended.getAndIncrement()
            runBlocking {
                dao.appendAcceptedPoint(
                    point = TrackPointEntity(
                        sessionId = recording.sessionId,
                        segmentId = recording.segmentId,
                        sequence = index.toLong(),
                        timestamp = FIRST_POINT_TIMESTAMP_MILLIS + index * POINT_INTERVAL_MILLIS,
                        latitude = center.latitude,
                        longitude = (center.longitude + index * POINT_LONGITUDE_STEP)
                            .coerceIn(MINIMUM_LONGITUDE, MAXIMUM_LONGITUDE),
                        horizontalAccuracy = POINT_ACCURACY_METERS,
                    ),
                    distanceDeltaMeters = POINT_DISTANCE_DELTA_METERS,
                )
            }
        }
    }

    /**
     * Waits for a fog failure that the caller's fault produced, not merely for one to exist.
     *
     * [failuresBefore] is the count read just before the fixture is switched to throwing. Waiting
     * on `isNotEmpty()` instead would be satisfied by any transient startup failure - an expired
     * render budget on a slow image, a stranded pending render - that landed while the surface was
     * still coming up healthy, and this case would then attribute everything it goes on to observe
     * to a fault it never actually delivered.
     */
    /**
     * One poll interval that also lets the composition make progress.
     *
     * `createEmptyComposeRule` puts this test in charge of the Compose test clock for every
     * composition in the process, including the one hosted here through `setContent`. A poll loop
     * that only slept therefore froze the surface it was waiting on: the activity reached RESUMED
     * and then nothing recomposed for the whole 30 s budget - no map view, no load state, no fog -
     * and the case failed blaming the basemap. Draining the rule's queue each turn is what makes
     * the wait a wait rather than a deadlock.
     */
    private fun pumpComposition() {
        runCatching { composeRule.waitForIdle() }
        SystemClock.sleep(POLL_MILLIS)
    }

    private fun awaitFogFailure(observation: SurfaceObservation, failuresBefore: Int) {
        val deadline = SystemClock.uptimeMillis() + FOG_FAILURE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (observation.fogFailures.size > failuresBefore) return
            pumpComposition()
        }
        throw AssertionError(
            "The injected fog failure never reached the surface within " +
                "${FOG_FAILURE_TIMEOUT_MILLIS}ms (the surface had already reported " +
                "$failuresBefore failure(s) before the fault was armed, and reported none " +
                "after), so nothing this case asserts was ever exercised. " +
                observation.summary(),
        )
    }

    private fun awaitBasemapOnline(observation: SurfaceObservation) {
        val deadline = SystemClock.uptimeMillis() + BASEMAP_ONLINE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (observation.loadStates.contains(BasemapLoadState.ONLINE)) return
            pumpComposition()
        }
        throw AssertionError(
            "The Google basemap never reported ONLINE within " +
                "${BASEMAP_ONLINE_TIMEOUT_MILLIS}ms, so the canonical fog this case faults was " +
                "never even attempted. This is a BASEMAP failure, not a fog one: the Maps SDK " +
                "backs off process-wide after a network outage, so a sibling class that took the " +
                "device offline earlier in the same run can land here. ${observation.summary()}",
        )
    }

    /**
     * Waits for a canonical generation to be proven and installed with both covers down.
     *
     * Every case must reach this state before it injects anything; see the class KDoc. Failing
     * here means the SURFACE never became healthy, which is a different finding from anything the
     * cases assert, so the message says so rather than blaming the injected fault.
     */
    private fun awaitProvenGeneration(observation: SurfaceObservation): Long {
        val deadline = SystemClock.uptimeMillis() + FIRST_GENERATION_TIMEOUT_MILLIS
        var reading = noSurfaceReading()
        while (SystemClock.uptimeMillis() < deadline) {
            reading = readSurface(observation)
            val generation = reading.installedGeneration
            if (generation != null && !reading.composeCoverUp && !reading.synchronousCoverUp) {
                return generation
            }
            if (observation.sawTerminal) break
            pumpComposition()
        }
        throw AssertionError(
            "The surface never proved a canonical generation with the cover down within " +
                "${FIRST_GENERATION_TIMEOUT_MILLIS}ms, so nothing this case injects could reach " +
                "the retry path it asserts on. Without a proven generation every fog failure is " +
                "TERMINAL_FOR_COMPOSITION (FogOverlaySurfaceCoordinator.classifyFogInstallFailure) " +
                "and the map slot is replaced by MapProviderUnavailableSurface. " +
                "lastReading=$reading sawTerminal=${observation.sawTerminal} " +
                "${observation.summary()}",
        )
    }

    private fun awaitStatusDisplayed(badgeText: String, observation: SurfaceObservation) {
        try {
            composeRule.waitUntil(timeoutMillis = STATUS_TIMEOUT_MILLIS) {
                statusDisplayed(badgeText)
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(missingStatusDiagnostic(observation), timeout)
        }
    }

    /**
     * Names the exact condition the Google surface did not satisfy, so this failure is read as a
     * product gap rather than as a flaky wait.
     */
    private fun missingStatusDiagnostic(observation: SurfaceObservation): String =
        "The fog-unavailable status was never displayed. On this surface it is composed only " +
            "while `fogState.retryScheduled == true && !fogCoverUp` " +
            "(GoogleHostedMapSurface), and `retryScheduled` is set only by " +
            "FogOverlaySurfaceCoordinator's RETRY_BEHIND_PLACEHOLDERS branch, which requires " +
            "an already proven generation. This case proves one before it injects anything, so a " +
            "failure here means either the injected fault never landed, or it landed while the " +
            "cover was up, or the proven generation was lost first. " +
            "Observed retryScheduled-with-cover-down: ${observation.sawStatusCondition}; " +
            "observed terminal: ${observation.sawTerminal}. " +
            observation.summary()

    private fun requireInstalledGeneration(observation: SurfaceObservation): Long {
        val reading = readSurface(observation)
        return reading.installedGeneration ?: throw AssertionError(
            "The fog-unavailable status was displayed with no canonical generation installed, " +
                "which this surface should not be able to do: the status is composed only while " +
                "`retryScheduled && !coverUp`, and `retryScheduled` is set only on the " +
                "RETRY_BEHIND_PLACEHOLDERS arm, which requires a proven generation. " +
                "reading=$reading ${observation.summary()}",
        )
    }

    private fun renderedCenter(observation: SurfaceObservation): GeoPoint =
        observation.renderedCenter.get() ?: throw AssertionError(
            "The surface installed a canonical generation but never reported a rendered " +
                "viewport, so this case has nowhere to put its canonical point. " +
                observation.summary(),
        )

    private fun awaitFeedResubscribed(
        feed: SwitchableChangeFeed,
        subscriptionsBefore: Int,
        observation: SurfaceObservation,
    ) {
        val deadline = SystemClock.uptimeMillis() + FEED_RESUBSCRIBE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (feed.subscriptionCount > subscriptionsBefore) return
            pumpComposition()
        }
        throw AssertionError(
            "The healed change feed was never collected again within " +
                "${FEED_RESUBSCRIBE_TIMEOUT_MILLIS}ms, so the point this case is about to append " +
                "would be absorbed by the next bootstrap cursor and produce no revision at all. " +
                "subscriptions=${feed.subscriptionCount} refusals=${feed.refusalCount} " +
                observation.summary(),
        )
    }

    private fun awaitStatusClearedWithANewGeneration(
        badgeText: String,
        observation: SurfaceObservation,
        previousGeneration: Long,
    ): Long {
        val deadline = SystemClock.uptimeMillis() + CANONICAL_INSTALL_TIMEOUT_MILLIS
        var reading = noSurfaceReading()
        var statusShown = true
        while (SystemClock.uptimeMillis() < deadline) {
            reading = readSurface(observation)
            statusShown = statusDisplayed(badgeText)
            val generation = reading.installedGeneration
            if (
                !statusShown &&
                generation != null &&
                generation > previousGeneration &&
                !reading.composeCoverUp &&
                !reading.synchronousCoverUp
            ) {
                return generation
            }
            pumpComposition()
        }
        throw AssertionError(
            "The fog-unavailable status never cleared onto a NEWER canonical generation within " +
                "${CANONICAL_INSTALL_TIMEOUT_MILLIS}ms of the change feed healing. A cleared " +
                "status alone would not prove recovery - the flag behind it is cleared on every " +
                "retry tick - so this waits for an installed generation past the one that stood " +
                "while the feed was broken. statusShown=$statusShown " +
                "previousGeneration=$previousGeneration reading=$reading ${observation.summary()}",
        )
    }

    private fun assertStatusStaysCleared(
        badgeText: String,
        observation: SurfaceObservation,
        healedGeneration: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + HEALED_SETTLE_MILLIS
        var samples = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val reading = readSurface(observation)
            samples += 1
            // Built only on the failing path: every one of these diagnostics costs a main-thread
            // round trip, and this loop runs beside the retry cadence it is measuring.
            if (statusDisplayed(badgeText)) {
                throw AssertionError(
                    "The fog-unavailable status came back after the change feed healed and a " +
                        "newer generation installed. reading=$reading ${observation.summary()}",
                )
            }
            if (terminalSurfaceDisplayed()) {
                throw AssertionError(
                    "The surface went terminal after the change feed healed. " +
                        "reading=$reading ${observation.summary()}",
                )
            }
            val generation = reading.installedGeneration
            if (generation == null || generation < healedGeneration) {
                throw AssertionError(
                    "The healed surface stopped presenting the canonical generation it installed " +
                        "(healed=$healedGeneration). reading=$reading ${observation.summary()}",
                )
            }
            SystemClock.sleep(SETTLE_SAMPLE_MILLIS)
        }
        assertTrue(
            "The healed settle window produced only $samples sample(s), so it proved nothing " +
                "about the status staying cleared. ${observation.summary()}",
            samples >= MINIMUM_HEALED_SAMPLES,
        )
    }

    /**
     * Samples the surface across a window spanning several retry cadences.
     *
     * Deliberately a SAMPLER rather than a per-iteration assertion: the Google status is not
     * latched (class KDoc), so an assertion that it is displayed at an arbitrary instant is a claim
     * about the product this surface does not make. What the window collects instead is enough to
     * fail every way the contract can actually be broken - a status that never returns, a lost
     * generation, bare ground, or a terminal surface.
     */
    private fun observeRetryWindow(
        badgeText: String,
        observation: SurfaceObservation,
        provenGeneration: Long,
    ): RetryWindow {
        val window = RetryWindow(provenGeneration)
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + FOG_STATUS_SETTLE_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val reading = readSurface(observation)
            window.record(
                atMillis = SystemClock.uptimeMillis() - startedAt,
                reading = reading,
                statusShown = statusDisplayed(badgeText),
                terminalShown = terminalSurfaceDisplayed(),
            )
            // Sleeping matters: three semantics queries and a main-thread round trip per iteration,
            // run without a pause, contend with the one-second retry cadence being measured.
            SystemClock.sleep(SETTLE_SAMPLE_MILLIS)
        }
        return window
    }

    private fun assertRetryWindow(window: RetryWindow, observation: SurfaceObservation) {
        val diagnostic = "${window.summary()} ${observation.summary()}"
        assertTrue(
            "The retry window was barely sampled (${window.samples} samples), so it proved " +
                "nothing. $diagnostic",
            window.samples >= MINIMUM_WINDOW_SAMPLES,
        )
        assertTrue(
            "Unexplored ground was presented as revealed while fog was unavailable: " +
                "${window.bareSamples} sample(s) had no canonical generation installed and " +
                "neither the Compose cover nor the synchronous ViewOverlay cover raised. " +
                diagnostic,
            window.bareSamples == 0,
        )
        assertTrue(
            "The proven canonical generation stopped being presented while fog was unavailable " +
                "(${window.missingGenerationSamples} sample(s) with none installed). A failure " +
                "with a proven generation must retain the published tiles and retry behind them " +
                "(FogOverlaySurfaceCoordinator, RETRY_BEHIND_PLACEHOLDERS). $diagnostic",
            window.missingGenerationSamples == 0,
        )
        assertTrue(
            "The installed canonical generation regressed below the one proven before the fault " +
                "in ${window.regressedGenerationSamples} sample(s). $diagnostic",
            window.regressedGenerationSamples == 0,
        )
        assertTrue(
            "The fog-unavailable status was displayed over ground with no proven generation in " +
                "${window.statusWithoutGenerationSamples} sample(s). $diagnostic",
            window.statusWithoutGenerationSamples == 0,
        )
        assertTrue(
            "The terminal provider-unavailable surface was composed in " +
                "${window.terminalSamples} sample(s). A fog failure that lands while a proven " +
                "generation stands is never terminal. $diagnostic",
            window.terminalSamples == 0,
        )
        assertFalse(
            "The binding published a terminal fog state while a proven generation stood. " +
                diagnostic,
            observation.sawTerminal,
        )
        assertTrue(
            "The user was told fog was unavailable in only ${window.statusSamples} sample(s) of " +
                "${window.samples}. $diagnostic",
            window.statusSamples >= MINIMUM_STATUS_SAMPLES,
        )
        assertTrue(
            "The fog-unavailable status was never displayed again after a full retry cadence: " +
                "first seen at ${window.firstStatusAtMillis}ms and last at " +
                "${window.lastStatusAtMillis}ms into the window, which is less than the " +
                "${RETRY_CADENCE_MILLIS}ms cadence apart. A status shown once and then latched " +
                "off fails here. $diagnostic",
            window.lastStatusAtMillis - window.firstStatusAtMillis >= RETRY_CADENCE_MILLIS,
        )
    }

    /**
     * Whether the badge is on screen with a real size, rather than merely present in semantics.
     *
     * This is the boolean form of `assertIsDisplayed`, needed because the Google status cannot be
     * asserted at an arbitrary instant the way the MapLibre twin asserts its latched one.
     */
    private fun statusDisplayed(badgeText: String): Boolean =
        composeRule.onAllNodesWithText(badgeText).fetchSemanticsNodes().any { node ->
            node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
        }

    private fun terminalSurfaceDisplayed(): Boolean =
        composeRule
            .onAllNodesWithTag(MapSurfaceTestTags.ProviderUnavailable)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /**
     * One main-thread read of everything the surface publishes about fog, in one round trip.
     *
     * BOTH covers are read. `map_fog_cover_up` is written from Compose state and therefore lags;
     * `map_fog_synchronous_cover_up` is written by `GoogleFogSafetyOverlay.setVisible` inside the
     * callback that raises the real ViewOverlay guard. A "ground is bare" judgement that trusts
     * only the lagging one can accuse a healthy product.
     */
    private fun readSurface(observation: SurfaceObservation): SurfaceReading {
        val mapView = observation.mapView.get() ?: return noSurfaceReading()
        val reading = AtomicReference(noSurfaceReading())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            reading.set(
                SurfaceReading(
                    mapViewPresent = true,
                    attached = mapView.isAttachedToWindow,
                    composeCoverUp = mapView.getTag(R.id.map_fog_cover_up) == true,
                    synchronousCoverUp =
                        mapView.getTag(R.id.map_fog_synchronous_cover_up) == true,
                    installedGeneration =
                        (mapView.getTag(R.id.map_fog_canonical_generation) as? String)
                            ?.toLongOrNull(),
                    basemap = mapView.getTag(R.id.map_basemap_load_state) as? String,
                ),
            )
        }
        return reading.get()
    }

    private fun noSurfaceReading(): SurfaceReading = SurfaceReading(
        mapViewPresent = false,
        attached = false,
        composeCoverUp = false,
        synchronousCoverUp = false,
        installedGeneration = null,
        basemap = null,
    )

    /** One instant of the hosted surface, as booleans, names and generation ids only. */
    private data class SurfaceReading(
        val mapViewPresent: Boolean,
        val attached: Boolean,
        val composeCoverUp: Boolean,
        val synchronousCoverUp: Boolean,
        val installedGeneration: Long?,
        val basemap: String?,
    )

    /** What one sampling of the retry window saw. Counts, booleans and generation ids only. */
    private class RetryWindow(private val provenGeneration: Long) {
        var samples: Int = 0
        var statusSamples: Int = 0
        var firstStatusAtMillis: Long = -1L
        var lastStatusAtMillis: Long = -1L
        var bareSamples: Int = 0
        var terminalSamples: Int = 0
        var statusWithoutGenerationSamples: Int = 0
        var regressedGenerationSamples: Int = 0
        var missingGenerationSamples: Int = 0
        var highestGeneration: Long = provenGeneration

        fun record(
            atMillis: Long,
            reading: SurfaceReading,
            statusShown: Boolean,
            terminalShown: Boolean,
        ) {
            samples += 1
            if (statusShown) {
                statusSamples += 1
                if (firstStatusAtMillis < 0L) firstStatusAtMillis = atMillis
                lastStatusAtMillis = atMillis
                if (reading.installedGeneration == null) statusWithoutGenerationSamples += 1
            }
            if (terminalShown) terminalSamples += 1
            val covered = reading.composeCoverUp || reading.synchronousCoverUp
            val generation = reading.installedGeneration
            if (generation == null) {
                missingGenerationSamples += 1
                if (!covered) bareSamples += 1
            } else {
                if (generation < provenGeneration) regressedGenerationSamples += 1
                if (generation > highestGeneration) highestGeneration = generation
            }
        }

        fun summary(): String = "window[samples=$samples status=$statusSamples " +
            "firstStatusMs=$firstStatusAtMillis lastStatusMs=$lastStatusAtMillis " +
            "bare=$bareSamples terminal=$terminalSamples " +
            "statusWithoutGeneration=$statusWithoutGenerationSamples " +
            "regressed=$regressedGenerationSamples missing=$missingGenerationSamples " +
            "proven=$provenGeneration highest=$highestGeneration]"
    }

    /** Everything the surface published, as names, booleans and ids only. */
    private class SurfaceObservation {
        val mapView = AtomicReference<MapView?>(null)
        val renderedCenter = AtomicReference<GeoPoint?>(null)
        val fogFailures = CopyOnWriteArrayList<String>()
        val loadStates = CopyOnWriteArrayList<BasemapLoadState>()
        val renderCount = AtomicInteger(0)

        @Volatile var sawStatusCondition = false
            private set

        @Volatile var sawTerminal = false
            private set

        private val fogStateSummaries = CopyOnWriteArrayList<String>()

        fun recordLoadState(state: BasemapLoadState) {
            // The surface republishes the load state on every recomposition; only transitions
            // carry information.
            if (loadStates.lastOrNull() != state) loadStates += state
        }

        fun recordFogState(state: GoogleCanonicalFogState) {
            if (state.retryScheduled && !state.coverUp) sawStatusCondition = true
            if (state.terminal) sawTerminal = true
            val summary = "cover=${state.coverUp}/${state.coverReason} " +
                "installed=${state.installedGeneration} pending=${state.pendingGeneration} " +
                "retry=${state.retryScheduled} terminal=${state.terminal}"
            if (fogStateSummaries.lastOrNull() != summary) fogStateSummaries += summary
        }

        fun summary(): String {
            val view = mapView.get()
            val tags = AtomicReference("no map view")
            if (view != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    tags.set(
                        "attached=${view.isAttachedToWindow} " +
                            "coverUp=${view.getTag(R.id.map_fog_cover_up)} " +
                            "syncCoverUp=${view.getTag(R.id.map_fog_synchronous_cover_up)} " +
                            "generation=${view.getTag(R.id.map_fog_canonical_generation)} " +
                            "basemap=${view.getTag(R.id.map_basemap_load_state)} " +
                            "binding=${view.getTag(R.id.map_fog_binding_state)} " +
                            "phase=${view.getTag(R.id.map_fog_phase)} " +
                            "gates=${view.getTag(R.id.map_fog_binding_gates)} " +
                            "lastFailure=${view.getTag(R.id.map_fog_last_failure)}",
                    )
                }
            }
            return "renders=${renderCount.get()} failures=$fogFailures " +
                "loadStates=$loadStates fogStates=$fogStateSummaries tags=[${tags.get()}]"
        }
    }

    private companion object {
        /**
         * The Google surface has no local basemap to fall back to, so the load deadline must be
         * the harness one (`GoogleMapSurfaceTestActivity` uses the same value). A short deadline
         * here would latch `MAP_LOAD_TIMEOUT` and destroy the surface before any fog assertion.
         */
        const val BASEMAP_LOAD_TIMEOUT_MILLIS = 30_000L
        const val BASEMAP_ONLINE_TIMEOUT_MILLIS = 30_000L
        const val FOG_FAILURE_TIMEOUT_MILLIS = 30_000L
        const val CANONICAL_INSTALL_TIMEOUT_MILLIS = 30_000L

        /**
         * The first proof on a cold hosted map: the SDK has to return a map, load an online
         * basemap, render a generation, drain its delivery barrier and pass a snapshot proof. The
         * surface's own `fogCoverTimeoutMillisForTesting` (20 s by default) terminates it well
         * before this, so a longer wait here only buys a legible diagnostic.
         */
        const val FIRST_GENERATION_TIMEOUT_MILLIS = 45_000L

        /** Matches the MapLibre cases' wait for the same status to appear. */
        const val STATUS_TIMEOUT_MILLIS = 15_000L

        /** One restart of a failed synchronization plus its collector subscription. */
        const val FEED_RESUBSCRIBE_TIMEOUT_MILLIS = 15_000L

        /**
         * Longer than the MapLibre suite's 2 s equivalent, and longer than this class's own first
         * attempt, because the Google cadences this window has to span are 1 s
         * (`GoogleCanonicalFogSurfaceBinding.RETRY_FOG_MILLIS`) and 1 s
         * (`SYNCHRONIZATION_RETRY_MILLIS`): the status is not latched, so only a window covering
         * several ticks can show it returning rather than blinking once.
         */
        const val FOG_STATUS_SETTLE_MILLIS = 5_000L

        /** Long enough to cover several retry ticks after the feed healed. */
        const val HEALED_SETTLE_MILLIS = 2_000L

        /** The production retry cadence both failure paths re-arm at. */
        const val RETRY_CADENCE_MILLIS = 1_000L

        /** Three refusals is three synchronization restarts: the window really spanned them. */
        const val MINIMUM_FEED_REFUSALS = 3

        /** Three refused complete-mask renders is three rebuild retries that reached the fault. */
        const val MINIMUM_RENDER_REFUSALS = 3

        /** Anti-vacuity floors for the sampled windows themselves. */
        const val MINIMUM_WINDOW_SAMPLES = 20
        const val MINIMUM_HEALED_SAMPLES = 8
        const val MINIMUM_STATUS_SAMPLES = 5

        const val POLL_MILLIS = 50L
        const val SETTLE_SAMPLE_MILLIS = 100L

        /** Canonical point fixture: a fixed clock, and a step small enough to stay in viewport. */
        const val FIRST_POINT_TIMESTAMP_MILLIS = 1_000L
        const val POINT_INTERVAL_MILLIS = 5_000L
        const val POINT_LONGITUDE_STEP = 0.00001
        const val POINT_ACCURACY_METERS = 5.0
        const val POINT_DISTANCE_DELTA_METERS = 1.0
        const val MINIMUM_LONGITUDE = -180.0
        const val MAXIMUM_LONGITUDE = 180.0
    }
}
