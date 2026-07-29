package io.github.jay890829.trailveil.data.recording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jay890829.trailveil.data.db.RecordingDao
import io.github.jay890829.trailveil.data.db.RecordingStatus
import io.github.jay890829.trailveil.data.db.TrailVeilDatabase
import io.github.jay890829.trailveil.data.location.RawLocationFix
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
