package app.trailveil.feature.history

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.get
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.feature.recording.RecordingEntryTestTags
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Actual MainActivity/NavHost regression for P4-013's detail-map compositor lifetime. */
@RunWith(AndroidJUnit4::class)
class HistoryDetailPopLifecycleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theDetailMapLeavesWithTheTransitionAndIsDestroyedWhenTheListSettles() {
        val database = (composeRule.activity.application as TrailVeilApplication)
            .appContainer
            .databaseForTesting()
        seedFixture(database)
        var listReferenceWindow: Bitmap? = null
        var listConfirmationWindow: Bitmap? = null
        try {
            dismissDisclosureIfShown()
            openFixtureHistoryList()
            listReferenceWindow = takeWholeWindowScreenshot()
            SystemClock.sleep(CALIBRATION_SETTLE_MILLIS)
            listConfirmationWindow = takeWholeWindowScreenshot()
            openFixtureDetailFromList()

            val firstMapView = awaitDetailMapView()
            val firstMap = awaitTrackMap(firstMapView)
            val firstOwner = composeRule.runOnIdle {
                checkNotNull(firstMapView.findViewTreeLifecycleOwner()) {
                    "The detail MapView has no NavBackStackEntry lifecycle owner"
                }
            }
            assertTrue(
                "The detail lifecycle owner was not active before pop",
                firstOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
            val detector = calibrateWholeWindowMapDetector(
                mapView = firstMapView,
                listReferenceWindow = checkNotNull(listReferenceWindow),
                listConfirmationWindow = checkNotNull(listConfirmationWindow),
            )

            val capture = startFrameCapture(detector)
            val listVisibleAt: Long
            val detailDestroyedAt: Long
            try {
                composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performClick()
                composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyListIsShowing() }
                composeRule.onNodeWithTag(RecordingHistoryTestTags.List).assertIsDisplayed()
                listVisibleAt = SystemClock.uptimeMillis()
                composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
                    composeRule.runOnIdle {
                        !firstMapView.isAttachedToWindow && firstMapView.isDestroyed
                    }
                }
                detailDestroyedAt = SystemClock.uptimeMillis()
                assertTrue(
                    "The exiting detail MapView outlived the 250 ms navigation transition: " +
                        "${detailDestroyedAt - listVisibleAt} ms",
                    detailDestroyedAt - listVisibleAt <= DETAIL_TEARDOWN_AFTER_LIST_BUDGET_MILLIS,
                )
                SystemClock.sleep(POST_LIST_CAPTURE_MILLIS)
            } finally {
                capture.stop()
            }

            // The detail map deliberately moves with the 250 ms horizontal exit. A screenshot begun
            // before teardown can return afterwards, so only captures that started after the old
            // MapView was destroyed prove the settled list has no stale compositor content.
            val afterTransition = capture.samples.filter {
                it.captureStartedAtMillis >= detailDestroyedAt
            }
            assertTrue(
                "Only ${afterTransition.size} whole-window frames were captured after detail teardown",
                afterTransition.size >= MINIMUM_POST_LIST_FRAMES,
            )
            assertTrue(
                "A popped detail-map signature remained after the horizontal exit: $afterTransition",
                afterTransition.none(FrameSample::mapPresent),
            )
            composeRule.runOnIdle {
                assertFalse("The popped detail MapView is still attached", firstMapView.isAttachedToWindow)
                assertNull("The popped detail MapView still has a parent", firstMapView.parent)
                assertTrue("The popped detail MapView was not destroyed", firstMapView.isDestroyed)
                assertEquals(
                    "The popped detail NavBackStackEntry lifecycle is not destroyed",
                    Lifecycle.State.DESTROYED,
                    firstOwner.lifecycle.currentState,
                )
            }

            // Re-enter through the real list and prove teardown did not poison the replacement GL
            // renderer or its gesture ownership.
            composeRule.onNodeWithTag(RecordingHistoryTestTags.item(FIXTURE_SESSION_ID)).performClick()
            composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { trackMapIsShowing() }
            val replacementMapView = awaitDetailMapView(excluding = firstMapView)
            assertNotSame("The popped MapView was reused after destruction", firstMapView, replacementMapView)
            val replacementMap = awaitTrackMap(replacementMapView)
            assertTrue(
                "The replacement detail renderer is not a live TextureView",
                replacementMapView.renderView is TextureView &&
                    (replacementMapView.renderView as TextureView).isAvailable,
            )
            repeat(2) { dragIndex ->
                val before = awaitStableCamera(replacementMap)
                composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap)
                    .performTouchInput { swipeUp() }
                awaitCameraMoved(replacementMap, before, dragIndex)
            }

            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "TrailVeil detail-pop lifecycle: mapStable=${detector.mapStableDifference} " +
                            "listStable=${detector.listStableDifference} " +
                            "separation=${detector.referenceSeparation} " +
                            "margin=${detector.classificationMargin} " +
                            "transitionMillis=${detailDestroyedAt - listVisibleAt} " +
                            "captured=${capture.samples.size} postTransition=${afterTransition.size} " +
                            "postDistances=${afterTransition.joinToString { sample ->
                                "%.4f/%.4f/b%d".format(
                                    sample.mapDistance,
                                    sample.listDistance,
                                    sample.mapSignalBlocks,
                                )
                            }} " +
                            "firstDestroyed=${firstMapView.isDestroyed} replacementPanned=true\n",
                    )
                },
            )

            composeRule.onNodeWithTag(RecordingHistoryTestTags.Back).performClick()
            composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyListIsShowing() }
            composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
                composeRule.runOnIdle { replacementMapView.isDestroyed }
            }
        } finally {
            listReferenceWindow?.recycle()
            listConfirmationWindow?.recycle()
            removeFixture(database)
        }
    }

    private fun dismissDisclosureIfShown() {
        repeat(DISCLOSURE_POLLS) {
            if (
                composeRule.onAllNodesWithTag(RecordingEntryTestTags.PrivacySheet)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacyDismiss).performClick()
                composeRule.waitForIdle()
                return
            }
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun openFixtureHistoryList() {
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingHistoryTestTags.item(FIXTURE_SESSION_ID))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.List).assertIsDisplayed()
        composeRule.waitForIdle()
    }

    private fun openFixtureDetailFromList() {
        composeRule.onNodeWithTag(RecordingHistoryTestTags.item(FIXTURE_SESSION_ID)).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { trackMapIsShowing() }
        composeRule.onNodeWithTag(RecordingHistoryTestTags.TrackMap).assertIsDisplayed()
    }

    private fun historyListIsShowing(): Boolean =
        composeRule.onAllNodesWithTag(RecordingHistoryTestTags.List)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun trackMapIsShowing(): Boolean =
        composeRule.onAllNodesWithTag(RecordingHistoryTestTags.TrackMap)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun awaitDetailMapView(excluding: MapView? = null): MapView {
        val found = AtomicReference<MapView?>()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            val candidate = composeRule.runOnIdle {
                composeRule.activity.window.decorView.findMapViews()
                    .firstOrNull { view ->
                        view !== excluding && view.isAttachedToWindow && view.renderView is TextureView
                    }
            }
            found.set(candidate)
            candidate != null
        }
        return checkNotNull(found.get()) { "No attached history TextureView MapView" }
    }

    private fun awaitTrackMap(mapView: MapView): MapLibreMap {
        val resolved = AtomicReference<MapLibreMap?>()
        composeRule.runOnIdle { mapView.getMapAsync(resolved::set) }
        composeRule.waitUntil(MAP_READY_TIMEOUT_MILLIS) {
            resolved.get()?.cameraPosition?.target?.let { target ->
                abs(target.latitude) > INITIAL_CAMERA_EPSILON_DEGREES ||
                    abs(target.longitude) > INITIAL_CAMERA_EPSILON_DEGREES
            } ?: false
        }
        val map = checkNotNull(resolved.get()) { "The history map never became ready" }
        awaitStableCamera(map)
        composeRule.waitUntil(MAP_READY_TIMEOUT_MILLIS) {
            composeRule.runOnIdle {
                (mapView.renderView as? TextureView)?.isAvailable == true
            }
        }
        return map
    }

    private fun awaitStableCamera(map: MapLibreMap): LatLng {
        var previous: LatLng? = null
        var latest: LatLng? = null
        var stable = 0
        composeRule.waitUntil(CAMERA_SETTLE_TIMEOUT_MILLIS) {
            val current = composeRule.runOnIdle {
                checkNotNull(map.cameraPosition.target) { "History map lost its camera target" }
            }
            latest = current
            stable = if (
                previous != null && cameraDistance(checkNotNull(previous), current) <=
                CAMERA_STABLE_EPSILON_DEGREES
            ) {
                stable + 1
            } else {
                0
            }
            previous = current
            stable >= CAMERA_STABLE_POLLS
        }
        return checkNotNull(latest)
    }

    private fun awaitCameraMoved(map: MapLibreMap, before: LatLng, dragIndex: Int) {
        composeRule.waitUntil(CAMERA_MOVE_TIMEOUT_MILLIS) {
            val current = composeRule.runOnIdle {
                checkNotNull(map.cameraPosition.target) { "History map lost its camera target" }
            }
            cameraDistance(before, current) > CAMERA_MOVE_EPSILON_DEGREES
        }
        val after = awaitStableCamera(map)
        assertTrue(
            "Replacement history-map drag ${dragIndex + 1} did not move the camera: $before -> $after",
            cameraDistance(before, after) > CAMERA_MOVE_EPSILON_DEGREES,
        )
    }

    private fun calibrateWholeWindowMapDetector(
        mapView: MapView,
        listReferenceWindow: Bitmap,
        listConfirmationWindow: Bitmap,
    ): MapDetector {
        val bounds = composeRule.runOnIdle { mapView.boundsOnScreen() }
        val listReference = fingerprint(listReferenceWindow, bounds)
        val listStableDifference = difference(listReference, listConfirmationWindow)
        assertTrue(
            "The history list was not stable enough to serve as a negative map sample " +
                "(difference=$listStableDifference)",
            listStableDifference <= MAX_STABLE_REFERENCE_DIFFERENCE,
        )
        val textureBitmap = composeRule.runOnIdle {
            checkNotNull((mapView.renderView as TextureView).bitmap) {
                "The detail TextureView produced no calibration bitmap"
            }
        }
        val first = takeWholeWindowScreenshot()
        val textureDifference = difference(textureBitmap, first, bounds)
        textureBitmap.recycle()
        assertTrue(
            "Whole-window capture omitted or altered the live detail TextureView " +
                "(difference=$textureDifference)",
            textureDifference <= MAX_TEXTURE_TO_WINDOW_DIFFERENCE,
        )
        val mapReference = fingerprint(first, bounds)
        first.recycle()
        SystemClock.sleep(CALIBRATION_SETTLE_MILLIS)
        val second = takeWholeWindowScreenshot()
        val mapStableDifference = difference(mapReference, second)
        assertTrue(
            "The detail map was not stable enough to identify after pop " +
                "(difference=$mapStableDifference)",
            mapStableDifference <= MAX_STABLE_REFERENCE_DIFFERENCE,
        )
        val referenceSeparation = difference(mapReference, listReferenceWindow)
        assertTrue(
            "The live detail map and history-list references are not distinguishable " +
                "(difference=$referenceSeparation)",
            referenceSeparation >= MINIMUM_REFERENCE_SEPARATION,
        )
        val classificationMargin = maxOf(
            MINIMUM_CLASSIFICATION_MARGIN,
            referenceSeparation * CLASSIFICATION_MARGIN_FRACTION,
        )
        val detector = MapDetector(
            mapReference = mapReference,
            listReference = listReference,
            mapStableDifference = mapStableDifference,
            listStableDifference = listStableDifference,
            referenceSeparation = referenceSeparation,
            classificationMargin = classificationMargin,
        )
        val positive = classify(detector, second)
        second.recycle()
        val negative = classify(detector, listConfirmationWindow)
        assertTrue(
            "The calibrated detector did not recognize a live detail map: $positive",
            positive.clearlyMap && positive.mapSignalBlocks > 0,
        )
        assertTrue(
            "The calibrated detector did not reject the map signature on the history list: $negative",
            negative.clearlyList && negative.mapSignalBlocks == 0,
        )
        assertAdversarialMapSignaturesAreDetected(detector)
        return detector
    }

    private fun startFrameCapture(detector: MapDetector): FrameCapture {
        val running = AtomicBoolean(true)
        val samples = CopyOnWriteArrayList<FrameSample>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val thread = Thread(
            {
                while (running.get()) {
                    val captureStartedAt = SystemClock.uptimeMillis()
                    runCatching { checkNotNull(automation.takeScreenshot()) }
                        .onSuccess { screenshot ->
                            val classification = classify(detector, screenshot)
                            samples += FrameSample(
                                captureStartedAtMillis = captureStartedAt,
                                capturedAtMillis = SystemClock.uptimeMillis(),
                                mapDistance = classification.mapDistance,
                                listDistance = classification.listDistance,
                                mapSignalBlocks = classification.mapSignalBlocks,
                                // Ambiguous global samples and any local old-map-directed block fail
                                // closed. A frame proves absence only when both checks clear it.
                                mapPresent = classification.oldMapNotExcluded,
                            )
                            screenshot.recycle()
                        }
                    SystemClock.sleep(FRAME_CAPTURE_GAP_MILLIS)
                }
            },
            "trailveil-history-pop-frame-capture",
        ).apply { start() }
        return FrameCapture(running, thread, samples)
    }

    private fun fingerprint(bitmap: Bitmap, bounds: Rect): MapFingerprint {
        require(bounds.left >= 0 && bounds.top >= 0)
        require(bounds.right <= bitmap.width && bounds.bottom <= bitmap.height)
        val colors = ArrayList<Int>()
        var y = bounds.top + FRAME_SAMPLE_INSET_PX
        while (y < bounds.bottom - FRAME_SAMPLE_INSET_PX) {
            var x = bounds.left + FRAME_SAMPLE_INSET_PX
            while (x < bounds.right - FRAME_SAMPLE_INSET_PX) {
                colors += bitmap[x, y]
                x += FRAME_SAMPLE_STRIDE_PX
            }
            y += FRAME_SAMPLE_STRIDE_PX
        }
        check(colors.isNotEmpty()) { "The map bounds produced no fingerprint samples: $bounds" }
        val columns = sequence {
            var x = bounds.left + FRAME_SAMPLE_INSET_PX
            while (x < bounds.right - FRAME_SAMPLE_INSET_PX) {
                yield(x)
                x += FRAME_SAMPLE_STRIDE_PX
            }
        }.count()
        return MapFingerprint(bounds, colors.toIntArray(), columns)
    }

    private fun difference(reference: MapFingerprint, bitmap: Bitmap): Double {
        val actual = fingerprint(bitmap, reference.bounds)
        check(actual.colors.size == reference.colors.size)
        var total = 0L
        reference.colors.indices.forEach { index ->
            total += colorDistance(reference.colors[index], actual.colors[index])
        }
        return total.toDouble() / (reference.colors.size * MAXIMUM_COLOR_DISTANCE)
    }

    private fun takeWholeWindowScreenshot(): Bitmap =
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "UiAutomation produced no whole-window screenshot"
        }

    private fun classify(detector: MapDetector, bitmap: Bitmap): MapClassification {
        val actual = fingerprint(bitmap, detector.mapReference.bounds)
        return classify(detector, actual)
    }

    private fun classify(detector: MapDetector, actual: MapFingerprint): MapClassification {
        val mapDistance = difference(detector.mapReference, actual)
        val listDistance = difference(detector.listReference, actual)
        val mapSignalBlocks = mapSignalBlockCount(detector, actual)
        return MapClassification(
            mapDistance = mapDistance,
            listDistance = listDistance,
            clearlyMap = mapDistance + detector.classificationMargin < listDistance,
            clearlyList = listDistance + detector.classificationMargin < mapDistance,
            mapSignalBlocks = mapSignalBlocks,
        )
    }

    private fun mapSignalBlockCount(detector: MapDetector, actual: MapFingerprint): Int {
        val columns = detector.mapReference.columns
        val rows = detector.mapReference.colors.size / columns
        var signals = 0
        var row = 0
        while (row < rows) {
            var column = 0
            while (column < columns) {
                val rowEnd = minOf(row + SIGNATURE_BLOCK_SAMPLES, rows)
                val columnEnd = minOf(column + SIGNATURE_BLOCK_SAMPLES, columns)
                var referenceEnergy = 0.0
                var actualProjection = 0.0
                var actualEnergy = 0.0
                var samples = 0
                for (sampleRow in row until rowEnd) {
                    for (sampleColumn in column until columnEnd) {
                        val index = sampleRow * columns + sampleColumn
                        val map = detector.mapReference.colors[index]
                        val list = detector.listReference.colors[index]
                        val observed = actual.colors[index]
                        for (shift in COLOR_CHANNEL_SHIFTS) {
                            val referenceDelta =
                                (map shr shift and 0xff) - (list shr shift and 0xff)
                            val actualDelta =
                                (observed shr shift and 0xff) - (list shr shift and 0xff)
                            referenceEnergy += referenceDelta.toDouble() * referenceDelta
                            actualProjection += referenceDelta.toDouble() * actualDelta
                            actualEnergy += actualDelta.toDouble() * actualDelta
                        }
                        samples += 1
                    }
                }
                val minimumEnergy = samples * COLOR_CHANNEL_SHIFTS.size *
                    MINIMUM_BLOCK_REFERENCE_RMS * MINIMUM_BLOCK_REFERENCE_RMS
                if (referenceEnergy >= minimumEnergy) {
                    val projection = actualProjection / referenceEnergy
                    val residualEnergy = (
                        actualEnergy - 2.0 * projection * actualProjection +
                            projection * projection * referenceEnergy
                        ).coerceAtLeast(0.0)
                    if (
                        projection >= MINIMUM_MAP_SIGNAL_PROJECTION &&
                        residualEnergy / referenceEnergy <= MAXIMUM_MAP_SIGNAL_RESIDUAL_RATIO
                    ) {
                        signals += 1
                    }
                }
                column += SIGNATURE_BLOCK_SAMPLES
            }
            row += SIGNATURE_BLOCK_SAMPLES
        }
        return signals
    }

    private fun assertAdversarialMapSignaturesAreDetected(detector: MapDetector) {
        val strongestBlock = strongestReferenceBlock(detector)
        val partialColors = detector.listReference.colors.copyOf()
        strongestBlock.indices.forEach { index ->
            partialColors[index] = detector.mapReference.colors[index]
        }
        val partial = classify(
            detector,
            detector.listReference.copy(colors = partialColors),
        )
        assertTrue(
            "A synthetic local old-map patch escaped the calibrated detector: $partial",
            partial.oldMapNotExcluded && partial.mapSignalBlocks > 0,
        )

        val fadedColors = IntArray(detector.listReference.colors.size) { index ->
            blendColor(
                detector.listReference.colors[index],
                detector.mapReference.colors[index],
                SYNTHETIC_FADED_MAP_ALPHA,
            )
        }
        val faded = classify(
            detector,
            detector.listReference.copy(colors = fadedColors),
        )
        assertTrue(
            "A synthetic low-alpha full-map residue escaped the calibrated detector: $faded",
            faded.oldMapNotExcluded && faded.mapSignalBlocks > 0,
        )
    }

    private fun strongestReferenceBlock(detector: MapDetector): SignatureBlock {
        val columns = detector.mapReference.columns
        val rows = detector.mapReference.colors.size / columns
        var strongest: SignatureBlock? = null
        var row = 0
        while (row < rows) {
            var column = 0
            while (column < columns) {
                val indices = buildList {
                    for (sampleRow in row until minOf(row + SIGNATURE_BLOCK_SAMPLES, rows)) {
                        for (
                            sampleColumn in column until
                                minOf(column + SIGNATURE_BLOCK_SAMPLES, columns)
                        ) {
                            add(sampleRow * columns + sampleColumn)
                        }
                    }
                }
                val energy = indices.sumOf { index ->
                    val map = detector.mapReference.colors[index]
                    val list = detector.listReference.colors[index]
                    COLOR_CHANNEL_SHIFTS.sumOf { shift ->
                        val delta = (map shr shift and 0xff) - (list shr shift and 0xff)
                        delta.toDouble() * delta
                    }
                }
                if (strongest == null || energy > checkNotNull(strongest).energy) {
                    strongest = SignatureBlock(indices, energy)
                }
                column += SIGNATURE_BLOCK_SAMPLES
            }
            row += SIGNATURE_BLOCK_SAMPLES
        }
        return checkNotNull(strongest) { "The detector reference had no signature blocks" }
    }

    private fun blendColor(list: Int, map: Int, mapAlpha: Double): Int {
        var blended = -0x1000000
        for (shift in COLOR_CHANNEL_SHIFTS) {
            val listChannel = list shr shift and 0xff
            val mapChannel = map shr shift and 0xff
            val channel = (listChannel + (mapChannel - listChannel) * mapAlpha)
                .toInt()
                .coerceIn(0, 255)
            blended = blended or (channel shl shift)
        }
        return blended
    }

    private fun difference(first: MapFingerprint, second: MapFingerprint): Double {
        check(first.bounds == second.bounds)
        check(first.colors.size == second.colors.size)
        var total = 0L
        first.colors.indices.forEach { index ->
            total += colorDistance(first.colors[index], second.colors[index])
        }
        return total.toDouble() / (first.colors.size * MAXIMUM_COLOR_DISTANCE)
    }

    private fun difference(texture: Bitmap, window: Bitmap, bounds: Rect): Double {
        var total = 0L
        var samples = 0
        var y = FRAME_SAMPLE_INSET_PX
        while (y < bounds.height() - FRAME_SAMPLE_INSET_PX) {
            var x = FRAME_SAMPLE_INSET_PX
            while (x < bounds.width() - FRAME_SAMPLE_INSET_PX) {
                val textureX = x * texture.width / bounds.width()
                val textureY = y * texture.height / bounds.height()
                total += colorDistance(
                    texture[textureX, textureY],
                    window[bounds.left + x, bounds.top + y],
                )
                samples += 1
                x += FRAME_SAMPLE_STRIDE_PX
            }
            y += FRAME_SAMPLE_STRIDE_PX
        }
        check(samples > 0)
        return total.toDouble() / (samples * MAXIMUM_COLOR_DISTANCE)
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
            abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
            abs((first and 0xff) - (second and 0xff))

    private fun View.boundsOnScreen(): Rect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun View.findMapViews(): List<MapView> = buildList {
        if (this@findMapViews is MapView) add(this@findMapViews)
        if (this@findMapViews is ViewGroup) {
            repeat(childCount) { index -> addAll(getChildAt(index).findMapViews()) }
        }
    }

    private fun cameraDistance(first: LatLng, second: LatLng): Double =
        abs(first.latitude - second.latitude) + abs(first.longitude - second.longitude)

    private fun seedFixture(database: TrailVeilDatabase) = runBlocking {
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            sql.execSQL(
                "DELETE FROM recording_sessions WHERE id = ?",
                arrayOf(FIXTURE_SESSION_ID),
            )
            sql.execSQL(
                "INSERT INTO recording_sessions(" +
                    "id, started_at, ended_at, status, stop_reason, distance_meters, " +
                    "accepted_point_count, rejected_point_count, created_app_version, " +
                    "active_slot, location_owner_token" +
                    ") VALUES (?, ?, ?, 'COMPLETED', 'USER_STOP', 125.0, 2, 0, ?, NULL, NULL)",
                arrayOf<Any?>(
                    FIXTURE_SESSION_ID,
                    FIXTURE_STARTED_AT,
                    FIXTURE_ENDED_AT,
                    "p4-013-integration-test",
                ),
            )
            sql.execSQL(
                "INSERT INTO track_segments(" +
                    "id, session_id, sequence, started_at, ended_at, start_reason, end_reason, open_slot" +
                    ") VALUES (?, ?, 0, ?, ?, 'SESSION_START', 'USER_STOP', NULL)",
                arrayOf<Any?>(
                    FIXTURE_SEGMENT_ID,
                    FIXTURE_SESSION_ID,
                    FIXTURE_STARTED_AT,
                    FIXTURE_ENDED_AT,
                ),
            )
            sql.execSQL(
                "INSERT INTO track_points(" +
                    "id, session_id, segment_id, sequence, timestamp, latitude, longitude, " +
                    "horizontal_accuracy, altitude, speed, bearing, is_mock" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, 5.0, NULL, NULL, NULL, 0)",
                arrayOf<Any?>(
                    FIXTURE_POINT_ID,
                    FIXTURE_SESSION_ID,
                    FIXTURE_SEGMENT_ID,
                    0,
                    FIXTURE_STARTED_AT,
                    25.0330,
                    121.5654,
                ),
            )
            sql.execSQL(
                "INSERT INTO track_points(" +
                    "id, session_id, segment_id, sequence, timestamp, latitude, longitude, " +
                    "horizontal_accuracy, altitude, speed, bearing, is_mock" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, 5.0, NULL, NULL, NULL, 0)",
                arrayOf<Any?>(
                    FIXTURE_POINT_ID + 1,
                    FIXTURE_SESSION_ID,
                    FIXTURE_SEGMENT_ID,
                    1,
                    FIXTURE_STARTED_AT + 5_000L,
                    25.0340,
                    121.5664,
                ),
            )
        }
    }

    private fun removeFixture(database: TrailVeilDatabase) = runBlocking {
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM recording_sessions WHERE id = ?",
                arrayOf(FIXTURE_SESSION_ID),
            )
        }
    }

    private data class MapFingerprint(
        val bounds: Rect,
        val colors: IntArray,
        val columns: Int,
    )

    private data class MapDetector(
        val mapReference: MapFingerprint,
        val listReference: MapFingerprint,
        val mapStableDifference: Double,
        val listStableDifference: Double,
        val referenceSeparation: Double,
        val classificationMargin: Double,
    )

    private data class MapClassification(
        val mapDistance: Double,
        val listDistance: Double,
        val clearlyMap: Boolean,
        val clearlyList: Boolean,
        val mapSignalBlocks: Int,
    ) {
        val oldMapNotExcluded: Boolean
            get() = !clearlyList || mapSignalBlocks > 0
    }

    private data class SignatureBlock(val indices: List<Int>, val energy: Double)

    private data class FrameSample(
        val captureStartedAtMillis: Long,
        val capturedAtMillis: Long,
        val mapDistance: Double,
        val listDistance: Double,
        val mapSignalBlocks: Int,
        val mapPresent: Boolean,
    )

    private class FrameCapture(
        private val running: AtomicBoolean,
        private val thread: Thread,
        val samples: List<FrameSample>,
    ) {
        fun stop() {
            running.set(false)
            thread.join(TimeUnit.SECONDS.toMillis(FRAME_CAPTURE_JOIN_SECONDS))
            check(!thread.isAlive) { "Whole-window frame capture did not stop" }
        }
    }

    private companion object {
        const val FIXTURE_SESSION_ID = 9_013_000_000L
        const val FIXTURE_SEGMENT_ID = FIXTURE_SESSION_ID + 1
        const val FIXTURE_POINT_ID = FIXTURE_SESSION_ID + 2
        const val FIXTURE_STARTED_AT = 9_013_000_000L
        const val FIXTURE_ENDED_AT = FIXTURE_STARTED_AT + 60_000L

        const val NAVIGATION_TIMEOUT_MILLIS = 20_000L
        const val MAP_READY_TIMEOUT_MILLIS = 20_000L
        const val CAMERA_SETTLE_TIMEOUT_MILLIS = 5_000L
        const val CAMERA_MOVE_TIMEOUT_MILLIS = 5_000L
        const val CAMERA_STABLE_POLLS = 3
        const val CAMERA_STABLE_EPSILON_DEGREES = 0.000_000_01
        const val CAMERA_MOVE_EPSILON_DEGREES = 0.000_001
        const val INITIAL_CAMERA_EPSILON_DEGREES = 1.0

        const val DISCLOSURE_POLLS = 40
        const val POLL_MILLIS = 100L
        const val CALIBRATION_SETTLE_MILLIS = 250L
        const val DETAIL_TEARDOWN_AFTER_LIST_BUDGET_MILLIS = 750L
        const val POST_LIST_CAPTURE_MILLIS = 1_000L
        const val FRAME_CAPTURE_GAP_MILLIS = 8L
        const val FRAME_CAPTURE_JOIN_SECONDS = 5L
        const val MINIMUM_POST_LIST_FRAMES = 3
        const val FRAME_SAMPLE_INSET_PX = 24
        const val FRAME_SAMPLE_STRIDE_PX = 8
        const val MAXIMUM_COLOR_DISTANCE = 255.0 * 3.0
        const val MAX_TEXTURE_TO_WINDOW_DIFFERENCE = 0.10
        const val MAX_STABLE_REFERENCE_DIFFERENCE = 0.05
        const val MINIMUM_REFERENCE_SEPARATION = 0.02
        const val MINIMUM_CLASSIFICATION_MARGIN = 0.002
        const val CLASSIFICATION_MARGIN_FRACTION = 0.10
        const val SIGNATURE_BLOCK_SAMPLES = 4
        const val MINIMUM_BLOCK_REFERENCE_RMS = 8.0
        const val MINIMUM_MAP_SIGNAL_PROJECTION = 0.10
        const val MAXIMUM_MAP_SIGNAL_RESIDUAL_RATIO = 0.75
        const val SYNTHETIC_FADED_MAP_ALPHA = 0.20
        val COLOR_CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
    }
}
