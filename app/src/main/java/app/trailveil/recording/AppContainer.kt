package app.trailveil.recording

import android.content.Context
import android.location.LocationManager
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.location.LocationEngine
import app.trailveil.data.location.PlatformLocationEngine
import app.trailveil.data.map.RoomPersistedTrackPointChangeFeed
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.data.recording.RecordingRepository
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
    override val locationEngine: LocationEngine = PlatformLocationEngine(
        requireNotNull(context.applicationContext.getSystemService(LocationManager::class.java)),
    )
    override val clock: RecordingServiceClock = SystemRecordingServiceClock
    override val operationIds: RecordingOperationIdFactory = UuidRecordingOperationIdFactory

    @Volatile
    private var createdFogRuntime: FogRuntime? = null

    fun recordingController(activityContext: Context): RecordingController = RecordingController(
        preflight = AndroidRecordingStartPreflight(activityContext),
        commands = RepositoryRecordingStartCommands(recordingRepository),
        launcher = AndroidRecordingServiceLauncher(activityContext),
        createdAppVersion = createdAppVersion,
    )

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
    }
}

/** Narrow seam for service tests; production dependencies are supplied by [AppContainer]. */
internal interface RecordingRuntimeDependencies {
    val recordingRepository: RecordingRepository
    val locationEngine: LocationEngine
    val clock: RecordingServiceClock
    val operationIds: RecordingOperationIdFactory
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
    override fun next(prefix: String): RecordingOperationId =
        RecordingOperationId("$prefix-${UUID.randomUUID()}")
}
