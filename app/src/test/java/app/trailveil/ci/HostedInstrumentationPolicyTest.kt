package app.trailveil.ci

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedInstrumentationPolicyTest {
    @Test
    fun notificationContinuationRemainsDeclaredButIsExcludedFromBothHostedRestShards() {
        val moduleDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val repository = requireNotNull(moduleDirectory.parentFile)
        val workflow = File(repository, ".github/workflows/android.yml").readText()
        REST_SHARDS.forEach { shardName ->
            val shardBlock = shardBlock(workflow, shardName)
            val excluded = NOT_CLASS_ARGUMENT.findAll(shardBlock)
                .map { match -> match.groupValues[1].split(',').toSet() }
                .single()
            assertEquals(HOSTED_REST_EXCLUSIONS, excluded)
        }

        val manifest = File(
            moduleDirectory,
            "src/androidTest/instrumentation-test-manifest.txt",
        ).readLines().map(String::trim)
        assertTrue(
            "The device-only regression must remain declared for explicit/unfiltered runs",
            NOTIFICATION_CONTINUATION_CASE in manifest,
        )
    }

    /**
     * `V02-005` stage 9, the googlePoc twin of the device-only regime above.
     *
     * The keyed googlePoc behaviour suites run only on the operator AVD; the hosted `googlepoc-nokey`
     * job must keep building the test APK without ever running it, and the two stage-9 device cases
     * that cannot run hosted stay DECLARED so an unfiltered operator run cannot drop them silently:
     * the twenty-cycle lifecycle proof and the in-process half of process-death restoration. The
     * `am kill` half is a host-driven script, which must exist and name both the package and the kill,
     * because a script is the only thing that can drive a plain, un-instrumented process death.
     */
    @Test
    fun googlePocDeviceOnlyCasesStayDeclaredAndTheHostedKeylessJobNeverRunsThem() {
        val moduleDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val repository = requireNotNull(moduleDirectory.parentFile)
        val workflow = File(repository, ".github/workflows/android.yml").readText()

        val nokeyStart = workflow.indexOf("  googlepoc-nokey:")
        assertTrue("Hosted workflow is missing the googlepoc-nokey job", nokeyStart >= 0)
        val nokeyEnd = workflow.indexOf("\n  instrumentation:", nokeyStart).takeIf { it >= 0 }
            ?: workflow.length
        val nokeyJob = workflow.substring(nokeyStart, nokeyEnd)
        assertFalse(
            "the keyless hosted job must never run googlePoc instrumentation; it has no key and no AVD",
            Regex("""connected\w*AndroidTest""").containsMatchIn(nokeyJob),
        )
        assertTrue(
            "the keyless hosted job must keep assembling the googlePoc test APK",
            nokeyJob.contains("assembleGooglePocAndroidTest"),
        )

        val googleManifest = File(
            moduleDirectory,
            "src/androidTestGooglePoc/instrumentation-test-manifest.txt",
        ).readLines().map(String::trim)
        GOOGLE_DEVICE_ONLY_CASES.forEach { case ->
            assertTrue("googlePoc device-only case must stay declared: $case", case in googleManifest)
        }

        val script = File(repository, PROCESS_DEATH_SCRIPT)
        assertTrue("process-death driver script is missing: $PROCESS_DEATH_SCRIPT", script.isFile)
        val body = script.readText()
        assertTrue(body.contains("am kill"))
        assertTrue(body.contains("app.trailveil"))
        assertTrue(
            "the driver must relaunch through the launcher intent so the saved task is resumed",
            body.contains("android.intent.category.LAUNCHER"),
        )
    }


    private fun shardBlock(workflow: String, shardName: String): String {
        val marker = "          - name: $shardName"
        val start = workflow.indexOf(marker)
        assertTrue("Hosted workflow is missing $shardName", start >= 0)
        val next = workflow.indexOf("\n          - name:", start + marker.length)
        return workflow.substring(start, if (next >= 0) next else workflow.length)
    }

    private companion object {
        val NOT_CLASS_ARGUMENT = Regex(
            """-Pandroid\.testInstrumentationRunnerArguments\.notClass=([^\s]+)""",
        )
        val REST_SHARDS = listOf("rest-0", "rest-1")
        val HOSTED_REST_EXCLUSIONS = setOf(
            BLOCKED_RESUME_FIXTURE,
            NOTIFICATION_CONTINUATION_FIXTURE,
        )
        const val BLOCKED_RESUME_FIXTURE =
            "app.trailveil.feature.recording.BlockedResumeComposeTest"
        const val NOTIFICATION_CONTINUATION_FIXTURE =
            "app.trailveil.feature.recording.NotificationStartContinuationTest"
        const val NOTIFICATION_CONTINUATION_CASE =
            "$NOTIFICATION_CONTINUATION_FIXTURE#" +
                "recreationWhileThePromptIsVisibleContinuesOneDeniedStartExactlyOnce"
        const val PROCESS_DEATH_SCRIPT = ".github/scripts/verify-process-death-restoration.sh"
        val GOOGLE_DEVICE_ONLY_CASES = listOf(
            "app.trailveil.map.GoogleTwentyCycleLifecycleTest#" +
                "twentyStopStartCyclesKeepTheProvenGenerationAndTwentyRecreationsRestoreTheCamera",
            "app.trailveil.map.GoogleMapProcessDeathRestorationTest#" +
                "aColdProcessRebuildsFogBehindTheCoverAndRecreationRestoresTheDetailEntryThroughLoading",
            "app.trailveil.map.GoogleMapProcessDeathRestorationTest#" +
                "aRealEnvelopeRestoresTheCameraAndAForeignEnvelopeIsDiscarded",
            "app.trailveil.map.GoogleMapProcessDeathRestorationTest#" +
                "aColdStartWithExistingHistoryRebuildsFogFromCanonicalRoomBehindTheCover",
        )
    }
}
