package app.trailveil.map.fog

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-005` design §11: structural tripwire for the port's central inversion. The PoC's
 * move-started handler began a fog generation on EVERY camera move; the ported design may arm a
 * later input-transparent safety cover but must
 * never reach `beginGeneration`/`beginHandoverGeneration`/`beginRebuild` (or any overlay
 * mutation) from the camera-move-started handler. Hosted keyless CI runs this scan, so the
 * regression is visible without operator-run instrumentation.
 *
 * Stage-4 scaffolding: the coordinator is scanned today; every provider binding file matching
 * `*SurfaceBinding*.kt` under `src/googlePoc` joins the scan automatically the moment stage 5
 * lands one.
 */
class FogMoveStartReachabilityTest {

    @Test
    fun moveStartedHandlerNeverBeginsAGenerationOrTouchesOverlays() {
        val source = coordinatorSource()
        listOf("onCameraMoveStarted", "onCameraMoveFrame").forEach { handler ->
            val body = functionBody(source, handler)
            FORBIDDEN_FROM_MOVE_START.forEach { token ->
                assertFalse(
                    "$handler must not reach $token — the PoC's per-move revoke is the " +
                        "central inversion this port exists to remove",
                    body.contains(token),
                )
            }
        }
    }

    @Test
    fun moveStartedHandlerOnlyMarksStateAndRaisesTheCover() {
        val body = functionBody(coordinatorSource(), "onCameraMoveStarted")
        assertTrue(body.contains("fogCameraReaction"))
        assertTrue(body.contains("raiseCover"))
    }

    @Test
    fun providerBindingsNeverBeginAGenerationFromMoveStarted() {
        val bindings = googlePocRoot()
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .filter { file -> file.name.contains("SurfaceBinding") }
            .toList()
        assertTrue("no provider surface bindings were found to scan", bindings.isNotEmpty())
        var scanned = 0
        bindings.forEach { file ->
            val source = file.readText()
            // Scan whichever shape this binding uses. Anchoring only on the SDK listener quietly
            // skipped GoogleCanonicalFogSurfaceBinding — which matches the file filter, performs
            // the actual move-start fog work in `fun onCameraMoveStarted`, and never registers the
            // listener itself (GoogleMapSurfaceBinding forwards to it). The production file that
            // most needed this guard was the one file it did not read.
            val regions = buildList {
                source.substringAfter("setOnCameraMoveStartedListener", "")
                    .takeIf(String::isNotEmpty)
                    ?.let { add("setOnCameraMoveStartedListener" to bracedBlock(it)) }
                source.indexOf("fun onCameraMoveStarted")
                    .takeIf { it >= 0 }
                    ?.let { add("fun onCameraMoveStarted" to bracedBlock(source.substring(it))) }
            }
            if (regions.isEmpty()) return@forEach
            regions.forEach { (shape, body) ->
                scanned += 1
                FORBIDDEN_FROM_MOVE_START.forEach { token ->
                    assertFalse(
                        "${file.name} ($shape): move-start must not reach $token",
                        body.contains(token),
                    )
                }
            }
        }
        assertTrue(
            "the scan matched no move-start region in any binding, so it proved nothing",
            scanned > 0,
        )
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name")
        assertTrue("coordinator must declare $name", start >= 0)
        return bracedBlock(source.substring(start))
    }

    /** The first balanced `{...}` block in [text]. */
    private fun bracedBlock(text: String): String {
        val open = text.indexOf('{')
        require(open >= 0) { "no braced block found" }
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces")
    }

    private fun coordinatorSource(): String = File(
        repositoryRoot(),
        "app/src/main/java/app/trailveil/map/fog/FogOverlaySurfaceCoordinator.kt",
    ).readText()

    // `V02-008`: the Google provider bindings this case reads are in the shared production
    // tree; the harness half has no binding to reach a generation from.
    private fun googlePocRoot(): File = File(repositoryRoot(), "app/src/google/java")

    private fun repositoryRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(cwd, "settings.gradle.kts").isFile) cwd else requireNotNull(cwd.parentFile)
    }

    private companion object {
        val FORBIDDEN_FROM_MOVE_START = listOf(
            "beginGeneration",
            "beginHandoverGeneration",
            "beginRebuild",
            "attachOverlay",
            "removeOverlay",
            "clearTileCache",
        )
    }
}
