package app.trailveil

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSecretBoundaryTest {
    @Test
    fun `signing material cannot enter tracked files or GitHub Actions`() {
        val root = repositoryRoot()
        val tracked = if (File(root, ".git").exists()) {
            git(root, "ls-files").lineSequence().filter(String::isNotBlank).toList().also { files ->
                assertTrue("git ls-files returned no tracked evidence", files.isNotEmpty())
            }
        } else {
            root.walkTopDown()
                .onEnter { directory -> directory == root || directory.name !in IGNORED_DIRECTORIES }
                .filter(File::isFile)
                .map { file -> file.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }
        val forbiddenTrackedNames = tracked.filter { path ->
            val lower = path.lowercase()
            lower.endsWith(".jks") ||
                lower.endsWith(".keystore") ||
                lower.endsWith(".p12") ||
                lower.endsWith(".pfx") ||
                lower.endsWith("internal-signing.properties")
        }
        assertTrue(
            "Tracked signing material: $forbiddenTrackedNames",
            forbiddenTrackedNames.isEmpty(),
        )

        val workflows = File(root, ".github").walkTopDown()
            .filter { file -> file.isFile && file.extension in setOf("yml", "yaml") }
            .toList()
        assertTrue("No GitHub workflow evidence was found", workflows.isNotEmpty())
        val workflowText = workflows.joinToString("\n") { file -> file.readText() }.lowercase()
        listOf(
            "trailveil_internal_signing_properties",
            "internal-signing.properties",
            "assemblerelease",
            "app-release.apk",
            "github-release",
            "storepassword",
            "keypassword",
            "secrets.",
            ".jks",
            ".keystore",
            ".p12",
            ".pfx",
        ).forEach { forbidden ->
            assertFalse(
                "GitHub Actions must not receive release signing input: $forbidden",
                workflowText.contains(forbidden),
            )
        }

        val ignore = File(root, ".gitignore").readText()
        listOf("*.jks", "*.keystore", "*.p12", "*.pfx", "internal-signing.properties")
            .forEach { pattern -> assertTrue("Missing signing ignore pattern $pattern", pattern in ignore) }
    }

    private fun repositoryRoot(): File {
        val moduleDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return requireNotNull(moduleDirectory.parentFile)
    }

    private fun git(root: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", *arguments))
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }

    private companion object {
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", "build")
    }
}
