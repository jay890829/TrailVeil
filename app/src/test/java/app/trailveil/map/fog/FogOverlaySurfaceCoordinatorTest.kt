package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-005` design §11: the coordinator state machine against fake ports. What the fakes cannot
 * prove — SDK threading and callback ordering — was measured by SP6/SP8/SP9/SP10 in stage 3
 * before this machinery hardens (`V02-005-spikes.md`).
 */
class FogOverlaySurfaceCoordinatorTest {

    private class FakeOverlayPort : FogOverlayPort {
        val log = mutableListOf<String>()
        private var nextGenerationId = 0L

        override fun beginRebuild(handover: Boolean, paletteRotation: Boolean): Long {
            val id = ++nextGenerationId
            log += "begin($id,handover=$handover,rotation=$paletteRotation)"
            return id
        }

        override fun attachOverlay(generationId: Long) {
            log += "attach($generationId)"
        }

        override fun revealOverlay(generationId: Long, previousGenerationId: Long?) {
            log += "reveal($generationId,previous=$previousGenerationId)"
        }

        var removeSucceeds = true

        override fun removeOverlay(generationId: Long): Boolean {
            log += "remove($generationId)"
            return removeSucceeds
        }

        override fun clearTileCache() {
            log += "clearTileCache"
        }

        override fun cancelRebuild(generationId: Long) {
            log += "cancel($generationId)"
        }
    }

    private class FakeSnapshotPort : FogSnapshotPort {
        var result = true
        var deliverImmediately = true
        val proveRequests = mutableListOf<Long>()
        val heldCallbacks = mutableListOf<Pair<Long, (Boolean) -> Unit>>()

        override fun prove(generationId: Long, onResult: (Boolean) -> Unit) {
            proveRequests += generationId
            if (deliverImmediately) onResult(result) else heldCallbacks += generationId to onResult
        }
    }

    private class FakeCameraPort : FogCameraPort {
        var inside = true
        override fun insidePublishedSurround(): Boolean = inside
        var pendingInsideOverride: Boolean? = null
        override fun insidePendingSurround(): Boolean = pendingInsideOverride ?: inside
    }

    // ---- V02-012 design 2i: the three findings of the independent verifier --------------------

    @Test
    fun `a generation delivered outside its own surround raises the cover before the predecessor leaves`() {
        val harness = Harness()
        val first = harness.firstInstall()
        assertFalse("the first install lowered its cover", harness.coordinator.coverUp)
        harness.overlay.log.clear()

        // The camera is still inside the INSTALLED generation's coverage, so nothing on the
        // movement path raised a cover; the generation being delivered does not cover it. Before
        // this fix the predecessor was removed here with nothing visible in its place, and the
        // screen showed bare basemap for the whole of the follow-up render.
        harness.coordinator.onCanonicalRefreshRequired()
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.camera.pendingInsideOverride = false
        harness.coordinator.onGenerationPublished(second)
        harness.coordinator.onDeliveryBarrierDrained(second)

        assertTrue("the cover must be up before anything is removed", harness.coordinator.coverUp)
        assertTrue(
            "nothing is revealed for a generation delivered outside its surround",
            harness.overlay.log.none { entry -> entry.startsWith("reveal(") },
        )
        val raised = harness.coordinator.recentTransitions.indexOfFirst { it.startsWith("raise:") }
        val removed = harness.overlay.log.indexOfFirst { it == "remove($first)" }
        assertTrue("the predecessor is removed", removed >= 0)
        assertTrue("the cover was raised, and before the removal", raised >= 0)
    }

    @Test
    fun `a superseded verification does not strand the predecessor it would have removed`() {
        val harness = Harness()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onFirstComposition()
        val first = harness.install()

        // Two more generations complete while the first verdict is still outstanding. The
        // snapshot port runs one proof at a time, so the replaced callbacks never return and the
        // removal that lives in them never runs; the next reveal has to do it instead.
        harness.coordinator.onCanonicalRefreshRequired()
        val second = harness.install()
        assertTrue(
            "the first generation is still held by the outstanding verdict",
            harness.overlay.log.none { entry -> entry == "remove($first)" },
        )

        harness.coordinator.onCanonicalRefreshRequired()
        val third = harness.install()

        assertTrue(
            "the stranded predecessor is removed by the next reveal",
            harness.overlay.log.any { entry -> entry == "remove($first)" },
        )
        assertTrue("three distinct generations", first < second && second < third)
        assertEquals(third, harness.coordinator.installedGenerationId)
    }

    @Test
    fun `a reveal the overlay port could not show fails closed instead of leaving nothing visible`() {
        val harness = Harness()
        val installed = harness.firstInstall()
        assertFalse(harness.coordinator.coverUp)
        harness.overlay.log.clear()

        harness.coordinator.onRevealFailed(installed)

        assertTrue("the cover goes up", harness.coordinator.coverUp)
        assertEquals(FogCoverReason.RUNTIME_FAILURE, harness.coordinator.coverReason)
        assertTrue(
            "a rebuild follows",
            harness.overlay.log.any { entry -> entry.startsWith("begin(") },
        )
        assertTrue(
            "the predecessor stays: it is the only proven thing on screen",
            harness.overlay.log.none { entry -> entry.startsWith("remove(") },
        )
    }

    @Test
    fun `a reveal failure for a generation that is no longer installed changes nothing`() {
        val harness = Harness()
        harness.firstInstall()
        harness.overlay.log.clear()

        harness.coordinator.onRevealFailed(9_999L)

        assertFalse(harness.coordinator.coverUp)
        assertTrue(harness.overlay.log.isEmpty())
    }

    private class Harness {
        val overlay = FakeOverlayPort()
        val snapshot = FakeSnapshotPort()
        val camera = FakeCameraPort()
        val coordinator = FogOverlaySurfaceCoordinator(overlay, snapshot, camera)

        /** Drives one full successful install of the next generation and returns its id. */
        fun install(): Long {
            coordinator.onCameraIdle()
            val id = requireNotNull(coordinator.pendingGenerationId) { "no rebuild began" }
            coordinator.onGenerationPublished(id)
            coordinator.onDeliveryBarrierDrained(id)
            check(coordinator.installedGenerationId == id) { "install did not complete" }
            return id
        }

        fun firstInstall(): Long {
            coordinator.onFirstComposition()
            return install()
        }
    }

    // ---- stage 9: a stranded pending install restarts, it does not fail -----------------------

    @Test
    fun `a stranded first install restarts over a new generation with the cover unchanged`() {
        val harness = Harness()
        harness.coordinator.onFirstComposition()
        harness.coordinator.onCameraIdle()
        val first = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(first)
        val coverBefore = harness.coordinator.coverUp to harness.coordinator.coverReason

        harness.coordinator.onPendingRenderStranded(first)

        val second = requireNotNull(harness.coordinator.pendingGenerationId) { "no restart began" }
        assertTrue("the restart is a new generation", second != first)
        assertEquals(
            listOf("cancel($first)", "remove($first)", "begin($second,handover=false,rotation=false)"),
            harness.overlay.log.takeLast(3),
        )
        assertEquals("cover exactly as it was", coverBefore, harness.coordinator.coverUp to harness.coordinator.coverReason)
        assertFalse("a restart is not a failure", harness.coordinator.terminal)
        assertFalse("a restart is not a retry", harness.coordinator.retryScheduled)
        assertNull(harness.coordinator.installedGenerationId)

        // The restarted generation installs like any other.
        harness.coordinator.onGenerationPublished(second)
        harness.coordinator.onDeliveryBarrierDrained(second)
        assertEquals(second, harness.coordinator.installedGenerationId)
        assertFalse(harness.coordinator.coverUp)
    }

    @Test
    fun `a stranded handover restarts in handover mode and a stale id is ignored`() {
        val harness = Harness()
        val installed = harness.firstInstall()
        harness.camera.inside = false
        harness.coordinator.onCameraIdle()
        val pending = requireNotNull(harness.coordinator.pendingGenerationId)
        val logBefore = harness.overlay.log.size

        harness.coordinator.onPendingRenderStranded(pending + 100L)
        assertEquals("a stale id changes nothing", logBefore, harness.overlay.log.size)
        assertEquals(pending, harness.coordinator.pendingGenerationId)

        harness.coordinator.onPendingRenderStranded(pending)
        val restarted = requireNotNull(harness.coordinator.pendingGenerationId)
        assertTrue(restarted != pending)
        assertTrue(
            "steady state restarts as a handover so the proven set keeps serving",
            harness.overlay.log.last() == "begin($restarted,handover=true,rotation=false)",
        )
        assertEquals("the proven generation still serves", installed, harness.coordinator.installedGenerationId)
        assertFalse(harness.coordinator.terminal)
    }

    // ---- §11 row 1: gesture is accepted, then covered only after a real surround exit --------

    @Test
    fun `gesture move start stays uncovered but a surround exit raises the safety cover`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.GESTURE)
        assertFalse("move-start remains input-visible", harness.coordinator.coverUp)
        harness.camera.inside = false
        harness.coordinator.onCameraMoveFrame()
        assertTrue(harness.coordinator.coverUp)
        assertEquals(FogCoverReason.VIEWPORT_EXIT, harness.coordinator.coverReason)
        harness.coordinator.onCameraIdle()
        assertTrue("cover remains until the new viewport is proven", harness.coordinator.coverUp)
        assertTrue(
            "gesture idle outside the surround begins a handover",
            harness.overlay.log.last().startsWith("begin(2,handover=true"),
        )
    }

    @Test
    fun `gesture idle outside surround closes a missed move-frame window`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.GESTURE)
        harness.camera.inside = false
        harness.coordinator.onCameraIdle()
        assertTrue(harness.coordinator.coverUp)
        assertEquals(FogCoverReason.VIEWPORT_EXIT, harness.coordinator.coverReason)
    }

    // ---- §11 row 2: follow ease inside the surround does zero fog work -----------------------

    @Test
    fun `follow ease inside surround performs zero fog work`() {
        val harness = Harness()
        harness.firstInstall()
        val before = harness.overlay.log.toList()
        val ticket = harness.coordinator.beginFollowEase()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        harness.coordinator.onCameraMoveFrame()
        assertTrue(harness.coordinator.endFollowEase(ticket))
        harness.coordinator.onCameraIdle()
        assertEquals("no overlay/generation calls at all", before, harness.overlay.log)
        assertFalse(harness.coordinator.coverUp)
    }

    @Test
    fun `follow ease leaving surround raises the viewport safety cover`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.beginFollowEase()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        harness.camera.inside = false
        harness.coordinator.onCameraMoveFrame()
        assertTrue(harness.coordinator.coverUp)
        assertEquals(FogCoverReason.VIEWPORT_EXIT, harness.coordinator.coverReason)
    }

    // ---- §11 row 3: programmed jump beyond the surround raises the cover ---------------------

    @Test
    fun `programmed jump beyond surround raises the cover at move start`() {
        val harness = Harness()
        harness.firstInstall()
        harness.camera.inside = false
        harness.coordinator.beginProgrammedFlight()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        assertTrue(harness.coordinator.coverUp)
        assertEquals(FogCoverReason.PROGRAMMED_EXIT, harness.coordinator.coverReason)
    }

    @Test
    fun `programmed flight exiting the surround mid-air raises the cover reactively`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.beginProgrammedFlight()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        assertFalse("inside the surround at move-start", harness.coordinator.coverUp)
        harness.camera.inside = false
        harness.coordinator.onCameraMoveFrame()
        assertTrue(harness.coordinator.coverUp)
        assertEquals(FogCoverReason.PROGRAMMED_EXIT, harness.coordinator.coverReason)
    }

    // ---- §11 row 4: idle inside surround with no canonical change is a no-op -----------------

    @Test
    fun `idle inside surround with nothing dirty is a complete no-op`() {
        val harness = Harness()
        harness.firstInstall()
        val before = harness.overlay.log.toList()
        harness.coordinator.onCameraIdle()
        harness.coordinator.onCameraIdle()
        assertEquals(before, harness.overlay.log)
    }

    @Test
    fun `rapid idles reuse the pending generation`() {
        val harness = Harness()
        harness.coordinator.onFirstComposition()
        harness.coordinator.onCameraIdle()
        harness.coordinator.onCameraIdle()
        harness.coordinator.onCameraIdle()
        assertEquals(
            "exactly one rebuild began",
            1,
            harness.overlay.log.count { it.startsWith("begin(") },
        )
    }

    // ---- §11 row 5: REFRESH handover with add-before-remove ordering -------------------------

    @Test
    fun `refresh reveals the new overlay at delivery and removes the old one only after the verdict`() {
        val harness = Harness()
        val first = harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        assertTrue(harness.overlay.log.contains("begin($second,handover=true,rotation=false)"))
        harness.coordinator.onGenerationPublished(second)
        harness.coordinator.onDeliveryBarrierDrained(second)
        assertTrue("new overlay attached", harness.overlay.log.contains("attach($second)"))
        assertTrue(
            "design 2: the delivered generation is revealed in the same turn the old one is hidden",
            harness.overlay.log.contains("reveal($second,previous=$first)"),
        )
        assertEquals("design 2: installed at delivery", second, harness.coordinator.installedGenerationId)
        assertFalse("design 2: the cover is down from the reveal on", harness.coordinator.coverUp)
        assertFalse(
            "old overlay must NOT be removed before the verification verdict",
            harness.overlay.log.contains("remove($first)"),
        )
        assertFalse("no in-place clearTileCache on the refresh path (SP9)", harness.overlay.log.contains("clearTileCache"))
        val (provenId, callback) = harness.snapshot.heldCallbacks.single()
        assertEquals(second, provenId)
        callback(true)
        assertTrue("old overlay removed after the verdict", harness.overlay.log.contains("remove($first)"))
        assertTrue(
            harness.overlay.log.indexOf("reveal($second,previous=$first)") <
                harness.overlay.log.indexOf("remove($first)"),
        )
        assertEquals(second, harness.coordinator.installedGenerationId)
        assertFalse(harness.coordinator.coverUp)
    }

    @Test
    fun `a reveal beneath a raised cover keeps the cover up until the verdict passes`() {
        val harness = Harness()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onFirstComposition()
        harness.coordinator.onCameraIdle()
        val first = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(first)
        harness.coordinator.onDeliveryBarrierDrained(first)
        assertTrue("revealed beneath the cover", harness.overlay.log.contains("reveal($first,previous=null)"))
        assertEquals(first, harness.coordinator.installedGenerationId)
        assertTrue(
            "the View cover and the renderer's overlay are not presented in the same frame: the cover " +
                "stays up until the renderer's own snapshot shows the revealed overlay",
            harness.coordinator.coverUp,
        )
        assertEquals(FogCoverReason.FIRST_COMPOSITION, harness.coordinator.coverReason)
        val (provenId, callback) = harness.snapshot.heldCallbacks.single()
        assertEquals(first, provenId)
        callback(true)
        assertFalse("the passed verdict lowers the held cover", harness.coordinator.coverUp)
        assertNull(harness.coordinator.coverReason)
    }

    @Test
    fun `a host start during an in-flight verification does not replace it`() {
        val harness = Harness()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onFirstComposition()
        harness.coordinator.onCameraIdle()
        val first = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(first)
        harness.coordinator.onDeliveryBarrierDrained(first)
        assertEquals(listOf(first), harness.snapshot.proveRequests)
        harness.coordinator.onStart()
        assertEquals(
            "the single-run prover would replace the reveal's verification and its verdict would never come",
            listOf(first),
            harness.snapshot.proveRequests,
        )
        harness.snapshot.heldCallbacks.single().second(true)
        assertFalse(harness.coordinator.coverUp)
        // With no verification in flight, a later start re-proves as before.
        harness.coordinator.onStart()
        assertEquals(listOf(first, first), harness.snapshot.proveRequests)
        harness.snapshot.heldCallbacks.last().second(true)
        assertFalse(harness.coordinator.coverUp)
        assertTrue(harness.coordinator.recentTransitions.contains("start:prove:$first"))
    }

    @Test
    fun `a cover the camera raises during a held verification is not lowered by the verdict`() {
        val harness = Harness()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onFirstComposition()
        harness.coordinator.onCameraIdle()
        val first = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(first)
        harness.coordinator.onDeliveryBarrierDrained(first)
        assertTrue(harness.coordinator.coverUp)
        harness.camera.inside = false
        harness.coordinator.beginProgrammedFlight()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        assertEquals(FogCoverReason.PROGRAMMED_EXIT, harness.coordinator.coverReason)
        harness.snapshot.heldCallbacks.single().second(true)
        assertTrue("the camera's cover outlives the verdict", harness.coordinator.coverUp)
        assertEquals(FogCoverReason.PROGRAMMED_EXIT, harness.coordinator.coverReason)
    }

    @Test
    fun `a failed verdict beneath a cover the camera raised after the reveal is moot`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(second)
        harness.coordinator.onDeliveryBarrierDrained(second)
        assertFalse("revealed at rest: no cover", harness.coordinator.coverUp)
        // A gesture leaves the surround before the verdict: the cover is up for the camera, every
        // overlay is hidden beneath it, and the snapshot that follows cannot pass.
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.GESTURE)
        harness.camera.inside = false
        harness.coordinator.onCameraMoveFrame()
        assertTrue(harness.coordinator.coverUp)
        assertEquals(FogCoverReason.VIEWPORT_EXIT, harness.coordinator.coverReason)
        val begins = harness.overlay.log.count { it.startsWith("begin(") }
        harness.snapshot.heldCallbacks.single().second(false)
        assertEquals("the camera's reason stands, not RUNTIME_FAILURE", FogCoverReason.VIEWPORT_EXIT, harness.coordinator.coverReason)
        assertEquals("no rebuild began on the moot verdict: the idle rebuild re-verifies", begins, harness.overlay.log.count { it.startsWith("begin(") })
        assertEquals(second, harness.coordinator.installedGenerationId)
    }

    @Test
    fun `movement ignores pending wider coverage but completion validates that pending coverage`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(second)

        // The camera has entered G2\G1: G1 is no longer safe, while the rendered-but-unproven
        // G2 surround contains it. Movement must still raise the synchronous cover from the
        // installed read; the pending read is reserved for the completion staleness check.
        harness.camera.inside = false
        harness.camera.pendingInsideOverride = true
        harness.coordinator.beginProgrammedFlight()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        assertTrue("pending coverage must not suppress the movement cover", harness.coordinator.coverUp)
        assertEquals(FogCoverReason.PROGRAMMED_EXIT, harness.coordinator.coverReason)

        // Once G2's actual delivery and visual proof complete, completion may use G2's own
        // coverage to lower the cover even though G1 (the old installed set) no longer covers it.
        harness.coordinator.onDeliveryBarrierDrained(second)
        harness.snapshot.heldCallbacks.single().second(true)
        assertEquals(second, harness.coordinator.installedGenerationId)
        assertFalse("the proven G2 coverage now covers the camera", harness.coordinator.coverUp)
    }

    @Test
    fun `handover failure before the reveal keeps the old published set serving without a cover`() {
        val harness = Harness()
        val first = harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(second)
        // Design 2: a failure BEFORE delivery (here the install timeout) is the handover failure
        // that keeps the old set serving; a failed verification after the reveal fails closed
        // instead (its own case above).
        harness.coordinator.onInstallTimeout(second)
        assertEquals(first, harness.coordinator.installedGenerationId)
        assertTrue(harness.overlay.log.contains("cancel($second)"))
        assertTrue("the failed new overlay leaves", harness.overlay.log.contains("remove($second)"))
        assertFalse("never a cover while proven coverage stands", harness.coordinator.coverUp)
        assertFalse(harness.coordinator.terminal)
        assertTrue(harness.coordinator.retryScheduled)
        harness.snapshot.result = true
        harness.coordinator.onRetryFogOperation()
        assertTrue(
            "the 1 s cadence begins a fresh handover",
            harness.overlay.log.last().startsWith("begin(3,handover=true"),
        )
    }

    @Test
    fun `a failed verification fails closed and the handover rebuild that follows clears it`() {
        val harness = Harness()
        val first = harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.result = false
        harness.coordinator.onCameraIdle()
        val failed = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(failed)
        harness.coordinator.onDeliveryBarrierDrained(failed)
        // Design 2: the generation was revealed, its verification failed, so the cover is up
        // again (every overlay hidden beneath it in the binding) and a handover rebuild is
        // already pending; the hidden previous overlay left on the verdict.
        assertTrue("a failed verification raises the cover", harness.coordinator.coverUp)
        assertEquals(FogCoverReason.RUNTIME_FAILURE, harness.coordinator.coverReason)
        assertTrue(harness.overlay.log.contains("remove($first)"))
        assertFalse("no scheduled retry: the rebuild begins at once", harness.coordinator.retryScheduled)

        harness.snapshot.result = true
        val retried = requireNotNull(harness.coordinator.pendingGenerationId)
        assertTrue(retried != failed)
        harness.coordinator.onGenerationPublished(retried)
        harness.coordinator.onDeliveryBarrierDrained(retried)

        assertFalse(
            "a proven retry must not leave the unavailable badge/gate latched",
            harness.coordinator.retryScheduled,
        )
        assertEquals(retried, harness.coordinator.installedGenerationId)
    }

    // ---- §11 row 6: palette rotation ---------------------------------------------------------

    @Test
    fun `palette rotation attaches the new overlay before removing the old and covers until re-proof`() {
        val harness = Harness()
        val first = harness.firstInstall()
        harness.coordinator.onPaletteRotationDue()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        assertTrue(harness.overlay.log.contains("begin($second,handover=true,rotation=true)"))
        assertTrue("cover up for the rotation window", harness.coordinator.coverUp)
        assertEquals(FogCoverReason.PALETTE_ROTATION, harness.coordinator.coverReason)
        harness.coordinator.onGenerationPublished(second)
        assertFalse("old stays until target delivery", harness.overlay.log.contains("remove($first)"))
        harness.coordinator.onDeliveryBarrierDrained(second)
        assertTrue("old leaves before same-colour proof", harness.overlay.log.contains("remove($first)"))
        assertTrue(
            harness.overlay.log.indexOf("attach($second)") <
                harness.overlay.log.indexOf("remove($first)"),
        )
        assertTrue(
            "design 2: revealed with no previous overlay left to hide",
            harness.overlay.log.contains("reveal($second,previous=null)"),
        )
        assertTrue("design 2b: the rotation cover is held until the verdict", harness.coordinator.coverUp)
        assertEquals(second, harness.coordinator.installedGenerationId)
        harness.snapshot.heldCallbacks.single().second(true)
        assertFalse("the passed verdict lowers it", harness.coordinator.coverUp)
        assertEquals(second, harness.coordinator.installedGenerationId)
        // The rotation debt is consumed: the next idle is a no-op.
        val settled = harness.overlay.log.toList()
        harness.coordinator.onCameraIdle()
        assertEquals(settled, harness.overlay.log)
    }

    @Test
    fun `palette old-overlay removal failure is terminal before snapshot`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onPaletteRotationDue()
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(second)
        harness.overlay.removeSucceeds = false

        harness.coordinator.onDeliveryBarrierDrained(second)

        assertTrue(harness.coordinator.terminal)
        assertTrue(harness.coordinator.coverUp)
        assertTrue(harness.snapshot.proveRequests.none { it == second })
    }

    // ---- §11 row 7: flight-ticket CAS vs posted-late cancel ----------------------------------

    @Test
    fun `stale flight terminal is rejected by CAS and never clears the live claim`() {
        val harness = Harness()
        val superseded = harness.coordinator.beginProgrammedFlight()
        val live = harness.coordinator.beginProgrammedFlight()
        // SP10: the superseded flight's cancel dispatches AFTER the replacement's claim.
        assertFalse(harness.coordinator.endProgrammedFlight(superseded))
        assertTrue(harness.coordinator.programmedFlightActive)
        assertTrue(harness.coordinator.endProgrammedFlight(live))
        assertFalse(harness.coordinator.programmedFlightActive)
    }

    @Test
    fun `stale follow ease terminal keeps the live ease exemption armed`() {
        val harness = Harness()
        val superseded = harness.coordinator.beginFollowEase()
        val live = harness.coordinator.beginFollowEase()
        assertFalse(harness.coordinator.endFollowEase(superseded))
        assertTrue("the live ease still owns the exemption", harness.coordinator.followStepInFlight)
        assertTrue(harness.coordinator.endFollowEase(live))
        assertFalse(harness.coordinator.followStepInFlight)
    }

    @Test
    fun `a programmed flight superseding an airborne follow ease drops the ease exemption`() {
        val harness = Harness()
        harness.firstInstall()
        val ease = harness.coordinator.beginFollowEase()
        // A recentre supersedes the ease. SP10: the ease's cancel arrives AFTER this claim, so
        // its CAS fails — the exemption must already be gone, or every later programmed jump
        // would skip the §4(b) cover forever.
        harness.coordinator.beginProgrammedFlight()
        assertFalse(harness.coordinator.followStepInFlight)
        assertFalse("the stale ease terminal is still rejected", harness.coordinator.endFollowEase(ease))
        assertFalse(harness.coordinator.followStepInFlight)

        harness.camera.inside = false
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        assertTrue("the programmed-exit cover is armed again", harness.coordinator.coverUp)
        assertEquals(FogCoverReason.PROGRAMMED_EXIT, harness.coordinator.coverReason)
    }

    @Test
    fun `a superseded ease terminal never leaves the exemption armed`() {
        val harness = Harness()
        harness.firstInstall()
        val first = harness.coordinator.beginFollowEase()
        harness.coordinator.beginFollowEase()
        assertFalse(harness.coordinator.endFollowEase(first))
        assertTrue("the live ease still owns the exemption", harness.coordinator.followStepInFlight)
        harness.coordinator.beginProgrammedFlight()
        assertFalse(harness.coordinator.followStepInFlight)
    }

    // ---- §11 row 8: ON_START re-proof --------------------------------------------------------

    @Test
    fun `on start re-proof failure raises the cover and re-renders`() {
        val harness = Harness()
        harness.firstInstall()
        harness.snapshot.result = false
        harness.coordinator.onStart()
        assertTrue(harness.coordinator.coverUp)
        assertEquals(FogCoverReason.RUNTIME_FAILURE, harness.coordinator.coverReason)
        assertTrue(
            "a rebuild began",
            harness.overlay.log.last().startsWith("begin(2,handover=true"),
        )
    }

    @Test
    fun `on start re-proof success changes nothing`() {
        val harness = Harness()
        harness.firstInstall()
        val before = harness.overlay.log.toList()
        harness.coordinator.onStart()
        assertEquals(before, harness.overlay.log)
        assertFalse(harness.coordinator.coverUp)
    }

    @Test
    fun `a late on start verdict never clobbers a rebuild that began meanwhile`() {
        val harness = Harness()
        harness.firstInstall()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onStart()
        val reProof = harness.snapshot.heldCallbacks.single()
        // A legitimate rebuild begins while the ON_START verdict is outstanding, and gets as far
        // as attaching its overlay.
        harness.coordinator.onCanonicalRefreshRequired()
        harness.coordinator.onCameraIdle()
        val concurrent = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(concurrent)
        val before = harness.overlay.log.toList()

        reProof.second(false)

        assertEquals("the stale verdict changed nothing", before, harness.overlay.log)
        assertEquals(
            "the concurrent rebuild is still the pending one",
            concurrent,
            harness.coordinator.pendingGenerationId,
        )
        assertFalse(harness.coordinator.coverUp)
        // And it can still complete normally — no orphaned overlay was left behind.
        harness.coordinator.onDeliveryBarrierDrained(concurrent)
        harness.snapshot.heldCallbacks.last().second(true)
        assertEquals(concurrent, harness.coordinator.installedGenerationId)
        assertEquals(
            "exactly one overlay attach per generation, one remove for the superseded one",
            1,
            harness.overlay.log.count { it.startsWith("remove(") },
        )
    }

    @Test
    fun `an install completing outside the surround keeps the cover up through the follow-up rebuild`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onCameraIdle()
        val pending = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(pending)
        harness.coordinator.onDeliveryBarrierDrained(pending)
        assertEquals("design 2: revealed and installed at delivery", pending, harness.coordinator.installedGenerationId)
        // A programmed jump exits the published surround while the verification is in flight.
        harness.camera.inside = false
        harness.coordinator.beginProgrammedFlight()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        assertTrue(harness.coordinator.coverUp)

        harness.snapshot.heldCallbacks.single().second(true)

        assertEquals(pending, harness.coordinator.installedGenerationId)
        assertTrue(
            "a passed verification never lowers a cover the camera raised meanwhile",
            harness.coordinator.coverUp,
        )
        assertEquals(FogCoverReason.PROGRAMMED_EXIT, harness.coordinator.coverReason)
        // The flight ends in an idle, which begins the handover for the new viewport.
        harness.coordinator.onCameraIdle()
        assertTrue(
            "a follow-up handover began for the new viewport",
            harness.overlay.log.last().startsWith("begin(3,handover=true"),
        )
        assertTrue("the cover stays up through that rebuild", harness.coordinator.coverUp)
    }

    @Test
    fun `a viewport idle swallowed by generation reuse is not lost`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onCameraIdle()
        val pending = requireNotNull(harness.coordinator.pendingGenerationId)
        val rebuildsBefore = harness.overlay.log.count { it.startsWith("begin(") }
        // A programmed move inside the surround marks the viewport dirty (REBUILD_AT_IDLE), but
        // its idle is swallowed by generation reuse — completeInstall must carry that debt or
        // the new viewport waits for the next camera move that may never come.
        harness.coordinator.beginProgrammedFlight()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        harness.coordinator.onCameraIdle()
        assertEquals(
            "no second rebuild while one is pending",
            rebuildsBefore,
            harness.overlay.log.count { it.startsWith("begin(") },
        )
        harness.coordinator.onGenerationPublished(pending)
        harness.coordinator.onDeliveryBarrierDrained(pending)
        assertTrue(
            "the swallowed viewport idle triggers the follow-up rebuild at delivery",
            harness.overlay.log.any { it.startsWith("begin(3,handover=true") },
        )
        harness.snapshot.heldCallbacks.single().second(true)
        assertEquals("the follow-up is the pending one", 3L, harness.coordinator.pendingGenerationId)
    }

    // ---- §11 row 9: per-composition terminal classification ----------------------------------

    @Test
    fun `nothing-proven failure is terminal for this composition only`() {
        val ports = Harness()
        ports.coordinator.onFirstComposition()
        ports.coordinator.onCameraIdle()
        val id = requireNotNull(ports.coordinator.pendingGenerationId)
        ports.coordinator.onGenerationRenderFailed(id)
        assertTrue(ports.coordinator.terminal)
        assertTrue("cover stays up on terminal", ports.coordinator.coverUp)
        val settled = ports.overlay.log.toList()
        ports.coordinator.onCameraIdle()
        assertEquals("a terminal coordinator does nothing further", settled, ports.overlay.log)

        // A fresh composition constructs a fresh coordinator and retries from scratch — the
        // classification is never latched anywhere shared.
        val recomposed = Harness()
        recomposed.firstInstall()
        assertFalse(recomposed.coordinator.terminal)
        assertFalse(recomposed.coordinator.coverUp)
    }

    // ---- oracle-integrity control (design §11, coordinator level) ----------------------------

    @Test
    fun `a verification that keeps failing raises the cover again and rebuilds until the deadline`() {
        val harness = Harness()
        harness.snapshot.result = false
        harness.coordinator.onFirstComposition()
        harness.coordinator.onCameraIdle()
        val id = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(id)
        harness.coordinator.onDeliveryBarrierDrained(id)
        assertTrue("a bare-basemap verdict raises the cover again", harness.coordinator.coverUp)
        assertEquals(FogCoverReason.RUNTIME_FAILURE, harness.coordinator.coverReason)
        assertEquals("design 2: the revealed generation is the installed one", id, harness.coordinator.installedGenerationId)
        val rebuild = requireNotNull(harness.coordinator.pendingGenerationId) { "a handover rebuild must be pending" }
        assertTrue(rebuild != id)
        // Every further attempt fails the same way; the binding's cover deadline ends it.
        harness.coordinator.onGenerationPublished(rebuild)
        harness.coordinator.onDeliveryBarrierDrained(rebuild)
        assertTrue(harness.coordinator.coverUp)
        harness.coordinator.onCoverDeadlineExceeded()
        assertTrue("bounded: the cover deadline makes the composition terminal", harness.coordinator.terminal)
    }

    // ---- interleavings -----------------------------------------------------------------------

    @Test
    fun `staleness during a rebuild triggers an immediate follow-up handover`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.deliverImmediately = false
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(second)
        // Canonical content changes again while the render is in flight; the camera is
        // stationary, so no further idle will arrive.
        harness.coordinator.onCanonicalRefreshRequired()
        harness.coordinator.onDeliveryBarrierDrained(second)
        assertEquals(second, harness.coordinator.installedGenerationId)
        assertTrue(
            "a follow-up handover began at delivery without waiting for an idle",
            harness.overlay.log.any { it.startsWith("begin(3,handover=true") },
        )
        harness.snapshot.heldCallbacks.single().second(true)
        assertEquals(3L, harness.coordinator.pendingGenerationId)
    }

    @Test
    fun `install timeout on the first install is terminal and cover stays up`() {
        val harness = Harness()
        harness.coordinator.onFirstComposition()
        harness.coordinator.onCameraIdle()
        val id = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(id)
        harness.coordinator.onInstallTimeout(id)
        assertTrue(harness.coordinator.terminal)
        assertTrue(harness.coordinator.coverUp)
        assertTrue(harness.overlay.log.contains("cancel($id)"))
    }

    @Test
    fun `persistent viewport-exit failure reaches a bounded terminal cover deadline`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.GESTURE)
        harness.camera.inside = false
        harness.coordinator.onCameraMoveFrame()
        harness.coordinator.onCameraIdle()
        assertTrue(harness.coordinator.coverUp)
        assertFalse(harness.coordinator.terminal)

        harness.coordinator.onCoverDeadlineExceeded()

        assertTrue(harness.coordinator.terminal)
        assertEquals(FogCoverReason.RUNTIME_FAILURE, harness.coordinator.coverReason)
        assertFalse(harness.coordinator.retryScheduled)
    }

    @Test
    fun `persistent palette rotation also reaches the same terminal cover deadline`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onPaletteRotationDue()
        harness.coordinator.onCameraIdle()
        assertEquals(FogCoverReason.PALETTE_ROTATION, harness.coordinator.coverReason)

        harness.coordinator.onCoverDeadlineExceeded()

        assertTrue(harness.coordinator.terminal)
        assertEquals(FogCoverReason.RUNTIME_FAILURE, harness.coordinator.coverReason)
        assertFalse(harness.coordinator.retryScheduled)
    }

    @Test
    fun `old overlay removal failure on the verdict terminates the composition`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(second)
        harness.overlay.removeSucceeds = false
        harness.coordinator.onDeliveryBarrierDrained(second)

        assertTrue(harness.coordinator.terminal)
        assertTrue(harness.coordinator.coverUp)
        assertEquals(FogCoverReason.RUNTIME_FAILURE, harness.coordinator.coverReason)
    }

    @Test
    fun `fog runtime failure with proven coverage schedules a retry instead of terminal`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onFogRuntimeFailure()
        assertFalse(harness.coordinator.terminal)
        assertTrue(harness.coordinator.retryScheduled)
        assertFalse(harness.coordinator.coverUp)
    }
}
