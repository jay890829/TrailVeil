package app.trailveil.map

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.map.fog.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** Real SDK projection and production evaluator, synthetic pixels with deliberate missing evidence. */
class GoogleProbeBankPixelOracleTest {
    @Test fun everyBankRejectsBareWrongColourMissingTileBlockAndFourOfNinePixels() =
        GoogleNativeCoordinateTestSupport.withMap { map ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
            main { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 3f)) }
            val image = AtomicReference<Bitmap?>()
            val ready = CountDownLatch(1)
            main { map.snapshot { image.set(it); ready.countDown() } }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            val size = checkNotNull(image.get())
            val width = size.width; val height = size.height
            size.recycle()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                main {
                    val visible = map.projection.visibleRegion
                    fun LatLng.point() = GeoPoint(latitude, longitude)
                    val request = FogViewportCoverageRequest(map.cameraPosition.target.point(), 3,
                        visible.nearLeft.point(), visible.farLeft.point(), visible.farRight.point(), visible.nearRight.point())
                    val masks = FogViewportCoveragePlanner(paddingTiles = 0).plan(request).keys.associateWith {
                        FogPixelMask(256, 256, ByteArray(256 * 256) { 184.toByte() })
                    }
                    val prover = GoogleFogSnapshotProver(map, scope, { _, _ -> null }, { 0L })
                    val evaluate = prover.javaClass.getDeclaredMethod("evaluate", Bitmap::class.java,
                        FogSnapshotVisualProbePlan::class.java, Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType).apply { isAccessible = true }
                    val colour = FogTilePngCodec.colorForGeneration(7L)
                    fun mid(channel: Int) = FogTilePngCodec.revealedFogChannelRange(channel).let { (it.first + it.last) / 2 }
                    val good = Color.rgb(mid(colour.red), mid(colour.green), mid(colour.blue))
                    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        for (bank in FogProbeCandidateBank.entries) {
                            val raw = FogSnapshotVisualProbePlanner().plan(request, masks, candidateBank = bank)
                            // Exactly three separated, fully on-screen blocks per tile make the
                            // missing-block case an actual quorum failure rather than redundant evidence.
                            val groups = raw.provableKeys().mapNotNull { key ->
                                val blocks = raw.probeBlocks(key).filter { block -> block.all { p ->
                                    val xy = map.projection.toScreenLocation(LatLng(p.latitude, p.longitude))
                                    p.strongNeighbourhood && xy.x in 2 until width - 2 && xy.y in 2 until height - 2
                                } }.take(3)
                                if (blocks.size == 3) key to blocks.flatten() else null
                            }.toMap()
                            assertTrue("pixel control needs multiple visible tiles", groups.size >= 2)
                            val plan = FogSnapshotVisualProbePlan(groups.keys, groups, candidateBank = bank)
                            fun verdict(): Boolean = (evaluate.invoke(prover, bitmap, plan, 7L, bank.ordinal + 1)
                                as GoogleFogProofObservation).passed
                            fun paint(probes: List<FogSnapshotVisualProbe>, matchingPixels: Int) {
                                for (p in probes) {
                                    val xy = map.projection.toScreenLocation(LatLng(p.latitude, p.longitude))
                                    repeat(9) { i -> bitmap[xy.x + i % 3 - 1, xy.y + i / 3 - 1] =
                                        if (i < matchingPixels) good else Color.WHITE }
                                }
                            }
                            bitmap.eraseColor(good); assertTrue("positive $bank", verdict())
                            val outsideBlue = FogTilePngCodec.revealedFogChannelRange(colour.blue).last + 1
                            assertTrue(outsideBlue <= 255)
                            for (bad in listOf(Color.WHITE, Color.rgb(214, 89, 0),
                                    Color.rgb(mid(colour.red), mid(colour.green), outsideBlue))) {
                                bitmap.eraseColor(bad); assertFalse("bare/fog-like $bank", verdict())
                            }
                            val victim = groups.keys.first()
                            bitmap.eraseColor(good); paint(groups.getValue(victim), 0)
                            paint(plan.probeBlocks(victim)[bank.ordinal % 3], 9)
                            assertFalse("different matching blocks cannot accumulate between banks $bank", verdict())
                            bitmap.eraseColor(good); paint(groups.getValue(victim), 0)
                            assertFalse("missing tile $bank", verdict())
                            val block = plan.probeBlocks(victim).last()
                            bitmap.eraseColor(good); paint(block, 0)
                            assertFalse("missing required block $bank", verdict())
                            bitmap.eraseColor(good); paint(block, 4)
                            assertFalse("4/9 must fail $bank", verdict())
                            bitmap.eraseColor(good); paint(block, 5)
                            assertTrue("5/9 must pass $bank", verdict())
                        }
                    } finally { bitmap.recycle(); prover.release() }
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "Z_BANK_PIXEL_ORACLE banks=4 positive=4 bareOrWrongColour=12 missingTile=4 missingBlock=4 fourOfNine=4 fiveOfNine=4 PASS\n")
                    })
                }
            } finally { scope.cancel() }
        }
}
