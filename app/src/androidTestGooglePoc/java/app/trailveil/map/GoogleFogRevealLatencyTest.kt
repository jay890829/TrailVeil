package app.trailveil.map

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.R
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedPointRevision
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.data.map.PersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.map.fog.FogMemoryTileCache
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.FogViewportRender
import app.trailveil.ui.theme.TrailVeilTheme
import com.google.android.gms.maps.MapView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Google case standing in for
 * `FogRevealLatencyTest#persistedPointToInstalledFogP95StaysWithinTwoSeconds`, with one recorded
 * endpoint delta. Not a twin, and the ledger must not record it as one.
 *
 * Same product criterion, same fixture and the same numbers: an in-memory Room, the same
 * `FogRuntime` construction, twenty samples, a per-sample wait equal to the budget, and a p95 of
 * two seconds from the moment a point is persisted to the moment fog covering it is delivered.
 *
 * **The delta.** What "rendered" means differs by provider, and the difference is not measured
 * away here. MapLibre reports through `onFogRendered` when the generation's geometry is installed
 * in the style; the Google binding reports through the same callback from
 * `publishFogRenderForCompatibility`, which runs once the generation's masks are published -
 * BEFORE the overlay handover, the delivery barrier and the snapshot proof that follow it. So the
 * timed endpoint is mask publication, not installed-and-proven, and the install suffix is not
 * inside any sample's own measurement. What bounds it instead is the shape of the loop: the
 * coordinator serialises rebuilds behind its pending slot, and sample N is persisted immediately
 * after sample N-1's mask publication, so nineteen of the twenty suffixes are bounded INDIRECTLY
 * by the next sample's [PER_SAMPLE_TIMEOUT_MILLIS] deadline. The twentieth has no successor and is
 * unmeasured. The closing assertion is what keeps the whole run from being a claim about masks
 * nobody ever saw: it requires the installed generation to have ADVANCED past the one standing
 * when the loop began, with the cover down - a stale proven generation from before the loop
 * cannot satisfy it - but it deliberately does not wait for the final sample's own install,
 * because that would measure a suffix this case does not claim to bound.
 *
 * The Google surface has no local basemap: nothing is rendered until the SDK reports a loaded
 * basemap, so this needs a configured key and a working network, and the deadlines around the
 * first render are the hosted ones rather than the MapLibre suite's local-style ones. The
 * measured budget itself is unchanged.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogRevealLatencyTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        GoogleMapSurfaceTestHooks.reset()
        // The harness activity composes its own surface in onCreate from these hooks, before this
        // test can install a runtime. A terminal startup decision keeps that first composition
        // from building a second MapView that would compete for the SDK renderer.
        GoogleMapSurfaceTestHooks.decision.set(
            ProviderStartupDecision(false, ProviderFallbackReason.MISSING_KEY),
        )
    }

    @After
    fun tearDown() = GoogleMapSurfaceTestHooks.reset()

    @Test
    fun persistedPointToInstalledFogP95StaysWithinTwoSeconds() {
        assumeTrue(
            "the hosted Google fog surface requires a configured Maps key",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        val database = inMemoryDatabase()
        try {
            val dao = database.recordingDao()
            val style = FogRenderStyle()
            val observedFeed = ObservedFeed(RoomPersistedTrackPointChangeFeed(dao))
            val runtime = FogRuntime(
                viewportCoordinator = FogViewportCoordinator(
                    trackDataSource = ViewportTrackDataSource(
                        RoomViewportTrackPointReader(dao),
                    ),
                    pipeline = FogTilePipeline(
                        memoryCache = FogMemoryTileCache(16L * 1024L * 1024L),
                        diskCache = null,
                        renderMask = FogTileRenderer(style)::render,
                    ),
                    style = style,
                ),
                pointChanges = observedFeed,
            )
            val rendered = LinkedBlockingQueue<TimedRender>()
            val failures = LinkedBlockingQueue<Throwable>()
            val mapViewRef = AtomicReference<MapView?>(null)

            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TrailVeilTheme {
                            TrailVeilMapSurface(
                                modifier = Modifier.fillMaxSize(),
                                fallbackTimeoutMillis = BASEMAP_LOAD_TIMEOUT_MILLIS,
                                savedStateKey = "trailveil.map.google-fog-latency-test",
                                fogRuntime = runtime,
                                fogRequired = true,
                                providerStartupDecisionForTesting =
                                    ProviderStartupDecision(true, null),
                                onFogRendered = { viewport ->
                                    rendered.offer(
                                        TimedRender(
                                            elapsedNanos = SystemClock.elapsedRealtimeNanos(),
                                            viewport = viewport,
                                        ),
                                    )
                                },
                                onFogFailure = failures::offer,
                                onMapViewCreatedForTesting = { view -> mapViewRef.set(view) },
                            )
                        }
                    }
                }

                composeRule.waitUntil(timeoutMillis = FIRST_RENDER_TIMEOUT_MILLIS) {
                    rendered.isNotEmpty() || failures.isNotEmpty()
                }
                val initial = checkNotNull(rendered.poll()) {
                    "Initial canonical fog did not render: ${failures.poll()?.stackTraceToString()}"
                }
                val center = initial.viewport.request.center
                val recording = runBlocking {
                    dao.startSession(
                        session = RecordingSessionEntity(
                            startedAt = 1_000L,
                            status = RecordingStatus.ACTIVE,
                            createdAppVersion = "google-fog-latency-test",
                        ),
                        initialSegment = TrackSegmentEntity(
                            sessionId = 0,
                            sequence = 0,
                            startedAt = 1_000L,
                            startReason = "SESSION_START",
                        ),
                    )
                }
                composeRule.waitUntil(timeoutMillis = FEED_START_TIMEOUT_MILLIS) {
                    synchronized(observedFeed.starts) { observedFeed.starts.isNotEmpty() }
                }
                rendered.clear()

                // The generation standing before any sample was persisted. The closing assertion
                // compares against this, so a proven generation that predates the loop entirely -
                // the one the initial render installed - can no longer discharge it.
                val generationAtLoopStart = readCanonicalGeneration(mapViewRef.get())

                val samplesMillis = buildList {
                    repeat(SAMPLE_COUNT) { index ->
                        rendered.clear()
                        val startedNanos = SystemClock.elapsedRealtimeNanos()
                        runBlocking {
                            dao.appendAcceptedPoint(
                                point = TrackPointEntity(
                                    sessionId = recording.sessionId,
                                    segmentId = recording.segmentId,
                                    sequence = index.toLong(),
                                    timestamp = 1_000L + index * 5_000L,
                                    latitude = center.latitude,
                                    longitude = (center.longitude + index * 0.00001)
                                        .coerceIn(-180.0, 180.0),
                                    horizontalAccuracy = 5.0,
                                ),
                                distanceDeltaMeters = 1.0,
                            )
                        }
                        // `waitUntil` THROWS on timeout instead of returning false, so a bare wait
                        // would report nothing but "Condition still not satisfied after 2000 ms".
                        // The MapLibre twin learned this the hard way; the same diagnostic shape is
                        // kept here, with the Google binding's own gates appended.
                        try {
                            composeRule.waitUntil(timeoutMillis = PER_SAMPLE_TIMEOUT_MILLIS) {
                                rendered.isNotEmpty() || failures.isNotEmpty()
                            }
                        } catch (timeout: ComposeTimeoutException) {
                            throw AssertionError(
                                stalledDiagnostic(
                                    index,
                                    this,
                                    observedFeed,
                                    failures,
                                    mapViewRef.get(),
                                ),
                                timeout,
                            )
                        }
                        val completed = rendered.poll()
                            ?: throw AssertionError(
                                stalledDiagnostic(
                                    index,
                                    this,
                                    observedFeed,
                                    failures,
                                    mapViewRef.get(),
                                ),
                            )
                        add(
                            TimeUnit.NANOSECONDS.toMillis(
                                completed.elapsedNanos - startedNanos,
                            ),
                        )
                    }
                }.sorted()
                val p95Index = ceil(samplesMillis.size * 0.95).toInt() - 1
                val p95Millis = samplesMillis[p95Index]
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "TrailVeil Google fully-rendered fog latency: " +
                                "p95=${p95Millis}ms, max=${samplesMillis.last()}ms\n",
                        )
                    },
                )

                assertTrue(
                    "Persisted-point-to-fog p95 was ${p95Millis}ms: $samplesMillis",
                    p95Millis <= P95_BUDGET_MILLIS,
                )
                // Anti-vacuity for the Google reading of "rendered": the callback above fires when
                // masks are published, so without this a generation that never reached the screen
                // would still have been timed. An installed generation with the cover lowered is
                // the binding's own proof that one did.
                assertInstalledGenerationAdvancedAndIsPresented(
                    mapViewRef.get(),
                    generationAtLoopStart,
                )
            }
        } finally {
            database.close()
        }
    }

    /** The installed generation this surface is currently presenting, or null if none is. */
    private fun readCanonicalGeneration(mapView: MapView?): Long? {
        val view = mapView ?: return null
        val generation = AtomicReference<Long?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            generation.set(
                (view.getTag(R.id.map_fog_canonical_generation) as? String)?.toLongOrNull(),
            )
        }
        return generation.get()
    }

    /**
     * Fails unless the surface ends the run presenting a generation installed DURING the sample
     * loop, with no safety cover.
     *
     * `map_fog_canonical_generation` is written from `installedGeneration`, which the coordinator
     * sets only in `completeInstall` - after the overlay attached, the delivery barrier drained
     * and the snapshot proof passed. Reading it at one instant is not enough on its own: the
     * generation the FIRST render installed, long before any sample was persisted, would satisfy
     * "installed and presented" while proving nothing about the twenty renders that were timed.
     * The comparison against [generationAtLoopStart] is what rules that out. It is not a wait on
     * the last sample's install - the run's generations are strictly increasing and any one of
     * them discharges this - only on there having been at least one.
     *
     * [ADVANCE_GRACE_MILLIS] absorbs the ordinary case where the final sample's masks published
     * microseconds ago and its install is still draining; it never lets a run with no advance at
     * all pass, because the wait ends in the same assertion either way.
     */
    private fun assertInstalledGenerationAdvancedAndIsPresented(
        mapView: MapView?,
        generationAtLoopStart: Long?,
    ) {
        val summary = AtomicReference("no map view was ever created")
        val presented = AtomicReference(false)
        val view = mapView
        if (view != null) {
            val deadline = SystemClock.elapsedRealtime() + ADVANCE_GRACE_MILLIS
            do {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val generation = view.getTag(R.id.map_fog_canonical_generation) as? String
                    val installed = generation?.toLongOrNull()
                    val coverUp = view.getTag(R.id.map_fog_cover_up) == true
                    presented.set(
                        installed != null &&
                            !coverUp &&
                            (generationAtLoopStart == null || installed > generationAtLoopStart),
                    )
                    summary.set(
                        "generation=$generation atLoopStart=$generationAtLoopStart " +
                            "coverUp=$coverUp " +
                            "basemap=${view.getTag(R.id.map_basemap_load_state)} " +
                            "phase=${view.getTag(R.id.map_fog_phase)} " +
                            "binding=${view.getTag(R.id.map_fog_binding_state)} " +
                            "lastFailure=${view.getTag(R.id.map_fog_last_failure)}",
                    )
                }
                if (presented.get()) break
                SystemClock.sleep(ADVANCE_POLL_MILLIS)
            } while (SystemClock.elapsedRealtime() < deadline)
        }
        assertTrue(
            "Fog renders were timed but no canonical generation installed during the run was " +
                "presented: " + summary.get(),
            presented.get(),
        )
    }

    /**
     * Everything known about a sample that never produced an installed fog render.
     *
     * Read under each list's own monitor: [ObservedFeed] is written from the feed's coroutines
     * while this runs, and a `ConcurrentModificationException` raised while building a failure
     * message would replace one uninformative failure with another.
     */
    private fun stalledDiagnostic(
        index: Int,
        measuredSoFar: List<Long>,
        observedFeed: ObservedFeed,
        failures: LinkedBlockingQueue<Throwable>,
        mapView: MapView?,
    ): String {
        val latest = synchronized(observedFeed.latest) { observedFeed.latest.toList() }
        val starts = synchronized(observedFeed.starts) { observedFeed.starts.toList() }
        val completions = synchronized(observedFeed.completions) { observedFeed.completions.toList() }
        val revisions = synchronized(observedFeed.revisions) { observedFeed.revisions.toList() }
        val reads = synchronized(observedFeed.reads) { observedFeed.reads.size }
        val tags = AtomicReference("no map view")
        if (mapView != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                tags.set(
                    "coverUp=${mapView.getTag(R.id.map_fog_cover_up)} " +
                        "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
                        "basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
                        "phase=${mapView.getTag(R.id.map_fog_phase)} " +
                        "gates=${mapView.getTag(R.id.map_fog_binding_gates)} " +
                        "lastFailure=${mapView.getTag(R.id.map_fog_last_failure)}",
                )
            }
        }
        return "Fog was not installed within ${PER_SAMPLE_TIMEOUT_MILLIS}ms of persisted point " +
            "$index of $SAMPLE_COUNT. Note that the per-sample wait equals the p95 budget " +
            "(${P95_BUDGET_MILLIS}ms), so a single sample that overruns arrives HERE rather than " +
            "as a p95 failure, whatever the other samples measured. " +
            "measuredSoFar=$measuredSoFar, " +
            "failure=${failures.poll()?.stackTraceToString()}, " +
            "latest=$latest, starts=$starts, completions=$completions, " +
            "revisions=$revisions, readBatches=$reads, binding=[${tags.get()}]"
    }

    private data class TimedRender(
        val elapsedNanos: Long,
        val viewport: FogViewportRender,
    )

    private class ObservedFeed(
        private val delegate: PersistedTrackPointChangeFeed,
    ) : PersistedTrackPointChangeFeed {
        val latest = mutableListOf<PersistedPointCursor>()
        val starts = mutableListOf<String>()
        val completions = mutableListOf<String>()
        val revisions = mutableListOf<PersistedPointRevision>()
        val reads = mutableListOf<List<PersistedTrackPointChange>>()

        override suspend fun latestCursor(): PersistedPointCursor =
            delegate.latestCursor().also { cursor -> synchronized(latest) { latest += cursor } }

        override fun revisionsAfter(cursor: PersistedPointCursor): Flow<PersistedPointRevision> =
            delegate.revisionsAfter(cursor)
                .onStart { synchronized(starts) { starts += "cursor=${cursor.pointId}" } }
                .onEach { revision ->
                    synchronized(revisions) { revisions += revision }
                }
                .onCompletion { failure ->
                    synchronized(completions) {
                        completions += failure?.javaClass?.name ?: "normal"
                    }
                }

        override suspend fun readChangesAfter(
            cursor: PersistedPointCursor,
            limit: Int,
        ): List<PersistedTrackPointChange> =
            delegate.readChangesAfter(cursor, limit).also { changes ->
                synchronized(reads) { reads += changes }
            }
    }

    private companion object {
        const val SAMPLE_COUNT = 20

        /** The product criterion this test is named for. */
        const val P95_BUDGET_MILLIS = 2_000L

        /**
         * Deliberately equal to [P95_BUDGET_MILLIS], which is what makes the p95 assertion
         * unfailable in practice: with [SAMPLE_COUNT] = 20 the p95 index is 18, so the statistic
         * tolerates one slow sample, but no sample can ever be RECORDED as slow because the wait
         * gives up at the same bound. Carried over unchanged from the MapLibre twin so the two
         * cases accept exactly the same behaviour.
         */
        const val PER_SAMPLE_TIMEOUT_MILLIS = 2_000L

        /**
         * Only the deadlines that bound the ENVIRONMENT differ from the MapLibre twin, and only
         * because this provider needs more of it: the MapLibre case renders against a bundled
         * local style, while nothing is rendered here until Play services returns a map and the
         * SDK reports a loaded online basemap. Thirty seconds is what every googlePoc case allows
         * for map readiness. No assertion's meaning depends on these values.
         */
        const val FIRST_RENDER_TIMEOUT_MILLIS = 30_000L
        const val BASEMAP_LOAD_TIMEOUT_MILLIS = 30_000L

        /** Unchanged from the MapLibre twin: the revision flow is subscribed before any render. */
        const val FEED_START_TIMEOUT_MILLIS = 2_000L

        /**
         * Grace for the closing advance check only. It bounds nothing the case reports: the
         * measured samples are already recorded when it starts, and it exists so a run whose last
         * install is still draining is not reported as a run that installed nothing.
         */
        const val ADVANCE_GRACE_MILLIS = 3_000L
        const val ADVANCE_POLL_MILLIS = 50L
    }
}
