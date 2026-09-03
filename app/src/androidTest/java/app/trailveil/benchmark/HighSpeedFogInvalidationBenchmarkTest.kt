package app.trailveil.benchmark

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.location.LocationDistance
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPointReader
import app.trailveil.map.fog.FogDiskTileCache
import app.trailveil.map.fog.FogMemoryTileCache
import app.trailveil.map.fog.FogRevealUpdate
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogTileInvalidator
import app.trailveil.map.fog.FogTilePipeline
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.GeoPoint
import java.io.File
import kotlin.math.cos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `P4-021`: opt-in device cost of merging one accepted high-speed fix.
 *
 * Run engineering evidence with `trailveilHighSpeed=true`. The acceptance gate additionally requires
 * `trailveilHighSpeedEnforce=true` on a physical device; emulator timings are never promoted into the
 * real-device claim. The 2 s ceiling is the provider's fastest accepted-fix interval, stricter than
 * the ordinary 5 s target interval.
 */
@RunWith(AndroidJUnit4::class)
class HighSpeedFogInvalidationBenchmarkTest {
    @Test
    fun highSpeedFogInvalidationFitsTheAcceptedFixInterval() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val enforcePhysicalGate = arguments.getString(ENFORCE_ARGUMENT) == "true"
        if (enforcePhysicalGate) {
            assertEquals(
                "The enforced gate also requires trailveilHighSpeed=true",
                "true",
                arguments.getString(OPT_IN_ARGUMENT),
            )
            assertFalse("The high-speed acceptance gate requires a physical device", isEmulator())
            assertEquals(
                "The high-speed acceptance gate requires the designated POCO F7 Ultra",
                DESIGNATED_DEVICE_MODEL,
                Build.MODEL,
            )
        } else {
            assumeTrue(
                "High-speed fog benchmark is opt-in; pass trailveilHighSpeed=true",
                arguments.getString(OPT_IN_ARGUMENT) == "true",
            )
        }

        val origin = GeoPoint(latitude = FIXTURE_LATITUDE, longitude = FIXTURE_LONGITUDE)
        val results = STEP_METERS.map { meters ->
            val destination = eastward(origin, meters)
            val actualMeters = LocationDistance.haversineMeters(
                origin.latitude,
                origin.longitude,
                destination.latitude,
                destination.longitude,
            )
            assertEquals("fixture step does not represent $meters m", meters, actualMeters, 0.02)
            measureStep(FogRevealUpdate(destination, previousInSegment = origin), meters)
        }

        val trainSteps = results.filter { it.stepMeters in TRAIN_STEP_METERS }
        assertEquals(TRAIN_STEP_METERS.size, trainSteps.size)
        if (enforcePhysicalGate) {
            trainSteps.forEach { result ->
                assertTrue(
                    "${result.stepMeters.toInt()} m fog invalidation p95 exceeded the fastest " +
                        "accepted-fix interval: ${result.wallP95Millis} ms >= " +
                        "$FASTEST_ACCEPTED_FIX_INTERVAL_MILLIS ms",
                    result.wallP95Millis < FASTEST_ACCEPTED_FIX_INTERVAL_MILLIS,
                )
            }
        }
        report(results, enforcePhysicalGate)
    }

    private suspend fun measureStep(
        update: FogRevealUpdate,
        stepMeters: Double,
    ): StepResult {
        val style = FogRenderStyle()
        val invalidator = FogTileInvalidator(0..22, style)
        val candidateCount = invalidator.candidateKeyCount(update)
        val cacheRoot = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "p4-021-high-speed-${stepMeters.toInt()}",
        )
        check(cacheRoot.deleteRecursively() || !cacheRoot.exists())
        try {
            val pipeline = FogTilePipeline(
                memoryCache = FogMemoryTileCache(MEMORY_CACHE_BYTES),
                diskCache = FogDiskTileCache(cacheRoot, DISK_CACHE_BYTES),
                renderMask = FogTileRenderer(style)::render,
            )
            val coordinator = FogViewportCoordinator(
                trackDataSource = ViewportTrackDataSource(
                    ViewportTrackPointReader { _, _, _ -> emptyList() },
                ),
                pipeline = pipeline,
                style = style,
                renderVersion = RENDER_VERSION,
            )
            coordinator.render(FogViewportRequest(update.previousInSegment ?: update.current, 14.0))
            repeat(WARMUP_COUNT) {
                check(coordinator.mergePersistedReveals(listOf(update)).updatedKeys.isNotEmpty())
            }
            var expectedUpdatedCount: Int? = null
            val wallNanos = LongArray(SAMPLE_COUNT)
            val cpuNanos = LongArray(SAMPLE_COUNT)
            repeat(SAMPLE_COUNT) { sample ->
                val wallStarted = SystemClock.elapsedRealtimeNanos()
                val cpuStarted = Debug.threadCpuTimeNanos()
                val merged = coordinator.mergePersistedReveals(listOf(update))
                cpuNanos[sample] = Debug.threadCpuTimeNanos() - cpuStarted
                wallNanos[sample] = SystemClock.elapsedRealtimeNanos() - wallStarted
                assertTrue(
                    "${stepMeters.toInt()} m step changed no active fog tile",
                    merged.updatedKeys.isNotEmpty(),
                )
                val previous = expectedUpdatedCount
                if (previous == null) {
                    expectedUpdatedCount = merged.updatedKeys.size
                } else {
                    assertEquals(previous, merged.updatedKeys.size)
                }
            }
            return StepResult(
                stepMeters = stepMeters,
                candidateKeyCount = candidateCount,
                updatedKeyCount = requireNotNull(expectedUpdatedCount),
                wallP95Millis = percentileMillis(wallNanos, 0.95),
                wallMaxMillis = wallNanos.max().nanosToMillis(),
                cpuP95Millis = percentileMillis(cpuNanos, 0.95),
            )
        } finally {
            check(cacheRoot.deleteRecursively() || !cacheRoot.exists())
        }
    }

    private fun report(results: List<StepResult>, enforced: Boolean) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stream",
                    "P4-021 high-speed fog invalidation device=${Build.MANUFACTURER}/${Build.MODEL} " +
                        "physicalGate=$enforced samples=$SAMPLE_COUNT: " +
                        results.joinToString("; ") { it.statusLine() } + "\n",
                )
            },
        )
    }

    private fun eastward(origin: GeoPoint, meters: Double): GeoPoint {
        val longitudeDelta = Math.toDegrees(
            meters / (
                LocationDistance.MEAN_EARTH_RADIUS_METERS *
                    cos(Math.toRadians(origin.latitude))
                ),
        )
        return origin.copy(longitude = origin.longitude + longitudeDelta)
    }

    private fun percentileMillis(samples: LongArray, percentile: Double): Double {
        val sorted = samples.sorted()
        val index = kotlin.math.ceil(sorted.size * percentile).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index].nanosToMillis()
    }

    private fun Long.nanosToMillis(): Double = this / 1_000_000.0

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)

    private data class StepResult(
        val stepMeters: Double,
        val candidateKeyCount: Long,
        val updatedKeyCount: Int,
        val wallP95Millis: Double,
        val wallMaxMillis: Double,
        val cpuP95Millis: Double,
    ) {
        fun statusLine(): String =
            "step=${stepMeters.toInt()}m candidates=$candidateKeyCount updated=$updatedKeyCount " +
                "wallP95=${"%.3f".format(java.util.Locale.US, wallP95Millis)}ms " +
                "wallMax=${"%.3f".format(java.util.Locale.US, wallMaxMillis)}ms " +
                "cpuP95=${"%.3f".format(java.util.Locale.US, cpuP95Millis)}ms"
    }

    private companion object {
        const val OPT_IN_ARGUMENT = "trailveilHighSpeed"
        const val ENFORCE_ARGUMENT = "trailveilHighSpeedEnforce"
        const val FIXTURE_LATITUDE = 25.0
        const val FIXTURE_LONGITUDE = 121.0
        const val DESIGNATED_DEVICE_MODEL = "24122RKC7G"
        const val RENDER_VERSION = 1
        const val WARMUP_COUNT = 3
        const val SAMPLE_COUNT = 12
        const val FASTEST_ACCEPTED_FIX_INTERVAL_MILLIS = 2_000.0
        const val MEMORY_CACHE_BYTES = 16L * 1024L * 1024L
        const val DISK_CACHE_BYTES = 64L * 1024L * 1024L
        val STEP_METERS = listOf(7.0, 125.0, 400.0, 417.0, 1_250.0)
        val TRAIN_STEP_METERS = setOf(400.0, 417.0)
    }
}
