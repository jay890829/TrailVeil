package app.trailveil.map

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedPointRevision
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.data.map.PersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.map.fog.FogMemoryTileCache
import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogTileKey
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.TrackSegment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking

/*
 * The canonical-fog fixtures the Google suite needs, re-hosted from the MapLibre suite.
 *
 * `MapSurfaceTest` keeps `inMemoryDatabase`, `fogRuntime` and its failure fixtures as private
 * helpers, so nothing outside `androidTestDebug` can reach them. They are provider-neutral by
 * construction - Room, the fog pipeline and the change-feed interface are all in `main` - so they
 * are copied here rather than shared through a common test source set, which would put a
 * MapLibre-suite file on the Google compile path.
 *
 * Deliberately no MapLibre import, and deliberately no default arguments: a Google test that wants
 * a healthy feed must name it, because "which feed" is the whole subject of
 * GoogleFogFailureStatusTest.
 *
 * The failure fixtures are SWITCHABLE rather than born broken, and that is a Google fact, not a
 * preference. `FogOverlaySurfaceCoordinator.classifyFogInstallFailure` returns
 * TERMINAL_FOR_COMPOSITION whenever NOTHING has been proven, so a fixture that refuses from its
 * first call terminates the whole composition - `TrailVeilMapSurface` swaps the map slot for
 * `MapProviderUnavailableSurface` - and the retry state the fog-unavailable status is composed from
 * is never reached. Only a failure landing on a surface that has already proven a generation takes
 * the RETRY_BEHIND_PLACEHOLDERS arm. The MapLibre originals can be born broken because that surface
 * latches its own `fogSyncFailed`/`fogRenderFailed` flags independently of any proof.
 */

/** Byte bound of the derived-mask cache, matching the MapLibre suite's surface fixtures. */
private const val FIXTURE_MEMORY_CACHE_BYTES = 8L * 1024L * 1024L

internal fun inMemoryDatabase(): TrailVeilDatabase =
    Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TrailVeilDatabase::class.java,
    )
        .allowMainThreadQueries()
        .addCallback(TrailVeilDatabase.invariantCallback)
        .build()

/** The ordinary healthy runtime: real Room reads, real masks, whatever feed the caller supplies. */
internal fun fogRuntime(
    database: TrailVeilDatabase,
    pointChanges: PersistedTrackPointChangeFeed,
): FogRuntime {
    val style = FogRenderStyle()
    return fogRuntimeWith(
        database = database,
        pointChanges = pointChanges,
        style = style,
        renderMask = FogTileRenderer(style)::render,
    )
}

/**
 * Drops every derived fog mask so the next generation has to render each tile again.
 *
 * `FogTilePipeline.load` and `FogTilePipeline.mergeReveal` both serve a cache hit without calling
 * the renderer at all, and `FogViewportCoordinator.renderTilesLocked` asks `loadCached` first, so a
 * warm cache is enough to carry a whole rebuild past an armed [SwitchableRenderFogRuntime] fault.
 * A case that arms that fault therefore calls this before the canonical change that triggers the
 * next generation.
 *
 * This does not disturb what the surface is presenting: the published masks of an installed
 * generation are held by `FogTileProviderAdapter` and the binding's own `masksByGeneration`, not by
 * this cache, so the proven generation's tile overlay keeps serving while the cache is empty.
 */
internal fun clearDerivedFogCache(runtime: FogRuntime) = runBlocking {
    runtime.viewportCoordinator.clearDerivedCache()
}

/**
 * A canonical fog runtime whose tile rendering can be switched to throwing at a chosen moment.
 *
 * The MapLibre original is an inline `renderMask` inside
 * `MapSurfaceTest#fogFailureStatusOutranksTheLocalBasemapFallback` that throws from the first tile.
 * That shape cannot be reused here: see the file comment above - a cold Google surface with nothing
 * proven goes terminal instead of retrying. The fault is therefore armed by [failFromNow] once the
 * hosting case has watched a generation install with the cover down, after which it surfaces
 * through `GoogleCanonicalFogSurfaceBinding.failGeneration` exactly as a real renderer failure
 * would.
 */
internal class SwitchableRenderFogRuntime(
    database: TrailVeilDatabase,
    pointChanges: PersistedTrackPointChangeFeed,
) {
    private val style = FogRenderStyle()
    private val renderer = FogTileRenderer(style)
    private val failing = AtomicBoolean(false)
    private val refusals = AtomicInteger(0)

    /** Complete-mask renders refused since [failFromNow]; a case's proof the fault was reached. */
    val renderRefusalCount: Int get() = refusals.get()

    val runtime: FogRuntime = fogRuntimeWith(
        database = database,
        pointChanges = pointChanges,
        style = style,
        renderMask = ::render,
    )

    fun failFromNow() {
        failing.set(true)
    }

    private fun render(key: FogTileKey, segments: List<TrackSegment>): FogPixelMask {
        if (failing.get()) {
            refusals.incrementAndGet()
            error("Fog tile rendering is unavailable")
        }
        return renderer.render(key, segments)
    }
}

private fun fogRuntimeWith(
    database: TrailVeilDatabase,
    pointChanges: PersistedTrackPointChangeFeed,
    style: FogRenderStyle,
    renderMask: (FogTileKey, List<TrackSegment>) -> FogPixelMask,
): FogRuntime {
    val dao = database.recordingDao()
    return FogRuntime(
        viewportCoordinator = FogViewportCoordinator(
            trackDataSource = ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
            pipeline = FogTilePipeline(
                memoryCache = FogMemoryTileCache(FIXTURE_MEMORY_CACHE_BYTES),
                diskCache = null,
                renderMask = renderMask,
            ),
            style = style,
        ),
        pointChanges = pointChanges,
    )
}

/**
 * Serves [delegate] until [failFromNow], refuses everything until [restore], then serves again.
 *
 * The behaviour after [failFromNow] is the MapLibre `RecoverableChangeFeed`'s: every entry point
 * throws, so `FogChangeSynchronizer.synchronizeTo` cannot bootstrap, cannot read a page, and cannot
 * keep a revision collector alive. What differs is when that starts, and why - see the file comment.
 *
 * The refusal path is reachable from two directions once armed, and a case should know which one it
 * drove: a live revision collector refuses at [revisionsAfter]'s per-emission check or at
 * [readChangesAfter], and every restart the binding schedules afterwards refuses at
 * [latestCursor] while re-bootstrapping.
 */
internal class SwitchableChangeFeed(
    private val delegate: PersistedTrackPointChangeFeed,
) : PersistedTrackPointChangeFeed {
    private val failing = AtomicBoolean(false)
    private val refusals = AtomicInteger(0)
    private val subscriptions = AtomicInteger(0)

    /** How many callers have been refused, so a case can prove retries really happened. */
    val refusalCount: Int get() = refusals.get()

    /**
     * How many times the revision flow has been collected.
     *
     * `GoogleCanonicalFogSurfaceBinding.failSynchronization` restarts synchronization from scratch
     * after every failure, and a restart re-bootstraps its cursor from `latestCursor()`. A point
     * appended while no collector is running is therefore absorbed by that bootstrap and produces
     * no revision at all, so a case that heals this feed waits for this count to grow before it
     * appends the point whose merge it wants to observe.
     */
    val subscriptionCount: Int get() = subscriptions.get()

    fun failFromNow() {
        failing.set(true)
    }

    fun restore() {
        failing.set(false)
    }

    override suspend fun latestCursor(): PersistedPointCursor {
        requireAvailable()
        return delegate.latestCursor()
    }

    override fun revisionsAfter(cursor: PersistedPointCursor): Flow<PersistedPointRevision> =
        delegate.revisionsAfter(cursor)
            .onStart {
                requireAvailable()
                subscriptions.incrementAndGet()
            }
            .onEach { requireAvailable() }

    override suspend fun readChangesAfter(
        cursor: PersistedPointCursor,
        limit: Int,
    ): List<PersistedTrackPointChange> {
        requireAvailable()
        return delegate.readChangesAfter(cursor, limit)
    }

    private fun requireAvailable() {
        if (failing.get()) {
            refusals.incrementAndGet()
            error("Canonical change feed is unavailable")
        }
    }
}
