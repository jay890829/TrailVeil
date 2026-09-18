package app.trailveil.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keyless hosted-CI tripwires for the production Stage-6 fog binding. */
class GoogleFogStage6SourceTest {
    @Test
    fun productionBindingUsesActualRequestsMultiLodWindowsAndDualOverlays() {
        val binding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        val provider = moduleRoot()
            .resolve("src/google/java/app/trailveil/googlepoc/GoogleFogTileProvider.kt")
            .readText()

        listOf(
            "FogActualTileRequestSet",
            "FogRequestedTileWindowRenderer",
            "adapter.beginHandoverGeneration()",
            "actualRequests.armBarrier",
            "coordinator.onDeliveryBarrierDrained",
            "overlays[generationId] = overlay",
            "coordinator.onGenerationPublished",
            "acceptedGeneration = targetGeneration ?: BOOTSTRAP_PLACEHOLDER_GENERATION",
            "OLD_OVERLAY_Z",
            "NEW_OVERLAY_Z",
            "overlay.zIndex = OLD_OVERLAY_Z",
            "!allMasks.keys.containsAll(requiredFloorKeys)",
            "!allMasks.keys.containsAll(actual)",
            "coordinator.onCoverDeadlineExceeded()",
            "MAXIMUM_COVER_MILLIS",
            "if (snapshot.barrierArmed)",
            "scheduleDeliveryQuietCheck(generationId)",
            "installTimeoutGeneration",
            "if (pending != owner) cancelInstallTimeout(owner)",
            "clearRecentRequests()",
            "catch (failure: Exception)",
            "retryScheduled = coordinator.retryScheduled",
        ).forEach { contract -> assertTrue("missing $contract", binding.contains(contract)) }
        assertTrue(provider.contains("private val acceptedGeneration: Long? = null"))
        assertTrue(provider.contains("FogTilePngCodec.opaquePlaceholder()"))
        val cancelBlock = binding
            .substringAfter("override fun cancelRebuild(generationId: Long)")
            .substringBefore("private val snapshotPort")
        assertTrue(cancelBlock.contains("actualRequests.cancel(generationId)"))
        assertTrue(cancelBlock.contains("clearRecentRequests()"))
        val installedCoverageRead = binding
            .substringAfter("override fun insidePublishedSurround(): Boolean")
            .substringBefore("override fun insidePendingSurround(): Boolean")
        assertTrue(
            "movement safety must use only installed/proven coverage",
            installedCoverageRead.contains("insideCoverage(installedCoverageKeys)"),
        )
        assertFalse(
            "pending unproven coverage must not suppress the movement cover",
            installedCoverageRead.contains("pendingCoverageKeys"),
        )
        assertTrue(
            "completion still needs the just-proven pending generation's own coverage",
            binding.substringAfter("override fun insidePendingSurround(): Boolean")
                .substringBefore("    init {")
                .contains("insideCoverage(pendingCoverageKeys ?: installedCoverageKeys)"),
        )
        val startRenderBlock = binding
            .substringAfter("private fun startRender(generation: FogTileGeneration)")
            .substringBefore("    private fun scheduleDeliveryQuietCheck")
        assertTrue(
            "renderer request-log failures must be caught before leaving the main callback",
            startRenderBlock.indexOf("val renderInput = try") in
                0 until startRenderBlock.indexOf("requestedKeysForRender()"),
        )
        assertTrue(startRenderBlock.contains("catch (failure: Exception)"))
        assertTrue(startRenderBlock.contains("catch (failure: LinkageError)"))
        assertFalse(
            "the production refresh path called clearTileCache",
            Regex("""\.clearTileCache\s*\(""").containsMatchIn(binding),
        )
    }

    @Test
    fun proofReplansEachAttemptAndTheCoverIsConditionalAndInputTransparent() {
        val prover = googleSource("GoogleFogSnapshotProver.kt")
        val hosted = googleSource("GoogleHostedMapSurface.kt")
        val gestureView = googleSource("GestureOwningGoogleMapView.kt")
        val synchronousCover = googleSource("GoogleFogSafetyOverlay.kt")

        // Scope to attempt(): unscoped "somewhere in the file" matching cannot tell per-attempt
        // planning from planning once in prove() and threading it through — the exact regression
        // this case is named for.
        val attemptBody = functionBody(prover, "private fun attempt(")
        val planBody = functionBody(prover, "private suspend fun planAndSnapshot(")
        assertTrue(
            "each attempt must launch its own plan, once per pass",
            attemptBody.contains("scope.launch { planAndSnapshot(run, attemptToken) }") &&
                planBody.contains("planForAttempt(run.generation, attemptToken.number)"),
        )
        assertFalse(
            "prove() must not hoist the plan; that restores the stale-plan defect F2 closes",
            functionBody(prover, "fun prove(").contains("planForAttempt("),
        )
        assertTrue(
            "each snapshot callback must be bound to its lifecycle/camera attempt token",
            planBody.contains("isLive(run, attemptToken)") &&
                planBody.contains("retrySameAttempt(run, attemptToken)"),
        )
        assertTrue("a stale worker result must be rejected before preparing overlays or snapshotting",
            planBody.indexOf("if (!isLive(run, attemptToken))") in
                0 until planBody.indexOf("prepareFogProofPlan(plan, onUnprovablePlan)"))
        assertTrue("warmup must yield and its captured plan must still pass the post-wait fence",
            planBody.indexOf("delay(warmupRemaining)") in
                0 until planBody.indexOf("if (!isLive(run, attemptToken))"))
        assertTrue(planBody.contains("catch (cancelled: CancellationException)"))
        listOf("fun prove(", "fun release(", "fun onHostStopped(").forEach { declaration ->
            assertTrue(functionBody(prover, declaration).contains("planningJob?.cancel()"))
        }
        val binding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        val freshPlan = functionBody(binding, "private suspend fun freshProofPlan(")
        assertTrue(freshPlan.contains("FogProbeCandidateBank.forAttempt(attempt)"))
        assertTrue(freshPlan.indexOf("recentRequestedKeysOrNull()") < freshPlan.indexOf("proofPlanMemo.find("))
        assertTrue(freshPlan.indexOf("exclusionZonesForProof()") < freshPlan.indexOf("proofPlanMemo.find("))
        assertTrue(freshPlan.contains("withContext(Dispatchers.Default)"))
        assertTrue("worker planning must observe its coroutine cancellation",
            freshPlan.contains("checkActive = { workerJob.ensureActive() }"))
        listOf("fun onCameraMoveStarted(", "fun onCameraMoveFrame(", "fun onCameraIdle(",
            "fun onOverlayDataChanged(").forEach { declaration ->
            assertTrue("changed SDK inputs must cancel in-flight planning: $declaration",
                functionBody(binding, declaration).contains("snapshotProver.onInputsChanged()"))
        }
        listOf("override fun removeOverlay(", "override fun cancelRebuild(").forEach { declaration ->
            assertTrue("discarded generation must cancel only its own proof",
                functionBody(binding, declaration).contains("snapshotProver.cancelGeneration(generationId)"))
        }
        listOf("private fun applyCanonicalEpoch(", "private fun failSynchronization(").forEach { declaration ->
            val body = functionBody(binding, declaration)
            assertTrue(body.contains("proofPlanMemo.clear()"))
            val next = if (declaration.contains("applyCanonicalEpoch"))
                "coordinator.onCanonicalResetRequired()" else "failRuntime(failure)"
            assertTrue("reset/failure cancels obsolete proof before its coordinator transition",
                body.indexOf("snapshotProver.release()") in 0 until body.indexOf(next))
        }
        val failedSync = functionBody(binding, "private fun failSynchronization(")
        assertFalse("transient read errors must not revoke a proven generation",
            failedSync.contains("coordinator.onCanonicalResetRequired()"))
        assertFalse(failedSync.contains("hideOverlaysBeneathCover()"))
        val reveal = functionBody(binding, "override fun revealOverlay(")
        assertEquals("both posted reveal failures must cancel their generation's now-useless proof", 2,
            Regex("""snapshotProver\.cancelGeneration\(generationId\)\s+coordinator\.onRevealFailed\(generationId\)""")
                .findAll(reveal).count())
        val terminal = functionBody(binding, "private fun afterCoordinatorMutation(")
            .substringAfter("val publishTerminal = coordinator.terminal && !terminalPublished")
            .substringBefore("if (coordinator.retryScheduled")
        assertTrue(terminal.contains("proofPlanMemo.clear()"))
        assertTrue("every terminal path must stop planning before notifying the host",
            terminal.indexOf("snapshotProver.release()") in 0 until terminal.indexOf("onStateChanged(state())"))
        assertTrue(terminal.indexOf("onStateChanged(state())") < terminal.indexOf("onTerminalFailure()"))
        assertTrue(freshPlan.indexOf("currentCoverageRequest()") < freshPlan.indexOf("withContext(Dispatchers.Default)"))
        assertTrue(freshPlan.indexOf("exclusionZonesForProof()") < freshPlan.indexOf("withContext(Dispatchers.Default)"))
        listOf("!allMasks.keys.containsAll(requiredFloorKeys)", "!allMasks.keys.containsAll(actual)",
            "exclusionZonesForProof()").forEach { check ->
            assertTrue("memo lookup must follow fresh coverage and exclusion checks",
                freshPlan.indexOf(check) in 0 until freshPlan.indexOf("proofPlanMemo.find("))
        }
        assertTrue(prover.contains("FogSnapshotProofBudget(MAX_ATTEMPTS)"))
        // V03-013: prove() posts the FIRST attempt after a settle so the main thread yields before
        // the plan and snapshot run. It must reach attempt(run) exactly once and only through that
        // post: an inline call in any spelling, or an immediate handler.post beside the settled
        // one, restores the b20 first attempt and leaves the settled post to `begin:null` - a
        // regression the earlier "no bare attempt(run) line" pin could not see. The settle is a
        // real interval that attempt() does not re-apply, and retries keep their own pacing.
        val proveBody = functionBody(prover, "fun prove(")
        assertTrue(
            "prove() must post the first attempt after FIRST_SNAPSHOT_SETTLE_MILLIS",
            proveBody.contains("handler.postDelayed({ attempt(run) }, FIRST_SNAPSHOT_SETTLE_MILLIS)"),
        )
        assertEquals(
            "prove() must reach attempt(run) exactly once, inside the settled post",
            1,
            Regex("attempt\\(run\\)").findAll(proveBody).count(),
        )
        assertFalse(
            "prove() must not post an immediate attempt beside the settled one",
            proveBody.contains("handler.post {") || proveBody.contains("handler.post("),
        )
        assertTrue(prover.contains("const val FIRST_SNAPSHOT_SETTLE_MILLIS = 50L"))
        assertFalse(
            "the settle belongs to prove(), not to every attempt",
            attemptBody.contains("FIRST_SNAPSHOT_SETTLE_MILLIS") || attemptBody.contains("50L"),
        )
        assertTrue(
            "retries keep their RETRY_MILLIS pacing; the settle is not a retry interval",
            functionBody(prover, "private fun retryOrFinish(")
                .contains("handler.postDelayed({ attempt(run) }, RETRY_MILLIS)"),
        )
        assertTrue(prover.contains("run.budget.recordSuccess(attemptToken)"))
        assertTrue(prover.contains("catch (_: Exception)"))
        assertTrue(prover.contains("catch (_: LinkageError)"))
        // The verdict rule itself is behaviourally covered by FogSnapshotProofVerdictTest; what is
        // pinned here is that the prover still delegates to it rather than re-deriving a verdict.
        assertTrue(
            "the prover must delegate its verdict to the tested provider-neutral rule",
            prover.contains("tallyFogProof(samples, MINIMUM_MATCHING_BLOCKS_PER_TILE)") &&
                prover.contains("passed = tally.passed"),
        )
        assertFalse(
            "the prover must not compute its own passed flag from raw tile counts",
            prover.contains("passed = verifiedTiles =="),
        )
        // One coat of fog beneath the cover: the tile overlays AND the installer arms' layers
        // are hidden on the cover's rising edge (the owner read the second coat as a darker cover).
        val coverHide = functionBody(googleSource("GoogleCanonicalFogSurfaceBinding.kt"), "private fun hideOverlaysBeneathCover()")
        assertTrue(
            "the installer's layers must be hidden beneath the cover with the tile overlays",
            coverHide.contains("overlayInstaller?.hideBeneathCover()"),
        )
        assertTrue(hosted.contains("if (fogRequired && fogCoverUp)"))
        assertFalse(hosted.contains("pointerInput"))
        assertTrue(gestureView.contains("requestDisallowInterceptTouchEvent(true)"))
        assertTrue(synchronousCover.contains("mapView.overlay.add(drawable)"))
        assertTrue(synchronousCover.contains("map_fog_synchronous_cover_up"))
        assertFalse(synchronousCover.contains("setOnTouchListener"))
        // V03-013: raising is synchronous, lowering settles. The drawable may only leave through
        // the settled path (a delay, then an animation frame), never straight from setVisible(false),
        // and the raise path must cancel a pending lowering before it adds the drawable.
        val setVisibleBody = functionBody(synchronousCover, "fun setVisible(show: Boolean)")
        assertFalse(
            "setVisible(false) must not remove the cover synchronously; the SDK's next frame may not carry the successor yet",
            setVisibleBody.contains("overlay.remove(drawable)"),
        )
        // The verifier's counter-example: `else if (visible) lowerNow()` kept every pin above
        // green. The false branch must go through the settle and nowhere else.
        assertTrue(
            "setVisible(false) must schedule the settled lowering",
            setVisibleBody.contains("scheduleLower()"),
        )
        assertFalse(
            "setVisible must never lower directly",
            setVisibleBody.contains("lowerNow()"),
        )
        assertTrue(
            "the settle must be a real interval, not a named zero",
            synchronousCover.contains("const val LOWER_SETTLE_MILLIS = 50L"),
        )
        assertTrue(
            "the raise path must cancel a pending lowering",
            setVisibleBody.indexOf("cancelPendingLower()") in 0 until setVisibleBody.indexOf("raiseNow()"),
        )
        val scheduleBody = functionBody(synchronousCover, "private fun scheduleLower()")
        assertTrue(
            "lowering must settle for LOWER_SETTLE_MILLIS and then align with an animation frame",
            scheduleBody.contains("mapView.postDelayed(lower, LOWER_SETTLE_MILLIS)") &&
                scheduleBody.contains("mapView.postOnAnimation(this)"),
        )
        assertTrue(
            "release must lower at once, not through the settle",
            functionBody(synchronousCover, "fun release()").contains("lowerNow()"),
        )
        // Stage 8 attempted to keep the Google attribution legible by clipping a 220x220 px hole in
        // this cover. That hole exposed unproven raw basemap and was rejected; the remedy moves the
        // SDK's own ImageView instead. Nothing pinned it, so the regression could return silently.
        // Scope to draw(): an unscoped file match cannot tell a cover that clips from one that
        // merely mentions clipping in a comment.
        val coverDraw = functionBody(synchronousCover, "override fun draw(canvas: Canvas)")
        assertTrue(
            "the safety cover must fill its whole bounds",
            coverDraw.contains("canvas.drawRect(bounds, paint)"),
        )
        assertFalse(
            "a cover that clips anything out can expose unproven basemap; carry the attribution " +
                "by moving the SDK view, never by punching a hole",
            Regex("""clipOut|clipRect|clipPath""").containsMatchIn(coverDraw),
        )
        // V02-012 design 2 (owner decision 2026-09-06): the cover is the fog colour at the shared
        // fog opacity - unproven ground is never shown clearer than fog - so it advertises
        // translucency and takes its colour from the codec, never from a literal of its own.
        assertTrue(
            "the cover is drawn at the fog opacity",
            synchronousCover.contains("PixelFormat.TRANSLUCENT") &&
                synchronousCover.contains("FogTilePngCodec.REVEALED_FOG_ALPHA"),
        )
        assertFalse(
            "moving the SDK attribution must not become a camera or padding change",
            synchronousCover.contains("setPadding("),
        )
        // The cover spent all of stage 6-8 painting pure black instead of fog: it was built as
        // `Paint(...).apply { this.color = color }`, where the Paint receiver's own `color` shadows
        // the constructor property, so it compiled to `paint.setColor(paint.getColor())`. Nothing
        // noticed because black is still fully opaque, so the fail-closed guarantee held and only
        // one device assertion ever compared the cover's RGB. Ban the shape, not the instance.
        // Comments are stripped first: the fix's own KDoc quotes the broken line, and a file-wide
        // match cannot tell the bug from the note explaining it. Both comment forms go in ONE
        // alternation so whichever opens first wins: a `/*` inside a `//` line cannot swallow code
        // up to a later `*/`, and a `//` inside a block (a URL in KDoc) cannot truncate the block.
        val coverCode = synchronousCover
            .replace(Regex("""//[^\n]*|/\*[\s\S]*?\*/"""), "")
        assertFalse(
            "a receiver-scoped `this.x = x` in the cover self-assigns and silently discards the " +
                "value; use also/it, or names that cannot shadow",
            Regex("""this\.(\w+)\s*=\s*\1\s*[};\n]""").containsMatchIn(coverCode),
        )
        assertFalse(
            "an unqualified `x = x` inside apply/run/with self-assigns the receiver's member just " +
                "as silently as `this.x = x`",
            Regex("""(?<![.\w])(\w+)\s*=\s*\1\s*[};\n]""").containsMatchIn(coverCode),
        )
        assertTrue(
            "the fog colour must arrive as a constructor property whose name the Paint cannot shadow",
            Regex("""class FogCoverDrawable\(\s*private val fogColor: Int""")
                .containsMatchIn(coverCode),
        )
        assertTrue(
            "the cover's paint must actually be given the fog colour it was constructed with",
            Regex("""it\.color\s*=\s*fogColor\s*[};\n]""").containsMatchIn(coverCode),
        )
        assertTrue(hosted.contains("mapCallbackEpoch"))
        assertTrue(hosted.contains("mapCallbackEpoch.get() != effectEpoch"))
        // `fogRuntime` still must NOT be a deadline key — a runtime arriving near the deadline may
        // not be granted another full window. `lifecycle` was added so the wait can be suspended
        // while the host is stopped; see boundedDeadlinesDoNotRunWhileTheHostIsStopped.
        // `fogGestureSettleClock` and `fogGestureHeld` (owner decision 2026-09-10) make this effect
        // the binding's net: it restarts only when a gesture's settling idle re-arms the binding's
        // deadline - never on the binding's first arm, which is what keeps the late-runtime bound
        // above - and waits longer while a person's gesture holds the camera, where the binding's
        // clock is deliberately stopped. An explicit harness canonical replacement also pauses
        // the window; its completion resumes rendering without extending a renderer timeout.
        assertTrue(
            hosted.contains(
                "LaunchedEffect(mapView, fogRequired, fogCoverUp, fogCoverTimeoutMillis, lifecycle, " +
                    "fogGestureSettleClock, fogGestureHeld, canonicalReplacementInProgress)",
            ),
        )
        // The settle clock is bumped in exactly one place, the settling idle. The binding's first
        // arm happens inside its own construction, so a clock bumped by every arm restarted the
        // host's window when a runtime arrived at 19 s - the defect these pins exist for.
        val bindingSource = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        assertEquals(
            "the settle clock must be bumped in exactly one place",
            1,
            Regex("""gestureSettleClock \+= 1L""").findAll(bindingSource).count(),
        )
        assertTrue(
            "and that place is the settling idle",
            functionBody(bindingSource, "fun onCameraIdle()").contains("gestureSettleClock += 1L"),
        )
        assertFalse(
            "armCoverDeadline must not touch the settle clock",
            functionBody(bindingSource, "private fun armCoverDeadline()").contains("gestureSettleClock"),
        )
        assertTrue(
            "the host's gesture restart must use the binding's settle clock",
            hosted.contains("val fogGestureSettleClock = fogState?.gestureSettleClock ?: 0L"),
        )
        assertTrue(
            "only an explicitly published canonical replacement may pause the host net",
            hosted.contains("val canonicalReplacementInProgress = fogState?.canonicalReplacementInProgress == true") &&
                hosted.contains("if (!fogRequired || !fogCoverUp || canonicalReplacementInProgress) return@LaunchedEffect"),
        )
        assertTrue(
            "the gesture rule must wait for the first cover to lower on a passed proof; the " +
                "installed-generation id is set at reveal, before the proof, and cannot be the gate",
            functionBody(bindingSource, "fun onCameraMoveStarted(reason: Int)")
                .contains("fogReason == FogCameraMoveReason.GESTURE && firstCoverLowered"),
        )
        assertFalse(
            "fogRuntime must never key the cover deadline",
            hosted.contains("fogCoverTimeoutMillis, fogRuntime"),
        )
        assertTrue(hosted.contains("fogState?.retryScheduled == true && !fogCoverUp"))
        assertFalse(
            "the Compose semantics backup must not be the visual cover",
            hosted.substringAfter("if (fogRequired && fogCoverUp)")
                .substringBefore("if (loadState")
                .contains("background(FogSurfaceColor)"),
        )
    }

    /**
     * A fresh adversarial round found every `withTimeout` in the binding inert:
     * `TimeoutCancellationException` IS a `CancellationException`, so a
     * `catch (cancelled: CancellationException) { throw cancelled }` placed first rethrew expiry as
     * ordinary cancellation and the failure handler never ran. A render timeout then wedged the
     * coordinator's pending slot with no install timeout armed, and a synchronization timeout
     * silently killed canonical refresh with the cover down and the surface looking healthy. The
     * retained PoC in the same module already ordered these clauses correctly; the production
     * binding lost that in the port, so pin the ordering here.
     */
    @Test
    fun everyBindingTimeoutIsClassifiedAsFailureNotCancellation() {
        val binding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        assertTrue(
            "the binding must import TimeoutCancellationException to distinguish expiry",
            binding.contains("import kotlinx.coroutines.TimeoutCancellationException"),
        )

        val timeoutCatch = "catch (timeout: TimeoutCancellationException)"
        val cancelCatch = "catch (cancelled: CancellationException)"
        val timeoutCatches = Regex(Regex.escape(timeoutCatch)).findAll(binding).count()
        val cancelCatches = Regex(Regex.escape(cancelCatch)).findAll(binding).count()
        assertTrue(
            "every withTimeout-bearing try must classify expiry before generic cancellation; " +
                "found $timeoutCatches timeout clauses for $cancelCatches cancellation clauses",
            cancelCatches > 0 && timeoutCatches >= cancelCatches,
        )

        // Ordering is the whole point: a timeout clause after the cancellation clause is dead code.
        var searchFrom = 0
        while (true) {
            val cancelAt = binding.indexOf(cancelCatch, searchFrom)
            if (cancelAt < 0) break
            val timeoutAt = binding.lastIndexOf(timeoutCatch, cancelAt)
            assertTrue(
                "the cancellation clause at offset $cancelAt is not preceded by a timeout clause, " +
                    "so withTimeout expiry there is swallowed as ordinary cancellation",
                timeoutAt >= 0 &&
                    !binding.substring(timeoutAt, cancelAt).contains(cancelCatch),
            )
            searchFrom = cancelAt + cancelCatch.length
        }
    }

    /**
     * The delivery quiet-check chain used to stop on a successful arm, so a later actual request
     * that nulled the barrier left nothing alive to observe the invalidation or re-arm over the
     * expanded set; the generation then stranded until the install timeout, which is terminal on a
     * first install. The poll must continue past arming and terminate on session end instead.
     */
    @Test
    fun deliveryQuietCheckSurvivesArmingAndEndsWithTheSession() {
        val binding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        val poll = binding
            .substringAfter("private fun scheduleDeliveryQuietCheck(generationId: Long)")
            .substringBefore("private fun onActualDeliveryBarrierDrained")
        assertFalse(
            "arming must not be the chain's terminating condition",
            poll.contains("if (!armed)"),
        )
        assertTrue(
            "the chain needs an explicit session-end guard now that arming no longer stops it",
            poll.contains("if (snapshot.generation != generationId) return@postDelayed"),
        )
        val armAt = poll.indexOf("actualRequests.armBarrier")
        assertTrue("the poll must still arm the barrier", armAt >= 0)
        // Step past the arm call's own callback lambda. Two evasions have to be excluded and they
        // pull opposite ways: a guard before the reschedule (`if (armed) return@postDelayed`), and
        // the reschedule hidden INSIDE the callback — which would run only once the barrier
        // completes, again leaving nothing alive to observe a late-request invalidation.
        val afterCallback = poll.substring(endOfFirstBracedBlock(poll, armAt))
        val rescheduleAt = afterCallback.indexOf("scheduleDeliveryQuietCheck(generationId)")
        assertTrue(
            "a reschedule must follow the arm call itself, not sit inside its callback",
            rescheduleAt >= 0,
        )
        val between = afterCallback.substring(0, rescheduleAt)
        assertFalse(
            "the reschedule after arming must be unconditional, but a branch precedes it: $between",
            between.contains("return@postDelayed") || Regex("""\bif\s*\(""").containsMatchIn(between),
        )
    }

    /**
     * Round-5 finding, reproduced on API 36 before the fix: both 20 s cover deadlines were armed on
     * a plain main-looper handler with no lifecycle gating, and the fog binding was never told the
     * host had stopped. A stopped renderer issues no tile requests and cannot serve a snapshot, so
     * the cover could not lower and backgrounding the recording screen with the cover up terminated
     * the primary map permanently.
     */
    @Test
    fun boundedDeadlinesDoNotRunWhileTheHostIsStopped() {
        val binding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        val hosted = googleSource("GoogleHostedMapSurface.kt")
        val lifecycleBinding = googleSource("GoogleMapViewLifecycleBinding.kt")

        assertTrue(
            "the fog binding needs a stop counterpart to onHostStarted",
            binding.contains("fun onHostStopped()"),
        )
        // Scope to the actual function body. substringAfter alone runs to end-of-file, where both
        // target strings occur for unrelated reasons, so an empty onHostStopped() body passed.
        val stopBody = functionBody(binding, "fun onHostStopped()")
        assertTrue(
            "stopping must disarm the cover deadline",
            stopBody.contains("cancelCoverDeadline()"),
        )
        assertTrue(
            "stopping must disarm the install timeout, which is terminal on a first install",
            stopBody.contains("cancelInstallTimeout"),
        )
        assertTrue("stopping must record the stopped state", stopBody.contains("hostStopped = true"))
        assertTrue(
            "stopping must pause the active render instead of discarding its remaining budget",
            stopBody.contains("pauseActiveRender()") &&
                binding.contains("FogLifecycleBudget(RENDER_TIMEOUT_MILLIS)"),
        )
        assertTrue(
            "starting must resume the pending generation's paused render",
            functionBody(binding, "fun onHostStarted()").contains("resumePendingRenderIfNeeded()") &&
                binding.contains("budget.resume(paused)"),
        )
        assertTrue(
            "the cover deadline must not be armed while the host is stopped, nor on a cover that " +
                "rises under a held gesture (the settling idle arms it; owner decision 2026-09-10)",
            binding.contains("if (!hostStopped && !gestureHeld) armCoverDeadline()"),
        )
        assertTrue(
            "the install timeout must not be armed while the host is stopped either; cancelling an " +
                "already-armed one does not stop a render that publishes after the stop",
            functionBody(binding, "private fun scheduleInstallTimeout(generationId: Long)")
                .let { body ->
                    body.contains("if (hostStopped)") &&
                        body.contains("pausedInstallTimeoutGeneration = generationId")
                },
        )
        assertTrue(
            "the snapshot prover's attempt budget must not be spent while stopped",
            googleSource("GoogleFogSnapshotProver.kt").contains("if (hostStopped())") &&
                binding.contains("hostStopped = { hostStopped }") &&
                googleSource("GoogleFogSnapshotProver.kt").contains("abandonActive()"),
        )
        assertTrue(
            "resume must reject a paused proof when the coordinator no longer owns its generation",
            googleSource("GoogleFogSnapshotProver.kt").contains(
                "expectedGeneration == null || run.generation != expectedGeneration",
            ),
        )
        assertTrue(
            "returning to the foreground must re-arm both deadlines",
            functionBody(binding, "fun onHostStarted()").let { body ->
                body.contains("armCoverDeadline()") &&
                    body.contains("pausedInstall?.let(::scheduleInstallTimeout)")
            },
        )
        assertFalse(
            "a pre-publish pending render must not start the post-publish install timeout on resume",
            functionBody(binding, "fun onHostStarted()")
                .contains("coordinator.pendingGenerationId?.let(::scheduleInstallTimeout)"),
        )
        assertTrue(
            "a binding born while the host is stopped must be told so, not left believing it runs",
            hosted.contains("newFogBinding?.onHostStopped()"),
        )
        assertTrue(
            "the map-load timeout is a bounded budget a stopped renderer cannot satisfy either",
            hosted.contains("LaunchedEffect(mapView, fallbackTimeoutMillis, loadState, lifecycle)"),
        )
        assertEquals(
            "both host deadlines must be gated on the STARTED lifecycle state",
            2,
            Regex(Regex.escape("lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)"))
                .findAll(hosted).count(),
        )
        val lifecycleStopBody = functionBody(lifecycleBinding, "private fun stop()")
        assertTrue("ON_STOP must reach the fog binding", lifecycleStopBody.contains("onHostStopped()"))
        assertTrue(
            "fog callback tokens must be invalidated before SDK onStop can re-enter",
            lifecycleStopBody.indexOf("onHostStopped()") in
                0 until lifecycleStopBody.indexOf("mapViewLifecycle.onStop()"),
        )
        assertTrue(
            "the hosted surface must wire the stop callback through",
            hosted.contains("onHostStopped = { fogBinding?.onHostStopped() }"),
        )
        assertTrue(
            "the hosted cover deadline must be gated on the STARTED lifecycle state",
            hosted.contains("lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)"),
        )
    }

    /**
     * Round-5 finding: classifying a synchronization timeout as a failure was not enough, because
     * `startSynchronization` has a single call site in `init`. Reporting alone left the revisions
     * collector dead for the rest of the composition while the surface published a healthy state.
     */
    @Test
    fun synchronizationFailureRestartsTheCollectorRatherThanOnlyReportingIt() {
        val binding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        val syncBlock = binding
            .substringAfter("private fun startSynchronization()")
            .substringBefore("private fun failSynchronization")
        assertFalse(
            "the synchronization path must not dispose of failures with a bare failRuntime",
            syncBlock.contains("failRuntime("),
        )
        assertTrue(
            "synchronization failures must route to the restarting handler",
            syncBlock.contains("failSynchronization("),
        )
        val restart = binding.substringAfter("private fun failSynchronization")
        assertTrue(
            "failSynchronization must relaunch the collector, not merely report",
            restart.contains("startSynchronization()") &&
                restart.contains("SYNCHRONIZATION_RETRY_MILLIS"),
        )
    }

    @Test
    fun ownerVisibleLabelsAndPoisRemainDefaultWithoutPlacesIntegration() {
        val surfaceBinding = googleSource("GoogleMapSurfaceBinding.kt")
        // `V02-008` split the tree: both halves are scanned, so moving a banned call into
        // the harness source set does not escape the ban.
        val source = listOf("src/google", "src/googlePoc")
            .map(moduleRoot()::resolve)
            .flatMap { root -> root.walkTopDown() }
            .filter { file -> file.isFile && file.extension == "kt" }
            .joinToString("\n") { file -> file.readText() }

        // V02-012 (owner, 2026-09-06): the SDK draws the basemap's labels and POI icons above
        // every overlay an app can add, so the fog cannot conceal them. Two styles were built and
        // judged on the phone - the halo removed (prototype 3, text became hard to read) and the
        // halo darkened with the POI icons muted (prototype 4, "這樣看起來也很怪") - and the owner
        // reverted both. The map is styled by nothing, and this ban stands.
        assertFalse(surfaceBinding.contains("setMapStyle"))
        assertFalse(surfaceBinding.contains("setOnPoiClickListener"))
        assertFalse(source.contains("com.google.android.libraries.places"))
        assertFalse(source.contains("Places.initialize"))

        // Scoping the styling/POI bans to GoogleMapSurfaceBinding.kt alone left them evadable: the
        // same call added from GoogleHostedMapSurface or the canonical fog binding would have
        // passed. Ban them across every production map source, exempting only the unexported
        // engineering PoC, which the owner decision explicitly allows to keep diagnostic listeners.
        // `V02-008`: the production map sources are `src/google` alone now. That IS the exemption
        // the comment above describes - the harness lives in `src/googlePoc` and is not scanned
        // here - so the ban became structural instead of a filename carve-out.
        val productionMapSources = moduleRoot().resolve("src/google/java/app/trailveil/map")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
        assertTrue("no production map sources were scanned", productionMapSources.isNotEmpty())
        listOf("setMapStyle", "setOnPoiClickListener", "MapStyleOptions", ".mapId(")
            .forEach { banned ->
                productionMapSources.forEach { file ->
                    assertFalse(
                        "${file.name} introduces $banned, which criterion 6 forbids in production",
                        file.readText().contains(banned),
                    )
                }
            }
        // No map STYLE resource ships either. This used to assert that `src/google/res/raw` did not
        // exist at all, which was a cheap proxy for the same thing and stopped being true in
        // `V02-016`: that directory now holds the Google build's third-party legal notices, which
        // are text and are not a style. The ban is therefore stated as what criterion 6 forbids -
        // a JSON style document, by extension and by the keys the SDK's own styling format uses -
        // so a real map style still cannot arrive here under any filename.
        moduleRoot().resolve("src/google/res/raw").listFiles().orEmpty().forEach { file ->
            assertFalse(
                "${file.name} is a JSON resource in the Google raw set; criterion 6 forbids a map " +
                    "style resource and this is the shape one would take",
                file.extension.equals("json", ignoreCase = true),
            )
            val text = file.readText()
            listOf("\"featureType\"", "\"elementType\"", "\"stylers\"").forEach { marker ->
                assertFalse(
                    "${file.name} contains $marker, which is Google's map-styling format, and " +
                        "criterion 6 forbids styling the production map",
                    text.contains(marker),
                )
            }
        }
    }

    /**
     * Everything from [signature] up to the next function declaration.
     *
     * A bare `substringAfter` runs to end-of-file, which made an earlier stop-gating pin vacuous:
     * its target strings all occurred later in the file for unrelated reasons, so an empty function
     * body still satisfied it.
     */
    private fun functionBody(source: String, signature: String): String {
        val after = source.substringAfter(signature, "")
        // Any modifier sequence ends the body, not just `private`/`internal`. `override` was
        // missing, so scoping to an `override fun` silently returned the rest of the file and the
        // pin was broader than its own comment claimed.
        val next = Regex(
            """\n {4}(?:(?:private|internal|public|protected|override|open|final|suspend|inline|abstract) )*fun """,
        ).find(after)
        return if (next == null) after else after.substring(0, next.range.first)
    }

    /** Index just past the first balanced `{...}` block at or after [from]. */
    private fun endOfFirstBracedBlock(source: String, from: Int): Int {
        var depth = 0
        var opened = false
        var index = from
        while (index < source.length) {
            when (source[index]) {
                '{' -> { depth += 1; opened = true }
                '}' -> depth -= 1
            }
            if (opened && depth == 0) return index + 1
            index += 1
        }
        return source.length
    }

    /**
     * The compatibility publish is switched by a NULLABLE callback, so the host must not hand the
     * binding an unconditional one.

     * `publishFogRenderForCompatibility` opens with `onFogRendered ?: return` and, past it, calls
     * `FogPocMosaic.compose` on the main thread for every published generation - a whole-mosaic
     * ByteArray plus a copy of every mask. The host used to pass
     * `onFogRendered = { rendered -> currentOnFogRendered?.invoke(rendered) }`: non-null always, so
     * the switch never fired, and a shipped build composed that mosaic and handed it to a lambda
     * whose body did nothing. Neither production screen supplies the callback; only the
     * instrumentation suites do.

     * Pinned as source shape rather than behaviour because the defect lives in a Compose call
     * site's nullability, which no unit test can observe and which reads as correct at a glance.
     */
    @Test
    fun theHostPassesTheFogRenderCallbackThroughInsteadOfWrappingItUnconditionally() {
        val host = googleSource("GoogleHostedMapSurface.kt")
        assertTrue(
            "the host must decide onFogRendered's nullability from the caller's, or the " +
                "binding's `onFogRendered ?: return` switch is dead",
            host.contains("onFogRendered = if (onFogRendered == null) {"),
        )
        assertFalse(
            "an unconditional wrapper is exactly the shape that broke this: its body is " +
                "null-safe, but the lambda itself never is",
            host.contains("onFogRendered = { rendered ->"),
        )

        val binding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        assertTrue(
            "this case is only meaningful while the compatibility publish is still gated on the " +
                "callback being null",
            binding.contains("val callback = onFogRendered ?: return"),
        )
        assertTrue(
            "...and only while what it guards is actually expensive",
            binding.contains("presentation = FogPocMosaic.compose(tiles)"),
        )
    }

    private fun googleSource(name: String): String = moduleRoot()
        .resolve("src/google/java/app/trailveil/map/$name")
        .readText()

    private fun moduleRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(cwd, "settings.gradle.kts").isFile) File(cwd, "app") else cwd
    }
}
