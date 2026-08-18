package app.trailveil.recording

import android.content.Context
import android.location.LocationManager
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.location.LocationEngine
import app.trailveil.data.location.PlatformLocationEngine
import app.trailveil.data.history.RecordingHistoryDataSource
import app.trailveil.data.history.RoomRecordingHistoryDataSource
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.data.recording.ReconcileStartingResult
import app.trailveil.data.recording.RecordingRepository
import app.trailveil.data.recording.LocationOperationSequence
import app.trailveil.data.recording.RoomRecordingStore
import app.trailveil.map.fog.FogDiskTileCache
import app.trailveil.map.fog.FogMemoryTileCache
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/** Process-scoped production wiring. Constructing it performs no recording action. */
internal class AppContainer(context: Context) : RecordingRuntimeDependencies {
    private val applicationContext = context.applicationContext
    private val createdAppVersion = applicationContext.packageManager.getPackageInfo(
        applicationContext.packageName,
        android.content.pm.PackageManager.PackageInfoFlags.of(0),
    ).versionName ?: "unknown"
    private val database = TrailVeilDatabase.open(context.applicationContext)

    override val recordingRepository: RecordingRepository = RecordingRepository(
        RoomRecordingStore(database.recordingDao()),
    )
    val recordingHistory: RecordingHistoryDataSource =
        RoomRecordingHistoryDataSource(database.recordingDao())

    /** Ownership token of this process, so the screen can tell a live ACTIVE row from an orphaned one. */
    val recordingRuntimeToken: String get() = recordingRepository.runtimeToken

    /**
     * Which abandoned exploration this process has already offered to re-arm.
     *
     * Process-scoped on purpose, and here rather than in the screen because a `remember` in a
     * navigation destination is reset by a history round trip or an activity recreation - which
     * would silently turn "one attempt" into one per return. A fresh process is a fresh chance; a
     * blocked attempt inside one process must not keep retrying.
     */
    private val resumeAttempted = AtomicLong(0L)

    fun claimAbandonedResumeAttempt(sessionId: Long): Boolean =
        sessionId > 0L && resumeAttempted.getAndSet(sessionId) != sessionId

    private val platformLocationEngine: LocationEngine = PlatformLocationEngine(
        requireNotNull(context.applicationContext.getSystemService(LocationManager::class.java)),
    )
    @Volatile
    private var locationEngineOverrideForTesting: LocationEngine? = null
    override val locationEngine: LocationEngine
        get() = locationEngineOverrideForTesting ?: platformLocationEngine
    override val clock: RecordingServiceClock = SystemRecordingServiceClock
    override val operationIds: RecordingOperationIdFactory = UuidRecordingOperationIdFactory
    override val recordingServiceState = RecordingServiceState()

    private val recordingStartupReconciler = RecordingStartupReconciler {
        val operationId = operationIds.next("app-startup-reconcile")
        val reconciledAt = clock.epochMillis()
        RecordingPersistenceRetrier(
            attempt = {
                recordingRepository.reconcileStarting(operationId, reconciledAt)
            },
            retryDelay = { delay(STARTUP_RECONCILIATION_RETRY_MILLIS) },
        ).runUntilResolved()
    }

    @Volatile
    private var createdFogRuntime: FogRuntime? = null

    fun recordingController(activityContext: Context): RecordingController = RecordingController(
        preflight = AndroidRecordingStartPreflight(activityContext),
        commands = RepositoryRecordingStartCommands(recordingRepository),
        launcher = AndroidRecordingServiceLauncher(activityContext),
        createdAppVersion = createdAppVersion,
    )

    /** Must resolve before an app-visible Start can be issued in this process. */
    suspend fun reconcileRecordingStartup(): ReconcileStartingResult =
        recordingStartupReconciler.reconcileOnce()

    /** Process-local service seam; production never installs an override. */
    internal fun setLocationEngineOverrideForTesting(engine: LocationEngine?) {
        locationEngineOverrideForTesting = engine
    }

    /** Dedicated-process benchmark cleanup; the container must not be reused after this call. */
    internal fun closeDatabaseForTesting() {
        database.close()
    }

    /** Same-process integration-test fixture access; production callers never mutate Room directly. */
    internal fun databaseForTesting(): TrailVeilDatabase = database

    /**
     * Call from a background dispatcher: disk-cache discovery and trimming perform file I/O.
     * Failure to create the derived disk cache degrades to the bounded in-memory cache.
     */
    fun fogRuntime(): FogRuntime =
        createdFogRuntime ?: synchronized(this) {
            createdFogRuntime ?: createFogRuntime().also { createdFogRuntime = it }
        }

    private fun createFogRuntime(): FogRuntime {
        val dao = database.recordingDao()
        val style = FogRenderStyle()
        val memoryCache = FogMemoryTileCache(maxBytes = FOG_MEMORY_CACHE_BYTES)
        val diskCache = runCatching {
            FogDiskTileCache(
                rootDirectory = File(applicationContext.cacheDir, "fog-tiles"),
                maxBytes = FOG_DISK_CACHE_BYTES,
            )
        }.getOrNull()
        val pipeline = FogTilePipeline(
            memoryCache = memoryCache,
            diskCache = diskCache,
            renderMask = FogTileRenderer(style)::render,
        )
        return FogRuntime(
            viewportCoordinator = FogViewportCoordinator(
                trackDataSource = ViewportTrackDataSource(RoomViewportTrackPointReader(dao)),
                pipeline = pipeline,
                style = style,
            ),
            pointChanges = RoomPersistedTrackPointChangeFeed(dao),
        )
    }

    private companion object {
        const val FOG_MEMORY_CACHE_BYTES = 16L * 1024L * 1024L
        const val FOG_DISK_CACHE_BYTES = 64L * 1024L * 1024L
        const val STARTUP_RECONCILIATION_RETRY_MILLIS = 1_000L
    }
}

/** Narrow seam for service tests; production dependencies are supplied by [AppContainer]. */
internal interface RecordingRuntimeDependencies {
    val recordingRepository: RecordingRepository
    val locationEngine: LocationEngine
    val clock: RecordingServiceClock
    val operationIds: RecordingOperationIdFactory
    val recordingServiceState: RecordingServiceState
}

internal interface RecordingServiceClock {
    fun epochMillis(): Long
    fun elapsedRealtimeNanos(): Long
}

internal object SystemRecordingServiceClock : RecordingServiceClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}

internal fun interface RecordingOperationIdFactory {
    fun next(prefix: String): RecordingOperationId
}

internal object UuidRecordingOperationIdFactory : RecordingOperationIdFactory {
    private val locationRuntimeToken = UUID.randomUUID().toString()
    private val locationSequence = AtomicLong(0L)

    override fun next(prefix: String): RecordingOperationId = if (prefix == "location") {
        val sequence = locationSequence.incrementAndGet()
        check(sequence > 0L) { "location operation sequence exhausted" }
        LocationOperationSequence(locationRuntimeToken, sequence).toOperationId()
    } else {
        RecordingOperationId("$prefix-${UUID.randomUUID()}")
    }
}
