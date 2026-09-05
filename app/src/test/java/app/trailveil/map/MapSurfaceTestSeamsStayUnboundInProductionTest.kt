package app.trailveil.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-011`: the MapLibre actual's diagnostic trace seam (`onFogViewportRequestedForTesting`)
 * compiles into every build type that compiles `src/mapLibre` - `release` included, and release is
 * not minified - so "the trace is debug-only" is not a property this repository can have without
 * moving the seam out of the actual. What it CAN prove, and what the task's acceptance criterion
 * was amended to on 2026-09-06 after verification, is that production never binds it: every
 * `*ForTesting` parameter of every actual has a default, and no call site in a shipped source set
 * (`main`, `mapLibre`, `google`) passes one. The seam's production cost is therefore its default,
 * a null, plus the null check where it is used; the trace lambda's arguments are never evaluated,
 * because the use is a safe call on that null.
 *
 * `src/googlePoc` is the engineering harness and binds seams on purpose; it is not a shipped
 * source set (`V02-008`) and is not scanned here. The device-test sources are scanned for the
 * opposite fact, so the absence asserted in production is paired with a presence the same scan
 * can see - a scan that could not find a binding anywhere would otherwise pass vacuously.
 */
class MapSurfaceTestSeamsStayUnboundInProductionTest {

    private val shippedSourceSets = listOf("main", "mapLibre", "google")
    private val actualDirectories = listOf("mapLibre", "google")

    @Test
    fun everyTestSeamOfEveryActualHasADefault() {
        actualDirectories.forEach { variantDirectory ->
            val seams = signatureLines(variantDirectory).filter { line -> line.contains("ForTesting") }
            assertTrue("the $variantDirectory actual declares no *ForTesting seam", seams.isNotEmpty())
            seams.forEach { seam ->
                assertTrue(
                    "the $variantDirectory actual's seam has no default, so a production call site " +
                        "would have to bind it: $seam",
                    seam.contains(" = "),
                )
            }
        }
    }

    @Test
    fun noShippedCallSiteBindsATestSeam() {
        val calls = shippedSourceSets.flatMap { sourceSet ->
            surfaceCallSites(moduleRoot().resolve("src/$sourceSet"))
        }
        assertTrue(
            "no TrailVeilMapSurface( call site was found in the shipped source sets; the scan is broken",
            calls.isNotEmpty(),
        )
        calls.forEach { call ->
            assertTrue(
                "a shipped call site binds a *ForTesting seam: ${call.location}",
                !call.arguments.contains("ForTesting"),
            )
        }
    }

    @Test
    fun theScanSeesTheTraceSeamWhereTheDeviceSuiteBindsIt() {
        val calls = surfaceCallSites(moduleRoot().resolve("src/androidTestDebug"))
        assertTrue(
            "the device suite binds onFogViewportRequestedForTesting at no TrailVeilMapSurface( " +
                "call site; either the harness dropped the trace or this scan no longer sees " +
                "named seam bindings",
            calls.any { call -> call.arguments.contains("onFogViewportRequestedForTesting") },
        )
    }

    private class SurfaceCall(val location: String, val arguments: String)

    /**
     * Every `TrailVeilMapSurface(` call under [root] with its balanced argument text. Declarations
     * (`fun TrailVeilMapSurface(`) and mentions on comment lines are not calls.
     */
    private fun surfaceCallSites(root: File): List<SurfaceCall> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .flatMap { file -> callSitesIn(file, root) }
            .toList()
    }

    private fun callSitesIn(file: File, root: File): List<SurfaceCall> {
        val text = file.readText()
        val marker = "TrailVeilMapSurface("
        val calls = mutableListOf<SurfaceCall>()
        var from = text.indexOf(marker)
        while (from >= 0) {
            val lineStart = text.lastIndexOf('\n', from) + 1
            val linePrefix = text.substring(lineStart, from)
            val isDeclaration = linePrefix.trimEnd().endsWith("fun")
            val isComment = linePrefix.trimStart().let { prefix ->
                prefix.startsWith("//") || prefix.startsWith("*") || prefix.startsWith("/*")
            }
            if (!isDeclaration && !isComment) {
                val open = from + marker.length - 1
                val close = matchingParenthesis(text, open)
                val line = text.substring(0, from).count { it == '\n' } + 1
                calls += SurfaceCall(
                    location = "${file.relativeTo(root).path}:$line",
                    arguments = text.substring(open + 1, close),
                )
            }
            from = text.indexOf(marker, from + marker.length)
        }
        return calls
    }

    private fun matchingParenthesis(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        error("unbalanced parenthesis after offset $open")
    }

    /**
     * One entry per parameter of the actual's signature. A parameter whose type wraps onto a
     * continuation line (`canonicalFogInstallCheckpointForTesting:` does) is joined back into one
     * entry, so the default check sees the whole declaration.
     */
    private fun signatureLines(variantDirectory: String): List<String> {
        val raw = moduleRoot()
            .resolve("src/$variantDirectory/java/app/trailveil/map/TrailVeilMapSurface.kt")
            .readText()
            .substringAfter("internal fun TrailVeilMapSurface(")
            .substringBefore("\n) {")
            .lines()
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith("//") }
        val parameters = mutableListOf<String>()
        var pending = ""
        raw.forEach { line ->
            pending = if (pending.isEmpty()) line else "$pending $line"
            if (pending.endsWith(",")) {
                parameters += pending.trimEnd(',')
                pending = ""
            }
        }
        if (pending.isNotEmpty()) parameters += pending
        return parameters
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
