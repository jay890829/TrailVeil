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
    /**
     * Re-arm an exploration this process found abandoned — an `ACTIVE` row whose owning runtime is
     * gone because the platform declined to restart the service after a process death.
     *
     * Unlike [startFromVisibleActivity] this reserves nothing, and that is the point. The row
     * already exists, while `prepareStart` inserts a fresh session whenever it finds nothing active
     * or reserved, so a resume routed through a reservation would silently record an exploration the
     * user never started if the row terminalized between the screen reading it and the transaction
     * running. The service is asked directly instead: an `ACTION_START` it cannot activate falls
     * through to the durable recovery transaction, and a row that no longer needs recovering simply
     * leaves the service with nothing to recover.
     */
    suspend fun resumeAbandonedFromVisibleActivity(
        activityVisible: Boolean,
        sessionId: Long,
    ): RecordingResumeOutcome {
        require(sessionId > 0L) { "sessionId must be positive" }
        preflight.blocker(activityVisible)?.let {
            return RecordingResumeOutcome.Blocked(it)
        }
        return try {
            launcher.start(sessionId)
            RecordingResumeOutcome.ServiceRequested(sessionId)
        } catch (failure: RuntimeException) {
            RecordingResumeOutcome.LaunchFailure(sessionId, failure.toRecordingStartFailureKind())
        }
    }

    /**
     * End an abandoned exploration the device restarted under, marking it interrupted.
     *
     * Deliberately without preflight: this starts no service and subscribes to no location, so a user
     * who has since revoked the permission or switched location off must still get the row closed —
     * refusing here would leave exactly the open row `PLAN.md` requires be marked interrupted.
     *
     * [stoppedRecordingAtEpochMillis] is when recording actually stopped being real — the last
     * accepted point, or the session's own start if it never recorded one — and **not** now. The row
     * is discovered whenever the user next opens the app, which may be hours or days later, and
     * dating the ending from that moment would publish an exploration whose duration is mostly time
     * the phone spent switched off. The store clamps it up to the session and segment starts, so a
     * past instant cannot produce a row that ends before it began.
     */
    suspend fun interruptAbandonedAcrossRestart(
        sessionId: Long,
        stoppedRecordingAtEpochMillis: Long?,
    ): Boolean {
        require(sessionId > 0L) { "sessionId must be positive" }
        return try {
            commands.interrupt(
                operationIds.next("restart-interrupt"),
                sessionId,
                stoppedRecordingAtEpochMillis ?: clock.epochMillis(),
                DEVICE_RESTARTED,
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The row stays ACTIVE and the screen keeps saying the exploration was abandoned, which
            // is still true; the next open tries again.
            false
        }
    }

    suspend fun startFromVisibleActivity(
        activityVisible: Boolean,
        beginOperationId: RecordingOperationId? = null,
    ): RecordingStartOutcome {
        preflight.blocker(activityVisible)?.let {
            return RecordingStartOutcome.Blocked(it)
        }

        val reservation = try {
            commands.beginStart(
                beginOperationId ?: operationIds.next("begin-start"),
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

    private companion object {
        /** Terminal reason for a row the device restarted under; stored as `INTERRUPT:` + this. */
        const val DEVICE_RESTARTED = "device_restarted"
    }
}

internal sealed interface RecordingResumeOutcome {
    data class Blocked(val blocker: RecordingStartBlocker) : RecordingResumeOutcome

    data class ServiceRequested(val sessionId: Long) : RecordingResumeOutcome

    data class LaunchFailure(
        val sessionId: Long,
        val kind: RecordingStartFailureKind,
    ) : RecordingResumeOutcome
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

    suspend fun interrupt(
        operationId: RecordingOperationId,
        sessionId: Long,
        interruptedAtEpochMillis: Long,
        reason: String,
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

    override suspend fun interrupt(
        operationId: RecordingOperationId,
        sessionId: Long,
        interruptedAtEpochMillis: Long,
        reason: String,
    ) {
        repository.interrupt(operationId, sessionId, interruptedAtEpochMillis, reason)
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
