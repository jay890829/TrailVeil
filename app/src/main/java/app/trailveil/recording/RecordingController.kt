package app.trailveil.recording

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.trailveil.data.recording.BeginStartResult
import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.data.recording.RecordingRepository
import app.trailveil.data.recording.StartDisposition
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Coordinates the only supported recording entry point: a deliberate action from a visible
 * activity. Permission prompts and Settings navigation belong to the UI layer in P3-002.
 */
internal class RecordingController(
    private val preflight: RecordingStartPreflight,
    private val commands: RecordingStartCommands,
    private val launcher: RecordingServiceLauncher,
    private val clock: RecordingControllerClock = SystemRecordingControllerClock,
    private val operationIds: RecordingControllerOperationIds = UuidRecordingControllerOperationIds,
    private val createdAppVersion: String,
) {
    suspend fun startFromVisibleActivity(activityVisible: Boolean): RecordingStartOutcome {
        preflight.blocker(activityVisible)?.let {
            return RecordingStartOutcome.Blocked(it)
        }

        val reservation = try {
            commands.beginStart(
                operationIds.next("begin-start"),
                clock.epochMillis(),
                createdAppVersion,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RecordingStartOutcome.PersistenceFailure(RecordingStartPersistencePhase.BEGIN_START)
        }

        return try {
            launcher.start(reservation.sessionId)
            RecordingStartOutcome.ServiceRequested(
                reservation.sessionId,
                reservation.disposition,
            )
        } catch (failure: RuntimeException) {
            val kind = failure.toRecordingStartFailureKind()
            if (reservation.disposition == StartDisposition.ALREADY_ACTIVE) {
                RecordingStartOutcome.LaunchFailure(
                    reservation.sessionId,
                    kind,
                    startFailurePersisted = false,
                )
            } else {
                val persisted = try {
                    commands.failStart(
                        operationIds.next("fail-start"),
                        reservation.sessionId,
                        clock.epochMillis(),
                        kind.name,
                    )
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                RecordingStartOutcome.LaunchFailure(
                    reservation.sessionId,
                    kind,
                    startFailurePersisted = persisted,
                )
            }
        }
    }
}

internal fun interface RecordingStartPreflight {
    fun blocker(activityVisible: Boolean): RecordingStartBlocker?
}

internal class AndroidRecordingStartPreflight(
    context: Context,
    private val locationManager: LocationManager =
        requireNotNull(context.getSystemService(LocationManager::class.java)),
) : RecordingStartPreflight {
    private val applicationContext = context.applicationContext

    override fun blocker(activityVisible: Boolean): RecordingStartBlocker? =
        evaluateRecordingStartPreflight(
            RecordingStartPreflightSnapshot(
                activityVisible = activityVisible,
                coarseLocationGranted = applicationContext.hasPermission(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                fineLocationGranted = applicationContext.hasPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                locationEnabled = locationManager.isLocationEnabled,
            ),
        )

    private fun Context.hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

internal data class RecordingStartPreflightSnapshot(
    val activityVisible: Boolean,
    val coarseLocationGranted: Boolean,
    val fineLocationGranted: Boolean,
    val locationEnabled: Boolean,
)

internal fun evaluateRecordingStartPreflight(
    snapshot: RecordingStartPreflightSnapshot,
): RecordingStartBlocker? = when {
    !snapshot.activityVisible -> RecordingStartBlocker.ACTIVITY_NOT_VISIBLE
    !snapshot.coarseLocationGranted && !snapshot.fineLocationGranted ->
        RecordingStartBlocker.MISSING_LOCATION_PERMISSION
    !snapshot.fineLocationGranted -> RecordingStartBlocker.MISSING_FINE_LOCATION
    !snapshot.locationEnabled -> RecordingStartBlocker.LOCATION_DISABLED
    else -> null
}

internal enum class RecordingStartBlocker {
    ACTIVITY_NOT_VISIBLE,
    MISSING_LOCATION_PERMISSION,
    MISSING_FINE_LOCATION,
    LOCATION_DISABLED,
}

internal fun interface RecordingServiceLauncher {
    fun start(sessionId: Long)
}

internal class AndroidRecordingServiceLauncher(
    private val context: Context,
) : RecordingServiceLauncher {
    override fun start(sessionId: Long) {
        RecordingForegroundService.startFromVisibleActivity(context, sessionId)
    }
}

internal interface RecordingStartCommands {
    suspend fun beginStart(
        operationId: RecordingOperationId,
        startedAtEpochMillis: Long,
        createdAppVersion: String,
    ): BeginStartResult

    suspend fun failStart(
        operationId: RecordingOperationId,
        sessionId: Long,
        failedAtEpochMillis: Long,
        message: String,
    )
}

internal class RepositoryRecordingStartCommands(
    private val repository: RecordingRepository,
) : RecordingStartCommands {
    override suspend fun beginStart(
        operationId: RecordingOperationId,
        startedAtEpochMillis: Long,
        createdAppVersion: String,
    ): BeginStartResult = repository.beginStart(
        operationId,
        startedAtEpochMillis,
        createdAppVersion,
    )

    override suspend fun failStart(
        operationId: RecordingOperationId,
        sessionId: Long,
        failedAtEpochMillis: Long,
        message: String,
    ) {
        repository.failStart(operationId, sessionId, failedAtEpochMillis, message)
    }
}

internal fun interface RecordingControllerClock {
    fun epochMillis(): Long
}

private object SystemRecordingControllerClock : RecordingControllerClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
}

internal fun interface RecordingControllerOperationIds {
    fun next(purpose: String): RecordingOperationId
}

private object UuidRecordingControllerOperationIds : RecordingControllerOperationIds {
    override fun next(purpose: String): RecordingOperationId =
        RecordingOperationId("$purpose:${UUID.randomUUID()}")
}

internal sealed interface RecordingStartOutcome {
    data class Blocked(val blocker: RecordingStartBlocker) : RecordingStartOutcome

    data class ServiceRequested(
        val sessionId: Long,
        val reservationDisposition: StartDisposition,
    ) : RecordingStartOutcome

    data class PersistenceFailure(
        val phase: RecordingStartPersistencePhase,
    ) : RecordingStartOutcome

    data class LaunchFailure(
        val sessionId: Long,
        val kind: RecordingStartFailureKind,
        val startFailurePersisted: Boolean,
    ) : RecordingStartOutcome
}

internal enum class RecordingStartPersistencePhase { BEGIN_START }

internal enum class RecordingStartFailureKind {
    BACKGROUND_START_NOT_ALLOWED,
    SECURITY,
    RUNTIME,
}

private fun RuntimeException.toRecordingStartFailureKind(): RecordingStartFailureKind = when (this) {
    is ForegroundServiceStartNotAllowedException ->
        RecordingStartFailureKind.BACKGROUND_START_NOT_ALLOWED
    is SecurityException -> RecordingStartFailureKind.SECURITY
    else -> RecordingStartFailureKind.RUNTIME
}
