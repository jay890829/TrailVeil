package app.trailveil.map

import app.trailveil.map.fog.FogRenderStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-012` design 2 tripwires. A Google fog overlay pre-renders fully transparent, is revealed at
 * the shared fog opacity in the same turn as its predecessor is hidden, and is screen-verified
 * afterwards through the revealed-fog window; beneath the fog-coloured safety cover every overlay is
 * hidden. Nothing here may make the fog darker than the shared fog style, and no proof may run
 * with an overlay flipped opaque (the pulse the owner rejected).
 */
class GoogleFogOpacitySourceTest {
    @Test
    fun overlaysAttachHiddenAndAreRevealedAtTheSharedFogOpacity() {
        val binding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        assertTrue(binding.contains("const val HIDDEN_FOG_TRANSPARENCY = 1F"))
        assertTrue(
            "the visible opacity must be the shared fog alpha, not a second constant",
            binding.contains("val VISIBLE_FOG_TRANSPARENCY: Float = 1F - FogRenderStyle().fogAlpha / 255F"),
        )
        assertEquals(184, FogRenderStyle().fogAlpha)

        val attach = binding
            .substringAfter("override fun attachOverlay(generationId: Long)")
            .substringBefore("override fun revealOverlay(")
        assertTrue("a new generation attaches hidden", attach.contains("addOverlay(targetProvider, NEW_OVERLAY_Z, HIDDEN_FOG_TRANSPARENCY)"))
        assertTrue(
            "the bootstrap placeholder attaches hidden as well",
            binding.contains("addOverlay(requireNotNull(bootstrapProvider), OLD_OVERLAY_Z, HIDDEN_FOG_TRANSPARENCY)"),
        )

        val reveal = binding
            .substringAfter("override fun revealOverlay(generationId: Long, previousGenerationId: Long?)")
            .substringBefore("override fun removeOverlay(")
        val show = reveal.indexOf("val shown = overlays[generationId]?.setTransparencySafely(VISIBLE_FOG_TRANSPARENCY)")
        val hide = reveal.indexOf("overlays[previous]?.setTransparencySafely(HIDDEN_FOG_TRANSPARENCY)")
        assertTrue("the reveal shows the new overlay", show >= 0)
        assertTrue("and hides the previous one in the same function", hide > show)
        // V02-012 design 2i: the hide is conditional on the show. The only thing between them is
        // the abort path for a show that did not take, and it RETURNS, so what it posts cannot
        // delay a hide that never runs; on the path that does reach the hide, nothing is posted.
        val between = reveal.substring(show, hide)
        assertTrue("the hide is guarded by the show", between.contains("if (!shown)"))
        assertTrue("the guard aborts rather than falling through", between.contains("return"))
        assertFalse(
            "no posting between the two on the path that hides: one main-thread turn",
            between.substringAfter("return").contains("handler.post"),
        )

        val prove = binding
            .substringAfter("private val snapshotPort = object : FogSnapshotPort {")
            .substringBefore("private val cameraPort = object : FogCameraPort {")
        assertFalse(
            "verification never flips an overlay's opacity (the pulse the owner rejected)",
            prove.contains("setTransparencySafely"),
        )

        assertTrue(
            "the cover's rising edge hides every overlay beneath it",
            binding.contains("if (coordinator.coverUp && !coverWasUp) hideOverlaysBeneathCover()"),
        )
        val hideBody = functionBody(binding, "private fun hideOverlaysBeneathCover()")
        assertTrue(hideBody.contains("setTransparencySafely(HIDDEN_FOG_TRANSPARENCY)"))
        assertTrue(
            "a refuted installed generation is hidden beneath the cover again",
            binding.contains("if (!passed && coordinator.coverUp && coordinator.installedGenerationId == generationId) {"),
        )

        // V02-012 design 2: a translucent layer's colour cannot be read through the basemap
        // blend, so the palette cycle needs no rotation cover and the binding has no reason to
        // ask for one. The helper that used to arm it is gone; this pins the absence directly.
        assertFalse("colour cannot be read through a blend, so no rotation cover", binding.contains("onPaletteRotationDue"))
        assertFalse("the emptied rotation helper is gone", binding.contains("armPaletteRotationIfNeeded"))
    }

    @Test
    fun theCoordinatorRevealsAtDeliveryVerifiesAndLowersARaisedCoverOnlyOnThePassedVerdict() {
        val coordinator = moduleRoot()
            .resolve("src/main/java/app/trailveil/map/fog/FogOverlaySurfaceCoordinator.kt")
            .readText()
        val body = functionBody(coordinator, "private fun revealAndComplete(pendingNow: PendingRebuild)")
        val reveal = body.indexOf("overlayPort.revealOverlay(generationId, previous)")
        val hold = body.indexOf("if (coverUp) coverHeldForVerification = generationId")
        val verify = body.indexOf("snapshotPort.prove(generationId)")
        assertTrue(reveal >= 0 && hold > reveal && verify > hold)
        assertFalse(
            "the cover (a View) and the overlay (the renderer) are not presented in the same frame: " +
                "the reveal never lowers the cover itself",
            body.contains("lowerCover()"),
        )
        assertTrue(
            "a rebuild that completes outside the surround keeps the cover up and reveals nothing",
            body.contains("if (outsideSurround) {") && body.indexOf("if (outsideSurround) {") < reveal,
        )
        val verdict = functionBody(coordinator, "private fun onVerification(generationId: Long, previous: Long?, passed: Boolean)")
        val passedBranch = verdict.indexOf("if (passed) {")
        val release = verdict.indexOf("releaseHeldCover(generationId)")
        assertTrue("only a passed verdict releases the held cover", passedBranch >= 0 && release > passedBranch)
        assertFalse("a failed verdict never lowers anything", verdict.contains("lowerCover()"))
        val releaseBody = functionBody(coordinator, "private fun releaseHeldCover(generationId: Long)")
        assertTrue(releaseBody.contains("if (coverHeldForVerification == generationId && installedGenerationId == generationId) {"))
        assertTrue(releaseBody.contains("lowerCover()"))
        assertTrue("a failed verification fails closed", verdict.contains("raiseCover(FogCoverReason.RUNTIME_FAILURE)"))
        assertTrue(
            "a failed verdict beneath a cover the camera raised meanwhile is moot",
            verdict.contains("if (coverUp && coverHeldForVerification != generationId) {") &&
                verdict.substringAfter("if (coverUp && coverHeldForVerification != generationId) {").substringBefore("}").contains("return"),
        )
        assertTrue(
            "any raise cancels the hold",
            functionBody(coordinator, "private fun raiseCover(reason: FogCoverReason)").contains("coverHeldForVerification = null"),
        )
        val start = functionBody(coordinator, "fun onStart()")
        assertTrue(
            "a host start never replaces the in-flight verification in the single-run prover",
            start.indexOf("if (verificationInFlight == installed) return") < start.indexOf("snapshotPort.prove(installed)"),
        )
        assertTrue("an on-start pass releases a held cover", start.contains("releaseHeldCover(installed)"))
        assertFalse(coordinator.contains("private fun completeInstall("))
    }

    @Test
    fun theProverReadsTheRevealedFogWindowAndTheCoverIsFogColouredAndTranslucent() {
        val prover = googleSource("GoogleFogSnapshotProver.kt")
        assertTrue(prover.contains("FogTilePngCodec.matchesRevealedFog("))
        assertFalse("no opaque-only pixel rule survives", prover.contains("Color.alpha(pixel) == 255"))

        val cover = googleSource("GoogleFogSafetyOverlay.kt")
        val colour = cover
            .substringAfter("private val drawable = FogCoverDrawable(")
            .substringBefore("private var visible")
        assertTrue(colour.contains("FogTilePngCodec.REVEALED_FOG_ALPHA"))
        assertTrue(colour.contains("FogTilePngCodec.DEFAULT_FOG_COLOR.red"))
        assertTrue(colour.contains("FogTilePngCodec.DEFAULT_FOG_COLOR.green"))
        assertTrue(colour.contains("FogTilePngCodec.DEFAULT_FOG_COLOR.blue"))
        assertTrue(cover.contains("override fun getOpacity(): Int = PixelFormat.TRANSLUCENT"))
        assertFalse(
            "nothing may dial the cover's paint alpha below the fog's",
            Regex("""drawable\.(alpha|setAlpha)\s*[=(]""").containsMatchIn(cover),
        )
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("unterminated $signature")
    }

    private fun googleSource(name: String): String = moduleRoot()
        .resolve("src/google/java/app/trailveil/map/$name")
        .readText()

    private fun moduleRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(cwd, "settings.gradle.kts").isFile) File(cwd, "app") else cwd
    }
}
