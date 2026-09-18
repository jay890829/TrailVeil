package app.trailveil.benchmark

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.TrailVeilApplication
import app.trailveil.harness.DemoExplorationSeeder
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Complete ordinary startup once, then measure without seeding, clearing, stopping or changing preferences. */
internal class ExistingDemoFrameFixture : Closeable {
    private val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as TrailVeilApplication).appContainer
    private val database = container.databaseForTesting()
    private val original: Map<String, Long>
    val description: String

    init {
        val preStartup = snapshot()
        report("pre-startup", preStartup)
        val demoSessions = count("SELECT COUNT(*) FROM recording_sessions WHERE stop_reason = ?",
            arrayOf(DemoExplorationSeeder.DEMO_STOP_REASON))
        val demoPoints = count("SELECT COUNT(*) FROM track_points p JOIN recording_sessions s " +
            "ON s.id = p.session_id WHERE s.stop_reason = ? AND p.is_mock = 1",
            arrayOf(DemoExplorationSeeder.DEMO_STOP_REASON))
        assertEquals("Existing demo session load must be exact", 200L, demoSessions)
        assertEquals("Existing demo point load must be exact", 204800L, demoPoints)
        assertEquals("Never benchmark while a recording is starting or active", 0L,
            count("SELECT COUNT(*) FROM recording_sessions WHERE status IN ('STARTING','ACTIVE')"))
        // A normal first Activity would create this process-once no-op receipt too.
        // Validate that exact operation, then keep it outside the immutable measurement baseline.
        val stateBeforeStartup = runBlocking(Dispatchers.IO) { container.recordingRepository.state() }
        assertTrue("Cannot reconcile an active recording in a frame fixture",
            stateBeforeStartup.lifecycle !in setOf(RecordingLifecycle.STARTING, RecordingLifecycle.ACTIVE))
        val startup = runBlocking(Dispatchers.IO) {
            withTimeout(20_000L) { container.reconcileRecordingStartup() }
        }
        assertEquals("NOTHING_TO_RECONCILE", startup.disposition.name)
        assertEquals("Ordinary startup changed the existing terminal repository state", stateBeforeStartup, startup.state)
        runBlocking(Dispatchers.IO) {
            database.openHelper.readableDatabase.query(
                "SELECT command_kind, outcome, session_id FROM recording_operation_receipts WHERE operation_id = ?",
                arrayOf(startup.operationId.value),
            ).use { cursor ->
                assertTrue("Missing ordinary startup receipt", cursor.moveToFirst())
                assertEquals("RECONCILE_STARTING", cursor.getString(0))
                assertEquals("NOTHING_TO_RECONCILE", cursor.getString(1))
                assertTrue("Startup unexpectedly affected a session", cursor.isNull(2))
                assertTrue("Duplicate startup operation receipt", !cursor.moveToNext())
            }
        }
        original = snapshot()
        val receiptKey = "recording_operation_receipts.count"
        assertEquals("Startup changed user or derived cell data",
            preStartup.filterKeys { it != receiptKey }, original.filterKeys { it != receiptKey })
        assertTrue("Unexpected receipt writes during ordinary startup",
            original.getValue(receiptKey) - preStartup.getValue(receiptKey) in 0L..1L)
        description = "dataset=existing-demo-v03-013 demoSessions=$demoSessions demoPoints=$demoPoints " +
            "totalSessions=${original.getValue("recording_sessions.count")} " +
            "points=${original.getValue("track_points.count")}"
        report("baseline-after-startup", original)
    }

    fun assertUnchanged(phase: String) {
        val current = snapshot()
        report(phase, current)
        assertEquals("Frame benchmark changed canonical aggregates", original, current)
    }

    override fun close() = assertUnchanged("after-scenario-close")

    private fun count(sql: String, args: Array<out Any> = emptyArray()): Long = runBlocking(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(sql, args).use {
            check(it.moveToFirst()); it.getLong(0)
        }
    }

    private fun snapshot(): Map<String, Long> = runBlocking(Dispatchers.IO) {
        val fields = buildList {
            for (table in listOf("recording_sessions", "track_segments", "track_points", "track_point_cells",
                "recording_operation_receipts", "recording_location_receipt_windows", "recording_location_receipt_retention_states")) {
                add("$table.count" to "(SELECT COUNT(*) FROM $table)")
                if (table in setOf("recording_sessions", "track_segments", "track_points")) {
                    add("$table.maxId" to "(SELECT COALESCE(MAX(id),0) FROM $table)")
                }
            }
        }
        // One SQLite read snapshot, not separately interleavable table reads.
        database.openHelper.readableDatabase.query("SELECT " + fields.joinToString { it.second }).use { cursor ->
            check(cursor.moveToFirst())
            fields.mapIndexed { index, field -> field.first to cursor.getLong(index) }.toMap()
        }
    }

    private fun report(phase: String, values: Map<String, Long>) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "Z_FRAME_CANONICAL phase=$phase aggregates=$values\n")
        })
    }

    companion object {
        const val ARGUMENT = "trailveilUiScaleExistingData"

        /** Large notice cards invalidate the frame fixture instead of being hidden or excluded. */
        @OptIn(ExperimentalComposeUiApi::class)
        fun noNoticeCards(view: View): Boolean {
            var roots = 0
            var notice = false
            val tags = setOf("recording_entry_location_notice", "recording_entry_notification_notice",
                "recording_entry_start_notice", "recording_entry_recording_state", "recording_entry_background_start_notice")
            fun nodes(node: SemanticsNode) {
                if (node.config.getOrNull(SemanticsProperties.TestTag) in tags && !node.boundsInWindow.isEmpty) notice = true
                node.children.forEach(::nodes)
            }
            fun views(current: View) {
                if (current is ViewRootForTest) { roots++; nodes(current.semanticsOwner.unmergedRootSemanticsNode) }
                if (current is ViewGroup) for (i in 0 until current.childCount) views(current.getChildAt(i))
            }
            views(view.rootView)
            assertTrue("No live Compose root for frame precondition", roots > 0)
            return !notice
        }
    }
}
