package app.trailveil.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FogCanonicalPassCoalescingTest] stays green if the fog content counter is put back into the
 * canonical effect's key list, because a key change cancels the coroutine before the driver ever
 * observes a revision. This pin is the guard that fails on that edit, on any host, in
 * milliseconds.
 */
class CanonicalFogEffectKeySourcePinTest {

    private val pinnedKeys = listOf(
        "fogRuntime",
        "readyStyle",
        "fogViewportRequest",
        "fogViewportGeneration",
        "fogPlaceholderReadyGeneration",
        "fogBaselineReady",
        "fogInstallFaultForTesting",
    )

    @Test
    fun theCanonicalRenderAndInstallEffectIsNotKeyedOnTheFogContentCounter() {
        val source = moduleRoot()
            .resolve("src/mapLibre/java/app/trailveil/map/TrailVeilMapSurface.kt")
            .readText()
        // Non-greedy to the FIRST four-space `) {`, so only the multi-line key lists match and a
        // single-line `LaunchedEffect(x) {` cannot swallow a later effect's keys. Line endings are
        // mixed per file in this repository, so match both.
        val keyLists = Regex("""LaunchedEffect\(\r?\n((?:[^\n]*\r?\n)*?)    \) \{""")
            .findAll(source)
            .map { match -> match.groupValues[1] }
            .toList()
        val canonical = requireNotNull(
            keyLists.singleOrNull { keys -> keys.contains("fogInstallFaultForTesting") },
        ) {
            "could not locate the canonical fog render+install LaunchedEffect by its " +
                "fogInstallFaultForTesting key; update this pin if the effect was restructured"
        }
        val declaredKeys = canonical.lines()
            .map { line -> line.trim().trimEnd(',') }
            .filter { line -> line.isNotEmpty() && !line.startsWith("//") }
        assertEquals(
            "the canonical fog render+install effect's key list drifted. `fogRevision` in " +
                "particular must never return to it: a merged page is a content signal collected " +
                "inside the effect by driveCanonicalFogPasses, and as a key it cancels the " +
                "in-flight render, style install and retirement once per merged page",
            pinnedKeys,
            declaredKeys,
        )
    }

    private fun moduleRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(workingDirectory, "settings.gradle.kts").isFile) {
            File(workingDirectory, "app")
        } else {
            workingDirectory
        }
    }
}
