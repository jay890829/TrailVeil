package app.trailveil.map

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.db.RecordingSessionEntity
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrackSegmentEntity
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedPointRevision
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.data.map.PersistedTrackPointChangeFeed
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.map.fog.FogMemoryTileCache
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.FogViewportRender
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FogRevealLatencyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun persistedPointToInstalledFogP95StaysWithinTwoSeconds() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()
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
            composeRule.setContent {
                TrailVeilMapSurface(
                    modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration(
                        providerName = "latency-local-fallback",
                        styleUri = "https://tiles.invalid/styles/latency",
                    ),
                    fallbackTimeoutMillis = 100L,
                    fogRuntime = runtime,
                    fogRequired = true,
                    onFogRendered = { viewport ->
                        rendered.offer(
                            TimedRender(
                                elapsedNanos = SystemClock.elapsedRealtimeNanos(),
                                viewport = viewport,
                            ),
                        )
                    },
                    onFogFailure = failures::offer,
                )
            }

            composeRule.waitUntil(timeoutMillis = 10_000L) {
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
                        createdAppVersion = "fog-latency-test",
                    ),
                    initialSegment = TrackSegmentEntity(
                        sessionId = 0,
                        sequence = 0,
                        startedAt = 1_000L,
                        startReason = "SESSION_START",
                    ),
                )
            }
            composeRule.waitUntil(timeoutMillis = 2_000L) {
                synchronized(observedFeed.starts) { observedFeed.starts.isNotEmpty() }
            }
            rendered.clear()

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
                    composeRule.waitUntil(timeoutMillis = 2_000L) {
                        rendered.isNotEmpty() || failures.isNotEmpty()
                    }
                    val completed = rendered.poll() ?: error(
                            "Fog was not installed after persisted point $index; " +
                                "failure=${failures.poll()?.stackTraceToString()}, " +
                                "latest=${observedFeed.latest}, " +
                                "starts=${observedFeed.starts}, " +
                                "completions=${observedFeed.completions}, " +
                                "revisions=${observedFeed.revisions}, " +
                                "reads=${observedFeed.reads}",
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
                        "TrailVeil fully-rendered fog latency: " +
                            "p95=${p95Millis}ms, max=${samplesMillis.last()}ms\n",
                    )
                },
            )

            assertTrue(
                "Persisted-point-to-fog p95 was ${p95Millis}ms: $samplesMillis",
                p95Millis <= 2_000L,
            )
        } finally {
            database.close()
        }
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
        ): List<PersistedTrackPointChange> =
            delegate.readChangesAfter(cursor).also { changes ->
                synchronized(reads) { reads += changes }
            }
    }

    private companion object {
        const val SAMPLE_COUNT = 20
    }
}
