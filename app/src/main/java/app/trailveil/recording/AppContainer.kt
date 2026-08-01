package app.trailveil.recording

import android.content.Context
import android.location.LocationManager
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.location.LocationEngine
import app.trailveil.data.location.PlatformLocationEngine
import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.data.recording.RecordingRepository
import app.trailveil.data.recording.RoomRecordingStore
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

    fun recordingController(activityContext: Context): RecordingController = RecordingController(
        preflight = AndroidRecordingStartPreflight(activityContext),
        commands = RepositoryRecordingStartCommands(recordingRepository),
        launcher = AndroidRecordingServiceLauncher(activityContext),
        createdAppVersion = createdAppVersion,
    )
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