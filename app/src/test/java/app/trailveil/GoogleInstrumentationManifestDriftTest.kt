package app.trailveil

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Hosted/keyless bidirectional drift gate for the isolated provider instrumentation graph. */
class GoogleInstrumentationManifestDriftTest {
    @Test
    fun everyGoogleInstrumentationTestIsDeclaredAndEveryDeclarationExists() {
        val module = File(requireNotNull(System.getProperty("user.dir")))
        val manifest = File(module, "src/androidTestGooglePoc/instrumentation-test-manifest.txt")
        val sourceRoot = File(module, "src/androidTestGooglePoc/java")
        require(manifest.isFile) { "manifest not found at ${manifest.absolutePath}" }
        require(sourceRoot.isDirectory) { "source root not found at ${sourceRoot.absolutePath}" }

        val declared = manifest.readLines()
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
            .toSortedSet()
        val written = sortedSetOf<String>()
        sourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .forEach { file ->
                val lines = file.readLines()
                val packageName = lines.firstNotNullOfOrNull { line ->
                    PACKAGE_PATTERN.find(line.trim())?.groupValues?.get(1)
                } ?: error("no package declaration in ${file.name}")
                var pendingTest = false
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (TEST_ANNOTATION_PATTERN.containsMatchIn(line)) pendingTest = true
                    if (pendingTest) {
                        FUNCTION_PATTERN.find(line)?.let { match ->
                            val method = match.groupValues[1].ifEmpty { match.groupValues[2] }
                            written += "$packageName.${file.nameWithoutExtension}#$method"
                            pendingTest = false
                        }
                    }
                }
            }
        assertEquals(
            "undeclared=${written - declared} / declared-but-unwritten=${declared - written}",
            declared,
            written,
        )
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("""^package\s+([\w.]+)""")
        val TEST_ANNOTATION_PATTERN = Regex("""^@Test\b""")
        val FUNCTION_PATTERN = Regex("""fun\s+(?:`([^`]+)`|(\w+))\s*\(""")
    }
}
