package app.trailveil.benchmark

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.ViewTreeObserver
import androidx.core.app.FrameMetricsAggregator
import androidx.core.util.size
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.LATEST_RECORDING_SUMMARY_QUERY
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.history.RecordingLatestSessionSummary
import app.trailveil.data.history.RoomRecordingHistoryDataSource
import app.trailveil.data.location.RawLocationFix
import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.feature.recording.RecordingDisplayState
import app.trailveil.feature.recording.toRecordingPresentation
import app.trailveil.recording.AppContainer
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in 10k/100k main-presentation subscription benchmark for P4-033. */
@RunWith(AndroidJUnit4::class)
class RecordingSummaryScaleBenchmarkTest {
    @Test
    fun oneInsertedFixKeepsMainPresentationWorkBoundedAtCanonicalScale() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Recording summary scale benchmark is opt-in",
            arguments.getString(SCALE_ARGUMENT) == "true",
        )
        assumeTrue(
            "Production fixture cleanup requires a dedicated focused instrumentation process",
            arguments.getString(DEDICATED_PROCESS_ARGUMENT) == "true",
        )

        val results = buildList {
            for (pointCount in POINT_COUNTS) add(measure(pointCount))
        }
        val small = results.first()
        val large = results.last()
        assertTrue(
            "100k summary allocation grew with canonical history: $results",
            large.allocatedBytes <= maxOf(
                MAX_PRESENTATION_ALLOCATED_BYTES,
                small.allocatedBytes * MAX_RELATIVE_ALLOCATION_MULTIPLIER,
            ),
        )
        assertTrue(
            "100k summary response time grew with canonical history: $results",
            large.responseMillis <= maxOf(
                MAX_PRESENTATION_RESPONSE_MILLIS,
                small.responseMillis * MAX_RELATIVE_TIME_MULTIPLIER,
            ),
        )
        val ui = measureProductionMainScreen(large.points)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stream",
                    results.joinToString(
                        prefix = "TrailVeil recording-summary benchmark: ",
                        separator = "; ",
                    ) { it.statusLine() } + "; ${ui.statusLine()}\n",
                )
            },
        )
    }

    private suspend fun measureProductionMainScreen(pointCount: Int): UiResult {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = TrailVeilDatabase.open(context)
        try {
            val counts = PRODUCTION_TABLES.associateWith { table ->
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            }
            assumeTrue(
                "Production UI benchmark needs a dedicated empty install; found $counts",
                counts.values.all { it == 0L },
            )
            populate(database, pointCount)
        } finally {
            database.close()
        }

        val container = AtomicReference<AppContainer?>()
        try {
            return ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val activity = scenario.requireActivity()
                val appContainer = (activity.application as TrailVeilApplication).appContainer
                container.set(appContainer)
                awaitPresentationDraw(
                    scenario = scenario,
                    expectedPointId = pointCount.toLong(),
                    expectedOutcome = INITIAL_LOCATION_OUTCOME,
                )
                val repository = appContainer.recordingRepository
                awaitPresentationDraw(
                    scenario = scenario,
                    expectedPointId = pointCount.toLong(),
                    expectedOutcome = RECOVERY_OUTCOME,
                ) {
                    repository.recover(
                        operationId = RecordingOperationId("summary-ui-recover-$pointCount"),
                        recoveredAtEpochMillis = pointCount + 1_000L,
                    )
                }

                val targetPointId = pointCount + 1L
                val drawn = CountDownLatch(1)
                val drawListener = ViewTreeObserver.OnDrawListener {
                    if (
                        activity.window.decorView.getTag(R.id.recording_presentation_latest_point_id) ==
                        targetPointId
                    ) {
                        drawn.countDown()
                    }
                }
                scenario.onActivity {
                    it.window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
                }
                val frameMetrics = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
                frameMetrics.add(activity)
                try {
                    val started = SystemClock.elapsedRealtimeNanos()
                    repository.deliverLocation(
                        operationId = RecordingOperationId(
                            "location:$SUMMARY_UI_RUNTIME_ID:$targetPointId",
                        ),
                        sessionId = SESSION_ID,
                        rawFix = RawLocationFix(
                            latitude = 25.1,
                            longitude = 121.1,
                            horizontalAccuracyMeters = 5.0,
                            capturedAtElapsedRealtimeNanos = started,
                            epochMillis = targetPointId,
                        ),
                        nowElapsedRealtimeNanos = started,
                        recordedAtEpochMillis = targetPointId,
                    )
                    assertTrue(
                        "MainActivity did not draw the inserted fix within ${MAX_PRESENTATION_RESPONSE_MILLIS}ms",
                        drawn.await(MAX_PRESENTATION_RESPONSE_MILLIS, TimeUnit.MILLISECONDS),
                    )
                    val responseMillis =
                        (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLISECOND
                    // FrameMetrics is delivered asynchronously after the matching OnDraw callback.
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    SystemClock.sleep(FRAME_METRICS_SETTLE_MILLIS)
                    val summary = summarize(frameMetrics.remove(activity))
                    assertTrue("No UI frame was measured for the summary update", summary.total > 0)
                    assertTrue(
                        "MainActivity summary draw exceeded ${MAX_PRESENTATION_RESPONSE_MILLIS}ms: " +
                            "${responseMillis}ms",
                        responseMillis <= MAX_PRESENTATION_RESPONSE_MILLIS,
                    )
                    UiResult(
                        points = pointCount,
                        responseMillis = responseMillis,
                        frameHistogramP95Millis = summary.p95Millis,
                        frames = summary.total,
                    )
                } finally {
                    frameMetrics.stop()
                    scenario.onActivity {
                        if (it.window.decorView.viewTreeObserver.isAlive) {
                            it.window.decorView.viewTreeObserver.removeOnDrawListener(drawListener)
                        }
                    }
                }
            }
        } finally {
            container.get()?.closeDatabaseForTesting()
            val deleted = context.deleteDatabase(TrailVeilDatabase.DATABASE_NAME)
            assertTrue(
                "Production UI benchmark fixture database was not removed",
                deleted || !context.getDatabasePath(TrailVeilDatabase.DATABASE_NAME).exists(),
            )
        }
    }

    private suspend fun awaitPresentationDraw(
        scenario: ActivityScenario<MainActivity>,
        expectedPointId: Long,
        expectedOutcome: String,
        action: suspend () -> Unit = {},
    ) {
        val activity = scenario.requireActivity()
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener {
            if (
                activity.window.decorView.getTag(R.id.recording_presentation_latest_point_id) ==
                expectedPointId &&
                activity.window.decorView.getTag(R.id.recording_presentation_latest_outcome) ==
                expectedOutcome
            ) {
                drawn.countDown()
            }
        }
        scenario.onActivity {
            it.window.decorView.viewTreeObserver.addOnDrawListener(listener)
            it.window.decorView.invalidate()
        }
        try {
            action()
            assertTrue(
                "MainActivity did not draw point=$expectedPointId outcome=$expectedOutcome",
                drawn.await(MAX_PRESENTATION_RESPONSE_MILLIS, TimeUnit.MILLISECONDS),
            )
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(PRESENTATION_SETTLE_MILLIS)
        } finally {
            scenario.onActivity {
                if (it.window.decorView.viewTreeObserver.isAlive) {
                    it.window.decorView.viewTreeObserver.removeOnDrawListener(listener)
                }
            }
        }
    }

    private fun ActivityScenario<MainActivity>.requireActivity(): MainActivity {
        val activity = AtomicReference<MainActivity?>()
        onActivity { activity.set(it) }
        return requireNotNull(activity.get())
    }

    private fun summarize(histograms: Array<SparseIntArray>?): FrameSummary {
        val histogram = histograms?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
            ?: return FrameSummary(total = 0, p95Millis = -1)
        var total = 0
        repeat(histogram.size) { index -> total += histogram.valueAt(index) }
        if (total == 0) return FrameSummary(total = 0, p95Millis = -1)
        val percentileRank = (total * 95 + 99) / 100
        var cumulative = 0
        var p95Millis = 0
        repeat(histogram.size) { index ->
            if (cumulative < percentileRank) {
                cumulative += histogram.valueAt(index)
                p95Millis = histogram.keyAt(index)
            }
        }
        return FrameSummary(total = total, p95Millis = p95Millis)
    }

    private suspend fun measure(pointCount: Int): ScaleResult {
        val summaryQueries = AtomicInteger(0)
        val directExecutor = Executor(Runnable::run)
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryCallback(
                { sql, _ ->
                    if (
                        sql.contains("latest_operation_outcome") &&
                        sql.contains("FROM recording_sessions s")
                    ) {
                        summaryQueries.incrementAndGet()
                    }
                },
                directExecutor,
            )
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()
        try {
            populate(database, pointCount)
            val history = RoomRecordingHistoryDataSource(database.recordingDao())
            assertBoundedQueryPlan(database)

            val update = coroutineScope {
                val emissions = Channel<RecordingLatestSessionSummary>(Channel.UNLIMITED)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    history.latestSessionSummary()
                        .filterNotNull()
                        .collect { emissions.send(it) }
                }
                try {
                    val initial = withTimeout(MAX_PRESENTATION_RESPONSE_MILLIS) {
                        emissions.receive()
                    }
                    assertEquals(pointCount.toLong(), initial.session.acceptedPointCount)
                    assertEquals(pointCount.toLong(), initial.latestAcceptedPoint?.id)
                    val queryCountBeforeInsert = summaryQueries.get()
                    Runtime.getRuntime().gc()
                    SystemClock.sleep(GC_SETTLE_MILLIS)
                    val allocatedBefore = allocatedBytes()
                    val started = SystemClock.elapsedRealtimeNanos()
                    insertOneFix(database, pointCount)
                    val updated = withTimeout(MAX_PRESENTATION_RESPONSE_MILLIS) {
                        var candidate: RecordingLatestSessionSummary
                        do {
                            candidate = emissions.receive()
                        } while (candidate.session.acceptedPointCount != pointCount + 1L)
                        candidate
                    }
                    val presentation = updated.toRecordingPresentation(stoppingSessionId = null)
                    UpdateMeasurement(
                        summary = updated,
                        presentationState = presentation.state,
                        responseMillis =
                            (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLISECOND,
                        allocatedBytes = allocatedBytes() - allocatedBefore,
                        queryCount = summaryQueries.get() - queryCountBeforeInsert,
                    )
                } finally {
                    collector.cancelAndJoin()
                    emissions.close()
                }
            }

            assertEquals(1, update.queryCount)
            assertTrue("summary allocation counter moved backwards", update.allocatedBytes >= 0L)
            assertTrue(
                "one fix allocated too much presentation work: ${update.allocatedBytes} bytes",
                update.allocatedBytes <= MAX_PRESENTATION_ALLOCATED_BYTES,
            )
            assertEquals(RecordingDisplayState.RECORDING, update.presentationState)
            assertEquals(pointCount + 1L, update.summary.latestAcceptedPoint?.id)

            // The main subscription is bounded, but opening history detail must still return every
            // canonical point with its segment boundary intact.
            val detail = requireNotNull(history.sessionDetail(SESSION_ID).first())
            assertEquals(
                pointCount + 1,
                detail.acceptedPointSegments.sumOf { it.points.size },
            )
            assertEquals(listOf(SEGMENT_ID), detail.acceptedPointSegments.map { it.segmentId })

            return ScaleResult(
                points = pointCount,
                summaryQueries = update.queryCount,
                allocatedBytes = update.allocatedBytes,
                responseMillis = update.responseMillis,
                historyDetailPoints = pointCount + 1,
            )
        } finally {
            database.close()
        }
    }

    private fun assertBoundedQueryPlan(database: TrailVeilDatabase) {
        val details = database.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $LATEST_RECORDING_SUMMARY_QUERY")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(3))
                }
            }
        assertFalse("summary query spills to a temporary sort: $details", details.any { "TEMP B-TREE" in it })
        assertTrue(
            "latest outcome is not using the session/created-at index: $details",
            details.any { "index_recording_operation_receipts_session_id_created_at" in it },
        )
        assertTrue(
            "latest point is not using the session/id index: $details",
            details.any { "index_track_points_session_id_id" in it },
        )
    }

    private fun populate(database: TrailVeilDatabase, pointCount: Int) {
        database.runInTransaction {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO recording_sessions(" +
                    "id, started_at, ended_at, status, stop_reason, distance_meters, " +
                    "accepted_point_count, rejected_point_count, created_app_version, active_slot, " +
                    "location_owner_token) VALUES($SESSION_ID, 1, NULL, 'ACTIVE', NULL, 0, " +
                    "$pointCount, 0, 'summary-benchmark', 1, 'summary-benchmark-owner')",
            )
            sqlite.execSQL(
                "INSERT INTO track_segments(" +
                    "id, session_id, sequence, started_at, ended_at, start_reason, end_reason, open_slot" +
                    ") VALUES($SEGMENT_ID, $SESSION_ID, 0, 1, NULL, 'BENCHMARK', NULL, 1)",
            )
            val point = sqlite.compileStatement(
                "INSERT INTO track_points(" +
                    "id, session_id, segment_id, sequence, timestamp, latitude, longitude, " +
                    "horizontal_accuracy) VALUES(?, $SESSION_ID, $SEGMENT_ID, ?, ?, 25.0, 121.0, 5.0)",
            )
            repeat(pointCount) { index ->
                val id = index.toLong() + 1L
                point.clearBindings()
                point.bindLong(1, id)
                point.bindLong(2, index.toLong())
                point.bindLong(3, id)
                point.executeInsert()
            }
            sqlite.execSQL(
                "INSERT INTO recording_operation_receipts(" +
                    "operation_id, command_kind, outcome, session_id, created_at" +
                    ") VALUES('summary-initial-$pointCount', 'LOCATION', " +
                    "'LOCATION_ACCEPTED_CONTINUOUS_NONE', $SESSION_ID, $pointCount)",
            )
        }
    }

    private fun insertOneFix(database: TrailVeilDatabase, priorPointCount: Int) {
        val pointId = priorPointCount + 1L
        database.runInTransaction {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO track_points(" +
                    "id, session_id, segment_id, sequence, timestamp, latitude, longitude, " +
                    "horizontal_accuracy) VALUES($pointId, $SESSION_ID, $SEGMENT_ID, " +
                    "$priorPointCount, $pointId, 25.1, 121.1, 5.0)",
            )
            sqlite.execSQL(
                "UPDATE recording_sessions SET accepted_point_count = $pointId " +
                    "WHERE id = $SESSION_ID",
            )
            sqlite.execSQL(
                "INSERT INTO recording_operation_receipts(" +
                    "operation_id, command_kind, outcome, session_id, created_at" +
                    ") VALUES('summary-after-$priorPointCount', 'LOCATION', " +
                    "'LOCATION_ACCEPTED_CONTINUOUS_NONE', $SESSION_ID, $pointId)",
            )
        }
    }

    private fun allocatedBytes(): Long =
        requireNotNull(Debug.getRuntimeStat("art.gc.bytes-allocated")) {
            "ART allocation runtime stat is unavailable"
        }.toLong()

    private data class ScaleResult(
        val points: Int,
        val summaryQueries: Int,
        val allocatedBytes: Long,
        val responseMillis: Long,
        val historyDetailPoints: Int,
    ) {
        fun statusLine(): String =
            "points=$points queries=$summaryQueries allocated=${allocatedBytes}B " +
                "response=${responseMillis}ms historyPoints=$historyDetailPoints"
    }

    private data class UpdateMeasurement(
        val summary: RecordingLatestSessionSummary,
        val presentationState: RecordingDisplayState,
        val responseMillis: Long,
        val allocatedBytes: Long,
        val queryCount: Int,
    )

    private data class UiResult(
        val points: Int,
        val responseMillis: Long,
        val frameHistogramP95Millis: Int,
        val frames: Int,
    ) {
        fun statusLine(): String =
            "productionUiPoints=$points drawResponse=${responseMillis}ms " +
                "frameHistogramP95=${frameHistogramP95Millis}ms frames=$frames " +
                "emulatorEngineeringEvidenceOnly"
    }

    private data class FrameSummary(val total: Int, val p95Millis: Int)

    private companion object {
        const val SCALE_ARGUMENT = "trailveilRecordingSummaryScale"
        const val DEDICATED_PROCESS_ARGUMENT = "trailveilDedicatedRecordingSummaryScale"
        val POINT_COUNTS = listOf(10_000, 100_000)
        const val SESSION_ID = 1L
        const val SEGMENT_ID = 1L
        const val SUMMARY_UI_RUNTIME_ID = "00000000-0000-0000-0000-000000000033"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val GC_SETTLE_MILLIS = 100L
        const val MAX_PRESENTATION_RESPONSE_MILLIS = 2_000L
        const val MAX_PRESENTATION_ALLOCATED_BYTES = 4L * 1024L * 1024L
        const val MAX_RELATIVE_ALLOCATION_MULTIPLIER = 3L
        const val MAX_RELATIVE_TIME_MULTIPLIER = 4L
        const val FRAME_METRICS_SETTLE_MILLIS = 100L
        const val PRESENTATION_SETTLE_MILLIS = 50L
        const val INITIAL_LOCATION_OUTCOME = "LOCATION_ACCEPTED_CONTINUOUS_NONE"
        const val RECOVERY_OUTCOME = "RECOVERED_ACTIVE"
        val PRODUCTION_TABLES = listOf(
            "recording_sessions",
            "track_segments",
            "track_points",
            "recording_operation_receipts",
        )
    }
}
