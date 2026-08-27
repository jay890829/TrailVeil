package app.trailveil.googlepoc

import android.database.Cursor
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.View
import android.view.ViewGroup
import androidx.core.app.FrameMetricsAggregator
import androidx.core.util.size
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.TrailVeilApplication
import app.trailveil.benchmark.ScaleBenchmarkFixture
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.history.RoomRecordingHistoryDataSource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in engineering evidence for the Google TileOverlay path. Run only on a dedicated empty
 * install with a valid external PoC key, for example:
 *
 * `-PtrailveilAndroidTestBuildType=googlePoc`
 * `-Pandroid.testInstrumentationRunnerArguments.trailveilGoogleFogScale=true`
 * `-Pandroid.testInstrumentationRunnerArguments.trailveilGoogleFogPointCount=100000`
 *
 * The test deliberately has no POCO threshold. Its output is a coordinate-free benchmark record;
 * designated-device acceptance remains the V02-007 gate.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogScaleBenchmarkTest {
    @Test
    fun googleFogScaleAndProviderFailurePreserveCanonicalHistory() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Google fog scale benchmark is opt-in; pass trailveilGoogleFogScale=true",
            arguments.getString(SCALE_ARGUMENT) == "true",
        )
        val requestedPointCount = arguments.getString(POINT_COUNT_ARGUMENT)
            ?.toIntOrNull()
        assumeTrue(
            "Google fog scale benchmark needs trailveilGoogleFogPointCount=10000 or 100000",
            requestedPointCount == POINTS_10K || requestedPointCount == POINTS_100K,
        )
        val pointCount = requireNotNull(requestedPointCount)
        assumeTrue(
            "Google PoC runtime key is not configured; host builds remain compile-only",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prepareDedicatedCanonicalFixture(pointCount)

        val initialAirplaneMode = readAirplaneMode()
        var scenario: ActivityScenario<GoogleMapsPocActivity>? = null
        var offlineScenario: ActivityScenario<GoogleMapsPocActivity>? = null
        var frameMetrics: FrameMetricsAggregator? = null
        var pssSampler: PssSampler? = null
        var frameSummary: FrameSummary? = null
        var startupMillis = -1L
        var fogRefreshMillis = emptyList<Long>()
        var peakPssKiB = 0L
        var livePointRefreshMillis = -1L
        var providerFailureSnapshotBefore: CanonicalSnapshot? = null
        var providerFailureSnapshotAfter: CanonicalSnapshot? = null
        try {
            if (initialAirplaneMode) setAirplaneMode(enabled = false)
            pssSampler = PssSampler()
            val startupStartedNanos = SystemClock.elapsedRealtimeNanos()
            scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            val activity = requireActivity(activeScenario)
            val mapView = awaitMapView(activeScenario)
            val map = awaitGoogleMap(activeScenario, mapView)
            awaitFallbackGone(activeScenario, requireVisibleTransition = false)
            startupMillis = elapsedMillisSince(startupStartedNanos)

            frameMetrics = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
            frameMetrics?.add(activity)
            fogRefreshMillis = runCameraSamples(activeScenario, map)
            livePointRefreshMillis = insertLivePointAndAwaitFog(activeScenario, pointCount)
            val sampledFrames = summarize(
                frameMetrics?.remove(activity)?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX),
            )
            frameSummary = sampledFrames
            assertTrue("Google fog pan/zoom collected no UI frames", sampledFrames.total > 0)
            assertTrue("Google fog frame p95 was invalid", sampledFrames.p95Millis >= 0)
            assertTrue(
                "Google fog frozen-frame ratio was invalid",
                sampledFrames.frozenRatio in 0.0..1.0,
            )
            frameMetrics?.stop()
            frameMetrics = null

            // Close the online provider before taking the failure snapshot. The database remains
            // owned by Room and is never cleared or rewritten by this harness.
            activeScenario.close()
            scenario = null
            peakPssKiB = requireNotNull(pssSampler).finish()
            pssSampler = null

            providerFailureSnapshotBefore = readCanonicalSnapshot(context)
            setAirplaneMode(enabled = true)
            offlineScenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
            val activeOfflineScenario = requireNotNull(offlineScenario)
            val offlineSurface = awaitStableOfflineFallback(activeOfflineScenario)
            assertTrue(
                "Offline Google PoC launch attached a provider map instead of local fallback",
                !offlineSurface.mapViewPresent,
            )
            providerFailureSnapshotAfter = readCanonicalSnapshot(context)
            assertTrue(
                "Canonical Room digest changed across offline provider failure",
                providerFailureSnapshotBefore?.canonicalDigest ==
                    providerFailureSnapshotAfter?.canonicalDigest,
            )
            assertEquals(
                "Canonical Room table counts changed across offline provider failure",
                providerFailureSnapshotBefore?.tableCounts,
                providerFailureSnapshotAfter?.tableCounts,
            )
            assertTrue(
                "History latest summary changed across offline provider failure",
                providerFailureSnapshotBefore?.history?.summaryDigest ==
                    providerFailureSnapshotAfter?.history?.summaryDigest,
            )
            assertTrue(
                "History session query changed across offline provider failure",
                providerFailureSnapshotBefore?.history?.sessionDigest ==
                    providerFailureSnapshotAfter?.history?.sessionDigest,
            )
            assertEquals(
                "History segment count changed across offline provider failure",
                providerFailureSnapshotBefore?.history?.segmentCount,
                providerFailureSnapshotAfter?.history?.segmentCount,
            )
            assertTrue(
                "History segment query changed across offline provider failure",
                providerFailureSnapshotBefore?.history?.segmentDigest ==
                    providerFailureSnapshotAfter?.history?.segmentDigest,
            )
        } finally {
            frameMetrics?.stop()
            pssSampler?.finish()
            offlineScenario?.close()
            scenario?.close()
            // The shell failure simulation is reversible and must not leak into the next test.
            setAirplaneMode(initialAirplaneMode)
        }

        val frames = requireNotNull(frameSummary)
        assertTrue("Google fog startup timing was invalid", startupMillis >= 0L)
        assertTrue(
            "Google fog refresh samples were incomplete",
            fogRefreshMillis.size == CAMERA_STEPS.size,
        )
        assertTrue("Google fog refresh p95 was invalid", p95(fogRefreshMillis) >= 0L)
        assertTrue("Google fog refresh max was invalid", fogRefreshMillis.maxOrNull() ?: -1L >= 0L)
        val status = buildString {
            append("TrailVeil Google fog scale engineering evidence ")
            append("seed=${ScaleBenchmarkFixture.SEED} ")
            append("points=$pointCount segments=${ScaleBenchmarkFixture.SEGMENT_COUNT} ")
            append("startupMs=$startupMillis ")
            append("fogRefreshP95Ms=${p95(fogRefreshMillis)} ")
            append("fogRefreshMaxMs=${fogRefreshMillis.maxOrNull()} ")
            append("livePointRefreshMs=$livePointRefreshMillis ")
            append("frameP95Ms=${frames.p95Millis} ")
            append("frozenRatio=${"%.4f".format(Locale.US, frames.frozenRatio)} ")
            append("frames=${frames.total} peakPssKiB=$peakPssKiB ")
            append("providerFailure=offline localFallback=true ")
            append("canonicalDigestUnchanged=true historySummaryUnchanged=true ")
            append("historySessionUnchanged=true historySegmentsUnchanged=true ")
            append("engineeringEvidenceOnly; no POCO gate applied\n")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", status) },
        )
    }

    private fun prepareDedicatedCanonicalFixture(pointCount: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = TrailVeilDatabase.open(context)
        try {
            val counts = database.openHelper.readableDatabase.let { sqlite ->
                CANONICAL_TABLES.associateWith { table ->
                    sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    }
                }
            }
            assumeTrue(
                "Google fog benchmark needs a dedicated empty app install",
                counts.values.all { count -> count == 0L },
            )
            ScaleBenchmarkFixture.populateCanonicalDataset(database, pointCount)
        } finally {
            database.close()
        }
    }

    private fun runCameraSamples(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: GoogleMap,
    ): List<Long> = buildList {
        CAMERA_STEPS.forEachIndexed { index, step ->
            val idle = CountDownLatch(1)
            val startedNanos = SystemClock.elapsedRealtimeNanos()
            scenario.onActivity { activity ->
                activity.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                        idle.countDown()
                    }
                }
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(step.location, step.zoom),
                    CAMERA_ANIMATION_MILLIS,
                    null,
                )
            }
            awaitFallbackVisible(scenario)
            assertTrue(
                "Google camera did not settle for pan/zoom sample ${index + 1}",
                idle.await(CAMERA_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            awaitFallbackGone(scenario, requireVisibleTransition = false)
            add(elapsedMillisSince(startedNanos))
        }
    }

    private fun insertLivePointAndAwaitFog(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        originalPointCount: Int,
    ): Long {
        val installed = CountDownLatch(1)
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        val databaseRef = AtomicReference<TrailVeilDatabase?>()
        scenario.onActivity { activity ->
            activity.callbacks = object : GoogleMapsPocCallbacks {
                override fun onCanonicalFogInstalled(generation: Long) {
                    installed.countDown()
                }
            }
            val application = activity.application as TrailVeilApplication
            databaseRef.set(application.appContainer.databaseForTesting())
        }
        val database = requireNotNull(databaseRef.get())
        val sqlite = database.openHelper.writableDatabase
        val latest = sqlite.query(
            """
            SELECT session_id, segment_id, sequence, timestamp, latitude, longitude
            FROM track_points ORDER BY id DESC LIMIT 1
            """.trimIndent(),
        ).use { cursor ->
            check(cursor.moveToFirst())
            LatestPoint(
                sessionId = cursor.getLong(0),
                segmentId = cursor.getLong(1),
                sequence = cursor.getLong(2),
                timestamp = cursor.getLong(3),
                latitude = cursor.getDouble(4),
                longitude = cursor.getDouble(5),
            )
        }
        database.runInTransaction {
            val inserted = sqlite.insert(
                "track_points",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("session_id", latest.sessionId)
                    put("segment_id", latest.segmentId)
                    put("sequence", latest.sequence + 1L)
                    put("timestamp", latest.timestamp + 1L)
                    put("latitude", latest.latitude)
                    put("longitude", latest.longitude)
                    put("horizontal_accuracy", 5.0)
                    put("is_mock", 0)
                },
            )
            check(inserted > 0L) { "live canonical point insert failed" }
            sqlite.execSQL(
                """
                UPDATE recording_sessions
                SET accepted_point_count = accepted_point_count + 1,
                    distance_meters = distance_meters + 1.0,
                    ended_at = ended_at + 1
                WHERE id = ?
                """.trimIndent(),
                arrayOf(latest.sessionId),
            )
            sqlite.execSQL(
                "UPDATE track_segments SET ended_at = ended_at + 1 WHERE id = ?",
                arrayOf(latest.segmentId),
            )
        }
        assertTrue(
            "Persisted point $originalPointCount did not produce a new canonical fog generation",
            installed.await(LIVE_POINT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        awaitFallbackGone(scenario, requireVisibleTransition = false)
        return elapsedMillisSince(startedNanos)
    }

    private fun awaitFallbackGone(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        requireVisibleTransition: Boolean,
    ) {
        var sawVisible = false
        repeat(SURFACE_POLL_COUNT) {
            val surface = readSurface(scenario)
            sawVisible = sawVisible || surface.fallbackVisibility == View.VISIBLE
            if (surface.fallbackVisibility == View.GONE) {
                if (requireVisibleTransition) {
                    assertTrue("Google PoC fallback did not show before readiness", sawVisible)
                }
                return
            }
            SystemClock.sleep(SURFACE_POLL_MILLIS)
        }
        val diagnostic = AtomicReference<GoogleFogInstallDiagnostic>()
        scenario.onActivity { activity ->
            diagnostic.set(activity.fogInstallDiagnosticForTesting())
        }
        val state = requireNotNull(diagnostic.get())
        error(
            "Google PoC fallback did not hide after canonical fog readiness " +
                "phase=${state.phase} pendingTiles=${state.pendingTileCount} " +
                "refreshFailure=${state.refreshFailure} " +
                "clearFailureClass=${state.clearFailureClass} " +
                "refreshGeneration=${state.refreshGeneration} " +
                "refreshStarted=${state.refreshStarted} " +
                "refreshPublished=${state.refreshPublished} " +
                "visualRequiredTiles=${state.visualRequiredTileCount} " +
                "visualVerifiedTiles=${state.visualVerifiedTileCount} " +
                "snapshotAttempt=${state.snapshotAttempt} " +
                "visualOffScreenOnlyTiles=${state.visualOffScreenOnlyTileCount} " +
                "visualMismatchedTiles=${state.visualMismatchedTileCount} " +
                "visualMinimumOnScreenProbes=${state.visualMinimumOnScreenProbeCount}",
        )
    }

    private fun awaitFallbackVisible(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): SurfaceObservation {
        repeat(SURFACE_POLL_COUNT) {
            val surface = readSurface(scenario)
            if (surface.fallbackVisibility == View.VISIBLE) return surface
            SystemClock.sleep(SURFACE_POLL_MILLIS)
        }
        error("Google PoC local fallback did not become visible")
    }

    /** Avoids accepting the transient loading cover before startup has decided the offline path. */
    private fun awaitStableOfflineFallback(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): SurfaceObservation {
        var consecutiveClosedFrames = 0
        repeat(SURFACE_POLL_COUNT) {
            val surface = readSurface(scenario)
            if (surface.fallbackVisibility == View.VISIBLE && !surface.mapViewPresent) {
                consecutiveClosedFrames += 1
                if (consecutiveClosedFrames >= OFFLINE_STABLE_POLL_COUNT) return surface
            } else {
                consecutiveClosedFrames = 0
            }
            SystemClock.sleep(SURFACE_POLL_MILLIS)
        }
        error("Google PoC offline fallback did not remain stably fail-closed")
    }

    private fun awaitMapView(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): MapView {
        repeat(MAP_VIEW_POLL_COUNT) {
            val surface = readSurface(scenario)
            if (surface.mapView != null) return requireNotNull(surface.mapView)
            SystemClock.sleep(MAP_VIEW_POLL_MILLIS)
        }
        error("Google PoC MapView was not attached")
    }

    private fun awaitGoogleMap(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        mapView: MapView,
    ): GoogleMap {
        val ready = CountDownLatch(1)
        val map = AtomicReference<GoogleMap?>()
        scenario.onActivity {
            mapView.getMapAsync {
                map.set(it)
                ready.countDown()
            }
        }
        assertTrue(
            "Google PoC map did not become ready",
            ready.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return requireNotNull(map.get())
    }

    private fun readSurface(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): SurfaceObservation {
        val result = AtomicReference<SurfaceObservation?>()
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val fallback = decor.findTaggedView(FALLBACK_TAG)
            val mapView = decor.findGoogleMapView()
            result.set(
                SurfaceObservation(
                    fallbackVisibility = fallback?.visibility,
                    mapView = mapView,
                    mapViewPresent = mapView != null,
                ),
            )
        }
        return requireNotNull(result.get())
    }

    private fun requireActivity(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): GoogleMapsPocActivity {
        val activity = AtomicReference<GoogleMapsPocActivity?>()
        scenario.onActivity { activity.set(it) }
        return requireNotNull(activity.get())
    }

    private fun readCanonicalSnapshot(context: android.content.Context): CanonicalSnapshot {
        val database = TrailVeilDatabase.open(context)
        try {
            val sqlite = database.openHelper.readableDatabase
            val tableCounts = CANONICAL_TABLES.associateWith { table ->
                sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
            }
            val history = readHistorySnapshot(database)
            check(runBlocking { database.recordingDao().activeSession() == null }) {
                "Google fog benchmark left an active recording"
            }
            return CanonicalSnapshot(
                canonicalDigest = digestCanonicalTables(sqlite),
                tableCounts = tableCounts,
                history = history,
            )
        } finally {
            database.close()
        }
    }

    private fun readHistorySnapshot(database: TrailVeilDatabase): HistorySnapshot {
        val dataSource = RoomRecordingHistoryDataSource(database.recordingDao())
        val sessions = runBlocking { dataSource.sessions().first() }
        val summary = runBlocking { dataSource.latestSessionSummary().first() }
        check(summary != null && sessions.size == 1 && sessions.single().id == summary.session.id) {
            "Google fog benchmark history query returned an unexpected session shape"
        }
        val relation = runBlocking { database.recordingDao().sessionWithSegments(summary.session.id) }
        check(relation != null) { "Google fog benchmark history segments were unavailable" }
        val segments = relation.segments.sortedWith(
            compareBy({ it.sequence }, { it.id }),
        )
        return HistorySnapshot(
            summaryDigest = digestStrings(
                listOf(
                    summary.session.id,
                    summary.session.startedAt,
                    summary.session.endedAt,
                    summary.session.status,
                    summary.session.stopReason,
                    summary.session.distanceMeters,
                    summary.session.acceptedPointCount,
                    summary.session.rejectedPointCount,
                    summary.latestOperationOutcome?.value,
                    summary.latestAcceptedPoint?.id,
                    summary.latestAcceptedPoint?.sequence,
                    summary.latestAcceptedPoint?.timestamp,
                    summary.latestAcceptedPoint?.latitude,
                    summary.latestAcceptedPoint?.longitude,
                    summary.locationOwnerToken,
                    summary.sessionLastAcceptedPointAt,
                ),
            ),
            sessionDigest = digestStrings(sessions.map { session ->
                listOf(
                    session.id,
                    session.startedAt,
                    session.endedAt,
                    session.status,
                    session.stopReason,
                    session.distanceMeters,
                    session.acceptedPointCount,
                    session.rejectedPointCount,
                )
            }.flatten()),
            segmentCount = segments.size,
            segmentDigest = digestStrings(segments.flatMap { segment ->
                listOf(
                    segment.id,
                    segment.sequence,
                    segment.startedAt,
                    segment.endedAt,
                    segment.startReason,
                    segment.endReason,
                )
            }),
        )
    }

    private fun digestCanonicalTables(sqlite: androidx.sqlite.db.SupportSQLiteDatabase): String {
        val digest = MessageDigest.getInstance("SHA-256")
        CANONICAL_TABLES.forEach { table ->
            updateDigest(digest, table)
            sqlite.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                cursor.columnNames.forEach { column -> updateDigest(digest, column) }
                while (cursor.moveToNext()) {
                    updateDigest(digest, "row")
                    repeat(cursor.columnCount) { index ->
                        updateDigest(digest, cursorValue(cursor, index))
                    }
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun cursorValue(cursor: Cursor, index: Int): String = when {
        cursor.isNull(index) -> "<null>"
        cursor.getType(index) == Cursor.FIELD_TYPE_BLOB ->
            cursor.getBlob(index).joinToString(separator = ",")
        else -> cursor.getString(index)
    }

    private fun digestStrings(values: List<Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value -> updateDigest(digest, value?.toString() ?: "<null>") }
        return digest.digest().toHex()
    }

    private fun updateDigest(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun readAirplaneMode(): Boolean =
        shell("settings get global airplane_mode_on").trim() == "1"

    private fun setAirplaneMode(enabled: Boolean) {
        shell("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
        SystemClock.sleep(NETWORK_SETTLE_MILLIS)
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return try {
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } finally {
            descriptor.close()
        }
    }

    private fun elapsedMillisSince(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / NANOS_PER_MILLISECOND

    private fun summarize(histogram: SparseIntArray?): FrameSummary {
        if (histogram == null) return FrameSummary(total = 0, p95Millis = -1, frozenRatio = 0.0)
        var total = 0
        var frozen = 0
        repeat(histogram.size) { index ->
            val durationMillis = histogram.keyAt(index)
            val count = histogram.valueAt(index)
            total += count
            if (durationMillis >= FROZEN_FRAME_MILLIS) frozen += count
        }
        if (total == 0) return FrameSummary(total = 0, p95Millis = -1, frozenRatio = 0.0)
        val percentileRank = (total * 95 + 99) / 100
        var cumulative = 0
        var p95Millis = 0
        repeat(histogram.size) { index ->
            if (cumulative < percentileRank) {
                cumulative += histogram.valueAt(index)
                p95Millis = histogram.keyAt(index)
            }
        }
        return FrameSummary(total, p95Millis, frozen.toDouble() / total)
    }

    private fun p95(samples: List<Long>): Long {
        val sorted = samples.sorted()
        return sorted[(sorted.size * 95 + 99) / 100 - 1]
    }

    private class PssSampler {
        private val running = AtomicBoolean(true)
        private val peakPssKiB = AtomicLong(Debug.getPss().toLong())
        private val worker = Thread({
            while (running.get()) {
                record()
                try {
                    Thread.sleep(PSS_SAMPLE_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    // The flag is checked at the top of the next bounded iteration.
                }
            }
        }, "trailveil-google-fog-pss").also(Thread::start)

        private fun record() {
            val observed = Debug.getPss().toLong()
            peakPssKiB.updateAndGet { previous -> maxOf(previous, observed) }
        }

        fun finish(): Long {
            running.set(false)
            worker.interrupt()
            worker.join(PSS_JOIN_TIMEOUT_MILLIS)
            check(!worker.isAlive) { "Google fog PSS sampler did not stop" }
            record()
            return peakPssKiB.get()
        }
    }

    private fun View.findTaggedView(expectedTag: String): View? {
        if (tag == expectedTag) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findTaggedView(expectedTag)?.let { return it }
        }
        return null
    }

    private fun View.findGoogleMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findGoogleMapView()?.let { return it }
        }
        return null
    }

    private data class SurfaceObservation(
        val fallbackVisibility: Int?,
        val mapView: MapView?,
        val mapViewPresent: Boolean,
    )

    private data class LatestPoint(
        val sessionId: Long,
        val segmentId: Long,
        val sequence: Long,
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double,
    )

    private data class CanonicalSnapshot(
        val canonicalDigest: String,
        val tableCounts: Map<String, Long>,
        val history: HistorySnapshot,
    )

    private data class HistorySnapshot(
        val summaryDigest: String,
        val sessionDigest: String,
        val segmentCount: Int,
        val segmentDigest: String,
    )

    private data class FrameSummary(
        val total: Int,
        val p95Millis: Int,
        val frozenRatio: Double,
    )

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.US, byte)
    }

    private companion object {
        const val SCALE_ARGUMENT = "trailveilGoogleFogScale"
        const val POINT_COUNT_ARGUMENT = "trailveilGoogleFogPointCount"
        const val POINTS_10K = 10_000
        const val POINTS_100K = 100_000
        const val FALLBACK_TAG = "trailveil_google_poc_fallback"
        const val CAMERA_ANIMATION_MILLIS = 250
        const val CAMERA_IDLE_TIMEOUT_SECONDS = 10L
        const val LIVE_POINT_TIMEOUT_SECONDS = 15L
        const val MAP_READY_TIMEOUT_SECONDS = 30L
        const val MAP_VIEW_POLL_COUNT = 100
        const val MAP_VIEW_POLL_MILLIS = 100L
        const val SURFACE_POLL_COUNT = 300
        const val SURFACE_POLL_MILLIS = 100L
        const val OFFLINE_STABLE_POLL_COUNT = 5
        const val NETWORK_SETTLE_MILLIS = 1_000L
        const val FROZEN_FRAME_MILLIS = 700
        const val PSS_SAMPLE_INTERVAL_MILLIS = 25L
        const val PSS_JOIN_TIMEOUT_MILLIS = 2_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L

        val CAMERA_STEPS = listOf(
            CameraStep(LatLng(25.0280, 121.5000), 12.0f),
            CameraStep(LatLng(25.0420, 121.5350), 13.5f),
            CameraStep(LatLng(25.0660, 121.5400), 14.5f),
            CameraStep(LatLng(25.0380, 121.5100), 13.0f),
            CameraStep(LatLng(25.0310, 121.5280), 12.5f),
            CameraStep(LatLng(25.0550, 121.5650), 14.0f),
            CameraStep(LatLng(25.0180, 121.5450), 13.5f),
            CameraStep(LatLng(25.0750, 121.5150), 12.0f),
            CameraStep(LatLng(25.0450, 121.5750), 14.5f),
            CameraStep(LatLng(25.0100, 121.5200), 13.0f),
            CameraStep(LatLng(25.0650, 121.5550), 12.5f),
            CameraStep(LatLng(25.0350, 121.4900), 14.0f),
            CameraStep(LatLng(25.0800, 121.5450), 13.5f),
            CameraStep(LatLng(25.0220, 121.5750), 12.0f),
            CameraStep(LatLng(25.0500, 121.5050), 14.5f),
            CameraStep(LatLng(25.0150, 121.5550), 13.0f),
            CameraStep(LatLng(25.0700, 121.5000), 12.5f),
            CameraStep(LatLng(25.0400, 121.5600), 14.0f),
            CameraStep(LatLng(25.0250, 121.5150), 13.5f),
            CameraStep(LatLng(25.0600, 121.5700), 12.0f),
        ).also { steps -> require(steps.size >= 20) }

        val CANONICAL_TABLES = listOf(
            "recording_sessions",
            "track_segments",
            "track_points",
            "track_point_cells",
            "recording_operation_receipts",
            "recording_location_receipt_windows",
            "recording_location_receipt_retention_states",
        )
    }

    private data class CameraStep(val location: LatLng, val zoom: Float)
}
