package app.trailveil.recording

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.trailveil.TrailVeilApplication
import app.trailveil.data.recording.RecordingLifecycle
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
        // This transition deliberately precedes all DB work and LocationManager registration.
        try {
            notifier.show(this, intent?.sessionIdOrNull())
        } catch (_: SecurityException) {
            commands.trySend(Command.ForegroundFailure(intent?.action, intent?.sessionIdOrNull(), startId))
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> commands.trySend(Command.Stop(intent.sessionIdOrNull(), startId))
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
        try {
            when (dependencies.recordingRepository.recover(
                dependencies.operationIds.next("service-recovery"), dependencies.clock.epochMillis(),
            ).disposition) {
                app.trailveil.data.recording.RecoveryDisposition.ACTIVE_ROTATED -> {
                    val sessionId = requireNotNull(dependencies.recordingRepository.state().sessionId)
                    notifier.show(this, sessionId)
                    startCollector(sessionId)
                }
                app.trailveil.data.recording.RecoveryDisposition.ACTIVE_ALREADY_RECOVERED -> {
                    // The repository did not grant this service a location-owner token.
                    stopRuntime(command.startId)
                }
                app.trailveil.data.recording.RecoveryDisposition.STARTING_FAILED,
                app.trailveil.data.recording.RecoveryDisposition.NOTHING_TO_RECOVER ->
                    stopRuntime(command.startId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val active = try {
                dependencies.recordingRepository.state()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (active?.sessionId != null && active.lifecycle == RecordingLifecycle.ACTIVE) {
                interruptThenStop(active.sessionId, TerminalReason.RECOVERY_FAILURE, command.startId)
            } else {
                stopRuntime(command.startId)
            }
        }
    }

    private suspend fun handleStop(command: Command.Stop) {
        val current = try {
            dependencies.recordingRepository.state()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Stop collection, but do not claim a durable terminal state.
            stopRuntime(command.startId)
            return
        }
        val sessionId = current.sessionId
        when (
            RecordingServicePolicy.notificationStopDecision(
                command.sessionId,
                sessionId,
                current.lifecycle,
            )
        ) {
            NotificationStopDecision.IGNORE_STALE_ACTION -> {
                val activeSessionId = requireNotNull(sessionId)
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
            NotificationStopDecision.STOP_CURRENT_SESSION -> Unit
        }
        val activeSessionId = requireNotNull(sessionId)
        try {
            // User intent is the only service path that records COMPLETED.
            dependencies.recordingRepository.stop(
                dependencies.operationIds.next("notification-stop"), activeSessionId, dependencies.clock.epochMillis(),
                TerminalReason.NOTIFICATION_STOP,
            )
            stopRuntime(command.startId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            interruptThenStop(activeSessionId, TerminalReason.NOTIFICATION_STOP_PERSISTENCE_FAILURE, command.startId)
        }
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
                state.sessionId != null && state.lifecycle == RecordingLifecycle.ACTIVE ->
                    dependencies.recordingRepository.interrupt(
                        dependencies.operationIds.next("foreground-restart-interrupt"), state.sessionId,
                        dependencies.clock.epochMillis(), TerminalReason.FOREGROUND_RESTART_FAILURE,
                    )
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
                        dependencies.recordingRepository.deliverLocation(
                            dependencies.operationIds.next("location"), sessionId, fix,
                            dependencies.clock.elapsedRealtimeNanos(), dependencies.clock.epochMillis(),
                        )
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
        stopRuntime(startId)
    }

    private fun stopRuntime(startId: Int) {
        collectorJob?.cancel()
        collectorJob = null
        collectorSessionId = null
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
        const val NOTIFICATION_STOP_PERSISTENCE_FAILURE = "notification_stop_persistence_failure"
        const val START_COMPLETION_FAILURE = "start_completion_failure"
        const val RECOVERY_FAILURE = "recovery_failure"
        const val LOCATION_STREAM_FAILURE = "location_stream_failure"
        const val LOCATION_DISABLED = "location_disabled"
        const val LOCATION_PERMISSION_REVOKED = "location_permission_revoked"
        const val LOCATION_PROVIDER_UNAVAILABLE = "location_provider_unavailable"
        const val STORAGE_FAILURE = "storage_failure"
        const val FOREGROUND_START_FAILURE = "foreground_start_failure"
        const val FOREGROUND_RESTART_FAILURE = "foreground_restart_failure"
    }

    private fun Exception.toLocationTerminalReason(): String = when (this) {
        is LocationPersistenceFailure -> TerminalReason.STORAGE_FAILURE
        is LocationProviderDisabledException -> TerminalReason.LOCATION_DISABLED
        is LocationPermissionException, is SecurityException ->
            TerminalReason.LOCATION_PERMISSION_REVOKED
        is LocationProviderUnavailableException -> TerminalReason.LOCATION_PROVIDER_UNAVAILABLE
        else -> TerminalReason.LOCATION_STREAM_FAILURE
    }

    private class LocationPersistenceFailure(cause: Exception) : Exception(cause)
    companion object {
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