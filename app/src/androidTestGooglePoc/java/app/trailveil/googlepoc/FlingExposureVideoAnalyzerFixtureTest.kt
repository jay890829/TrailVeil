package app.trailveil.googlepoc

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.googlepoc.FlingExposureVideoAnalyzer.FrameStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-007`, condition (c) of the MapLibre-parity inventory's NA argument: a fixture-level
 * self-check of the Google exposure arithmetic.
 *
 * The MapLibre suite retires four detector-calibration cases
 * (`bareReferenceMustBeStableAndTheComparatorStillDetectsADarkLeak`,
 * `theLivenessInstrumentsRejectAStalledSurfaceAndAcceptAMeasuredLiveOne`,
 * `aDeadCaptureIsReportedAsEnvironmentRatherThanAsFogCoverage`,
 * `theOverFogDetectorFiresOnADoubleCoatIncludingOverDarkOcean`) on the argument that the Google
 * oracle is absolute rather than comparative and asserts its own validity at RUNTIME - clapper
 * pulses, monotonic PTS, a measured noise floor, a bounded frame gap. That argument leaves the
 * runtime instruments themselves unpinned, and `src/testGooglePoc` holds no unit tests at all.
 * This is the cheap half of the answer: no map, no capture, no video - synthetic frame sequences
 * through the reductions those instruments are made of.
 *
 * What it can and cannot reach, stated plainly so nobody reads more into a green run than is
 * there. [FlingExposureVideoAnalyzer.analyze] takes a screenrecord PATH, and its per-frame
 * classifier (the fog-family window, the strided 8-connected clustering) is private, so the only
 * shipped pieces callable from a fixture are [FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX],
 * [FlingExposureVideoAnalyzer.exposureLooksLikeBasemap] and [FrameStat]. Those are exercised for
 * real here. The window/burst reduction is transcribed below from the code that owns it -
 * `GoogleFogFlingExposureSpikeTest.runTrial` and `analyze`'s clapper pass - so these cases are a
 * specification pin on that arithmetic, not a call through to it: change the reduction there
 * without changing it here and this fixture stops describing the shipped numbers. It is written
 * to be read next to that code, and it is deliberately the only Google test that needs no device
 * state whatsoever.
 *
 * The size of that gap, named so no ledger can record condition (c) as fully discharged on the
 * strength of this file. The exposure reduction now exists in FOUR copies - `analyze`'s clapper
 * pass, `GoogleFogFlingExposureSpikeTest.runTrial`, `GestureExposureAudit` and
 * `GoogleSettledFogCoverageSweepTest` - and this fixture is a fifth, private one. A transcription
 * cannot fail when the copy it describes changes: deleting the closing
 * `if (previousExposed) longestBurstMs = maxOf(...)` branch from `runTrial` leaves every case
 * below green. What would close condition (c) is extracting `motionWindow` / `framesInWindow` /
 * `exposedFrames` / `longestBurstMillis` / `noiseThresholdPx` into [FlingExposureVideoAnalyzer]
 * and having the spike, the audit, the sweep and this fixture all call the one copy. That change
 * spans files this fixture does not own, and until it lands condition (c) is HALF discharged:
 * the analyzer's published predicate and cluster minimum are pinned for real, and the reduction
 * is pinned only as a specification.
 */
@RunWith(AndroidJUnit4::class)
class FlingExposureVideoAnalyzerFixtureTest {

    @Test
    fun theFrameGapIsMeasuredOnlyInsideTheClapperDelimitedMotionWindow() {
        // Two clapper pulses bracket the fling. The frames outside them carry the two widest gaps
        // in the trial - a long pre-roll while recording spins up, and the post-idle tail - and
        // neither may inflate the bound that decides whether a no-exposure claim is trustworthy.
        val frames = listOf(
            frame(pts = 0L, marker = MARKER_OFF_PX),
            frame(pts = 300L, marker = MARKER_OFF_PX),
            frame(pts = 316L, marker = MARKER_ON_PX),
            frame(pts = 332L, marker = MARKER_OFF_PX),
            frame(pts = 408L, marker = MARKER_OFF_PX),
            frame(pts = 424L, marker = MARKER_OFF_PX),
            frame(pts = 470L, marker = MARKER_ON_PX),
            frame(pts = 900L, marker = MARKER_OFF_PX),
        )

        val window = motionWindow(frames)
        assertEquals(2, window.pulses)
        assertEquals(316L, window.startMillis)
        assertEquals(470L, window.endMillis)
        val inWindow = framesInWindow(frames, window)
        assertEquals(
            listOf(316L, 332L, 408L, 424L, 470L),
            inWindow.map { it.ptsMillis },
        )
        assertEquals(76L, maxFrameGapMillis(inWindow))
        // The unwindowed sequence is dominated by the 430 ms tail: the windowing is the whole
        // difference between a 40 ms resolution bound and one that can never be met.
        assertEquals(430L, maxFrameGapMillis(frames))

        // A gap needs two frames to exist, and `-1` is the analyzer's "no measurement" value.
        // What the spike's `frameGapMsMax in 1..FRAME_GAP_BOUND_MS` lower bound actually excludes
        // is 0, not this sentinel: that gate reads an accumulator seeded at `0L` and folded with
        // `maxOf`, so a `-1` from here is turned into 0 before the gate ever sees it, and 0 is the
        // reading that means no trial measured a gap at all. The sentinel is pinned here because
        // it is what the fold converts, not because it reaches the bound.
        assertEquals(-1L, maxFrameGapMillis(emptyList()))
        assertEquals(-1L, maxFrameGapMillis(listOf(frame(pts = 10L, marker = MARKER_OFF_PX))))
    }

    @Test
    fun aSingleClapperPulseLeavesNoWindowAndIsWhyTwoPulsesAreRequired() {
        // One pulse: the start is real, the end never arrives. The reduction keeps an unbounded
        // internal end while REPORTING -1, so a caller that trusted the reported end would filter
        // every frame away and read "no exposure" from a trial that measured nothing at all.
        val frames = listOf(
            frame(pts = 0L, marker = MARKER_OFF_PX),
            frame(pts = 100L, marker = MARKER_ON_PX),
            frame(pts = 200L, marker = MARKER_OFF_PX),
        )

        val window = motionWindow(frames)
        assertEquals(1, window.pulses)
        assertEquals(100L, window.startMillis)
        assertEquals(Long.MAX_VALUE, window.endMillis)
        assertEquals(-1L, reportedEndMillis(window))
        assertEquals(2, framesInWindow(frames, window).size)
        assertTrue(
            "a reported end of -1 must select nothing, which is what the pulse gate prevents",
            framesInWindow(frames, window.copy(endMillis = reportedEndMillis(window))).isEmpty(),
        )
    }

    @Test
    fun theLongestBurstWinsAndARunThatNeverEndsIsStillMeasured() {
        // Two bursts inside one window: a short one that ends, and a longer one still running when
        // the window closes. The closing branch is the one a naive loop drops, and dropping it
        // reports the SHORTER burst as the worst case - the exact direction that hides a defect.
        val motionFrames = listOf(
            clearFrame(pts = 100L),
            exposedFrame(pts = 116L),
            exposedFrame(pts = 132L),
            clearFrame(pts = 148L),
            clearFrame(pts = 164L),
            exposedFrame(pts = 180L),
            exposedFrame(pts = 196L),
            exposedFrame(pts = 212L),
        )
        val exposed = exposedFrames(motionFrames)

        assertEquals(5, exposed.size)
        assertEquals(32L, longestBurstMillis(motionFrames, exposed))
        // The same run, closed by one clear frame, must measure the same: the burst is bounded by
        // its last EXPOSED frame either way.
        val closed = motionFrames + clearFrame(pts = 228L)
        assertEquals(32L, longestBurstMillis(closed, exposedFrames(closed)))
        // ...and that is also why the reported duration pads the burst with the worst frame gap:
        // the last exposed frame's own display interval is not inside the difference.
        assertEquals(48L, longestBurstMillis(closed, exposed) + maxFrameGapMillis(motionFrames))

        val quiet = motionFrames.map { clearFrame(pts = it.ptsMillis) }
        assertEquals(0L, longestBurstMillis(quiet, exposedFrames(quiet)))
    }

    @Test
    fun theClusterRuleRejectsLabelSizedNonFogAtTheFloorAndAcceptsABareRegion() {
        // The trap this rule exists for: Google basemap labels composite ABOVE the opaque fog and
        // are not fog-coloured, so ~12% of the map area is non-fog in every steady frame. A frame
        // is only exposure when it has one 8-connected region of at least CLUSTER_MINIMUM_PX AND
        // more non-fog area than the measured floor - either clause alone would fire on labels.
        val floor = listOf(labelFrame(pts = 0L, exposedPx = LABEL_FLOOR_PX))
        val threshold = noiseThresholdPx(floor)
        // 12% of the area plus the 0.05 point margin, and the value the other cases judge with.
        assertEquals(DEFAULT_NOISE_THRESHOLD_PX, threshold)

        val labelsAtTheFloor = labelFrame(pts = 16L, exposedPx = LABEL_FLOOR_PX)
        val moreLabelsThanTheFloor = labelFrame(pts = 32L, exposedPx = 130_000)
        val bareRegion = exposedFrame(pts = 48L)
        val judged = exposedFrames(
            listOf(labelsAtTheFloor, moreLabelsThanTheFloor, bareRegion),
            threshold,
        )

        assertEquals(listOf(bareRegion), judged)
        assertFalse("label glyphs at the floor were judged as exposure", labelsAtTheFloor in judged)
        assertFalse(
            "a label-dense frame with no bare region was judged as exposure",
            moreLabelsThanTheFloor in judged,
        )

        // Both boundaries, in the direction that matters: the cluster minimum is inclusive, and
        // the area comparison is strict, so a frame exactly at the floor is never exposure.
        //
        // Reachability matters as much as the rule. `statFor` scales its 8-connected CELL count
        // by the sampling stride squared, so every `largestClusterPx` a real capture can carry is
        // a multiple of [CLUSTER_CELL_PX]: 64 is a value the shipped analyzer never emits, 63
        // (seven cells) is the largest producible rejection and 72 (eight cells) the smallest
        // producible acceptance. Pinning the declared boundary alone would leave the floor a
        // frame can actually sit on unpinned, so both are asserted.
        assertTrue(
            "$SMALLEST_PRODUCIBLE_CLUSTER_PX is not the first whole-cell cluster at or above " +
                "${FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX}",
            SMALLEST_PRODUCIBLE_CLUSTER_PX % CLUSTER_CELL_PX == 0 &&
                SMALLEST_PRODUCIBLE_CLUSTER_PX >= FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX &&
                SMALLEST_PRODUCIBLE_CLUSTER_PX - CLUSTER_CELL_PX <
                FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX,
        )
        val atClusterMinimum = FrameStat(
            ptsMillis = 64L,
            exposedPx = BARE_EXPOSED_PX,
            largestClusterPx = FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX,
            markerPx = MARKER_OFF_PX,
        )
        val belowClusterMinimum = atClusterMinimum.copy(
            ptsMillis = 80L,
            largestClusterPx = FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX - 1,
        )
        val atSmallestProducibleCluster = atClusterMinimum.copy(
            ptsMillis = 88L,
            largestClusterPx = SMALLEST_PRODUCIBLE_CLUSTER_PX,
        )
        val atTheThreshold = exposedFrame(pts = 96L).copy(exposedPx = threshold)
        val justOverTheThreshold = exposedFrame(pts = 112L).copy(exposedPx = threshold + 1)
        assertEquals(
            listOf(atClusterMinimum, atSmallestProducibleCluster, justOverTheThreshold),
            exposedFrames(
                listOf(
                    atClusterMinimum,
                    belowClusterMinimum,
                    atSmallestProducibleCluster,
                    atTheThreshold,
                    justOverTheThreshold,
                ),
                threshold,
            ),
        )
    }

    @Test
    fun theFalsifyThresholdIsInclusiveAndIsAShareOfTheMapRectangle() {
        // The analyzer's published sensitivity predicate, called for real - and the one place the
        // difference between "shipped" and "published" has to be said out loud.
        // [FlingExposureVideoAnalyzer.exposureLooksLikeBasemap] is what the analyzer offers as the
        // falsify comparison, but `GoogleFogFlingExposureSpikeTest.runFalsify` does not call it:
        // it inlines `exposedPx * 100.0 / mapArea >= FALSIFY_MINIMUM_PCT` against its own copy of
        // that constant, so this fixture is the function's only caller anywhere. What is pinned
        // below is therefore the predicate's shape and the threshold's VALUE - inclusive at the
        // bound, a share of the map rectangle rather than a pixel count, immune to label noise -
        // which is exactly the arithmetic the spike inlines, but pinned in one copy while the arm
        // that runs on the device uses another. Routing `runFalsify` through this function and
        // collapsing the duplicated 30.0 is what would make the two one thing; until then this
        // case is a specification pin on the sensitivity arm, not a call through to it.
        val bareRegion = exposedFrame(pts = 0L)
        assertTrue(
            FlingExposureVideoAnalyzer.exposureLooksLikeBasemap(
                bareRegion,
                MAP_AREA_PX,
                FALSIFY_MINIMUM_PCT,
            ),
        )
        assertFalse(
            FlingExposureVideoAnalyzer.exposureLooksLikeBasemap(
                bareRegion.copy(exposedPx = BARE_EXPOSED_PX - 1),
                MAP_AREA_PX,
                FALSIFY_MINIMUM_PCT,
            ),
        )
        // Label noise must never satisfy the falsify arm, or a blinded analyzer would certify
        // itself as sensitive.
        assertFalse(
            FlingExposureVideoAnalyzer.exposureLooksLikeBasemap(
                labelFrame(pts = 16L, exposedPx = LABEL_FLOOR_PX),
                MAP_AREA_PX,
                FALSIFY_MINIMUM_PCT,
            ),
        )
        // It is a share, not a count: the same pixels on a map half the size read twice as high.
        assertTrue(
            FlingExposureVideoAnalyzer.exposureLooksLikeBasemap(
                bareRegion.copy(exposedPx = BARE_EXPOSED_PX / 2),
                MAP_AREA_PX / 2,
                FALSIFY_MINIMUM_PCT,
            ),
        )
    }

    /**
     * The clapper window, transcribed from `analyze`: a pulse is a RISING edge of marker pixels
     * above half the marker rectangle, the first pulse opens the window and the second closes it.
     * The analyzer counts marker pixels on its private sampling grid, so the fixture takes the
     * already-strided marker area rather than duplicating that stride.
     */
    private fun motionWindow(frames: List<FrameStat>): MotionWindow {
        val markerOn = frames.map { it.markerPx > MARKER_AREA_SAMPLES / 2 }
        var pulses = 0
        var start = -1L
        var end = Long.MAX_VALUE
        markerOn.forEachIndexed { index, on ->
            if (on && (index == 0 || !markerOn[index - 1])) {
                pulses += 1
                when (pulses) {
                    1 -> start = frames[index].ptsMillis
                    2 -> end = frames[index].ptsMillis
                }
            }
        }
        return MotionWindow(startMillis = start, endMillis = end, pulses = pulses)
    }

    /** What `Analysis.motionEndMillis` publishes: an unclosed window is reported as `-1`. */
    private fun reportedEndMillis(window: MotionWindow): Long =
        if (window.endMillis == Long.MAX_VALUE) -1L else window.endMillis

    private fun framesInWindow(frames: List<FrameStat>, window: MotionWindow): List<FrameStat> =
        frames.filter { it.ptsMillis in window.startMillis..window.endMillis }

    private fun maxFrameGapMillis(frames: List<FrameStat>): Long =
        frames.map { it.ptsMillis }.zipWithNext().maxOfOrNull { (a, b) -> b - a } ?: -1L

    /** The floor is measured per scene from the pre-motion frames, plus the spike's margin. */
    private fun noiseThresholdPx(preMotion: List<FrameStat>): Int {
        val floorPct = preMotion.maxOfOrNull { it.exposedPx * 100.0 / MAP_AREA_PX } ?: 0.0
        return (MAP_AREA_PX * (floorPct + NOISE_MARGIN_PCT) / 100.0).toInt()
    }

    private fun exposedFrames(
        motionFrames: List<FrameStat>,
        noiseThresholdPx: Int = DEFAULT_NOISE_THRESHOLD_PX,
    ): List<FrameStat> = motionFrames.filter {
        it.largestClusterPx >= FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX &&
            it.exposedPx > noiseThresholdPx
    }

    private fun longestBurstMillis(
        motionFrames: List<FrameStat>,
        exposed: List<FrameStat>,
    ): Long {
        var longest = 0L
        var burstStart = -1L
        var previousExposed = false
        var previousPts = 0L
        motionFrames.forEach { frame ->
            val isExposed = frame in exposed
            if (isExposed && !previousExposed) burstStart = frame.ptsMillis
            if (!isExposed && previousExposed) {
                longest = maxOf(longest, previousPts - burstStart)
            }
            previousExposed = isExposed
            previousPts = frame.ptsMillis
        }
        if (previousExposed) longest = maxOf(longest, previousPts - burstStart)
        return longest
    }

    private fun frame(pts: Long, marker: Int): FrameStat = FrameStat(
        ptsMillis = pts,
        exposedPx = LABEL_FLOOR_PX,
        largestClusterPx = LABEL_CLUSTER_PX,
        markerPx = marker,
    )

    private fun clearFrame(pts: Long): FrameStat = frame(pts = pts, marker = MARKER_OFF_PX)

    private fun labelFrame(pts: Long, exposedPx: Int): FrameStat = FrameStat(
        ptsMillis = pts,
        exposedPx = exposedPx,
        largestClusterPx = LABEL_CLUSTER_PX,
        markerPx = MARKER_OFF_PX,
    )

    private fun exposedFrame(pts: Long): FrameStat = FrameStat(
        ptsMillis = pts,
        exposedPx = BARE_EXPOSED_PX,
        largestClusterPx = BARE_CLUSTER_PX,
        markerPx = MARKER_OFF_PX,
    )

    private data class MotionWindow(
        val startMillis: Long,
        val endMillis: Long,
        val pulses: Int,
    )

    private companion object {
        const val MAP_AREA_PX = 1_000_000

        /** The measured emulator floor: basemap labels sit above the fog at roughly 12% of area. */
        const val LABEL_FLOOR_PX = 120_000
        const val LABEL_CLUSTER_PX = 48
        const val BARE_EXPOSED_PX = 300_000
        const val BARE_CLUSTER_PX = 90_000
        const val DEFAULT_NOISE_THRESHOLD_PX = 120_500
        const val NOISE_MARGIN_PCT = 0.05

        /**
         * The same value `GoogleFogFlingExposureSpikeTest` declares in its own private companion.
         * Two declarations, one number: the duplication is the finding, and this constant exists
         * so the fixture pins the value the shipped falsify arm compares against.
         */
        const val FALSIFY_MINIMUM_PCT = 30.0

        /** `SAMPLE_STRIDE * SAMPLE_STRIDE` in the analyzer: one sampled cell is nine pixels. */
        const val CLUSTER_CELL_PX = 9

        /** Eight cells. The smallest cluster area a real capture can carry past the minimum. */
        const val SMALLEST_PRODUCIBLE_CLUSTER_PX = 72
        const val MARKER_AREA_SAMPLES = 400
        const val MARKER_ON_PX = 380
        const val MARKER_OFF_PX = 0
    }
}
