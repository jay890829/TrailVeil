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
        assertTrue(
            "the plan must be obtained inside attempt(), once per pass",
            attemptBody.contains("planForAttempt(run.generation)"),
        )
        assertFalse(
            "prove() must not hoist the plan; that restores the stale-plan defect F2 closes",
            functionBody(prover, "fun prove(").contains("planForAttempt("),
        )
        assertTrue(
            "each snapshot callback must be bound to its lifecycle/camera attempt token",
            attemptBody.contains("isLive(run, attemptToken)") &&
                attemptBody.contains("retrySameAttempt(run, attemptToken)"),
        )
        assertTrue(prover.contains("FogSnapshotProofBudget(MAX_ATTEMPTS)"))
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
        assertTrue(hosted.contains("if (fogRequired && fogCoverUp)"))
        assertFalse(hosted.contains("pointerInput"))
        assertTrue(gestureView.contains("requestDisallowInterceptTouchEvent(true)"))
        assertTrue(synchronousCover.contains("mapView.overlay.add(drawable)"))
        assertTrue(synchronousCover.contains("map_fog_synchronous_cover_up"))
        assertFalse(synchronousCover.contains("setOnTouchListener"))
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
        assertFalse(
            "the cover must not advertise translucency; it is the fail-closed guard",
            synchronousCover.contains("PixelFormat.TRANSLUCENT"),
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
        assertTrue(
            hosted.contains(
                "LaunchedEffect(mapView, fogRequired, fogCoverUp, fogCoverTimeoutMillis, lifecycle)",
            ),
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
            "the cover deadline must not be armed while the host is stopped",
            binding.contains("if (!hostStopped) armCoverDeadline()"),
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

    private fun googleSource(name: String): String = moduleRoot()
        .resolve("src/google/java/app/trailveil/map/$name")
        .readText()

    private fun moduleRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(cwd, "settings.gradle.kts").isFile) File(cwd, "app") else cwd
    }
}
