package app.trailveil.recording

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.trailveil.TrailVeilApplication
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.data.recording.LocationDisposition
import app.trailveil.data.location.LocationPermissionException
import app.trailveil.data.location.LocationProviderDisabledException
import app.trailveil.data.location.LocationProviderUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android 14+ location foreground-service runtime. A visible activity must preflight permissions
 * and persist beginStart before launching this service. This service never requests permissions.
 */
class RecordingForegroundService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private lateinit var dependencies: RecordingRuntimeDependencies
    private lateinit var notifier: RecordingForegroundNotifier
    private var collectorJob: Job? = null
    private var collectorSessionId: Long? = null
    private val latestStartId = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        dependencies = (application as TrailVeilApplication).appContainer
        notifier = RecordingForegroundNotifier(this)
        serviceScope.launch {
            for (command in commands) handle(command)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.updateAndGet { maxOf(it, startId) }
        // An explicit Stop is already targeting an existing runtime (or is the visible in-app
        // fallback). Persist that command before doing notification work which could fail and
        // otherwise divert the service into a technical-interrupt path.
        if (intent?.action == ACTION_STOP) {
            commands.trySend(Command.Stop(intent.sessionIdOrNull(), startId))
            return START_STICKY
        }
        // This transition deliberately precedes all DB work and LocationManager registration.
        try {
            notifier.show(this, intent?.sessionIdOrNull())
        } catch (_: SecurityException) {
            commands.trySend(Command.ForegroundFailure(intent?.action, intent?.sessionIdOrNull(), startId))
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> commands.trySend(Command.Start(intent.sessionIdOrNull(), startId))
            null -> commands.trySend(Command.StickyRestart(startId))
            else -> {
                // Never treat an arbitrary explicit intent as a recovery or stop request. Preserve
                // sticky restart semantics until the serialized handler confirms there is no work.
                commands.trySend(Command.Unknown(startId))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Lifecycle cleanup only: system process death must not be recorded as COMPLETED.
        collectorJob?.cancel()
        commands.close()
        serviceScope.cancel()
        if (::dependencies.isInitialized) dependencies.recordingServiceState.clearStopping()
        super.onDestroy()
    }

    private suspend fun handle(command: Command) = when (command) {
        is Command.Start -> handleStart(command)
        is Command.Stop -> handleStop(command)
        is Command.StickyRestart -> handleStickyRestart(command)
        is Command.LocationFailure -> handleLocationFailure(command)
        is Command.ForegroundFailure -> handleForegroundFailure(command)
        is Command.Unknown -> handleUnknown(command)
    }

    private suspend fun handleStart(command: Command.Start) {
        val sessionId = command.sessionId ?: run {
            stopRuntime(command.startId)
            return
        }
        if (isCollecting(sessionId)) return
        try {
            val completion = dependencies.recordingRepository.completeStart(
                dependencies.operationIds.next("service-complete"), sessionId, dependencies.clock.epochMillis(),
            )
            if (completion.activated) {
                notifier.show(this, sessionId)
                startCollector(sessionId)
            } else {
                // Reacquire ownership only through the durable recovery transaction.
                handleStickyRestart(Command.StickyRestart(command.startId))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            interruptThenStop(sessionId, TerminalReason.START_COMPLETION_FAILURE, command.startId)
        }
    }

    private suspend fun handleStickyRestart(command: Command.StickyRestart) {
        if (collectorJob?.isActive == true) return
        val recovery = RecordingPersistenceRetrier(
            attempt = {
                dependencies.recordingRepository.recover(
                    dependencies.operationIds.next("service-recovery"),
                    dependencies.clock.epochMillis(),
                )
            },
            retryDelay = { delay(PERSISTENCE_RETRY_MILLIS) },
        ).runUntilResolved()
        when (recovery.disposition) {
            app.trailveil.data.recording.RecoveryDisposition.PENDING_STOP_COMPLETED -> {
                try {
                    notifier.showCompleted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The terminal state is already durable; a confirmation failure cannot
                    // resurrect the session or justify keeping a location service alive.
                }
                stopRuntime(command.startId)
            }
            app.trailveil.data.recording.RecoveryDisposition.ACTIVE_ROTATED -> {
                val sessionId = requireNotNull(recovery.state.sessionId)
                try {
                    notifier.show(this, sessionId)
                    startCollector(sessionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    interruptThenStop(
                        sessionId,
                        TerminalReason.FOREGROUND_RESTART_FAILURE,
                        command.startId,
                    )
                }
            }
            app.trailveil.data.recording.RecoveryDisposition.ACTIVE_ALREADY_RECOVERED -> {
                // The repository did not grant this service a location-owner token.
                stopRuntime(command.startId)
            }
            app.trailveil.data.recording.RecoveryDisposition.STARTING_FAILED,
            app.trailveil.data.recording.RecoveryDisposition.NOTHING_TO_RECOVER ->
                stopRuntime(command.startId)
        }
    }

    private suspend fun handleStop(command: Command.Stop) {
        // Every production Stop action carries a session id. A generic action must first resolve
        // one, but a read failure is not permission to stop collection without durable intent.
        val requestedSessionId = command.sessionId ?: try {
            dependencies.recordingRepository.state().sessionId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (requestedSessionId == null) {
            stopRuntime(command.startId)
            return
        }
        val requestedAt = dependencies.clock.epochMillis()
        val request = RecordingPersistenceRetrier(
            attempt = {
                dependencies.recordingRepository.requestStop(
                    dependencies.operationIds.next("notification-stop-request"),
                    requestedSessionId,
                    requestedAt,
                    TerminalReason.NOTIFICATION_STOP,
                )
            },
            // Collection remains active until an attempt commits. If this process dies first,
            // there is intentionally no claim that an uncommitted intent survived.
            retryDelay = { delay(PERSISTENCE_RETRY_MILLIS) },
        ).runUntilResolved()
        if (!request.requested) {
            val current = try {
                dependencies.recordingRepository.state()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return
            }
            when (
                RecordingServicePolicy.notificationStopDecision(
                    requestedSessionId,
                    current.sessionId,
                    current.lifecycle,
                )
            ) {
                NotificationStopDecision.IGNORE_STALE_ACTION -> {
                    val activeSessionId = requireNotNull(current.sessionId)
                    try {
                        notifier.show(this, activeSessionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        interruptThenStop(
                            activeSessionId,
                            TerminalReason.FOREGROUND_RESTART_FAILURE,
                            command.startId,
                        )
                    }
                    return
                }
                NotificationStopDecision.NO_ACTIVE_SESSION -> {
                    stopRuntime(command.startId)
                    return
                }
                NotificationStopDecision.STOP_CURRENT_SESSION ->
                    error("an active current session rejected its durable Stop request")
            }
        }

        dependencies.recordingServiceState.markStopping(requestedSessionId)
        // Only now may collection end. The receipt above is what a fresh process will consume if
        // terminal persistence or this process dies after this line.
        collectorJob?.cancel()
        collectorJob = null
        collectorSessionId = null
        completePendingUserStop(requestedSessionId, requestedAt, command.startId)
    }

    private suspend fun completePendingUserStop(
        sessionId: Long,
        requestedAt: Long,
        startId: Int,
    ) {
        RecordingPersistenceRetrier(
            attempt = {
                dependencies.recordingRepository.stop(
                    dependencies.operationIds.next("notification-stop"),
                    sessionId,
                    requestedAt,
                    TerminalReason.NOTIFICATION_STOP,
                )
            },
            // Keep the foreground runtime alive but with location collection disabled. Process
            // death transfers the durable receipt to sticky recovery before collection can resume.
            retryDelay = { delay(PERSISTENCE_RETRY_MILLIS) },
        ).runUntilResolved()
        // Only after the exploration is durable, and only on the path that completes one, so the
        // confirmation can never outrun what it is confirming. Notification failure does not retry
        // an already terminal database command or keep the foreground runtime alive.
        try {
            notifier.showCompleted()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable completion remains authoritative.
        }
        stopRuntime(startId)
    }

    private suspend fun handleLocationFailure(command: Command.LocationFailure) {
        if (collectorSessionId == command.sessionId) {
            interruptThenStop(command.sessionId, command.reason, command.startId)
        }
    }

    private suspend fun handleUnknown(command: Command.Unknown) {
        val collectingSessionId = collectorSessionId.takeIf { collectorJob?.isActive == true }
        val state = if (collectingSessionId == null) {
            try {
                dependencies.recordingRepository.state()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Unknown input must not tear down a runtime whose durable state cannot be read.
                return
            }
        } else {
            null
        }
        val currentSessionId = collectingSessionId ?: state?.sessionId
        if (
            currentSessionId == null ||
            (state != null && state.lifecycle !in setOf(
                RecordingLifecycle.STARTING,
                RecordingLifecycle.ACTIVE,
            ))
        ) {
            stopRuntime(command.startId)
            return
        }
        try {
            notifier.show(this, currentSessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            handleForegroundFailure(
                Command.ForegroundFailure(
                    action = null,
                    sessionId = currentSessionId,
                    startId = command.startId,
                ),
            )
        }
    }
    private suspend fun handleForegroundFailure(command: Command.ForegroundFailure) {
        try {
            val state = dependencies.recordingRepository.state()
            when {
                state.sessionId != null && state.lifecycle == RecordingLifecycle.ACTIVE -> {
                    dependencies.recordingRepository.interrupt(
                        dependencies.operationIds.next("foreground-restart-interrupt"), state.sessionId,
                        dependencies.clock.epochMillis(), TerminalReason.FOREGROUND_RESTART_FAILURE,
                    )
                    // This was the one interrupt arm that told nobody (P4-015's named exception).
                    // Notifying needs no location permission and no foreground elevation, and this
                    // is exactly the moment the user is not looking - a restarted process whose
                    // startForeground was refused because the permission grade cannot re-arm
                    // location from the background (measured on the POCO: sessions 39 and 43
                    // refused at 使用時允許; session 44 recovered at 一律允許, P4-041).
                    announceInterruption(state.sessionId)
                }
                command.action == ACTION_START && command.sessionId != null ->
                    dependencies.recordingRepository.failStart(
                        dependencies.operationIds.next("foreground-fail-start"), command.sessionId,
                        dependencies.clock.epochMillis(), TerminalReason.FOREGROUND_START_FAILURE,
                    )
                state.sessionId != null && state.lifecycle == RecordingLifecycle.STARTING ->
                    dependencies.recordingRepository.failStart(
                        dependencies.operationIds.next("foreground-restart-fail-start"), state.sessionId,
                        dependencies.clock.epochMillis(), TerminalReason.FOREGROUND_START_FAILURE,
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort only: foreground elevation is unavailable, so this service must exit.
        } finally {
            stopSelfResult(command.startId)
        }
    }

    private fun startCollector(sessionId: Long) {
        if (isCollecting(sessionId)) return
        collectorJob?.cancel()
        collectorSessionId = sessionId
        collectorJob = serviceScope.launch {
            try {
                dependencies.locationEngine.fixes().collect { fix ->
                    try {
                        val result = dependencies.recordingRepository.deliverLocation(
                            dependencies.operationIds.next("location"), sessionId, fix,
                            dependencies.clock.elapsedRealtimeNanos(), dependencies.clock.epochMillis(),
                        )
                        if (result.disposition == LocationDisposition.ACCEPTED) {
                            dependencies.recordingServiceState.publishAcceptedLocation(
                                RecordingServiceLocation(
                                    sessionId = sessionId,
                                    latitude = fix.latitude,
                                    longitude = fix.longitude,
                                ),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        throw LocationPersistenceFailure(failure)
                    }
                }
                commands.trySend(
                    Command.LocationFailure(
                        sessionId,
                        TerminalReason.LOCATION_STREAM_FAILURE,
                        latestStartId.get(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                commands.trySend(
                    Command.LocationFailure(
                        sessionId,
                        failure.toLocationTerminalReason(),
                        latestStartId.get(),
                    ),
                )
            }
        }
    }

    private suspend fun interruptThenStop(sessionId: Long, reason: String, startId: Int) {
        collectorJob?.cancel()
        collectorJob = null
        collectorSessionId = null
        try {
            dependencies.recordingRepository.interrupt(
                dependencies.operationIds.next("service-interrupt"), sessionId,
                dependencies.clock.epochMillis(), reason,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The process is being stopped; recovery will reconcile the durable active row later.
        }
        // Notified either way. The claim is that recording stopped without completing and that
        // whatever was already saved is kept, and both are true whether or not the terminal row
        // could be written — nothing else will tell the user this happened.
        //
        // This is the site P4-048 exists for. When the interrupt above fails - which is what a full
        // disk does - the durable row stays ACTIVE while the user is told the exploration ended,
        // and without the record below, reopening would resume it.
        announceInterruption(sessionId)
        stopRuntime(startId)
    }

    /**
     * Tell the user an exploration was interrupted, and remember that this runtime said so.
     *
     * `P4-048`. Every announcement goes through here, so the memory cannot fall out of step with the
     * notification: [RecordingForegroundNotifier.showInterrupted] has no session to record and the
     * two would otherwise be separate statements at each site. What the runtime remembers is read by
     * `abandonedExplorationAction`, which refuses to resume an exploration the user has been told
     * about.
     */
    private fun announceInterruption(sessionId: Long) {
        dependencies.announcedInterruptions.announce(sessionId)
        notifier.showInterrupted()
    }

    private fun stopRuntime(startId: Int) {
        collectorJob?.cancel()
        collectorJob = null
        collectorSessionId = null
        dependencies.recordingServiceState.clearStopping()
        dependencies.recordingServiceState.clearLocation()
        notifier.dismiss(this)
        stopSelfResult(startId)
    }

    private fun isCollecting(sessionId: Long): Boolean =
        collectorSessionId == sessionId && collectorJob?.isActive == true

    private fun Intent.sessionIdOrNull(): Long? =
        getLongExtra(EXTRA_SESSION_ID, RecordingForegroundNotifier.NO_SESSION_ID).takeIf { it > 0L }

    private sealed interface Command {
        data class Start(val sessionId: Long?, val startId: Int) : Command
        data class Stop(val sessionId: Long?, val startId: Int) : Command
        data class StickyRestart(val startId: Int) : Command
        data class LocationFailure(val sessionId: Long, val reason: String, val startId: Int) : Command
        data class ForegroundFailure(val action: String?, val sessionId: Long?, val startId: Int) : Command
        data class Unknown(val startId: Int) : Command
    }

    private object TerminalReason {
        const val NOTIFICATION_STOP = "user_notification_stop"
        const val START_COMPLETION_FAILURE = "start_completion_failure"
        const val LOCATION_STREAM_FAILURE = "location_stream_failure"
        const val LOCATION_DISABLED = "location_disabled"
        const val LOCATION_PERMISSION_REVOKED = "location_permission_revoked"
        const val LOCATION_PROVIDER_UNAVAILABLE = "location_provider_unavailable"
        const val STORAGE_FAILURE = "storage_failure"
        const val FOREGROUND_START_FAILURE = "foreground_start_failure"
        const val FOREGROUND_RESTART_FAILURE = "foreground_restart_failure"
    }

    private fun Exception.toLocationTerminalReason(): String =
        RecordingServicePolicy.locationBackpressureTerminalReason(this) ?: when (this) {
            is LocationPersistenceFailure -> TerminalReason.STORAGE_FAILURE
            is LocationProviderDisabledException -> TerminalReason.LOCATION_DISABLED
            is LocationPermissionException, is SecurityException ->
                TerminalReason.LOCATION_PERMISSION_REVOKED
            is LocationProviderUnavailableException -> TerminalReason.LOCATION_PROVIDER_UNAVAILABLE
            else -> TerminalReason.LOCATION_STREAM_FAILURE
        }

    private class LocationPersistenceFailure(cause: Exception) : Exception(cause)
    companion object {
        private const val PERSISTENCE_RETRY_MILLIS = 1_000L
        const val ACTION_START = "app.trailveil.action.START_RECORDING"
        const val ACTION_STOP = "app.trailveil.action.STOP_RECORDING"
        const val EXTRA_SESSION_ID = "app.trailveil.extra.SESSION_ID"

        /** Call only from a visible activity after preflight and durable beginStart have succeeded. */
        fun startFromVisibleActivity(context: Context, sessionId: Long) {
            require(sessionId > 0L)
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sessionId)
                },
            )
        }

        /** Visible in-app fallback when the user has hidden foreground-service notifications. */
        fun stopFromVisibleActivity(context: Context, sessionId: Long) {
            require(sessionId > 0L)
            context.startService(
                Intent(context, RecordingForegroundService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SESSION_ID, sessionId)
                },
            )
        }
    }
}

/**
 * Fail-closed persistence loop. A transient store error cannot authorize the service to advance;
 * the operation is retried until one transaction returns a committed or idempotent result.
 */
internal class RecordingPersistenceRetrier<T>(
    private val attempt: suspend () -> T,
    private val retryDelay: suspend () -> Unit,
) {
    suspend fun runUntilResolved(): T {
        while (true) {
            try {
                return attempt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retryDelay()
            }
        }
    }
}
