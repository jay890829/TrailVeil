package app.trailveil.googlepoc

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoTaiwanAnchors
import app.trailveil.map.fog.FogTileBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in Room/geometry cost probe; no FPS or SDK rendering claim. Never touches a phone/history. */
@RunWith(AndroidJUnit4::class)
class TrackNativeScaleProbeTest {
    @Test fun regionalHistoryUsesBoundedNativePartitionsAndAWarmCache() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("explicit scale-probe opt-in required",
            InstrumentationRegistry.getArguments().getString("trailveilTrackNativeScale") == "true")
        assumeTrue("synthetic empty-emulator probe only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = instrumentation.targetContext
        val container = (context.applicationContext as TrailVeilApplication).appContainer
        withContext(Dispatchers.Default) {
            val database = container.databaseForTesting()
            val count = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_points").use {
                check(it.moveToFirst()); it.getLong(0)
            }
            assumeTrue("probe requires an empty database; existing history must not be replaced", count == 0L)
            val runtime = container.fogRuntime()
            try {
                val seedStarted = SystemClock.elapsedRealtime()
                val seeded = DemoExplorationSeeder.seedRegion(context)
                val seedMillis = SystemClock.elapsedRealtime() - seedStarted
                assertEquals(200, seeded.sessions)
                assertEquals(204_800, seeded.points)
                runtime.viewportCoordinator.clearDerivedCache()
                val anchor = DemoTaiwanAnchors.anchors().first()
                val bounds = FogTileBounds(anchor.longitude - 0.008, anchor.latitude - 0.008,
                    anchor.longitude + 0.008, anchor.latitude + 0.008)
                val coldStarted = SystemClock.elapsedRealtime()
                val cold = checkNotNull(runtime.viewportCoordinator.renderNativeGeometry(bounds))
                val coldMillis = SystemClock.elapsedRealtime() - coldStarted
                val warmStarted = SystemClock.elapsedRealtime()
                val warm = checkNotNull(runtime.viewportCoordinator.renderNativeGeometry(bounds))
                val warmMillis = SystemClock.elapsedRealtime() - warmStarted
                assertTrue("cold read did not produce revealed geometry",
                    cold.polygons.any { it.holes.isNotEmpty() } || cold.polygons.size > 1)
                assertEquals(cold.polygons, warm.polygons)
                assertTrue("warm read rebuilt partitions: ${warm.diagnostics}", warm.diagnostics.contains("misses=0"))
                SpikeEvidence.emit(context, "v03-013-track-scale.txt",
                    "V03-013-NATIVE-SCALE sessions=${seeded.sessions} points=${seeded.points} " +
                        "seedMs=$seedMillis coldMs=$coldMillis warmMs=$warmMillis " +
                        "cold=${cold.diagnostics} warm=${warm.diagnostics}")
            } finally {
                DemoExplorationSeeder.clear(context)
                runtime.viewportCoordinator.clearDerivedCache()
            }
        }
    }
}
