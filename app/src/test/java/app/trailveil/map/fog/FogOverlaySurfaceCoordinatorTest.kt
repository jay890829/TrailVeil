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
    fun `refresh runs a handover and removes the old overlay only after the new proof`() {
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
        assertFalse(
            "old overlay must NOT be removed before the proof verdict",
            harness.overlay.log.contains("remove($first)"),
        )
        assertFalse("no in-place clearTileCache on the refresh path (SP9)", harness.overlay.log.contains("clearTileCache"))
        val (provenId, callback) = harness.snapshot.heldCallbacks.single()
        assertEquals(second, provenId)
        callback(true)
        assertTrue("old overlay removed after proof", harness.overlay.log.contains("remove($first)"))
        assertTrue(
            harness.overlay.log.indexOf("attach($second)") <
                harness.overlay.log.indexOf("remove($first)"),
        )
        assertEquals(second, harness.coordinator.installedGenerationId)
        assertFalse(harness.coordinator.coverUp)
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
    fun `handover failure keeps the old published set serving without a cover`() {
        val harness = Harness()
        val first = harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.result = false
        harness.coordinator.onCameraIdle()
        val second = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(second)
        harness.coordinator.onDeliveryBarrierDrained(second)
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
    fun `successful retry clears the unavailable state before overlays are revealed`() {
        val harness = Harness()
        harness.firstInstall()
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.result = false
        harness.coordinator.onCameraIdle()
        val failed = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(failed)
        harness.coordinator.onDeliveryBarrierDrained(failed)
        assertTrue(harness.coordinator.retryScheduled)

        harness.snapshot.result = true
        harness.coordinator.onRetryFogOperation()
        val retried = requireNotNull(harness.coordinator.pendingGenerationId)
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
        assertTrue(harness.coordinator.coverUp)
        assertTrue("old leaves before same-colour proof", harness.overlay.log.contains("remove($first)"))
        assertNull("old generation is no longer a valid fallback", harness.coordinator.installedGenerationId)
        harness.snapshot.heldCallbacks.single().second(true)
        assertTrue(
            harness.overlay.log.indexOf("attach($second)") <
                harness.overlay.log.indexOf("remove($first)"),
        )
        assertFalse("re-proof lowers the rotation cover", harness.coordinator.coverUp)
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
        // A programmed jump exits the published surround while the proof is in flight.
        harness.camera.inside = false
        harness.coordinator.beginProgrammedFlight()
        harness.coordinator.onCameraMoveStarted(FogCameraMoveReason.DEVELOPER)
        assertTrue(harness.coordinator.coverUp)

        harness.snapshot.heldCallbacks.single().second(true)

        assertEquals(pending, harness.coordinator.installedGenerationId)
        assertTrue(
            "the cover must survive an install proven for coverage the camera has left",
            harness.coordinator.coverUp,
        )
        assertEquals(FogCoverReason.PROGRAMMED_EXIT, harness.coordinator.coverReason)
        assertTrue(
            "a follow-up handover began for the new viewport",
            harness.overlay.log.last().startsWith("begin(3,handover=true"),
        )
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
        harness.snapshot.heldCallbacks.single().second(true)
        assertTrue(
            "the swallowed viewport idle triggers the follow-up rebuild",
            harness.overlay.log.last().startsWith("begin(3,handover=true"),
        )
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
    fun `a proof that keeps failing can never lower the first-install cover`() {
        val harness = Harness()
        harness.snapshot.result = false
        harness.coordinator.onFirstComposition()
        harness.coordinator.onCameraIdle()
        val id = requireNotNull(harness.coordinator.pendingGenerationId)
        harness.coordinator.onGenerationPublished(id)
        harness.coordinator.onDeliveryBarrierDrained(id)
        assertTrue("bare-basemap verdicts keep the cover up", harness.coordinator.coverUp)
        assertNull(harness.coordinator.installedGenerationId)
        assertTrue("nothing proven: terminal for the composition", harness.coordinator.terminal)
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
        harness.coordinator.onDeliveryBarrierDrained(second)
        // Canonical content changes again while the proof is in flight; the camera is
        // stationary, so no further idle will arrive.
        harness.coordinator.onCanonicalRefreshRequired()
        harness.snapshot.heldCallbacks.single().second(true)
        assertEquals(second, harness.coordinator.installedGenerationId)
        assertTrue(
            "a follow-up handover began without waiting for an idle",
            harness.overlay.log.last().startsWith("begin(3,handover=true"),
        )
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
    fun `old overlay removal failure terminates instead of lowering the cover`() {
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
