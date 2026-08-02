package app.trailveil.benchmark

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.ViewportTrackReadModel
import app.trailveil.map.fog.FogMemoryTileCache
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.FogViewportRender
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.GeoPoint
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, reproducible device scale benchmark. Run only with
 * `-Pandroid.testInstrumentationRunnerArguments.trailveilScale=true`.
 *
 * This measures canonical Room/query and fog-core work. Production MapLibre frame/lifecycle
 * measurements are deliberately isolated in [UiScaleBenchmarkTest].
 */
@RunWith(AndroidJUnit4::class)
class ScaleBenchmarkTest {
    @Test
    fun canonicalRoomAndFogScaleAtTenAndOneHundredThousandPoints() = runBlocking {
        assumeTrue(
            "Scale benchmark is opt-in; pass trailveilScale=true",
            InstrumentationRegistry.getArguments().getString(SCALE_ARGUMENT) == "true",
        )

        val results = buildList {
            listOf(POINTS_10K, POINTS_100K).forEach { pointCount ->
                add(measureDataset(pointCount))
            }
        }
        val stress = results.single { it.pointCount == POINTS_100K }
        assertTrue(
            "100k peak process PSS exceeded 250 MiB: ${stress.peakPssKiB} KiB",
            stress.peakPssKiB <= MAX_PSS_KIB,
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stream",
                    results.joinToString(
                        prefix = "TrailVeil scale benchmark seed=${ScaleBenchmarkFixture.SEED}: ",
                        separator = "; ",
                    ) {
                        it.toStatusLine()
                    } + "; production UI metrics are reported separately\n",
                )
            },
        )
    }

    private suspend fun measureDataset(pointCount: Int): ScaleResult {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailVeilDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(TrailVeilDatabase.invariantCallback)
            .build()
        val peakPssKiB = AtomicLong(Debug.getPss().toLong())
        val sampler = Job()
        val samplerJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + sampler).launch {
            while (true) {
                peakPssKiB.recordPss()
                delay(PSS_SAMPLE_INTERVAL_MILLIS)
            }
        }
        var result: ScaleResult? = null
        try {
            ScaleBenchmarkFixture.populateCanonicalDataset(database, pointCount)
            val dao = database.recordingDao()
            val dataSource = ViewportTrackDataSource(RoomViewportTrackPointReader(dao))
            val bounds = ViewportBounds(25.0, 25.1, 121.4, 121.7)
            val style = FogRenderStyle()
            val request = FogViewportRequest(GeoPoint(25.05, 121.55), mapZoom = 14.0)

            // Fixed preheat separates compilation/allocation noise from the sampled work.
            validateCanonicalRead(dataSource.read(bounds), pointCount)
            val coldPipeline = newPipeline(style)
            val coldCoordinator = FogViewportCoordinator(dataSource, coldPipeline, style)
            val expectedFog = coldCoordinator.render(request)
            validateFogRender(expectedFog, style)

            val bboxSamples = samples(
                action = { dataSource.read(bounds) },
                validate = { read -> validateCanonicalRead(read, pointCount) },
            )
            val rebuildSamples = samples(
                action = {
                    FogViewportCoordinator(dataSource, newPipeline(style), style).render(request)
                },
                validate = { render -> validateFogRender(render, style, expectedFog) },
            )
            val warmCoordinator = FogViewportCoordinator(dataSource, newPipeline(style), style)
            validateFogRender(warmCoordinator.render(request), style, expectedFog)
            val warmSamples = samples(
                action = { warmCoordinator.render(request) },
                validate = { render -> validateFogRender(render, style, expectedFog) },
            )
            result = ScaleResult(
                pointCount = pointCount,
                bboxP95Millis = p95(bboxSamples),
                canonicalRebuildP95Millis = p95(rebuildSamples),
                warmDerivedCacheP95Millis = p95(warmSamples),
                peakPssKiB = 0L,
            )
        } finally {
            database.close()
            peakPssKiB.recordPss()
            sampler.cancel()
            samplerJob.cancelAndJoin()
        }
        return requireNotNull(result).copy(peakPssKiB = peakPssKiB.get())
    }

    private fun AtomicLong.recordPss() {
        val observed = Debug.getPss().toLong()
        updateAndGet { previous -> maxOf(previous, observed) }
    }

    private fun validateCanonicalRead(read: ViewportTrackReadModel, pointCount: Int) {
        check(read.segments.size == ScaleBenchmarkFixture.SEGMENT_COUNT) {
            "Expected ${ScaleBenchmarkFixture.SEGMENT_COUNT} canonical segments, " +
                "found ${read.segments.size}"
        }
        val actualPoints = read.segments.sumOf { segment -> segment.points.size }
        check(actualPoints == pointCount) {
            "Expected $pointCount canonical points, found $actualPoints"
        }
    }

    private fun validateFogRender(
        render: FogViewportRender,
        style: FogRenderStyle,
        expected: FogViewportRender? = null,
    ) {
        check(render.keys.isNotEmpty() && render.mosaic.tileCount == render.keys.size) {
            "Fog render did not produce the requested tile mosaic"
        }
        val alpha = render.mosaic.mask.copyAlpha()
        check(alpha.any { value -> (value.toInt() and 0xff) < style.fogAlpha }) {
            "Fog render contained no canonical reveal pixels"
        }
        check(alpha.any { value -> (value.toInt() and 0xff) == style.fogAlpha }) {
            "Fog render incorrectly revealed the entire viewport"
        }
        check(expected == null || render == expected) {
            "Fog render was not deterministic for the fixed canonical dataset"
        }
    }

    private fun newPipeline(style: FogRenderStyle) = FogTilePipeline(
        memoryCache = FogMemoryTileCache(MEMORY_CACHE_BYTES),
        diskCache = null,
        renderMask = FogTileRenderer(style)::render,
    )

    private suspend fun <T> samples(
        action: suspend () -> T,
        validate: (T) -> Unit,
    ): List<Long> {
        repeat(WARMUP_SAMPLES) { validate(action()) }
        return List(MEASURED_SAMPLES) {
            val started = SystemClock.elapsedRealtimeNanos()
            val result = action()
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLISECOND
            validate(result)
            elapsed
        }.sorted()
    }

    private fun p95(sortedSamples: List<Long>): Long =
        sortedSamples[(sortedSamples.size * 95 + 99) / 100 - 1]

    private data class ScaleResult(
        val pointCount: Int,
        val bboxP95Millis: Long,
        val canonicalRebuildP95Millis: Long,
        val warmDerivedCacheP95Millis: Long,
        val peakPssKiB: Long,
    ) {
        fun toStatusLine() =
            "points=$pointCount bboxP95=${bboxP95Millis}ms rebuildP95=${canonicalRebuildP95Millis}ms " +
                "warmCacheP95=${warmDerivedCacheP95Millis}ms peakPss=${peakPssKiB}KiB"
    }

    private companion object {
        const val SCALE_ARGUMENT = "trailveilScale"
        const val POINTS_10K = 10_000
        const val POINTS_100K = 100_000
        const val WARMUP_SAMPLES = 2
        const val MEASURED_SAMPLES = 5
        const val MEMORY_CACHE_BYTES = 32L * 1024L * 1024L
        const val MAX_PSS_KIB = 250L * 1024L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PSS_SAMPLE_INTERVAL_MILLIS = 25L
    }
}
