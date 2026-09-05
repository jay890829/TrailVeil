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
 * `src/googlePoc` is the engineering harness and binds seams on purpose; it is compiled into the
 * `googlePoc` build type alone, which is not shipped (`V02-008`), so it is the one non-test source
 * root this scan excludes - and it is scanned for the opposite fact, like the device-test sources,
 * so every absence asserted here is paired with a presence the same scan can see; a scan that
 * could not find a binding anywhere would otherwise pass vacuously.
 *
 * Two routes a binding could take past a text scan, both closed by construction: a seam bound
 * positionally or as a trailing lambda carries no `ForTesting` text at the call site, so every
 * shipped call site must name every argument and end at its parenthesis; and a source root the
 * scan does not know about would never be read, so the roots are found by listing `src/` and
 * excluding the test trees and the harness, never by naming what to include. A source scan still
 * cannot see Gradle scoping (`V02-008`'s lesson); what it proves is that no source a shipped build
 * could compile binds a seam, which for this repository is the same thing.
 */
class MapSurfaceTestSeamsStayUnboundInProductionTest {

    private val actualDirectories = listOf("mapLibre", "google")

    /** The roots the amended acceptance criterion names; a rename must fail loudly, not shrink the scan. */
    private val rootsThatMustExist = listOf("main", "mapLibre", "google")

    /** The one non-test root a shipped build never compiles. */
    private val engineeringHarness = "googlePoc"

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
    fun theShippedRootsTheCriterionNamesAllExist() {
        val roots = shippedSourceRoots().map(File::getName)
        rootsThatMustExist.forEach { name ->
            assertTrue("src/$name is gone or renamed; the scan would silently shrink: $roots", name in roots)
        }
    }

    @Test
    fun noShippedCallSiteBindsATestSeam() {
        val calls = shippedSourceRoots().flatMap(::surfaceCallSites)
        assertTrue(
            "no TrailVeilMapSurface( call site was found in any shipped source root; the scan is broken",
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
    fun everyShippedCallSiteNamesEveryArgumentAndTakesNoTrailingLambda() {
        val calls = shippedSourceRoots().flatMap(::surfaceCallSites)
        assertTrue("no shipped call site found; the scan is broken", calls.isNotEmpty())
        calls.forEach { call ->
            assertTrue(
                "a shipped call site passes a trailing lambda, which binds the last seam without " +
                    "naming it: ${call.location}",
                !call.trailingLambda,
            )
            topLevelArguments(call.arguments).forEach { argument ->
                assertTrue(
                    "a shipped call site passes an argument positionally, which could bind a seam " +
                        "without naming it: ${call.location}: $argument",
                    NAMED_ARGUMENT.containsMatchIn(argument),
                )
            }
        }
    }

    @Test
    fun theEngineeringHarnessIsWhereSeamsAreBoundOutsideTheTestTrees() {
        val calls = surfaceCallSites(moduleRoot().resolve("src/$engineeringHarness"))
        assertTrue(
            "src/$engineeringHarness binds no *ForTesting seam at any TrailVeilMapSurface( call site; " +
                "if the harness moved, move the exclusion with it",
            calls.any { call -> call.arguments.contains("ForTesting") },
        )
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

    private class SurfaceCall(val location: String, val arguments: String, val trailingLambda: Boolean)

    /**
     * Every directory under `src/` a shipped build could compile: everything but the JVM and device
     * test trees and the engineering harness. An exclusion list, so a root added later is scanned
     * by default.
     */
    private fun shippedSourceRoots(): List<File> = moduleRoot().resolve("src")
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .filter { directory ->
            val name = directory.name
            name != engineeringHarness && !name.startsWith("test") && !name.startsWith("androidTest")
        }
        .sortedBy(File::getName)

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
                val afterCall = text.substring(close + 1).trimStart()
                calls += SurfaceCall(
                    location = "${file.relativeTo(root).path}:$line",
                    arguments = text.substring(open + 1, close),
                    trailingLambda = afterCall.startsWith("{"),
                )
            }
            from = text.indexOf(marker, from + marker.length)
        }
        return calls
    }

    /**
     * The call's arguments split at the commas that belong to the call itself, not to a nested
     * call, lambda, index or string literal; comments inside the argument list are dropped, so a
     * commented argument is neither an argument nor a positional one.
     */
    private fun topLevelArguments(arguments: String): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var index = 0
        while (index < arguments.length) {
            val c = arguments[index]
            val next = if (index + 1 < arguments.length) arguments[index + 1] else ' '
            when {
                !inString && c == '/' && next == '/' -> {
                    val end = arguments.indexOf('\n', index)
                    index = if (end < 0) arguments.length else end
                }
                !inString && c == '/' && next == '*' -> {
                    val end = arguments.indexOf("*/", index + 2)
                    index = if (end < 0) arguments.length else end + 1
                }
                inString -> {
                    current.append(c)
                    if (c == '\\') {
                        index += 1
                        if (index < arguments.length) current.append(arguments[index])
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> { inString = true; current.append(c) }
                c == '(' || c == '{' || c == '[' -> { depth += 1; current.append(c) }
                c == ')' || c == '}' || c == ']' -> { depth -= 1; current.append(c) }
                c == ',' && depth == 0 -> { pieces += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
            index += 1
        }
        pieces += current.toString()
        return pieces.map(String::trim).filter(String::isNotEmpty)
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

    private companion object {
        /** `name = ` at the start of an argument; `==` is not a binding. */
        val NAMED_ARGUMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*=(?!=)")
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
