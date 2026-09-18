package app.trailveil.map

import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import app.trailveil.map.GoogleNativeCoordinateTestSupport.hash
import app.trailveil.map.GoogleNativeCoordinateTestSupport.withMap
import app.trailveil.map.GoogleNativeCoordinateTestSupport.input
import app.trailveil.map.GoogleNativeCoordinateTestSupport.main
import app.trailveil.map.GoogleNativeCoordinateTestSupport.track
import app.trailveil.map.GoogleNativeCoordinateTestSupport.sdkHash
import app.trailveil.map.GoogleNativeCoordinateTestSupport.snapshotProgress
import app.trailveil.map.GoogleNativeCoordinateTestSupport.inputHash

/** Same test APK invokes the production Track prepare/attach API in both app versions. */
class GoogleNativeCoordinateCostTest {
    @Test fun productionPreparationAndAttachCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("x1Cost") == "true")
        val appHash=hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        val testHash=hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("x1ExpectedApkHash"))
        val order=(args.getString("x1CaseOrder") ?: "small,vertices1000,vertices12000,holes2400").split(',')
        check(order.sorted() == listOf("holes2400","small","vertices1000","vertices12000"))
        withMap { map -> for (name in order) {
            val input=input(name); val samples=JSONArray(); var expectedOutput: String? = null
            repeat(7) { sample ->
                lateinit var installer: GoogleTrackFogOverlayInstaller
                main { installer=track(map) { input.geometry } }
                var prepareCpu=0L; var prepareWall=0L; var mainCpu=0L; var mainWall=0L; var attached=false
                var output=""; var workerThread=0L
                val totalStart=System.nanoTime()
                try {
                    runBlocking(Dispatchers.Default) {
                        workerThread=Thread.currentThread().id
                        val cpu=Debug.threadCpuTimeNanos(); val start=System.nanoTime()
                        installer.prepare(1,input.coverage,input.tiles)
                        prepareWall=System.nanoTime()-start; prepareCpu=Debug.threadCpuTimeNanos()-cpu
                        check(Thread.currentThread().id==workerThread) { "CPU measurement migrated threads" }
                    }
                    main {
                        val cpu=Debug.threadCpuTimeNanos(); val start=System.nanoTime()
                        attached=installer.attach(1,input.coverage,input.tiles)
                        mainWall=System.nanoTime()-start; mainCpu=Debug.threadCpuTimeNanos()-cpu
                    }
                    val totalWall=System.nanoTime()-totalStart
                    main { check(attached) { installer.describe() }; output=sdkHash(installer,1); check(installer.covers(1,listOf(input.coverage.center))) }
                    if (expectedOutput == null) expectedOutput=output else check(output == expectedOutput)
                    samples.put(JSONObject().put("sample",sample).put("prepareCpuNs",prepareCpu).put("prepareWallNs",prepareWall)
                        .put("mainCpuNs",mainCpu).put("mainWallNs",mainWall).put("totalWallNs",totalWall).put("cpuNs",prepareCpu+mainCpu).put("workerThread",workerThread).put("outputSha256",output))
                } finally { main { check(installer.remove(1)); installer.release() } }
                snapshotProgress(map)
            }
            val result=JSONObject().put("case",name).put("caseOrder",order.joinToString(",")).put("appSha256",appHash).put("testSha256",testHash)
                .put("fingerprint",Build.FINGERPRINT).put("inputSha256",inputHash(input)).put("outputSha256",expectedOutput).put("samples",samples)
            instrumentation.sendStatus(0,Bundle().apply { putString("stream","X1_COORDINATES $result\n") })
        } }
    }
}
