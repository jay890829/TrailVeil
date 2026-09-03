package app.trailveil.map.fog

import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPointReader
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the 2026-09-01 fog-merge starvation defect.
 *
 * `FogViewportCoordinator` invalidates through `FogTileInvalidator(0..22)`, and the invalidator
 * answers exactly by rendering the tile twice — before and after — and comparing the masks. Merging
 * one canonical page therefore cost `points x zoomLevels x candidateTiles x 2` full 256x256 renders,
 * with a page carrying up to 256 points. On a real backlog that was measured at 31 s and still
 * climbing for a single page.
 *
 * The cost alone would only be slow. What made it a defect is where it was spent: inside the
 * process-scoped coordinator mutex, in a loop with no suspension point. Coroutine cancellation is
 * cooperative, so the caller's `withTimeout` expired while the holder kept the lock, and every other
 * map surface in the process starved behind it — silently, and fail-closed, so the symptom was a
 * permanently covered map rather than an error. It was found because a second surface could never
 * install fog after a track-seeding screen ran first.
 *
 * Almost all of that work was discarded: the memory and disk caches together hold only the tiles
 * that were actually rendered, and [FogTilePipeline.mergeReveal] reports any uncached tile as
 * missing without merging it. So the fix is to ask the cache before paying for the exact answer,
 * and these tests pin that the scan stays bounded by what the caches actually hold rather than by
 * the zoom range.
 */
class FogRevealMergeCostTest {

    @Test
    fun aColdCacheMergePaysForNothingBecauseThereIsNothingToMergeInto() = runTest {
        val coordinator = coordinator()

        val merge = coordinator.mergePersistedReveals(listOf(update()))

        assertTrue(
            "a cold cache holds no tile this reveal could be merged into, so the exact " +
                "invalidation scan buys an answer that is discarded; reporting keys here means " +
                "all 23 zoom levels were rendered twice per point to produce it",
            merge.updatedKeys.isEmpty() && merge.missingKeys.isEmpty(),
        )
    }

    @Test
    fun aWarmCacheMergeStaysInsideTheZoomTheCacheActuallyHolds() = runTest {
        val coordinator = coordinator()
        val request = FogViewportRequest(center = CURRENT, mapZoom = EXPLORATION_MAP_ZOOM)
        val initial = coordinator.render(request)
        val cachedZoom = initial.keys.map(FogTileKey::zoom).distinct()
        assertEquals("the fixture must warm exactly one zoom for this to prove anything", 1, cachedZoom.size)

        val merge = coordinator.mergePersistedReveals(listOf(update()))

        assertTrue("the warm merge updated nothing, so it proves nothing", merge.updatedKeys.isNotEmpty())
        assertEquals(
            "the merge scanned zoom levels the cache does not hold; that is the whole cost, and " +
                "it is spent under the shared lock that every other surface waits on",
            cachedZoom.toSet(),
            (merge.updatedKeys + merge.missingKeys).map(FogTileKey::zoom).toSet(),
        )
        assertTrue(
            "the merge must still touch the cached tiles it always did",
            merge.updatedKeys.all { key -> key in initial.keys },
        )
    }

    @Test
    fun theInvalidatorNeverReportsACandidateItWasToldNotToTest() {
        val probed = mutableListOf<FogTileKey>()

        val affected = FogTileInvalidator(0..22).affectedKeys(
            update = update(),
            renderVersion = 0,
            worthTesting = { key ->
                probed += key
                key.zoom == EXPLORATION_RENDER_ZOOM
            },
        )

        assertTrue(
            "the scan really does span every zoom, which is why filtering it matters",
            probed.map(FogTileKey::zoom).distinct().size > 1,
        )
        assertEquals(
            "a rejected candidate must be absent from the result; reporting one anyway would " +
                "hand back a key that was never actually compared",
            setOf(EXPLORATION_RENDER_ZOOM),
            affected.map(FogTileKey::zoom).toSet(),
        )
    }

    /**
     * Source pin for the two lines the behavioural tests cannot fully defend.
     *
     * Deleting `::worthInvalidating` turns the two cache tests above red, but deleting
     * `ensureActive()` leaves every assertion green on a cold cache and every rendered pixel
     * identical: its only symptom is that one wedged surface covers every later map in the
     * process, which no unit test observes. The pin also fixes the cancellation point's position,
     * which no behavioural test can see either.
     */
    @Test
    fun theMergeKeepsItsCacheFilterAndItsCancellationPoint() {
        val body = mergeBody()

        assertTrue(
            "mergePersistedReveals must ask the cache before paying for the exact scan",
            body.contains("worthInvalidating"),
        )
        assertTrue(
            "mergePersistedReveals must stay cancellable; without a suspension point the " +
                "coordinator mutex is held past the caller's timeout and starves every other " +
                "surface in the process",
            body.contains("ensureActive()"),
        )
        assertFalse(
            "the per-update loop must keep the cancellation point inside it, not before it",
            Regex("""ensureActive\(\)[\s\S]*?updates\.forEach""").containsMatchIn(body),
        )
        // The loop explains itself in a comment before the cancellation point; strip line
        // comments so the pin reads the statements, not the prose.
        val loopCode = body.replace(Regex("""//[^\n]*"""), "")
        assertTrue(
            "the cancellation point must be the first statement of the per-update loop, so no " +
                "update's exact scan is paid for after the caller has given up",
            Regex(
                """updates\.forEach\s*\{\s*update\s*->\s*""" +
                    """currentCoroutineContext\(\)\.ensureActive\(\)""",
            ).containsMatchIn(loopCode),
        )
    }

    private fun mergeBody(): String {
        val source = File(
            repositoryRoot(),
            "app/src/main/java/app/trailveil/map/fog/FogViewportCoordinator.kt",
        ).readText()
        val start = source.indexOf("suspend fun mergePersistedReveals")
        assertTrue("the coordinator must declare mergePersistedReveals", start >= 0)
        val text = source.substring(start)
        val open = text.indexOf('{')
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

    private fun repositoryRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(cwd, "settings.gradle.kts").isFile) cwd else requireNotNull(cwd.parentFile)
    }

    private fun update() = FogRevealUpdate(previousInSegment = PREVIOUS, current = CURRENT)

    private fun coordinator(): FogViewportCoordinator {
        val style = FogRenderStyle()
        return FogViewportCoordinator(
            trackDataSource = ViewportTrackDataSource(
                ViewportTrackPointReader { _, _, _ -> emptyList() },
            ),
            pipeline = FogTilePipeline(
                memoryCache = FogMemoryTileCache(maxBytes = 4L * 1024L * 1024L),
                diskCache = null,
                renderMask = FogTileRenderer(style)::render,
            ),
            style = style,
        )
    }

    private companion object {
        val CURRENT = GeoPoint(latitude = 25.0330, longitude = 121.5654)
        val PREVIOUS = GeoPoint(latitude = 25.0329, longitude = 121.5653)

        const val EXPLORATION_MAP_ZOOM = 14.0

        /** `renderZoom` floors, so [EXPLORATION_MAP_ZOOM] warms exactly this tile zoom. */
        const val EXPLORATION_RENDER_ZOOM = 14
    }
}
