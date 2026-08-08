package app.trailveil.data.recording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.data.db.RecordingDao
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrackPointEntity
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.location.RawLocationFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRecordingStoreTest {
    private lateinit var database: TrailVeilDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()
        dao = database.recordingDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun repositoryPersistsStartGapResumeAndCompletedStopThroughRoom() = runBlocking {
        val repository = repository()
        val sessionId = repository.begin("begin", 1_000).sessionId
        assertTrue(repository.completeStart(id("activate"), sessionId, 1_001).activated)

        val first = repository.deliver(
            operationId = "first",
            sessionId = sessionId,
            capturedAtNanos = 0,
            epochMillis = 1_002,
            longitude = 121.0,
            recordedAt = 1_002,
        )
        val replay = repository.deliver(
            operationId = "first",
            sessionId = sessionId,
            capturedAtNanos = SECOND,
            epochMillis = 9_999,
            longitude = 122.0,
            recordedAt = 9_999,
        )
        assertEquals(first, replay)

        repository.deliver(
            operationId = "continuous",
            sessionId = sessionId,
            capturedAtNanos = SECOND,
            epochMillis = 1_003,
            longitude = 121.00001,
            recordedAt = 1_003,
        )
        val rejected = repository.deliver(
            operationId = "gap-rejected",
            sessionId = sessionId,
            capturedAtNanos = 70 * SECOND,
            epochMillis = 1_004,
            longitude = 121.5,
            accuracyMeters = 60.0,
            recordedAt = 1_100,
        )
        assertEquals(LocationDisposition.REJECTED, rejected.disposition)
        assertEquals(2, dao.pointCount())

        repository.deliver(
            operationId = "after-break",
            sessionId = sessionId,
            capturedAtNanos = 71 * SECOND,
            epochMillis = 1_005,
            longitude = 121.00002,
            recordedAt = 1_101,
        )
        assertTrue(repository.stop(id("stop"), sessionId, 1_102, "USER").stopped)

        val session = requireNotNull(dao.sessionById(sessionId))
        assertEquals(RecordingStatus.COMPLETED, session.status)
        assertEquals(3L, session.acceptedPointCount)
        assertEquals(1L, session.rejectedPointCount)
        assertTrue(session.distanceMeters > 0.0)
        assertEquals(3, dao.pointCount())

        val segments = requireNotNull(dao.sessionWithSegments(sessionId))
            .segments
            .sortedBy { it.sequence }
        assertEquals(2, segments.size)
        assertEquals("GAP", segments[0].endReason)
        assertEquals("PROCESS_GAP", segments[1].startReason)
        assertEquals(listOf(0L, 1L), dao.pointsForSegment(segments[0].id).map { it.sequence })
        assertEquals(listOf(0L), dao.pointsForSegment(segments[1].id).map { it.sequence })
    }

    @Test
    fun activeTechnicalInterruptPersistsInterruptedTerminalAndStableReceipt() = runBlocking {
        val repository = repository()
        val sessionId = repository.begin("begin-interrupt", 1_000).sessionId
        assertTrue(repository.completeStart(id("activate-interrupt"), sessionId, 1_001).activated)

        val first = repository.interrupt(
            id("interrupt-location-disabled"),
            sessionId,
            1_100,
            "LOCATION_DISABLED",
        )
        val replay = repository.interrupt(
            id("interrupt-location-disabled"),
            sessionId,
            9_999,
            "replayed-different-input",
        )

        assertEquals(first, replay)
        assertTrue(first.stopped)
        val session = requireNotNull(dao.sessionById(sessionId))
        assertEquals(RecordingStatus.INTERRUPTED, session.status)
        assertEquals("INTERRUPT:LOCATION_DISABLED", session.stopReason)
        val segment = requireNotNull(dao.sessionWithSegments(sessionId)).segments.single()
        assertEquals("INTERRUPT:LOCATION_DISABLED", segment.endReason)
        assertEquals("INTERRUPT", dao.receiptByOperationId("interrupt-location-disabled")?.commandKind)

        val nextId = repository.begin("begin-completed", 1_200).sessionId
        assertTrue(repository.completeStart(id("activate-completed"), nextId, 1_201).activated)
        assertTrue(repository.stop(id("user-stop"), nextId, 1_300, "USER").stopped)
        assertEquals(RecordingStatus.COMPLETED, dao.sessionById(nextId)?.status)
    }

    @Test
    fun pendingUserStopIsDurableAndRecoveryCompletesItWithoutResuming() = runBlocking {
        val owner = repository(RecordingRuntimeId("stop-owner"))
        val sessionId = owner.begin("pending-stop-begin", 1_000).sessionId
        assertTrue(owner.completeStart(id("pending-stop-activate"), sessionId, 1_001).activated)

        val request = owner.requestStop(
            id("pending-stop-request"),
            sessionId,
            1_100,
            "USER",
        )
        assertTrue(request.requested)
        assertEquals(
            "REQUEST_STOP",
            dao.receiptByOperationId("pending-stop-request")?.commandKind,
        )
        assertEquals(
            "STOP_REQUESTED:USER",
            dao.receiptByOperationId("pending-stop-request")?.outcome,
        )
        assertTrue(
            repository(RecordingRuntimeId("second-stop-requester")).requestStop(
                id("pending-stop-request-later"),
                sessionId,
                1_200,
                "LATER_DUPLICATE",
            ).requested,
        )
        assertEquals(RecordingStatus.ACTIVE, dao.sessionById(sessionId)?.status)

        val openSegment = requireNotNull(dao.sessionWithSegments(sessionId)).segments.single()
        val guarded = dao.executeLocation(
            sessionId = sessionId,
            expectedOpenSegmentId = openSegment.id,
            expectedLocationOwnerToken = "stop-owner",
            point = TrackPointEntity(
                sessionId = sessionId,
                segmentId = 0L,
                sequence = 0L,
                timestamp = 1_101L,
                latitude = 25.0,
                longitude = 121.0,
                horizontalAccuracy = 5.0,
            ),
            acceptedKind = "FIRST",
            breakReason = null,
            distanceDeltaMeters = 0.0,
            recordedAt = 1_101L,
            operationId = "pending-stop-late-location",
            commandKind = "LOCATION",
            createdAt = 1_101L,
        )
        assertEquals("LOCATION_SESSION_GUARD", guarded.receipt.outcome)
        assertEquals(0, dao.pointCount())

        val recovered = repository(RecordingRuntimeId("stop-recovery"))
        val result = recovered.recover(id("recover-pending-stop"), 9_999)
        assertEquals(RecoveryDisposition.PENDING_STOP_COMPLETED, result.disposition)
        assertEquals(RecordingLifecycle.STOPPED, result.state.lifecycle)
        val session = requireNotNull(dao.sessionById(sessionId))
        assertEquals(RecordingStatus.COMPLETED, session.status)
        assertEquals(1_100L, session.endedAt)
        assertEquals("STOP:USER", session.stopReason)
        val segment = requireNotNull(dao.sessionWithSegments(sessionId)).segments.single()
        assertEquals("STOP:USER", segment.endReason)
        assertEquals(1, dao.segmentCount())

        assertEquals(
            result,
            recovered.recover(id("recover-pending-stop"), 20_000),
        )
        assertEquals(
            RecoveryDisposition.NOTHING_TO_RECOVER,
            repository(RecordingRuntimeId("later-runtime"))
                .recover(id("recover-after-stop"), 20_001)
                .disposition,
        )
        assertEquals(1, dao.segmentCount())
    }

    @Test
    fun pendingUserStopOutranksALaterTechnicalInterruptTransaction() = runBlocking {
        val owner = repository(RecordingRuntimeId("stop-before-interrupt-owner"))
        val sessionId = owner.begin("stop-before-interrupt-begin", 1_000).sessionId
        assertTrue(
            owner.completeStart(
                id("stop-before-interrupt-activate"),
                sessionId,
                1_001,
            ).activated,
        )
        assertTrue(
            owner.requestStop(
                id("stop-before-interrupt-request"),
                sessionId,
                1_100,
                "USER",
            ).requested,
        )

        val fallback = repository(RecordingRuntimeId("technical-fallback"))
        val interrupted = fallback.interrupt(
            id("technical-interrupt-after-stop"),
            sessionId,
            9_999,
            "RECOVERY_FAILURE",
        )
        assertTrue(interrupted.stopped)
        assertEquals(
            interrupted,
            fallback.interrupt(
                id("technical-interrupt-after-stop"),
                sessionId,
                20_000,
                "DIFFERENT_REPLAY_INPUT",
            ),
        )

        val session = requireNotNull(dao.sessionById(sessionId))
        assertEquals(RecordingStatus.COMPLETED, session.status)
        assertEquals(1_100L, session.endedAt)
        assertEquals("STOP:USER", session.stopReason)
        assertEquals(
            "STOP:USER",
            requireNotNull(dao.sessionWithSegments(sessionId)).segments.single().endReason,
        )
        assertEquals(
            "INTERRUPT",
            dao.receiptByOperationId("technical-interrupt-after-stop")?.commandKind,
        )
        assertEquals(
            RecoveryDisposition.NOTHING_TO_RECOVER,
            repository(RecordingRuntimeId("after-technical-fallback"))
                .recover(id("after-technical-fallback-recovery"), 20_001)
                .disposition,
        )
    }

    @Test
    fun pendingStopDuringStartPreventsActivationAndRecoversAsInterrupted() = runBlocking {
        val owner = repository(RecordingRuntimeId("starting-stop-owner"))
        val sessionId = owner.begin("starting-stop-begin", 2_000).sessionId
        assertTrue(
            owner.requestStop(
                id("starting-stop-request"),
                sessionId,
                2_100,
                "USER_CANCELLED",
            ).requested,
        )

        assertFalse(
            owner.completeStart(id("activation-after-stop"), sessionId, 2_101).activated,
        )
        assertEquals(RecordingStatus.STARTING, dao.sessionById(sessionId)?.status)
        assertEquals(0, dao.segmentCount())

        val recovered = repository(RecordingRuntimeId("starting-stop-recovery"))
        assertEquals(
            RecoveryDisposition.PENDING_STOP_COMPLETED,
            recovered.recover(id("recover-starting-stop"), 9_999).disposition,
        )
        val session = requireNotNull(dao.sessionById(sessionId))
        assertEquals(RecordingStatus.INTERRUPTED, session.status)
        assertEquals(2_100L, session.endedAt)
        assertEquals("STOP_DURING_START:USER_CANCELLED", session.stopReason)
        assertEquals(0, dao.segmentCount())
    }

    @Test
    fun pendingStopDuringStartOutranksALaterStartFailureTransaction() = runBlocking {
        val owner = repository(RecordingRuntimeId("starting-stop-failure-owner"))
        val sessionId = owner.begin("starting-stop-failure-begin", 3_000).sessionId
        assertTrue(
            owner.requestStop(
                id("starting-stop-failure-request"),
                sessionId,
                3_100,
                "USER_CANCELLED",
            ).requested,
        )

        assertTrue(
            owner.failStart(
                id("technical-start-failure-after-stop"),
                sessionId,
                9_999,
                "foreground unavailable",
            ).failed,
        )

        val session = requireNotNull(dao.sessionById(sessionId))
        assertEquals(RecordingStatus.INTERRUPTED, session.status)
        assertEquals(3_100L, session.endedAt)
        assertEquals("STOP_DURING_START:USER_CANCELLED", session.stopReason)
        assertEquals(0, dao.segmentCount())
        assertEquals(
            "FAIL_START",
            dao.receiptByOperationId("technical-start-failure-after-stop")?.commandKind,
        )
    }

    @Test
    fun processRecoveryRotatesOnceAndStartsANewZeroDistanceAnchor() = runBlocking {
        val original = repository()
        val sessionId = original.begin("begin", 1_000).sessionId
        original.completeStart(id("activate"), sessionId, 1_001)
        original.deliver(
            operationId = "before-recovery",
            sessionId = sessionId,
            capturedAtNanos = 0,
            epochMillis = 1_002,
            longitude = 0.0,
            recordedAt = 1_002,
        )

        val recovered = repository()
        assertEquals(RecordingLifecycle.ACTIVE, recovered.state().lifecycle)
        assertEquals(
            LocationDisposition.STALE_SESSION,
            recovered.deliver(
                operationId = "before-explicit-recovery",
                sessionId = sessionId,
                capturedAtNanos = SECOND,
                epochMillis = 1_003,
                longitude = 30.0,
                recordedAt = 1_003,
            ).disposition,
        )
        assertEquals(
            RecoveryDisposition.ACTIVE_ROTATED,
            recovered.recover(id("recover"), 1_100).disposition,
        )
        val firstAfterRecovery = recovered.deliver(
            operationId = "after-recovery",
            sessionId = sessionId,
            capturedAtNanos = SECOND,
            epochMillis = 1_004,
            longitude = 30.0,
            recordedAt = 1_101,
        )
        assertEquals(LocationDisposition.ACCEPTED, firstAfterRecovery.disposition)
        assertEquals(0.0, firstAfterRecovery.state.distanceMeters, 0.0)

        assertEquals(
            RecoveryDisposition.ACTIVE_ALREADY_RECOVERED,
            repository().recover(id("recover-again"), 1_200).disposition,
        )
        assertEquals(2, dao.segmentCount())
        assertEquals(2, dao.pointCount())
    }

    @Test
    fun beginReceiptReplayKeepsItsStartingProjectionWithoutRewindingRepositoryAuthority() = runBlocking {
        val repository = repository(RecordingRuntimeId("replay-owner"))
        val begin = repository.begin("stable-begin", 1_000)
        assertTrue(repository.completeStart(id("activate"), begin.sessionId, 1_001).activated)
        assertTrue(repository.stop(id("stop"), begin.sessionId, 1_002, "USER").stopped)

        val replay = repository.begin("stable-begin", 9_999)

        assertEquals(begin, replay)
        assertEquals(RecordingLifecycle.STARTING, replay.state.lifecycle)
        assertEquals(RecordingLifecycle.STOPPED, repository.state().lifecycle)
    }

    @Test
    fun durableLocalStaleReceiptStaysStaleAfterRecoveryAndCannotAddAPoint() = runBlocking {
        val owner = repository(RecordingRuntimeId("original-process"))
        val sessionId = owner.begin("begin", 1_000).sessionId
        assertTrue(owner.completeStart(id("activate"), sessionId, 1_001).activated)

        val recovering = repository(RecordingRuntimeId("recovery-process"))
        recovering.state()
        val stale = recovering.deliver("durable-stale", sessionId, 0, 1_002, 30.0, 1_002)
        assertEquals(LocationDisposition.STALE_SESSION, stale.disposition)
        assertEquals("LOCATION_SESSION_GUARD", dao.receiptByOperationId("durable-stale")?.outcome)
        assertEquals(0, dao.pointCount())

        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, recovering.recover(id("recover"), 1_100).disposition)
        val replay = recovering.deliver("durable-stale", sessionId, SECOND, 1_101, 30.1, 1_101)

        assertEquals(stale, replay)
        assertEquals(0, dao.pointCount())
    }

    @Test
    fun sameRuntimeRecoveryCannotDeliverAndOldOwnerIsGuardedAfterNewProcessTakesOver() = runBlocking {
        val originalRuntime = RecordingRuntimeId("original-runtime")
        val originalOwner = repository(originalRuntime)
        val sessionId = originalOwner.begin("begin", 1_000).sessionId
        assertTrue(originalOwner.completeStart(id("activate"), sessionId, 1_001).activated)

        val sameRuntimeRepository = repository(originalRuntime)
        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, sameRuntimeRepository.recover(id("recover-owner"), 1_100).disposition)
        val sameRuntimeSecondRepository = repository(originalRuntime)
        assertEquals(
            RecoveryDisposition.ACTIVE_ALREADY_RECOVERED,
            sameRuntimeSecondRepository.recover(id("recover-same-runtime"), 1_101).disposition,
        )
        assertEquals(
            LocationDisposition.STALE_SESSION,
            sameRuntimeSecondRepository.deliver("same-runtime-non-owner", sessionId, 0, 1_102, 30.0, 1_102).disposition,
        )

        val restarted = repository(RecordingRuntimeId("restarted-runtime"))
        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, restarted.recover(id("recover-new-process"), 1_200).disposition)
        assertEquals(
            LocationDisposition.STALE_SESSION,
            originalOwner.deliver("old-owner-after-takeover", sessionId, SECOND, 1_201, 30.1, 1_201).disposition,
        )
        assertEquals(0, dao.pointCount())
    }

    @Test
    fun newRuntimeRecoveryRotatesSegmentAcquiresOwnerAndStartsWithZeroDistance() = runBlocking {
        val original = repository(RecordingRuntimeId("before-restart"))
        val sessionId = original.begin("begin", 1_000).sessionId
        assertTrue(original.completeStart(id("activate"), sessionId, 1_001).activated)
        assertEquals(
            LocationDisposition.ACCEPTED,
            original.deliver("before-restart-anchor", sessionId, 0, 1_002, 0.0, 1_002).disposition,
        )

        val restarted = repository(RecordingRuntimeId("after-restart"))
        assertEquals(RecoveryDisposition.ACTIVE_ROTATED, restarted.recover(id("recover-after-restart"), 1_100).disposition)
        val firstAfterRestart = restarted.deliver("after-restart-anchor", sessionId, SECOND, 1_101, 60.0, 1_101)

        assertEquals(LocationDisposition.ACCEPTED, firstAfterRestart.disposition)
        assertEquals(0.0, firstAfterRestart.state.distanceMeters, 0.0)
        val segments = requireNotNull(dao.sessionWithSegments(sessionId)).segments.sortedBy { it.sequence }
        assertEquals(2, segments.size)
        assertEquals("PROCESS_RECOVERY", segments[1].startReason)
        assertEquals(2, dao.pointCount())
    }
    @Test
    fun racingStartsShareOneReservationAndTerminalTransitionsStayHonest() = runBlocking {
        val first = repository()
        val second = repository()
        val results = coroutineScope {
            awaitAll(
                async(Dispatchers.Default) { first.begin("begin-one", 1_000) },
                async(Dispatchers.Default) { second.begin("begin-two", 1_001) },
            )
        }

        assertEquals(
            setOf(StartDisposition.PREPARED, StartDisposition.ALREADY_STARTING),
            results.map { it.disposition }.toSet(),
        )
        assertEquals(1, dao.sessionCount())
        val startingSessionId = results.first().sessionId
        assertEquals(startingSessionId, results.last().sessionId)
        assertTrue(
            first.failStart(
                id("fail-start"),
                startingSessionId,
                1_002,
                "runtime unavailable",
            ).failed,
        )
        assertEquals(
            RecordingStatus.FAILED_TO_START,
            dao.sessionById(startingSessionId)?.status,
        )

        val cancelledSessionId = first.begin("begin-cancelled", 1_003).sessionId
        assertTrue(
            first.stop(
                id("stop-starting"),
                cancelledSessionId,
                1_004,
                "USER_CANCELLED",
            ).stopped,
        )
        assertEquals(
            RecordingStatus.INTERRUPTED,
            dao.sessionById(cancelledSessionId)?.status,
        )
        assertFalse(dao.reservedSession() != null)
    }

    @Test
    fun startupReconciliationFailsOnlyStartingAndAllowsFutureStart() = runBlocking {
        val repository = repository()
        assertEquals(
            ReconcileStartingDisposition.NOTHING_TO_RECONCILE,
            repository.reconcileStarting(id("empty-reconcile"), 900).disposition,
        )
        val stranded = repository.begin("stranded", 1_000).sessionId

        val reconciled = repository.reconcileStarting(id("reconcile"), 1_100)

        assertEquals(ReconcileStartingDisposition.STARTING_FAILED, reconciled.disposition)
        assertEquals(RecordingLifecycle.FAILED_TO_START, reconciled.state.lifecycle)
        val failed = requireNotNull(dao.sessionById(stranded))
        assertEquals(RecordingStatus.FAILED_TO_START, failed.status)
        assertEquals(1_100L, failed.endedAt)
        assertEquals("APP_STARTUP_RECONCILIATION", failed.stopReason)
        assertEquals(reconciled, repository.reconcileStarting(id("reconcile"), 9_999))

        val future = repository.begin("future", 1_101)
        assertEquals(StartDisposition.PREPARED, future.disposition)
        assertTrue(future.sessionId != stranded)
        assertTrue(repository.completeStart(id("future-active"), future.sessionId, 1_102).activated)

        // A later replay returns the historical reconciliation outcome without revoking the
        // replacement session's process-local delivery ownership.
        assertEquals(reconciled, repository.reconcileStarting(id("reconcile"), 9_999))
        assertEquals(
            LocationDisposition.ACCEPTED,
            repository.deliver("future-first", future.sessionId, 0, 1_103, 45.0, 1_103).disposition,
        )
    }

    @Test
    fun activationAndStartupReconciliationSerializeToOneHonestWinner() = runBlocking {
        repeat(12) { iteration ->
            val starter = repository(RecordingRuntimeId("starter-$iteration"))
            val reconciler = repository(RecordingRuntimeId("reconciler-$iteration"))
            val sessionId = starter.begin("race-begin-$iteration", 2_000L + iteration * 10).sessionId
            val (activation, reconciliation) = coroutineScope {
                val activated = async(Dispatchers.Default) {
                    starter.completeStart(
                        id("race-activate-$iteration"),
                        sessionId,
                        2_001L + iteration * 10,
                    )
                }
                val repaired = async(Dispatchers.Default) {
                    reconciler.reconcileStarting(
                        id("race-reconcile-$iteration"),
                        2_002L + iteration * 10,
                    )
                }
                activated.await() to repaired.await()
            }
            val stored = requireNotNull(dao.sessionById(sessionId))
            if (activation.activated) {
                assertEquals(ReconcileStartingDisposition.NOTHING_TO_RECONCILE, reconciliation.disposition)
                assertEquals(RecordingStatus.ACTIVE, stored.status)
                assertTrue(
                    starter.stop(
                        id("race-cleanup-$iteration"),
                        sessionId,
                        2_003L + iteration * 10,
                        "TEST_CLEANUP",
                    ).stopped,
                )
            } else {
                assertEquals(ReconcileStartingDisposition.STARTING_FAILED, reconciliation.disposition)
                assertEquals(RecordingStatus.FAILED_TO_START, stored.status)
            }
        }
    }

    @Test
    fun startupReconciliationCompletesPendingStopInsteadOfFailingStart() = runBlocking {
        val repository = repository()
        val sessionId = repository.begin("pending-reconcile-begin", 3_000).sessionId
        assertTrue(
            repository.requestStop(
                id("pending-reconcile-request"),
                sessionId,
                3_001,
                "USER",
            ).requested,
        )

        val result = repository.reconcileStarting(id("pending-reconcile"), 3_999)

        assertEquals(ReconcileStartingDisposition.PENDING_STOP_COMPLETED, result.disposition)
        val stored = requireNotNull(dao.sessionById(sessionId))
        assertEquals(RecordingStatus.INTERRUPTED, stored.status)
        assertEquals(3_001L, stored.endedAt)
        assertEquals("STOP_DURING_START:USER", stored.stopReason)
        assertEquals("RECONCILED_PENDING_STOP", dao.receiptByOperationId("pending-reconcile")?.outcome)
    }

    @Test
    fun startupReconciliationRollsBackIfItsReceiptCannotCommit() = runBlocking {
        val repository = repository()
        val sessionId = repository.begin("rollback-begin", 4_000).sessionId
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_reconcile_receipt
            BEFORE INSERT ON recording_operation_receipts
            WHEN NEW.command_kind = 'RECONCILE_STARTING'
            BEGIN SELECT RAISE(ABORT, 'injected reconcile receipt failure'); END
            """.trimIndent(),
        )

        try {
            repository.reconcileStarting(id("rollback-reconcile"), 4_100)
            throw AssertionError("expected receipt failure")
        } catch (_: Exception) {
            // The transaction must restore STARTING when its durable acknowledgement cannot land.
        }
        assertEquals(RecordingStatus.STARTING, dao.sessionById(sessionId)?.status)
        assertEquals(null, dao.receiptByOperationId("rollback-reconcile"))

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_reconcile_receipt")
        assertEquals(
            ReconcileStartingDisposition.STARTING_FAILED,
            repository.reconcileStarting(id("rollback-reconcile"), 4_100).disposition,
        )
        assertEquals(RecordingStatus.FAILED_TO_START, dao.sessionById(sessionId)?.status)
    }

    private fun repository(
        runtimeId: RecordingRuntimeId = RecordingRuntimeId("room-instrumentation-runtime"),
    ) = RecordingRepository(RoomRecordingStore(dao), runtimeId = runtimeId)

    private suspend fun RecordingRepository.begin(
        operationId: String,
        startedAt: Long,
    ) = beginStart(id(operationId), startedAt, TEST_APP_VERSION)

    private suspend fun RecordingRepository.deliver(
        operationId: String,
        sessionId: Long,
        capturedAtNanos: Long,
        epochMillis: Long,
        longitude: Double,
        recordedAt: Long,
        accuracyMeters: Double = 5.0,
    ) = deliverLocation(
        operationId = id(operationId),
        sessionId = sessionId,
        rawFix = RawLocationFix(
            latitude = 25.0,
            longitude = longitude,
            horizontalAccuracyMeters = accuracyMeters,
            capturedAtElapsedRealtimeNanos = capturedAtNanos,
            epochMillis = epochMillis,
        ),
        nowElapsedRealtimeNanos = capturedAtNanos,
        recordedAtEpochMillis = recordedAt,
    )

    private fun id(value: String) = RecordingOperationId(value)

    private companion object {
        const val SECOND = 1_000_000_000L
        const val TEST_APP_VERSION = "instrumentation"
    }
}
