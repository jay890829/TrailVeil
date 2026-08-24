package app.trailveil.ci

import java.io.File
import org.junit.Assert.assertEquals
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
    }
}
