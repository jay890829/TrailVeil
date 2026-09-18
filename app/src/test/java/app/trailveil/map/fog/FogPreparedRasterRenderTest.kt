package app.trailveil.map.fog

import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.ViewportTrackPointReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FogPreparedRasterRenderTest {
    @Test fun nativeSuccessUsesRequiredLodsAndFallbackRestoresEveryOriginalMask() = runTest {
        val required = setOf(key(5, 15, 16), key(4, 7, 8))
        val full = required + setOf(key(5, 13, 14), key(5, 17, 18))
        for (optIn in listOf(false, true)) for (native in listOf(false, true)) {
            val calls = mutableListOf<Set<FogTileKey>>()
            var prepares = 0
            val result = renderFogWithRequiredMasks(full, required, optIn, 256,
                render = { calls += it; render(it) }, prepare = {
                    prepares++
                    assertTrue(it.keys.containsAll(required))
                    native
                })
            assertEquals(1, prepares)
            assertEquals(if (optIn) required else full, calls.first())
            assertEquals(if (optIn && !native) 2 else 1, calls.size)
            val expected = render(if (optIn && native) required else full)
            assertEquals(expected.keys.toList(), result.keys.toList())
            expected.forEach { (key, mask) -> assertEquals(mask, result[key]) }
        }
    }

    @Test fun everySmallCyclicSubsetRetainsTheOriginalCompletionBound() = runTest {
        var cases = 0
        var refusedReduction = 0
        for (bits in 1 until 256) {
            val full = (0..7).filter { bits and (1 shl it) != 0 }.map { key(3, it, 3) }.toSet()
            for (subset in 1..bits) {
                if (subset and bits != subset) continue
                val required = full.filter { subset and (1 shl it.x) != 0 }.toSet()
                val fullPlan = FogRequestedTileWindowPlanner(8).plan(full).keys
                val calls = mutableListOf<Set<FogTileKey>>()
                val result = renderFogWithRequiredMasks(full, required, true, 8,
                    render = { calls += it; render(it) }, prepare = { true })
                assertEquals(1, calls.size)
                assertTrue(fullPlan.containsAll(result.keys))
                assertTrue(result.keys.containsAll(required))
                assertEquals(fullPlan.filter { it in result.keys }, result.keys.toList())
                if (calls.single() == full && required != full) refusedReduction++
                cases++
            }
        }
        assertEquals(6305, cases)
        assertTrue("arc changes and completion ties must retain full rendering", refusedReduction > 0)
        var touched = false
        val error = runCatching { renderFogWithRequiredMasks(setOf(key(5, 1, 1), key(5, 15, 15)),
            setOf(key(5, 1, 1)), true, 32, { touched = true; render(it) }, { true }) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(touched)
    }

    @Test fun cancellationAfterEitherRasterOrPreparationNeverReturnsMasks() = runTest {
        val required = setOf(key(5, 15, 16))
        val full = required + setOf(key(5, 13, 14), key(5, 17, 18))
        for (cancelAt in listOf("first", "prepare", "fallback")) {
            var renders = 0
            var prepares = 0
            var returned = false
            val attempt = async {
                renderFogWithRequiredMasks(full, required, true, 256, render = {
                    renders++
                    val masks = render(it)
                    if (cancelAt == "first" || (cancelAt == "fallback" && renders == 2)) {
                        currentCoroutineContext().cancel()
                    }
                    masks
                }, prepare = {
                    prepares++
                    if (cancelAt == "prepare") currentCoroutineContext().cancel()
                    false
                })
                returned = true
            }
            assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
            assertFalse(returned)
            assertEquals(if (cancelAt == "first") 0 else 1, prepares)
            assertEquals(if (cancelAt == "fallback") 2 else 1, renders)
        }
    }

    @Test fun actualCoordinatorFallbackIsExactAcrossColdWarmAppendAndReset() = runTest {
        for (center in listOf(GeoPoint(25.0, 121.0), GeoPoint(0.0, 179.999), GeoPoint(84.9, -179.999))) {
            val required = FogViewportTileGrid.around(center, 12, 1, 0).toSet()
            val full = FogViewportTileGrid.around(center, 12, 1, 2).toSet()
            val reference = Rig(center)
            val candidate = Rig(center)
            for (step in 0..3) {
                if (step == 2) for (rig in listOf(reference, candidate)) {
                    rig.points = listOf(ViewportTrackPoint(1, 1, 1, 0, 0, center.latitude, center.longitude))
                    rig.coordinator.mergePersistedReveals(listOf(FogRevealUpdate(center)))
                }
                if (step == 3) for (rig in listOf(reference, candidate)) {
                    rig.points = emptyList()
                    rig.coordinator.clearDerivedCache()
                }
                val expected = reference.render(full)
                val actual = renderFogWithRequiredMasks(full, required, true, 256, candidate::render, { false })
                assertEquals(expected.keys.toList(), actual.keys.toList())
                expected.forEach { (key, mask) -> assertEquals("step=$step key=$key", mask, actual[key]) }
                assertEquals(reference.pipeline.cachedKeys(), candidate.pipeline.cachedKeys())
                // Both finish with the full last viewport for subsequent delta maintenance.
                val refDelta = reference.coordinator.mergePersistedReveals(listOf(FogRevealUpdate(center)))
                val actualDelta = candidate.coordinator.mergePersistedReveals(listOf(FogRevealUpdate(center)))
                assertEquals(refDelta.updatedKeys, actualDelta.updatedKeys)
                assertEquals(refDelta.missingKeys, actualDelta.missingKeys)
            }
        }
    }

    @Test fun droppingOffscreenPaddingPreservesAllProbeBlocksAndZoneDecisions() = runTest {
        var cases = 0
        for (zoom in listOf(0, 1, 2, 12)) for (latitude in listOf(-80.0, 0.0, 80.0)) {
            for (longitude in listOf(-179.999, 15.0, 179.999, 375.0)) {
                val delta = 360.0 / (1 shl zoom) / 8
                val c = GeoPoint(latitude, longitude)
                val request = FogViewportCoverageRequest(c, zoom,
                    GeoPoint((latitude - delta).coerceAtLeast(-85.0), longitude - delta),
                    GeoPoint((latitude + delta).coerceAtMost(85.0), longitude - delta),
                    GeoPoint((latitude + delta).coerceAtMost(85.0), longitude + delta),
                    GeoPoint((latitude - delta).coerceAtLeast(-85.0), longitude + delta))
                val required = FogViewportCoveragePlanner(paddingTiles = 0).plan(request).keySet
                val full = FogViewportCoveragePlanner(paddingTiles = 2).plan(request).keySet
                for (pattern in 0..2) for (zones in listOf(emptyList(),
                    listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0)),
                    listOf(FogProbeExclusionZone(-20.0, 20.0, -30.0, 30.0)))) {
                    val windowPlanner = FogRequestedTileWindowPlanner()
                    val masks = windowPlanner.plan(full).keys.associateWith { k -> FogPixelMask(32, 32, ByteArray(1024) { i ->
                        if (pattern == 0 || (pattern == 2 && (i + k.x * 7 + k.y * 3) % 23 < 6)) 184.toByte() else 0
                    }) }
                    val planner = FogSnapshotVisualProbePlanner()
                    val expected = planner.plan(request, masks, zones)
                    val reducedMasks = renderFogWithRequiredMasks(full, required, true, 256,
                        render = { keys -> windowPlanner.plan(keys).keys.associateWith { checkNotNull(masks[it]) } },
                        prepare = { true })
                    val actual = planner.plan(request, reducedMasks, zones)
                    assertEquals(expected.probesByKey.keys.toList(), actual.probesByKey.keys.toList())
                    assertEquals(expected.probesByKey, actual.probesByKey)
                    assertEquals(expected.zoneBlockedKeys.toList(), actual.zoneBlockedKeys.toList())
                    assertEquals(expected.isProvable(), actual.isProvable())
                    for (key in full) assertEquals(expected.probeBlocks(key), actual.probeBlocks(key))
                    cases++
                }
            }
        }
        assertEquals(432, cases)
    }

    @Test fun fallbackReadsAppendOrResetBetweenTheTwoRasterStages() = runTest {
        val center = GeoPoint(25.0, 121.0)
        val required = FogViewportTileGrid.around(center, 16, 1, 0).toSet()
        val full = FogViewportTileGrid.around(center, 16, 1, 2).toSet()
        for (reset in listOf(false, true)) for (cacheBytes in listOf(1024L, 4_000_000L)) {
            val reference = Rig(center, cacheBytes)
            val candidate = Rig(center, cacheBytes)
            val point = ViewportTrackPoint(1, 1, 1, 0, 0, center.latitude, center.longitude)
            if (reset) for (rig in listOf(reference, candidate)) rig.points = listOf(point)
            suspend fun replace(rig: Rig) {
                rig.points = if (reset) emptyList() else listOf(point)
                if (reset) rig.coordinator.clearDerivedCache()
                else rig.coordinator.mergePersistedReveals(listOf(FogRevealUpdate(center)))
            }
            // Independent one-stage render sees exactly the canonical state at fallback time.
            replace(reference)
            val expected = reference.render(full)
            val actual = renderFogWithRequiredMasks(full, required, true, 256, candidate::render, {
                replace(candidate)
                false
            })
            assertEquals(expected, actual)
        }
    }

    private class Rig(val center: GeoPoint, cacheBytes: Long = 4_000_000) {
        var points = emptyList<ViewportTrackPoint>()
        val style = FogRenderStyle(tileSize = 32)
        val pipeline = FogTilePipeline(FogMemoryTileCache(cacheBytes), null, FogTileRenderer(style)::render)
        val coordinator = FogViewportCoordinator(ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> points }), pipeline, style)
        private val renderer = FogRequestedTileWindowRenderer(FogViewportBatchSubrenderer(coordinator::renderTiles))
        suspend fun render(keys: Set<FogTileKey>) = renderer.render(center, keys)
    }
    private suspend fun render(keys: Set<FogTileKey>): Map<FogTileKey, FogPixelMask> =
        FogRequestedTileWindowRenderer(FogViewportBatchSubrenderer { request, window ->
            FogViewportTileRender(request, window, null, window.map { key ->
                FogMosaicTile(key, FogPixelMask(4, 4, ByteArray(16) { (it + key.x * 7 + key.y * 3).toByte() }))
            })
        }).render(GeoPoint(0.0, 0.0), keys)
    private fun key(z: Int, x: Int, y: Int) = FogTileKey(z, x, y, 1)
}
