package app.trailveil

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `P4-039`: the connected-run manifest verifier is skipped whenever a runner selection argument is
 * present, and every CI shard passes one — so a drift between the declared manifest and the actual
 * `@Test` methods survived five commits and a fully green hosted run before a human count caught it.
 * This JVM twin runs in the quality job on every push, needs no device, and compares the two sets
 * both ways from source. It deliberately does NOT replace the connected verifier, which also checks
 * that the declared cases actually *ran*; this one only pins declared == written.
 *
 * Parsing contract, kept in step with the sources by the assertions themselves: every `@Test` in
 * `app/src/androidTest/java` sits inside a single top-level class per file whose name matches the
 * file name, packages mirror directories, and the annotation binds to the next `fun name(` match -
 * on the same line or any later line (so interleaved annotations are fine; an interleaved comment
 * containing `fun x(` would over-match, which no source file does). A parse miss shows up as a set difference, so the
 * failure message names exactly which side is wrong.
 */
class InstrumentationManifestDriftTest {
    @Test
    fun `every written instrumentation test is declared, and nothing declared is unwritten`() {
        val moduleDir = File(System.getProperty("user.dir"))
        val manifestFile = File(moduleDir, "src/androidTest/instrumentation-test-manifest.txt")
        val sourceRoot = File(moduleDir, "src/androidTest/java")
        require(manifestFile.isFile) { "manifest not found at ${manifestFile.absolutePath}" }
        require(sourceRoot.isDirectory) { "androidTest sources not found at ${sourceRoot.absolutePath}" }

        val declared = manifestFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toSortedSet()

        val written = sortedSetOf<String>()
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            val packageName = lines.firstNotNullOfOrNull { line ->
                PACKAGE_PATTERN.find(line.trim())?.groupValues?.get(1)
            } ?: error("no package declaration in ${file.name}")
            val className = file.nameWithoutExtension
            var pendingAnnotation = false
            for (raw in lines) {
                val line = raw.trim()
                if (TEST_ANNOTATION_PATTERN.containsMatchIn(line)) pendingAnnotation = true
                if (pendingAnnotation) {
                    val match = FUNCTION_PATTERN.find(line)
                    if (match != null) {
                        val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                        written += "$packageName.$className#$name"
                        pendingAnnotation = false
                    }
                }
            }
        }

        assertEquals(
            "undeclared tests (add to instrumentation-test-manifest.txt): " +
                (written - declared) +
                " / declared-but-unwritten (remove or fix the name): " +
                (declared - written),
            declared,
            written,
        )
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("""^package\s+([\w.]+)""")
        val TEST_ANNOTATION_PATTERN = Regex("""^@Test\b""")

        /** Matches both plain and backtick-quoted function names. */
        val FUNCTION_PATTERN = Regex("""fun\s+(?:`([^`]+)`|(\w+))\s*\(""")
    }
}
