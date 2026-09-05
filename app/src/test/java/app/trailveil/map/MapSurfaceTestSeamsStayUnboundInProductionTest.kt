package app.trailveil.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-011`: the MapLibre actual's diagnostic trace seam (`onFogViewportRequestedForTesting`)
 * compiles into every build type that compiles `src/mapLibre` - `release` included, and release is
 * not minified - so "the trace is debug-only" is not a property this repository can have without
 * moving the seam out of the actual. What it CAN prove, and what the task's acceptance criterion
 * was amended to on 2026-09-06 after verification, is that production never binds it: every
 * `*ForTesting` parameter of every actual has a default, and no call site in a shipped source root
 * passes one. The seam's production cost is therefore its default, a null, plus the null check
 * where it is used; the trace lambda's arguments are never evaluated, because the use is a safe
 * call on that null.
 *
 * `src/googlePoc` is the engineering harness and binds seams on purpose; it is compiled into the
 * `googlePoc` build type alone, which is not shipped (`V02-008`), so it is the one non-test source
 * root this scan excludes - and it is scanned for the opposite fact, like the device-test sources,
 * so every absence asserted here is paired with a presence the same scan can see; a scan that
 * could not find a binding anywhere would otherwise pass vacuously.
 *
 * What a text scan has to get right, each closed here and each pinned by
 * [theScanSeesThroughLiteralsCommentsAndCallShapes]: a seam bound positionally or as a trailing
 * lambda carries no `ForTesting` text at the call site, so every shipped call site must name every
 * argument and end at its parenthesis; a source root the scan does not know about would never be
 * read, so the roots are found by listing `src/` and excluding the test trees and the harness,
 * never by naming what to include; and a parenthesis, comma or call marker inside a string, a
 * char literal or a comment is not syntax, so comments are blanked before the scan and literals are
 * stepped over while matching. Two limits remain, stated rather than hidden: the test trees are
 * excluded by AGP's own `test<Variant>` / `androidTest<Variant>` naming, so a shipped root given a
 * name of that shape would be skipped; and a source scan cannot see Gradle scoping (`V02-008`'s
 * lesson) - what it proves is that no source a shipped build could compile binds a seam, which for
 * this repository is the same thing.
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

    /**
     * The routes two verifications walked past earlier versions of this scan, kept as a positive
     * control: a `)` inside a string, a char literal or a comment must not end the call early and
     * hide what follows; a space or a newline before the parenthesis, or a closed block comment
     * earlier on the line, must not hide the call; commented-out calls and the declaration are not
     * calls.
     */
    @Test
    fun theScanSeesThroughLiteralsCommentsAndCallShapes() {
        val snippet = listOf(
            "// TrailVeilMapSurface(commented = 1) is not a call",
            "/* TrailVeilMapSurface(commented = 2) is not a call either */",
            "internal fun TrailVeilMapSurface(declaration: Int) = Unit",
            "val a = TrailVeilMapSurface(label = \":)\", mark = ')', note = /* :) */ 1, escaped = \"\\\":)\", onFogViewportRequestedForTesting = null)",
            "val b = TrailVeilMapSurface (modifier = Modifier)",
            "val c = TrailVeilMapSurface",
            "    (modifier = Modifier) { }",
            "val d = /* keep */ TrailVeilMapSurface(modifier = Modifier, count = list.size)",
        ).joinToString("\n")
        val calls = callSitesIn(snippet, "snippet.kt")
        assertEquals(calls.map(SurfaceCall::location), listOf("snippet.kt:4", "snippet.kt:5", "snippet.kt:6", "snippet.kt:8"))
        assertTrue("the binding after the literals was hidden", calls[0].arguments.contains("onFogViewportRequestedForTesting"))
        val named = topLevelArguments(calls[0].arguments)
        assertEquals(5, named.size)
        named.forEach { argument -> assertTrue(argument, NAMED_ARGUMENT.containsMatchIn(argument)) }
        assertTrue("the trailing lambda on the newline-shaped call was missed", calls[2].trailingLambda)
        assertTrue(!calls[3].trailingLambda)
        assertEquals("modifier = Modifier, count = list.size", calls[3].arguments)
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

    /** Every `TrailVeilMapSurface(` call under [root] with its balanced argument text. */
    private fun surfaceCallSites(root: File): List<SurfaceCall> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .flatMap { file -> callSitesIn(file.readText(), file.relativeTo(root).path) }
            .toList()
    }

    /**
     * Every call in [source], located as `name:line`. Comments are blanked first (newlines kept, so
     * line numbers survive), the marker tolerates whitespace before the parenthesis, a declaration
     * (`fun TrailVeilMapSurface(`) is not a call, and the argument text ends at the parenthesis that
     * really closes the call - literals are stepped over, not counted.
     */
    private fun callSitesIn(source: String, name: String): List<SurfaceCall> {
        val text = withoutComments(source)
        return CALL_MARKER.findAll(text).mapNotNull { match ->
            val before = text.substring(0, match.range.first).trimEnd()
            if (before.endsWith("fun")) return@mapNotNull null
            val open = match.range.last
            val close = matchingParenthesis(text, open)
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            SurfaceCall(
                location = "$name:$line",
                arguments = text.substring(open + 1, close),
                trailingLambda = text.substring(close + 1).trimStart().startsWith("{"),
            )
        }.toList()
    }

    /** [text] with every comment turned to spaces, newlines kept; string and char literals untouched. */
    private fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            val next = if (index + 1 < text.length) text[index + 1] else ' '
            when {
                c == '"' || c == '\'' -> {
                    val end = literalEnd(text, index)
                    out.append(text, index, end)
                    index = end
                }
                c == '/' && next == '/' -> {
                    while (index < text.length && text[index] != '\n') {
                        out.append(' ')
                        index += 1
                    }
                }
                c == '/' && next == '*' -> {
                    var depth = 0
                    while (index < text.length) {
                        val here = text[index]
                        val after = if (index + 1 < text.length) text[index + 1] else ' '
                        if (here == '/' && after == '*') {
                            depth += 1
                            out.append("  ")
                            index += 2
                        } else if (here == '*' && after == '/') {
                            depth -= 1
                            out.append("  ")
                            index += 2
                            if (depth == 0) break
                        } else {
                            out.append(if (here == '\n') '\n' else ' ')
                            index += 1
                        }
                    }
                }
                else -> {
                    out.append(c)
                    index += 1
                }
            }
        }
        return out.toString()
    }

    /** The index just past the string (`"…"` or `"""…"""`) or char literal (`'…'`) that starts at [start]. */
    private fun literalEnd(text: String, start: Int): Int {
        if (text.startsWith("\"\"\"", start)) {
            val end = text.indexOf("\"\"\"", start + 3)
            return if (end < 0) text.length else end + 3
        }
        val quote = text[start]
        var index = start + 1
        while (index < text.length) {
            val c = text[index]
            when {
                c == '\\' -> index += 2
                c == quote -> return index + 1
                c == '\n' -> return index
                else -> index += 1
            }
        }
        return text.length
    }

    /**
     * The call's arguments split at the commas that belong to the call itself, not to a nested
     * call, lambda, index or literal. Comments were blanked before the call was cut out, so a
     * commented argument is neither an argument nor a positional one.
     */
    private fun topLevelArguments(arguments: String): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var index = 0
        while (index < arguments.length) {
            val c = arguments[index]
            when {
                c == '"' || c == '\'' -> {
                    val end = literalEnd(arguments, index)
                    current.append(arguments, index, end)
                    index = end
                    continue
                }
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

    /** The index of the `)` that closes the `(` at [open]; parentheses inside literals do not count. */
    private fun matchingParenthesis(text: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '"', '\'' -> {
                    index = literalEnd(text, index)
                    continue
                }
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            index += 1
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

        /** The call marker, whitespace and newlines allowed before the parenthesis. */
        val CALL_MARKER = Regex("\\bTrailVeilMapSurface\\s*\\(")
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
